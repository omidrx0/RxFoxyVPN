package com.vauth.rxfoxyvpn.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caverock.androidsvg.SVG
import com.vauth.rxfoxyvpn.data.CaptchaNoticeKind
import com.vauth.rxfoxyvpn.data.CaptchaPromptUiState
import com.vauth.rxfoxyvpn.data.LanguageManager
import com.vauth.rxfoxyvpn.ui.icons.FoxyIcons

/**
 * Shared Fastly CAPTCHA challenge UI. Used by the direct sign-in dialog so every
 * interactive challenge (art-image variants shipped by the FxA flow) is surfaced
 * and solvable in place — never only logged.
 *
 * Visual containment is deliberately light: dark glyphs on transparent PNGs stay legible.
 */
@Composable
internal fun CaptchaChallengeSection(
    captchaState: CaptchaPromptUiState,
    input: String,
    onInputChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    accent: Color = Color(0xFF8B7CFF),
    badColor: Color = Color(0xFFFF5C7A),
    textColor: Color = Color(0xFFEEF0FF),
    muteColor: Color = Color(0xFF9AA1D6),
) {
    val decodedBitmap: ImageBitmap? = remember(captchaState.promptId, captchaState.imageBytes) {
        decodeCaptchaImageBitmap(captchaState.imageBytes, captchaState.mimeType)
    }
    val isRtl = LocalConfiguration.current.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL

    val noticeText: String? = captchaState.validationError ?: when (captchaState.notice) {
        CaptchaNoticeKind.WRONG_ANSWER ->
            LanguageManager.t("captcha_wrong", "The characters you entered are incorrect. Try the new image.")
        CaptchaNoticeKind.EXPIRED ->
            LanguageManager.t("captcha_expired", "The previous security code expired. A new image was loaded.")
        CaptchaNoticeKind.DECODE_FAILED ->
            LanguageManager.t("captcha_decode_err", "The security image cannot be displayed. Fetch a new one.")
        CaptchaNoticeKind.REFRESH_FAILED ->
            LanguageManager.t("captcha_refresh_err", "Could not load a new image. Check the internet and try again.")
        null -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            LanguageManager.t("captcha_title", "Security check"),
            color = textColor,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            fontSize = 14.sp,
        )

        // Only the CAPTCHA image — no box / panel behind it
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp, max = 160.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (decodedBitmap != null) {
                Image(
                    bitmap = decodedBitmap,
                    contentDescription = LanguageManager.t("captcha_title", "Security check"),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp, max = 156.dp),
                )
            } else if (!captchaState.isRefreshing) {
                Text(
                    text = LanguageManager.t(
                        "captcha_decode_err",
                        "The security image cannot be displayed. Fetch a new one.",
                    ),
                    color = badColor,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(14.dp),
                )
            }

            if (captchaState.isRefreshing) {
                CircularProgressIndicator(color = accent, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isRtl) Arrangement.Start else Arrangement.End,
        ) {
            Surface(
                onClick = onRefresh,
                enabled = !captchaState.isRefreshing && !captchaState.isSubmitting,
                shape = RoundedCornerShape(11.dp),
                color = accent.copy(alpha = 0.16f),
                border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = LanguageManager.t("captcha_refresh", "New image"),
                        color = textColor,
                        fontSize = 12.5.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            enabled = !captchaState.isSubmitting,
            placeholder = {
                Text(
                    text = LanguageManager.t("captcha_placeholder", "Characters in the image…"),
                    color = muteColor.copy(alpha = 0.5f),
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = FoxyIcons.Security,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (input.trim().isNotEmpty() && !captchaState.isRefreshing && !captchaState.isSubmitting) {
                        onSubmit()
                    }
                },
            ),
            textStyle = TextStyle(fontSize = 16.sp, textDirection = TextDirection.Ltr),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = accent.copy(alpha = 0.3f),
                focusedTextColor = textColor,
                unfocusedTextColor = textColor,
                cursorColor = accent,
                focusedLeadingIconColor = accent,
                unfocusedLeadingIconColor = muteColor,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        if (!noticeText.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(badColor.copy(alpha = 0.12f))
                    .border(1.dp, badColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(text = noticeText, color = badColor, fontSize = 13.sp)
            }
        }

        Button(
            onClick = onSubmit,
            enabled = input.trim().isNotEmpty() && decodedBitmap != null && !captchaState.isRefreshing && !captchaState.isSubmitting,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent),
        ) {
            if (captchaState.isSubmitting) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Text(
                    LanguageManager.t("captcha_submit", "Verify and continue"),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
            }
        }

        TextButton(
            onClick = onCancel,
            enabled = !captchaState.isSubmitting,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(LanguageManager.t("back", "Back"), color = muteColor, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }
    }
}

private fun decodeCaptchaImageBitmap(bytes: ByteArray, mimeType: String): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return try {
        if (mimeType.contains("svg", ignoreCase = true)) {
            val svg = SVG.getFromInputStream(bytes.inputStream())
            val width = svg.documentWidth.takeIf { it > 0f }?.toInt()?.coerceIn(120, 1200) ?: 400
            val height = svg.documentHeight.takeIf { it > 0f }?.toInt()?.coerceIn(60, 600) ?: 140
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = AndroidCanvas(bmp)
            svg.renderToCanvas(canvas)
            bmp.asImageBitmap()
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    } catch (_: Throwable) {
        null
    }
}
