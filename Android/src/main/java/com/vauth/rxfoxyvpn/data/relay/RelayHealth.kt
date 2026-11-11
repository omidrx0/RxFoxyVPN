package com.vauth.rxfoxyvpn.data.relay

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

data class RelayHealthResult(
    val ready: Boolean,
    val failure: RelayFailure? = null,
    val detail: String = "",
    val httpStatus: Int? = null,
    val elapsedMs: Long = 0,
)

/**
 * The relay's authenticated health check: `GET <relay>?secret=S&health=1`.
 * The Worker and the PHP relay answer `{"relay":"rxvpn","version":1,"ready":true}` from the relay itself;
 * no request is made to Mozilla, Guardian or any other host.
 *
 * [client] must not follow redirects and must not carry a [RelayFallbackInterceptor].
 */
class RelayHealthChecker(private val client: OkHttpClient, private val now: () -> Long = System::currentTimeMillis) {

    fun check(relay: HttpUrl): RelayHealthResult {
        val started = now()
        val url = relay.newBuilder().setQueryParameter("health", "1").build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .get().build()
        return try {
            client.newCall(request).execute().use { response ->
                val elapsed = now() - started
                val reason = response.header(RelayPolicy.RELAY_ERROR_HEADER)
                if (reason != null) {
                    val kind = when {
                        response.code == 403 && reason.contains("wrong secret", true) -> RelayFailure.WRONG_SECRET
                        response.code == 503 && reason.contains("secret", true) -> RelayFailure.NOT_SET_UP
                        else -> RelayFailure.REFUSED
                    }
                    return RelayHealthResult(false, kind, "HTTP ${response.code} ${reason.take(80)}", response.code, elapsed)
                }
                if (response.code != 200) {
                    return RelayHealthResult(false, RelayFailure.UNRECOGNISED, "HTTP ${response.code}", response.code, elapsed)
                }
                val text = response.peekBody(4096).string()
                val ok = runCatching {
                    val json = JSONObject(text)
                    json.optString("relay") == "rxvpn" && json.optInt("version", 0) == 1 && json.optBoolean("ready", false)
                }.getOrDefault(false)
                if (ok) {
                    RelayHealthResult(true, null, "ready", 200, elapsed)
                } else {
                    RelayHealthResult(false, RelayFailure.UNRECOGNISED, "health response not recognised", 200, elapsed)
                }
            }
        } catch (e: IOException) {
            RelayHealthResult(false, RelayTransport.failureFor(e), RelayTransport.describe(e), null, now() - started)
        }
    }
}
