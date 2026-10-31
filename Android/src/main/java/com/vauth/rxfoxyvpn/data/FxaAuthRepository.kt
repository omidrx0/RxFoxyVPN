package com.vauth.rxfoxyvpn.data

import com.vauth.rxfoxyvpn.data.model.RuntimeAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val TAG = "FxaAuthRepository"

enum class LoginStep { CREDENTIALS, TWO_FACTOR }

enum class SessionStatus {
    ACTIVE,
    NEEDS_LOGIN,
    UNREACHABLE,
}

class FxaApiError(message: String, val errno: Int?, val statusCode: Int?, val accountEmail: String? = null) : Exception(message)

class FxaRefreshFailure(
    message: String,
    val permanent: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause)

private const val FXA_AUTH_SERVER = "https://api.accounts.firefox.com/v1"
private const val FIREFOX_CLIENT_ID = "5882386c6d801776"
private const val OAUTH_SCOPE = "profile https://identity.mozilla.com/apps/vpn"
private const val PROTOCOL_VERSION = "identity.mozilla.com/picl/v1/"
private const val PBKDF2_ROUNDS = 1000
private const val STRETCHED_PW_LEN = 32
private const val HKDF_LEN = 32
private const val VERIFICATION_METHOD_EMAIL_2FA = "email-2fa"
private const val FXA_ERRNO_INVALID_PARAMETER = 107
private const val FXA_MAX_CHALLENGE_ATTEMPTS = 5
private const val DEFAULT_ACCESS_TOKEN_TTL_SECONDS = 24 * 60 * 60

private val jsonMediaType = "application/json".toMediaType()

