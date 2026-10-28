package com.vauth.rxfoxyvpn.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/* ------------------------------------------------------------------------------------------
   The connect button: a glass orb with a living neon fox.
   Ported 1:1 from the Windows web UI (index.html orb svg + style.css + the "foxRig" in app.js).

   Six states, like the artwork:  OFF  READY  CONNECTING  PROCESSING  ON  ERROR
   The fox is a soft skeleton: the tail bends from its root, the head turns from the neck,
   ears flick, the body breathes. Joints never move, so the parts can never come apart.
   ------------------------------------------------------------------------------------------ */

internal enum class OrbState { OFF, READY, CONNECTING, PROCESSING, ON, ERROR }

internal class OrbPalette(
    val c: Color,    // main colour of the state
    val c2: Color,   // second colour
    val fx1: Color,  // fox: head side of the gradient
    val fx2: Color,  // fox: tail side of the gradient
    val ic: Color,   // power icon / arc highlight
)

internal fun orbPalette(s: OrbState): OrbPalette = when (s) {
    OrbState.OFF -> OrbPalette(Color(0xFF8A95BB), Color(0xFF566093), Color(0xFF4A5376), Color(0xFFA3ADCC), Color(0xFF98A2C4))
    // blue-violet
    OrbState.READY -> OrbPalette(Color(0xFF7B8CFF), Color(0xFFA07BFF), Color(0xFF4F7BFF), Color(0xFFA78BFF), Color(0xFFA9B8FF))
    // warm amber, so "working" never looks like ready / on
    OrbState.CONNECTING -> OrbPalette(Color(0xFFFFB84D), Color(0xFFFF7A59), Color(0xFFFF9F43), Color(0xFFFFD36A), Color(0xFFFFE29A))
    // mint
    OrbState.PROCESSING -> OrbPalette(Color(0xFF34E0B0), Color(0xFF8BF0C8), Color(0xFF1FBF9A), Color(0xFF7DF5CF), Color(0xFFB6FFE6))
    // violet with a touch of pink: the fox goes blue-violet -> pink
    OrbState.ON -> OrbPalette(Color(0xFF9B6CFF), Color(0xFFFF66C4), Color(0xFF6C7DFF), Color(0xFFFF7AD0), Color(0xFFE0C4FF))
    OrbState.ERROR -> OrbPalette(Color(0xFFFF4D6A), Color(0xFFFF8A5C), Color(0xFFFF4D5E), Color(0xFFFF9A9A), Color(0xFFFF6B86))
}

/* ---- pose table (degrees / orb units / seconds), identical to POSE in app.js ---- */
private const val HR = 0   // head rotation
private const val HX = 1
private const val HY = 2
private const val EL = 3   // left ear
private const val ER = 4   // right ear
private const val TR = 5   // tail bend
private const val WX = 6   // whole fox offset / rotation / scale
private const val WY = 7
private const val WR = 8
private const val WS = 9
private const val TA = 10  // tail sway amplitude
private const val TD = 11  // tail sway period
private const val HA = 12  // head look amplitude
private const val HD = 13  // head look period
private const val BA = 14  // breathing amount
private const val BD = 15  // breathing period

private fun pose(
    hr: Float, hx: Float, hy: Float, el: Float, er: Float, tr: Float,
    wx: Float, wy: Float, wr: Float, ws: Float,
    ta: Float, td: Float, ha: Float, hd: Float, ba: Float, bd: Float,
) = floatArrayOf(hr, hx, hy, el, er, tr, wx, wy, wr, ws, ta, td, ha, hd, ba, bd)

private val POSE_OFF = pose(17f, 3f, 5f, -26f, -30f, 12f, 0f, 3f, 0f, .97f, 1.2f, 7f, .8f, 9f, .03f, 6f)
private val POSE_READY = pose(-2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 4f, 3.6f, 2.4f, 7f, .02f, 5f)
private val POSE_CONNECTING = pose(-10f, 3f, -2f, 12f, 14f, -6f, 2f, 0f, -2f, 1.02f, 9f, .62f, 2f, 1.2f, .03f, .9f)
private val POSE_PROCESSING = pose(-4f, 0f, 0f, -14f, -16f, -4f, 0f, 0f, -3f, 1f, 17f, .9f, 3f, 1.5f, .025f, 1.2f)
private val POSE_ON = pose(-8f, 0f, -2f, 8f, 9f, -3f, 0f, 0f, 0f, 1.02f, 5f, 3.2f, 2.5f, 6f, .02f, 4.5f)
private val POSE_ERROR = pose(18f, -1f, 5f, -28f, -32f, 18f, -3f, 2f, -3f, .94f, 1.5f, 2f, 1f, 3f, 0f, 3f)

private fun poseOf(s: OrbState): FloatArray = when (s) {
    OrbState.OFF -> POSE_OFF
    OrbState.READY -> POSE_READY
    OrbState.CONNECTING -> POSE_CONNECTING
    OrbState.PROCESSING -> POSE_PROCESSING
    OrbState.ON -> POSE_ON
    OrbState.ERROR -> POSE_ERROR
}

