package com.vauth.rxfoxyvpn.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vauth.rxfoxyvpn.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val UpdateBg = Color(0xFF0B0D25)
private val UpdateViolet = Color(0xFF8B5CF6)
private val UpdateText = Color(0xFFEEF0FF)
private val UpdateMute = Color(0xFF9AA1D6)
private const val RELEASES_URL = "https://github.com/omidrx0/RxFoxyVPN/releases"

private data class ReleaseInfo(val version: String)

private fun isVersionNewer(remote: String, local: String): Boolean {
    fun parts(value: String) = value.trim().removePrefix("v").removePrefix("V")
        .substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(remote); val b = parts(local)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private fun fetchLatestRelease(): ReleaseInfo {
    val connection = (URL("https://api.github.com/repos/omidrx0/RxFoxyVPN/releases/latest").openConnection() as HttpURLConnection).apply {
        connectTimeout = 12000
        readTimeout = 15000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "RXFoxyVPN-Android-Updater")
    }
    try {
        if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val tag = json.optString("tag_name").ifBlank { json.optString("name") }
            .removePrefix("v").removePrefix("V")
        if (tag.isBlank()) throw IllegalStateException("Release version missing")
        return ReleaseInfo(tag)
    } finally {
        connection.disconnect()
    }
}

@Composable
internal fun AndroidUpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lang by com.vauth.rxfoxyvpn.data.LanguageManager.currentLang.collectAsState()
    val current = BuildConfig.VERSION_NAME
    var checking by remember { mutableStateOf(false) }
    var latest by remember { mutableStateOf<ReleaseInfo?>(null) }
    var message by remember { mutableStateOf(updateText(lang, "intro")) }
    var error by remember { mutableStateOf(false) }

    fun check() {
        checking = true
        error = false
        message = updateText(lang, "checking")
        latest = null
        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) { fetchLatestRelease() }
                latest = release
                message = if (isVersionNewer(release.version, current)) {
                    updateText(lang, "available").replace("{version}", "V${release.version}")
                } else {
                    updateText(lang, "latest")
                }
            } catch (_: Exception) {
                error = true
                message = updateText(lang, "check_error")
            } finally {
                checking = false
            }
        }
    }

    val updateAvailable = latest?.let { isVersionNewer(it.version, current) } == true

    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        containerColor = UpdateBg,
        titleContentColor = UpdateText,
        textContentColor = UpdateMute,
        shape = RoundedCornerShape(28.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.SystemUpdateAlt, null, tint = UpdateViolet, modifier = Modifier.size(26.dp))
                Text(updateText(lang, "title"), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(message, color = if (error) Color(0xFFFF718B) else UpdateMute)
                if (updateAvailable) {
                    Surface(color = Color(0xFF191B43), shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(updateText(lang, "current"), color = UpdateMute, style = MaterialTheme.typography.labelMedium)
                                Text("V$current", color = UpdateText, fontWeight = FontWeight.SemiBold)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(updateText(lang, "newest"), color = UpdateMute, style = MaterialTheme.typography.labelMedium)
                                Text("V${latest!!.version}", color = Color(0xFF67E8B7), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text(updateText(lang, "manual_download"), color = UpdateMute)
                }
            }
        },
        confirmButton = {
            if (updateAvailable) {
                Button(
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)))
                            onDismiss()
                        } catch (_: Exception) {
                            error = true
                            message = updateText(lang, "open_error")
                        }
                    },
                    enabled = !checking,
                    colors = ButtonDefaults.buttonColors(containerColor = UpdateViolet, contentColor = Color.White),
                ) { Text(updateText(lang, "open_releases")) }
            } else {
                Button(
                    onClick = { check() },
                    enabled = !checking,
                    colors = ButtonDefaults.buttonColors(containerColor = UpdateViolet, contentColor = Color.White),
                ) {
                    if (checking) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text(updateText(lang, "check"))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !checking) { Text(updateText(lang, "close"), color = UpdateMute) }
        },
    )
}

