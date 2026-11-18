package com.vauth.rxfoxyvpn.ui.screens

import android.content.Intent
import android.widget.Toast
import android.net.Uri
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vauth.rxfoxyvpn.BuildConfig
import com.vauth.rxfoxyvpn.R
import com.vauth.rxfoxyvpn.data.LanguageManager
import com.vauth.rxfoxyvpn.data.SettingsStore
import com.vauth.rxfoxyvpn.vpn.RxFoxyVpnService
import com.vauth.rxfoxyvpn.vpn.TunnelHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ScaffoldDefaults
import com.vauth.rxfoxyvpn.ui.icons.FoxyIcons

/* Design tokens from the Windows web UI (same values as the other screens) */
private object RxSet {
    val Bg = Color(0xFF060714)
    val PanelS = Color(0xFF0E1131)
    val Panel = Color(0xFF14173C).copy(alpha = 0.72f)
    val Panel2 = Color(0xFF282C68).copy(alpha = 0.50f)
    val Line = Color(0xFF969EFF).copy(alpha = 0.16f)
    val Line2 = Color(0xFF969EFF).copy(alpha = 0.30f)
    val Text = Color(0xFFEEF0FF)
    val Mute = Color(0xFF9AA1D6)
    val Blue = Color(0xFF4D7CFF)
    val Violet = Color(0xFF8B5CF6)
    val Pink = Color(0xFFFF5FB0)
    val Cyan = Color(0xFF38D9FF)
    val Good = Color(0xFF34E0A1)
    val Bad = Color(0xFFFF5C7A)
    val Warn = Color(0xFFFFB86B)
}

