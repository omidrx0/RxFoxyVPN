package com.vauth.rxfoxyvpn.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vauth.rxfoxyvpn.R
import com.vauth.rxfoxyvpn.data.LanguageManager
import com.vauth.rxfoxyvpn.data.LocalAppLanguage
import com.vauth.rxfoxyvpn.data.ProxyStateStore
import com.vauth.rxfoxyvpn.data.RECOMMENDED_COUNTRY_CODE
import com.vauth.rxfoxyvpn.data.ServerListClient
import com.vauth.rxfoxyvpn.data.model.ConnectionState
import com.vauth.rxfoxyvpn.data.model.VpnCountry
import com.vauth.rxfoxyvpn.vpn.PingUtil
import com.vauth.rxfoxyvpn.vpn.RxFoxyVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* Design tokens from the Windows web UI (same values as HomeScreen.kt) */
private object RxList {
    val Bg = Color(0xFF060714)
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
    val Warn = Color(0xFFFFB86B)
}

private enum class CountryPingState { IDLE, PINGING, DONE, FAILED }

private fun countryCodeToEmoji(countryCode: String): String {
    if (countryCode.length != 2) return "🌐"
    val firstChar = Character.codePointAt(countryCode.uppercase(), 0) - 0x41 + 0x1F1E6
    val secondChar = Character.codePointAt(countryCode.uppercase(), 1) - 0x41 + 0x1F1E6
    return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
}

private fun fill(template: String, n: String): String =
    if (template.contains("{n}")) template.replace("{n}", n) else "$template $n"

private data class CountryUiModel(
    val country: VpnCountry,
    val flagEmoji: String,
    val localizedName: String,
    val subText: String,
    val isSelected: Boolean,
    val isRecommended: Boolean,
)

// Same thresholds as the Windows app: <60 / <120 / <200 / slow  ->  4 / 3 / 2 / 1 bars
private fun qualityColor(ms: Int): Color = when {
    ms < 120 -> RxList.Good
    ms < 200 -> RxList.Warn
    else -> RxList.Bad
}

private fun qualityBars(ms: Int): Int = when {
    ms < 60 -> 4
    ms < 120 -> 3
    ms < 200 -> 2
    else -> 1
}