class FxaAuthRepository(
    private val tokenStore: TokenStore,
    private val challengeSolver: FastlyChallengeSolver = FastlyChallengeSolver(ControlPlaneHttp.cookieJar),
) {
    private var pendingSessionToken: String? = null
    private var pendingVerificationMethod: String = "email-2fa"

    suspend fun startLogin(email: String, password: String): Result<Boolean> = runCatching {
        val normalizedEmail = normalizeEmail(email)
        val data = try {
            loginAttempt(normalizedEmail, password)
        } catch (caseError: FxaApiError) {
            val correctedEmail = caseError.accountEmail
            if (caseError.errno == 120 && !correctedEmail.isNullOrBlank() && correctedEmail != normalizedEmail) {
                loginAttempt(correctedEmail, password, originalEmail = normalizedEmail)
            } else {
                throw caseError
            }
        }
        val sessionToken = data.getString("sessionToken")
        pendingSessionToken = sessionToken
        pendingVerificationMethod = data.optString("verificationMethod", "email-2fa")
        val verified = data.optBoolean("verified", false)
        if (!verified) {
            true
        } else {
            completeLogin(sessionToken)
            false
        }
    }

    suspend fun submitTwoFactorCode(code: String): Result<Boolean> = runCatching {
        val sessionToken = pendingSessionToken ?: error("No pending FxA session. Start sign-in again.")
        val verifyPath = if (pendingVerificationMethod == "totp-2fa") {
            "/session/verify/totp"
        } else {
            "/session/verify_code"
        }
        fxaDo(
            "POST",
            verifyPath,
            sessionToken = sessionToken,
            jsonBody = JSONObject().put("code", normalizeCode(code)),
        )
        completeLogin(sessionToken)
        false
    }

    suspend fun restoreSession(): SessionStatus {
        val stored = runCatching { tokenStore.loadAuth() }.getOrNull()
            ?: return SessionStatus.NEEDS_LOGIN
        if (stored.refreshToken == null && !tokenStore.hasValidAccessToken()) {
            runCatching { tokenStore.clear() }
            return SessionStatus.NEEDS_LOGIN
        }
        if (tokenStore.hasValidAccessToken()) return SessionStatus.ACTIVE

        return try {
            ensureFreshAccessToken()
            SessionStatus.ACTIVE
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: FxaRefreshFailure) {
            if (failure.permanent) {
                AppLogger.w(TAG, "FxA rejected the stored session; signing out", failure)
                runCatching { tokenStore.clear() }
                SessionStatus.NEEDS_LOGIN
            } else {
                AppLogger.w(TAG, "could not renew the session right now; staying signed in", failure)
                SessionStatus.UNREACHABLE
            }
        } catch (unexpected: Exception) {
            AppLogger.w(TAG, "unexpected error while restoring the session", unexpected)
            SessionStatus.UNREACHABLE
        }
    }

    suspend fun ensureFreshAccessToken(force: Boolean = false): RuntimeAuth {
        val current = tokenStore.loadAuth()
            ?: throw FxaRefreshFailure("Not signed in", permanent = true)
        if (!force && tokenStore.hasValidAccessToken()) return current

        val refreshToken = current.refreshToken
            ?: throw FxaRefreshFailure(
                "The stored session has no refresh token; sign in again.",
                permanent = true,
            )

        val body = JSONObject().apply {
            put("client_id", FIREFOX_CLIENT_ID)
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
            put("scope", OAUTH_SCOPE)
        }

        val tokenData = try {
            fxaDo("POST", "/oauth/token", jsonBody = body)
        } catch (rejected: FxaApiError) {
            throw FxaRefreshFailure(
                rejected.message ?: "FxA rejected the refresh token",
                permanent = rejected.isPermanentRefreshRejection(),
                cause = rejected,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (transport: Exception) {
            throw FxaRefreshFailure(
                transport.message ?: "Could not reach the Firefox Accounts server",
                permanent = false,
                cause = transport,
            )
        }

        val accessToken = tokenData.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw FxaRefreshFailure(
                "FxA returned no access token for the refresh grant",
                permanent = false,
            )

        val renewed = RuntimeAuth(
            accessToken = accessToken,
            refreshToken = tokenData.optString("refresh_token", "").ifBlank { null } ?: refreshToken,
            expiresAtEpochSeconds = expiryFrom(tokenData),
        )
        runCatching { tokenStore.saveAuth(renewed) }
            .onFailure { AppLogger.w(TAG, "could not persist the renewed token", it) }
        return renewed
    }

    suspend fun currentAccessToken(): String? = try {
        ensureFreshAccessToken().accessToken
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: FxaRefreshFailure) {
        if (failure.permanent) null else tokenStore.loadAuth()?.accessToken
    }

    suspend fun refreshAccessToken(): RuntimeAuth? = try {
        ensureFreshAccessToken(force = true)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: FxaRefreshFailure) {
        AppLogger.w(TAG, "access token renewal failed", failure)
        null
    }

    private fun FxaApiError.isPermanentRefreshRejection(): Boolean {
        return statusCode == 400 || statusCode == 401 || statusCode == 403
    }

    private fun expiryFrom(tokenData: JSONObject): Long {
        val expiresIn = tokenData.optInt("expires_in", 0)
            .takeIf { it > 0 } ?: DEFAULT_ACCESS_TOKEN_TTL_SECONDS
        return System.currentTimeMillis() / 1000 + expiresIn
    }

    private suspend fun completeLogin(sessionToken: String) {
        val tokenData = oauthToken(sessionToken)
        tokenStore.saveAuth(
            RuntimeAuth(
                accessToken = tokenData.getString("access_token"),
                refreshToken = tokenData.optString("refresh_token", "").ifBlank { null },
                expiresAtEpochSeconds = expiryFrom(tokenData),
            ),
        )
        // Firefox's reference client destroys the temporary FxA session after
        // exchanging it for OAuth credentials. Failure here must not undo login.
        runCatching {
            fxaDo("POST", "/session/destroy", sessionToken = sessionToken, jsonBody = JSONObject())
        }
        pendingSessionToken = null
        pendingVerificationMethod = "email-2fa"
    }

    private suspend fun loginAttempt(
        email: String,
        password: String,
        originalEmail: String? = null,
        unblockCode: String? = null,
        withVerificationMethod: Boolean = true,
    ): JSONObject {
        val body = JSONObject().apply {
            put("email", email)
            put("authPW", deriveAuthPw(email, password))
            if (!originalEmail.isNullOrBlank()) put("originalLoginEmail", originalEmail)
            if (!unblockCode.isNullOrBlank()) put("unblockCode", normalizeCode(unblockCode).uppercase(Locale.ROOT))
            if (withVerificationMethod) put("verificationMethod", VERIFICATION_METHOD_EMAIL_2FA)
        }
        return try {
            fxaDo("POST", "/account/login", jsonBody = body)
        } catch (e: FxaApiError) {
            if (e.errno == FXA_ERRNO_INVALID_PARAMETER && withVerificationMethod) {
                loginAttempt(email, password, originalEmail, unblockCode, withVerificationMethod = false)
            } else {
                throw e
            }
        }
    }

    /** Request the email containing FxA's unblock code after errno 125. */
    suspend fun sendUnblockCode(email: String): Result<Unit> = runCatching {
        fxaDo(
            "POST",
            "/account/login/send_unblock_code",
            jsonBody = JSONObject().put("email", normalizeEmail(email)),
        )
    }

    /** Retry a blocked sign-in with the code sent by Firefox Accounts. */
    suspend fun startLoginWithUnblockCode(email: String, password: String, code: String): Result<Boolean> = runCatching {
        val normalizedEmail = normalizeEmail(email)
        val normalizedCode = normalizeCode(code).uppercase(Locale.ROOT)
        val data = try {
            loginAttempt(normalizedEmail, password, unblockCode = normalizedCode)
        } catch (caseError: FxaApiError) {
            val correctedEmail = caseError.accountEmail
            if (caseError.errno == 120 && !correctedEmail.isNullOrBlank() && correctedEmail != normalizedEmail) {
                loginAttempt(
                    correctedEmail,
                    password,
                    originalEmail = normalizedEmail,
                    unblockCode = normalizedCode,
                )
            } else {
                throw caseError
            }
        }
        val sessionToken = data.getString("sessionToken")
        pendingSessionToken = sessionToken
        pendingVerificationMethod = data.optString("verificationMethod", "email-2fa")
        if (!data.optBoolean("verified", false)) true else {
            completeLogin(sessionToken)
            false
        }
    }

    private suspend fun oauthToken(sessionToken: String): JSONObject {
        val body = JSONObject().apply {
            put("client_id", FIREFOX_CLIENT_ID)
            put("grant_type", "fxa-credentials")
            put("scope", OAUTH_SCOPE)
            put("access_type", "offline")
        }
        return fxaDo("POST", "/oauth/token", sessionToken = sessionToken, jsonBody = body)
    }

    private suspend fun fxaDo(
        method: String,
        path: String,
        sessionToken: String? = null,
        jsonBody: JSONObject,
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = "$FXA_AUTH_SERVER$path".toHttpUrl()
        val bodyBytes = jsonBody.toString().toByteArray(Charsets.UTF_8)
        var tokenId: String? = null
        var hmacKey: ByteArray? = null
        if (sessionToken != null) {
            val (id, key) = deriveHawkCredentials(sessionToken)
            tokenId = id
            hmacKey = key
        }

        fun buildRequest(): Request {
            val builder = Request.Builder().url(url).applyMozillaVpnHeaders()
            builder.header("Content-Type", "application/json")
            if (sessionToken != null) {
                builder.header("Authorization", hawkHeader(method, url, tokenId!!, hmacKey!!, bodyBytes))
            }
            builder.method(method, bodyBytes.toRequestBody(jsonMediaType))
            return builder.build()
        }

        var response: Response = ControlPlaneHttp.client.newCall(buildRequest()).execute()
        var challengeAttempts = 0
        while (response.code == 406 && challengeAttempts < FXA_MAX_CHALLENGE_ATTEMPTS) {
            response.close()
            challengeSolver.solveAndInstall()
            challengeAttempts++
            response = ControlPlaneHttp.client.newCall(buildRequest()).execute()
        }

        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code >= 400) {
                val errorBody = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    JSONObject()
                }
                val errno = if (errorBody.has("errno")) errorBody.optInt("errno") else null
                val message = errorBody.optString("message", text.ifEmpty { "HTTP ${resp.code}" })
                val accountEmail = errorBody.optString("email", "").ifBlank { null }
                throw FxaApiError(message, errno, resp.code, accountEmail)
            }
            if (text.isEmpty()) return@withContext JSONObject()
            return@withContext JSONObject(text)
        }
    }

    private fun deriveQuickStretch(email: String, password: String): ByteArray {
        val salt = "${PROTOCOL_VERSION}quickStretch:$email".toByteArray(Charsets.UTF_8)
        return pbkdf2HmacSha256(password.toByteArray(Charsets.UTF_8), salt, PBKDF2_ROUNDS, STRETCHED_PW_LEN)
    }

    private fun deriveAuthPw(email: String, password: String): String {
        val quickStretched = deriveQuickStretch(email, password)
        return hkdf(quickStretched, "${PROTOCOL_VERSION}authPW", HKDF_LEN).toHex()
    }

    private fun deriveHawkCredentials(sessionTokenHex: String): Pair<String, ByteArray> {
        val sessionToken = sessionTokenHex.hexToBytes()
        val expanded = hkdf(sessionToken, "${PROTOCOL_VERSION}sessionToken", 64)
        return expanded.copyOfRange(0, 32).toHex() to expanded.copyOfRange(32, 64)
    }

    private fun hawkHeader(method: String, url: HttpUrl, tokenId: String, hmacKey: ByteArray, body: ByteArray): String {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val nonceBytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
        val nonce = nonceBytes.toHex()
        val path = url.encodedPath + if (url.encodedQuery != null) "?${url.encodedQuery}" else ""

        var payloadHash = ""
        if (body.isNotEmpty()) {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("hawk.1.payload\napplication/json\n".toByteArray(Charsets.UTF_8))
            digest.update(body)
            digest.update("\n".toByteArray(Charsets.UTF_8))
            payloadHash = digest.digest().toHex()
        }

        val normalized = "hawk.1.header\n$ts\n$nonce\n${method.uppercase()}\n$path\n${url.host}\n${url.port}\n$payloadHash\n\n"

        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(hmacKey, "HmacSHA256")) }
        val macHex = mac.doFinal(normalized.toByteArray(Charsets.UTF_8)).toHex()

        var header = "Hawk id=\"$tokenId\", ts=\"$ts\", nonce=\"$nonce\", mac=\"$macHex\""
        if (payloadHash.isNotEmpty()) header += ", hash=\"$payloadHash\""
        return header
    }
}

