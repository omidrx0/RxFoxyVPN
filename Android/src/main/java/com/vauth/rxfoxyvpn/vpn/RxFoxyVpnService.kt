package com.vauth.rxfoxyvpn.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vauth.rxfoxyvpn.MainActivity
import com.vauth.rxfoxyvpn.R
import com.vauth.rxfoxyvpn.RxFoxyVpnApp
import com.vauth.rxfoxyvpn.data.AppLogger
import com.vauth.rxfoxyvpn.data.ControlPlaneHttp
import com.vauth.rxfoxyvpn.data.FxaAuthRepository
import com.vauth.rxfoxyvpn.data.GUARDIAN_ENDPOINT_DEFAULT
import com.vauth.rxfoxyvpn.data.GuardianClient
import com.vauth.rxfoxyvpn.data.LanguageManager
import com.vauth.rxfoxyvpn.data.ProxyPass
import com.vauth.rxfoxyvpn.data.ProxyStateStore
import com.vauth.rxfoxyvpn.data.QuotaExceededError
import com.vauth.rxfoxyvpn.data.ServerListClient
import com.vauth.rxfoxyvpn.data.SettingsStore
import com.vauth.rxfoxyvpn.data.TokenInvalidError
import com.vauth.rxfoxyvpn.data.TokenStore
import com.vauth.rxfoxyvpn.data.error.ErrorClassifier
import com.vauth.rxfoxyvpn.data.error.ErrorCode
import com.vauth.rxfoxyvpn.data.error.ErrorSection
import com.vauth.rxfoxyvpn.data.error.FoxyError
import com.vauth.rxfoxyvpn.data.error.FoxyLogger
import com.vauth.rxfoxyvpn.data.formatBytesPerSecond
import com.vauth.rxfoxyvpn.data.model.ConnectionState
import com.vauth.rxfoxyvpn.data.model.ProxyCandidate
import com.vauth.rxfoxyvpn.data.model.RuntimeAuth
import com.vauth.rxfoxyvpn.vpn.engine.EngineHost
import com.vauth.rxfoxyvpn.vpn.engine.SingBoxConfigBuilder
import com.vauth.rxfoxyvpn.vpn.engine.SingBoxEngine
import com.vauth.rxfoxyvpn.vpn.engine.TunnelProbe
import com.vauth.rxfoxyvpn.vpn.engine.VpnEngine
import com.vauth.rxfoxyvpn.vpn.engine.tunnelSnapshot
import com.vauth.rxfoxyvpn.widget.QuickConnectWidgetProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "RxFoxyVpnService"
private const val CONNECT_TIMEOUT_MS = 25_000L
private const val SERVER_LIST_FETCH_TIMEOUT_MS = 15_000L
private const val WATCHDOG_INTERVAL_MS = 2_000L
private const val RECONNECT_FAILURES_BEFORE_WARNING = 5
private const val RECONNECT_BACKOFF_BASE_MS = 3_000L
private const val RECONNECT_BACKOFF_CAP_MS = 60_000L
private const val RECONNECT_FAILURES_BEFORE_EDGE_ROTATION = 2
private const val MAX_ALTERNATE_EDGES = 3
private const val PROXY_PASS_RENEWAL_FRACTION = 0.5
private const val PROXY_PASS_RENEWAL_SAFETY_MARGIN_MS = 30_000L
private const val PROXY_PASS_RENEWAL_FLOOR_MS = 15_000L
private const val PROXY_PASS_RENEWAL_CEILING_MS = 30 * 60_000L
private const val PROXY_PASS_RENEWAL_FALLBACK_MS = 4 * 60_000L
private const val PROXY_PASS_RENEWAL_RETRY_MS = 30_000L
private const val INITIAL_DIAL_MAX_ATTEMPTS = MAX_ALTERNATE_EDGES + 1
private const val INITIAL_DIAL_BACKOFF_BASE_MS = 2_000L
private const val INITIAL_DIAL_BACKOFF_CAP_MS = 8_000L
private const val HEALTH_PROBE_INTERVAL_MS = 45_000L
private const val HEALTH_PROBE_RESUME_SETTLE_MS = 3_500L
private const val HEALTH_PROBE_FAST_INTERVAL_MS = 10_000L
private const val HEALTH_FAILURES_TO_CONFIRM = 3          // consecutive failed probe rounds before a failure counts as confirmed
private const val AUTO_RECOVERY_MAX_PER_WINDOW = 3         // automatic recoveries allowed per window, then stop and report ERROR
private const val AUTO_RECOVERY_WINDOW_MS = 10 * 60_000L
private const val HOLD_CPU_WAKE_LOCK = true
private const val ENGINE_MAX_RESTARTS_PER_WINDOW = 3
private const val ENGINE_RESTART_WINDOW_MS = 10 * 60_000L
private const val WAKE_LOCK_TAG = "com.vauth.rxfoxyvpn:vpn"
private const val WIFI_LOCK_TAG = "com.vauth.rxfoxyvpn:vpn-wifi"

/** Real tunnel health, driven by end-to-end probes and the core's own state, never by a stored value. */
enum class TunnelHealth { DISCONNECTED, CONNECTING, CONNECTED, RECOVERING, ERROR }