/* ---- skeleton anchors (fox art space -> orb space, same mapping as the generator) ---- */
private fun mx(x: Float) = 100f + (x - 170f) * 0.64f
private fun my(y: Float) = 100f + (y - 170f) * 0.64f

private val TAIL_ROOT_X = mx(140f); private val TAIL_ROOT_Y = my(290f)
private val TAIL_TIP_X = mx(251f);  private val TAIL_TIP_Y = my(264f)
private val NECK_X = mx(62f);       private val NECK_Y = my(232f)
private val BODY_X = mx(110f);      private val BODY_Y = my(285f)
private val WHOLE_X = mx(110f);     private val WHOLE_Y = my(250f)
private val EAR_L_X = mx(64f);      private val EAR_L_Y = my(180f)
private val EAR_R_X = mx(80f);      private val EAR_R_Y = my(177f)
private val HEAD_Y0 = my(238f)      // neck bottom: head weight 0
private val HEAD_Y1 = my(212f)      // snout: head weight 1
private val TAIL_LEN = kotlin.math.hypot(TAIL_TIP_X - TAIL_ROOT_X, TAIL_TIP_Y - TAIL_ROOT_Y)
private val TAIL_UX = (TAIL_TIP_X - TAIL_ROOT_X) / TAIL_LEN
private val TAIL_UY = (TAIL_TIP_Y - TAIL_ROOT_Y) / TAIL_LEN

private const val RAD = (PI / 180.0).toFloat()
private const val TAU = (2.0 * PI).toFloat()

private fun smooth01(t: Float): Float { val k = t.coerceIn(0f, 1f); return k * k * (3f - 2f * k) }
private fun wave(x: Float): Float = sin(x * TAU)

// which skeleton part a path belongs to
private const val P_NONE = -1
private const val P_TAIL = 0
private const val P_BODY = 1
private const val P_HEAD = 2
private const val P_EAR_L = 3
private const val P_EAR_R = 4

private class FoxRig {
    // springs on the 16 pose values
    private val v = FloatArray(16)
    private val vel = FloatArray(16)
    private var started = false

    // ear flick springs (degrees)
    private var kickL = 0f; private var kickLv = 0f
    private var kickR = 0f; private var kickRv = 0f
    private var nextTwitch = 2.5f

    private var phT = 0f; private var phH = 0f; private var phB = 0f; private var phW = 0f
    var clock = 0f
    private var prev = OrbState.OFF
    private var onSince = -100f
    private var errSince = -100f

    // finger / pointer look target and smoothed look
    var tlx = 0f; var tly = 0f
    private var lx = 0f; private var ly = 0f

    // derived per frame
    private var a1 = 0f; private var a2 = 0f
    private var hAng = 0f; private var hx = 0f; private var hy = 0f
    private var eL = 0f; private var eR = 0f
    private var bs = 1f; private var bsy = 1f
    var wx = 0f; var wy = 0f; var wr = 0f; var ws = 1f

    // visual springs (alpha, arcs, swirl ...)
    var auraA = .55f
    var foxA = 1f
    var arcMain = 135f; var arcSecond = 85f
    var arcW = 10f; var arcW2 = 3.2f
    var arcA = .95f; var arcA2 = .4f
    var arcRot = 0f
    private var arcSpeed = 22.5f
    var swirlA = 0f; var swirlS1 = 0f; var swirlS2 = 0f; var swirlS3 = 0f; var swirlS4 = 0f
    var sparkA = 0f; var waveA = 0f; var sheenA = 0f; var crackA = 0f
    var glintA = .9f; var glintT = 0f
    var eyeBase = 1f
    var iconPulse = 0f; var pressIcon = 1f
    var state = OrbState.OFF

    private var ox = 0f; private var oy = 0f

    // reusable paths
    val pBody = Path(); val pBodyStreak = Path(); val pBodyRim = Path()
    val pTail = Path(); val pBlade = Path(); val pStreak1 = Path(); val pStreak2 = Path()
    val pTailRim = Path(); val pGlint = Path()
    val pEarL = Path(); val pEarR = Path(); val pHead = Path(); val pHeadHl = Path()
    val clipCircle = Path().apply { addOval(Rect(12f, 12f, 188f, 188f)) }
    val clipFox = Path().apply { addOval(Rect(5f, 5f, 195f, 195f)) }   // the fox never leaves the orb
    val crackPaths: List<Path> = FoxGeometry.CRACKS.map { g -> Path().also { build(it, g, P_NONE) } }
    val crackLens: List<Float> = crackPaths.map { p -> PathMeasure().also { it.setPath(p, false) }.length }
    val crackDst = Path()
    val glintLen: Float = run {
        val p = Path(); build(p, FoxGeometry.TAIL_GLINT, P_NONE)
        PathMeasure().also { it.setPath(p, false) }.length
    }
    var eyeX1 = 0f; var eyeY1 = 0f; var eyeX2 = 0f; var eyeY2 = 0f
    var noseX = 0f; var noseY = 0f
    val onElapsed: Float get() = clock - onSince
    val burstP: Float get() = ((clock - onSince) / 1.1f).coerceIn(0f, 1f)
    val errorT: Float get() = clock - errSince

