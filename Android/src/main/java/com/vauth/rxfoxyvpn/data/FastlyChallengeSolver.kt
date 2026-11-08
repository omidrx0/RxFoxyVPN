package com.vauth.rxfoxyvpn.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class FastlyChallengeError(message: String) : Exception(message)

private const val SOLVE_TIMEOUT_SECONDS = 60L

private const val MAX_POST_BACK_ROUNDS = 8

private const val SOLVER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0"

private const val POW_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

private val challengeSolveMutex = Mutex()

private val FXA_COOKIE_HOSTS = listOf("accounts.firefox.com", "api.accounts.firefox.com", "oauth.accounts.firefox.com")

private const val HTML_ACCEPT =
    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

private val CHALLENGE_PREFIX_REGEX = Regex("/_fs-ch-[A-Za-z0-9]+")

class FastlyChallengeSolver(private val cookieJar: SimpleCookieJar) {

    private class NoChallengePage : Exception()

    suspend fun solveAndInstall() = withContext(Dispatchers.IO) {
        challengeSolveMutex.withLock {
            var lastError: Exception? = null
            for (base in listOf("https://api.accounts.firefox.com", "https://accounts.firefox.com")) {
                try {
                    solveOnHost(base)
                    return@withLock
                } catch (_: NoChallengePage) {
                    continue
                } catch (e: FastlyChallengeError) {
                    lastError = e
                }
            }
            throw lastError ?: FastlyChallengeError("host did not serve a Fastly challenge page")
        }
    }

    private fun baseOrigin(prefixUrl: String): String {
        val idx = prefixUrl.indexOf("/_fs-ch-")
        return if (idx > 0) prefixUrl.substring(0, idx) else prefixUrl
    }

    private fun solvePow(base: String, targetHex: String): String? {
        val target = try {
            targetHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
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
            if (!response.isSuccessful) throw FastlyChallengeError("GET $url returned HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun fetchChallengePage(client: OkHttpClient, pageUrl: String): Pair<String, Boolean> {
        val body = fetchText(client, pageUrl)
        val isChallenge = body.contains("/_fs-ch-") && body.contains("Client Challenge")
        return body to isChallenge
    }

    private fun parseChallengeInit(script: String, prefix: String): Pair<JSONArray, String> =
        parseFastlyChallengeInit(script, prefix)

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
            } catch (e: Exception) {
                throw FastlyChallengeError("could not parse PAT response: ${e.message}")
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

    private fun answerChallenge(client: OkHttpClient, prefixUrl: String, token: String, challenge: JSONObject): JSONObject {
        val data = challenge.optJSONObject("data") ?: JSONObject()
        return when (val type = challenge.optString("ty")) {
            "pow" -> {
                val base = data.optString("base", "")
                val answer = solvePow(base, data.optString("hash", ""))
                    ?: throw FastlyChallengeError("no proof-of-work solution found for base $base")
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
            "captcha" -> throw FastlyChallengeError(
                "Fastly requires an image CAPTCHA; this direct-login UI has no interactive CAPTCHA step",
            )
            else -> throw FastlyChallengeError("unsupported Fastly challenge type '$type'")
        }
    }

    private fun installChallengeCookies(solverJar: SimpleCookieJar, pageUrl: String) {
        val page = pageUrl.toHttpUrl()
        val cookies = solverJar.snapshotFor(page)
        if (cookies.isEmpty()) {
            throw FastlyChallengeError("challenge completed but no applicable cookies were issued")
        }
        // Like the reference client: re-issue each cookie as a host-only cookie for every FxA origin
        // (sign-in site, API host, OAuth host). A cookie scoped by Fastly to one host would otherwise
        // never be sent to the API host that answered 406.
        val installed = ArrayList<Cookie>()
        for (host in FXA_COOKIE_HOSTS) {
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

    private fun solveOnHost(base: String) {
        val solverJar = SimpleCookieJar()
        val solver = ControlPlaneHttp.client.newBuilder()
            .cookieJar(solverJar)
            .connectTimeout(SOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(SOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(SOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        val pageUrl = "$base/"
        val (page, isChallenge) = fetchChallengePage(solver, pageUrl)
        if (!isChallenge) throw NoChallengePage()

        val prefixMatch = CHALLENGE_PREFIX_REGEX.find(page)
            ?: throw FastlyChallengeError("challenge asset prefix not found on $base")
        val prefixUrl = base + prefixMatch.value

        val script = fetchText(solver, "$prefixUrl/script.js?reload=true")
        var (challenges, token) = parseChallengeInit(script, prefixMatch.value)

        repeat(MAX_POST_BACK_ROUNDS) {
            val answers = JSONArray()
            for (i in 0 until challenges.length()) {
                answers.put(answerChallenge(solver, prefixUrl, token, challenges.getJSONObject(i)))
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
            solver.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw FastlyChallengeError("challenge post-back returned HTTP ${response.code}")
                val body = JSONObject(response.body?.string().orEmpty())
                if (body.optString("status") == "success") {
                    val (_, stillChallenged) = fetchChallengePage(solver, pageUrl)
                    if (stillChallenged) throw FastlyChallengeError("challenge cookie not accepted on this exit IP")
                    installChallengeCookies(solverJar, pageUrl)
                    return
                }
                val nextChallenges = body.optJSONArray("ch")
                val nextToken = body.optString("tok", "")
                if (nextChallenges == null || nextChallenges.length() == 0 || nextToken.isEmpty()) {
                    throw FastlyChallengeError("unexpected post-back response: $body")
                }
                challenges = nextChallenges
                token = nextToken
            }
        }
        throw FastlyChallengeError("Fastly challenge did not complete within $MAX_POST_BACK_ROUNDS rounds")
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
