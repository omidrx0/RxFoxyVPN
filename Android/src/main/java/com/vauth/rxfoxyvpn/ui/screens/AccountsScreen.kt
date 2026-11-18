package com.vauth.rxfoxyvpn.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.vauth.rxfoxyvpn.data.AccountReason
import com.vauth.rxfoxyvpn.data.AccountStatus
import com.vauth.rxfoxyvpn.data.GUARDIAN_ENDPOINT_DEFAULT
import com.vauth.rxfoxyvpn.data.LanguageManager
import com.vauth.rxfoxyvpn.R
import com.vauth.rxfoxyvpn.data.MultiAccountStore
import com.vauth.rxfoxyvpn.data.backup.BackupManager
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import com.vauth.rxfoxyvpn.ui.icons.FoxyIcons

private object RxAccList {
    // Exact same tokens as Settings (RxSet) / Home (Rx)
    val Bg = Color(0xFF060714)
    val PanelS = Color(0xFF0E1131)
    val Panel = Color(0xFF14173C).copy(alpha = 0.72f)
    val Panel2 = Color(0xFF282C68).copy(alpha = 0.50f)
    val Card = Panel // alias used across this screen
    val Line = Color(0xFF969EFF).copy(alpha = 0.16f)
    val Line2 = Color(0xFF969EFF).copy(alpha = 0.30f)
    val Text = Color(0xFFEEF0FF)
    val Mute = Color(0xFF9AA1D6)
    val Blue = Color(0xFF4D7CFF)
    val Violet = Color(0xFF8B5CF6)
    val Purple = Violet // alias
    val Pink = Color(0xFFFF5FB0)
    val Cyan = Color(0xFF38D9FF)
    val Good = Color(0xFF34E0A1)
    val Ready = Color(0xFFA78BFA)
    val Warn = Color(0xFFFFB86B)
    val Bad = Color(0xFFFF5C7A)
}