@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    onOpenLogs: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenServers: () -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var exitCheckEnabled by remember { mutableStateOf(settingsStore.exitCheckEnabled) }
    var tunnelMtu by remember { mutableIntStateOf(settingsStore.tunnelMtu) }
    var showTunnelMtuDialog by remember { mutableStateOf(false) }
    var dohProvider by remember { mutableStateOf(settingsStore.dohProvider) }
    var customDnsEnabled by remember { mutableStateOf(settingsStore.customDnsEnabled) }
    var customDnsServer by remember { mutableStateOf(settingsStore.customDnsServer) }
    var socksBindAddress by remember { mutableStateOf(settingsStore.socksBindAddress) }
    var socksPort by remember { mutableStateOf(settingsStore.socksPort) }
    var proxyOnlyMode by remember { mutableStateOf(settingsStore.proxyOnlyMode) }
    var customEdgeAddress by remember { mutableStateOf(settingsStore.customEdgeAddress) }
    var upstreamProxyEnabled by remember { mutableStateOf(settingsStore.upstreamProxyEnabled) }
    var upstreamProxyType by remember { mutableStateOf(settingsStore.upstreamProxyType) }
    var upstreamProxyHost by remember { mutableStateOf(settingsStore.upstreamProxyHost) }
    var upstreamProxyPort by remember { mutableStateOf(settingsStore.upstreamProxyPort) }
    var upstreamProxyUsername by remember { mutableStateOf(settingsStore.upstreamProxyUsername) }
    var upstreamProxyPassword by remember { mutableStateOf(settingsStore.upstreamProxyPassword) }
    var excludedApps by remember { mutableStateOf(settingsStore.excludedApps) }
    var includedApps by remember { mutableStateOf(settingsStore.includedApps) }
    var splitMode by remember { mutableStateOf(settingsStore.splitTunnelMode) }
    var routingRegion by remember { mutableStateOf(settingsStore.routingRegion) }
    var pendingInclude by remember { mutableStateOf(false) }
    var bypassLocal by remember { mutableStateOf(settingsStore.bypassLocalNetwork) }
    var bootstrapDoh by remember { mutableStateOf(settingsStore.bootstrapDohProvider) }
    var latencyUrl by remember { mutableStateOf(settingsStore.latencyProbeUrl) }
    var httpVersion by remember { mutableStateOf(settingsStore.httpVersion) }
    var fragMode by remember { mutableStateOf(settingsStore.tlsFragmentMode) }
    var fingerprint by remember { mutableStateOf(settingsStore.utlsFingerprint) }
    var streams by remember { mutableIntStateOf(settingsStore.maxConcurrentStreams) }
    var ipMode by remember { mutableStateOf(settingsStore.ipMode) }
    var autoRecovery by remember { mutableStateOf(settingsStore.automaticRecovery) }
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    var showBootstrapDialog by remember { mutableStateOf(false) }
    var showLatencyDialog by remember { mutableStateOf(false) }
    var showSplitModeDialog by remember { mutableStateOf(false) }
    var showRoutingRegionDialog by remember { mutableStateOf(false) }
    var showTransportDialog by remember { mutableStateOf(false) }
    var showFragModeDialog by remember { mutableStateOf(false) }
    var showFingerprintDialog by remember { mutableStateOf(false) }
    var showStreamsDialog by remember { mutableStateOf(false) }
    var showIpModeDialog by remember { mutableStateOf(false) }
    val tunnelHealth by RxFoxyVpnService.health.collectAsState()
    val tunnelLatency by RxFoxyVpnService.latencyMs.collectAsState()
    val lockdownActive by RxFoxyVpnService.lockdownActive.collectAsState()
    var showDohProviderDialog by remember { mutableStateOf(false) }
    var showCustomDnsDialog by remember { mutableStateOf(false) }
    var showSocksBindDialog by remember { mutableStateOf(false) }
    var showSocksPortDialog by remember { mutableStateOf(false) }
    var showEdgeAddressDialog by remember { mutableStateOf(false) }
    var showUpstreamProxyTypeDialog by remember { mutableStateOf(false) }
    var showUpstreamProxyAddressDialog by remember { mutableStateOf(false) }
    var showUpstreamProxyCredentialsDialog by remember { mutableStateOf(false) }
    var showSplitTunnelPage by remember { mutableStateOf(false) }
    var showRelaySettingsPage by remember { mutableStateOf(false) }
    // Control-plane relay (Mozilla sign-in / Guardian / server list only; independent of the proxy settings above)
    var sharedRelayEnabled by remember { mutableStateOf(settingsStore.sharedRelayEnabled) }
    var personalRelayEnabled by remember { mutableStateOf(settingsStore.personalRelayEnabled) }
    var personalRelayOnly by remember { mutableStateOf(settingsStore.personalRelayOnly) }
    var personalRelayUrl by remember { mutableStateOf(settingsStore.personalRelayUrl) }
    var showRelayUrlDialog by remember { mutableStateOf(false) }
    var relayTesting by remember { mutableStateOf(false) }
    var relayStatus by remember { mutableStateOf<String?>(null) }
    var relayStatusOk by remember { mutableStateOf(false) }
    val relayScope = androidx.compose.runtime.rememberCoroutineScope()
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showAndroidUpdateDialog by remember { mutableStateOf(false) }
    val currentLang by LanguageManager.currentLang.collectAsState()
    val selectedLang by LanguageManager.selectedLang.collectAsState()

    if (showTunnelMtuDialog) {
        TunnelMtuDialog(
            current = tunnelMtu,
            onDismiss = { showTunnelMtuDialog = false },
            onConfirm = { value ->
                tunnelMtu = value
                settingsStore.tunnelMtu = value
                showTunnelMtuDialog = false
                Toast.makeText(context, LanguageManager.t("mtu_saved_reconnect", "MTU saved. Reconnect VPN to apply."), Toast.LENGTH_LONG).show()
            },
        )
    }

    if (showAndroidUpdateDialog) {
        AndroidUpdateDialog(onDismiss = { showAndroidUpdateDialog = false })
    }

    if (showLanguageDialog) {
        LanguagePickerDialog(
            currentTag = selectedLang,
            onDismiss = { showLanguageDialog = false },
            onConfirm = { tag ->
                LanguageManager.setLanguage(context, tag)
                showLanguageDialog = false
            },
        )
    }
    if (showRelayUrlDialog) {
        RelayUrlDialog(
            current = personalRelayUrl,
            onDismiss = { showRelayUrlDialog = false },
            onConfirm = { value ->
                personalRelayUrl = value.trim()
                settingsStore.personalRelayUrl = personalRelayUrl
                relayStatus = null
                showRelayUrlDialog = false
            },
        )
    }

    if (showBootstrapDialog) {
        DohProviderPickerDialog(
            current = bootstrapDoh,
            title = LanguageManager.t("bootstrap_dns", "Bootstrap DNS"),
            onDismiss = { showBootstrapDialog = false },
            onConfirm = { bootstrapDoh = it; settingsStore.bootstrapDohProvider = it; showBootstrapDialog = false },
        )
    }
    if (showLatencyDialog) {
        LatencyUrlDialog(
            current = latencyUrl,
            onDismiss = { showLatencyDialog = false },
            onConfirm = { settingsStore.latencyProbeUrl = it; latencyUrl = settingsStore.latencyProbeUrl; showLatencyDialog = false },
        )
    }
    if (showSplitModeDialog) {
        ChoiceDialog(
            title = LanguageManager.t("split_mode", "Split tunneling mode"),
            options = listOf(
                Triple(SettingsStore.SplitTunnelMode.EXCLUDE, LanguageManager.t("split_exclude", "Exclude apps"), LanguageManager.t("split_exclude_desc", "Selected apps bypass the VPN")),
                Triple(SettingsStore.SplitTunnelMode.INCLUDE, LanguageManager.t("split_include", "Include only"), LanguageManager.t("split_include_desc", "Only selected apps use the VPN")),
            ),
            current = splitMode,
            onDismiss = { showSplitModeDialog = false },
            onConfirm = { mode ->
                showSplitModeDialog = false
                if (mode == SettingsStore.SplitTunnelMode.INCLUDE && includedApps.isEmpty()) {
                    pendingInclude = true; showSplitTunnelPage = true   // Include needs at least one app: pick them now
                } else {
                    splitMode = mode; settingsStore.splitTunnelMode = mode
                }
            },
        )
    }
    if (showRoutingRegionDialog) {
        ChoiceDialog(
            title = LanguageManager.t("routing_region", "Routing region"),
            options = listOf(
                Triple(SettingsStore.RoutingRegion.NONE, LanguageManager.t("region_none", "Off"), LanguageManager.t("region_none_desc", "All traffic goes through the VPN")),
                Triple(SettingsStore.RoutingRegion.IR, LanguageManager.t("region_ir", "Iran"), LanguageManager.t("region_desc", "National sites and services load directly; international traffic goes through the VPN")),
                Triple(SettingsStore.RoutingRegion.CN, LanguageManager.t("region_cn", "China"), LanguageManager.t("region_desc", "National sites and services load directly; international traffic goes through the VPN")),
                Triple(SettingsStore.RoutingRegion.RU, LanguageManager.t("region_ru", "Russia"), LanguageManager.t("region_desc", "National sites and services load directly; international traffic goes through the VPN")),
                Triple(SettingsStore.RoutingRegion.AF, LanguageManager.t("region_af", "Afghanistan"), LanguageManager.t("region_desc", "National sites and services load directly; international traffic goes through the VPN")),
                Triple(SettingsStore.RoutingRegion.TR, LanguageManager.t("region_tr", "Turkey"), LanguageManager.t("region_desc", "National sites and services load directly; international traffic goes through the VPN")),
                Triple(SettingsStore.RoutingRegion.ID, LanguageManager.t("region_id", "Indonesia"), LanguageManager.t("region_desc", "National sites and services load directly; international traffic goes through the VPN")),
                Triple(SettingsStore.RoutingRegion.BR, LanguageManager.t("region_br", "Brazil"), LanguageManager.t("region_desc", "National sites and services load directly; international traffic goes through the VPN")),
            ),
            current = routingRegion,
            onDismiss = { showRoutingRegionDialog = false },
            onConfirm = { region -> routingRegion = region; settingsStore.routingRegion = region; showRoutingRegionDialog = false },
        )
    }
    if (showTransportDialog) {
        ChoiceDialog(
            title = LanguageManager.t("transport_protocol", "Transport protocol"),
            options = listOf(
                Triple(SettingsStore.HttpVersion.HTTP_1, "HTTP/1.1", LanguageManager.t("transport_h1", "Classic HTTP proxy over TLS")),
                Triple(SettingsStore.HttpVersion.HTTP_2, "HTTP/2", LanguageManager.t("transport_h2", "Multiplexed streams over TCP (default)")),
                Triple(SettingsStore.HttpVersion.HTTP_3, "HTTP/3", LanguageManager.t("transport_h3", "QUIC over UDP. Cannot be chained through an upstream proxy")),
            ),
            current = httpVersion,
            onDismiss = { showTransportDialog = false },
            onConfirm = { v ->
                showTransportDialog = false
                if (v == SettingsStore.HttpVersion.HTTP_3 && upstreamProxyEnabled) {
                    Toast.makeText(context, LanguageManager.t("h3_upstream_conflict", "HTTP/3 cannot be used while an upstream proxy is enabled."), Toast.LENGTH_LONG).show()
                } else { httpVersion = v; settingsStore.httpVersion = v }
            },
        )
    }
    if (showFragModeDialog) {
        ChoiceDialog(
            title = LanguageManager.t("frag_mode", "Fragmentation mode"),
            options = listOf(
                Triple(SettingsStore.TlsFragmentMode.RECORD, LanguageManager.t("frag_record", "Record"), LanguageManager.t("frag_record_desc", "Split the TLS record layer")),
                Triple(SettingsStore.TlsFragmentMode.PACKET, LanguageManager.t("frag_packet", "Packet"), LanguageManager.t("frag_packet_desc", "Split TCP segments")),
                Triple(SettingsStore.TlsFragmentMode.COMBINED, LanguageManager.t("frag_combined", "Combined"), LanguageManager.t("frag_combined_desc", "Record and packet")),
            ),
            current = fragMode.takeIf { it != SettingsStore.TlsFragmentMode.OFF } ?: SettingsStore.TlsFragmentMode.COMBINED,
            onDismiss = { showFragModeDialog = false },
            onConfirm = { fragMode = it; settingsStore.tlsFragmentMode = it; showFragModeDialog = false },
        )
    }
    if (showFingerprintDialog) {
        ChoiceDialog(
            title = LanguageManager.t("tls_fingerprint", "Browser TLS fingerprint"),
            options = SettingsStore.UtlsFingerprint.entries.map { Triple(it, it.name.lowercase().replaceFirstChar(Char::uppercase), null as String?) },
            current = fingerprint,
            onDismiss = { showFingerprintDialog = false },
            onConfirm = { fingerprint = it; settingsStore.utlsFingerprint = it; showFingerprintDialog = false },
        )
    }
    if (showStreamsDialog) {
        StreamsDialog(
            current = streams,
            onDismiss = { showStreamsDialog = false },
            onConfirm = { streams = it; settingsStore.maxConcurrentStreams = it; showStreamsDialog = false },
        )
    }
    if (showIpModeDialog) {
        ChoiceDialog(
            title = LanguageManager.t("ip_mode", "IP protocol mode"),
            options = listOf(
                Triple(SettingsStore.IpMode.DUAL_STACK, LanguageManager.t("ip_dual", "Dual-Stack"), LanguageManager.t("ip_dual_desc", "IPv4 and IPv6 (default)")),
                Triple(SettingsStore.IpMode.PREFER_IPV4, LanguageManager.t("ip_pref4", "Prefer IPv4"), null as String?),
                Triple(SettingsStore.IpMode.PREFER_IPV6, LanguageManager.t("ip_pref6", "Prefer IPv6"), null as String?),
                Triple(SettingsStore.IpMode.IPV4_ONLY, LanguageManager.t("ip_v4only", "IPv4 only"), LanguageManager.t("ip_v4only_desc", "IPv6 traffic is blocked, not leaked")),
                Triple(SettingsStore.IpMode.IPV6_ONLY, LanguageManager.t("ip_v6only", "IPv6 only"), LanguageManager.t("ip_v6only_desc", "IPv4 traffic is blocked")),
            ),
            current = ipMode,
            onDismiss = { showIpModeDialog = false },
            onConfirm = { ipMode = it; settingsStore.ipMode = it; showIpModeDialog = false },
        )
    }

    if (showDohProviderDialog) {
        DohProviderPickerDialog(
            current = dohProvider,
            onDismiss = { showDohProviderDialog = false },
            onConfirm = {
                dohProvider = it
                settingsStore.dohProvider = it
                showDohProviderDialog = false
            },
        )
    }
    if (showCustomDnsDialog) {
        CustomDnsPickerDialog(
            current = customDnsServer,
            onDismiss = { showCustomDnsDialog = false },
            onConfirm = {
                customDnsServer = it
                settingsStore.customDnsServer = it
                showCustomDnsDialog = false
            },
        )
    }
    if (showSocksBindDialog) {
        SocksBindAddressPickerDialog(
            current = socksBindAddress,
            onDismiss = { showSocksBindDialog = false },
            onConfirm = {
                socksBindAddress = it
                settingsStore.socksBindAddress = it
                showSocksBindDialog = false
            },
        )
    }
    if (showSocksPortDialog) {
        SocksPortPickerDialog(
            current = socksPort,
            onDismiss = { showSocksPortDialog = false },
            onConfirm = {
                socksPort = it
                settingsStore.socksPort = it
                showSocksPortDialog = false
            },
        )
    }
    if (showEdgeAddressDialog) {
        CustomEdgeAddressDialog(
            current = customEdgeAddress,
            onDismiss = { showEdgeAddressDialog = false },
            onConfirm = {
                customEdgeAddress = it
                settingsStore.customEdgeAddress = it
                showEdgeAddressDialog = false
            },
        )
    }
    if (showUpstreamProxyTypeDialog) {
        UpstreamProxyTypePickerDialog(
            current = upstreamProxyType,
            onDismiss = { showUpstreamProxyTypeDialog = false },
            onConfirm = {
                upstreamProxyType = it
                settingsStore.upstreamProxyType = it
                showUpstreamProxyTypeDialog = false
            },
        )
    }
    if (showUpstreamProxyAddressDialog) {
        UpstreamProxyAddressDialog(
            currentHost = upstreamProxyHost,
            currentPort = upstreamProxyPort,
            onDismiss = { showUpstreamProxyAddressDialog = false },
            onConfirm = { host, port ->
                upstreamProxyHost = host
                upstreamProxyPort = port
                settingsStore.upstreamProxyHost = host
                settingsStore.upstreamProxyPort = port
                showUpstreamProxyAddressDialog = false
            },
        )
    }
    if (showUpstreamProxyCredentialsDialog) {
        UpstreamProxyCredentialsDialog(
            currentUsername = upstreamProxyUsername,
            currentPassword = upstreamProxyPassword,
            onDismiss = { showUpstreamProxyCredentialsDialog = false },
            onConfirm = { username, password ->
                upstreamProxyUsername = username
                upstreamProxyPassword = password
                settingsStore.upstreamProxyUsername = username
                settingsStore.upstreamProxyPassword = password
                showUpstreamProxyCredentialsDialog = false
            },
        )
    }

    // Like HomeScreen: the content area goes all the way up behind the clock / battery,
    // and the header itself pads for the status bar (see `windowInsetsPadding` below).
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.statusBars),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            SettingsBackground()

            if (showSplitTunnelPage) {
                // Full-screen page (not a dialog) – system back returns to settings
                val pageInclude = pendingInclude || splitMode == SettingsStore.SplitTunnelMode.INCLUDE
                SplitTunnelPage(
                    current = if (pageInclude) includedApps else excludedApps,
                    includeMode = pageInclude,
                    onClose = { pendingInclude = false; showSplitTunnelPage = false },
                    onSave = {
                        if (pageInclude) {
                            if (it.isEmpty()) {
                                Toast.makeText(context, LanguageManager.t("include_needs_app", "Select at least one app, or switch back to Exclude mode."), Toast.LENGTH_LONG).show()
                                return@SplitTunnelPage
                            }
                            includedApps = it; settingsStore.includedApps = it
                            splitMode = SettingsStore.SplitTunnelMode.INCLUDE; settingsStore.splitTunnelMode = splitMode
                            pendingInclude = false
                        } else {
                            excludedApps = it
                            settingsStore.excludedApps = it
                        }
                        showSplitTunnelPage = false
                    },
                )
            } else if (showRelaySettingsPage) {
                RelaySettingsPage(
                    sharedRelayEnabled = sharedRelayEnabled,
                    onSharedRelayChanged = {
                        sharedRelayEnabled = it
                        settingsStore.sharedRelayEnabled = it
                    },
                    personalRelayEnabled = personalRelayEnabled,
                    onPersonalRelayChanged = {
                        personalRelayEnabled = it
                        settingsStore.personalRelayEnabled = it
                    },
                    personalRelayUrl = personalRelayUrl,
                    onOpenRelayUrlDialog = { showRelayUrlDialog = true },
                    personalRelayOnly = personalRelayOnly,
                    onPersonalRelayOnlyChanged = {
                        personalRelayOnly = it
                        settingsStore.personalRelayOnly = it
                    },
                    relayTesting = relayTesting,
                    relayStatus = relayStatus,
                    onTestPersonalRelay = {
                        if (!relayTesting) {
                            val parsed = com.vauth.rxfoxyvpn.data.relay.RelayPolicy.validatePersonalRelayUrl(personalRelayUrl)
                            val url = parsed.getOrNull()
                            if (url == null) {
                                relayStatusOk = false
                                relayStatus = relayUrlErrorText(parsed.exceptionOrNull())
                            } else {
                                relayTesting = true
                                relayScope.launch {
                                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        com.vauth.rxfoxyvpn.data.relay.RelayRuntime.healthChecker.check(url)
                                    }
                                    relayTesting = false
                                    relayStatusOk = result.ready
                                    relayStatus = if (result.ready) {
                                        LanguageManager.t("relay_status_ok", "Relay is ready") + " (${result.elapsedMs} ms)"
                                    } else {
                                        relayFailureText(result.failure)
                                    }
                                }
                            }
                        }
                    },
                    onClose = { showRelaySettingsPage = false },
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    BackHandler(enabled = settingsPage != null) { settingsPage = null }
                    PageHeader(
                        title = settingsPage?.let { groupTitle(it) } ?: LanguageManager.t("settings", "Settings"),
                        subtitle = null,
                        onBack = { if (settingsPage != null) settingsPage = null else onBack() },
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        // ---------- Main menu (grouped like reference) ----------
                        if (settingsPage == null) {
                            // —— Primary ——
                            Section("") {
                                NavRow(
                                    icon = FoxyIcons.Tune,
                                    tint = RxSet.Cyan,
                                    title = LanguageManager.t("general", "General"),
                                    subtitle = null,
                                    onClick = { settingsPage = "general" },
                                )
                                RowDivider()
                                NavRow(
                                    icon = FoxyIcons.Public,
                                    tint = RxSet.Blue,
                                    title = LanguageManager.t("servers", "Servers"),
                                    subtitle = null,
                                    onClick = onOpenServers,
                                )
                                RowDivider()
                                NavRow(
                                    icon = Icons.Default.AccountCircle,
                                    tint = RxSet.Good,
                                    title = LanguageManager.t("account_section", "Accounts"),
                                    subtitle = null,
                                    onClick = onOpenAccount,
                                )
                            }

                            // —— Network ——
                            Section(LanguageManager.t("network_section", "Network")) {
                                NavRow(
                                    icon = FoxyIcons.Link,
                                    tint = RxSet.Blue,
                                    title = LanguageManager.t("connection", "Connection"),
                                    subtitle = null,
                                    onClick = { settingsPage = "connection_proxy" },
                                )
                                RowDivider()
                                NavRow(
                                    icon = FoxyIcons.Apps,
                                    tint = RxSet.Pink,
                                    title = LanguageManager.t("routing", "Routing"),
                                    subtitle = null,
                                    onClick = { settingsPage = "routing" },
                                )
                                RowDivider()
                                NavRow(
                                    icon = FoxyIcons.Dns,
                                    tint = RxSet.Blue,
                                    title = "DNS",
                                    subtitle = null,
                                    onClick = { settingsPage = "network_dns" },
                                )
                                RowDivider()
                                NavRow(
                                    icon = FoxyIcons.Speed,
                                    tint = healthColor(tunnelHealth),
                                    title = LanguageManager.t("connection_health", "Connection health"),
                                    subtitle = null,
                                    onClick = { settingsPage = "connection_health" },
                                )
                            }

                            // —— If Mozilla is blocked ——
                            Section(LanguageManager.t("relay_blocked_section", "If Mozilla is blocked")) {
                                val relaySummary = when {
                                    personalRelayEnabled && personalRelayOnly ->
                                        LanguageManager.t("relay_summary_personal_only", "Personal relay only")
                                    sharedRelayEnabled && personalRelayEnabled ->
                                        LanguageManager.t("relay_summary_both", "Shared & personal relay active")
                                    sharedRelayEnabled ->
                                        LanguageManager.t("relay_summary_shared", "Shared relay active")
                                    personalRelayEnabled ->
                                        LanguageManager.t("relay_summary_personal", "Personal relay active")
                                    else -> null
                                }
                                NavRow(
                                    icon = FoxyIcons.Router,
                                    tint = RxSet.Cyan,
                                    title = LanguageManager.t("relay_server_menu", "Relay server"),
                                    subtitle = relaySummary,
                                    onClick = { showRelaySettingsPage = true },
                                )
                                RowDivider()
                                NavRow(
                                    icon = FoxyIcons.Security,
                                    tint = RxSet.Violet,
                                    title = LanguageManager.t("tls_fragmentation", "TLS fragmentation"),
                                    subtitle = null,
                                    onClick = { settingsPage = "anticensor_perf" },
                                )
                                RowDivider()
                                NavRow(
                                    icon = FoxyIcons.Link,
                                    tint = RxSet.Pink,
                                    title = LanguageManager.t("upstream_proxy", "Upstream proxy"),
                                    subtitle = if (upstreamProxyEnabled) LanguageManager.t("active", "Active") else null,
                                    onClick = { settingsPage = "upstream_proxy" },
                                )
                            }

                            // —— App ——
                            Section(LanguageManager.t("app_section", "App")) {
                                NavRow(
                                    icon = FoxyIcons.Description,
                                    tint = RxSet.Cyan,
                                    title = LanguageManager.t("logs", "Logs"),
                                    subtitle = null,
                                    onClick = onOpenLogs,
                                )
                                RowDivider()
                                NavRow(
                                    icon = Icons.Default.Info,
                                    tint = RxSet.Violet,
                                    title = LanguageManager.t("about", "About"),
                                    subtitle = null,
                                    onClick = { settingsPage = "about_page" },
                                )
                            }
                        }

                        // ---------- 1. General ----------
                        GroupPage(settingsPage, "general", LanguageManager.t("general", "General"), initiallyExpanded = true) {
                            NavRow(
                                icon = FoxyIcons.Language,
                                tint = RxSet.Cyan,
                                title = LanguageManager.t("language", "Language"),
                                subtitle = if (selectedLang == "auto") "${LanguageManager.t("auto", "Automatic")} (${LanguageManager.getLanguageNativeName(currentLang)})" else LanguageManager.getLanguageNativeName(selectedLang),
                                onClick = { showLanguageDialog = true },
                            )
                            RowDivider()
                            SwitchRow(
                                icon = Icons.Default.Place,
                                tint = RxSet.Good,
                                title = LanguageManager.t("verify_exit", "Verify exit location"),
                                subtitle = LanguageManager.t("verify_exit_desc", "Check that the tunnel exits in the selected country"),
                                checked = exitCheckEnabled,
                                onChecked = { exitCheckEnabled = it; settingsStore.exitCheckEnabled = it },
                            )
                        }

                        // ---------- 2. Network & DNS ----------
                        GroupPage(settingsPage, "network_dns", LanguageManager.t("network_dns", "Network & DNS"), summary = "MTU $tunnelMtu") {
                            NavRow(
                                icon = FoxyIcons.Tune,
                                tint = RxSet.Cyan,
                                title = LanguageManager.t("tunnel_mtu", "Tunnel MTU"),
                                subtitle = LanguageManager.t("tunnel_mtu_desc", "Packet size for the VPN tunnel") + " · $tunnelMtu · " + LanguageManager.t("applies_next", "Applies on next connection."),
                                onClick = { showTunnelMtuDialog = true },
                            )
                            RowDivider()
                            NavRow(
                                icon = FoxyIcons.Security,
                                tint = RxSet.Violet,
                                title = LanguageManager.t("encrypted_dns", "Encrypted DNS"),
                                subtitle = dohSummary(dohProvider),
                                onClick = { showDohProviderDialog = true },
                            )
                            RowDivider()
                            NavRow(
                                icon = FoxyIcons.Dns,
                                tint = RxSet.Blue,
                                title = LanguageManager.t("bootstrap_dns", "Bootstrap DNS"),
                                subtitle = LanguageManager.t("bootstrap_dns_desc", "Resolves the VPN server and DNS hostnames only") + " · " + dohSummary(bootstrapDoh),
                                onClick = { showBootstrapDialog = true },
                            )
                            RowDivider()
                            SwitchRow(
                                icon = FoxyIcons.Dns,
                                tint = RxSet.Cyan,
                                title = LanguageManager.t("custom_dns", "Use a custom DNS server"),
                                subtitle = LanguageManager.t("custom_dns_desc", "Send your apps' DNS queries to a server you choose"),
                                checked = customDnsEnabled,
                                onChecked = { customDnsEnabled = it; settingsStore.customDnsEnabled = it },
                            )
                            AnimatedVisibility(visible = customDnsEnabled) {
                                Column {
                                    RowDivider()
                                    NavRow(
                                        icon = FoxyIcons.Tune,
                                        tint = RxSet.Cyan,
                                        title = LanguageManager.t("dns_server", "DNS server"),
                                        subtitle = SettingsStore.CUSTOM_DNS_PRESETS.firstOrNull { it.first == customDnsServer }?.second ?: customDnsServer,
                                        onClick = { showCustomDnsDialog = true },
                                    )
                                }
                            }
                            RowDivider()
                            NavRow(
                                icon = FoxyIcons.Speed,
                                tint = RxSet.Good,
                                title = LanguageManager.t("latency_url", "Latency test URL"),
                                subtitle = latencyUrl,
                                onClick = { showLatencyDialog = true },
                            )
                        }

                        // ---------- 3. Routing ----------
                        GroupPage(settingsPage, "routing", LanguageManager.t("routing", "Routing"),
                            summary = if (splitMode == SettingsStore.SplitTunnelMode.INCLUDE) LanguageManager.t("split_include", "Include only") else LanguageManager.t("split_exclude", "Exclude apps"),
                        ) {
                            NavRow(
                                icon = FoxyIcons.Public,
                                tint = RxSet.Blue,
                                title = LanguageManager.t("routing_region", "Routing region"),
                                subtitle = regionLabel(routingRegion) + " · " + LanguageManager.t("applies_next", "Applies on next connection."),
                                onClick = { showRoutingRegionDialog = true },
                            )
                            RowDivider()
                            NavRow(
                                icon = FoxyIcons.Tune,
                                tint = RxSet.Pink,
                                title = LanguageManager.t("split_mode", "Split tunneling mode"),
                                subtitle = if (splitMode == SettingsStore.SplitTunnelMode.INCLUDE) LanguageManager.t("split_include_desc", "Only selected apps use the VPN") else LanguageManager.t("split_exclude_desc", "Selected apps bypass the VPN"),
                                onClick = { showSplitModeDialog = true },
                            )
                            RowDivider()
                            val activeApps = if (splitMode == SettingsStore.SplitTunnelMode.INCLUDE) includedApps else excludedApps
                            SettingRow(
                                icon = FoxyIcons.Apps,
                                tint = RxSet.Pink,
                                title = LanguageManager.t("split_tunneling", "Split tunneling"),
                                subtitle = if (activeApps.isEmpty()) LanguageManager.t("none", "None")
                                else LanguageManager.tn("split_apps_count", "{n} apps selected", activeApps.size),
                                onClick = { showSplitTunnelPage = true },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (activeApps.isNotEmpty()) {
                                            ExcludedIconStack(activeApps.toList())
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Chevron()
                                    }
                                },
                            )
                            RowDivider()
                            SwitchRow(
                                icon = FoxyIcons.Router,
                                tint = RxSet.Warn,
                                title = LanguageManager.t("bypass_local", "Bypass VPN for local network"),
                                subtitle = LanguageManager.t(
                                    "bypass_local_desc",
                                    "Access private and link-local IP addresses directly; internet traffic stays in the VPN. Applies on next connection. Android block-without-VPN can block this access.",
                                ),
                                checked = bypassLocal,
                                onChecked = { bypassLocal = it; settingsStore.bypassLocalNetwork = it },
                            )
                            if (bypassLocal && lockdownActive) {
                                Text(
                                    LanguageManager.t("lockdown_warning", "Android \"Block connections without VPN\" is on, so local-network access will still be blocked. Turn it off in Android VPN settings to use this option."),
                                    color = RxSet.Warn, fontSize = 12.sp, lineHeight = 16.sp,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }

                        // ---------- 4. Connection ----------
                        GroupPage(settingsPage, "connection_proxy", LanguageManager.t("connection", "Connection"),
                            summary = when (httpVersion) { SettingsStore.HttpVersion.HTTP_1 -> "HTTP/1.1"; SettingsStore.HttpVersion.HTTP_2 -> "HTTP/2"; SettingsStore.HttpVersion.HTTP_3 -> "HTTP/3" },
                        ) {
                            NavRow(
                                icon = FoxyIcons.Public,
                                tint = RxSet.Blue,
                                title = LanguageManager.t("custom_edge", "Custom edge address"),
                                subtitle = customEdgeAddress.ifBlank { LanguageManager.t("not_set", "Not set") },
                                onClick = { showEdgeAddressDialog = true },
                            )
                            RowDivider()
                            NavRow(
                                icon = FoxyIcons.Link,
                                tint = RxSet.Cyan,
                                title = LanguageManager.t("transport_protocol", "Transport protocol"),
                                subtitle = when (httpVersion) { SettingsStore.HttpVersion.HTTP_1 -> "HTTP/1.1"; SettingsStore.HttpVersion.HTTP_2 -> "HTTP/2"; SettingsStore.HttpVersion.HTTP_3 -> "HTTP/3" } + " · " + LanguageManager.t("applies_next", "Applies on next connection."),
                                onClick = { showTransportDialog = true },
                            )
                            RowDivider()
                            SwitchRow(
                                icon = FoxyIcons.Router,
                                tint = RxSet.Warn,
                                title = LanguageManager.t("proxy_only", "Proxy-only mode"),
                                subtitle = LanguageManager.t("proxy_only_desc", "Run only the local SOCKS5 proxy, without a VPN interface"),
                                checked = proxyOnlyMode,
                                onChecked = { proxyOnlyMode = it; settingsStore.proxyOnlyMode = it },
                            )
                            RowDivider()
                            NavRow(FoxyIcons.Link, RxSet.Cyan, LanguageManager.t("local_address", "Local address"), socksBindAddress) { showSocksBindDialog = true }
                            RowDivider()
                            NavRow(FoxyIcons.Tune, RxSet.Cyan, LanguageManager.t("local_port", "Local port"), socksPort.toString()) { showSocksPortDialog = true }
                        }

                        // ---------- 4b. Upstream proxy (Mozilla-block path) ----------
                        GroupPage(settingsPage, "upstream_proxy", LanguageManager.t("upstream_proxy", "Upstream proxy"),
                            summary = if (upstreamProxyEnabled) LanguageManager.t("active", "Active") else null,
                        ) {
                            SwitchRow(
                                icon = FoxyIcons.Link,
                                tint = RxSet.Pink,
                                title = LanguageManager.t("upstream_proxy", "Chain through upstream proxy"),
                                subtitle = LanguageManager.t("upstream_proxy_desc", "Connect to the VPN server through another proxy first"),
                                checked = upstreamProxyEnabled,
                                onChecked = {
                                    if (it && httpVersion == SettingsStore.HttpVersion.HTTP_3) {
                                        Toast.makeText(context, LanguageManager.t("h3_upstream_conflict", "HTTP/3 cannot be used while an upstream proxy is enabled."), Toast.LENGTH_LONG).show()
                                    } else { upstreamProxyEnabled = it; settingsStore.upstreamProxyEnabled = it }
                                },
                            )
                            AnimatedVisibility(visible = upstreamProxyEnabled) {
                                Column {
                                    RowDivider()
                                    NavRow(FoxyIcons.Tune, RxSet.Pink, LanguageManager.t("proxy_type", "Proxy type"), upstreamProxyType.name) { showUpstreamProxyTypeDialog = true }
                                    RowDivider()
                                    NavRow(
                                        FoxyIcons.Router, RxSet.Pink, LanguageManager.t("proxy_address", "Proxy address"),
                                        if (upstreamProxyHost.isBlank()) LanguageManager.t("not_set", "Not set") else "$upstreamProxyHost:$upstreamProxyPort",
                                    ) { showUpstreamProxyAddressDialog = true }
                                    RowDivider()
                                    NavRow(
                                        Icons.Default.Lock, RxSet.Pink, LanguageManager.t("proxy_credentials", "Proxy credentials"),
                                        if (upstreamProxyUsername.isBlank()) LanguageManager.t("none", "None") else upstreamProxyUsername,
                                    ) { showUpstreamProxyCredentialsDialog = true }
                                }
                            }
                        }

                        // ---------- 5. Anti-Censorship & Performance ----------
                        GroupPage(settingsPage, "anticensor_perf", LanguageManager.t("anticensor_perf", "Anti-Censorship & Performance"),
                            summary = if (fragMode == SettingsStore.TlsFragmentMode.OFF) LanguageManager.t("fragment_off", "Fragmentation off") else LanguageManager.t("fragment_on", "Fragmentation on"),
                        ) {
                            SwitchRow(
                                icon = FoxyIcons.Security,
                                tint = RxSet.Violet,
                                title = LanguageManager.t("tls_fragmentation", "TLS fragmentation"),
                                subtitle = LanguageManager.t("tls_fragmentation_desc", "Split the TLS handshake to evade filtering. Disabled by default. Applies on next connection."),
                                checked = fragMode != SettingsStore.TlsFragmentMode.OFF,
                                onChecked = {
                                    val m = if (it) SettingsStore.TlsFragmentMode.COMBINED else SettingsStore.TlsFragmentMode.OFF
                                    fragMode = m; settingsStore.tlsFragmentMode = m
                                },
                            )
                            AnimatedVisibility(visible = fragMode != SettingsStore.TlsFragmentMode.OFF) {
                                Column {
                                    RowDivider()
                                    NavRow(
                                        FoxyIcons.Tune, RxSet.Violet, LanguageManager.t("frag_mode", "Fragmentation mode"),
                                        fragMode.name.lowercase().replaceFirstChar(Char::uppercase),
                                    ) { showFragModeDialog = true }
                                }
                            }
                            RowDivider()
                            NavRow(
                                icon = Icons.Default.Lock,
                                tint = RxSet.Blue,
                                title = LanguageManager.t("tls_fingerprint", "Browser TLS fingerprint"),
                                subtitle = fingerprint.name.lowercase().replaceFirstChar(Char::uppercase) + (if (httpVersion == SettingsStore.HttpVersion.HTTP_3) " · " + LanguageManager.t("fp_not_h3", "not used with HTTP/3") else ""),
                                onClick = { showFingerprintDialog = true },
                            )
                            RowDivider()
                            NavRow(
                                icon = FoxyIcons.Speed,
                                tint = RxSet.Good,
                                title = LanguageManager.t("multiplex", "Multiplex concurrency"),
                                subtitle = LanguageManager.tn("stream_count", "{n} streams", streams) + (if (httpVersion != SettingsStore.HttpVersion.HTTP_2) " · " + LanguageManager.t("http2_only", "HTTP/2 only") else ""),
                                onClick = { showStreamsDialog = true },
                            )
                            RowDivider()
                            NavRow(
                                icon = FoxyIcons.Public,
                                tint = RxSet.Cyan,
                                title = LanguageManager.t("ip_mode", "IP protocol mode"),
                                subtitle = when (ipMode) {
                                    SettingsStore.IpMode.DUAL_STACK -> "Dual-Stack"
                                    SettingsStore.IpMode.PREFER_IPV4 -> "Prefer IPv4"
                                    SettingsStore.IpMode.PREFER_IPV6 -> "Prefer IPv6"
                                    SettingsStore.IpMode.IPV4_ONLY -> "IPv4 only"
                                    SettingsStore.IpMode.IPV6_ONLY -> "IPv6 only"
                                },
                                onClick = { showIpModeDialog = true },
                            )
                        }

                        // ---------- 6. Connection Health ----------
                        GroupPage(settingsPage, "connection_health", LanguageManager.t("connection_health", "Connection Health"), summary = healthLabel(tunnelHealth)) {
                            SettingRow(
                                icon = FoxyIcons.Speed,
                                tint = healthColor(tunnelHealth),
                                title = LanguageManager.t("tunnel_health", "Tunnel health"),
                                subtitle = tunnelLatency?.let { "$it ms" } ?: LanguageManager.t("health_no_data", "Measured by real traffic through the tunnel"),
                                trailing = {
                                    Text(healthLabel(tunnelHealth), color = healthColor(tunnelHealth), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                },
                            )
                            RowDivider()
                            SwitchRow(
                                icon = FoxyIcons.Security,
                                tint = RxSet.Good,
                                title = LanguageManager.t("auto_recovery", "Automatic connection recovery"),
                                subtitle = LanguageManager.t("auto_recovery_desc", "Reconnect after repeated confirmed proxy-path failures; stay in the selected country. Applies on next connection."),
                                checked = autoRecovery,
                                onChecked = { autoRecovery = it; settingsStore.automaticRecovery = it },
                            )
                        }

                        // About sub-page (opened from App → About)
                        GroupPage(settingsPage, "about_page", LanguageManager.t("about", "About")) {
                            SettingRow(
                                icon = Icons.Default.Info,
                                tint = RxSet.Violet,
                                title = LanguageManager.t("version", "Version"),
                                subtitle = "V${BuildConfig.VERSION_NAME.removePrefix("v").removePrefix("V")}",
                            )
                            RowDivider()
                            NavRow(
                                icon = FoxyIcons.SystemUpdateAlt,
                                tint = RxSet.Cyan,
                                title = updateSettingsLabel(currentLang, "title"),
                                subtitle = null,
                                onClick = { showAndroidUpdateDialog = true },
                            )
                            RowDivider()
                            NavRow(
                                icon = FoxyIcons.Code,
                                tint = RxSet.Violet,
                                title = LanguageManager.t("source_code", "Source code"),
                                subtitle = null,
                                onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/omidrx0/RxFoxyVPN"))
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                },
                            )
                        }

                        // Version footer only on the main settings list (not inside sub-pages)
                        if (settingsPage == null) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Image(painterResource(id = R.drawable.rx_logo), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("RXFOXYVPN  ·  V${BuildConfig.VERSION_NAME.removePrefix("v").removePrefix("V")}", color = RxSet.Mute, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------------------------------
   Shared building blocks
   ------------------------------------------------------------------------------------------ */
@Composable
private fun SettingsBackground() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0D1033), RxSet.Bg)))
    ) {
        Image(
            painter = painterResource(id = R.drawable.rx_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.28f },
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(RxSet.Bg.copy(alpha = 0.70f), RxSet.Bg.copy(alpha = 0.50f), RxSet.Bg.copy(alpha = 0.80f))
                    )
                )
        )
        Canvas(Modifier.fillMaxSize()) {
            val big = size.maxDimension
            fun blob(color: Color, center: Offset, radius: Float) = drawCircle(
                brush = Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = center, radius = radius),
                radius = radius,
                center = center,
            )
            blob(Color(0xFF3A62FF).copy(alpha = 0.26f), Offset(0f, 0f), big * 0.55f)
            blob(Color(0xFF8A3CFF).copy(alpha = 0.24f), Offset(size.width, size.height), big * 0.55f)
        }
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String?, onBack: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF080A20).copy(alpha = 0.55f))
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = LanguageManager.t("back", "Back"), tint = RxSet.Text, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = RxSet.Text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                if (subtitle != null) Text(subtitle, color = RxSet.Mute, fontSize = 11.sp)
            }
            if (trailing != null) {
                trailing()
                Spacer(Modifier.width(8.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(RxSet.Line))
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title.isNotBlank()) {
            Text(
                title,
                color = RxSet.Mute,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = RxSet.Panel,
            border = BorderStroke(1.dp, RxSet.Line),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

/** Shows [content] only while its settings page is the open one; the category list lives on the main page. */
@Composable
private fun GroupPage(
    current: String?,
    key: String,
    title: String,
    summary: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (current == key) Section(title, content)
}

private fun groupTitle(key: String): String = when (key) {
    "general" -> LanguageManager.t("general", "General")
    "network_dns" -> LanguageManager.t("network_dns", "Network & DNS")
    "routing" -> LanguageManager.t("routing", "Routing")
    "connection_proxy" -> LanguageManager.t("connection_proxy", "Connection & Proxy")
    "anticensor_perf" -> LanguageManager.t("anticensor_perf", "Anti-Censorship & Performance")
    "connection_health" -> LanguageManager.t("connection_health", "Connection Health")
    "about_page" -> LanguageManager.t("about", "About")
    "upstream_proxy" -> LanguageManager.t("upstream_proxy", "Upstream proxy")
    else -> key
}

private fun dohSummary(p: SettingsStore.DohProvider): String = when (p) {
    SettingsStore.DohProvider.AUTOMATIC -> LanguageManager.t("doh_auto", "Use whichever answers first")
    SettingsStore.DohProvider.OFF -> LanguageManager.t("doh_off", "Use the network's own DNS")
    else -> p.label + " · " + p.addresses.joinToString(", ")
}

private fun healthLabel(h: TunnelHealth): String = when (h) {
    TunnelHealth.CONNECTED -> LanguageManager.t("health_connected", "Connected")
    TunnelHealth.CONNECTING -> LanguageManager.t("health_connecting", "Connecting")
    TunnelHealth.DISCONNECTED -> LanguageManager.t("health_disconnected", "Disconnected")
    TunnelHealth.ERROR -> LanguageManager.t("health_error", "Error")
    TunnelHealth.RECOVERING -> LanguageManager.t("health_recovering", "Recovering")
}

private fun healthColor(h: TunnelHealth): Color = when (h) {
    TunnelHealth.CONNECTED -> RxSet.Good
    TunnelHealth.CONNECTING, TunnelHealth.RECOVERING -> RxSet.Warn
    TunnelHealth.ERROR -> RxSet.Bad
    TunnelHealth.DISCONNECTED -> RxSet.Mute
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Triple<T, String, String?>>,
    current: T,
    onDismiss: () -> Unit,
    onConfirm: (T) -> Unit,
) {
    var selected by remember { mutableStateOf(current) }
    RxDialog(title = title, onDismiss = onDismiss, onConfirm = { onConfirm(selected) }) {
        options.forEach { (value, label, detail) -> ChoiceRow(selected == value, label, detail) { selected = value } }
    }
}

@Composable
private fun StreamsDialog(current: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember(current) { mutableStateOf(current.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in 2..128
    RxDialog(
        title = LanguageManager.t("multiplex", "Multiplex concurrency"),
        onDismiss = onDismiss,
        onConfirm = { parsed?.let(onConfirm) },
        confirmEnabled = valid,
    ) {
        Text(LanguageManager.t("multiplex_range", "Streams per HTTP/2 connection, 2 to 128. Default: 32. Applies on next connection."), color = RxSet.Mute, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        RxField(
            value = text,
            onChange = { text = it.filter(Char::isDigit).take(3) },
            label = LanguageManager.t("streams", "Streams"),
            keyboardType = KeyboardType.Number,
            isError = text.isNotBlank() && !valid,
        )
    }
}

@Composable
private fun LatencyUrlDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(current) { mutableStateOf(current) }
    val trimmed = value.trim()
    val valid = trimmed.isEmpty() || SettingsStore.isValidProbeUrl(trimmed)
    RxDialog(
        title = LanguageManager.t("latency_url", "Latency test URL"),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(trimmed) },
        confirmEnabled = valid,
    ) {
        Text(LanguageManager.t("latency_url_desc", "HTTPS address fetched through the tunnel to measure latency and health. Leave empty for the default."), color = RxSet.Mute, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        RxField(value = value, onChange = { value = it }, label = "URL", keyboardType = KeyboardType.Uri, isError = !valid)
    }
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(1.dp).background(RxSet.Line))
}

@Composable
private fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = RxSet.Mute, modifier = Modifier.size(22.dp))
}

@Composable
private fun IconTile(icon: ImageVector, tint: Color, size: Dp = 38.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.22f), tint.copy(alpha = 0.08f))))
            .border(1.dp, tint.copy(alpha = 0.28f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String?,
    titleColor: Color = RxSet.Text,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val vPad = if (subtitle == null) 13.dp else 12.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = vPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (subtitle != null) {
                Text(subtitle, color = RxSet.Mute, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

private fun regionLabel(region: SettingsStore.RoutingRegion): String = when (region) {
    SettingsStore.RoutingRegion.NONE -> LanguageManager.t("region_none", "Off")
    SettingsStore.RoutingRegion.IR -> LanguageManager.t("region_ir", "Iran")
    SettingsStore.RoutingRegion.CN -> LanguageManager.t("region_cn", "China")
    SettingsStore.RoutingRegion.RU -> LanguageManager.t("region_ru", "Russia")
    SettingsStore.RoutingRegion.AF -> LanguageManager.t("region_af", "Afghanistan")
    SettingsStore.RoutingRegion.TR -> LanguageManager.t("region_tr", "Turkey")
    SettingsStore.RoutingRegion.ID -> LanguageManager.t("region_id", "Indonesia")
    SettingsStore.RoutingRegion.BR -> LanguageManager.t("region_br", "Brazil")
}

@Composable
private fun NavRow(icon: ImageVector, tint: Color, title: String, subtitle: String?, onClick: () -> Unit) {
    SettingRow(icon, tint, title, subtitle, onClick = onClick, trailing = { Chevron() })
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    SettingRow(
        icon = icon, tint = tint, title = title, subtitle = subtitle,
        onClick = { onChecked(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onChecked,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = RxSet.Violet,
                    checkedBorderColor = RxSet.Violet,
                    uncheckedThumbColor = RxSet.Mute,
                    uncheckedTrackColor = RxSet.Panel2,
                    uncheckedBorderColor = RxSet.Line2,
                ),
            )
        },
    )
}

@Composable
private fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 50.dp,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .height(height)
            .graphicsLayer { alpha = if (enabled) 1f else 0.55f }
            .shadow(12.dp, shape, ambientColor = RxSet.Violet, spotColor = RxSet.Violet)
            .clip(shape)
            .background(Brush.linearGradient(listOf(RxSet.Blue, RxSet.Violet, RxSet.Pink)))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/* ------------------------------------------------------------------------------------------
   App icons (cached) + split tunneling
   ------------------------------------------------------------------------------------------ */
private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

@Composable
private fun AppIcon(packageName: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = iconCache[packageName], packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    iconCache.getOrPut(packageName) {
                        val drawable = context.packageManager.getApplicationIcon(packageName)
                        val px = 112
                        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bitmap)
                        drawable.setBounds(0, 0, px, px)
                        drawable.draw(canvas)
                        bitmap.asImageBitmap()
                    }
                }.getOrNull()
            }
        }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.26f))
            .background(RxSet.Panel2),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = icon
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Icon(FoxyIcons.Apps, contentDescription = null, tint = RxSet.Mute, modifier = Modifier.size(size * 0.5f))
        }
    }
}

