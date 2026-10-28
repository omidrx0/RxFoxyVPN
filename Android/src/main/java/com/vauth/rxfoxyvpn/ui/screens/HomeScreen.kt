package com.vauth.rxfoxyvpn.ui.screens

import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.vauth.rxfoxyvpn.BuildConfig
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.List as ListIcon
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vauth.rxfoxyvpn.R
import com.vauth.rxfoxyvpn.RxFoxyVpnApp
import com.vauth.rxfoxyvpn.data.GUARDIAN_ENDPOINT_DEFAULT
import com.vauth.rxfoxyvpn.data.GuardianClient
import com.vauth.rxfoxyvpn.data.LanguageManager
import com.vauth.rxfoxyvpn.data.formatBytes
import com.vauth.rxfoxyvpn.data.formatBytesPerSecond
import com.vauth.rxfoxyvpn.data.model.ConnectionState
import com.vauth.rxfoxyvpn.vpn.PingUtil
import com.vauth.rxfoxyvpn.vpn.RxFoxyVpnService
import com.vauth.rxfoxyvpn.vpn.tun.HevSocks5Tunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/* ------------------------------------------------------------------------------------------
   Design tokens — copied 1:1 from the Windows web UI (style.css :root)
   ------------------------------------------------------------------------------------------ */
private object Rx {
    val Bg = Color(0xFF060714)
    val PanelS = Color(0xFF0E1131)
    // The web UI blurs what is behind the panels; Compose can't, so panels are a bit more opaque.
    val Panel = Color(0xFF14173C).copy(alpha = 0.68f)
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

/**
 * Survives leaving/re-entering HomeScreen (the composable's own state is destroyed when you navigate away).
 * Keeps the moment the current VPN session started plus the last known rates.
 */
private object ConnectionSession {
    @Volatile var connectedAtMillis = 0L
    @Volatile var lastTotalBytes = 0L
    @Volatile var txRate = 0L
    @Volatile var rxRate = 0L

    fun elapsedSeconds(): Long =
        if (connectedAtMillis == 0L) 0L
        else ((System.currentTimeMillis() - connectedAtMillis) / 1000L).coerceAtLeast(0L)

    fun clear() {
        connectedAtMillis = 0L
        lastTotalBytes = 0L
        txRate = 0L
        rxRate = 0L
    }
}

private fun gbText(bytes: Long): String =
    String.format(Locale.ROOT, "%.1f", bytes / 1024.0 / 1024.0 / 1024.0)

/** Language strings in the Windows UI use "{n}" placeholders; works whether or not the string has one. */
private fun fill(template: String, n: String): String =
    if (template.contains("{n}")) template.replace("{n}", n) else "$template $n"

private class PingQuality(val color: Color, val label: String)

// Same thresholds as the Windows app (app.js -> quality())
private fun pingQuality(ms: Int?): PingQuality = when {
    ms == null -> PingQuality(Rx.Bad, LanguageManager.t("q_none", "پاسخ نداد"))
    ms < 60 -> PingQuality(Rx.Good, LanguageManager.t("q_best", "عالی"))
    ms < 120 -> PingQuality(Rx.Good, LanguageManager.t("q_good", "خوب"))
    ms < 200 -> PingQuality(Rx.Warn, LanguageManager.t("q_mid", "متوسط"))
    else -> PingQuality(Rx.Bad, LanguageManager.t("q_slow", "کند"))
}

/* ------------------------------------------------------------------------------------------
   Small building blocks
   ------------------------------------------------------------------------------------------ */
@Composable
private fun Glass(
    modifier: Modifier = Modifier,
    radius: Dp = 16.dp,
    color: Color = Rx.Panel,
    border: Color = Rx.Line,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(radius),
        color = color,
        border = BorderStroke(1.dp, border),
        content = content,
    )
}