@Composable
fun ServerListScreen(
    serverListClient: ServerListClient,
    proxyStateStore: ProxyStateStore,
    onServerSelected: () -> Unit,
    onBack: () -> Unit,
) {
    var countries by remember { mutableStateOf<List<VpnCountry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isPingingAll by remember { mutableStateOf(false) }
    var sortByPing by remember { mutableStateOf(false) }

    val selectedState by proxyStateStore.selectedProxyFlow.collectAsState()
    val vpnState by RxFoxyVpnService.state.collectAsState()
    val activeLang = LocalAppLanguage.current
    val langState by LanguageManager.currentLang.collectAsState()
    val scope = rememberCoroutineScope()

    val pingResults = remember { mutableStateMapOf<String, Int>() }
    val pingStates = remember { mutableStateMapOf<String, CountryPingState>() }

    LaunchedEffect(Unit) {
        runCatching { serverListClient.fetchCountries() }
            .onSuccess { countries = it }
            .onFailure { errorMessage = it.message ?: LanguageManager.t("err_load_servers", "Failed to load server list") }
        isLoading = false
    }

    val filteredCountries = remember(countries, searchQuery, activeLang, langState) {
        if (searchQuery.isBlank()) countries else {
            val q = searchQuery.trim().lowercase()
            countries.filter { country ->
                val localizedName = LanguageManager.getCountryName(country.code, country.name)
                country.name.lowercase().contains(q) ||
                        localizedName.lowercase().contains(q) ||
                        country.code.lowercase().contains(q)
            }
        }
    }

    val uiModels = remember(filteredCountries, activeLang, langState, selectedState) {
        filteredCountries
            .sortedWith(
                compareBy(
                    { it.code != RECOMMENDED_COUNTRY_CODE },
                    { LanguageManager.getCountryName(it.code, it.name) },
                )
            )
            .map { country ->
                val isRec = (country.code == RECOMMENDED_COUNTRY_CODE)
                val locName = if (isRec) {
                    LanguageManager.t("recommended", "Recommended")
                } else {
                    LanguageManager.getCountryName(country.code, country.name)
                }
                val total = country.cities.sumOf { it.servers.size }
                val serversText = fill(LanguageManager.t("srv_n", "{n} سرور"), total.toString())
                val sub = if (isRec) {
                    LanguageManager.t("auto", "خودکار") + " · " + serversText
                } else {
                    country.code.uppercase() + " · " + serversText
                }
                val sel = (selectedState?.countryCode == country.code)
                CountryUiModel(
                    country = country,
                    flagEmoji = countryCodeToEmoji(country.code),
                    localizedName = locName,
                    subText = sub,
                    isSelected = sel,
                    isRecommended = isRec,
                )
            }
    }

    // "Sort by ping": recommended first, then fastest first (like the Windows list). Reads pingResults so it updates live.
    val shownModels = if (sortByPing) {
        val rec = uiModels.filter { it.isRecommended }
        val rest = uiModels.filter { !it.isRecommended }
            .sortedBy { pingResults[it.country.code] ?: Int.MAX_VALUE }
        rec + rest
    } else uiModels

    fun pingCountry(cCode: String) {
        if (pingStates[cCode] == CountryPingState.PINGING) return
        pingStates[cCode] = CountryPingState.PINGING
        scope.launch(Dispatchers.IO) {
            val candidate = ServerListClient.candidatesForCountry(countries, cCode).firstOrNull()
            if (candidate == null) {
                withContext(Dispatchers.Main) { pingStates[cCode] = CountryPingState.FAILED }
                return@launch
            }
            val result = PingUtil.measureTcpLatencyMs(candidate.host, candidate.port)
            withContext(Dispatchers.Main) {
                if (result != null) {
                    pingResults[cCode] = result
                    pingStates[cCode] = CountryPingState.DONE
                } else {
                    pingStates[cCode] = CountryPingState.FAILED
                }
            }
        }
    }

    fun triggerPingAll() {
        if (isPingingAll) return
        isPingingAll = true
        scope.launch(Dispatchers.IO) {
            val targets = uiModels.filter { !it.isRecommended }
            val deferreds = targets.map { model ->
                async {
                    val cCode = model.country.code
                    val candidate = ServerListClient.candidatesForCountry(countries, cCode).firstOrNull()
                    val result = if (candidate != null) {
                        PingUtil.measureTcpLatencyMs(candidate.host, candidate.port)
                    } else null
                    cCode to result
                }
            }
            val results = deferreds.awaitAll()
            withContext(Dispatchers.Main) {
                for ((cCode, result) in results) {
                    if (result != null) {
                        pingResults[cCode] = result
                        pingStates[cCode] = CountryPingState.DONE
                    } else {
                        pingStates[cCode] = CountryPingState.FAILED
                    }
                }
                isPingingAll = false
            }
        }
    }

    Scaffold(containerColor = Color.Transparent) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            ListBackground()

            Column(Modifier.fillMaxSize()) {
                // ----- header: back + "Servers (N)" -----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF080A20).copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = RxList.Text)
                    }
                    Text(
                        LanguageManager.t("servers", "سرورها"),
                        color = RxList.Text, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                    )
                    if (countries.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text("(${countries.size})", color = RxList.Mute, fontSize = 12.5.sp)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(RxList.Line))

                // ----- search + chips -----
                Column(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(LanguageManager.t("search_country", "جستجوی کشور…"), color = RxList.Mute) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = RxList.Mute) },
                        singleLine = true,
                        shape = RoundedCornerShape(13.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF080A20).copy(alpha = 0.62f),
                            unfocusedContainerColor = Color(0xFF080A20).copy(alpha = 0.62f),
                            focusedBorderColor = RxList.Violet,
                            unfocusedBorderColor = RxList.Line,
                            focusedTextColor = RxList.Text,
                            unfocusedTextColor = RxList.Text,
                            cursorColor = RxList.Cyan,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(
                            text = LanguageManager.t("sort", "مرتب‌سازی با پینگ"),
                            on = sortByPing,
                            onClick = { sortByPing = !sortByPing },
                        )
                        Chip(
                            text = LanguageManager.t("ping_all", "پینگ همه"),
                            icon = true,
                            loading = isPingingAll,
                            enabled = !isPingingAll && !isLoading,
                            onClick = { triggerPingAll() },
                        )
                    }
                }

                // ----- list -----
                Box(Modifier.fillMaxSize()) {
                    when {
                        isLoading -> CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = RxList.Cyan,
                        )
                        errorMessage != null && countries.isEmpty() -> Text(
                            errorMessage.orEmpty(),
                            color = RxList.Bad,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                        shownModels.isEmpty() -> Text(
                            LanguageManager.t("nomatch", "موردی پیدا نشد"),
                            color = RxList.Mute,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            items(shownModels, key = { it.country.code }) { model ->
                                val cCode = model.country.code
                                ServerRow(
                                    model = model,
                                    live = model.isSelected && vpnState == ConnectionState.CONNECTED,
                                    pingState = pingStates[cCode] ?: CountryPingState.IDLE,
                                    pingMs = pingResults[cCode],
                                    onSelected = {
                                        val candidates = ServerListClient.candidatesForCountry(countries, model.country.code)
                                        val chosen = candidates.randomOrNull()
                                        if (chosen != null) {
                                            proxyStateStore.save(chosen)
                                            onServerSelected()
                                        } else {
                                            errorMessage = LanguageManager.tn("err_no_servers", "No available servers for {n}", model.localizedName)
                                        }
                                    },
                                    onTestPing = { pingCountry(cCode) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListBackground() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0D1033), RxList.Bg)))
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
                        listOf(RxList.Bg.copy(alpha = 0.65f), RxList.Bg.copy(alpha = 0.45f), RxList.Bg.copy(alpha = 0.75f))
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
            blob(Color(0xFF3A62FF).copy(alpha = 0.28f), Offset(0f, 0f), big * 0.55f)
            blob(Color(0xFF8A3CFF).copy(alpha = 0.26f), Offset(size.width, size.height), big * 0.55f)
        }
    }
}

