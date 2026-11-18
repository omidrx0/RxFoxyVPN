package com.vauth.rxfoxyvpn.vpn.engine

import com.vauth.rxfoxyvpn.BuildConfig
import com.vauth.rxfoxyvpn.data.AppLogger
import com.vauth.rxfoxyvpn.data.SettingsStore
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "SingBoxEngine"

/**
 * VpnEngine backed by libbox (sing-box). One instance per service. libbox keeps process-global state (the bearer
 * token, the command socket), so at most one engine may own it at a time; [owner] enforces that.
 */
class SingBoxEngine(
    private val host: EngineHost,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) : VpnEngine {

    override var onCoreStopped: (() -> Unit)? = null
    override var onTraffic: ((TrafficSample) -> Unit)? = null

    private val lock = Any()
    @Volatile private var server: CommandServer? = null
    @Volatile private var platform: SingBoxPlatformInterface? = null
    @Volatile private var monitor: NativeTrafficMonitor? = null
    @Volatile private var running = false
    @Volatile private var stoppingByUs = false

    override val isRunning: Boolean get() = running

    override suspend fun start(configJson: String, bearerToken: String) = withContext(Dispatchers.IO) {
        require(bearerToken.isNotBlank()) { "A proxy pass is required" }
        synchronized(lock) {
            check(server == null) { "sing-box is already running; stop() it first" }
            val prev = owner
            check(prev == null || prev === this@SingBoxEngine) { "another engine instance owns libbox" }
            owner = this@SingBoxEngine
        }
        ensureSetup()
        // A dynamic-bearer outbound refuses to even be constructed (and so checkConfig fails) until a credential is
        // installed, so the token must be set BEFORE validation. It is cleared again on any failure.
        try {
            Libbox.setBearerToken(bearerToken)
            Libbox.checkConfig(configJson)
        } catch (e: Exception) {
            runCatching { Libbox.setBearerToken("") }
            releaseOwnership()
            throw IllegalArgumentException("Invalid VPN configuration: ${e.message}", e)
        }
        val split = settings.splitTunnelMode == SettingsStore.SplitTunnelMode.INCLUDE && !settings.proxyOnlyMode
        val pi = SingBoxPlatformInterface(host, includeOnly = split)
        platform = pi
        stoppingByUs = false
        // Stale control files from a killed process would make the core refuse to start.
        runCatching { File(host.vpnService.filesDir, "command.sock").delete() }
        runCatching { File(host.vpnService.filesDir, "cache.db").delete() }
        val srv = CommandServer(pi, pi)
        server = srv
        try {
            srv.start()
            srv.startOrReloadService(configJson, OverrideOptions())
        } catch (e: Throwable) {
            cleanup()
            throw e
        }
        running = true
        monitor = NativeTrafficMonitor(scope) { s -> onTraffic?.invoke(s) }.also { it.start() }
    }

    override fun updateBearerToken(token: String) {
        require(token.isNotBlank())
        synchronized(lock) { if (owner === this && server != null) Libbox.setBearerToken(token) }
    }

    override fun notifyUnderlyingNetworkChanged() { platform?.notifyDefaultInterface() }

    override fun interruptStart() { runCatching { server?.closeService() } }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        stoppingByUs = true
        cleanup()
    }

    /** Called by the platform interface when the core itself asks to stop. */
    internal fun coreRequestedStop() {
        if (stoppingByUs || !running) return
        running = false
        onCoreStopped?.invoke()
    }

    private fun cleanup() {
        running = false
        monitor?.stop(); monitor = null
        val srv = server; val pi = platform
        server = null; platform = null
        synchronized(lock) { if (owner === this) runCatching { Libbox.setBearerToken("") } }
        runCatching { srv?.closeService() }.onFailure { AppLogger.w(TAG, "closeService failed", it) }
        runCatching { srv?.close() }.onFailure { AppLogger.w(TAG, "command server close failed", it) }
        runCatching { pi?.closeTun() }.onFailure { AppLogger.w(TAG, "closing TUN failed", it) }
        releaseOwnership()
    }

    private fun releaseOwnership() { synchronized(lock) { if (owner === this) owner = null } }

    private fun ensureSetup() {
        synchronized(setupLock) {
            if (libboxReady) return
            val svc = host.vpnService
            Libbox.setup(SetupOptions().apply {
                basePath = svc.filesDir.absolutePath
                workingPath = svc.filesDir.absolutePath
                tempPath = svc.cacheDir.absolutePath
                logMaxLines = 2000
                debug = BuildConfig.DEBUG
            })
            libboxReady = true
        }
    }

    private companion object {
        private val setupLock = Any()
        @Volatile private var libboxReady = false
        @Volatile private var owner: SingBoxEngine? = null
    }
}
