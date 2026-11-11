package com.vauth.rxfoxyvpn.data.relay

import com.vauth.rxfoxyvpn.data.AppLogger
import com.vauth.rxfoxyvpn.data.ControlPlaneHttp
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Wiring for the control-plane relay. Everything here is built on [ControlPlaneHttp.directClient], so relay,
 * health and manifest sockets get the same VpnService.protect() treatment and the same TLS / hostname verification
 * as every other control-plane request. None of these clients contains the fallback interceptor.
 */
object RelayRuntime {

    private const val TAG = "Relay"

    /** Replaced by [com.vauth.rxfoxyvpn.RxFoxyVpnApp] with the settings-backed source. */
    @Volatile
    var configSource: RelayConfigSource = RelayConfigSource { RelayConfig() }

    val logger = RelayLogger { AppLogger.i(TAG, it) }

    private fun base(connect: Long, read: Long, call: Long): OkHttpClient =
        ControlPlaneHttp.directClient.newBuilder()
            // Relayed (and forwarded) cookies are not applied to the app's cookie jar: a relay must not
            // be able to plant or read Mozilla cookies for the direct path.
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(connect, TimeUnit.SECONDS)
            .readTimeout(read, TimeUnit.SECONDS)
            .writeTimeout(read, TimeUnit.SECONDS)
            .callTimeout(call, TimeUnit.SECONDS)
            .build()

    private val relayClient: OkHttpClient by lazy { base(8, 15, 20) }
    private val healthClient: OkHttpClient by lazy { base(4, 6, 8) }
    private val manifestClient: OkHttpClient by lazy { base(4, 6, 8) }

    val transport: RelayTransport by lazy { RelayTransport(relayClient) }
    val healthChecker: RelayHealthChecker by lazy { RelayHealthChecker(healthClient) }
    val shared: SharedRelayManager by lazy { SharedRelayManager(manifestClient, healthChecker, transport, log = logger) }

    val interceptor: RelayFallbackInterceptor by lazy {
        RelayFallbackInterceptor(
            config = RelayConfigSource { configSource.current() },
            transport = transport,
            shared = shared,
            log = logger,
        )
    }
}
