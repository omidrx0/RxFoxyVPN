package com.vauth.rxfoxyvpn.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vauth.rxfoxyvpn.R
import com.vauth.rxfoxyvpn.data.FxaAuthRepository
import com.vauth.rxfoxyvpn.data.GUARDIAN_ENDPOINT_DEFAULT
import com.vauth.rxfoxyvpn.data.GuardianClient
import com.vauth.rxfoxyvpn.data.LanguageManager
import com.vauth.rxfoxyvpn.data.formatBytes
import com.vauth.rxfoxyvpn.data.model.Entitlement
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ScaffoldDefaults

/* Design tokens from the Windows web UI (same values as the other screens) */
private object RxAcc {
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
}

@Composable
fun AccountScreen(
    authRepository: FxaAuthRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entitlement by remember { mutableStateOf<Entitlement?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        isLoading = true
        errorMessage = null
        scope.launch {
            val accessToken = authRepository.currentAccessToken()
            if (accessToken == null) {
                isLoading = false
                errorMessage = LanguageManager.t("err_not_signed_in", "Not signed in.")
                return@launch
            }
            runCatching { GuardianClient().fetchUserInfo(GUARDIAN_ENDPOINT_DEFAULT, accessToken) }
                .onSuccess { entitlement = it }
                .onFailure { errorMessage = it.message ?: LanguageManager.t("err_load_account", "Failed to load account info") }
            isLoading = false
        }
    }

    val currentLang by LanguageManager.currentLang.collectAsState()
    remember(currentLang) { } // re-compose every string when the language changes

    LaunchedEffect(Unit) { refresh() }

    // Like HomeScreen: the content area goes all the way up behind the clock / battery,
    // and the header itself pads for the status bar (see `windowInsetsPadding` below).
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.statusBars),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AccountBackground()

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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = RxAcc.Text, modifier = Modifier.size(20.dp))
                    }
                    Text(
                        LanguageManager.t("account_section", "Account"),
                        color = RxAcc.Text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { refresh() }, enabled = !isLoading, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Refresh",
                            tint = if (isLoading) RxAcc.Mute else RxAcc.Cyan,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(RxAcc.Line))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ProfileCard(entitlement)

                    when {
                        isLoading -> Box(
                            Modifier.fillMaxWidth().padding(top = 28.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = RxAcc.Cyan)
                        }

                        errorMessage != null -> GlassCard {
                            Column(
                                Modifier.fillMaxWidth().padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    errorMessage.orEmpty(),
                                    color = RxAcc.Bad, fontSize = 13.5.sp, textAlign = TextAlign.Center,
                                )
                                GradientButton(
                                    text = LanguageManager.t("retry", "Retry"),
                                    onClick = { refresh() },
                                    height = 44.dp,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        entitlement != null -> {
                            val info = entitlement!!
                            val remaining = info.quotaRemaining
                            val max = info.maxBytes

                            // ----- data card -----
                            GlassCard {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconTile(Icons.Filled.DataUsage, RxAcc.Cyan)
                                        Spacer(Modifier.width(12.dp))
                                        Text(
                                            LanguageManager.t("data_remaining", "Data remaining"),
                                            color = RxAcc.Text, fontWeight = FontWeight.Bold, fontSize = 14.5.sp,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = when {
                                                !info.limitedBandwidth -> LanguageManager.t("unlimited", "Unlimited")
                                                remaining != null -> formatBytes(remaining)
                                                max != null -> "${LanguageManager.t("limited", "Limited")}: ${formatBytes(max)}"
                                                else -> LanguageManager.t("limited", "Limited")
                                            },
                                            color = if (!info.limitedBandwidth) RxAcc.Good else RxAcc.Text,
                                            fontWeight = FontWeight.ExtraBold, fontSize = 17.sp,
                                        )
                                    }

                                    if (info.limitedBandwidth && remaining != null && max != null && max > 0L) {
                                        val target = (remaining.toFloat() / max.toFloat()).coerceIn(0f, 1f)
                                        val fraction by animateFloatAsState(target, tween(700), label = "acc-quota")
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .height(10.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF080A20).copy(alpha = 0.8f))
                                                .border(1.dp, RxAcc.Line, CircleShape)
                                        ) {
                                            Box(
                                                Modifier
                                                    .fillMaxHeight()
                                                    .fillMaxWidth(fraction)
                                                    .clip(CircleShape)
                                                    .background(Brush.horizontalGradient(listOf(RxAcc.Cyan, RxAcc.Good)))
                                            )
                                        }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(
                                                "${formatBytes((max - remaining).coerceAtLeast(0L))}  ·  ${LanguageManager.t("used", "Used")}",
                                                color = RxAcc.Mute, fontSize = 12.sp,
                                            )
                                            Text(formatBytes(max), color = RxAcc.Mute, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }

                            // ----- details card -----
                            GlassCard {
                                Column {
                                    InfoRow(
                                        icon = Icons.Filled.Verified,
                                        tint = if (info.subscribed) RxAcc.Good else RxAcc.Bad,
                                        label = LanguageManager.t("subscription", "Subscription"),
                                    ) {
                                        StatusPill(
                                            active = info.subscribed,
                                            text = if (info.subscribed) LanguageManager.t("active", "Active") else LanguageManager.t("inactive", "Inactive"),
                                        )
                                    }
                                    Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(1.dp).background(RxAcc.Line))
                                    InfoRow(
                                        icon = Icons.Filled.Badge,
                                        tint = RxAcc.Violet,
                                        label = LanguageManager.t("account_id", "Account ID"),
                                    ) {
                                        Text(
                                            info.uid.ifBlank { "\u2014" },
                                            color = RxAcc.Text, fontSize = 12.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(start = 12.dp),
                                        )
                                    }
                                }
                            }

                            GradientButton(
                                text = LanguageManager.t("manage_sub_btn", "Manage subscription"),
                                icon = Icons.AutoMirrored.Filled.OpenInNew,
                                onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GUARDIAN_ENDPOINT_DEFAULT)))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
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
private fun AccountBackground() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0D1033), RxAcc.Bg)))
    ) {
        Image(
            painter = painterResource(id = R.drawable.rx_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.30f },
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(RxAcc.Bg.copy(alpha = 0.70f), RxAcc.Bg.copy(alpha = 0.50f), RxAcc.Bg.copy(alpha = 0.80f))
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
private fun GlassCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = RxAcc.Panel,
        border = BorderStroke(1.dp, RxAcc.Line),
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun ProfileCard(info: Entitlement?) {
    GlassCard {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // avatar with gradient ring
            Box(
                Modifier
                    .size(84.dp)
                    .shadow(18.dp, CircleShape, ambientColor = RxAcc.Violet, spotColor = RxAcc.Violet)
                    .clip(CircleShape)
                    .background(Brush.sweepGradient(listOf(RxAcc.Blue, RxAcc.Violet, RxAcc.Pink, RxAcc.Violet, RxAcc.Blue))),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(78.dp).clip(CircleShape).background(RxAcc.PanelS),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.AccountCircle,
                        contentDescription = null,
                        tint = Color(0xFFC4B5FD),
                        modifier = Modifier.size(52.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                LanguageManager.t("firefox_account", "Firefox Account"),
                color = RxAcc.Text, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp,
            )
            Text(
                LanguageManager.t("signed_in_mozilla", "Signed in with Mozilla VPN"),
                color = RxAcc.Mute, fontSize = 13.sp,
            )
            if (info != null) {
                Spacer(Modifier.height(6.dp))
                StatusPill(
                    active = info.subscribed,
                    text = if (info.subscribed) LanguageManager.t("active", "Active") else LanguageManager.t("inactive", "Inactive"),
                )
            }
        }
    }
}

@Composable
private fun StatusPill(active: Boolean, text: String) {
    val c = if (active) RxAcc.Good else RxAcc.Bad
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(c.copy(alpha = 0.10f))
            .border(1.dp, c.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(c, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(text, color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
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
private fun InfoRow(
    icon: ImageVector,
    tint: Color,
    label: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint)
        Spacer(Modifier.width(12.dp))
        Text(label, color = RxAcc.Mute, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 50.dp,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .height(height)
            .shadow(12.dp, shape, ambientColor = RxAcc.Violet, spotColor = RxAcc.Violet)
            .clip(shape)
            .background(Brush.linearGradient(listOf(RxAcc.Blue, RxAcc.Violet, RxAcc.Pink)))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}