@Composable
fun AccountsScreen(
    multiAccountStore: MultiAccountStore,
    settingsStore: com.vauth.rxfoxyvpn.data.SettingsStore,
    authRepository: com.vauth.rxfoxyvpn.data.FxaAuthRepository,
    backupManager: BackupManager,
    onBack: () -> Unit,
    onSignedIn: (email: String, password: String?) -> Unit,
    onOpenBrowserSignIn: (email: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts by multiAccountStore.accounts.collectAsState()
    val activeId by multiAccountStore.activeId.collectAsState()
    var threshold by remember { mutableStateOf(multiAccountStore.rotationThresholdPercent.toFloat()) }
    var showSignIn by remember { mutableStateOf(false) }
    var signInEmail by remember { mutableStateOf("") }
    var showMethodPicker by remember { mutableStateOf(false) }
    var method by remember { mutableStateOf(settingsStore.signInMethod) }
    var deleteCandidate by remember { mutableStateOf<MultiAccountStore.Account?>(null) }
    var importing by remember { mutableStateOf(false) }
    var refreshingAll by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Pair<ByteArray, BackupManager.BackupPreview>?>(null) }

    fun toast(message: String, long: Boolean = false) {
        Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    fun startAddAccount() {
        when (settingsStore.signInMethod) {
            com.vauth.rxfoxyvpn.data.SettingsStore.SignInMethod.BROWSER -> onOpenBrowserSignIn("")
            else -> {
                signInEmail = ""
                showSignIn = true
            }
        }
    }

    suspend fun refreshAccountQuota(acc: MultiAccountStore.Account): Boolean {
        val now = System.currentTimeMillis() / 1000
        val token = when {
            acc.expiresAtEpochSeconds > now + 60 -> acc.accessToken
            acc.id == activeId -> authRepository.currentAccessToken()
            else -> null
        } ?: return false
        return runCatching {
            val entitlement = com.vauth.rxfoxyvpn.data.GuardianClient()
                .fetchUserInfo(GUARDIAN_ENDPOINT_DEFAULT, token)
            multiAccountStore.observeEntitlement(acc.id, entitlement)
            true
        }.getOrElse { error ->
            com.vauth.rxfoxyvpn.data.error.FoxyLogger.logWarn(
                com.vauth.rxfoxyvpn.data.error.ErrorClassifier.classify(
                    error,
                    com.vauth.rxfoxyvpn.data.error.ErrorSection.ACCOUNTS,
                    "refresh_account_quota",
                ),
            )
            false
        }
    }

    fun refreshAll() {
        if (refreshingAll) return
        refreshingAll = true
        scope.launch {
            var ok = 0
            var skipped = 0
            for (acc in multiAccountStore.accounts.value) {
                if (refreshAccountQuota(acc)) ok++ else skipped++
            }
            refreshingAll = false
            toast(
                LanguageManager.tn("accounts_refreshed", "Refreshed {n} · skipped {s}", ok.toString())
                    .replace("{s}", skipped.toString()),
            )
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupManager.export()
                .onSuccess { bytes ->
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            ?: error("cannot write the backup file")
                    }.onSuccess {
                        toast(LanguageManager.t("backup_exported", "Backup saved"))
                    }.onFailure {
                        com.vauth.rxfoxyvpn.data.error.FoxyLogger.log(
                            com.vauth.rxfoxyvpn.data.error.ErrorClassifier.classify(
                                it, com.vauth.rxfoxyvpn.data.error.ErrorSection.BACKUP, "export_backup",
                            ),
                        )
                        toast(LanguageManager.t("backup_export_failed", "Backup failed"), long = true)
                    }
                }
                .onFailure {
                    toast(LanguageManager.t("backup_export_failed", "Backup failed"), long = true)
                }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    stream.copyTo(out, 64 * 1024)
                    out.toByteArray()
                } ?: error("empty")
            }.getOrNull()
            importing = false
            if (bytes == null) {
                toast(LanguageManager.t("backup_invalid", "This file is not a valid backup."), long = true)
                return@launch
            }
            backupManager.inspect(bytes)
                .onSuccess { preview -> pendingImport = bytes to preview }
                .onFailure {
                    toast(LanguageManager.t("backup_invalid", "This file is not a valid backup."), long = true)
                }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Same background family as Settings / Home
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF0D1033), RxAccList.Bg)))
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
                            listOf(
                                RxAccList.Bg.copy(alpha = 0.70f),
                                RxAccList.Bg.copy(alpha = 0.50f),
                                RxAccList.Bg.copy(alpha = 0.80f),
                            )
                        )
                    )
            )
            Canvas(Modifier.fillMaxSize()) {
                val big = size.maxDimension
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0xFF3A62FF).copy(alpha = 0.26f), Color.Transparent),
                        center = Offset(0f, 0f),
                        radius = big * 0.55f,
                    ),
                    radius = big * 0.55f,
                    center = Offset(0f, 0f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0xFF8A3CFF).copy(alpha = 0.24f), Color.Transparent),
                        center = Offset(size.width, size.height),
                        radius = big * 0.55f,
                    ),
                    radius = big * 0.55f,
                    center = Offset(size.width, size.height),
                )
            }
        }

        Column(Modifier.fillMaxSize()) {
            PageHeader(
                title = LanguageManager.t("accounts_title", "Accounts"),
                onBack = onBack,
                trailing = {
                    HeaderActions(
                        refreshing = refreshingAll,
                        canRefresh = accounts.isNotEmpty(),
                        importing = importing,
                        onRefresh = { refreshAll() },
                        onAdd = { startAddAccount() },
                        onExport = {
                            exportLauncher.launch(
                                "rxfoxyvpn-backup-" +
                                        java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US)
                                            .format(java.util.Date()) +
                                        ".zip"
                            )
                        },
                        onImport = {
                            importLauncher.launch(
                                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")
                            )
                        },
                    )
                },
            )

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                if (accounts.isEmpty()) {
                    Section("") { EmptyAccounts(onAdd = { startAddAccount() }) }
                } else {
                    Section(LanguageManager.t("accounts_list_section", "Your accounts")) {
                        accounts.forEachIndexed { index, acc ->
                            if (index > 0) RowDivider()
                            AccountRow(
                                account = acc,
                                index = index + 1,
                                isActive = acc.id == activeId,
                                isFirst = index == 0,
                                isLast = index == accounts.lastIndex,
                                thresholdPercent = multiAccountStore.rotationThresholdPercent,
                                onToggle = { multiAccountStore.setEnabled(acc.id, it) },
                                onSelect = {
                                    if (multiAccountStore.setActive(acc.id)) {
                                        toast(LanguageManager.t("account_selected", "Account selected"))
                                    }
                                },
                                onRemove = { deleteCandidate = acc },
                                onMoveUp = { multiAccountStore.move(acc.id, -1) },
                                onMoveDown = { multiAccountStore.move(acc.id, +1) },
                                onRefresh = {
                                    scope.launch {
                                        if (refreshAccountQuota(acc)) {
                                            toast(LanguageManager.t("account_refreshed", "Quota updated"))
                                        } else {
                                            toast(LanguageManager.t("account_refresh_skipped", "Could not refresh this account"), long = true)
                                        }
                                    }
                                },
                                onSignInAgain = {
                                    signInEmail = acc.email
                                    showSignIn = true
                                },
                            )
                        }
                    }
                    GradientButton(
                        text = LanguageManager.t("add_account", "Add account"),
                        onClick = { startAddAccount() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Section(LanguageManager.t("accounts_options_section", "Options")) {
                    NavRow(
                        icon = FoxyIcons.Login,
                        tint = RxAccList.Blue,
                        title = LanguageManager.t("login_method", "Sign-in method"),
                        subtitle = when (method) {
                            com.vauth.rxfoxyvpn.data.SettingsStore.SignInMethod.BROWSER ->
                                LanguageManager.t("login_method_browser", "Browser sign-in")
                            else -> LanguageManager.t("login_method_direct", "Direct sign-in")
                        },
                        onClick = { showMethodPicker = true },
                    )
                    RowDivider()
                    RotationRow(
                        threshold = threshold,
                        onChange = { threshold = it },
                        onChangeFinished = { multiAccountStore.rotationThresholdPercent = threshold.toInt() },
                    )
                }

                Section("") {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        IconTile(Icons.Default.Info, RxAccList.Cyan)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            LanguageManager.t(
                                "accounts_quota_info",
                                "Each Firefox account includes 50 GB per month. You can add several accounts: when one runs out of data, " +
                                        "the app switches to the next one automatically, in the order of this list. " +
                                        "Each account is used up to 95%, and the last account until its data is fully used.",
                            ),
                            color = RxAccList.Text.copy(alpha = 0.92f),
                            fontSize = 13.sp,
                            lineHeight = 21.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Section("") {
                    SettingRow(
                        icon = Icons.Default.AccountCircle,
                        tint = RxAccList.Good,
                        title = LanguageManager.t("no_fx_account", "No Firefox account?"),
                        subtitle = LanguageManager.t(
                            "create_fx_hint",
                            "Create a free account at accounts.firefox.com, then come back and tap Add account.",
                        ),
                        maxSubtitleLines = 3,
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://accounts.firefox.com")))
                        },
                        trailing = {
                            Icon(FoxyIcons.OpenInNew, null, tint = RxAccList.Mute, modifier = Modifier.size(18.dp))
                        },
                    )
                }

                Spacer(Modifier.height(8.dp))
            }
        }

        if (showSignIn) {
            SignInDialog(
                authRepository = authRepository,
                multiAccountStore = multiAccountStore,
                initialEmail = signInEmail,
                onDismiss = { showSignIn = false },
                onSignedIn = { email, password ->
                    showSignIn = false
                    onSignedIn(email, password)
                },
            )
        }
        if (showMethodPicker) {
            SignInMethodDialog(
                current = method,
                onDismiss = { showMethodPicker = false },
                onPick = {
                    method = it
                    settingsStore.signInMethod = it
                    showMethodPicker = false
                },
            )
        }
        deleteCandidate?.let { victim ->
            AlertDialog(
                onDismissRequest = { deleteCandidate = null },
                title = { Text(LanguageManager.t("account_delete_confirm_title", "Remove account?")) },
                text = {
                    Text(
                        LanguageManager.t(
                            "account_delete_confirm_msg",
                            "Remove {email} from this device? The Firefox account itself is not deleted.",
                        ).replace("{email}", victim.email),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        multiAccountStore.remove(victim.id)
                        deleteCandidate = null
                    }) {
                        Text(LanguageManager.t("remove", "Remove"), color = RxAccList.Bad)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteCandidate = null }) {
                        Text(LanguageManager.t("cancel", "Cancel"))
                    }
                },
            )
        }
        pendingImport?.let { (bytes, preview) ->
            AlertDialog(
                onDismissRequest = { pendingImport = null },
                title = { Text(LanguageManager.t("backup_import_confirm_title", "Restore backup")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        preview.createdAtEpochSeconds?.takeIf { it > 0 }?.let { created ->
                            Text(
                                LanguageManager.t(
                                    "backup_created_at",
                                    "Backup created: {date}",
                                ).replace(
                                    "{date}",
                                    java.text.DateFormat.getDateTimeInstance(
                                        java.text.DateFormat.MEDIUM,
                                        java.text.DateFormat.SHORT,
                                        java.util.Locale.getDefault(),
                                    ).format(java.util.Date(created * 1000)),
                                ),
                                fontSize = 12.sp,
                                color = RxAccList.Mute,
                            )
                        }
                        Text(
                            LanguageManager.tn(
                                "backup_import_confirm_msg",
                                "{n} account(s) will be added or updated:",
                                preview.usableCount.toString(),
                            ),
                            fontSize = 13.sp,
                        )
                        preview.accounts.take(8).forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (item.exists) Icons.Default.Refresh else Icons.Default.Add,
                                    contentDescription = null,
                                    tint = if (item.exists) RxAccList.Ready else RxAccList.Good,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    item.email,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    if (item.exists) LanguageManager.t("backup_item_update", "update")
                                    else LanguageManager.t("backup_item_new", "new"),
                                    fontSize = 11.sp,
                                    color = RxAccList.Mute,
                                )
                            }
                        }
                        if (preview.accounts.size > 8) {
                            Text("+${preview.accounts.size - 8}…", fontSize = 12.sp, color = RxAccList.Mute)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val payload = bytes
                        pendingImport = null
                        importing = true
                        scope.launch {
                            backupManager.import(payload)
                                .onSuccess { result ->
                                    importing = false
                                    toast(
                                        LanguageManager.t("backup_imported", "Added {a} · updated {u}")
                                            .replace("{a}", result.added.toString())
                                            .replace("{u}", result.updated.toString()),
                                    )
                                }
                                .onFailure {
                                    importing = false
                                    toast(LanguageManager.t("backup_import_failed", "Import failed. No data was changed."), long = true)
                                }
                        }
                    }) {
                        Text(LanguageManager.t("restore", "Restore"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingImport = null }) {
                        Text(LanguageManager.t("cancel", "Cancel"))
                    }
                },
            )
        }
    }
}