/** Overlapping icons of the first excluded apps, shown on the settings row. */
@Composable
private fun ExcludedIconStack(packages: List<String>) {
    val shown = packages.take(4)
    Row(horizontalArrangement = Arrangement.spacedBy((-9).dp), verticalAlignment = Alignment.CenterVertically) {
        shown.forEach { pkg ->
            Box(Modifier.clip(RoundedCornerShape(9.dp)).background(RxSet.PanelS).padding(1.5.dp)) {
                AppIcon(pkg, 26.dp)
            }
        }
        if (packages.size > shown.size) {
            Box(
                Modifier
                    .size(29.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(RxSet.PanelS)
                    .padding(1.5.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(RxSet.Panel2),
                contentAlignment = Alignment.Center,
            ) {
                Text("+${packages.size - shown.size}", color = RxSet.Text, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private data class InstalledAppEntry(val packageName: String, val label: String, val isSystem: Boolean)

@Composable
private fun SplitTunnelPage(
    current: Set<String>,
    includeMode: Boolean = false,
    onClose: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    var selected by remember { mutableStateOf(current) }
    var searchQuery by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) } // 0 = all apps, 1 = selected only
    var showSystemApps by remember { mutableStateOf(true) }

    var loaded by remember { mutableStateOf(false) }
    val installedApps by produceState<List<InstalledAppEntry>>(initialValue = emptyList(), showSystemApps) {
        loaded = false
        value = withContext(Dispatchers.IO) {
            val packageManager = context.packageManager
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != context.packageName }
                .map { info: ApplicationInfo ->
                    val isSystem = (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                    val label = info.loadLabel(packageManager).toString()
                    InstalledAppEntry(info.packageName, label, isSystem)
                }
                .filter { app ->
                    if (showSystemApps) {
                        true
                    } else {
                        !app.isSystem && packageManager.getLaunchIntentForPackage(app.packageName) != null
                    }
                }
                .sortedBy { it.label.lowercase() }
                .toList()
        }
        loaded = true
    }

    val shownApps = remember(installedApps, searchQuery, tab, selected) {
        installedApps.filter { app ->
            (tab == 0 || selected.contains(app.packageName)) &&
                    (searchQuery.isBlank() ||
                            app.label.contains(searchQuery, ignoreCase = true) ||
                            app.packageName.contains(searchQuery, ignoreCase = true))
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = LanguageManager.t("split_tunneling", "Split tunneling"),
            subtitle = if (selected.isEmpty()) {
                LanguageManager.t("none", "None")
            } else {
                LanguageManager.tn("excluded_apps_count", "{n} apps bypass the VPN", selected.size)
            },
            onBack = onClose,
        )

        Column(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Only show a short tip in Include mode (must pick at least one app).
            if (includeMode) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(listOf(RxSet.Blue.copy(alpha = 0.14f), RxSet.Violet.copy(alpha = 0.12f))))
                        .border(1.dp, RxSet.Line2, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = RxSet.Cyan, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        LanguageManager.t("split_hint_include", "Only the selected apps use the VPN; everything else uses your normal connection. Pick at least one app."),
                        color = RxSet.Text, fontSize = 12.5.sp, lineHeight = 18.sp,
                    )
                }
            }

            // Show system apps toggle row
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(RxSet.Panel2)
                    .clickable { showSystemApps = !showSystemApps }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    LanguageManager.t("show_system_apps", "Show system apps"),
                    color = RxSet.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = showSystemApps,
                    onCheckedChange = { showSystemApps = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = RxSet.Violet,
                        uncheckedThumbColor = RxSet.Mute,
                        uncheckedTrackColor = RxSet.PanelS,
                    ),
                )
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(LanguageManager.t("search_apps", "Search apps…"), color = RxSet.Mute) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = RxSet.Mute) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = null, tint = RxSet.Mute)
                        }
                    }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(13.dp),
                colors = rxFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                TabChip(
                    text = LanguageManager.t("all_apps", "All apps"),
                    on = tab == 0,
                    onClick = { tab = 0 },
                )
                Spacer(Modifier.width(8.dp))
                TabChip(
                    text = "${LanguageManager.t("selected_apps", "Selected")} (${selected.size})",
                    on = tab == 1,
                    onClick = { tab = 1 },
                )
                Spacer(Modifier.weight(1f))
                if (selected.isNotEmpty()) {
                    TextButton(onClick = { selected = emptySet() }) {
                        Text(LanguageManager.t("clear", "Clear"), color = RxSet.Bad, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = RxSet.Cyan)
                shownApps.isEmpty() -> Text(
                    LanguageManager.t("nomatch", "Nothing found"),
                    color = RxSet.Mute,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(shownApps, key = { it.packageName }) { app ->
                        val isChecked = selected.contains(app.packageName)
                        AppRow(
                            app = app,
                            checked = isChecked,
                            onToggle = {
                                selected = if (isChecked) selected - app.packageName else selected + app.packageName
                            },
                        )
                    }
                }
            }
        }

        // sticky save bar
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF080A20).copy(alpha = 0.80f))
                .padding(14.dp)
        ) {
            GradientButton(
                text = "${LanguageManager.t("save", "Save")}  (${selected.size})",
                onClick = { onSave(selected) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TabChip(text: String, on: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (on) RxSet.Violet.copy(alpha = 0.20f) else RxSet.Panel2,
        border = BorderStroke(1.dp, if (on) RxSet.Violet else RxSet.Line),
    ) {
        Text(
            text,
            color = RxSet.Text,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun AppRow(app: InstalledAppEntry, checked: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (checked) {
                    Modifier.background(
                        Brush.linearGradient(
                            listOf(RxSet.Blue.copy(alpha = 0.16f), RxSet.Violet.copy(alpha = 0.16f), RxSet.Pink.copy(alpha = 0.10f))
                        )
                    )
                } else Modifier
            )
            .border(1.dp, if (checked) RxSet.Violet.copy(alpha = 0.55f) else Color.Transparent, shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                color = RxSet.Text, fontWeight = FontWeight.Bold, fontSize = 14.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.packageName,
                color = RxSet.Mute, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .then(
                    if (checked) {
                        Modifier.background(Brush.linearGradient(listOf(RxSet.Blue, RxSet.Violet, RxSet.Pink)))
                    } else {
                        Modifier.border(1.5.dp, RxSet.Line2, CircleShape)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
    }
}

/* ------------------------------------------------------------------------------------------
   Dialog helpers
   ------------------------------------------------------------------------------------------ */
@Composable
private fun rxFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = RxSet.Violet,
    unfocusedBorderColor = RxSet.Line,
    errorBorderColor = RxSet.Bad,
    focusedTextColor = RxSet.Text,
    unfocusedTextColor = RxSet.Text,
    focusedLabelColor = Color(0xFFC4B5FD),
    unfocusedLabelColor = RxSet.Mute,
    focusedContainerColor = Color(0xFF080A20).copy(alpha = 0.85f),
    unfocusedContainerColor = Color(0xFF080A20).copy(alpha = 0.62f),
    cursorColor = RxSet.Pink,
)

@Composable
private fun RxField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        shape = RoundedCornerShape(13.dp),
        colors = rxFieldColors(),
        textStyle = TextStyle(fontSize = 15.sp, textDirection = TextDirection.Ltr),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun RxDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    confirmLabel: String = LanguageManager.t("save", "Save"),
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RxSet.PanelS,
        shape = RoundedCornerShape(22.dp),
        title = { Text(title, color = RxSet.Text, fontWeight = FontWeight.ExtraBold) },
        text = { Column(content = content) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(
                    confirmLabel,
                    color = if (confirmEnabled) Color(0xFFC4B5FD) else RxSet.Mute.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(LanguageManager.t("cancel", "Cancel"), color = RxSet.Mute) }
        },
    )
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, subtitle: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (selected) Modifier.background(RxSet.Violet.copy(alpha = 0.14f)) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 4.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = RxSet.Violet, unselectedColor = RxSet.Mute),
        )
        Column(Modifier.padding(start = 6.dp)) {
            Text(title, color = RxSet.Text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = RxSet.Mute, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LanguagePickerDialog(
    currentTag: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selected by remember { mutableStateOf(currentTag) }
    val languages = listOf(
        "auto" to LanguageManager.t("auto", "Automatic"),
        "en" to "English",
        "fa" to "فارسی",
        "ar" to "العربية",
        "ko" to "한국어",
        "ru" to "Русский",
        "zh" to "简体中文"
    )
    RxDialog(
        title = LanguageManager.t("language", "Language"),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(selected) },
        confirmLabel = LanguageManager.t("ok", "OK"),
    ) {
        languages.forEach { (tag, label) ->
            ChoiceRow(selected == tag, label) { selected = tag }
        }
    }
}

@Composable
private fun DohProviderPickerDialog(
    current: SettingsStore.DohProvider,
    title: String = LanguageManager.t("encrypted_dns", "Encrypted DNS"),
    onDismiss: () -> Unit,
    onConfirm: (SettingsStore.DohProvider) -> Unit,
) {
    var selected by remember { mutableStateOf(current) }
    RxDialog(
        title = title,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(selected) },
    ) {
        SettingsStore.DohProvider.entries.forEach { provider ->
            val detail = when (provider) {
                SettingsStore.DohProvider.AUTOMATIC -> LanguageManager.t("doh_auto", "Use whichever answers first")
                SettingsStore.DohProvider.OFF -> LanguageManager.t("doh_off", "Use the network's own DNS")
                else -> provider.addresses.joinToString(", ")
            }
            ChoiceRow(selected == provider, provider.label, detail) { selected = provider }
        }
    }
}

@Composable
private fun CustomDnsPickerDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(current) }
    val isValid = SettingsStore.isValidDnsServer(value)
    RxDialog(
        title = LanguageManager.t("custom_dns_title", "Custom DNS Server"),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(value) },
        confirmEnabled = isValid,
    ) {
        SettingsStore.CUSTOM_DNS_PRESETS.forEach { (address, label) ->
            ChoiceRow(value == address, label) { value = address }
        }
        RxField(
            value = value,
            onChange = { value = it.trim() },
            label = LanguageManager.t("ipv4_address", "IPv4 address"),
            isError = value.isNotBlank() && !isValid,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun CustomEdgeAddressDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(current) }
    val trimmed = value.trim()
    val isValid = trimmed.isEmpty() || SettingsStore.isValidEdgeHost(trimmed)
    RxDialog(
        title = LanguageManager.t("custom_edge_title", "Custom Edge Address"),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(trimmed) },
        confirmEnabled = isValid,
    ) {
        RxField(
            value = value,
            onChange = { value = it },
            label = LanguageManager.t("host_or_ip", "Host or IP"),
            isError = !isValid,
        )
    }
}