/** Cumulative bytes for the current VPN session (continues across engine restarts) and the latest rates. */
data class TrafficSnapshot(val upload: Long = 0L, val download: Long = 0L, val uploadRate: Long = 0L, val downloadRate: Long = 0L)

private fun fullJitterBackoffMs(attempt: Int, baseMs: Long, capMs: Long): Long {
    val exponential = baseMs * (1L shl attempt.coerceIn(0, 10))
    val upperBound = exponential.coerceAtMost(capMs)
    return (upperBound.toDouble() * Math.random()).toLong().coerceAtLeast(baseMs / 4)
}

private fun proxyPassRenewalDelayMs(expiresAtEpochSeconds: Long?): Long {
    if (expiresAtEpochSeconds == null) return PROXY_PASS_RENEWAL_FALLBACK_MS
    val remainingMs = expiresAtEpochSeconds * 1_000L - System.currentTimeMillis()
    if (remainingMs <= 0L) return PROXY_PASS_RENEWAL_FLOOR_MS
    val atHalfLife = (remainingMs * PROXY_PASS_RENEWAL_FRACTION).toLong()
    val beforeExpiry = remainingMs - PROXY_PASS_RENEWAL_SAFETY_MARGIN_MS
    return minOf(atHalfLife, beforeExpiry)
        .coerceAtMost(PROXY_PASS_RENEWAL_CEILING_MS)
        .coerceAtLeast(PROXY_PASS_RENEWAL_FLOOR_MS)
}