// ============================================================================
//  Building blocks — same look as SettingsScreen
// ============================================================================

@Composable
private fun PageHeader(title: String, onBack: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
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
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = LanguageManager.t("back", "Back"),
                    tint = RxAccList.Text,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                title,
                color = RxAccList.Text,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                trailing()
                Spacer(Modifier.width(4.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(RxAccList.Line))
    }
}

@Composable
private fun HeaderActions(
    refreshing: Boolean,
    canRefresh: Boolean,
    importing: Boolean,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onRefresh, enabled = !refreshing && canRefresh, modifier = Modifier.size(38.dp)) {
            if (refreshing) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = RxAccList.Violet,
                )
            } else {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = LanguageManager.t("refresh_all", "Refresh all"),
                    tint = if (canRefresh) RxAccList.Text else RxAccList.Mute.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        IconButton(onClick = onAdd, modifier = Modifier.size(38.dp)) {
            Icon(
                Icons.Default.Add,
                contentDescription = LanguageManager.t("add_account", "Add account"),
                tint = RxAccList.Violet,
                modifier = Modifier.size(22.dp),
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(38.dp)) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = LanguageManager.t("more", "More"),
                    tint = RxAccList.Text,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(LanguageManager.t("backup_export", "Export backup")) },
                    leadingIcon = { Icon(FoxyIcons.Download, null) },
                    onClick = { menuOpen = false; onExport() },
                )
                DropdownMenuItem(
                    text = { Text(LanguageManager.t("backup_import", "Import backup")) },
                    leadingIcon = { Icon(FoxyIcons.Upload, null) },
                    enabled = !importing,
                    onClick = { menuOpen = false; onImport() },
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title.isNotBlank()) {
            Text(
                title,
                color = RxAccList.Mute,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = RxAccList.Panel,
            border = BorderStroke(1.dp, RxAccList.Line),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(1.dp).background(RxAccList.Line))
}

