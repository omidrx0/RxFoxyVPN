package com.vauth.rxfoxyvpn.data

import com.vauth.rxfoxyvpn.data.model.RuntimeAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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

/** Fastly's bot check refused the call for good (unsolvable challenge, or 406 persisted). Not an FxA API error. */
class FxaChallengeBlocked(message: String, cause: Throwable? = null) : Exception(message, cause)

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
private const val FXA_CHALLENGE_BUDGET_MS = 90_000L
private const val FXA_OAUTH_TOKEN_URL = "https://oauth.accounts.firefox.com/v1/token"
private const val DEFAULT_ACCESS_TOKEN_TTL_SECONDS = 24 * 60 * 60

private val jsonMediaType = "application/json".toMediaType()
// Mozilla's own OAuth requests carry the charset; OkHttp derives Content-Type from the body type.
private val oauthJsonMediaType = "application/json; charset=utf-8".toMediaType()
private const val FXA_OAUTH_AUTHORIZE_PATH = "/oauth/authorization"

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
            fxaDo("POST", "/oauth/token", jsonBody = body, oauthProfile = true, refreshFallback = true)
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
        return fxaDo("POST", "/oauth/token", sessionToken = sessionToken, jsonBody = body, oauthProfile = true, oauthCodeFallback = true)
    }

    private suspend fun fxaDo(
        method: String,
        path: String,
        sessionToken: String? = null,
        jsonBody: JSONObject,
        oauthProfile: Boolean = false,
        oauthCodeFallback: Boolean = false,
        refreshFallback: Boolean = false,
    ): JSONObject {
        val url = "$FXA_AUTH_SERVER$path".toHttpUrl()
        val bodyBytes = jsonBody.toString().toByteArray(Charsets.UTF_8)
        val hawk: Pair<String, ByteArray>? = sessionToken?.let { deriveHawkCredentials(it) }

        // Rebuilt for every attempt: fresh HAWK nonce/timestamp each time.
        val newRequest = { buildFxaRequest(url, method, bodyBytes, oauthProfile, hawk) }

        var on406: (suspend (Int) -> Response?)? = null
        if (oauthCodeFallback && hawk != null) {
            on406 = { _ -> exchangeSessionWithOAuthCode(hawk.first, hawk.second) }
        } else if (refreshFallback) {
            on406 = { solved -> if (solved == 0) refreshAtOAuthEndpoint(bodyBytes) else null }
        }

        val response = sendWith406Recovery(newRequest, on406)
        return withContext(Dispatchers.IO) {
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
                if (text.isEmpty()) JSONObject() else JSONObject(text)
            }
        }
    }

    private fun buildFxaRequest(
        url: HttpUrl,
        method: String,
        bodyBytes: ByteArray,
        oauthProfile: Boolean,
        hawk: Pair<String, ByteArray>?,
    ): Request {
        val builder = Request.Builder().url(url).applyMozillaVpnHeaders()
        if (oauthProfile) builder.applyOAuthProfileHeaders()
        if (hawk != null) builder.header("Authorization", hawkHeader(method, url, hawk.first, hawk.second, bodyBytes))
        return builder.method(method, bodyBytes.toRequestBody(if (oauthProfile) oauthJsonMediaType else jsonMediaType)).build()
    }

    /**
     * Mirrors the reference client's fxaDoWith406Fallback: send; on HTTP 406 (Fastly's bot check)
     * run the optional alternate request, otherwise solve the challenge and resend, bounded by
     * FXA_MAX_CHALLENGE_ATTEMPTS and one overall budget. Cancellation is honoured (the in-flight
     * call is cancelled) and every 406 body is closed before the next attempt.
     */
    private suspend fun sendWith406Recovery(
        newRequest: () -> Request,
        on406: (suspend (Int) -> Response?)?,
    ): Response {
        val client = ControlPlaneHttp.client
        val first = client.newCall(newRequest()).awaitResponse()
        if (first.code != 406) return first
        first.close()
        AppLogger.i(TAG, "account server answered HTTP 406 (Fastly bot check); starting recovery")
        return try {
            withTimeout(FXA_CHALLENGE_BUDGET_MS) {
                var solved = 0
                var result: Response? = null
                while (result == null) {
                    val alternate = on406?.invoke(solved)
                    if (alternate != null) {
                        result = alternate
                        continue
                    }
                    if (solved >= FXA_MAX_CHALLENGE_ATTEMPTS) {
                        throw FxaChallengeBlocked("Mozilla's security check still answers HTTP 406 after $solved solved challenges")
                    }
                    try {
                        challengeSolver.solveAndInstall()
                    } catch (e: FastlyChallengeError) {
                        throw FxaChallengeBlocked("Mozilla's security challenge failed: ${e.message}", e)
                    }
                    solved++
                    val next = client.newCall(newRequest()).awaitResponse()
                    AppLogger.i(TAG, "request retried after challenge solve #$solved: HTTP ${next.code}")
                    if (next.code == 406) next.close() else result = next
                }
                checkNotNull(result)
            }
        } catch (timeout: TimeoutCancellationException) {
            throw FxaChallengeBlocked("Mozilla's security check recovery timed out", timeout)
        }
    }

    /** Session-authenticated authorization-code grant (PKCE), used when fxa-credentials is refused with 406. */
    private suspend fun exchangeSessionWithOAuthCode(tokenId: String, hmacKey: ByteArray): Response? {
        val rng = SecureRandom()
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val verifier = b64.encodeToString(ByteArray(32).also { rng.nextBytes(it) })
        val challenge = b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = b64.encodeToString(ByteArray(16).also { rng.nextBytes(it) })

        val authorizeUrl = "$FXA_AUTH_SERVER$FXA_OAUTH_AUTHORIZE_PATH".toHttpUrl()
        val authorizeBody = JSONObject().apply {
            put("client_id", FIREFOX_CLIENT_ID)
            put("response_type", "code")
            put("scope", OAUTH_SCOPE)
            put("state", state)
            put("access_type", "offline")
            put("code_challenge", challenge)
            put("code_challenge_method", "S256")
        }.toString().toByteArray(Charsets.UTF_8)

        val authorization = ControlPlaneHttp.client
            .newCall(buildFxaRequest(authorizeUrl, "POST", authorizeBody, true, tokenId to hmacKey))
            .awaitResponse()
        val code: String = authorization.use { resp ->
            if (resp.code == 406) {
                AppLogger.i(TAG, "OAuth authorization returned HTTP 406")
                return null
            }
            val text = resp.body?.string().orEmpty()
            if (resp.code != 200) throw oauthCodeError(resp.code, text, "OAuth authorization")
            val json = try { JSONObject(text) } catch (e: Exception) { throw IOException("invalid OAuth authorization response") }
            if (json.optString("code").isEmpty() || json.optString("state") != state) {
                throw IOException("invalid OAuth authorization response")
            }
            json.getString("code")
        }

        val tokenBody = JSONObject().apply {
            put("client_id", FIREFOX_CLIENT_ID)
            put("grant_type", "authorization_code")
            put("code", code)
            put("code_verifier", verifier)
        }.toString().toByteArray(Charsets.UTF_8)
        val tokens = ControlPlaneHttp.client
            .newCall(buildFxaRequest(FXA_OAUTH_TOKEN_URL.toHttpUrl(), "POST", tokenBody, true, null))
            .awaitResponse()
        if (tokens.code == 200) {
            AppLogger.i(TAG, "OAuth authorization-code exchange accepted after HTTP 406")
            return tokens
        }
        tokens.use { resp ->
            if (resp.code == 406) return null
            throw oauthCodeError(resp.code, resp.body?.string().orEmpty(), "OAuth authorization code exchange")
        }
    }

    /** Refresh at Mozilla's OAuth endpoint after the API host answered 406; null lets the challenge path continue. */
    private suspend fun refreshAtOAuthEndpoint(bodyBytes: ByteArray): Response? {
        val response = try {
            ControlPlaneHttp.client
                .newCall(buildFxaRequest(FXA_OAUTH_TOKEN_URL.toHttpUrl(), "POST", bodyBytes, true, null))
                .awaitResponse()
        } catch (e: IOException) {
            return null
        }
        if (response.code == 200) {
            AppLogger.i(TAG, "OAuth refresh accepted by Mozilla's OAuth endpoint after HTTP 406")
            return response
        }
        response.use { resp ->
            when (resp.code) {
                406 -> return null
                400 -> {
                    val text = resp.body?.string().orEmpty()
                    val errno = runCatching { JSONObject(text).optInt("errno") }.getOrDefault(0)
                    if (errno == 108) throw FxaApiError("invalid refresh token", 108, 400)
                }
            }
            throw IOException("Mozilla's OAuth endpoint returned HTTP ${resp.code}")
        }
    }

    private fun oauthCodeError(status: Int, body: String, step: String): FxaApiError {
        val errno = runCatching { JSONObject(body).optInt("errno") }.getOrDefault(0).takeIf { it != 0 }
        return FxaApiError("$step failed", errno, status)
    }

    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    private fun Request.Builder.applyOAuthProfileHeaders(): Request.Builder {
        // Native Firefox OAuth request profile, as sent by the reference client on every grant.
        header("Accept", "application/json;q=0.9,*/*;q=0.2")
        header("Accept-Language", "en-US, en")
        header("Cache-Control", "no-cache")
        header("Pragma", "no-cache")
        header("Sec-Fetch-Dest", "empty")
        header("Sec-Fetch-Mode", "no-cors")
        header("Sec-Fetch-Site", "none")
        header("Priority", "u=4")
        return this
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
