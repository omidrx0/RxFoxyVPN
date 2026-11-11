package com.vauth.rxfoxyvpn.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

open class FastlyChallengeError(message: String, cause: Throwable? = null) : Exception(message, cause)

class FastlyCaptchaRequiredException(message: String) : FastlyChallengeError(message)

class CaptchaCancelledException : CancellationException("CAPTCHA verification cancelled by user")

/** User action returned from the Direct Login interactive CAPTCHA step. */
sealed interface CaptchaUserAction {
    data class Submit(val answer: String) : CaptchaUserAction
    data object Refresh : CaptchaUserAction
    data object Cancel : CaptchaUserAction
}

/** Contextual reason shown above/below the CAPTCHA image when Fastly issues or replaces a prompt. */
enum class CaptchaNoticeKind {
    WRONG_ANSWER,
    EXPIRED,
    DECODE_FAILED,
    REFRESH_FAILED,
}

data class CaptchaImagePayload(
    val imageBytes: ByteArray,
    val mimeType: String,
    val round: Int,
    val notice: CaptchaNoticeKind? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CaptchaImagePayload) return false
        return imageBytes.contentEquals(other.imageBytes) &&
            mimeType == other.mimeType &&
            round == other.round &&
            notice == other.notice
    }

    override fun hashCode(): Int {
        var result = imageBytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + round
        result = 31 * result + (notice?.hashCode() ?: 0)
        return result
    }
}

fun interface CaptchaSessionHandler {
    suspend fun onCaptchaRequired(payload: CaptchaImagePayload): CaptchaUserAction
}

data class DecodedCaptchaImage(
    val bytes: ByteArray,
    val mimeType: String,
)

/**
 * Validates and decodes Fastly's `data:image/<format>;base64,<payload>` CAPTCHA payload
 * (ported and adapted from Reference Project #2 `core/internal/fxa/challenge.go`).
 */
object CaptchaDataUriDecoder {
    private const val MAX_CAPTCHA_IMAGE_BYTES = 2 * 1024 * 1024

    fun decode(rawImageField: String): Result<DecodedCaptchaImage> {
        val trimmed = rawImageField.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(FastlyChallengeError("CAPTCHA image payload is empty"))
        }

        val mimeType: String
        val base64Part: String
        if (trimmed.startsWith("data:", ignoreCase = true)) {
            val commaIdx = trimmed.indexOf(',')
            if (commaIdx <= 5 || commaIdx >= trimmed.length - 1) {
                return Result.failure(FastlyChallengeError("Invalid CAPTCHA data URI format"))
            }
            val header = trimmed.substring(5, commaIdx).trim()
            val semiIdx = header.indexOf(';')
            val rawMime = (if (semiIdx >= 0) header.substring(0, semiIdx) else header).trim().lowercase()
            if (!rawMime.startsWith("image/")) {
                return Result.failure(FastlyChallengeError("Unsupported CAPTCHA media type"))
            }
            mimeType = rawMime
            base64Part = trimmed.substring(commaIdx + 1).trim()
        } else {
            return Result.failure(FastlyChallengeError("Unrecognized CAPTCHA challenge data prefix"))
        }

        val bytes = try {
            Base64.getMimeDecoder().decode(base64Part)
        } catch (e: IllegalArgumentException) {
            return Result.failure(FastlyChallengeError("Corrupted Base64 CAPTCHA image data"))
        }

        if (bytes.isEmpty() || bytes.size > MAX_CAPTCHA_IMAGE_BYTES) {
            return Result.failure(FastlyChallengeError("Invalid decoded CAPTCHA image size"))
        }
        if (!looksLikeSupportedImage(bytes, mimeType)) {
            return Result.failure(FastlyChallengeError("Decoded CAPTCHA payload is not a valid image"))
        }

