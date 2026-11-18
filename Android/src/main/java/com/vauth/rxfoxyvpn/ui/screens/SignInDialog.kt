package com.vauth.rxfoxyvpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vauth.rxfoxyvpn.data.CaptchaCancelledException
import com.vauth.rxfoxyvpn.data.MultiAccountStore
import com.vauth.rxfoxyvpn.data.error.ErrorClassifier
import com.vauth.rxfoxyvpn.data.error.ErrorSection
import com.vauth.rxfoxyvpn.data.FxaAuthRepository
import com.vauth.rxfoxyvpn.data.LanguageManager
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import com.vauth.rxfoxyvpn.ui.icons.FoxyIcons

// Aligned with Home / Settings
private val DiagBg = Color(0xFF0E1131)
private val DiagPanel = Color(0xFF14173C).copy(alpha = 0.95f)
private val DiagLine = Color(0xFF969EFF).copy(alpha = 0.20f)
private val DiagText = Color(0xFFEEF0FF)
private val DiagMute = Color(0xFF9AA1D6)
private val DiagBlue = Color(0xFF4D7CFF)
private val DiagPurple = Color(0xFF8B5CF6)
private val DiagPink = Color(0xFFFF5FB0)
private val DiagBad = Color(0xFFFF5C7A)
private val DiagGood = Color(0xFF34E0A1)

/**
 * Arman-style popup for direct Firefox sign-in (email + password + 2FA / CAPTCHA).
 * Does not replace the browser sign-in flow.
 */
@Composable
fun SignInDialog(
    authRepository: FxaAuthRepository,
    multiAccountStore: MultiAccountStore,
    initialEmail: String = "",
    onDismiss: () -> Unit,
    onSignedIn: (email: String, password: String?) -> Unit,
) {
    val context = LocalContext.current
    var email by remember { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var awaitingTwoFactor by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val captchaState by authRepository.captchaPrompt.collectAsState()
    var captchaInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(captchaState?.promptId) { captchaInput = "" }
    DisposableEffect(authRepository) {
        onDispose { authRepository.cancelCaptcha() }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = DiagPurple,
        unfocusedBorderColor = DiagLine,
        focusedTextColor = DiagText,
        unfocusedTextColor = DiagText,
        focusedLabelColor = Color(0xFFC4B5FD),
        unfocusedLabelColor = DiagMute,
        focusedContainerColor = Color(0xFF080A20).copy(alpha = 0.85f),
        unfocusedContainerColor = Color(0xFF080A20).copy(alpha = 0.62f),
        cursorColor = DiagPink,
    )

    fun doLogin() {
        errorMessage = null
        isLoading = true
        scope.launch {
            val result = authRepository.startLogin(email.trim(), password)
            isLoading = false
            result.onSuccess { needsVerification ->
                if (needsVerification) awaitingTwoFactor = true
                else onSignedIn(email.trim(), password)
            }.onFailure { err ->
                if (err is CaptchaCancelledException) {
                    errorMessage = null
                } else {
                    val classified = ErrorClassifier.classify(err, ErrorSection.LOGIN, "direct_login")
                    com.vauth.rxfoxyvpn.data.error.FoxyLogger.log(classified)
                    errorMessage = classified.userMessage()
                }
            }
        }
    }

    fun doVerify() {
        errorMessage = null
        isLoading = true
        scope.launch {
            val result = authRepository.submitTwoFactorCode(code.trim())
            isLoading = false
            result.onSuccess { onSignedIn(email.trim(), password) }
                .onFailure {
                    val classified = ErrorClassifier.classify(it, ErrorSection.LOGIN, "two_factor_verify")
                    com.vauth.rxfoxyvpn.data.error.FoxyLogger.log(classified)
                    errorMessage = classified.userMessage()
                }
        }
    }

    // While a CAPTCHA is shown the login call is still "loading", but the user must be able to leave.
    val canClose = !isLoading || captchaState != null
    Dialog(
        onDismissRequest = { if (canClose) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = canClose),
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF0E1131),
            border = BorderStroke(1.dp, DiagLine),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(8.dp),
        ) {
            Column(
                Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Title row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        LanguageManager.t("add_account", "Add account"),
                        color = DiagText,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            authRepository.cancelCaptcha()
                            onDismiss()
                        },
                        enabled = canClose,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Default.Close, null, tint = DiagMute, modifier = Modifier.size(20.dp))
                    }
                }
                Text(
                    LanguageManager.t("login_lead", "Sign in with your Firefox account email and password."),
                    color = DiagMute,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )

                errorMessage?.let { msg ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(DiagBad.copy(alpha = 0.12f))
                            .padding(12.dp),
                    ) {
                        Text(msg, color = DiagBad, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }

                val activeCaptcha = captchaState
                if (activeCaptcha != null) {
                    CaptchaChallengeSection(
                        captchaState = activeCaptcha,
                        input = captchaInput,
                        onInputChange = { captchaInput = it },
                        onRefresh = {
                            captchaInput = ""
                            authRepository.refreshCaptcha()
                        },
                        onSubmit = { authRepository.submitCaptchaAnswer(captchaInput) },
                        onCancel = {
                            captchaInput = ""
                            authRepository.cancelCaptcha()
                        },
                        accent = DiagPurple,
                        badColor = DiagBad,
                        textColor = DiagText,
                        muteColor = DiagMute,
                    )
                } else if (!awaitingTwoFactor) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(LanguageManager.t("email", "Email")) },
                        singleLine = true,
                        shape = RoundedCornerShape(13.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(LanguageManager.t("password", "Password")) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) FoxyIcons.VisibilityOff else FoxyIcons.Visibility,
                                    null,
                                    tint = DiagMute,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors,
                    )
                    val loginEnabled = !isLoading && email.isNotBlank() && password.isNotBlank()
                    val btnShape = RoundedCornerShape(14.dp)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .graphicsLayer { alpha = if (loginEnabled || isLoading) 1f else 0.55f }
                            .shadow(12.dp, btnShape, ambientColor = DiagPurple, spotColor = DiagPurple)
                            .clip(btnShape)
                            .background(Brush.linearGradient(listOf(DiagBlue, DiagPurple, DiagPink)))
                            .clickable(enabled = loginEnabled, onClick = { doLogin() }),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        } else {
                            Text(LanguageManager.t("btn_login", "Sign in"), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text(LanguageManager.t("totp_code", "Verification code")) },
                        singleLine = true,
                        shape = RoundedCornerShape(13.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors,
                    )
                    Button(
                        onClick = { doVerify() },
                        enabled = !isLoading && code.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DiagPurple,
                            disabledContainerColor = DiagPurple.copy(alpha = 0.35f),
                        ),
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        } else {
                            Text(
                                LanguageManager.t("btn_verify", "Verify"),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color.White,
                            )
                        }
                    }
                    TextButton(
                        onClick = { awaitingTwoFactor = false },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(LanguageManager.t("back", "Back"), color = DiagMute)
                    }
                }
            }
        }
    }
}