private fun updateText(lang: String, key: String): String {
    val en = mapOf("title" to "App updates", "intro" to "Manually check GitHub for the latest RXFoxyVPN version.", "checking" to "Checking GitHub Releases…", "available" to "A new version ({version}) is available.", "latest" to "You're using the latest version.", "check_error" to "Couldn't check for updates. Check your internet connection and try again.", "manual_download" to "Open Releases and download the APK yourself. RXFoxyVPN will not download or install it.", "open_releases" to "Open GitHub Releases", "open_error" to "Couldn't open GitHub Releases. Try opening the link in your browser.", "current" to "CURRENT VERSION", "newest" to "NEW VERSION", "check" to "Check for updates", "close" to "Close")
    val fa = mapOf("title" to "به‌روزرسانی برنامه", "intro" to "نسخهٔ جدید RXFoxyVPN را به‌صورت دستی در گیت‌هاب بررسی کن.", "checking" to "در حال بررسی نسخه‌های گیت‌هاب…", "available" to "نسخهٔ جدید ({version}) در دسترس است.", "latest" to "از آخرین نسخه استفاده می‌کنی.", "check_error" to "بررسی به‌روزرسانی ناموفق بود. اینترنت را بررسی و دوباره تلاش کن.", "manual_download" to "صفحهٔ انتشارها را باز کن و فایل APK را خودت دانلود کن. برنامه آن را دانلود یا نصب نمی‌کند.", "open_releases" to "رفتن به انتشارهای گیت‌هاب", "open_error" to "بازکردن گیت‌هاب ناموفق بود. لینک را در مرورگر باز کن.", "current" to "نسخهٔ فعلی", "newest" to "نسخهٔ جدید", "check" to "بررسی به‌روزرسانی", "close" to "بستن")
    val ar = mapOf("title" to "تحديث التطبيق", "intro" to "تحقق يدويًا من أحدث إصدار على GitHub.", "checking" to "جارٍ التحقق من إصدارات GitHub…", "available" to "يتوفر إصدار جديد ({version}).", "latest" to "أنت تستخدم أحدث إصدار.", "check_error" to "تعذر التحقق من التحديثات. تحقق من الإنترنت وحاول مجددًا.", "manual_download" to "افتح صفحة الإصدارات ونزّل ملف APK بنفسك. لن يقوم التطبيق بتنزيله أو تثبيته.", "open_releases" to "فتح إصدارات GitHub", "open_error" to "تعذر فتح GitHub. حاول فتح الرابط في المتصفح.", "current" to "الإصدار الحالي", "newest" to "الإصدار الجديد", "check" to "التحقق من التحديثات", "close" to "إغلاق")
    val ru = mapOf("title" to "Обновление приложения", "intro" to "Вручную проверить последнюю версию RXFoxyVPN на GitHub.", "checking" to "Проверка релизов GitHub…", "available" to "Доступна новая версия ({version}).", "latest" to "У вас последняя версия.", "check_error" to "Не удалось проверить обновления. Проверьте интернет и повторите попытку.", "manual_download" to "Откройте страницу релизов и скачайте APK самостоятельно. Приложение не будет скачивать или устанавливать его.", "open_releases" to "Открыть релизы GitHub", "open_error" to "Не удалось открыть GitHub. Откройте ссылку в браузере.", "current" to "ТЕКУЩАЯ ВЕРСИЯ", "newest" to "НОВАЯ ВЕРСИЯ", "check" to "Проверить обновления", "close" to "Закрыть")
    val ko = mapOf("title" to "앱 업데이트", "intro" to "GitHub에서 최신 RXFoxyVPN 버전을 수동으로 확인합니다.", "checking" to "GitHub 릴리스를 확인하는 중…", "available" to "새 버전({version})을 사용할 수 있습니다.", "latest" to "최신 버전을 사용 중입니다.", "check_error" to "업데이트를 확인할 수 없습니다. 인터넷을 확인하고 다시 시도하세요.", "manual_download" to "릴리스 페이지를 열고 APK를 직접 다운로드하세요. 앱은 APK를 다운로드하거나 설치하지 않습니다.", "open_releases" to "GitHub 릴리스 열기", "open_error" to "GitHub를 열 수 없습니다. 브라우저에서 링크를 열어 보세요.", "current" to "현재 버전", "newest" to "새 버전", "check" to "업데이트 확인", "close" to "닫기")
    val zh = mapOf("title" to "应用更新", "intro" to "手动检查 GitHub 上的最新 RXFoxyVPN 版本。", "checking" to "正在检查 GitHub 发布版本…", "available" to "有新版本（{version}）可用。", "latest" to "你使用的是最新版本。", "check_error" to "无法检查更新。请检查网络后重试。", "manual_download" to "打开发布页面并自行下载 APK。应用不会下载或安装 APK。", "open_releases" to "打开 GitHub 发布页", "open_error" to "无法打开 GitHub，请尝试在浏览器中打开链接。", "current" to "当前版本", "newest" to "新版本", "check" to "检查更新", "close" to "关闭")
    val dict = when (lang.lowercase()) { "fa" -> fa; "ar" -> ar; "ru" -> ru; "ko" -> ko; "zh" -> zh; else -> en }
    return dict[key] ?: en[key] ?: key
}


/**
 * Quietly checks GitHub Releases once when the main screen is opened.
 * It only shows a dialog when a newer release exists; downloading and installing
 * the APK remain entirely manual through the official Releases page.
 */
@Composable
internal fun AutomaticUpdatePrompt() {
    val context = LocalContext.current
    val lang by com.vauth.rxfoxyvpn.data.LanguageManager.currentLang.collectAsState()
    var newerVersion by remember { mutableStateOf<String?>(null) }
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val result = runCatching { withContext(Dispatchers.IO) { fetchLatestRelease() } }.getOrNull()
        if (result != null && isVersionNewer(result.version, BuildConfig.VERSION_NAME)) {
            newerVersion = result.version
        }
    }

    val version = newerVersion
    if (version != null && !dismissed) {
        AlertDialog(
            onDismissRequest = { dismissed = true },
            containerColor = UpdateBg,
            titleContentColor = UpdateText,
            textContentColor = UpdateMute,
            shape = RoundedCornerShape(28.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.SystemUpdateAlt, null, tint = UpdateViolet, modifier = Modifier.size(26.dp))
                    Text(updateText(lang, "title"), fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(updateText(lang, "available").replace("{version}", "V$version"), color = UpdateText)
                    Text(updateText(lang, "manual_download"), color = UpdateMute)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)))
                            dismissed = true
                        } catch (_: Exception) {
                            dismissed = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = UpdateViolet, contentColor = Color.White),
                ) { Text(updateText(lang, "open_releases")) }
            },
            dismissButton = {
                TextButton(onClick = { dismissed = true }) { Text(updateText(lang, "close"), color = UpdateMute) }
            },
        )
    }
}