        return Result.success(DecodedCaptchaImage(bytes = bytes, mimeType = mimeType))
    }

    private fun looksLikeSupportedImage(bytes: ByteArray, mimeType: String): Boolean {
        if (mimeType.contains("svg")) {
            val head = bytes.take(256).toByteArray().toString(Charsets.UTF_8).trimStart().lowercase()
            return head.startsWith("<svg") || head.startsWith("<?xml")
        }
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) return true // PNG
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        ) return true // JPEG
        if (bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()
        ) return true // GIF
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) return true // WebP
        if (bytes.size >= 2 &&
            bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()
        ) return true // BMP
        return false
    }
}

private const val HTTP_CONNECT_TIMEOUT_SECONDS = 20L
private const val HTTP_READ_TIMEOUT_SECONDS = 30L
private const val HTTP_CALL_TIMEOUT_SECONDS = 30L

private const val MAX_POST_BACK_ROUNDS = 8
private const val MAX_CAPTCHA_SESSION_RESTARTS = 8

/** Must match [ControlPlaneHttp.MOZILLA_VPN_USER_AGENT] because Fastly binds clearance cookies to User-Agent + IP. */
private const val SOLVER_USER_AGENT = MOZILLA_VPN_USER_AGENT

private const val POW_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

private val challengeSolveMutex = Mutex()

private val DEFAULT_CHALLENGE_HOSTS = listOf(
    "https://api.accounts.firefox.com",
    "https://accounts.firefox.com",
)

private val FXA_COOKIE_HOSTS = listOf(
    "accounts.firefox.com",
    "api.accounts.firefox.com",
    "oauth.accounts.firefox.com",
)

private val EXPIRED_POST_BACK_CODES = setOf(400, 401, 403, 406, 410, 422)

private const val HTML_ACCEPT =
    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

private val CHALLENGE_PREFIX_REGEX = Regex("/_fs-ch-[A-Za-z0-9]+")

/**
 * Solves Fastly's client challenge (`/_fs-ch-...`) in front of Firefox Accounts, including
 * interactive manual CAPTCHA presentation, refresh, wrong-answer retry, and expired-session
 * recovery within the exact same authentication flow.
 */