    /** maps one point of the rest pose into the current pose; result in ox/oy */
    private fun map(part: Int, x: Float, y: Float) {
        when (part) {
            P_TAIL -> {
                val t = (((x - TAIL_ROOT_X) * TAIL_UX + (y - TAIL_ROOT_Y) * TAIL_UY) / TAIL_LEN).coerceIn(0f, 1f)
                val w = smooth01(t)
                rotateAround(x, y, TAIL_ROOT_X, TAIL_ROOT_Y, a1 * w + a2 * w * w)
            }
            P_BODY -> {
                ox = BODY_X + (x - BODY_X) * bs
                oy = BODY_Y + (y - BODY_Y) * bsy
            }
            P_HEAD -> headMap(x, y)
            P_EAR_L -> { rotateAround(x, y, EAR_L_X, EAR_L_Y, eL); headMap(ox, oy) }
            P_EAR_R -> { rotateAround(x, y, EAR_R_X, EAR_R_Y, eR); headMap(ox, oy) }
            else -> { ox = x; oy = y }
        }
    }

    private fun rotateAround(x: Float, y: Float, cx: Float, cy: Float, a: Float) {
        val s = sin(a); val k = cos(a)
        val dx = x - cx; val dy = y - cy
        ox = cx + dx * k - dy * s
        oy = cy + dx * s + dy * k
    }

    private fun headMap(x: Float, y: Float) {
        val w = smooth01((HEAD_Y0 - y) / (HEAD_Y0 - HEAD_Y1))
        rotateAround(x, y, NECK_X, NECK_Y, hAng * w)
        ox += hx * w
        oy += hy * w
    }

    fun build(path: Path, g: FloatArray, part: Int) {
        path.rewind()
        var i = 0
        while (i < g.size) {
            when (g[i].toInt()) {
                0 -> { map(part, g[i + 1], g[i + 2]); path.moveTo(ox, oy); i += 3 }
                1 -> { map(part, g[i + 1], g[i + 2]); path.lineTo(ox, oy); i += 3 }
                2 -> {
                    map(part, g[i + 1], g[i + 2]); val x1 = ox; val y1 = oy
                    map(part, g[i + 3], g[i + 4]); val x2 = ox; val y2 = oy
                    map(part, g[i + 5], g[i + 6])
                    path.cubicTo(x1, y1, x2, y2, ox, oy); i += 7
                }
                3 -> {
                    map(part, g[i + 1], g[i + 2]); val x1 = ox; val y1 = oy
                    map(part, g[i + 3], g[i + 4])
                    path.quadraticBezierTo(x1, y1, ox, oy); i += 5
                }
                else -> { path.close(); i += 1 }
            }
        }
    }

    private fun approach(cur: Float, target: Float, dt: Float, speed: Float): Float =
        cur + (target - cur) * min(1f, dt * speed)

