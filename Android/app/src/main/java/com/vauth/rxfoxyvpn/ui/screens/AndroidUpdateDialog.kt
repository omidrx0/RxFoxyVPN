package com.vauth.rxfoxyvpn.ui.screens

import com.vauth.rxfoxyvpn.BuildConfig
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private val UpdateBg = Color(0xFF0B0D25)
private val UpdateViolet = Color(0xFF8B5CF6)
private val UpdateBlue = Color(0xFF4D7CFF)
private val UpdateText = Color(0xFFEEF0FF)
private val UpdateMute = Color(0xFF9AA1D6)

private data class ReleaseInfo(val version: String, val apkUrl: String)

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
        connectTimeout = 12000; readTimeout = 15000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "RXFoxyVPN-Android-Updater")
    }
    try {
        if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val tag = json.optString("tag_name").ifBlank { json.optString("name") }.removePrefix("v").removePrefix("V")
        val assets = json.optJSONArray("assets") ?: throw IllegalStateException("No release assets")
        val abiNames = Build.SUPPORTED_ABIS.toList()
        val assetMap = (0 until assets.length()).mapNotNull { i ->
            val a = assets.optJSONObject(i) ?: return@mapNotNull null
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (name.endsWith(".apk", true) && url.startsWith("https://")) name to url else null
        }
        val selected = abiNames.firstNotNullOfOrNull { abi ->
            assetMap.firstOrNull { (name, _) -> name.contains(abi, ignoreCase = true) && !name.contains("universal", true) }?.second
        } ?: assetMap.firstOrNull { (name, _) -> name.contains("universal", true) }?.second
            ?: assetMap.firstOrNull()?.second
            ?: throw IllegalStateException("No APK asset found in latest release")
        return ReleaseInfo(tag, selected)
    } finally { connection.disconnect() }
}

@Composable
internal fun AndroidUpdateDialog(
    onDismiss: () -> Unit,
    autoCheck: Boolean = false,
    onlyShowWhenAvailable: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lang by com.vauth.rxfoxyvpn.data.LanguageManager.currentLang.collectAsState()
    val current = BuildConfig.VERSION_NAME
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var downloaded by remember { mutableLongStateOf(0L) }
    var total by remember { mutableLongStateOf(0L) }
    var latest by remember { mutableStateOf<ReleaseInfo?>(null) }
    var message by remember { mutableStateOf(updateText(lang, "intro")) }
    var error by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(!onlyShowWhenAvailable) }

    fun check() {
        checking = true; error = false; message = updateText(lang, "checking")
        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) { fetchLatestRelease() }
                latest = release
                val updateAvailable = isVersionNewer(release.version, current)
                message = if (updateAvailable) updateText(lang, "available").replace("{version}", "V${release.version}") else updateText(lang, "latest")
                if (onlyShowWhenAvailable) visible = updateAvailable
            } catch (_: Exception) {
                if (!onlyShowWhenAvailable) { error = true; message = updateText(lang, "check_error") }
            }
            finally { checking = false }
        }
    }

    LaunchedEffect(autoCheck) {
        if (autoCheck) check()
    }

    fun downloadAndInstall(release: ReleaseInfo) {
        downloading = true; error = false; progress = 0; downloaded = 0L; total = 0L
        message = updateText(lang, "downloading")
        scope.launch {
            var temp: File? = null
            try {
                temp = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                    val out = File(dir, "RXFoxyVPN-${release.version}.apk")
                    val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000; readTimeout = 30000
                        setRequestProperty("User-Agent", "RXFoxyVPN-Android-Updater")
                    }
                    try {
                        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                        val length = conn.contentLengthLong
                        withContext(Dispatchers.Main) { total = length }
                        conn.inputStream.use { input -> out.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024); var count: Int; var done = 0L
                            while (input.read(buffer).also { count = it } != -1) {
                                output.write(buffer, 0, count); done += count
                                withContext(Dispatchers.Main) {
                                    downloaded = done
                                    if (length > 0) progress = ((done * 100L) / length).toInt().coerceIn(0, 100)
                                }
                            }
                        } }
                    } finally { conn.disconnect() }
                    out
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", temp)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
                message = updateText(lang, "install_opened")
            } catch (_: Exception) { error = true; message = updateText(lang, "download_error") }
            finally { downloading = false }
        }
    }

    if (!visible) return

    AlertDialog(
        onDismissRequest = { if (!checking && !downloading) { visible = false; onDismiss() } },
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
                if (latest != null && isVersionNewer(latest!!.version, current) && !downloading) {
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
                }
                if (downloading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (total > 0) "${formatBytes(downloaded)} / ${formatBytes(total)}" else formatBytes(downloaded), color = UpdateText, style = MaterialTheme.typography.labelMedium)
                        Text(if (total > 0) "$progress%" else "…", color = Color(0xFF67E8B7), fontWeight = FontWeight.Bold)
                    }
                    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF24264F))) {
                        if (total > 0) Box(Modifier.fillMaxWidth(progress / 100f).fillMaxHeight().background(Brush.horizontalGradient(listOf(UpdateBlue, UpdateViolet, Color(0xFFFF5FB0)))))
                        else LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.Center), color = UpdateViolet, trackColor = Color.Transparent)
                    }
                }
            }
        },
        confirmButton = {
            if (latest == null || !isVersionNewer(latest!!.version, current)) {
                Button(onClick = { check() }, enabled = !checking && !downloading, colors = ButtonDefaults.buttonColors(containerColor = UpdateViolet, contentColor = Color.White)) {
                    if (checking) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp) else Text(updateText(lang, "check"))
                }
            } else {
                Button(onClick = { downloadAndInstall(latest!!) }, enabled = !checking && !downloading, colors = ButtonDefaults.buttonColors(containerColor = UpdateViolet, contentColor = Color.White)) {
                    Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(updateText(lang, "download_install"))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { visible = false; onDismiss() }, enabled = !checking && !downloading) { Text(updateText(lang, "close"), color = UpdateMute) }
        }
    )
}

