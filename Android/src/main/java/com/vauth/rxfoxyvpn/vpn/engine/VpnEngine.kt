package com.vauth.rxfoxyvpn.vpn.engine

/** Cumulative counters and instantaneous rates reported by a running engine. */
data class TrafficSample(val uploadTotal: Long, val downloadTotal: Long, val uploadRate: Long, val downloadRate: Long)

/**
 * Contract between the service (state, tokens, recovery) and the native VPN core.
 * Nothing outside the `vpn.engine` package may touch libbox types.
 */
interface VpnEngine {
    /** True while the native core reports an active service. */
    val isRunning: Boolean

    /** Called (from a native thread) when the core stops on its own. Not called for stop() requested by us. */
    var onCoreStopped: (() -> Unit)?

    /** Called with fresh statistics roughly every 2 s while running. */
    var onTraffic: ((TrafficSample) -> Unit)?

    /** Validate [configJson] with the native checker, set the credential, open the TUN and start the core. */
    suspend fun start(configJson: String, bearerToken: String)

    /** Swap the bearer credential in place; no restart. */
    fun updateBearerToken(token: String)

    /** Tell the core the underlying network changed. */
    fun notifyUnderlyingNetworkChanged()

    /** Abort a start() that is blocked in native code. Safe to call from any thread. */
    fun interruptStart()

    /** Stop the core and release every native resource, including the TUN descriptor. Idempotent. */
    suspend fun stop()
}

/** What the platform layer needs from the hosting VpnService. Keeps the engine free of service internals. */
interface EngineHost {
    val vpnService: android.net.VpnService
    val appPackageName: String
    val nativeProtectionRequired: Boolean
    fun firstUsableNetwork(): android.net.Network?
    fun onTunOpened()
    fun onCoreRequestedStop()
}