    fun update(dt: Float, s: OrbState, pressed: Boolean) {
        clock += dt
        if (s != prev) {
            if (s == OrbState.ON) onSince = clock
            if (s == OrbState.ERROR) errSince = clock
            prev = s
        }
        state = s

        // ---- pose springs ----
        val base = poseOf(s)
        val tg = base.copyOf()
        if (pressed) { tg[WS] *= .94f; tg[WY] += 4f; tg[EL] -= 22f; tg[ER] -= 26f; tg[HR] += 8f }
        if (!started) { for (i in 0 until 16) { v[i] = tg[i]; vel[i] = 0f }; started = true }
        else for (i in 0 until 16) {
            vel[i] += ((tg[i] - v[i]) * 120f - vel[i] * 13f) * dt
            v[i] += vel[i] * dt
        }
        kickLv += (-kickL * 220f - kickLv * 11f) * dt; kickL += kickLv * dt
        kickRv += (-kickR * 220f - kickRv * 11f) * dt; kickR += kickRv * dt
        if (clock > nextTwitch) {
            if (s != OrbState.OFF && s != OrbState.ERROR) {
                if (Random.nextBoolean()) kickLv -= 300f + Random.nextFloat() * 120f
                else kickRv -= 300f + Random.nextFloat() * 120f
            }
            nextTwitch = clock + 2.5f + Random.nextFloat() * 4f
        }
        lx += (tlx - lx) * min(1f, dt * 6f)
        ly += (tly - ly) * min(1f, dt * 6f)

        phT += dt / max(.2f, v[TD]); phH += dt / max(.5f, v[HD]); phB += dt / max(.5f, v[BD])
        phW += dt / when (s) { OrbState.CONNECTING -> 1.1f; OrbState.PROCESSING -> 1.6f; OrbState.OFF -> 8f; else -> 5f }

        a1 = (v[TR] + v[TA] * wave(phT)) * RAD
        a2 = .35f * v[TA] * wave(phT - .18f) * RAD
        hAng = (v[HR] + ly * 6f + v[HA] * wave(phH)) * RAD
        hx = v[HX] + lx * 2f
        hy = v[HY] + ly * 1.5f
        eL = (v[EL] + lx * 3f + kickL) * RAD
        eR = (v[ER] + lx * 3f + kickR) * RAD
        bs = 1f + v[BA] * wave(phB)
        bsy = 1f + v[BA] * 1.5f * wave(phB)
        wx = v[WX] + sin(phW * TAU * .7f) * .6f
        wy = v[WY] - abs(wave(phW)) * 2.2f
        if (s == OrbState.ERROR) wx += sin(clock * 75f) * .8f
        wr = v[WR]; ws = v[WS]

        // ---- build the deformed fox ----
        build(pBody, FoxGeometry.BODY, P_BODY)
        build(pBodyStreak, FoxGeometry.BODY_STREAK, P_BODY)
        build(pBodyRim, FoxGeometry.BODY_RIM, P_BODY)
        build(pTail, FoxGeometry.TAIL, P_TAIL)
        build(pBlade, FoxGeometry.TAIL_BLADE, P_TAIL)
        build(pStreak1, FoxGeometry.TAIL_STREAK_1, P_TAIL)
        build(pStreak2, FoxGeometry.TAIL_STREAK_2, P_TAIL)
        build(pTailRim, FoxGeometry.TAIL_RIM, P_TAIL)
        build(pGlint, FoxGeometry.TAIL_GLINT, P_TAIL)
        build(pEarL, FoxGeometry.EAR_L, P_EAR_L)
        build(pEarR, FoxGeometry.EAR_R, P_EAR_R)
        build(pHead, FoxGeometry.HEAD, P_HEAD)
        build(pHeadHl, FoxGeometry.HEAD_HIGHLIGHT, P_HEAD)
        val e = FoxGeometry.EYE
        map(P_HEAD, e[1], e[2]); eyeX1 = ox; eyeY1 = oy
        map(P_HEAD, e[4], e[5]); eyeX2 = ox; eyeY2 = oy
        map(P_HEAD, FoxGeometry.NOSE_X, FoxGeometry.NOSE_Y); noseX = ox; noseY = oy

        // ---- eye: closed when asleep, wide when alert, blinks otherwise ----
        val eyeTarget = when (s) {
            OrbState.OFF -> .25f
            OrbState.CONNECTING, OrbState.PROCESSING -> 1.45f
            OrbState.ERROR -> .5f
            else -> 1f
        }
        eyeBase = approach(eyeBase, eyeTarget, dt, 8f)

        // ---- glass / arcs / swirl ----
        val on = s == OrbState.ON
        val tAura = when (s) { OrbState.OFF -> .14f; OrbState.READY -> .55f; OrbState.CONNECTING -> .95f; OrbState.PROCESSING -> .9f; OrbState.ON -> .8f; OrbState.ERROR -> .7f }
        auraA = approach(auraA, tAura, dt, 5f)

        val sp = 5f
        when (s) {
            OrbState.READY -> { arcMain = approach(arcMain, 135f, dt, sp); arcSecond = approach(arcSecond, 85f, dt, sp); arcW = approach(arcW, 10f, dt, sp); arcA = approach(arcA, .95f, dt, sp); arcA2 = approach(arcA2, .4f, dt, sp); arcSpeed = approach(arcSpeed, 22.5f, dt, 3f) }
            OrbState.CONNECTING -> { arcMain = approach(arcMain, 153f, dt, sp); arcSecond = approach(arcSecond, 92f, dt, sp); arcW = approach(arcW, 10.5f, dt, sp); arcA = approach(arcA, 1f, dt, sp); arcA2 = approach(arcA2, .45f, dt, sp); arcSpeed = approach(arcSpeed, 327f, dt, 3f) }
            OrbState.PROCESSING -> { arcMain = approach(arcMain, 78f, dt, sp); arcSecond = approach(arcSecond, 135f, dt, sp); arcW = approach(arcW, 6f, dt, sp); arcA = approach(arcA, .9f, dt, sp); arcA2 = approach(arcA2, .3f, dt, sp); arcSpeed = approach(arcSpeed, -200f, dt, 3f) }
            OrbState.ON -> { arcMain = approach(arcMain, 360f, dt, 3.2f); arcSecond = approach(arcSecond, 360f, dt, 3.2f); arcW = approach(arcW, 5f, dt, sp); arcA = approach(arcA, .95f, dt, sp); arcA2 = approach(arcA2, .5f, dt, sp); arcSpeed = approach(arcSpeed, 0f, dt, 3f) }
            else -> { arcA = approach(arcA, 0f, dt, sp); arcA2 = approach(arcA2, 0f, dt, sp); arcSpeed = approach(arcSpeed, 0f, dt, 3f) }
        }
        arcW2 = if (on) approach(arcW2, 2f, dt, sp) else approach(arcW2, 3.2f, dt, sp)
        arcRot = (arcRot + arcSpeed * dt) % 360f

        swirlA = approach(swirlA, if (s == OrbState.PROCESSING) 1f else 0f, dt, 6f)
        swirlS1 = (swirlS1 + 360f / 2.4f * dt) % 360f
        swirlS2 = (swirlS2 - 360f / 3.3f * dt) % 360f
        swirlS3 = (swirlS3 + 360f / 4.2f * dt) % 360f
        swirlS4 = (swirlS4 - 360f / 1.9f * dt) % 360f

        sparkA = approach(sparkA, if (s == OrbState.ON || s == OrbState.CONNECTING) 1f else 0f, dt, 5f)
        waveA = approach(waveA, if (on) 1f else 0f, dt, 4f)
        sheenA = approach(sheenA, if (on) 1f else 0f, dt, 4f)
        crackA = approach(crackA, if (s == OrbState.ERROR) 1f else 0f, dt, 6f)

        val glintTarget = when (s) { OrbState.READY, OrbState.ON -> .9f; OrbState.CONNECTING, OrbState.PROCESSING -> 1f; else -> 0f }
        glintA = approach(glintA, glintTarget, dt, 6f)
        val fast = s == OrbState.CONNECTING || s == OrbState.PROCESSING
        glintT = (glintT + dt / (if (fast) .9f else 3.4f)) % 1f

        val foxTarget = when (s) {
            OrbState.OFF -> .9f
            OrbState.ERROR -> {
                val p = (clock % 2.2f) / 2.2f
                when {
                    p < .35f -> .95f + (.55f - .95f) * (p / .35f)
                    p < .40f -> .55f + (1f - .55f) * ((p - .35f) / .05f)
                    p < .70f -> 1f + (.7f - 1f) * ((p - .40f) / .30f)
                    else -> .7f + (.95f - .7f) * ((p - .70f) / .30f)
                }
            }
            else -> 1f
        }
        foxA = approach(foxA, foxTarget, dt, 10f)

        // icon: breathing + press
        val pulsePeriod = when (s) { OrbState.READY -> 3f; OrbState.CONNECTING -> .9f; OrbState.PROCESSING -> 1.3f; else -> 0f }
        iconPulse = if (pulsePeriod > 0f) .5f - .5f * cos(clock / pulsePeriod * TAU) else approach(iconPulse, 0f, dt, 8f)
        pressIcon = approach(pressIcon, if (pressed) .88f else 1f, dt, 14f)
    }
}

