package com.vauth.rxfoxyvpn.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vauth.rxfoxyvpn.R
import com.vauth.rxfoxyvpn.data.FxaAuthRepository
import com.vauth.rxfoxyvpn.data.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private const val SESSION_RESTORE_TIMEOUT_MS = 8_000L

/* Design tokens from the Windows web UI (same values as the other screens) */
private object RxSplash {
    val Bg = Color(0xFF060714)
    val Line = Color(0xFF969EFF).copy(alpha = 0.16f)
    val Line2 = Color(0xFF969EFF).copy(alpha = 0.30f)
    val Mute = Color(0xFF9AA1D6)
    val Blue = Color(0xFF4D7CFF)
    val Violet = Color(0xFF8B5CF6)
    val Pink = Color(0xFFFF5FB0)
    val Cyan = Color(0xFF38D9FF)
}

@Composable
fun SplashScreen(
    authRepository: FxaAuthRepository,
    onSignedIn: () -> Unit,
    onNeedsLogin: () -> Unit,
) {
    LaunchedEffect(Unit) {
        delay(500)
        val status = withTimeoutOrNull(SESSION_RESTORE_TIMEOUT_MS) {
            authRepository.restoreSession()
        } ?: SessionStatus.UNREACHABLE

        when (status) {
            SessionStatus.ACTIVE -> onSignedIn()
            SessionStatus.UNREACHABLE -> onSignedIn()
            SessionStatus.NEEDS_LOGIN -> onNeedsLogin()
        }
    }

    // entrance animation
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val enter by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "splash-enter",
    )

    val inf = rememberInfiniteTransition(label = "splash")
    val spin by inf.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "spin",
    )
    val slowSpin by inf.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(30000, easing = LinearEasing)),
        label = "slow-spin",
    )
    val pulse by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val float by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "float",
    )
    val brandShift = inf.animateFloat(
        initialValue = 0f, targetValue = 120f,
        animationSpec = infiniteRepeatable(tween(5500, easing = LinearEasing)),
        label = "brand-shift",
    )
    val bar by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing)),
        label = "bar",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0D1033), RxSplash.Bg)))
    ) {
        // background art + shade + soft blobs
        Image(
            painter = painterResource(id = R.drawable.rx_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.45f * enter },
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(RxSplash.Bg.copy(alpha = 0.60f), RxSplash.Bg.copy(alpha = 0.35f), RxSplash.Bg.copy(alpha = 0.70f))
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
            blob(Color(0xFF3A62FF).copy(alpha = 0.32f), Offset(0f, 0f), big * 0.55f)
            blob(Color(0xFF8A3CFF).copy(alpha = 0.30f), Offset(size.width, size.height), big * 0.55f)
            blob(Color(0xFFFF4FA8).copy(alpha = 0.14f), Offset(size.width * 0.5f, size.height * 0.45f), big * 0.35f)
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer { alpha = enter },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---------- logo with rings ----------
            Box(
                Modifier
                    .size(210.dp)
                    .graphicsLayer {
                        val s = 0.7f + 0.3f * enter
                        scaleX = s; scaleY = s
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val ctr = center
                    val r = size.minDimension / 2f
                    val hair = 1.dp.toPx()

                    // pulsing glow
                    val glowR = r * (0.62f + 0.10f * pulse)
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(RxSplash.Violet.copy(alpha = 0.45f), RxSplash.Violet.copy(alpha = 0f)),
                            center = ctr, radius = glowR,
                        ),
                        radius = glowR, center = ctr,
                    )

                    // dashed slow orbit
                    rotate(slowSpin, ctr) {
                        drawCircle(
                            color = RxSplash.Line2, radius = r - hair, center = ctr,
                            style = Stroke(hair, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 7.dp.toPx()))),
                        )
                    }
                    rotate(-slowSpin * 1.6f, ctr) {
                        drawCircle(
                            color = RxSplash.Violet.copy(alpha = 0.40f),
                            radius = r * 0.80f, center = ctr, style = Stroke(hair),
                        )
                    }

                    // spinning gradient arc (the actual "loading" indicator)
                    val arcR = r * 0.66f
                    rotate(spin, ctr) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                0f to RxSplash.Cyan.copy(alpha = 0.1f),
                                0.15f to RxSplash.Cyan,
                                0.30f to RxSplash.Pink,
                                0.31f to Color.Transparent,
                                1f to Color.Transparent,
                                center = ctr,
                            ),
                            startAngle = 0f,
                            sweepAngle = 110f,
                            useCenter = false,
                            topLeft = Offset(ctr.x - arcR, ctr.y - arcR),
                            size = Size(arcR * 2f, arcR * 2f),
                            style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }

                Image(
                    painter = painterResource(id = R.drawable.rx_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(92.dp)
                        .graphicsLayer { translationY = -8.dp.toPx() * float },
                )
            }

            Spacer(Modifier.height(8.dp))

            // ---------- animated brand ----------
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandPart("RX", listOf(Color(0xFF38D9FF), Color(0xFF4D7CFF), Color(0xFF9BE7FF), Color(0xFF38D9FF)), RxSplash.Cyan, brandShift, 0f)
                    BrandPart("FOXY", listOf(Color(0xFFFF9A3C), Color(0xFFFF5FB0), Color(0xFFFFD36B), Color(0xFFFF9A3C)), Color(0xFFFF8C3C), brandShift, 40f)
                    BrandPart("VPN", listOf(Color(0xFF8B5CF6), Color(0xFFE06BFF), Color(0xFFB9A2FF), Color(0xFF8B5CF6)), Color(0xFFB06CFF), brandShift, 80f)
                }
            }

            Spacer(Modifier.height(26.dp))

            // ---------- sliding gradient loader ----------
            Canvas(Modifier.width(150.dp).height(4.dp)) {
                val h = size.height
                drawRoundRect(
                    color = RxSplash.Line2.copy(alpha = 0.5f),
                    size = size,
                    cornerRadius = CornerRadius(h / 2f, h / 2f),
                )
                val seg = size.width * 0.38f
                val x = (size.width + seg) * bar - seg
                val left = x.coerceAtLeast(0f)
                val right = (x + seg).coerceAtMost(size.width)
                if (right > left) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(RxSplash.Blue, RxSplash.Violet, RxSplash.Pink),
                            startX = x, endX = x + seg,
                        ),
                        topLeft = Offset(left, 0f),
                        size = Size(right - left, h),
                        cornerRadius = CornerRadius(h / 2f, h / 2f),
                    )
                }
            }
        }
    }
}

@Composable
private fun BrandPart(
    text: String,
    colors: List<Color>,
    glow: Color,
    shift: State<Float>,
    phase: Float,
    fontSize: TextUnit = 30.sp,
    letterSpacing: TextUnit = 2.sp,
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