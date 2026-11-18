package com.vauth.rxfoxyvpn.vpn.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * End-to-end liveness/latency check: a real HTTPS request through the core's loopback mixed inbound, so it exercises
 * the whole path (inbound -> routing -> HTTP outbound -> edge). A rejected or silent edge fails it.
 */
object TunnelProbe {
    private val fallbackUrls = listOf("https://connectivitycheck.gstatic.com/generate_204", "https://www.cloudflare.com/cdn-cgi/trace")
    private const val TIMEOUT_S = 8L

    /** Round-trip time in ms of the first URL that answers 2xx, or null if none did (two rounds). */
    suspend fun measure(socksPort: Int, preferredUrl: String? = null): Long? = withContext(Dispatchers.IO) {
        if (socksPort !in 1..65_535) return@withContext null
        val urls = (listOfNotNull(preferredUrl) + fallbackUrls).distinct()
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS).readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_S + 2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        try {
            for (attempt in 1..2) {
                for (url in urls) {
                    val started = System.nanoTime()
                    val ok = runCatching {
                        client.newCall(Request.Builder().url(url).build()).execute().use { it.code in 200..299 }
                    }.getOrDefault(false)
                    if (ok) return@withContext (System.nanoTime() - started) / 1_000_000L
                }
                if (attempt == 1) kotlinx.coroutines.delay(1_000L)
            }
            null
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    suspend fun check(socksPort: Int, preferredUrl: String? = null): Boolean = measure(socksPort, preferredUrl) != null
}