private fun normalizeEmail(value: String): String =
    value.filterNot {
        it.isWhitespace() ||
            Character.getType(it) == Character.FORMAT.toInt() ||
            Character.getType(it) == Character.SPACE_SEPARATOR.toInt()
    }

private fun normalizeCode(value: String): String = buildString {
    for (ch in value) {
        when (ch) {
            in '۰'..'۹' -> append(('0'.code + (ch.code - '۰'.code)).toChar())
            in '٠'..'٩' -> append(('0'.code + (ch.code - '٠'.code)).toChar())
            else -> if (
                !ch.isWhitespace() &&
                Character.getType(ch) != Character.FORMAT.toInt() &&
                Character.getType(ch) != Character.SPACE_SEPARATOR.toInt()
            ) append(ch)
        }
    }
}

private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

private fun hkdf(ikm: ByteArray, info: String, length: Int, salt: ByteArray = ByteArray(32)): ByteArray {
    val prk = hmacSha256(salt, ikm)
    val infoBytes = info.toByteArray(Charsets.UTF_8)
    val result = ByteArray(length)
    var previousBlock = ByteArray(0)
    var generated = 0
    var counter = 1
    while (generated < length) {
        val block = hmacSha256(prk, previousBlock + infoBytes + byteArrayOf(counter.toByte()))
        val toCopy = minOf(block.size, length - generated)
        System.arraycopy(block, 0, result, generated, toCopy)
        generated += toCopy
        previousBlock = block
        counter++
    }
    return result
}

private fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray {
    val hLen = 32
    val numBlocks = (keyLengthBytes + hLen - 1) / hLen
    val result = ByteArray(numBlocks * hLen)
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
    for (blockIndex in 1..numBlocks) {
        val blockIndexBytes = ByteBuffer.allocate(4).putInt(blockIndex).array()
        var u = mac.doFinal(salt + blockIndexBytes)
        val t = u.copyOf()
        for (iter in 2..iterations) {
            u = mac.doFinal(u)
            for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
        }
        System.arraycopy(t, 0, result, (blockIndex - 1) * hLen, hLen)
    }
    return result.copyOf(keyLengthBytes)
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
