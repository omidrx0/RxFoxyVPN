package com.vauth.rxfoxyvpn.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.vauth.rxfoxyvpn.R
import com.vauth.rxfoxyvpn.data.AppLogger
import com.vauth.rxfoxyvpn.data.LanguageManager
import com.vauth.rxfoxyvpn.data.LogEntry
import com.vauth.rxfoxyvpn.data.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ScaffoldDefaults

/* Design tokens from the Windows web UI (same values as the other screens) */
private object RxLog {
    val Bg = Color(0xFF060714)
    val PanelS = Color(0xFF0E1131)
    val Panel = Color(0xFF14173C).copy(alpha = 0.72f)
    val Panel2 = Color(0xFF282C68).copy(alpha = 0.50f)
    val Line = Color(0xFF969EFF).copy(alpha = 0.16f)
    val Line2 = Color(0xFF969EFF).copy(alpha = 0.30f)
    val Text = Color(0xFFEEF0FF)
    val Mute = Color(0xFF9AA1D6)
    val Violet = Color(0xFF8B5CF6)
    val Cyan = Color(0xFF38D9FF)
    val Good = Color(0xFF34E0A1)
    val Bad = Color(0xFFFF5C7A)
    val Warn = Color(0xFFFFB86B)
}

private enum class LogFilter(val labelKey: String, val fallback: String, val levels: Set<LogLevel>) {
    ALL("logs_filter_all", "All", setOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)),
    INFO("logs_filter_info", "Info", setOf(LogLevel.INFO)),
    PROBLEMS("logs_filter_problems", "Problems", setOf(LogLevel.WARN, LogLevel.ERROR)),
}

private suspend fun writeLogFile(context: Context): File = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "logs").apply { mkdirs() }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val file = File(dir, "rxfoxyvpn-logs-$stamp.txt")
    file.writeText(AppLogger.exportAsText())
    file
}

private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> RxLog.Bad
    LogLevel.WARN -> RxLog.Warn
    LogLevel.INFO -> RxLog.Cyan
}

@Composable
fun LogsScreen(onBack: () -> Unit) {
    val entries by AppLogger.entries.collectAsState()
    val currentLang by LanguageManager.currentLang.collectAsState()
    remember(currentLang) { } // re-compose every string when the language changes
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    var filter by remember { mutableStateOf(LogFilter.ALL) }

    val countsByFilter: Map<LogFilter, Int> = remember(entries) {
        LogFilter.entries.associateWith { option -> entries.count { it.level in option.levels } }
    }
    val visible: List<LogEntry> = remember(entries, filter) {
        if (filter == LogFilter.ALL) entries else entries.filter { it.level in filter.levels }
    }

    // Like HomeScreen: the content area goes all the way up behind the clock / battery,
    // and the header itself pads for the status bar (see `windowInsetsPadding` below).
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.statusBars),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LogsBackground()

            Column(Modifier.fillMaxSize()) {
                // ---------- header ----------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF080A20).copy(alpha = 0.55f))
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = RxLog.Text, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            LanguageManager.t("logs_title", "Logs"),
                            color = RxLog.Text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp,
                        )
                        Text(
                            if (visible.size == entries.size) "${entries.size}" else "${visible.size} / ${entries.size}",
                            color = RxLog.Mute, fontSize = 11.sp,
                        )
                    }
                    ActionIcon(Icons.Filled.Share, LanguageManager.t("logs_share", "Save/share logs as a file"), RxLog.Cyan) {
                        if (entries.isEmpty()) {
                            Toast.makeText(context, LanguageManager.t("logs_none_toast", "No log entries yet"), Toast.LENGTH_SHORT).show()
                            return@ActionIcon
                        }
                        scope.launch {
                            val file = writeLogFile(context)
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, file.name)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(shareIntent, LanguageManager.t("logs_chooser", "Save or send RxFoxyVPN logs")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        }
                    }
                    ActionIcon(Icons.Filled.ContentCopy, LanguageManager.t("logs_copy", "Copy logs"), RxLog.Violet) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("RxFoxyVPN logs", AppLogger.exportAsText()))
                        Toast.makeText(context, LanguageManager.t("logs_copied", "Copied"), Toast.LENGTH_SHORT).show()
                    }
                    ActionIcon(Icons.Filled.DeleteSweep, LanguageManager.t("logs_clear", "Clear logs"), RxLog.Bad) { AppLogger.clear() }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(RxLog.Line))

                // ---------- filter chips ----------
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (option in LogFilter.entries) {
                        FilterPill(
                            text = LanguageManager.t(option.labelKey, option.fallback),
                            count = countsByFilter[option] ?: 0,
                            on = filter == option,
                            accent = if (option == LogFilter.PROBLEMS) RxLog.Warn else RxLog.Violet,
                            onClick = { filter = option },
                        )
                    }
                }

                // ---------- list / empty ----------
                if (visible.isEmpty()) {
                    val noProblems = entries.isNotEmpty() && filter == LogFilter.PROBLEMS
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(RxLog.Panel2)
                                .border(1.dp, RxLog.Line2, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (noProblems) Icons.Filled.CheckCircle else Icons.Filled.Description,
                                contentDescription = null,
                                tint = if (noProblems) RxLog.Good else RxLog.Mute,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            when {
                                entries.isEmpty() -> LanguageManager.t("logs_empty", "No log entries yet.")
                                filter == LogFilter.PROBLEMS -> LanguageManager.t("logs_no_problems", "No warnings or errors. Nothing has gone wrong.")
                                else -> LanguageManager.t("logs_no_match", "Nothing matches this filter.")
                            },
                            color = RxLog.Mute, fontSize = 14.sp, textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(visible.asReversed(), key = { it.id }) { entry ->
                            LogRow(entry, timeFormat.format(Date(entry.timestampMillis)))
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------------------------------
   Pieces
   ------------------------------------------------------------------------------------------ */
@Composable
private fun LogsBackground() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0D1033), RxLog.Bg)))
    ) {
        Image(
            painter = painterResource(id = R.drawable.rx_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.22f },
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(RxLog.Bg.copy(alpha = 0.75f), RxLog.Bg.copy(alpha = 0.60f), RxLog.Bg.copy(alpha = 0.85f))
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
            blob(Color(0xFF3A62FF).copy(alpha = 0.22f), Offset(0f, 0f), big * 0.55f)
            blob(Color(0xFF8A3CFF).copy(alpha = 0.20f), Offset(size.width, size.height), big * 0.55f)
        }
    }
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun FilterPill(text: String, count: Int, on: Boolean, accent: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (on) accent.copy(alpha = 0.18f) else RxLog.Panel2,
        border = BorderStroke(1.dp, if (on) accent else RxLog.Line),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, color = RxLog.Text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(7.dp))
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (on) accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 7.dp, vertical = 1.dp)
            ) {
                Text("$count", color = RxLog.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, time: String) {
    val color = levelColor(entry.level)
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(RxLog.Panel)
            .border(1.dp, RxLog.Line, shape),
    ) {
        // coloured level bar
        Box(Modifier.width(3.dp).fillMaxHeight().background(color))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.16f))
                        .border(1.dp, color.copy(alpha = 0.40f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 1.dp)
                ) {
                    Text(entry.level.name, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.tag,
                    color = RxLog.Mute, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, modifier = Modifier.weight(1f),
                )
                Text(time, color = RxLog.Mute, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(5.dp))
            Text(
                entry.message,
                color = RxLog.Text,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}