class RxFoxyVpnService : VpnService(), EngineHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val opMutex = Mutex()

    private var engine: VpnEngine? = null
    private var connectJob: Job? = null
    private var watchdogJob: Job? = null
    private var tokenRenewalJob: Job? = null
    private var probeJob: Job? = null

    private val healthCheckTrigger = Channel<String>(Channel.CONFLATED)
    private var resumeReceiver: BroadcastReceiver? = null

    @Volatile private var lastNetworkLostAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var tunnelInterfaceActive = false
    @Volatile private var boundNetwork: Network? = null
    @Volatile private var reportedUnderlying: Network? = null
    @Volatile private var statusLabel: String = "Connecting\u2026"
    @Volatile private var foregroundActive = false
    @Volatile private var lastNotificationText: String? = null
    @Volatile private var connectionGeneration = 0
    @Volatile private var activeSocksPort = 0

    // Traffic accounting that survives engine restarts (the core's counters restart at zero).
    @Volatile private var trafficBaseUp = 0L
    @Volatile private var trafficBaseDown = 0L
    @Volatile private var lastCoreUp = 0L
    @Volatile private var lastCoreDown = 0L

    // ---- EngineHost ----
    override val vpnService: VpnService get() = this
    override val appPackageName: String get() = packageName
    @Volatile override var nativeProtectionRequired: Boolean = true

    override fun onTunOpened() {
        tunnelInterfaceActive = true
        reportedUnderlying = null
        runCatching { firstUsableNetwork()?.let { reportUnderlyingNetwork(it) } }
    }

    override fun onCoreRequestedStop() {
        // The core asked to stop on its own (not us): let the watchdog's recovery policy decide.
        (engine as? SingBoxEngine)?.coreRequestedStop()
        AppLogger.w(TAG, "sing-box requested a stop; the watchdog will recover or give up")
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { reportUnderlyingNetwork(network) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            reportUnderlyingNetwork(network, capabilities)
        }
        override fun onLost(network: Network) {
            if (boundNetwork != network) return
            boundNetwork = null
            reportedUnderlying = null
            lastNetworkLostAt = System.currentTimeMillis()
            if (tunnelInterfaceActive) runCatching { setUnderlyingNetworks(null) }
            engine?.notifyUnderlyingNetworkChanged()
        }
    }

    private fun reportUnderlyingNetwork(network: Network, capabilities: NetworkCapabilities? = null) {
        val caps = capabilities ?: getSystemService(ConnectivityManager::class.java)?.getNetworkCapabilities(network) ?: return
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
        if (tunnelInterfaceActive && reportedUnderlying != network) {
            reportedUnderlying = network
            runCatching { setUnderlyingNetworks(arrayOf(network)) }.onFailure {
                reportedUnderlying = null
                AppLogger.w(TAG, "failed to report the underlying network to the platform", it)
            }
        }
        val previous = boundNetwork
        if (previous == network) return
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && previous != null) return
        boundNetwork = network
        runCatching { ControlPlaneHttp.client.connectionPool.evictAll() }
        // sing-box re-binds new outbound sockets to the new default interface; sockets on the old one die and are
        // redialed by their owners. An immediate end-to-end probe confirms the new path.
        engine?.notifyUnderlyingNetworkChanged()
        if (_state.value == ConnectionState.CONNECTED) healthCheckTrigger.trySend("network changed")
    }

    private fun startHealthProbe(myGeneration: Int, settings: com.vauth.rxfoxyvpn.vpn.engine.TunnelSettings, restartEngine: suspend (String) -> Boolean) {
        probeJob?.cancel()
        probeJob = scope.launch {
            var failures = 0
            val recoveryTimes = ArrayDeque<Long>()
            while (isActive) {
                val wait = if (failures > 0) HEALTH_PROBE_FAST_INTERVAL_MS else HEALTH_PROBE_INTERVAL_MS
                val trigger = withTimeoutOrNull(wait) { healthCheckTrigger.receive() }
                if (myGeneration != connectionGeneration) return@launch
                if (_state.value != ConnectionState.CONNECTED) { failures = 0; continue }
                if (engine?.isRunning != true) { failures = 0; continue } // the watchdog is on it
                if (trigger != null) delay(HEALTH_PROBE_RESUME_SETTLE_MS)
                val rtt = TunnelProbe.measure(settings.socksPort, settings.latencyProbeUrl)
                if (rtt != null) {
                    if (failures > 0 || _health.value != TunnelHealth.CONNECTED) AppLogger.i(TAG, "health check ok again (${rtt} ms)")
                    failures = 0
                    _latencyMs.value = rtt
                    _health.value = TunnelHealth.CONNECTED
                    if (_lastError.value != null) _lastError.value = null
                    continue
                }
                failures++
                _latencyMs.value = null
                AppLogger.w(TAG, "health check failed ($failures/$HEALTH_FAILURES_TO_CONFIRM)")
                if (failures < HEALTH_FAILURES_TO_CONFIRM) continue   // short blips must not trigger a reconnect
                // Failure is now confirmed over several probe rounds.
                val now = System.currentTimeMillis()
                while (recoveryTimes.isNotEmpty() && now - recoveryTimes.first() > AUTO_RECOVERY_WINDOW_MS) recoveryTimes.removeFirst()
                if (!settings.automaticRecovery) {
                    _health.value = TunnelHealth.ERROR
                    AppLogger.e(TAG, "tunnel path is failing and automatic recovery is off")
                    failures = 0
                    continue
                }
                if (recoveryTimes.size >= AUTO_RECOVERY_MAX_PER_WINDOW) {
                    _health.value = TunnelHealth.ERROR
                    _lastError.value = FoxyError(ErrorCode.CONNECTION_FAILED, ErrorSection.VPN, "recovery_paused", detail = "automatic recovery budget exhausted")
                    AppLogger.e(TAG, "automatic recovery budget used up ($AUTO_RECOVERY_MAX_PER_WINDOW in ${AUTO_RECOVERY_WINDOW_MS / 60_000} min); not restarting again")
                    failures = 0
                    continue
                }
                recoveryTimes.addLast(now)
                failures = 0
                restartEngine("proxy path failed ${HEALTH_FAILURES_TO_CONFIRM} consecutive health checks")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    healthCheckTrigger.trySend(intent?.action?.substringAfterLast('.') ?: "system event")
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            resumeReceiver = receiver
        }.onFailure { AppLogger.w(TAG, "could not register the screen/idle receiver", it) }
        runCatching {
            val underlyingNetworks = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(underlyingNetworks, networkCallback)
        }.onFailure { AppLogger.w(TAG, "could not register a network callback; handovers will be slower to detect", it) }
    }

    override fun onDestroy() {
        ControlPlaneHttp.socketProtector = null
        runCatching { resumeReceiver?.let { unregisterReceiver(it) } }
        resumeReceiver = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback) }
        connectJob?.cancel(); connectJob = null
        watchdogJob?.cancel(); watchdogJob = null
        connectionGeneration++
        val doomed = detachEngine()
        // Synchronous best effort: the process may be reclaimed right after onDestroy.
        kotlinx.coroutines.runBlocking { withTimeoutOrNull(3_000L) { runCatching { doomed?.stop() } } }
        releaseWakeLocks()
        foregroundActive = false
        lastNotificationText = null
        boundNetwork = null
        reportedUnderlying = null
        scope.cancel()
        _state.value = ConnectionState.DISCONNECTED
        _traffic.value = TrafficSnapshot()
        QuickConnectWidgetProvider.updateAll(this@RxFoxyVpnService, _state.value)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_CONNECT
        if (action == ACTION_CONNECT || action == VpnService.SERVICE_INTERFACE) {
            if (_state.value != ConnectionState.DISCONNECTED) {
                AppLogger.d(TAG, "ignoring duplicate connect request while ${_state.value}")
                enterForeground(statusLabel)
                return START_STICKY
            }
            if (intent == null) AppLogger.i(TAG, "the service was restarted by the system; bringing the tunnel back up")
            _state.value = ConnectionState.CONNECTING
            QuickConnectWidgetProvider.updateAll(this@RxFoxyVpnService, _state.value)
            _lastError.value = null
            statusLabel = LanguageManager.t("notif_connecting", "Connecting…")
            enterForeground(statusLabel)
            acquireWakeLocks()
            connectJob = scope.launch { opMutex.withLock { connect() } }
            return START_STICKY
        } else if (action == ACTION_DISCONNECT) {
            requestDisconnect("requested by the user")
        }
        return START_NOT_STICKY
    }

    private fun requestDisconnect(reason: String) {
        val idle = _state.value == ConnectionState.DISCONNECTED && connectJob == null && watchdogJob == null
        if (idle) { releaseWakeLocks(); stopSelf(); return }
        AppLogger.i(TAG, "disconnect: $reason")
        val endedGeneration = ++connectionGeneration
        _health.value = TunnelHealth.DISCONNECTED
        _latencyMs.value = null
        _state.value = ConnectionState.DISCONNECTED
        QuickConnectWidgetProvider.updateAll(this@RxFoxyVpnService, _state.value)
        // A start() blocked in native code must be interrupted, otherwise opMutex never frees.
        engine?.interruptStart()
        connectJob?.cancel(); connectJob = null
        watchdogJob?.cancel(); watchdogJob = null
        exitForeground()
        releaseWakeLocks()
        val doomed = detachEngine()
        scope.launch {
            opMutex.withLock {
                val stillOurs = connectionGeneration == endedGeneration
                runCatching { doomed?.stop() }.onFailure { AppLogger.w(TAG, "error stopping the engine", it) }
                if (stillOurs && _state.value == ConnectionState.DISCONNECTED) stopSelf()
            }
        }
    }

    private fun detachEngine(): VpnEngine? {
        tokenRenewalJob?.cancel(); tokenRenewalJob = null
        probeJob?.cancel(); probeJob = null
        val e = engine
        e?.onTraffic = null
        e?.onCoreStopped = null
        engine = null
        tunnelInterfaceActive = false
        nativeProtectionRequired = true
        ControlPlaneHttp.socketProtector = null
        _traffic.value = TrafficSnapshot()
        _health.value = TunnelHealth.DISCONNECTED
        _latencyMs.value = null
        return e
    }

    private fun isFatalUpstreamError(error: Throwable): Boolean = error is TokenInvalidError || error is QuotaExceededError

    private fun startProxyPassRenewal(myGeneration: Int, initialExpiry: Long?, mintProxyPass: suspend () -> ProxyPass) {
        tokenRenewalJob?.cancel()
        tokenRenewalJob = scope.launch {
            var expiry = initialExpiry
            AppLogger.i(TAG, "proxy pass renewal scheduled in ${proxyPassRenewalDelayMs(expiry) / 1_000}s")
            while (isActive) {
                delay(proxyPassRenewalDelayMs(expiry))
                if (myGeneration != connectionGeneration) return@launch
                val live = engine
                if (live == null || !live.isRunning) {
                    AppLogger.d(TAG, "skipping proxy pass renewal: engine not running (a restart mints its own pass)")
                    continue
                }
                val attempt = runCatching { mintProxyPass() }
                val pass = attempt.getOrNull()
                if (pass == null) {
                    val error = attempt.exceptionOrNull()
                    if (error is CancellationException) throw error
                    if (error != null && isFatalUpstreamError(error)) {
                        AppLogger.e(TAG, "proxy pass renewal failed for a reason retrying cannot fix; leaving it to the watchdog", error)
                        return@launch
                    }
                    AppLogger.w(TAG, "proxy pass renewal failed; retrying shortly: ${error?.message}")
                    expiry = null
                    delay(PROXY_PASS_RENEWAL_RETRY_MS)
                    continue
                }
                if (myGeneration != connectionGeneration) return@launch
                val current = engine
                if (current == null || !current.isRunning) { expiry = pass.expiresAtEpochSeconds; continue }
                // Swapped inside the core; no TUN rebuild, no restart, existing streams keep running.
                current.updateBearerToken(pass.token)
                AppLogger.i(TAG, "proxy pass renewed in place; the tunnel was not rebuilt")
                expiry = pass.expiresAtEpochSeconds
            }
        }
    }

    private suspend fun connect() {
        val myGeneration = ++connectionGeneration
        boundNetwork = null
        _health.value = TunnelHealth.CONNECTING
        _latencyMs.value = null
        _lockdown.value = if (android.os.Build.VERSION.SDK_INT >= 29) runCatching { isLockdownEnabled }.getOrDefault(false) else false
        runCatching {
            AppLogger.i(TAG, "connect: starting")
            val tokenStore = TokenStore(applicationContext)
            if (tokenStore.loadAuth() == null) error("Not signed in")
            val proxyStateStore = ProxyStateStore(applicationContext)
            val settingsStore = SettingsStore(applicationContext)

            val primaryCandidate = resolveConnectCandidate(proxyStateStore)
            ensureGenerationCurrent(myGeneration)

            var candidates = listOf(primaryCandidate)
            var candidateIndex = 0
            var alternatesDiscovered = false
            fun activeCandidate(): ProxyCandidate = candidates[candidateIndex.coerceIn(0, candidates.lastIndex)]

            suspend fun rotateCandidate(): Boolean {
                if (candidateIndex + 1 >= candidates.size && !alternatesDiscovered) {
                    alternatesDiscovered = true
                    val fresh = discoverAlternateCandidates(primaryCandidate)
                        .filter { d -> candidates.none { k -> k.authority == d.authority } }
                        .take(MAX_ALTERNATE_EDGES)
                    if (fresh.isNotEmpty()) {
                        candidates = candidates + fresh
                        AppLogger.i(TAG, "found ${fresh.size} alternate edge(s) in ${primaryCandidate.countryCode} to fail over to")
                    }
                }
                if (candidateIndex + 1 >= candidates.size) return false
                candidateIndex++
                AppLogger.i(TAG, "failing over to edge ${activeCandidate().authority} (${candidateIndex + 1} of ${candidates.size})")
                return true
            }

            val tunnelSettings = settingsStore.tunnelSnapshot(applicationContext)
            val proxyOnlyMode = tunnelSettings.proxyOnlyMode
            activeSocksPort = tunnelSettings.socksPort
            nativeProtectionRequired = !proxyOnlyMode
            ControlPlaneHttp.socketProtector = if (proxyOnlyMode) null else { socket -> protect(socket) }

            fun connectedLabel(): String = if (proxyOnlyMode) {
                LanguageManager.tn("notif_proxy_active", "Proxy active • {n}", "${tunnelSettings.socksBindAddress}:${tunnelSettings.socksPort}")
            } else {
                val t = activeCandidate()
                LanguageManager.tn("notif_connected", "Connected • {n}", t.countryName.ifBlank { t.countryCode })
            }

            // Single source of truth: the app-wide account store (UI and service must agree).
            val multiAccountStore = (application as RxFoxyVpnApp).multiAccountStore

            /**
             * Mints a proxy pass for the best usable account. Quota and auth failures are
             * absorbed here by advancing to the next account (Arman's rotation semantics):
             * - HTTP 401/403 (invalid token) → the account is marked reauth_required, try the next;
             * - HTTP 429 (quota) → the account is reserved until its reset, try the next;
             * - otherwise the quota from the response headers is recorded, and an account at or
             *   over the switch threshold is reserved so the NEXT pass uses the successor.
             * Throws when no account can serve.
             */
            suspend fun mintProxyPass(): ProxyPass {
                val guardian = GuardianClient()
                val tried = HashSet<String>()
                while (true) {
                    val account = multiAccountStore.candidate(tried)
                        ?: throw IllegalStateException("no usable account; earliest retry at ${multiAccountStore.earliestRetryAt()}")
                    tried += account.id
                    if (multiAccountStore.activeId.value != account.id) {
                        multiAccountStore.setActive(account.id)
                    }
                    var auth = com.vauth.rxfoxyvpn.data.model.RuntimeAuth(
                        accessToken = account.accessToken,
                        refreshToken = account.refreshToken,
                        expiresAtEpochSeconds = account.expiresAtEpochSeconds,
                    )
                    if (!tokenStore.hasValidSession()) {
                        val renewed = renewAccessToken(tokenStore)
                        if (renewed != null) {
                            multiAccountStore.updateTokens(account.id, renewed)
                            auth = renewed
                            AppLogger.i(TAG, "the account's access token had expired and was renewed")
                        } else {
                            AppLogger.w(TAG, "the account's access token is past its expiry and could not be renewed automatically")
                        }
                    }
                    try {
                        val pass = guardian.fetchProxyPass(GUARDIAN_ENDPOINT_DEFAULT, auth.accessToken)
                        multiAccountStore.observePassQuota(account.id, pass)
                        return pass
                    } catch (invalid: TokenInvalidError) {
                        AppLogger.w(TAG, "the proxy pass was rejected; attempting one Guardian activation before giving up on this account", invalid)
                        val recovered = runCatching { guardian.activateGuardian(GUARDIAN_ENDPOINT_DEFAULT, auth.accessToken) }.isSuccess
                        if (recovered) {
                            val retry = runCatching { guardian.fetchProxyPass(GUARDIAN_ENDPOINT_DEFAULT, auth.accessToken) }
                            retry.getOrNull()?.let { pass ->
                                multiAccountStore.observePassQuota(account.id, pass)
                                return pass
                            }
                        }
                        AppLogger.w(TAG, "account requires a new sign-in; trying the next account")
                        multiAccountStore.markReauthRequired(account.id, invalid.message)
                    } catch (quota: QuotaExceededError) {
                        AppLogger.i(TAG, "account reached its monthly quota; trying the next account")
                        multiAccountStore.reserveForQuotaExhausted(account.id, quota.resetAtEpochSeconds ?: 0L, quota.message)
                    }
                }
            }

            var currentPassExpiry: Long? = null
            val coreRef = SingBoxEngine(this@RxFoxyVpnService, settingsStore, scope)
            engine = coreRef
            coreRef.onTraffic = { s ->
                if (s.uploadTotal < lastCoreUp || s.downloadTotal < lastCoreDown) {
                    trafficBaseUp += lastCoreUp; trafficBaseDown += lastCoreDown // core restarted: keep the session monotonic
                }
                lastCoreUp = s.uploadTotal; lastCoreDown = s.downloadTotal
                _traffic.value = TrafficSnapshot(trafficBaseUp + s.uploadTotal, trafficBaseDown + s.downloadTotal, s.uploadRate, s.downloadRate)
                if (_state.value == ConnectionState.CONNECTED) {
                    updateNotification(statusLabel, "\u2193 ${formatBytesPerSecond(s.downloadRate)}  \u2191 ${formatBytesPerSecond(s.uploadRate)}")
                }
            }
            trafficBaseUp = 0L; trafficBaseDown = 0L; lastCoreUp = 0L; lastCoreDown = 0L

            /** Mint a pass, validate + start the core for the active candidate. Throws on failure, leaving the core stopped. */
            suspend fun startCore() {
                val pass = mintProxyPass()
                currentPassExpiry = pass.expiresAtEpochSeconds
                val config = SingBoxConfigBuilder.build(activeCandidate(), tunnelSettings, packageName)
                coreRef.start(config, pass.token)
            }

            var dialAttempt = 0
            var lastFailure: Throwable? = null
            while (true) {
                dialAttempt++
                ensureGenerationCurrent(myGeneration)
                val target = activeCandidate()
                var ok = false
                try {
                    withTimeout(CONNECT_TIMEOUT_MS) { startCore() }
                    // The core dials lazily, so only a real request proves the edge accepts our pass.
                    val rtt = TunnelProbe.measure(tunnelSettings.socksPort, tunnelSettings.latencyProbeUrl)
                    ok = rtt != null
                    if (rtt != null) _latencyMs.value = rtt else lastFailure = null
                } catch (c: CancellationException) {
                    if (c !is TimeoutCancellationException) throw c
                    lastFailure = c
                    AppLogger.w(TAG, "start of the core for ${target.authority} timed out (attempt $dialAttempt)")
                } catch (e: Throwable) {
                    if (isFatalUpstreamError(e) || e is IllegalArgumentException) throw e
                    lastFailure = e
                    AppLogger.w(TAG, "start for ${target.authority} failed (attempt $dialAttempt): ${e.javaClass.simpleName}: ${e.message}")
                }
                if (ok) break
                runCatching { coreRef.stop() }
                if (dialAttempt >= INITIAL_DIAL_MAX_ATTEMPTS) {
                    val (failures, cleared) = proxyStateStore.recordFailure()
                    if (cleared) AppLogger.w(TAG, "the saved location failed $failures connects in a row; clearing it so the next connect auto-selects")
                    val f = lastFailure
                    _lastError.value = null
                    if (f != null) throw com.vauth.rxfoxyvpn.data.error.FoxyException(
                        FoxyError(ErrorCode.SERVER_UNREACHABLE, ErrorSection.VPN, "connect", cause = f, detail = "after $dialAttempt attempts across ${candidates.size} server(s)"),
                    )
                    throw com.vauth.rxfoxyvpn.data.error.FoxyException(
                        FoxyError(ErrorCode.CONNECTION_FAILED, ErrorSection.VPN, "connect", detail = "server did not carry traffic after $dialAttempt attempts"),
                    )
                }
                rotateCandidate()
                delay(fullJitterBackoffMs(dialAttempt - 1, INITIAL_DIAL_BACKOFF_BASE_MS, INITIAL_DIAL_BACKOFF_CAP_MS))
            }
            ensureGenerationCurrent(myGeneration)

            val established = activeCandidate()
            if (established.authority == primaryCandidate.authority) proxyStateStore.save(primaryCandidate)

            val connectedText = connectedLabel()
            _state.value = ConnectionState.CONNECTED
            _health.value = TunnelHealth.CONNECTED
            QuickConnectWidgetProvider.updateAll(this@RxFoxyVpnService, _state.value)
            statusLabel = connectedText
            updateNotification(connectedText)
            AppLogger.i(TAG, "connect: CONNECTED via ${established.authority} (sing-box)")

            // Recovery: restart the core with a freshly minted pass; rotate the edge after repeated failures.
            val restartTimes = ArrayDeque<Long>()
            var consecutiveFailures = 0
            suspend fun restartEngine(reason: String): Boolean = opMutex.withLock {
                if (myGeneration != connectionGeneration) return@withLock false
                val now = System.currentTimeMillis()
                while (restartTimes.isNotEmpty() && now - restartTimes.first() > ENGINE_RESTART_WINDOW_MS) restartTimes.removeFirst()
                if (restartTimes.size >= ENGINE_MAX_RESTARTS_PER_WINDOW) {
                    // A core that keeps dying right after starting: slow down instead of spinning.
                    AppLogger.e(TAG, "engine restarted ${restartTimes.size} times in ${ENGINE_RESTART_WINDOW_MS / 60_000} min ($reason); throttling")
                    delay(RECONNECT_BACKOFF_CAP_MS / 2)
                    if (myGeneration != connectionGeneration) return@withLock false
                }
                restartTimes.addLast(now)
                AppLogger.w(TAG, "restarting the sing-box core: $reason")
                _health.value = TunnelHealth.RECOVERING
                statusLabel = LanguageManager.t("notif_reconnecting", "Reconnecting…")
                updateNotification(statusLabel)
                runCatching { coreRef.stop() }
                if (myGeneration != connectionGeneration) return@withLock false
                if (consecutiveFailures > 0 && consecutiveFailures % RECONNECT_FAILURES_BEFORE_EDGE_ROTATION == 0) rotateCandidate()
                val started = try {
                    withTimeout(CONNECT_TIMEOUT_MS) { startCore() }
                    TunnelProbe.measure(tunnelSettings.socksPort, tunnelSettings.latencyProbeUrl)?.also { _latencyMs.value = it } != null
                } catch (c: CancellationException) {
                    if (c !is TimeoutCancellationException) throw c
                    false
                } catch (e: Throwable) {
                    if (isFatalUpstreamError(e)) {
                        _lastError.value = classified(e, "proxy_pass_renew")
                        AppLogger.e(TAG, "unrecoverable failure during restart; disconnecting", e)
                        requestDisconnect("unrecoverable upstream failure")
                        return@withLock false
                    }
                    AppLogger.w(TAG, "restart failed: ${e.message}")
                    false
                }
                if (started) {
                    consecutiveFailures = 0
                    _lastError.value = null
                    _health.value = TunnelHealth.CONNECTED
                    statusLabel = connectedLabel()
                    updateNotification(statusLabel)
                    startProxyPassRenewal(myGeneration, currentPassExpiry) { mintProxyPass() }
                    true
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= RECONNECT_FAILURES_BEFORE_WARNING) {
                        _health.value = TunnelHealth.ERROR
                        _lastError.value = FoxyError(
                            code = ErrorCode.SERVER_UNREACHABLE,
                            section = ErrorSection.VPN,
                            operation = "watchdog_reconnect",
                            detail = "consecutive restart failures: $consecutiveFailures",
                        )
                    }
                    false
                }
            }

            startProxyPassRenewal(myGeneration, currentPassExpiry) { mintProxyPass() }
            startHealthProbe(myGeneration, tunnelSettings) { reason -> restartEngine(reason) }

            if (settingsStore.exitCheckEnabled) {
                scope.launch {
                    com.vauth.rxfoxyvpn.vpn.ExitCheck().verifyExitCountry(tunnelSettings.socksPort, established.countryCode)
                        .onSuccess { observed -> if (myGeneration == connectionGeneration) AppLogger.i(TAG, "exit check: observed country=$observed") }
                        .onFailure { AppLogger.w(TAG, "exit check failed (non-fatal)", it) }
                }
            }

            watchdogJob = scope.launch {
                var waitingForNetwork = false
                while (isActive) {
                    delay(WATCHDOG_INTERVAL_MS)
                    if (myGeneration != connectionGeneration) return@launch
                    val core = engine
                    if (core != null && core.isRunning) {
                        if (consecutiveFailures > 0 || waitingForNetwork) {
                            statusLabel = connectedLabel(); updateNotification(statusLabel)
                        }
                        waitingForNetwork = false
                        continue
                    }
                    if (!hasUsableNetwork()) {
                        if (!waitingForNetwork) {
                            AppLogger.i(TAG, "no usable network; waiting for connectivity")
                            waitingForNetwork = true
                            statusLabel = LanguageManager.t("notif_waiting_network", "Waiting for network…")
                            updateNotification(statusLabel)
                        }
                        continue
                    }
                    waitingForNetwork = false
                    val ok = restartEngine("the native core is not running")
                    if (!ok && myGeneration == connectionGeneration) {
                        delay(fullJitterBackoffMs(consecutiveFailures, RECONNECT_BACKOFF_BASE_MS, RECONNECT_BACKOFF_CAP_MS))
                    }
                }
            }
        }.onFailure { failure ->
            if (failure is CancellationException) {
                AppLogger.i(TAG, "connect: aborted because the connection was cancelled")
                val doomed = detachEngine()
                runCatching { doomed?.stop() }
                return@onFailure
            }
            _lastError.value = classified(failure, "connect")
            connectionGeneration++
            watchdogJob?.cancel(); watchdogJob = null
            val doomed = detachEngine()
            runCatching { doomed?.stop() }
            _state.value = ConnectionState.DISCONNECTED
            QuickConnectWidgetProvider.updateAll(this@RxFoxyVpnService, _state.value)
            exitForeground()
            releaseWakeLocks()
            stopSelf()
        }
        connectJob = null
    }

        private fun acquireWakeLocks() {
            if (HOLD_CPU_WAKE_LOCK && wakeLock == null) {
                runCatching {
                    val power = getSystemService(PowerManager::class.java) ?: error("PowerManager unavailable")
                    wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                        setReferenceCounted(false)
                        @Suppress("WakelockTimeout")
                        acquire()
                    }
                }.onFailure { AppLogger.w(TAG, "CPU wake lock unavailable (is WAKE_LOCK declared in the manifest?): ${it.message}") }
            }
            if (wifiLock == null) {
                runCatching {
                    val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                        ?: error("WifiManager unavailable")
                    @Suppress("DEPRECATION")
                    wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG).apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }.onFailure { AppLogger.d(TAG, "Wi-Fi high-performance lock unavailable: ${it.message}") }
            }
        }

        private fun releaseWakeLocks() {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
                .onFailure { AppLogger.d(TAG, "error releasing the CPU wake lock: ${it.message}") }
            wakeLock = null
            runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
                .onFailure { AppLogger.d(TAG, "error releasing the Wi-Fi lock: ${it.message}") }
            wifiLock = null
        }

        override fun firstUsableNetwork(): Network? {
            val manager = getSystemService(ConnectivityManager::class.java) ?: return null
            @Suppress("DEPRECATION")
            val networks = runCatching { manager.allNetworks }.getOrDefault(emptyArray())
            var unvalidated: Network? = null
            for (network in networks) {
                val capabilities = manager.getNetworkCapabilities(network) ?: continue

                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return network
                if (unvalidated == null) unvalidated = network
            }
            return unvalidated
        }

        private fun hasUsableNetwork(): Boolean {

            getSystemService(ConnectivityManager::class.java) ?: return true
            return firstUsableNetwork() != null
        }

        /**
         * The user must pick a location themselves (connect order: account → server → connect).
         * Never auto-selects an edge here — silently choosing one would bypass that order.
         */
        private suspend fun resolveConnectCandidate(proxyStateStore: ProxyStateStore): ProxyCandidate =
            proxyStateStore.load()
                ?: throw IllegalStateException("no server selected; choose a location first")

        private suspend fun discoverAlternateCandidates(primary: ProxyCandidate): List<ProxyCandidate> {
            val attempt = runCatching {
                val countries = withTimeout(SERVER_LIST_FETCH_TIMEOUT_MS) { ServerListClient().fetchCountries() }
                val sameCity = ServerListClient.candidatesForCity(countries, primary.countryCode, primary.cityCode)
                val sameCountry = ServerListClient.candidatesForCountry(countries, primary.countryCode)
                (sameCity + sameCountry)
                    .distinctBy { it.authority }
                    .filter { it.authority != primary.authority }
            }
            val failure = attempt.exceptionOrNull()
            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
            if (failure != null) {
                AppLogger.w(TAG, "could not fetch alternate edges; staying with ${primary.authority}", failure)
                return emptyList()
            }
            return attempt.getOrDefault(emptyList())
        }

        private suspend fun renewAccessToken(tokenStore: TokenStore): RuntimeAuth? {
            val attempt = runCatching { FxaAuthRepository(tokenStore).refreshAccessToken() }
            val failure = attempt.exceptionOrNull()
            if (failure is CancellationException) throw failure
            if (failure != null) {
                AppLogger.w(TAG, "renewing the account's access token failed", failure)
                return null
            }
            return attempt.getOrNull()
        }

        /** Classify and fully log a failure that must leave the tunnel down. Never leaks secrets. */
        private fun classified(error: Throwable, operation: String): FoxyError {
            val classified = ErrorClassifier.classify(error, ErrorSection.VPN, operation)
            FoxyLogger.log(classified)
            return classified
        }

        private fun ensureGenerationCurrent(myGeneration: Int) {
            if (myGeneration != connectionGeneration) {
                throw CancellationException("connect was superseded by a newer request")
            }
        }

        override fun onRevoke() {
            requestDisconnect("VPN permission was revoked by the system")
            super.onRevoke()
        }

        private fun buildNotification(text: String, subText: String? = null): Notification {
            val openIntent = PendingIntent.getActivity(
                this, REQUEST_CODE_OPEN, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val disconnectIntent = PendingIntent.getService(
                this, REQUEST_CODE_DISCONNECT, Intent(this, RxFoxyVpnService::class.java).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = NotificationCompat.Builder(this, RxFoxyVpnApp.VPN_NOTIFICATION_CHANNEL_ID)
                .setContentTitle("RxFoxyVPN")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_qs_fox)
                .setContentIntent(openIntent)
                .setOngoing(true)

                .setOnlyAlertOnce(true)

                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)

                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

                .setLocalOnly(true)

                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectIntent)
            if (subText != null) {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$subText"))
            }
            return builder.build()
        }

        private fun enterForeground(text: String) {
            foregroundActive = true
            lastNotificationText = text
            startForeground(NOTIFICATION_ID, buildNotification(text))
        }

        private fun exitForeground() {
            foregroundActive = false
            lastNotificationText = null
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        }

        private fun updateNotification(text: String, subText: String? = null) {
            if (!foregroundActive) return
            val key = if (subText == null) text else "$text\n$subText"
            if (key == lastNotificationText) return
            lastNotificationText = key
            val manager = getSystemService(NotificationManager::class.java) ?: return
            runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text, subText)) }
        }

    companion object {
        const val ACTION_CONNECT = "com.vauth.rxfoxyvpn.action.CONNECT"
        const val ACTION_DISCONNECT = "com.vauth.rxfoxyvpn.action.DISCONNECT"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_CODE_OPEN = 0
        private const val REQUEST_CODE_DISCONNECT = 1

        private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
        val state: StateFlow<ConnectionState> = _state

        private val _lastError = MutableStateFlow<FoxyError?>(null)
        val lastError: StateFlow<FoxyError?> = _lastError

        private val _health = MutableStateFlow(TunnelHealth.DISCONNECTED)
        val health: StateFlow<TunnelHealth> = _health

        private val _latencyMs = MutableStateFlow<Long?>(null)
        /** Last end-to-end round trip through the tunnel (to the configured latency URL), null when unknown. */
        val latencyMs: StateFlow<Long?> = _latencyMs

        private val _lockdown = MutableStateFlow(false)
        /** True when Android "Block connections without VPN" is on for this app (known after the first connect). */
        val lockdownActive: StateFlow<Boolean> = _lockdown

        private val _traffic = MutableStateFlow(TrafficSnapshot())
        val traffic: StateFlow<TrafficSnapshot> = _traffic

        /** Legacy shape used by the UI: [1] = uploaded bytes, [3] = downloaded bytes. */
        fun trafficTotals(): LongArray = _traffic.value.let { longArrayOf(0L, it.upload, 0L, it.download) }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RxFoxyVpnService::class.java).setAction(ACTION_CONNECT))
        }

        fun stop(context: Context) {
            val intent = Intent(context, RxFoxyVpnService::class.java).setAction(ACTION_DISCONNECT)
            runCatching { context.startService(intent) }
                .onFailure { AppLogger.w(TAG, "could not deliver the disconnect intent; the service is not running", it) }
        }
    }
}