@Composable
internal fun FoxOrb(
    orb: OrbState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dialSize: Dp = 300.dp,
    orbSize: Dp = 250.dp,
) {
    val rig = remember { FoxRig() }
    var tick by remember { mutableLongStateOf(0L) }          // forces a redraw every frame
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val orbNow by rememberUpdatedState(orb)
    val pressedNow by rememberUpdatedState(pressed)

    val pal = orbPalette(orb)
    val c by animateColorAsState(pal.c, tween(700), label = "orb-c")
    val c2 by animateColorAsState(pal.c2, tween(700), label = "orb-c2")
    val fx1 by animateColorAsState(pal.fx1, tween(700), label = "orb-fx1")
    val fx2 by animateColorAsState(pal.fx2, tween(700), label = "orb-fx2")
    val ic by animateColorAsState(pal.ic, tween(700), label = "orb-ic")

    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 1f / 30f)
                last = now
                rig.update(dt, orbNow, pressedNow)
                tick++
            }
        }
    }

    Box(modifier.size(dialSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val frame = tick
            drawFoxOrb(rig, size.minDimension.coerceAtMost(orbSize.toPx()), c, c2, fx1, fx2, ic)
        }
        // touch target + "the fox looks at your finger"
        Box(
            modifier = Modifier
                .size(orbSize * 0.88f)
                .clip(CircleShape)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull()
                            if (ch != null && ch.pressed) {
                                rig.tlx = ((ch.position.x - size.width / 2f) / (size.width * .55f)).coerceIn(-1f, 1f)
                                rig.tly = ((ch.position.y - size.height / 2f) / (size.height * .55f)).coerceIn(-1f, 1f)
                            } else {
                                rig.tlx = 0f; rig.tly = 0f
                            }
                        }
                    }
                }
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        )
    }
}

/* ------------------------------------------------------------------------------------------
   Drawing. Everything inside the orb is drawn in the 200 x 200 "orb space" of the web SVG
   (via withTransform), so every number below matches the SVG / CSS of the Windows UI.
   ------------------------------------------------------------------------------------------ */