@Composable
private fun IconTile(icon: ImageVector, size: Dp = 28.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(9.dp))
            .background(Brush.linearGradient(listOf(Rx.Blue.copy(alpha = 0.25f), Rx.Pink.copy(alpha = 0.20f)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFFCFC6FF), modifier = Modifier.size(size * 0.58f))
    }
}

@Composable
private fun FlagBadge(countryCode: String, size: Dp) {
    CountryFlag(countryCode = countryCode, size = size)
}

/* ------------------------------------------------------------------------------------------
   Header: logo + animated RX / FOXY / VPN + Beta badge  |  status pill + settings
   ------------------------------------------------------------------------------------------ */
@Composable
private fun RxBrand(fontSize: TextUnit) {
    val inf = rememberInfiniteTransition(label = "brand")
    val shift = inf.animateFloat(
        initialValue = 0f,
        targetValue = 120f,
        animationSpec = infiniteRepeatable(tween(5500, easing = LinearEasing)),
        label = "brand-shift",
    )
    val spacing = fontSize * 0.07f

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradientText("RX", listOf(Color(0xFF38D9FF), Color(0xFF4D7CFF), Color(0xFF9BE7FF), Color(0xFF38D9FF)), Color(0xFF38D9FF), shift, 0f, fontSize, spacing)
            GradientText("FOXY", listOf(Color(0xFFFF9A3C), Color(0xFFFF5FB0), Color(0xFFFFD36B), Color(0xFFFF9A3C)), Color(0xFFFF8C3C), shift, 40f, fontSize, spacing)
            GradientText("VPN", listOf(Color(0xFF8B5CF6), Color(0xFFE06BFF), Color(0xFFB9A2FF), Color(0xFF8B5CF6)), Color(0xFFB06CFF), shift, 80f, fontSize, spacing)
        }
    }
}

@Composable
private fun GradientText(
    text: String,
    colors: List<Color>,
    glow: Color,
    shift: State<Float>,
    phase: Float,
    fontSize: TextUnit,
    letterSpacing: TextUnit,
) {
    // The gradient is painted in the DRAW phase (shift.value is read inside drawWithContent),
    // so the animation no longer recomposes the screen 60 times a second.
    Text(
        text = text,
        style = TextStyle(
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = letterSpacing,
            shadow = Shadow(color = glow.copy(alpha = 0.55f), blurRadius = 8f),
        ),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val s = shift.value + phase
                drawRect(
                    brush = Brush.linearGradient(
                        colors = colors,
                        start = Offset(s, 0f),
                        end = Offset(s + 120f, 0f),
                        tileMode = TileMode.Repeated,
                    ),
                    blendMode = BlendMode.SrcIn,
                )
            },
    )
}

