package com.vauth.rxfoxyvpn.ui.screens

import android.content.Intent
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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ScaffoldDefaults

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
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var exitCheckEnabled by remember { mutableStateOf(settingsStore.exitCheckEnabled) }
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
    var showDohProviderDialog by remember { mutableStateOf(false) }
    var showCustomDnsDialog by remember { mutableStateOf(false) }
    var showSocksBindDialog by remember { mutableStateOf(false) }
    var showSocksPortDialog by remember { mutableStateOf(false) }
    var showEdgeAddressDialog by remember { mutableStateOf(false) }
    var showUpstreamProxyTypeDialog by remember { mutableStateOf(false) }
    var showUpstreamProxyAddressDialog by remember { mutableStateOf(false) }
    var showUpstreamProxyCredentialsDialog by remember { mutableStateOf(false) }
    var showSplitTunnelPage by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    val currentLang by LanguageManager.currentLang.collectAsState()
    val langDisplayNames = mapOf(
        "en" to "English",
        "fa" to "فارسی",
        "ar" to "العربية",
        "ko" to "한국어",
        "ru" to "Русский",
        "zh" to "简体中文"
    )

    if (showLanguageDialog) {
        LanguagePickerDialog(
            currentTag = currentLang,
            onDismiss = { showLanguageDialog = false },
            onConfirm = { tag ->
                LanguageManager.setLanguage(context, tag)
                showLanguageDialog = false
            },
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
                SplitTunnelPage(
                    current = excludedApps,
                    onClose = { showSplitTunnelPage = false },
                    onSave = {
                        excludedApps = it
                        settingsStore.excludedApps = it
                        showSplitTunnelPage = false
                    },
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    PageHeader(
                        title = LanguageManager.t("settings", "Settings"),
                        subtitle = null,
                        onBack = onBack,
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        // ---------- General ----------
                        Section(LanguageManager.t("general", "General")) {
                            NavRow(
                                icon = Icons.Default.Language,
                                tint = RxSet.Cyan,
                                title = LanguageManager.t("language", "Language"),
                                subtitle = langDisplayNames[currentLang] ?: "English",
                                onClick = { showLanguageDialog = true },
                            )
                        }

                        // ---------- Connection ----------
                        Section(LanguageManager.t("connection", "Connection")) {
                            SwitchRow(
                                icon = Icons.Default.Place,
                                tint = RxSet.Good,
                                title = LanguageManager.t("verify_exit", "Verify exit location"),
                                subtitle = LanguageManager.t("verify_exit_desc", "Check that the tunnel exits in the selected country"),
                                checked = exitCheckEnabled,
                                onChecked = {
                                    exitCheckEnabled = it
                                    settingsStore.exitCheckEnabled = it
                                },
                            )
                            RowDivider()
                            SettingRow(
                                icon = Icons.Default.Apps,
                                tint = RxSet.Pink,
                                title = LanguageManager.t("split_tunneling", "Split tunneling"),
                                subtitle = if (excludedApps.isEmpty()) {
                                    LanguageManager.t("none", "None")
                                } else {
                                    LanguageManager.tn("excluded_apps_count", "{n} apps bypass the VPN", excludedApps.size)
                                },
                                onClick = { showSplitTunnelPage = true },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (excludedApps.isNotEmpty()) {
                                            ExcludedIconStack(excludedApps.toList())
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Chevron()
                                    }
                                },
                            )
                        }

                        // ---------- DNS ----------
                        Section(LanguageManager.t("dns_network", "DNS & Network")) {
                            NavRow(
                                icon = Icons.Default.Security,
                                tint = RxSet.Violet,
                                title = LanguageManager.t("encrypted_dns", "Encrypted DNS"),
                                subtitle = when (dohProvider) {
                                    SettingsStore.DohProvider.AUTOMATIC -> LanguageManager.t("doh_auto", "Use whichever answers first")
                                    SettingsStore.DohProvider.OFF -> LanguageManager.t("doh_off", "Use the network's own DNS")
                                    else -> dohProvider.addresses.joinToString(", ")
                                },
                                onClick = { showDohProviderDialog = true },
                            )
                            RowDivider()
                            SwitchRow(
                                icon = Icons.Default.Dns,
                                tint = RxSet.Cyan,
                                title = LanguageManager.t("custom_dns", "Use a custom DNS server"),
                                subtitle = LanguageManager.t("custom_dns_desc", "Send your apps' DNS queries to a server you choose"),
                                checked = customDnsEnabled,
                                onChecked = {
                                    customDnsEnabled = it
                                    settingsStore.customDnsEnabled = it
                                },
                            )
                            AnimatedVisibility(visible = customDnsEnabled) {
                                Column {
                                    RowDivider()
                                    NavRow(
                                        icon = Icons.Default.Tune,
                                        tint = RxSet.Cyan,
                                        title = LanguageManager.t("dns_server", "DNS server"),
                                        subtitle = SettingsStore.CUSTOM_DNS_PRESETS
                                            .firstOrNull { it.first == customDnsServer }
                                            ?.second ?: customDnsServer,
                                        onClick = { showCustomDnsDialog = true },
                                    )
                                }
                            }
                        }

                        // ---------- Local proxy ----------
                        Section(LanguageManager.t("local_proxy", "Local proxy")) {
                            SwitchRow(
                                icon = Icons.Default.Router,
                                tint = RxSet.Warn,
                                title = LanguageManager.t("proxy_only", "Proxy-only mode"),
                                subtitle = LanguageManager.t("proxy_only_desc", "Run only the local SOCKS5 proxy, without a VPN interface"),
                                checked = proxyOnlyMode,
                                onChecked = {
                                    proxyOnlyMode = it
                                    settingsStore.proxyOnlyMode = it
                                },
                            )
                            RowDivider()
                            NavRow(
                                icon = Icons.Default.Link,
                                tint = RxSet.Cyan,
                                title = LanguageManager.t("local_address", "Local address"),
                                subtitle = socksBindAddress,
                                onClick = { showSocksBindDialog = true },
                            )
                            RowDivider()
                            NavRow(
                                icon = Icons.Default.Tune,
                                tint = RxSet.Cyan,
                                title = LanguageManager.t("local_port", "Local port"),
                                subtitle = socksPort.toString(),
                                onClick = { showSocksPortDialog = true },
                            )
                        }

                        // ---------- Advanced ----------
                        Section(LanguageManager.t("advanced", "Advanced")) {
                            NavRow(
                                icon = Icons.Default.Public,
                                tint = RxSet.Blue,
                                title = LanguageManager.t("custom_edge", "Custom edge address"),
                                subtitle = customEdgeAddress.ifBlank { LanguageManager.t("not_set", "Not set") },
                                onClick = { showEdgeAddressDialog = true },
                            )
                            RowDivider()
                            SwitchRow(
                                icon = Icons.Default.Link,
                                tint = RxSet.Pink,
                                title = LanguageManager.t("upstream_proxy", "Chain through upstream proxy"),
                                subtitle = LanguageManager.t("upstream_proxy_desc", "Connect to the VPN server through another proxy first"),
                                checked = upstreamProxyEnabled,
                                onChecked = {
                                    upstreamProxyEnabled = it
                                    settingsStore.upstreamProxyEnabled = it
                                },
                            )
                            AnimatedVisibility(visible = upstreamProxyEnabled) {
                                Column {
                                    RowDivider()
                                    NavRow(
                                        icon = Icons.Default.Tune,
                                        tint = RxSet.Pink,
                                        title = LanguageManager.t("proxy_type", "Proxy type"),
                                        subtitle = upstreamProxyType.name,
                                        onClick = { showUpstreamProxyTypeDialog = true },
                                    )
                                    RowDivider()
                                    NavRow(
                                        icon = Icons.Default.Router,
                                        tint = RxSet.Pink,
                                        title = LanguageManager.t("proxy_address", "Proxy address"),
                                        subtitle = if (upstreamProxyHost.isBlank()) {
                                            LanguageManager.t("not_set", "Not set")
                                        } else {
                                            "$upstreamProxyHost:$upstreamProxyPort"
                                        },
                                        onClick = { showUpstreamProxyAddressDialog = true },
                                    )
                                    RowDivider()
                                    NavRow(
                                        icon = Icons.Default.Lock,
                                        tint = RxSet.Pink,
                                        title = LanguageManager.t("proxy_credentials", "Proxy credentials"),
                                        subtitle = if (upstreamProxyUsername.isBlank()) LanguageManager.t("none", "None") else upstreamProxyUsername,
                                        onClick = { showUpstreamProxyCredentialsDialog = true },
                                    )
                                }
                            }
                        }

                        // ---------- Account ----------
                        Section(LanguageManager.t("account_section", "Account")) {
                            NavRow(
                                icon = Icons.Default.AccountCircle,
                                tint = RxSet.Good,
                                title = LanguageManager.t("manage_account", "Manage account"),
                                subtitle = LanguageManager.t("manage_account_desc", "Subscription status and data usage"),
                                onClick = onOpenAccount,
                            )
                            RowDivider()
                            SettingRow(
                                icon = Icons.AutoMirrored.Filled.Logout,
                                tint = RxSet.Bad,
                                title = LanguageManager.t("sign_out", "Sign out"),
                                subtitle = null,
                                titleColor = RxSet.Bad,
                                onClick = onSignOut,
                            )
                        }

                        // ---------- About ----------
                        Section(LanguageManager.t("about", "About")) {
                            NavRow(
                                icon = Icons.Default.Description,
                                tint = RxSet.Cyan,
                                title = LanguageManager.t("view_logs", "View logs"),
                                subtitle = LanguageManager.t("view_logs_desc", "Recent connection activity"),
                                onClick = onOpenLogs,
                            )
                            RowDivider()
                            SettingRow(
                                icon = Icons.Default.Info,
                                tint = RxSet.Violet,
                                title = LanguageManager.t("version", "Version"),
                                subtitle = BuildConfig.VERSION_NAME,
                            )
                            RowDivider()
                            NavRow(
                                icon = Icons.Default.Code,
                                tint = RxSet.Violet,
                                title = LanguageManager.t("source_code", "Source code"),
                                subtitle = "https://github.com/omidrx0/RxFoxyVPN",
                                onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/omidrx0/RxFoxyVPN"))
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                },
                            )
                        }

                        // ---------- footer ----------
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(painterResource(id = R.drawable.rx_logo), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("RXFOXYVPN  ·  ${BuildConfig.VERSION_NAME}", color = RxSet.Mute, fontSize = 12.sp)
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = RxSet.Text, modifier = Modifier.size(20.dp))
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 12.dp)
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(RxSet.Cyan, RxSet.Pink)))
            )
            Spacer(Modifier.width(8.dp))
            Text(title, color = RxSet.Mute, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = RxSet.Panel,
            border = BorderStroke(1.dp, RxSet.Line),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
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
            Icon(Icons.Default.Apps, contentDescription = null, tint = RxSet.Mute, modifier = Modifier.size(size * 0.5f))
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

private data class InstalledAppEntry(val packageName: String, val label: String)

@Composable
private fun SplitTunnelPage(
    current: Set<String>,
    onClose: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    var selected by remember { mutableStateOf(current) }
    var searchQuery by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) } // 0 = all apps, 1 = selected only

    var loaded by remember { mutableStateOf(false) }
    val installedApps by produceState<List<InstalledAppEntry>>(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) {
            val packageManager = context.packageManager
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != context.packageName }
                .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
                .map { info: ApplicationInfo ->
                    InstalledAppEntry(info.packageName, info.loadLabel(packageManager).toString())
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
            // info card
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
                    LanguageManager.t("split_hint", "Selected apps bypass the VPN and use your normal internet connection."),
                    color = RxSet.Text, fontSize = 12.5.sp, lineHeight = 18.sp,
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
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
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
    onDismiss: () -> Unit,
    onConfirm: (SettingsStore.DohProvider) -> Unit,
) {
    var selected by remember { mutableStateOf(current) }
    RxDialog(
        title = LanguageManager.t("encrypted_dns", "Encrypted DNS"),
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