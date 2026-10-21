package com.vauth.rxfoxyvpn.ui.screens

import com.vauth.rxfoxyvpn.BuildConfig
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vauth.rxfoxyvpn.R
import com.vauth.rxfoxyvpn.data.FxaAuthRepository
import com.vauth.rxfoxyvpn.data.LanguageManager
import com.vauth.rxfoxyvpn.data.SavedAccountsStore
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/* Design tokens from the Windows web UI (style.css :root) */
private object RxLogin {
    val Bg = Color(0xFF060714)
    val PanelS = Color(0xFF0E1131)
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
}

@Composable
fun LoginScreen(
    authRepository: FxaAuthRepository,
    onSignedIn: () -> Unit,
) {
    val context = LocalContext.current
    val savedAccountsStore = remember { SavedAccountsStore(context) }
    var savedAccounts by remember { mutableStateOf(savedAccountsStore.getSavedAccounts()) }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberAccount by remember { mutableStateOf(true) }
    var code by remember { mutableStateOf("") }
    var awaitingTwoFactor by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showSignUpDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val currentLang by LanguageManager.currentLang.collectAsState()

    val langNames = mapOf(
        "en" to "English",
        "fa" to "فارسی",
        "ar" to "العربية",
        "ko" to "한국어",
        "ru" to "Русский",
        "zh" to "简体中文"
    )
    val currentLangName = langNames[currentLang] ?: "فارسی"

    fun performLogin(targetEmail: String, targetPass: String) {
        email = targetEmail
        password = targetPass
        errorMessage = null
        isLoading = true
        scope.launch {
            val result = authRepository.startLogin(targetEmail, targetPass)
            isLoading = false
            result.onSuccess { needsVerification ->
                if (rememberAccount) {
                    savedAccountsStore.saveAccount(targetEmail, targetPass)
                    savedAccounts = savedAccountsStore.getSavedAccounts()
                }
                if (needsVerification) awaitingTwoFactor = true else onSignedIn()
            }.onFailure { errorMessage = it.message ?: LanguageManager.t("err_signin_failed", "Sign-in failed") }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LoginBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---------- language corner ----------
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    onClick = { showLanguageDialog = true },
                    shape = RoundedCornerShape(12.dp),
                    color = RxLogin.Panel,
                    border = BorderStroke(1.dp, RxLogin.Line),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("🌐", fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(currentLangName, color = RxLogin.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // ---------- brand ----------
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.rx_logo),
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    LoginBrand(fontSize = 21.sp)
                    Spacer(Modifier.width(8.dp))
                    LoginVersionBadge()
                }
            }

            // ---------- hero visual ----------
            HeroVis(Modifier.padding(vertical = 6.dp))

            // ---------- hero copy ----------
            Text(
                text = LanguageManager.t("login_h1", "آزاد وب‌گردی کن؛ امن و بی‌دردسر."),
                style = TextStyle(
                    brush = Brush.linearGradient(listOf(Color.White, Color(0xFFD8D2FF), Color(0xFFFFB4DC))),
                    fontSize = 26.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.widthIn(max = 420.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = LanguageManager.t("login_p", "با حساب فایرفاکس خودت وصل شو، کشور را انتخاب کن، پینگ را ببین و با یک دکمه محافظت‌شده باش."),
                color = RxLogin.Mute,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )

            Spacer(Modifier.height(22.dp))

            // ---------- card with rotating gradient border ----------
            GradientBorderCard(Modifier.fillMaxWidth().widthIn(max = 420.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (awaitingTwoFactor) LanguageManager.t("code_h", "کد تأیید") else LanguageManager.t("login_h", "ورود"),
                            color = RxLogin.Text, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            text = if (awaitingTwoFactor) {
                                LanguageManager.t("code_lead", "Firefox یک کد به ایمیلت فرستاد. آن را اینجا وارد کن.")
                            } else {
                                LanguageManager.t("login_lead", "با ایمیل و رمز حساب Firefox وارد شو.")
                            },
                            color = RxLogin.Mute, fontSize = 14.sp,
                        )
                    }

                    if (!awaitingTwoFactor) {
                        // ----- saved accounts -----
                        if (savedAccounts.isNotEmpty()) {
                            var accountsExpanded by remember { mutableStateOf(false) }
                            val teal = Color(0xFF5EE6C4)
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(0xFF25CDA4).copy(alpha = 0.10f), RxLogin.Blue.copy(alpha = 0.08f))
                                        )
                                    )
                                    .border(1.dp, teal.copy(alpha = 0.20f), RoundedCornerShape(14.dp))
                                    .clickable { accountsExpanded = !accountsExpanded }
                                    .padding(13.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                Brush.linearGradient(
                                                    listOf(Color(0xFF25CDA4).copy(alpha = 0.22f), RxLogin.Blue.copy(alpha = 0.17f))
                                                )
                                            )
                                            .border(1.dp, teal.copy(alpha = 0.30f), RoundedCornerShape(12.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF9AF3DB), modifier = Modifier.size(17.dp))
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = if (accountsExpanded) LanguageManager.t("saved_accounts_label", "حساب‌های ذخیره‌شده") else (savedAccounts.firstOrNull()?.email ?: ""),
                                            color = RxLogin.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1,
                                        )
                                        Text(
                                            text = if (accountsExpanded) LanguageManager.t("saved_accounts_hint", "برای بستن لیست کلیک کنید") else LanguageManager.tn("saved_accounts_collapsed", "Saved accounts: {n} (tap to choose)", savedAccounts.size),
                                            color = RxLogin.Mute, fontSize = 11.sp,
                                        )
                                    }
                                    Icon(
                                        imageVector = if (accountsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Expand/Collapse",
                                        tint = RxLogin.Mute,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }

                                if (accountsExpanded) {
                                    Spacer(Modifier.height(10.dp))
                                    HorizontalDivider(color = RxLogin.Line2, thickness = 1.dp)
                                    Spacer(Modifier.height(6.dp))
                                    savedAccounts.forEach { acc ->
                                        Row(
                                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = acc.email,
                                                color = RxLogin.Text,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable { performLogin(acc.email, acc.pass) },
                                            )
                                            Spacer(Modifier.width(7.dp))
                                            Surface(
                                                onClick = { performLogin(acc.email, acc.pass) },
                                                shape = RoundedCornerShape(11.dp),
                                                color = Color.White.copy(alpha = 0.03f),
                                                border = BorderStroke(1.dp, RxLogin.Line2),
                                            ) {
                                                Text(
                                                    LanguageManager.t("use_saved_account", "ورود"),
                                                    color = RxLogin.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    savedAccountsStore.removeAccount(acc.email)
                                                    savedAccounts = savedAccountsStore.getSavedAccounts()
                                                },
                                                modifier = Modifier.size(36.dp),
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = RxLogin.Bad, modifier = Modifier.size(17.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        LoginField(
                            label = LanguageManager.t("email", "ایمیل"),
                            value = email,
                            onChange = { email = it },
                            placeholder = "you@example.com",
                            icon = Icons.Default.Email,
                            keyboardType = KeyboardType.Email,
                        )
                        LoginField(
                            label = LanguageManager.t("password", "رمز عبور"),
                            value = password,
                            onChange = { password = it },
                            placeholder = "",
                            icon = Icons.Default.Lock,
                            keyboardType = KeyboardType.Password,
                            isPassword = true,
                            passwordVisible = passwordVisible,
                            onTogglePassword = { passwordVisible = !passwordVisible },
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { rememberAccount = !rememberAccount },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = rememberAccount,
                                onCheckedChange = { rememberAccount = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = RxLogin.Violet,
                                    uncheckedColor = RxLogin.Mute,
                                ),
                            )
                            Text(
                                text = LanguageManager.t("remember_account", "این حساب را روی همین گوشی امن ذخیره کن"),
                                color = RxLogin.Mute,
                                fontSize = 12.sp,
                            )
                        }

                        errorMessage?.let { Text(it, color = RxLogin.Bad, fontSize = 13.5.sp) }

                        GradientButton(
                            text = LanguageManager.t("btn_login", "ورود"),
                            loading = isLoading,
                            enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                            onClick = { performLogin(email, password) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        // ----- two-factor code -----
                        LoginField(
                            label = LanguageManager.t("code_label", "کد ایمیل"),
                            value = code,
                            onChange = { code = it },
                            placeholder = "123456",
                            icon = Icons.Default.Lock,
                            keyboardType = KeyboardType.Number,
                        )

                        errorMessage?.let { Text(it, color = RxLogin.Bad, fontSize = 13.5.sp) }

                        GradientButton(
                            text = LanguageManager.t("btn_code", "تأیید و ورود"),
                            loading = isLoading,
                            enabled = !isLoading && code.isNotBlank(),
                            onClick = {
                                errorMessage = null
                                isLoading = true
                                scope.launch {
                                    val result = authRepository.submitTwoFactorCode(code)
                                    isLoading = false
                                    result.onSuccess { onSignedIn() }
                                        .onFailure { errorMessage = it.message ?: LanguageManager.t("err_verify_failed", "Verification failed") }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        TextButton(
                            onClick = {
                                awaitingTwoFactor = false
                                code = ""
                                errorMessage = null
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Text(
                                LanguageManager.t("back", "برگشت"),
                                color = Color(0xFFC4B5FD), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    TextButton(
                        onClick = { showSignUpDialog = true },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(
                            text = LanguageManager.t("no_account", "حساب ندارم؛ چطور ثبت‌نام کنم؟"),
                            color = Color(0xFFC4B5FD),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            // ---------- feature pills ----------
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FeaturePill(Icons.Default.Public, LanguageManager.t("f1", "سرور در کشورهای مختلف"))
                FeaturePill(Icons.Default.Speed, LanguageManager.t("f2", "پینگ زنده و مرتب‌سازی"))
                FeaturePill(Icons.Default.Dns, LanguageManager.t("f3", "پراکسی SOCKS5 و HTTP محلی"))
            }

            Spacer(Modifier.height(22.dp))

            // ---------- powered by ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(id = R.drawable.rx_logo), contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = LanguageManager.t("powered", "قدرت‌گرفته از Firefox").replace("<b>", "").replace("</b>", ""),
                    color = RxLogin.Mute,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showLanguageDialog) {
        LanguagePickerDialog(
            currentTag = currentLang,
            onDismiss = { showLanguageDialog = false },
            onConfirm = { newLang ->
                LanguageManager.setLanguage(context, newLang)
                showLanguageDialog = false
            }
        )
    }

    if (showSignUpDialog) {
        SignUpGuideDialog(
            onDismiss = { showSignUpDialog = false },
            onOpenFirefox = {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://accounts.firefox.com/signup"))
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        )
    }
}

/* ------------------------------------------------------------------------------------------
   Background
   ------------------------------------------------------------------------------------------ */
@Composable
private fun LoginBackground() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0D1033), RxLogin.Bg)))
    ) {
        Image(
            painter = painterResource(id = R.drawable.rx_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.5f },
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(RxLogin.Bg.copy(alpha = 0.55f), RxLogin.Bg.copy(alpha = 0.30f), RxLogin.Bg.copy(alpha = 0.65f))
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
            blob(Color(0xFFFF4FA8).copy(alpha = 0.14f), Offset(size.width * 0.45f, size.height * 0.40f), big * 0.35f)
        }
    }
}

/* ------------------------------------------------------------------------------------------
   Brand
   ------------------------------------------------------------------------------------------ */
@Composable
private fun LoginBrand(fontSize: TextUnit) {
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
private fun LoginVersionBadge() {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(RxLogin.Blue.copy(alpha = 0.24f), RxLogin.Pink.copy(alpha = 0.22f))))
            .border(1.dp, Color(0xFFAA96FF).copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(Brush.linearGradient(listOf(RxLogin.Blue, RxLogin.Violet, RxLogin.Pink)), CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text("V ${BuildConfig.VERSION_NAME}", color = Color(0xFFEFEAFF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
    }
}

/* ------------------------------------------------------------------------------------------
   Hero visual: orbit rings, floating logo, country chips orbiting (like .hero-vis)
   ------------------------------------------------------------------------------------------ */
@Composable
private fun HeroVis(modifier: Modifier = Modifier) {
    val heroSize = 200.dp
    var clock by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                clock += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }

    Box(modifier.padding(vertical = 14.dp).size(heroSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val ctr = center
            val r = size.minDimension / 2f
            val hair = 1.dp.toPx()
            rotate(clock * 360f / 60f, ctr) {
                drawCircle(
                    color = RxLogin.Line2, radius = r - hair, center = ctr,
                    style = Stroke(hair, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 7.dp.toPx()))),
                )
            }
            rotate(-clock * 360f / 38f, ctr) {
                drawCircle(color = RxLogin.Violet.copy(alpha = 0.40f), radius = r * 0.72f, center = ctr, style = Stroke(hair))
            }
            val pulse = 0.5f + 0.5f * sin(clock * 2f * PI.toFloat() / 4f)
            drawCircle(
                color = RxLogin.Pink.copy(alpha = 0.35f * (1f - 0.5f * pulse)),
                radius = r * 0.40f * (1f + 0.06f * pulse),
                center = ctr, style = Stroke(hair),
            )
        }

        // floating core logo with glow
        val coreSize = heroSize * 0.52f
        Box(
            Modifier
                .size(coreSize)
                .offset {
                    val phase = 0.5f - 0.5f * cos(clock * 2f * PI.toFloat() / 5f)
                    IntOffset(0, (-10.dp.toPx() * phase).roundToInt())
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.matchParentSize()) {
                val rr = size.minDimension * 0.55f
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(RxLogin.Violet.copy(alpha = 0.5f), RxLogin.Violet.copy(alpha = 0f)),
                        center = center, radius = rr,
                    ),
                    radius = rr, center = center,
                )
            }
            Image(
                painter = painterResource(id = R.drawable.rx_logo),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
            )
        }

        // orbiting country chips: label, colour, orbit radius, start angle, direction, seconds per turn
        val r = heroSize / 2f
        Satellite("US", RxLogin.Good, r, -90f, 1f, 20f, { clock })
        Satellite("JP", RxLogin.Pink, r, 90f, 1f, 20f, { clock })
        Satellite("DE", RxLogin.Violet, r * 0.72f, -90f, -1f, 26f, { clock })
        Satellite("NL", RxLogin.Cyan, r * 0.40f, 90f, 1f, 15f, { clock })
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Satellite(
    label: String,
    dot: Color,
    orbitRadius: Dp,
    startDeg: Float,
    direction: Float,
    period: Float,
    clock: () -> Float,
) {
    Row(
        modifier = Modifier
            .align(Alignment.Center)
            .offset {
                val a = Math.toRadians((startDeg + direction * 360f * clock() / period).toDouble())
                val rad = orbitRadius.toPx()
                IntOffset((cos(a) * rad).roundToInt(), (sin(a) * rad).roundToInt())
            }
            .clip(CircleShape)
            .background(Color(0xFF0E1131).copy(alpha = 0.88f))
            .border(1.dp, RxLogin.Line2, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, color = RxLogin.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/* ------------------------------------------------------------------------------------------
   Card, fields, buttons
   ------------------------------------------------------------------------------------------ */
@Composable
private fun GradientBorderCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val inf = rememberInfiniteTransition(label = "card-border")
    val angle by inf.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing)),
        label = "card-angle",
    )
    Box(
        modifier
            .shadow(24.dp, RoundedCornerShape(26.dp), ambientColor = RxLogin.Violet, spotColor = RxLogin.Violet)
            .clip(RoundedCornerShape(26.dp))
    ) {
        Canvas(Modifier.matchParentSize()) {
            rotate(angle, center) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(RxLogin.Blue, RxLogin.Violet, RxLogin.Pink, RxLogin.Violet, RxLogin.Blue),
                        center = center,
                    ),
                    radius = size.maxDimension,
                    center = center,
                )
            }
        }
        Box(
            Modifier
                .padding(1.5.dp)
                .clip(RoundedCornerShape(24.5.dp))
                .background(RxLogin.PanelS)
        ) { content() }
    }
}

@Composable
private fun LoginField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    keyboardType: KeyboardType,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePassword: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, color = RxLogin.Mute, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = if (placeholder.isNotEmpty()) {
                { Text(placeholder, color = RxLogin.Mute.copy(alpha = 0.5f)) }
            } else null,
            leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = if (isPassword) {
                {
                    IconButton(onClick = onTogglePassword) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = LanguageManager.t("toggle_pw", "نمایش یا پنهان‌کردن رمز"),
                            tint = RxLogin.Mute,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            } else null,
            visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = TextStyle(fontSize = 15.sp, textDirection = TextDirection.Ltr),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = RxLogin.Violet,
                unfocusedBorderColor = RxLogin.Line,
                focusedTextColor = RxLogin.Text,
                unfocusedTextColor = RxLogin.Text,
                focusedContainerColor = Color(0xFF080A20).copy(alpha = 0.85f),
                unfocusedContainerColor = Color(0xFF080A20).copy(alpha = 0.62f),
                cursorColor = RxLogin.Pink,
                focusedLeadingIconColor = RxLogin.Pink,
                unfocusedLeadingIconColor = RxLogin.Mute,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GradientButton(
    text: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 50.dp,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .height(height)
            .graphicsLayer { alpha = if (enabled || loading) 1f else 0.55f }
            .shadow(12.dp, shape, ambientColor = RxLogin.Violet, spotColor = RxLogin.Violet)
            .clip(shape)
            .background(Brush.linearGradient(listOf(RxLogin.Blue, RxLogin.Violet, RxLogin.Pink)))
            .clickable(enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FeaturePill(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(RxLogin.Panel)
            .border(1.dp, RxLogin.Line, CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = RxLogin.Pink, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, color = RxLogin.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/* ------------------------------------------------------------------------------------------
   Dialogs
   ------------------------------------------------------------------------------------------ */
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RxLogin.PanelS,
        shape = RoundedCornerShape(22.dp),
        titleContentColor = RxLogin.Text,
        textContentColor = RxLogin.Text,
        title = { Text(LanguageManager.t("language", "Language"), fontWeight = FontWeight.ExtraBold) },
        text = {
            Column {
                languages.forEach { (tag, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected == tag, onClick = { selected = tag })
                            .padding(vertical = 6.dp),
                    ) {
                        RadioButton(
                            selected = selected == tag,
                            onClick = { selected = tag },
                            colors = RadioButtonDefaults.colors(selectedColor = RxLogin.Violet, unselectedColor = RxLogin.Mute),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, color = RxLogin.Text, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(LanguageManager.t("ok", "OK"), color = Color(0xFFC4B5FD), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(LanguageManager.t("cancel", "Cancel"), color = RxLogin.Mute)
            }
        },
    )
}

@Composable
private fun SignUpGuideDialog(
    onDismiss: () -> Unit,
    onOpenFirefox: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RxLogin.PanelS,
        shape = RoundedCornerShape(22.dp),
        titleContentColor = RxLogin.Text,
        textContentColor = RxLogin.Text,
        title = { Text(LanguageManager.t("su_h", "ساخت حساب Firefox"), fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SignUpStep("1", LanguageManager.t("su1t", ""), LanguageManager.t("su1d", ""))
                SignUpStep("2", LanguageManager.t("su2t", ""), LanguageManager.t("su2d", ""))
                SignUpStep("3", LanguageManager.t("su3t", ""), LanguageManager.t("su3d", ""))
                SignUpStep("4", LanguageManager.t("su4t", ""), LanguageManager.t("su4d", ""))

                HorizontalDivider(color = RxLogin.Line, thickness = 1.dp)
                Text(LanguageManager.t("su_note", ""), fontSize = 13.sp, color = RxLogin.Mute)
            }
        },
        confirmButton = {
            GradientButton(
                text = LanguageManager.t("su_open", "باز کردن صفحه ثبت‌نام Firefox"),
                loading = false,
                enabled = true,
                onClick = onOpenFirefox,
                height = 42.dp,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(LanguageManager.t("close", "بستن"), color = RxLogin.Mute)
            }
        },
    )
}

@Composable
private fun SignUpStep(number: String, title: String, desc: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .shadow(6.dp, CircleShape, ambientColor = RxLogin.Violet, spotColor = RxLogin.Violet)
                .background(Brush.linearGradient(listOf(RxLogin.Blue, RxLogin.Violet, RxLogin.Pink)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(number, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = RxLogin.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = RxLogin.Mute, fontSize = 13.sp)
        }
    }
}