@Composable
private fun SocksBindAddressPickerDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selected by remember { mutableStateOf(current) }
    RxDialog(
        title = LanguageManager.t("bind_address_title", "Local Bind Address"),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(selected) },
    ) {
        SettingsStore.SOCKS_BIND_ADDRESS_PRESETS.forEach { (address, label) ->
            ChoiceRow(selected == address, label) { selected = address }
        }
    }
}

@Composable
private fun SocksPortPickerDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var portText by remember { mutableStateOf(current.toString()) }
    val parsedPort = portText.toIntOrNull()
    val isValid = parsedPort != null && parsedPort in 1..65_535
    RxDialog(
        title = LanguageManager.t("socks_port_title", "Local SOCKS5 Port"),
        onDismiss = onDismiss,
        onConfirm = { parsedPort?.let { onConfirm(it) } },
        confirmEnabled = isValid,
    ) {
        RxField(
            value = portText,
            onChange = { portText = it.filter(Char::isDigit).take(5) },
            label = LanguageManager.t("port", "Port"),
            keyboardType = KeyboardType.Number,
            isError = portText.isNotBlank() && !isValid,
        )
    }
}

@Composable
private fun TunnelMtuDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var mtuText by remember(current) { mutableStateOf(current.toString()) }
    val parsed = mtuText.toIntOrNull()
    val valid = parsed != null && parsed in SettingsStore.MIN_TUNNEL_MTU..SettingsStore.MAX_TUNNEL_MTU
    RxDialog(
        title = LanguageManager.t("tunnel_mtu", "Tunnel MTU"),
        onDismiss = onDismiss,
        onConfirm = { parsed?.let(onConfirm) },
        confirmEnabled = valid,
    ) {
        Text(
            LanguageManager.t("tunnel_mtu_range", "Choose a value from 1280 to 9000. Default: 1280."),
            color = RxSet.Mute,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        RxField(
            value = mtuText,
            onChange = { mtuText = it.filter(Char::isDigit).take(4) },
            label = LanguageManager.t("tunnel_mtu", "Tunnel MTU"),
            keyboardType = KeyboardType.Number,
            isError = mtuText.isNotBlank() && !valid,
        )
    }
}