@Composable
private fun VersionBadge() {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Rx.Blue.copy(alpha = 0.24f), Rx.Pink.copy(alpha = 0.22f))))
            .border(1.dp, Color(0xFFAA96FF).copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 7.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(5.dp)
                .background(Brush.linearGradient(listOf(Rx.Blue, Rx.Violet, Rx.Pink)), CircleShape)
        )
        Spacer(Modifier.width(4.dp))
        Text("V ${BuildConfig.VERSION_NAME}", color = Color(0xFFEFEAFF), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun StatusPill(state: ConnectionState) {
    val textTarget = when (state) {
        ConnectionState.CONNECTED -> Rx.Good
        ConnectionState.CONNECTING -> Rx.Pink
        else -> Rx.Mute
    }
    val borderTarget = when (state) {
        ConnectionState.CONNECTED -> Rx.Good.copy(alpha = 0.45f)
        ConnectionState.CONNECTING -> Rx.Pink.copy(alpha = 0.40f)
        else -> Rx.Line
    }
    val bgTarget = if (state == ConnectionState.CONNECTED) Rx.Good.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.03f)
    val textColor by animateColorAsState(textTarget, tween(500), label = "pill-text")
    val borderColor by animateColorAsState(borderTarget, tween(500), label = "pill-border")
    val bgColor by animateColorAsState(bgTarget, tween(500), label = "pill-bg")

    val inf = rememberInfiniteTransition(label = "pill")
    val blink by inf.animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "blink",
    )
    val ripple by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "ripple",
    )

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(bgColor)
            .border(1.dp, borderColor, CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp), contentAlignment = Alignment.Center) {
            if (state == ConnectionState.CONNECTED) {
                Box(
                    Modifier
                        .size(8.dp)
                        .graphicsLayer {
                            val s = 1f + 2.4f * ripple
                            scaleX = s; scaleY = s; alpha = 0.9f * (1f - ripple)
                        }
                        .border(1.5.dp, Rx.Good, CircleShape)
                )
            }
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = if (state == ConnectionState.CONNECTING) blink else 1f }
                    .background(if (state == ConnectionState.DISCONNECTED) Rx.Mute else textTarget, CircleShape)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = when (state) {
                ConnectionState.CONNECTED -> LanguageManager.t("pill_on", "متصل")
                ConnectionState.CONNECTING -> LanguageManager.t("pill_ing", "در حال اتصال")
                ConnectionState.DISCONNECTED -> LanguageManager.t("pill_off", "قطع")
            },
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun TopBar(state: ConnectionState, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF080A20).copy(alpha = 0.55f))
            .drawBehind {
                drawLine(Rx.Line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.rx_logo),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(6.dp))
                RxBrand(fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                VersionBadge()
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(state)
            Surface(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(10.dp),
                color = Rx.Panel,
                border = BorderStroke(1.dp, Rx.Line),
            ) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = LanguageManager.t("settings", "تنظیمات"),
                        tint = Rx.Cyan,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------------------------------
   Background: wolf/fox art + shade + soft colour blobs (like .bg in the web UI)
   ------------------------------------------------------------------------------------------ */
@Composable
private fun RxBackground() {
    val inf = rememberInfiniteTransition(label = "bg")
    val kenBurns by inf.animateFloat(
        initialValue = 1.02f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(48000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "kenburns",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0D1033), Rx.Bg)))
    ) {
        Image(
            painter = painterResource(id = R.drawable.rx_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = kenBurns; scaleY = kenBurns; alpha = 0.38f },
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Rx.Bg.copy(alpha = 0.60f),
                            Rx.Bg.copy(alpha = 0.30f),
                            Rx.Bg.copy(alpha = 0.70f),
                        )
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
            blob(Color(0xFF3A62FF).copy(alpha = 0.30f), Offset(0f, 0f), big * 0.55f)
            blob(Color(0xFF8A3CFF).copy(alpha = 0.28f), Offset(size.width, size.height), big * 0.55f)
            blob(Color(0xFFFF4FA8).copy(alpha = 0.14f), Offset(size.width * 0.45f, size.height * 0.40f), big * 0.35f)
        }
    }
}

/* The connect button (glass orb + living fox) lives in FoxOrb.kt. */

/* ------------------------------------------------------------------------------------------
   Cards
   ------------------------------------------------------------------------------------------ */
@Composable
private fun StatBox(title: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Glass(modifier) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            IconTile(icon, 26.dp)
            Spacer(Modifier.height(8.dp))
            Text(title, color = Rx.Mute, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, color = Rx.Text, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 1)
        }
    }
}

