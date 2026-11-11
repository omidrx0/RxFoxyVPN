package com.vauth.rxfoxyvpn.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.SslErrorHandler
import android.net.http.SslError
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.vauth.rxfoxyvpn.data.AppLogger
import com.vauth.rxfoxyvpn.data.BrowserLoginStage
import com.vauth.rxfoxyvpn.data.BrowserSignInPolicy
import com.vauth.rxfoxyvpn.data.FxaAuthRepository
import com.vauth.rxfoxyvpn.data.FxaWebChannel
import com.vauth.rxfoxyvpn.data.LanguageManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val TAG = "BrowserSignIn"

private object RxBrowser {
    val Bg = Color(0xFF060714)
    val Panel = Color(0xFF14173C)
    val Line = Color(0xFF969EFF).copy(alpha = 0.30f)
    val Text = Color(0xFFEEF0FF)
    val Mute = Color(0xFF9AA1D6)
    val Violet = Color(0xFF8B5CF6)
    val Bad = Color(0xFFFF5C7A)
    val Good = Color(0xFF34E0A1)
}

/** `scheme://host` of a page URL, or null. Used to check who is talking before the bridge is answered. */
private fun originOf(url: String?): String? =
    url?.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }

/**
 * Browser Sign-in: Mozilla's own sign-in page (accounts.firefox.com, Firefox Sync context) inside the app.
 *
 * The person types the email, password, CAPTCHA and any email / authenticator code on Mozilla's page; this app never
 * sees a password. The page reports the signed-in session over its WebChannel ([FxaWebChannel]); that session is passed
 * to [FxaAuthRepository.completeBrowserLogin], which waits for Mozilla to confirm it and then exchanges it for OAuth
 * credentials with the existing TokenStore. The page stays open for the whole time.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserSignInScreen(
    authRepository: FxaAuthRepository,
    initialEmail: String,
    onSignedIn: () -> Unit,
    onUseDirectSignIn: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var progress by remember { mutableIntStateOf(0) }
    var stage by remember { mutableStateOf<BrowserLoginStage?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var blockedNotice by remember { mutableStateOf(false) }
    var viewFailure by remember { mutableStateOf<String?>(null) }
    var followJob by remember { mutableStateOf<Job?>(null) }
    val holder = remember { arrayOfNulls<WebView>(1) }

    fun follow(token: String) {
        followJob?.cancel()
        errorMessage = null
        followJob = scope.launch {
            val result = authRepository.completeBrowserLogin(token) { stage = it }
            result
                .onSuccess {
                    stage = null
                    onSignedIn()
                }
                .onFailure {
                    stage = null
                    // Never log the message body of a failure on this path beyond its class: it may echo FxA fields.
                    AppLogger.w(TAG, "browser sign-in did not complete: ${it::class.java.simpleName}")
                    errorMessage = it.message ?: LanguageManager.t("err_signin_failed", "Sign-in failed")
                }
        }
    }

    val channel = remember {
        FxaWebChannel(
            reply = { js ->
                val view = holder[0]
                // Answer only the trusted origin, even though the listener already filters by origin.
                if (view != null && originOf(view.url) == FxaWebChannel.TRUSTED_ORIGIN) {
                    view.evaluateJavascript(js, null)
                }
            },
            onSession = { _, token -> follow(token) },
        )
    }

    val unsupportedText = LanguageManager.t("bs_unsupported", "This device's web view cannot run the secure sign-in bridge.")
    // The view, or the reason there is none. Built once; no state is written while composing.
    val created: Pair<WebView?, String?> = remember {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // Without an origin-restricted listener the bridge could not be kept away from other origins: do not
            // fall back to an unrestricted JavaScript interface.
            Pair(null, unsupportedText)
        } else {
            try {
                val view = WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(android.graphics.Color.WHITE)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = FxaWebChannel.USER_AGENT
                        allowFileAccess = false
                        allowContentAccess = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        safeBrowsingEnabled = true
                    }
                    WebViewCompat.addWebMessageListener(
                        this,
                        FxaWebChannel.BRIDGE_NAME,
                        setOf(FxaWebChannel.TRUSTED_ORIGIN),
                    ) { _, message, sourceOrigin, isMainFrame, _ ->
                        if (isMainFrame && BrowserSignInPolicy.isTrustedBridgeOrigin(sourceOrigin.toString().trimEnd('/'))) {
                            channel.handle(message.data)
                        }
                    }
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        WebViewCompat.addDocumentStartJavaScript(this, FxaWebChannel.HOOK_JS, setOf(FxaWebChannel.TRUSTED_ORIGIN))
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val allowed = BrowserSignInPolicy.isNavigationAllowed(request.url.toString())
                            if (!allowed) blockedNotice = true
                            return !allowed
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            // Backup for engines without document-start scripts; the hook installs itself once.
                            if (originOf(url) == FxaWebChannel.TRUSTED_ORIGIN) view.evaluateJavascript(FxaWebChannel.HOOK_JS, null)
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            if (originOf(url) == FxaWebChannel.TRUSTED_ORIGIN) view.evaluateJavascript(FxaWebChannel.HOOK_JS, null)
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) {
                                viewFailure = LanguageManager.t("bs_load_failed", "Mozilla's sign-in page could not be loaded.")
                            }
                        }

                        // Certificate errors are never overridden: the page simply does not load.
                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                            handler.cancel()
                            viewFailure = LanguageManager.t("bs_load_failed", "Mozilla's sign-in page could not be loaded.")
                        }

                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            viewFailure = LanguageManager.t("bs_load_failed", "Mozilla's sign-in page could not be loaded.")
                            return true
                        }
                    }
                    holder[0] = this
                    loadUrl(FxaWebChannel.loginUrl(initialEmail))
                }
                Pair(view, null)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "WebView could not be created: ${t::class.java.simpleName}")
                Pair(null, unsupportedText)
            }
        }
    }
    val webView: WebView? = created.first
    val initFailure: String? = created.second

    DisposableEffect(webView) {
        onDispose {
            followJob?.cancel()
            holder[0] = null
            webView?.let {
                runCatching {
                    it.stopLoading()
                    it.destroy()
                }
                // Nothing of the Mozilla web session is left behind in the app's WebView.
                runCatching {
                    CookieManager.getInstance().removeAllCookies(null)
                    WebStorage.getInstance().deleteAllData()
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RxBrowser.Bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = LanguageManager.t("back", "Back"), tint = RxBrowser.Text)
            }
            Text(
                LanguageManager.t("bs_title", "Mozilla sign-in"),
                color = RxBrowser.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
        }

        val statusText = when (stage) {
            BrowserLoginStage.CHECKING -> LanguageManager.t("bs_status_checking", "Checking the sign-in…")
            BrowserLoginStage.WAITING_FOR_CONFIRMATION -> LanguageManager.t(
                "bs_status_waiting",
                "Waiting for Mozilla to confirm the sign-in. Finish any code or CAPTCHA on the page.",
            )
            BrowserLoginStage.GETTING_TOKENS -> LanguageManager.t("bs_status_token", "Getting the account token…")
            null -> null
        }
        if (progress in 1..99 && viewFailure == null) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = RxBrowser.Violet)
        }
        statusText?.let {
            Surface(color = RxBrowser.Panel, modifier = Modifier.fillMaxWidth()) {
                Text(it, color = RxBrowser.Good, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        if (blockedNotice) {
            Surface(color = RxBrowser.Panel, modifier = Modifier.fillMaxWidth()) {
                Text(
                    LanguageManager.t("bs_blocked_nav", "A page outside Mozilla sign-in was blocked."),
                    color = RxBrowser.Mute, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (webView != null && viewFailure == null && initFailure == null) {
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            }
            val problem = viewFailure ?: initFailure ?: errorMessage
            if (problem != null) {
                ProblemPanel(
                    message = problem,
                    // A failed session exchange can be retried on the same page; a broken page / WebView cannot.
                    onRetry = if (viewFailure == null && initFailure == null && webView != null) {
                        {
                            errorMessage = null
                            webView.loadUrl(FxaWebChannel.loginUrl(initialEmail))
                        }
                    } else if (webView != null) {
                        {
                            viewFailure = null
                            webView.loadUrl(FxaWebChannel.loginUrl(initialEmail))
                        }
                    } else {
                        null
                    },
                    onUseDirect = onUseDirectSignIn,
                )
            }
        }
    }
}

@Composable
private fun ProblemPanel(message: String, onRetry: (() -> Unit)?, onUseDirect: () -> Unit) {
    Box(Modifier.fillMaxSize().background(RxBrowser.Bg.copy(alpha = 0.96f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                LanguageManager.t("bs_broken", "Browser sign-in could not finish"),
                color = RxBrowser.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Text(message, color = RxBrowser.Bad, fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            if (onRetry != null) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = RxBrowser.Violet),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(LanguageManager.t("bs_retry", "Try again"), color = Color.White) }
            }
            OutlinedButton(
                onClick = onUseDirect,
                border = BorderStroke(1.dp, RxBrowser.Line),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(LanguageManager.t("bs_use_direct", "Use direct sign-in"), color = RxBrowser.Text) }
        }
    }
}