private fun formatBytes(value: Long): String {
    if (value < 1024) return "$value B"
    val kb = value / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
    return String.format(java.util.Locale.US, "%.1f MB", kb / 1024.0)
}

private fun updateText(lang: String, key: String): String {
    val en = mapOf("title" to "App updates", "intro" to "Check GitHub for the latest RXFoxyVPN version.", "checking" to "Checking GitHub Releases…", "available" to "A new version ({version}) is available.", "latest" to "You're using the latest version.", "check_error" to "Couldn't check for updates. Check your internet connection and try again.", "downloading" to "Downloading update… Keep this screen open.", "install_opened" to "Download complete. Android's installer has been opened.", "download_error" to "Download failed. Check your connection and try again.", "current" to "CURRENT VERSION", "newest" to "NEW VERSION", "check" to "Check for updates", "download_install" to "Download & install", "close" to "Close")
    val fa = mapOf("title" to "به‌روزرسانی برنامه", "intro" to "آخرین نسخه RXFoxyVPN را در گیت‌هاب بررسی کن.", "checking" to "در حال بررسی نسخه‌های گیت‌هاب…", "available" to "نسخه جدید ({version}) در دسترس است.", "latest" to "از آخرین نسخه استفاده می‌کنی.", "check_error" to "بررسی به‌روزرسانی ناموفق بود. اینترنت را بررسی و دوباره تلاش کن.", "downloading" to "در حال دانلود به‌روزرسانی… این صفحه را باز نگه دار.", "install_opened" to "دانلود تمام شد. نصب‌کننده اندروید باز شد.", "download_error" to "دانلود ناموفق بود. اتصال اینترنت را بررسی کن.", "current" to "نسخه فعلی", "newest" to "نسخه جدید", "check" to "بررسی به‌روزرسانی", "download_install" to "دانلود و نصب", "close" to "بستن")
    val ar = mapOf("title" to "تحديث التطبيق", "intro" to "تحقق من أحدث إصدار من RXFoxyVPN على GitHub.", "checking" to "جارٍ التحقق من الإصدارات…", "available" to "يتوفر إصدار جديد ({version}).", "latest" to "أنت تستخدم أحدث إصدار.", "check_error" to "تعذر التحقق من التحديثات. تحقق من الإنترنت وحاول مجددًا.", "downloading" to "جارٍ تنزيل التحديث… أبقِ هذه الصفحة مفتوحة.", "install_opened" to "اكتمل التنزيل. فُتح مُثبّت Android.", "download_error" to "فشل التنزيل. تحقق من الاتصال وحاول مجددًا.", "current" to "الإصدار الحالي", "newest" to "الإصدار الجديد", "check" to "التحقق من التحديثات", "download_install" to "تنزيل وتثبيت", "close" to "إغلاق")
    val ru = mapOf("title" to "Обновление приложения", "intro" to "Проверить последнюю версию RXFoxyVPN на GitHub.", "checking" to "Проверка релизов GitHub…", "available" to "Доступна новая версия ({version}).", "latest" to "У вас последняя версия.", "check_error" to "Не удалось проверить обновления. Проверьте интернет и повторите попытку.", "downloading" to "Загрузка обновления… Не закрывайте экран.", "install_opened" to "Загрузка завершена. Открыт установщик Android.", "download_error" to "Ошибка загрузки. Проверьте подключение и повторите попытку.", "current" to "ТЕКУЩАЯ ВЕРСИЯ", "newest" to "НОВАЯ ВЕРСИЯ", "check" to "Проверить обновления", "download_install" to "Скачать и установить", "close" to "Закрыть")
    val ko = mapOf("title" to "앱 업데이트", "intro" to "GitHub에서 최신 RXFoxyVPN 버전을 확인합니다.", "checking" to "GitHub 릴리스를 확인하는 중…", "available" to "새 버전({version})을 사용할 수 있습니다.", "latest" to "최신 버전을 사용 중입니다.", "check_error" to "업데이트를 확인할 수 없습니다. 인터넷을 확인하고 다시 시도하세요.", "downloading" to "업데이트 다운로드 중… 이 화면을 열어 두세요.", "install_opened" to "다운로드 완료. Android 설치 프로그램을 열었습니다.", "download_error" to "다운로드 실패. 연결을 확인하고 다시 시도하세요.", "current" to "현재 버전", "newest" to "새 버전", "check" to "업데이트 확인", "download_install" to "다운로드 및 설치", "close" to "닫기")
    val zh = mapOf("title" to "应用更新", "intro" to "在 GitHub 检查 RXFoxyVPN 的最新版本。", "checking" to "正在检查 GitHub 发布版本…", "available" to "有新版本（{version}）可用。", "latest" to "你使用的是最新版本。", "check_error" to "无法检查更新。请检查网络后重试。", "downloading" to "正在下载更新…请保持此页面打开。", "install_opened" to "下载完成，已打开 Android 安装程序。", "download_error" to "下载失败。请检查网络后重试。", "current" to "当前版本", "newest" to "新版本", "check" to "检查更新", "download_install" to "下载并安装", "close" to "关闭")
    val dict = when (lang.lowercase()) { "fa" -> fa; "ar" -> ar; "ru" -> ru; "ko" -> ko; "zh" -> zh; else -> en }
    return dict[key] ?: en[key] ?: key
}