class FastlyChallengeSolver(
    private val cookieJar: SimpleCookieJar,
    private val baseClientProvider: () -> OkHttpClient = { ControlPlaneHttp.directClient },
    private val challengeHosts: List<String> = DEFAULT_CHALLENGE_HOSTS,
    private val cookieHosts: List<String> = FXA_COOKIE_HOSTS,
) {

    private class NoChallengePage : Exception()

    suspend fun solveAndInstall(captchaHandler: CaptchaSessionHandler? = null) = withContext(Dispatchers.IO) {
        challengeSolveMutex.withLock {
            var lastError: Exception? = null
            for (base in challengeHosts) {
                try {
                    solveOnHost(base.trimEnd('/'), captchaHandler)
                    return@withLock
                } catch (_: NoChallengePage) {
                    continue
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: FastlyChallengeError) {
                    lastError = e
                } catch (io: IOException) {
                    lastError = FastlyChallengeError("network error while solving security check", io)
                }
            }
            throw lastError ?: FastlyChallengeError("host did not serve a Fastly challenge page")
        }
    }

    private fun buildSolverClient(solverJar: SimpleCookieJar): OkHttpClient =
        baseClientProvider().newBuilder()
            .cookieJar(solverJar)
            .connectTimeout(HTTP_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(HTTP_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(HTTP_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(HTTP_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    private fun baseOrigin(prefixUrl: String): String {
        val idx = prefixUrl.indexOf("/_fs-ch-")
        return if (idx > 0) prefixUrl.substring(0, idx) else prefixUrl
    }

    private fun solvePow(base: String, targetHex: String): String? {
        val target = try {
            targetHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            return null
        }
        if (target.size != 32) return null
        val digest = MessageDigest.getInstance("SHA-256")
        for (a in POW_ALPHABET) {
            for (b in POW_ALPHABET) {
                val suffix = "$a$b"
                val hash = digest.digest((base + suffix).toByteArray(Charsets.UTF_8))
                if (hash.contentEquals(target)) return suffix
            }
        }
        return null
    }

    private fun fetchText(
        client: OkHttpClient,
        url: String,
        accept: String = HTML_ACCEPT,
    ): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", SOLVER_USER_AGENT)
            .header("Accept-Encoding", "identity")
            .header("Accept", accept)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw FastlyChallengeError("challenge endpoint returned HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun fetchChallengePage(client: OkHttpClient, pageUrl: String): Pair<String, Boolean> {
        val body = fetchText(client, pageUrl)
        val isChallenge = body.contains("/_fs-ch-") && body.contains("Client Challenge")
        return body to isChallenge
    }

    private fun fetchPat(client: OkHttpClient, prefixUrl: String, token: String): String {
        val patUrl = "$prefixUrl/pat?token=${URLEncoder.encode(token, "UTF-8")}"
        val request = Request.Builder()
            .url(patUrl)
            .header("Accept", "text/plain")
            .header("Content-Type", "application/json")
            .header("User-Agent", SOLVER_USER_AGENT)
            .header("Accept-Encoding", "identity")
            .header("Origin", baseOrigin(prefixUrl))
            .post(ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 400 || response.code == 401) return ""
            if (!response.isSuccessful) throw FastlyChallengeError("PAT request returned HTTP ${response.code}")
            val json = try {
                JSONObject(response.body?.string().orEmpty())
            } catch (_: Exception) {
                throw FastlyChallengeError("could not parse PAT response")
            }
            val auth = json.optString("auth", "")
            if (auth.isEmpty()) throw FastlyChallengeError("empty PAT auth token")
            return auth
        }
    }

    private fun clientMetricsAnswer(): JSONObject = JSONObject().apply {
        put("ty", "clientmetrics")
        put("webdriver", false)
        put(
            "bot_detection_result",
            JSONObject().apply {
                put("bot_detected", false)
                put("bot_kind", JSONObject.NULL)
            },
        )
        put(
            "browser_metrics",
            JSONObject().apply {
                put("client_data", "{}")
                put("error_trace", JSONObject.NULL)
            },
        )
        put("detector_results", JSONObject())
        put("v", 2)
    }

    private fun solveAutomaticTask(
        client: OkHttpClient,
        prefixUrl: String,
        token: String,
        challenge: JSONObject,
    ): JSONObject {
        val data = challenge.optJSONObject("data") ?: JSONObject()
        return when (val type = challenge.optString("ty")) {
            "pow" -> {
                val base = data.optString("base", "")
                val answer = solvePow(base, data.optString("hash", ""))
                    ?: throw FastlyChallengeError("no proof-of-work solution found")
                JSONObject().apply {
                    put("ty", "pow")
                    put("base", base)
                    put("answer", answer)
                    put("hmac", data.optString("hmac", ""))
                    put("expires", data.optString("expires", ""))
                }
            }
            "pat" -> JSONObject().apply {
                put("ty", "pat")
                put("auth", fetchPat(client, prefixUrl, token))
            }
            "clientmetrics" -> clientMetricsAnswer()
            else -> throw FastlyChallengeError("unsupported Fastly challenge type '$type'")
        }
    }

    private fun installChallengeCookies(solverJar: SimpleCookieJar, pageUrl: String) {
        val page = pageUrl.toHttpUrl()
        val cookies = solverJar.snapshotFor(page)
        if (cookies.isEmpty()) {
            throw FastlyChallengeError("challenge completed but no clearance cookies were issued")
        }
        // Re-issue each clearance cookie as a host-only cookie across all FxA origins
        // (accounts, api.accounts, oauth.accounts) plus the actual challenge host so the
        // subsequent Direct Login / 2FA / OAuth replay shares the exact verified session.
        val targets = LinkedHashSet<String>(cookieHosts)
        targets.add(page.host)
        val installed = ArrayList<Cookie>(targets.size * cookies.size)
        for (host in targets) {
            for (cookie in cookies) {
                val builder = Cookie.Builder()
                    .name(cookie.name)
                    .value(cookie.value)
                    .hostOnlyDomain(host)
                    .path("/")
                if (cookie.secure) builder.secure()
                if (cookie.httpOnly) builder.httpOnly()
                if (cookie.persistent) builder.expiresAt(cookie.expiresAt)
                installed.add(builder.build())
            }
        }
        cookieJar.addAll(installed)
    }

    private suspend fun solveOnHost(base: String, captchaHandler: CaptchaSessionHandler?) {
        val pageUrl = "$base/"
        var pendingNotice: CaptchaNoticeKind? = null
        var lastShownCaptcha: CaptchaImagePayload? = null
        var autoRetriedCorruptImage = false
        var sessionRestarts = 0

        while (sessionRestarts < MAX_CAPTCHA_SESSION_RESTARTS) {
            // Fresh isolated cookie jar per challenge session; discarded on refresh/expiry/cancel
            // and copied to the shared control-plane jar ONLY after verified clearance.
            val solverJar = SimpleCookieJar()
            val solver = buildSolverClient(solverJar)

            val initPair: Triple<String, JSONArray, String> = try {
                val (page, isChallenge) = fetchChallengePage(solver, pageUrl)
                if (!isChallenge) {
                    if (sessionRestarts == 0) throw NoChallengePage()
                    // Challenge cleared on a subsequent refresh probe.
                    if (solverJar.snapshotFor(pageUrl.toHttpUrl()).isNotEmpty()) {
                        installChallengeCookies(solverJar, pageUrl)
                    }
                    return
                }
                val prefixMatch = CHALLENGE_PREFIX_REGEX.find(page)
                    ?: throw FastlyChallengeError("challenge asset prefix not found")
                val prefixUrl = base + prefixMatch.value
                val script = fetchText(solver, "$prefixUrl/script.js?reload=true")
                val (ch, tok) = parseFastlyChallengeInit(script, prefixMatch.value)
                Triple(prefixUrl, ch, tok)
            } catch (noPage: NoChallengePage) {
                throw noPage
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val previous = lastShownCaptcha
                if (captchaHandler != null && previous != null && sessionRestarts > 0) {
                    val recoveryAction = captchaHandler.onCaptchaRequired(
                        previous.copy(notice = CaptchaNoticeKind.REFRESH_FAILED)
                    )
                    when (recoveryAction) {
                        is CaptchaUserAction.Cancel -> throw CaptchaCancelledException()
                        is CaptchaUserAction.Refresh,
                        is CaptchaUserAction.Submit -> {
                            sessionRestarts++
                            continue
                        }
                    }
                }
                if (e is FastlyChallengeError) throw e
                throw FastlyChallengeError("failed to load Fastly challenge", e)
            }

            val prefixUrl = initPair.first
            var challenges = initPair.second
            var token = initPair.third
            var submittedCaptchaInSession = false
            var shouldRestartSession = false

            for (round in 0 until MAX_POST_BACK_ROUNDS) {
                val captchaEntry = findCaptchaChallenge(challenges)
                var manualCaptchaAnswer: String? = null

                if (captchaEntry != null) {
                    if (captchaHandler == null) {
                        throw FastlyCaptchaRequiredException(
                            LanguageManager.t(
                                "captcha_required_err",
                                "نیاز به تأیید امنیتی (CAPTCHA) است؛ لطفاً از بخش ورود مستقیم اقدام کنید.",
                            )
                        )
                    }

                    val rawDataUri = captchaEntry.optJSONObject("data")?.optString("image_b64", "").orEmpty()
                    val decoded = CaptchaDataUriDecoder.decode(rawDataUri).getOrNull()
                    if (decoded == null) {
                        if (!autoRetriedCorruptImage && sessionRestarts + 1 < MAX_CAPTCHA_SESSION_RESTARTS) {
                            autoRetriedCorruptImage = true
                            shouldRestartSession = true
                            break
                        }
                        val corruptPrompt = CaptchaImagePayload(
                            imageBytes = ByteArray(0),
                            mimeType = "image/png",
                            round = round + 1,
                            notice = CaptchaNoticeKind.DECODE_FAILED,
                        )
                        when (captchaHandler.onCaptchaRequired(corruptPrompt)) {
                            is CaptchaUserAction.Cancel -> throw CaptchaCancelledException()
                            is CaptchaUserAction.Refresh,
                            is CaptchaUserAction.Submit -> {
                                shouldRestartSession = true
                                break
                            }
                        }
                    } else {
                        autoRetriedCorruptImage = false
                        val notice = when {
                            submittedCaptchaInSession -> CaptchaNoticeKind.WRONG_ANSWER
                            else -> pendingNotice
                        }
                        pendingNotice = null

                        val promptPayload = CaptchaImagePayload(
                            imageBytes = decoded.bytes,
                            mimeType = decoded.mimeType,
                            round = round + 1,
                            notice = notice,
                        )
                        lastShownCaptcha = promptPayload

                        when (val action = captchaHandler.onCaptchaRequired(promptPayload)) {
                            is CaptchaUserAction.Cancel -> throw CaptchaCancelledException()
                            is CaptchaUserAction.Refresh -> {
                                shouldRestartSession = true
                                break
                            }
                            is CaptchaUserAction.Submit -> {
                                manualCaptchaAnswer = action.answer.trim()
                                submittedCaptchaInSession = true
                            }
                        }
                    }
                }

                if (shouldRestartSession) break

                val answers = JSONArray()
                for (i in 0 until challenges.length()) {
                    val item = challenges.getJSONObject(i)
                    if (item.optString("ty") == "captcha") {
                        answers.put(
                            JSONObject().apply {
                                put("ty", "captcha")
                                put("answer", manualCaptchaAnswer.orEmpty())
                            }
                        )
                    } else {
                        answers.put(solveAutomaticTask(solver, prefixUrl, token, item))
                    }
                }

                val postBody = JSONObject().apply {
                    put("token", token)
                    put("data", answers)
                }.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$prefixUrl/fst-post-back")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", SOLVER_USER_AGENT)
                    .header("Accept-Encoding", "identity")
                    .header("Origin", baseOrigin(prefixUrl))
                    .post(postBody)
                    .build()

                var completed = false
                solver.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (submittedCaptchaInSession && response.code in EXPIRED_POST_BACK_CODES) {
                            pendingNotice = CaptchaNoticeKind.EXPIRED
                            shouldRestartSession = true
                            return@use
                        }
                        throw FastlyChallengeError("challenge post-back returned HTTP ${response.code}")
                    }
                    val bodyText = response.body?.string().orEmpty()
                    val body = try {
                        JSONObject(bodyText)
                    } catch (_: Exception) {
                        if (submittedCaptchaInSession) {
                            pendingNotice = CaptchaNoticeKind.EXPIRED
                            shouldRestartSession = true
                            return@use
                        }
                        throw FastlyChallengeError("invalid challenge post-back JSON")
                    }

                    if (body.optString("status") == "success") {
                        val (_, stillChallenged) = fetchChallengePage(solver, pageUrl)
                        if (stillChallenged) {
                            throw FastlyChallengeError("challenge cookie was not accepted on this exit address")
                        }
                        installChallengeCookies(solverJar, pageUrl)
                        completed = true
                        return@use
                    }

                    val nextChallenges = body.optJSONArray("ch")
                    val nextToken = body.optString("tok", "")
                    if (nextChallenges == null || nextChallenges.length() == 0 || nextToken.isEmpty()) {
                        if (submittedCaptchaInSession) {
                            pendingNotice = CaptchaNoticeKind.EXPIRED
                            shouldRestartSession = true
                            return@use
                        }
                        throw FastlyChallengeError("unexpected challenge post-back state")
                    }
                    challenges = nextChallenges
                    token = nextToken
                }

                if (completed) return
                if (shouldRestartSession) break
            }

            if (shouldRestartSession) {
                sessionRestarts++
                continue
            }

            throw FastlyChallengeError("Fastly challenge did not complete within $MAX_POST_BACK_ROUNDS rounds")
        }

        throw FastlyChallengeError("Fastly CAPTCHA session exceeded maximum refresh attempts")
    }

    private fun findCaptchaChallenge(challenges: JSONArray): JSONObject? {
        for (i in 0 until challenges.length()) {
            val item = challenges.optJSONObject(i) ?: continue
            if (item.optString("ty") == "captcha") return item
        }
        return null
    }
}

