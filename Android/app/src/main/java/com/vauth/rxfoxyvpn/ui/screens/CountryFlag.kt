package com.vauth.rxfoxyvpn.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Country flag drawn from the same SVG files the Windows app uses
 * (app/src/main/assets/flags/<code>.svg) instead of the phone's emoji font.
 *
 * - Rendered once per (code, size) to a bitmap in the background and cached.
 * - Falls back to the emoji flag only if the SVG for that code does not exist.
 */
@Composable
fun CountryFlag(countryCode: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val code = normalizeFlagCode(countryCode)
    val px = with(density) { size.roundToPx() }.coerceIn(48, 320)
    val key = "$code@$px"

    var failed by remember(key) { mutableStateOf(code.isNotEmpty() && code in missingFlags) }
    val flag by produceState<ImageBitmap?>(initialValue = flagCache[key], key) {
        if (value == null && code.isNotEmpty() && code !in missingFlags) {
            val loaded = withContext(Dispatchers.IO) { renderFlag(context, code, px) }
            if (loaded != null) {
                flagCache[key] = loaded
                value = loaded
            } else {
                missingFlags.add(code)
                failed = true
            }
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF282C68).copy(alpha = 0.50f))
            .border(1.dp, Color(0xFF969EFF).copy(alpha = 0.30f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = flag
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (failed || code.isEmpty()) {
            // no SVG for this code -> emoji fallback
            Text(flagEmojiFor(code), fontSize = with(density) { (size * 0.5f).toSp() })
        }
    }
}

private val flagCache = ConcurrentHashMap<String, ImageBitmap>()
private val missingFlags: MutableSet<String> = ConcurrentHashMap.newKeySet()

private fun normalizeFlagCode(raw: String): String {
    val c = raw.trim().lowercase()
    return when (c) {
        "uk" -> "gb"
        "eu" -> "european_union"
        "un" -> "united_nations"
        "rec", "recommended", "auto" -> "xx"
        else -> c
    }
}

private fun renderFlag(context: android.content.Context, code: String, px: Int): ImageBitmap? =
    runCatching {
        val svg = SVG.getFromAsset(context.assets, "flags/$code.svg")
        svg.documentPreserveAspectRatio = com.caverock.androidsvg.PreserveAspectRatio.STRETCH
        svg.setDocumentWidth(px.toFloat())
        svg.setDocumentHeight(px.toFloat())
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(android.graphics.Canvas(bitmap))
        bitmap.asImageBitmap()
    }.getOrNull()

private fun flagEmojiFor(code: String): String {
    if (code.length != 2) return "🌐"
    val upper = code.uppercase()
    val first = Character.codePointAt(upper, 0) - 0x41 + 0x1F1E6
    val second = Character.codePointAt(upper, 1) - 0x41 + 0x1F1E6
    return String(Character.toChars(first)) + String(Character.toChars(second))
}