@Composable
private fun TotalBox(title: String, value: String, icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Glass(modifier, radius = 12.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, color = Rx.Mute, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(value, color = Rx.Text, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun QuotaCard(
    known: Boolean,
    remainingBytes: Long,
    maxBytes: Long,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    // Like the Windows UI: the bar shows what is LEFT of the monthly quota.
    val target = if (known) (remainingBytes.toFloat() / maxBytes.toFloat()).coerceIn(0f, 1f) else 0f
    val fraction by animateFloatAsState(target, tween(700), label = "quota-fill")

    Glass(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Layers, contentDescription = null, tint = Rx.Cyan, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        LanguageManager.t("quota_title", "سهمیه ماهانه Firefox"),
                        color = Rx.Text, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !loading, onClick = onRefresh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = LanguageManager.t("refresh_quota", "Refresh quota"),
                            tint = Rx.Violet.copy(alpha = if (loading) 0.4f else 1f),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Text(
                    text = if (known) "${gbText(remainingBytes)} GB" else "—",
                    color = Rx.Text, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF080A20).copy(alpha = 0.8f))
                    .border(1.dp, Rx.Line, CircleShape)
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(Rx.Cyan, Rx.Good)))
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = if (known) {
                        fill(LanguageManager.t("quota_used", "مصرف‌شده: {n} GB · آخرین گزارش"), gbText(maxBytes - remainingBytes))
                    } else {
                        LanguageManager.t("quota_unknown", "اطلاعات سهمیه هنوز دریافت نشده")
                    },
                    color = Rx.Mute, fontSize = 12.sp,
                )
                Text(
                    text = if (known) {
                        fill(LanguageManager.t("quota_total", "از {n} GB"), gbText(maxBytes))
                    } else {
                        LanguageManager.t("quota_total_unknown", "از سقف ماهانه")
                    },
                    color = Rx.Mute, fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun Sonar(color: Color, pinging: Boolean) {
    val inf = rememberInfiniteTransition(label = "sonar")
    val p by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "sonar-p",
    )
    Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
        if (pinging) {
            Box(
                Modifier
                    .size(12.dp)
                    .graphicsLayer {
                        val s = 1f + 1.4f * p
                        scaleX = s; scaleY = s; alpha = 0.9f * (1f - p)
                    }
                    .border(1.5.dp, Rx.Pink, CircleShape)
            )
        }
        Box(Modifier.size(12.dp).background(if (pinging) Rx.Pink else color, CircleShape))
    }
}