private fun DrawScope.drawFoxOrb(
    rig: FoxRig, orbPx: Float,
    c: Color, c2: Color, fx1: Color, fx2: Color, ic: Color,
) {
    val ctr = center
    val u = orbPx / 200f
    val ox = ctr.x - orbPx / 2f
    val oy = ctr.y - orbPx / 2f
    val s = rig.state

    // ---- aura (CSS .aura) ----
    val breathe = 1f + .08f * (.5f - .5f * cos(rig.clock / (when (s) {
        OrbState.CONNECTING -> 1.1f; OrbState.PROCESSING -> 1.4f; OrbState.ON -> 4f; OrbState.OFF -> 8f; else -> 5f
    }) * TAU))
    val auraR = orbPx * .62f * breathe
    drawCircle(
        brush = Brush.radialGradient(
            0f to c.copy(alpha = .36f * rig.auraA),
            .56f to c.copy(alpha = .10f * rig.auraA),
            .74f to Color.Transparent,
            center = ctr, radius = auraR,
        ),
        radius = auraR, center = ctr,
    )

    // ---- just connected: three rings sent out ONCE (same as the Windows UI) ----
    if (s == OrbState.ON) {
        for (i in 0..2) {
            val p = (rig.onElapsed - i * .3f) / 1.8f
            if (p in 0f..1f) {
                val e = 1f - (1f - p) * (1f - p)
                drawCircle(
                    color = c.copy(alpha = .55f * (1f - e)),
                    radius = orbPx / 2f * (1f + .22f * e),
                    center = ctr,
                    style = Stroke(1.5f * u),
                )
            }
        }
    }

    withTransform({ translate(ox, oy); scale(u, u, Offset.Zero) }) {
        drawOrbUnits(rig, c, c2, fx1, fx2, ic)
    }
}