@Composable
fun SignInMethodDialog(
    current: com.vauth.rxfoxyvpn.data.SettingsStore.SignInMethod,
    onDismiss: () -> Unit,
    onPick: (com.vauth.rxfoxyvpn.data.SettingsStore.SignInMethod) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = DiagPanel,
            border = BorderStroke(1.dp, DiagLine),
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(DiagPurple.copy(alpha = 0.16f))
                                .border(1.dp, DiagPurple.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(FoxyIcons.Login, null, tint = DiagPurple, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            LanguageManager.t("login_method", "Sign-in method"),
                            color = DiagText,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 17.sp,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    MethodRow(
                        title = LanguageManager.t("login_method_direct", "Direct sign-in"),
                        subtitle = LanguageManager.t("login_method_direct_desc", "Email and password in a popup"),
                        selected = current == com.vauth.rxfoxyvpn.data.SettingsStore.SignInMethod.DIRECT,
                        onClick = { onPick(com.vauth.rxfoxyvpn.data.SettingsStore.SignInMethod.DIRECT) },
                    )
                    MethodRow(
                        title = LanguageManager.t("login_method_browser", "Browser sign-in"),
                        subtitle = LanguageManager.t("login_method_browser_desc", "Mozilla page inside the app"),
                        selected = current == com.vauth.rxfoxyvpn.data.SettingsStore.SignInMethod.BROWSER,
                        onClick = { onPick(com.vauth.rxfoxyvpn.data.SettingsStore.SignInMethod.BROWSER) },
                    )
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                        Text(LanguageManager.t("cancel", "Cancel"), color = DiagMute)
                    }
                }
            }
        }
    }
}

@Composable
private fun MethodRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) DiagPurple.copy(alpha = 0.16f) else Color(0xFF14173C).copy(alpha = 0.5f),
        border = BorderStroke(1.dp, if (selected) DiagPurple.copy(alpha = 0.60f) else DiagLine),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = DiagText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(subtitle, color = DiagMute, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (selected) DiagPurple else DiagMute.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(DiagPurple))
                }
            }
        }
    }
}