private val INIT_START_REGEX = Regex("""\binit\s*\(\s*\[""")

/** End (exclusive) of the JSON array/object/string value starting at [start], or -1. Never evaluates script. */
private fun jsonValueEnd(text: String, start: Int): Int {
    if (start >= text.length) return -1
    val first = text[start]
    if (first != '"' && first != '[' && first != '{') return -1
    var depth = 0
    var inString = false
    var i = start
    while (i < text.length) {
        val c = text[i]
        if (inString) {
            if (c == '\\') i++ else if (c == '"') {
                inString = false
                if (depth == 0) return i + 1
            }
        } else when (c) {
            '"' -> inString = true
            '[', '{' -> depth++
            ']', '}' -> {
                depth--
                if (depth == 0) return i + 1
            }
        }
        i++
    }
    return -1
}

private fun hasInitDelimiter(rest: String): Boolean {
    if (rest.startsWith(")")) return true
    return rest.startsWith(",") && rest.substring(1).isNotBlank()
}

private fun hasLegacyInitDelimiter(restIn: String): Boolean {
    var rest = restIn
    while (rest.startsWith("/*")) {
        val close = rest.indexOf("*/", 2)
        if (close < 0) return false
        rest = rest.substring(close + 2).trim()
    }
    return hasInitDelimiter(rest)
}