@Composable
private fun Chevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = RxAccList.Mute,
        modifier = Modifier.size(22.dp),
    )
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
    maxSubtitleLines: Int = 2,
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
            Text(title, color = RxAccList.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = RxAccList.Mute,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = maxSubtitleLines,
                    overflow = TextOverflow.Ellipsis,
                )
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
            .shadow(12.dp, shape, ambientColor = RxAccList.Violet, spotColor = RxAccList.Violet)
            .clip(shape)
            .background(Brush.linearGradient(listOf(RxAccList.Blue, RxAccList.Violet, RxAccList.Pink)))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyAccounts(onAdd: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconTile(FoxyIcons.Login, RxAccList.Violet, size = 56.dp)
        Spacer(Modifier.height(2.dp))
        Text(
            LanguageManager.t("accounts_empty_title", "No accounts yet"),
            color = RxAccList.Text,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 17.sp,
        )
        Text(
            LanguageManager.t("accounts_empty_hint", "Add a Firefox account to connect."),
            color = RxAccList.Mute,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        GradientButton(
            text = LanguageManager.t("add_account", "Add account"),
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RotationRow(
    threshold: Float,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(FoxyIcons.SwapHoriz, RxAccList.Violet)
            Spacer(Modifier.width(12.dp))
            Text(
                LanguageManager.t("auto_rotate", "Auto account rotation"),
                color = RxAccList.Text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${threshold.toInt()}%",
                color = RxAccList.Violet,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp,
            )
        }
        Slider(
            value = threshold,
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            valueRange = 50f..100f,
            steps = 9,
            colors = SliderDefaults.colors(
                thumbColor = RxAccList.Violet,
                activeTrackColor = RxAccList.Violet,
                inactiveTrackColor = RxAccList.Line2,
                activeTickColor = RxAccList.Pink.copy(alpha = 0.7f),
                inactiveTickColor = RxAccList.Line,
            ),
        )
        Text(
            LanguageManager.t(
                "auto_rotate_hint",
                "When the active account uses this much of its monthly quota, the next enabled account takes over.",
            ),
            color = RxAccList.Mute,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }
}

@Composable
private fun RankTile(index: Int, tint: Color) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.22f), tint.copy(alpha = 0.08f))))
            .border(1.dp, tint.copy(alpha = 0.28f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("$index", color = tint, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
    }
}

@Composable
private fun AccountRow(
    account: MultiAccountStore.Account,
    index: Int,
    isActive: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    thresholdPercent: Int,
    onToggle: (Boolean) -> Unit,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRefresh: () -> Unit,
    onSignInAgain: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }

    val statusColor = when (account.status) {
        AccountStatus.READY -> if (isActive) RxAccList.Good else RxAccList.Ready
        AccountStatus.RESERVED -> RxAccList.Warn
        AccountStatus.REAUTH_REQUIRED -> RxAccList.Bad
        AccountStatus.UNAVAILABLE -> RxAccList.Mute
    }
    val statusLabel = when (account.status) {
        AccountStatus.READY -> if (isActive) LanguageManager.t("status_active", "Active") else LanguageManager.t("status_ready", "Ready")
        AccountStatus.RESERVED -> LanguageManager.t("status_reserved", "Reserved")
        AccountStatus.REAUTH_REQUIRED -> LanguageManager.t("status_reauth", "Sign-in required")
        AccountStatus.UNAVAILABLE -> LanguageManager.t("status_unavailable", "Unavailable")
    }
    val hasMeter = !account.unlimited && account.limitBytes > 0
    val quotaText = when {
        account.unlimited -> LanguageManager.t("quota_unlimited", "Unlimited") + " ∞"
        account.limitBytes > 0 -> {
            val used = formatBytes(account.usedBytes)
            val limit = formatBytes(account.limitBytes)
            val base = LanguageManager.t("account_used_of", "{used} of {limit} used")
                .replace("{used}", used)
                .replace("{limit}", limit)
            if (account.resetAtEpochSeconds > System.currentTimeMillis() / 1000) {
                base + " · " + LanguageManager.t("account_resets", "resets") + " " +
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(account.resetAtEpochSeconds * 1000))
            } else base
        }
        else -> LanguageManager.t("account_quota_unknown", "Quota unknown")
    }
    val reasonText = reasonTextFor(account)

    Column(
        Modifier
            .fillMaxWidth()
            .background(if (isActive) RxAccList.Violet.copy(alpha = 0.08f) else Color.Transparent)
            .alpha(if (account.enabled) 1f else 0.55f)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Rank tile + email/status + enable switch
        Row(verticalAlignment = Alignment.CenterVertically) {
            RankTile(index, statusColor)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    account.email,
                    color = RxAccList.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(statusLabel, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = account.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = RxAccList.Violet,
                    checkedBorderColor = RxAccList.Violet,
                    uncheckedThumbColor = RxAccList.Mute,
                    uncheckedTrackColor = RxAccList.Panel2,
                    uncheckedBorderColor = RxAccList.Line2,
                ),
            )
        }

        // Details, aligned under the text (tile 38 + gap 12)
        Column(Modifier.padding(start = 50.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (reasonText != null) {
                Text(reasonText, color = RxAccList.Mute, fontSize = 12.sp, lineHeight = 16.sp)
            }
            if (account.retryAtEpochSeconds > System.currentTimeMillis() / 1000 &&
                (account.status == AccountStatus.RESERVED || account.status == AccountStatus.UNAVAILABLE)
            ) {
                Text(
                    LanguageManager.t("account_reserved_until", "Back on") + " " +
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(account.retryAtEpochSeconds * 1000)),
                    color = RxAccList.Warn,
                    fontSize = 12.sp,
                )
            }
            if (hasMeter) {
                ThresholdProgressBar(
                    usedFraction = account.usedFraction,
                    thresholdPercent = thresholdPercent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(quotaText, color = RxAccList.Mute, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (hasMeter) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${(account.usedFraction * 100).toInt().coerceIn(0, 100)}%",
                        color = RxAccList.Text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Actions: reorder · primary action · more
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMoveUp, enabled = !isFirst, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = LanguageManager.t("move_up", "Move up"),
                        tint = if (isFirst) RxAccList.Mute.copy(alpha = 0.3f) else RxAccList.Mute,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onMoveDown, enabled = !isLast, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = LanguageManager.t("move_down", "Move down"),
                        tint = if (isLast) RxAccList.Mute.copy(alpha = 0.3f) else RxAccList.Mute,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (account.status == AccountStatus.REAUTH_REQUIRED) {
                    androidx.compose.material3.Button(
                        onClick = onSignInAgain,
                        shape = RoundedCornerShape(12.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = RxAccList.Bad),
                        modifier = Modifier.height(34.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                    ) {
                        Text(
                            LanguageManager.t("account_sign_in_again", "Sign in"),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else if (!isActive && account.enabled && account.status == AccountStatus.READY) {
                    Surface(
                        onClick = onSelect,
                        shape = RoundedCornerShape(12.dp),
                        color = RxAccList.Violet.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, RxAccList.Violet.copy(alpha = 0.45f)),
                    ) {
                        Text(
                            LanguageManager.t("select", "Select"),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            color = Color(0xFFC4B5FD),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { more = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.MoreVert, null, tint = RxAccList.Mute, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(
                            text = { Text(LanguageManager.t("refresh_quota", "Refresh quota")) },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            onClick = { more = false; onRefresh() },
                        )
                        DropdownMenuItem(
                            text = { Text(LanguageManager.t("remove", "Remove"), color = RxAccList.Bad) },
                            leadingIcon = { Icon(FoxyIcons.DeleteOutline, null, tint = RxAccList.Bad) },
                            onClick = { more = false; onRemove() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThresholdProgressBar(
    usedFraction: Float,
    thresholdPercent: Int,
    modifier: Modifier = Modifier,
) {
    val track = Color(0xFF232545)
    val crossed = usedFraction >= thresholdPercent / 100f
    val fillColors = if (crossed) listOf(RxAccList.Warn, RxAccList.Pink) else listOf(RxAccList.Blue, RxAccList.Violet)
    Canvas(
        modifier = modifier
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(track),
    ) {
        val w = size.width
        val fillW = w * usedFraction.coerceIn(0f, 1f)
        if (fillW > 0f) {
            drawRect(
                brush = Brush.horizontalGradient(fillColors, startX = 0f, endX = fillW.coerceAtLeast(1f)),
                size = androidx.compose.ui.geometry.Size(fillW, size.height),
            )
        }
        val tickX = (w * (thresholdPercent / 100f)).coerceIn(0f, (w - 2f).coerceAtLeast(0f))
        drawRect(
            color = Color(0xFFEEF0FF).copy(alpha = 0.85f),
            topLeft = Offset(tickX, 0f),
            size = androidx.compose.ui.geometry.Size(2f, size.height),
        )
    }
}

private fun reasonTextFor(account: MultiAccountStore.Account): String? {
    return when (account.reasonCode) {
        AccountReason.QUOTA_THRESHOLD -> LanguageManager.t("reason_quota_threshold", "Switched early: too much of the monthly quota used.")
        AccountReason.QUOTA_EXHAUSTED -> LanguageManager.t("reason_quota_exhausted", "Monthly quota used up.")
        AccountReason.SIGNIN_REQUIRED, AccountReason.SIGNIN_RETRY -> LanguageManager.t("reason_signin_required", "Mozilla requires a new sign-in.")
        AccountReason.NOT_ENTITLED -> LanguageManager.t("reason_not_entitled", "Guardian refused this account.")
        else -> null
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.1f GB".format(gb)
        mb >= 1.0 -> "%.0f MB".format(mb)
        else -> "%.0f KB".format(kb)
    }
}