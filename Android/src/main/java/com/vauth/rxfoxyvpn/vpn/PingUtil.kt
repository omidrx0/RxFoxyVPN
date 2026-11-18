package com.vauth.rxfoxyvpn.vpn

import com.vauth.rxfoxyvpn.data.SettingsStore
import com.vauth.rxfoxyvpn.vpn.engine.TunnelProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

object PingUtil {
    private const val TIMEOUT_MS = 1_500
    private const val HTTPS_TIMEOUT_S = 5L

    /** TCP connect RTT to the VPN edge (host:port). Useful when the tunnel is not up. */
    suspend fun measureTcpLatencyMs(host: String, port: Int): Int? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(TIMEOUT_MS.toLong()) {
            runCatching {
                val start = System.nanoTime()
                Socket().use { socket ->
                    socket.bind(InetSocketAddress(0))
                    com.vauth.rxfoxyvpn.data.ControlPlaneHttp.socketProtector?.invoke(socket)
                    socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                }
                ((System.nanoTime() - start) / 1_000_000L).toInt()
            }.onFailure {
                com.vauth.rxfoxyvpn.data.AppLogger.d("PingUtil", "tcp ping failed to $host:$port: ${it.message}")
            }.getOrNull()
        }
    }

    /**
     * Real end-to-end latency through the local SOCKS (VPN tunnel): HTTPS to
     * [SettingsStore.DEFAULT_LATENCY_PROBE_URL] / gstatic generate_204.
     */
    suspend fun measureTunnelLatencyMs(
        socksPort: Int,
        probeUrl: String = SettingsStore.DEFAULT_LATENCY_PROBE_URL,
    ): Int? {
        val ms = TunnelProbe.measure(socksPort, probeUrl) ?: return null
        return ms.toInt().coerceAtLeast(1)
    }

    /**
     * Prefer tunnel probe (real path via generate_204) when [tunnelSocksPort] is set;
     * otherwise fall back to TCP edge ping.
     */
    suspend fun measurePreferredMs(
        host: String,
        port: Int,
        tunnelSocksPort: Int? = null,
        probeUrl: String = SettingsStore.DEFAULT_LATENCY_PROBE_URL,
    ): Int? {
        if (tunnelSocksPort != null && tunnelSocksPort in 1..65_535) {
            measureTunnelLatencyMs(tunnelSocksPort, probeUrl)?.let { return it }
        }
        return measureTcpLatencyMs(host, port)
    }
}