/** Bottom dock: selected server + live ping, like `.dock` in the Windows UI. */
@Composable
private fun Dock(
    flagCode: String,
    serverName: String,
    pingMs: Int?,
    pingTested: Boolean,
    pinging: Boolean,
    canPing: Boolean,
    onPick: () -> Unit,
    onPing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Glass(modifier.fillMaxWidth(), radius = 18.dp, border = Rx.Line2) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Surface(
                onClick = onPick,
                shape = RoundedCornerShape(13.dp),
                color = Rx.Panel2,
                border = BorderStroke(1.dp, Rx.Line),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlagBadge(flagCode, 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(LanguageManager.t("selected", "سرور انتخاب‌شده"), color = Rx.Mute, fontSize = 11.sp)
                        Text(
                            serverName,
                            color = Rx.Text, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.ListIcon, contentDescription = null, tint = Rx.Mute, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(Modifier.height(7.dp))

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val q = when {
                    pinging -> null
                    pingTested -> pingQuality(pingMs)
                    else -> null
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Sonar(color = q?.color ?: Rx.Mute, pinging = pinging)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = pingMs?.toString() ?: "—",
                                color = Rx.Text, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp,
                            )
                            Spacer(Modifier.width(2.dp))
                            Text("ms", color = Rx.Mute, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
                        }
                        Text(
                            text = when {
                                pinging -> LanguageManager.t("pinging", "در حال پینگ…")
                                pingTested -> q?.label ?: ""
                                else -> LanguageManager.t("ping_none", "پینگ گرفته نشده")
                            },
                            color = Rx.Mute, fontSize = 11.sp,
                        )
                    }
                }

                Surface(
                    onClick = onPing,
                    enabled = canPing && !pinging,
                    shape = RoundedCornerShape(10.dp),
                    color = Rx.Panel,
                    border = BorderStroke(1.dp, Rx.Line),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Rx.Cyan, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(LanguageManager.t("ping_btn", "پینگ بگیر"), color = Rx.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------------------------------
   Screen
   ------------------------------------------------------------------------------------------ */
@Composable
fun HomeScreen(
    app: RxFoxyVpnApp,
    onRequestConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val state by RxFoxyVpnService.state.collectAsState()
    val lastError by RxFoxyVpnService.lastError.collectAsState()
    val selectedProxy by app.proxyStateStore.selectedProxyFlow.collectAsState()
    val currentLang by LanguageManager.currentLang.collectAsState()
    remember(currentLang) { currentLang } // re-compose every string when the language changes

    // The connect button has six looks, exactly like the artwork and the Windows UI:
    // off (no server) / ready / connecting / processing (disconnecting) / on / error.
    var disconnecting by remember { mutableStateOf(false) }
    var errorFlash by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state != ConnectionState.CONNECTED && disconnecting) { delay(900); disconnecting = false }
    }
    LaunchedEffect(disconnecting) { if (disconnecting) { delay(5000); disconnecting = false } }
    LaunchedEffect(lastError, state) {
        if (lastError != null && state == ConnectionState.DISCONNECTED) {
            errorFlash = true; delay(4200); errorFlash = false
        } else errorFlash = false
    }
    val orb = when {
        disconnecting -> OrbState.PROCESSING
        state == ConnectionState.CONNECTING -> OrbState.CONNECTING
        state == ConnectionState.CONNECTED -> OrbState.ON
        errorFlash -> OrbState.ERROR
        selectedProxy != null -> OrbState.READY
        else -> OrbState.OFF
    }
    val pal = orbPalette(orb)
    val c by animateColorAsState(pal.c, tween(700), label = "c")
    val c2 by animateColorAsState(pal.c2, tween(700), label = "c2")

    // Stats and timer
    // Initialised from the running session so coming back to this screen doesn't flash zeros.
    val alreadyConnected = state == ConnectionState.CONNECTED
    var txBytes by remember { mutableLongStateOf(if (alreadyConnected) HevSocks5Tunnel.stats()[1] else 0L) }
    var rxBytes by remember { mutableLongStateOf(if (alreadyConnected) HevSocks5Tunnel.stats()[3] else 0L) }
    var txRate by remember { mutableLongStateOf(if (alreadyConnected) ConnectionSession.txRate else 0L) }
    var rxRate by remember { mutableLongStateOf(if (alreadyConnected) ConnectionSession.rxRate else 0L) }
    var connectedSeconds by remember { mutableLongStateOf(if (alreadyConnected) ConnectionSession.elapsedSeconds() else 0L) }

    // Quota from the Guardian API (no fake defaults — unknown stays unknown, like on Windows)
    var quotaMaxBytes by remember { mutableStateOf<Long?>(null) }
    var quotaRemainingBytes by remember { mutableStateOf<Long?>(null) }
    var isQuotaLoading by remember { mutableStateOf(false) }

    // Ping
    var serverPingMs by remember { mutableStateOf<Int?>(null) }
    var pingTested by remember { mutableStateOf(false) }
    var isPingingServer by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun fetchRealQuota() {
        isQuotaLoading = true
        scope.launch(Dispatchers.IO) {
            val token = app.authRepository.currentAccessToken()
            if (token != null) {
                runCatching {
                    GuardianClient().fetchUserInfo(GUARDIAN_ENDPOINT_DEFAULT, token)
                }.onSuccess { info ->
                    withContext(Dispatchers.Main) {
                        quotaMaxBytes = info.maxBytes
                        quotaRemainingBytes = info.quotaRemaining
                    }
                }
            }
            withContext(Dispatchers.Main) {
                isQuotaLoading = false
            }
        }
    }

    fun measureServerPing() {
        val proxy = selectedProxy ?: return
        isPingingServer = true
        scope.launch(Dispatchers.IO) {
            val latency = PingUtil.measureTcpLatencyMs(proxy.host, proxy.port)
            withContext(Dispatchers.Main) {
                serverPingMs = latency
                pingTested = true
                isPingingServer = false
            }
        }
    }

    LaunchedEffect(Unit) {
        fetchRealQuota()
    }

    LaunchedEffect(selectedProxy) {
        serverPingMs = null
        pingTested = false
        measureServerPing()
    }

    LaunchedEffect(state) {
        if (state == ConnectionState.CONNECTED) {
            var lastStats = HevSocks5Tunnel.stats()
            var lastTime = System.currentTimeMillis()

            // Totals dropping below what we saw before means the tunnel was restarted -> new session.
            val totalNow = lastStats[1] + lastStats[3]
            if (ConnectionSession.connectedAtMillis == 0L || totalNow < ConnectionSession.lastTotalBytes) {
                ConnectionSession.clear()
                ConnectionSession.connectedAtMillis = lastTime
            }
            ConnectionSession.lastTotalBytes = totalNow

            // show the real values immediately (no reset to zero)
            txBytes = lastStats[1]
            rxBytes = lastStats[3]
            connectedSeconds = ConnectionSession.elapsedSeconds()

            while (true) {
                delay(1000L)
                val stats = HevSocks5Tunnel.stats()
                val now = System.currentTimeMillis()
                val elapsed = ((now - lastTime).coerceAtLeast(1)) / 1000.0
                connectedSeconds = ConnectionSession.elapsedSeconds()
                txBytes = stats[1]
                rxBytes = stats[3]
                txRate = ((stats[1] - lastStats[1]).coerceAtLeast(0) / elapsed).toLong()
                rxRate = ((stats[3] - lastStats[3]).coerceAtLeast(0) / elapsed).toLong()
                ConnectionSession.txRate = txRate
                ConnectionSession.rxRate = rxRate
                ConnectionSession.lastTotalBytes = stats[1] + stats[3]
                lastStats = stats
                lastTime = now
            }
        } else {
            ConnectionSession.clear()
            txBytes = 0L
            rxBytes = 0L
            txRate = 0L
            rxRate = 0L
            connectedSeconds = 0L
        }
    }

    val hours = connectedSeconds / 3600
    val minutes = (connectedSeconds % 3600) / 60
    val seconds = connectedSeconds % 60
    val uptimeFormatted = String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)

    val qMax = quotaMaxBytes
    val qRem = quotaRemainingBytes
    val quotaKnown = qMax != null && qRem != null && qMax > 0L && qRem in 0L..qMax

    val flagCode = selectedProxy?.countryCode ?: ""
    val countryName = selectedProxy?.let { proxy ->
        LanguageManager.getCountryName(proxy.countryCode, proxy.countryName.ifBlank { proxy.countryCode })
    } ?: "—"

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.statusBars),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            RxBackground()

            Column(Modifier.fillMaxSize()) {
                TopBar(state = state, onOpenSettings = onOpenSettings)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // ---------- Hero: dial + title + subtitle ----------
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Brush.radialGradient(listOf(Rx.Bg.copy(alpha = 0.55f), Rx.Bg.copy(alpha = 0f)))),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FoxOrb(
                            orb = orb,
                            onClick = {
                                if (state == ConnectionState.DISCONNECTED) {
                                    if (selectedProxy == null) {
                                        Toast.makeText(
                                            context,
                                            LanguageManager.t("select_server_first", "لطفاً ابتدا یک سرور انتخاب کنید."),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        onOpenServers()
                                    } else {
                                        onRequestConnect()
                                    }
                                } else {
                                    if (state == ConnectionState.CONNECTED) disconnecting = true
                                    onDisconnect()
                                }
                            },
                        )

                        Spacer(Modifier.height(8.dp))

                        Crossfade(targetState = state, label = "title") { s ->
                            Text(
                                text = when (s) {
                                    ConnectionState.CONNECTED -> LanguageManager.t("ttl_conn", "محافظت‌شده")
                                    ConnectionState.CONNECTING -> LanguageManager.t("ttl_ing", "در حال اتصال…")
                                    ConnectionState.DISCONNECTED -> LanguageManager.t("ttl_idle", "قطع است")
                                },
                                style = TextStyle(
                                    brush = Brush.horizontalGradient(listOf(c, c2)),
                                    fontSize = 26.sp,
                                    lineHeight = 36.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                ),
                            )
                        }

                        Crossfade(targetState = state, label = "subtitle") { s ->
                            Row(
                                modifier = Modifier.padding(top = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                when (s) {
                                    ConnectionState.CONNECTED -> {
                                        FlagBadge(flagCode, 28.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            fill(LanguageManager.t("sub_conn", "وصل به {n}"), countryName),
                                            color = Rx.Mute, fontSize = 14.sp,
                                        )
                                    }
                                    ConnectionState.CONNECTING -> Text(
                                        LanguageManager.t("sub_ing", "دریافت مجوز و برقراری تونل"),
                                        color = Rx.Mute, fontSize = 14.sp,
                                    )
                                    ConnectionState.DISCONNECTED -> Text(
                                        LanguageManager.t("sub_idle", "یک سرور انتخاب کن و دکمه را بزن."),
                                        color = Rx.Mute, fontSize = 14.sp,
                                    )
                                }
                            }
                        }

                        val error = lastError
                        if (error != null && state != ConnectionState.CONNECTING) {
                            Spacer(Modifier.height(6.dp))
                            Text(error, color = Rx.Bad, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    // ---------- Stats: uptime / upload / download (same order as Windows) ----------
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatBox(
                            title = LanguageManager.t("s_up", "زمان اتصال"),
                            value = if (state == ConnectionState.CONNECTED) uptimeFormatted else "00:00:00",
                            icon = Icons.Default.Schedule,
                            modifier = Modifier.weight(1f),
                        )
                        StatBox(
                            title = LanguageManager.t("upload_rate", "آپلود در لحظه"),
                            value = formatBytesPerSecond(txRate),
                            icon = Icons.Default.CloudUpload,
                            modifier = Modifier.weight(1f),
                        )
                        StatBox(
                            title = LanguageManager.t("download_rate", "دانلود در لحظه"),
                            value = formatBytesPerSecond(rxRate),
                            icon = Icons.Default.CloudDownload,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // ---------- Traffic totals ----------
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Layers, contentDescription = null, tint = Rx.Cyan, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(
                                LanguageManager.t("traffic_total_title", "حجم مصرفی این اتصال"),
                                color = Rx.Mute, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TotalBox(
                                title = LanguageManager.t("uploaded_total", "مجموع آپلود"),
                                value = formatBytes(txBytes),
                                icon = Icons.Default.CloudUpload,
                                tint = Color(0xFF65E2B5),
                                modifier = Modifier.weight(1f),
                            )
                            TotalBox(
                                title = LanguageManager.t("downloaded_total", "مجموع دانلود"),
                                value = formatBytes(rxBytes),
                                icon = Icons.Default.CloudDownload,
                                tint = Rx.Cyan,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    // ---------- Monthly quota ----------
                    QuotaCard(
                        known = quotaKnown,
                        remainingBytes = qRem ?: 0L,
                        maxBytes = qMax ?: 0L,
                        loading = isQuotaLoading,
                        onRefresh = { fetchRealQuota() },
                    )
                }

                // ---------- Dock (stays at the bottom, outside the scroll area) ----------
                Dock(
                    flagCode = flagCode,
                    serverName = countryName,
                    pingMs = serverPingMs,
                    pingTested = pingTested,
                    pinging = isPingingServer,
                    canPing = selectedProxy != null,
                    onPick = onOpenServers,
                    onPing = { measureServerPing() },
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                )
            }
        }
    }
}