/**
 * Port of the reference parseChallengeInit: considers every init([...], "token", "prefix") call,
 * newest first; accepts the one whose prefix equals the page's prefix and whose challenge list is
 * valid JSON with a non-empty type on every entry. Tolerates "]" inside strings/nested values.
 */
internal fun parseFastlyChallengeInit(script: String, prefix: String): Pair<JSONArray, String> {
    val starts = INIT_START_REGEX.findAll(script).toList()
    var legacy: Pair<JSONArray, String>? = null
    for (m in starts.asReversed()) {
        val arrayStart = m.range.last
        val arrayEnd = jsonValueEnd(script, arrayStart)
        if (arrayEnd < 0) continue
        val challenges = try { JSONArray(script.substring(arrayStart, arrayEnd)) } catch (_: Exception) { continue }
        var rest = script.substring(arrayEnd).trimStart()
        val strings = ArrayList<String>(2)
        var ok = true
        for (n in 0 until 2) {
            if (!rest.startsWith(",")) { ok = false; break }
            rest = rest.substring(1).trimStart()
            val end = jsonValueEnd(rest, 0)
            if (end < 0 || rest[0] != '"') { ok = false; break }
            val value = try { JSONArray("[" + rest.substring(0, end) + "]").getString(0) } catch (_: Exception) { ok = false; break }
            strings.add(value)
            rest = rest.substring(end).trimStart()
        }
        if (!ok) continue
        val token = strings[0]
        if (strings[1] != prefix || token.isEmpty() || challenges.length() == 0) continue
        if ((0 until challenges.length()).any { challenges.optJSONObject(it)?.optString("ty").isNullOrEmpty() }) continue
        if (hasInitDelimiter(rest)) return challenges to token
        if (legacy == null && hasLegacyInitDelimiter(rest)) legacy = challenges to token
    }
    legacy?.let { return it }
    throw FastlyChallengeError("valid challenge init call not found in script")
}