@Composable
private fun Chip(
    text: String,
    on: Boolean = false,
    icon: Boolean = false,
    loading: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = if (on) RxList.Violet.copy(alpha = 0.18f) else RxList.Panel2,
        border = BorderStroke(1.dp, if (on) RxList.Violet else RxList.Line),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = RxList.Pink)
                Spacer(Modifier.width(6.dp))
            } else if (icon) {
                Icon(Icons.Filled.Speed, contentDescription = null, tint = RxList.Cyan, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, color = RxList.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SignalBars(count: Int, color: Color) {
    val heights = listOf(5.dp, 8.dp, 11.dp, 14.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        heights.forEachIndexed { i, h ->
            Box(
                Modifier
                    .width(3.dp)
                    .height(h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i < count) color else RxList.Line2)
            )
        }
    }
}

@Composable
private fun LiveTag(text: String) {
    val inf = rememberInfiniteTransition(label = "live")
    val a by inf.animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
        label = "live-a",
    )
    Row(
        Modifier
            .clip(CircleShape)
            .background(RxList.Good.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).graphicsLayer { alpha = a }.background(RxList.Good, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(text, color = RxList.Good, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FlagCircle(model: CountryUiModel, size: Dp = 38.dp) {
    if (model.isRecommended) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(RxList.Panel2)
                .border(1.5.dp, RxList.Line2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Public, contentDescription = null, tint = RxList.Cyan, modifier = Modifier.size(22.dp))
        }
    } else {
        CountryFlag(countryCode = model.country.code, size = size)
    }
}

@Composable
private fun ServerRow(
    model: CountryUiModel,
    live: Boolean,
    pingState: CountryPingState,
    pingMs: Int?,
    onSelected: () -> Unit,
    onTestPing: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val selectedBg = Brush.linearGradient(
        listOf(RxList.Blue.copy(alpha = 0.16f), RxList.Violet.copy(alpha = 0.16f), RxList.Pink.copy(alpha = 0.10f))
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (model.isSelected) Modifier.background(selectedBg) else Modifier)
            .border(1.dp, if (model.isSelected) RxList.Violet.copy(alpha = 0.55f) else Color.Transparent, shape)
            .clickable(onClick = onSelected)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagCircle(model)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                model.localizedName,
                color = RxList.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                model.subText,
                color = RxList.Mute, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (live) LiveTag(LanguageManager.t("live", "فعال"))

            if (!model.isRecommended) {
                when (pingState) {
                    CountryPingState.PINGING ->
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = RxList.Pink)

                    CountryPingState.DONE -> if (pingMs != null) {
                        val color = qualityColor(pingMs)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("$pingMs", color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Spacer(Modifier.width(3.dp))
                            Text("ms", color = color, fontSize = 10.5.sp, modifier = Modifier.padding(bottom = 1.dp))
                        }
                        SignalBars(qualityBars(pingMs), color)
                    }

                    CountryPingState.FAILED ->
                        Text("✕", color = RxList.Bad, fontWeight = FontWeight.Bold, fontSize = 13.sp)

                    CountryPingState.IDLE -> if (model.isSelected) {
                        Chip(
                            text = LanguageManager.t("test_ping", "پینگ بگیر"),
                            icon = true,
                            onClick = onTestPing,
                        )
                    }
                }
            }
        }
    }
}