private fun DrawScope.drawOrbUnits(
    rig: FoxRig, c: Color, c2: Color, fx1: Color, fx2: Color, ic: Color,
) {
    val s = rig.state
    val o = Offset(100f, 100f)

    // burst ring right after connecting
    val bp = rig.burstP
    if (s == OrbState.ON && bp < 1f) {
        drawCircle(c.copy(alpha = .9f * (1f - bp)), radius = 88f * (1f + .55f * bp), center = o, style = Stroke(2f))
    }

    // glass body
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color(0xFF0A0F30).copy(alpha = .93f),
            .72f to Color(0xFF060A22).copy(alpha = .95f),
            1f to c.copy(alpha = .5f),
            center = Offset(100f, 85.9f), radius = 109.1f,
        ),
        radius = 88f, center = o,
    )
    drawCircle(c.copy(alpha = .45f), radius = 88f, center = o, style = Stroke(1.6f))
    drawCircle(c.copy(alpha = if (s == OrbState.ERROR) .8f else .55f), radius = 96f, center = o, style = Stroke(1.4f))

    // processing: four swirling ribbons
    if (rig.swirlA > .01f) {
        val ribbon = Brush.linearGradient(listOf(c2, ic), start = Offset(0f, 0f), end = Offset(200f, 200f))
        val tilts = floatArrayOf(-28f, 38f, 82f, -62f)
        val spins = floatArrayOf(rig.swirlS1, rig.swirlS2, rig.swirlS3, rig.swirlS4)
        val rxs = floatArrayOf(94f, 92f, 97f, 95f)
        val rys = floatArrayOf(86f, 88f, 85f, 90f)
        val sweeps = floatArrayOf(96f, 70f, 57f, 45f)
        val widths = floatArrayOf(2.6f, 1.6f, 1.2f, 3f)
        val alphas = floatArrayOf(1f, 1f, 1f, .6f)
        for (i in 0..3) {
            rotate(tilts[i], o) {
                rotate(spins[i], o) {
                    drawArc(
                        brush = ribbon,
                        startAngle = 0f, sweepAngle = sweeps[i], useCenter = false,
                        topLeft = Offset(100f - rxs[i], 100f - rys[i]), size = Size(rxs[i] * 2f, rys[i] * 2f),
                        alpha = rig.swirlA * alphas[i],
                        style = Stroke(widths[i], cap = StrokeCap.Round),
                    )
                }
            }
        }
    }

    // arcs (CSS .ob-arcs): main thick arc + faint tail that follows it
    val arcTop = Offset(100f - 80.5f, 100f - 80.5f)
    val arcSize = Size(161f, 161f)
    val pulse = if (s == OrbState.ON) .5f - .5f * cos(rig.clock / 3.2f * TAU) else 0f
    val w1 = rig.arcW + 2f * pulse
    val a1 = rig.arcA - .2f * pulse
    val start = -90f + rig.arcRot
    if (rig.arcA2 > .01f) {
        drawArc(
            color = c.copy(alpha = rig.arcA2), startAngle = start + rig.arcMain, sweepAngle = max(.1f, rig.arcSecond),
            useCenter = false, topLeft = arcTop, size = arcSize, style = Stroke(rig.arcW2, cap = StrokeCap.Round),
        )
    }
    if (rig.arcA > .01f) {
        val sweep = max(.1f, rig.arcMain)
        // cheap glow: wide, faint strokes underneath
        drawArc(c.copy(alpha = .07f * rig.arcA), start, sweep, false, arcTop, arcSize, style = Stroke(w1 + 5f, cap = StrokeCap.Round))
        drawArc(c.copy(alpha = .14f * rig.arcA), start, sweep, false, arcTop, arcSize, style = Stroke(w1 + 2.2f, cap = StrokeCap.Round))
        drawArc(ic.copy(alpha = a1), start, sweep, false, arcTop, arcSize, style = Stroke(w1, cap = StrokeCap.Round))
    }

    // ---- the fox ----
    val fill = Brush.linearGradient(
        0f to fx1, .5f to lerp(fx1, fx2, .55f), 1f to fx2,
        start = Offset(32.8f, 103.2f), end = Offset(151.8f, 160.2f),
    )
    val rimColor = lerp(fx2, Color.White, .55f).copy(alpha = .75f)
    val needLayer = rig.foxA < .995f
    val layer = if (needLayer) Paint().also { it.alpha = rig.foxA } else null

    clipPath(rig.clipFox) {
        withTransform({
            translate(rig.wx, rig.wy)
            rotate(rig.wr, Offset(WHOLE_X, WHOLE_Y))
            scale(rig.ws, rig.ws, Offset(WHOLE_X, WHOLE_Y))
        }) {
            if (layer != null) drawContext.canvas.saveLayer(Rect(-20f, -20f, 220f, 220f), layer)

            fun solid(p: Path) {
                drawPath(p, fx2.copy(alpha = .07f), style = Stroke(4.5f, join = StrokeJoin.Round))   // soft glow
                drawPath(p, fx2.copy(alpha = .12f), style = Stroke(2.4f, join = StrokeJoin.Round))
                drawPath(p, fill)
            }
            solid(rig.pBody)
            drawPath(rig.pBodyStreak, fx1.copy(alpha = .2f), style = Stroke(.9f, cap = StrokeCap.Round))
            drawPath(rig.pBodyRim, rimColor, style = Stroke(1.1f, cap = StrokeCap.Round))
            solid(rig.pTail)
            drawPath(rig.pBlade, Color.White.copy(alpha = .2f))
            drawPath(rig.pStreak1, Color.White.copy(alpha = .3f), style = Stroke(.9f, cap = StrokeCap.Round))
            drawPath(rig.pStreak2, Color.White.copy(alpha = .3f), style = Stroke(.9f, cap = StrokeCap.Round))
            drawPath(rig.pTailRim, rimColor, style = Stroke(1.1f, cap = StrokeCap.Round))

            // glint running along the top edge of the tail
            if (rig.glintA > .01f) {
                val len = rig.glintLen
                val on = len * .10f
                val cycle = len * 1.30f
                val t = if (s == OrbState.CONNECTING || s == OrbState.PROCESSING) rig.glintT else smooth01(rig.glintT)
                val pos = len * (-.10f + 1.10f * t)
                val phase = ((-pos) % cycle + cycle) % cycle
                drawPath(
                    rig.pGlint, Color.White.copy(alpha = rig.glintA),
                    style = Stroke(1.8f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(on, len * 1.2f), phase)),
                )
            }

            solid(rig.pEarL)
            solid(rig.pEarR)
            solid(rig.pHead)
            drawPath(rig.pHeadHl, Color.White.copy(alpha = .16f))

            // eye (a dark slit that blinks) and nose
            val bt = (rig.clock % 5.5f) / 5.5f
            val blink = when {
                bt < .92f -> 1f
                bt < .95f -> 1f + (.1f - 1f) * ((bt - .92f) / .03f)
                bt < 1f -> .1f + (1f - .1f) * ((bt - .95f) / .05f)
                else -> 1f
            }
            val sy = rig.eyeBase * (if (s == OrbState.OFF || s == OrbState.ERROR || s == OrbState.CONNECTING || s == OrbState.PROCESSING) 1f else blink)
            val ecx = (rig.eyeX1 + rig.eyeX2) / 2f
            val ecy = (rig.eyeY1 + rig.eyeY2) / 2f
            drawLine(
                color = Color(0xFF050816),
                start = Offset(rig.eyeX1, ecy + (rig.eyeY1 - ecy) * sy),
                end = Offset(rig.eyeX2, ecy + (rig.eyeY2 - ecy) * sy),
                strokeWidth = 1.5f * sy.coerceIn(.35f, 1.2f),
                cap = StrokeCap.Round,
            )
            drawCircle(Color(0xFF050816), radius = 1.1f, center = Offset(rig.noseX, rig.noseY))

            if (layer != null) drawContext.canvas.restore()
        }
    }

    // sparks rising inside the glass + sheen sweeping over it (connected)
    if (rig.sparkA > .01f || rig.sheenA > .01f) {
        clipPath(rig.clipCircle) {
            if (rig.sparkA > .01f) {
                val period = if (s == OrbState.CONNECTING) 1.6f else 4.2f
                val xs = floatArrayOf(62f, 140f, 118f, 88f)
                val ys = floatArrayOf(96f, 120f, 140f, 150f)
                val rs = floatArrayOf(1.3f, 1.1f, 1.4f, 1f)
                for (i in 0..3) {
                    val p = (((rig.clock - i * .9f) / period) % 1f + 1f) % 1f
                    val a = if (p < .25f) .9f * (p / .25f) else .9f * (1f - (p - .25f) / .75f)
                    drawCircle(ic.copy(alpha = a * rig.sparkA), radius = rs[i], center = Offset(xs[i], ys[i] + 24f - 80f * p))
                }
            }
            if (rig.sheenA > .01f) {
                val t = ((rig.clock % 6f) / 6f / .35f).coerceIn(0f, 1f)
                val x = -110f + 420f * smooth01(t)
                val band = Path().apply {
                    moveTo(x, 0f); lineTo(x + 60f, 0f); lineTo(x + 60f - 72.8f, 200f); lineTo(x - 72.8f, 200f); close()
                }
                drawPath(
                    band,
                    Brush.horizontalGradient(
                        0f to Color.Transparent, .5f to Color.White.copy(alpha = .2f * rig.sheenA), 1f to Color.Transparent,
                        startX = x - 72.8f, endX = x + 60f,
                    ),
                )
            }
        }
    }

    // ---- power icon (stem + 283 degree arc), centre (101.6, 108) radius 25 ----
    val iconAlpha = (if (s == OrbState.OFF) .75f else 1f) * (1f - .12f * rig.iconPulse)
    val iconScale = (1f + .07f * rig.iconPulse) * rig.pressIcon
    withTransform({ scale(iconScale, iconScale, Offset(101.5f, 95f)) }) {
        fun glyph(color: Color, w: Float) {
            drawLine(color, Offset(101.5f, 67f), Offset(101.5f, 98f), strokeWidth = w, cap = StrokeCap.Round)
            drawArc(
                color = color, startAngle = 308.7f, sweepAngle = 282.6f, useCenter = false,
                topLeft = Offset(101.6f - 25f, 108f - 25f), size = Size(50f, 50f),
                style = Stroke(w, cap = StrokeCap.Round),
            )
        }
        glyph(c.copy(alpha = .07f * iconAlpha), 8f + 6f)
        glyph(c.copy(alpha = .14f * iconAlpha), 8f + 2.5f)
        glyph(ic.copy(alpha = iconAlpha), 8f)
    }

    // ---- glass reflections ----
    drawArc(Color.White.copy(alpha = .34f), 192.9f, 66.2f, false, Offset(28f, 28f), Size(144f, 144f), style = Stroke(3f, cap = StrokeCap.Round))
    rotate(-40f, Offset(66f, 44f)) {
        drawOval(Color.White.copy(alpha = .16f), topLeft = Offset(53f, 39.5f), size = Size(26f, 9f))
    }
    drawCircle(
        brush = Brush.linearGradient(
            0f to Color.White.copy(alpha = .8f), .42f to Color.White.copy(alpha = 0f), 1f to c.copy(alpha = .75f),
            start = Offset(12f, 12f), end = Offset(188f, 188f),
        ),
        radius = 88f, center = o, style = Stroke(1.6f),
    )

    // ---- cracks in the glass (error): one connected network that starts on the rim ----
    if (rig.crackA > .01f) {
        clipPath(rig.clipCircle) {
            val delays = floatArrayOf(0f, .22f, .32f, .42f, .10f, .15f, .12f, .50f)
            val pm = PathMeasure()
            for (i in rig.crackPaths.indices) {
                val dur = if (i == 0) .9f else .5f
                val p = ((rig.errorT - delays[i.coerceAtMost(7)]) / dur).coerceIn(0f, 1f)
                if (p <= 0f) continue
                pm.setPath(rig.crackPaths[i], false)
                rig.crackDst.rewind()
                pm.getSegment(0f, rig.crackLens[i] * p, rig.crackDst, true)
                drawPath(rig.crackDst, ic.copy(alpha = .25f * rig.crackA), style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(rig.crackDst, ic.copy(alpha = rig.crackA), style = Stroke(1.25f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            // flash where the glass was hit
            val ip = (rig.errorT / .8f).coerceIn(0f, 1f)
            if (ip < 1f) drawCircle(ic.copy(alpha = .9f * (1f - ip) * rig.crackA), radius = 7f * (.3f + 2.3f * ip), center = Offset(150f, 26f))
        }
    }
}