@Composable
private fun UpstreamProxyTypePickerDialog(
    current: SettingsStore.UpstreamProxyType,
    onDismiss: () -> Unit,
    onConfirm: (SettingsStore.UpstreamProxyType) -> Unit,
) {
    var selected by remember { mutableStateOf(current) }
    RxDialog(
        title = LanguageManager.t("upstream_type_title", "Upstream Proxy Type"),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(selected) },
    ) {
        SettingsStore.UpstreamProxyType.entries.forEach { type ->
            ChoiceRow(selected == type, type.name) { selected = type }
        }
    }
}

@Composable
private fun UpstreamProxyAddressDialog(
    currentHost: String,
    currentPort: Int,
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit,
) {
    var host by remember { mutableStateOf(currentHost) }
    var portText by remember { mutableStateOf(currentPort.toString()) }
    val parsedPort = portText.toIntOrNull()
    val isValid = host.isNotBlank() && parsedPort != null && parsedPort in 1..65_535
    RxDialog(
        title = LanguageManager.t("upstream_address_title", "Upstream Proxy Address"),
        onDismiss = onDismiss,
        onConfirm = { parsedPort?.let { onConfirm(host, it) } },
        confirmEnabled = isValid,
    ) {
        RxField(
            value = host,
            onChange = { host = it.trim() },
            label = LanguageManager.t("host_or_ip", "Host or IP"),
        )
        RxField(
            value = portText,
            onChange = { portText = it.filter(Char::isDigit).take(5) },
            label = LanguageManager.t("port", "Port"),
            keyboardType = KeyboardType.Number,
            isError = portText.isNotBlank() && (parsedPort == null || parsedPort !in 1..65_535),
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun UpstreamProxyCredentialsDialog(
    currentUsername: String,
    currentPassword: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var username by remember { mutableStateOf(currentUsername) }
    var password by remember { mutableStateOf(currentPassword) }
    RxDialog(
        title = LanguageManager.t("upstream_creds_title", "Upstream Proxy Credentials"),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(username, password) },
    ) {
        RxField(
            value = username,
            onChange = { username = it },
            label = LanguageManager.t("username", "Username"),
        )
        RxField(
            value = password,
            onChange = { password = it },
            label = LanguageManager.t("password", "Password"),
            password = true,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
@Composable
private fun updateSettingsLabel(lang: String, key: String): String = when (key) {
    "title" -> when (lang) { "fa" -> "بررسی به‌روزرسانی"; "ar" -> "التحقق من التحديثات"; "ru" -> "Проверить обновления"; "ko" -> "업데이트 확인"; "zh" -> "检查更新"; else -> "Check for updates" }
    else -> when (lang) { "fa" -> "بررسی نسخه جدید در گیت‌هاب"; "ar" -> "التحقق من إصدار جديد على GitHub"; "ru" -> "Проверить новую версию на GitHub"; "ko" -> "GitHub에서 새 버전 확인"; "zh" -> "检查 GitHub 上的新版本"; else -> "Check for a new version on GitHub" }
}

private fun relayUrlErrorText(error: Throwable?): String {
    val kind = (error as? com.vauth.rxfoxyvpn.data.relay.RelayPolicy.PersonalUrlException)?.error
    return when (kind) {
        com.vauth.rxfoxyvpn.data.relay.RelayPolicy.PersonalUrlError.NOT_HTTPS ->
            LanguageManager.t("relay_err_not_https", "The relay URL must start with https://")
        com.vauth.rxfoxyvpn.data.relay.RelayPolicy.PersonalUrlError.NO_SECRET ->
            LanguageManager.t("relay_err_no_secret", "The relay URL must end with ?secret=YOUR_SECRET")
        else -> LanguageManager.t("relay_err_invalid_url", "This is not a valid relay URL")
    }
}

private fun relayFailureText(kind: com.vauth.rxfoxyvpn.data.relay.RelayFailure?): String = when (kind) {
    com.vauth.rxfoxyvpn.data.relay.RelayFailure.WRONG_SECRET ->
        LanguageManager.t("relay_err_wrong_secret", "Wrong secret: it must match the SECRET set in the relay")
    com.vauth.rxfoxyvpn.data.relay.RelayFailure.NOT_SET_UP ->
        LanguageManager.t("relay_err_not_set_up", "The relay has no secret set yet; set SECRET and deploy again")
    com.vauth.rxfoxyvpn.data.relay.RelayFailure.TIMEOUT ->
        LanguageManager.t("relay_err_timeout", "The relay did not answer in time")
    com.vauth.rxfoxyvpn.data.relay.RelayFailure.UNRECOGNISED ->
        LanguageManager.t("relay_err_not_relay", "This address is not a relay, or the relay code is outdated; deploy the latest version")
    else -> LanguageManager.t("relay_err_connection", "Could not connect to the relay")
}

@Composable
private fun RelaySettingsPage(
    sharedRelayEnabled: Boolean,
    onSharedRelayChanged: (Boolean) -> Unit,
    personalRelayEnabled: Boolean,
    onPersonalRelayChanged: (Boolean) -> Unit,
    personalRelayUrl: String,
    onOpenRelayUrlDialog: () -> Unit,
    personalRelayOnly: Boolean,
    onPersonalRelayOnlyChanged: (Boolean) -> Unit,
    relayTesting: Boolean,
    relayStatus: String?,
    onTestPersonalRelay: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = LanguageManager.t("relay_server_menu", "سرور رله"),
            subtitle = null,
            onBack = onClose,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Section(LanguageManager.t("relay_shared_section", "رله اشتراکی")) {
                Text(
                    text = LanguageManager.t(
                        "relay_desc",
                        "Used only for Mozilla sign-in and authorization requests when Mozilla refuses your region (HTTP 451). VPN traffic never goes through a relay.",
                    ),
                    color = RxSet.Mute,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                RowDivider()
                SwitchRow(
                    icon = FoxyIcons.Public,
                    tint = RxSet.Cyan,
                    title = LanguageManager.t("relay_shared", "Shared relay (automatic)"),
                    subtitle = LanguageManager.t(
                        "relay_shared_hint",
                        "Tried only after HTTP 451 from Mozilla's authorization server. A relay operator can see the account token while forwarding.",
                    ),
                    checked = sharedRelayEnabled,
                    onChecked = onSharedRelayChanged,
                )
            }

            Section(LanguageManager.t("relay_personal", "Personal relay")) {
                SwitchRow(
                    icon = FoxyIcons.Router,
                    tint = RxSet.Pink,
                    title = LanguageManager.t("relay_personal", "Personal relay"),
                    subtitle = LanguageManager.t(
                        "relay_privacy_note",
                        "A relay forwards your Authorization header and account tokens. Prefer a relay you control.",
                    ),
                    checked = personalRelayEnabled,
                    onChecked = onPersonalRelayChanged,
                )
                RowDivider()
                NavRow(
                    icon = FoxyIcons.Link,
                    tint = RxSet.Pink,
                    title = LanguageManager.t("relay_personal_url", "Relay URL"),
                    // Only the host is shown: the URL's secret never appears on screen after it is saved.
                    subtitle = personalRelayUrl.ifBlank { null }?.let { saved ->
                        com.vauth.rxfoxyvpn.data.relay.RelayPolicy.validatePersonalRelayUrl(saved).getOrNull()?.host
                    } ?: LanguageManager.t("not_set", "Not set"),
                    onClick = onOpenRelayUrlDialog,
                )
                RowDivider()
                SwitchRow(
                    icon = Icons.Default.Lock,
                    tint = RxSet.Pink,
                    title = LanguageManager.t("relay_personal_only", "Personal relay only (never use shared)"),
                    subtitle = null,
                    checked = personalRelayOnly,
                    onChecked = onPersonalRelayOnlyChanged,
                )
                RowDivider()
                NavRow(
                    icon = FoxyIcons.Speed,
                    tint = RxSet.Good,
                    title = if (relayTesting) {
                        LanguageManager.t("relay_testing", "Testing…")
                    } else {
                        LanguageManager.t("relay_test", "Test relay")
                    },
                    subtitle = relayStatus ?: LanguageManager.t("relay_status_idle", "Not tested"),
                    onClick = onTestPersonalRelay,
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RelayUrlDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(current) }
    val parsed = if (value.isBlank()) null else com.vauth.rxfoxyvpn.data.relay.RelayPolicy.validatePersonalRelayUrl(value)
    // An empty field is allowed (it clears the saved URL); anything else must be a valid relay URL.
    val valid = value.isBlank() || parsed?.isSuccess == true
    RxDialog(
        title = LanguageManager.t("relay_personal_url", "Relay URL"),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(value) },
        confirmEnabled = valid,
    ) {
        RxField(
            value = value,
            onChange = { value = it },
            label = LanguageManager.t("relay_personal_hint", "https://your-worker.workers.dev/?secret=..."),
            keyboardType = KeyboardType.Uri,
            isError = !valid,
            password = false,
        )
        if (!valid) {
            Text(relayUrlErrorText(parsed?.exceptionOrNull()), color = RxSet.Bad, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
