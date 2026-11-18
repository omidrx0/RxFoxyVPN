package com.vauth.rxfoxyvpn.data.routing

import android.content.Context
import com.vauth.rxfoxyvpn.R
import com.vauth.rxfoxyvpn.data.AppLogger
import com.vauth.rxfoxyvpn.data.SettingsStore

/**
 * Regional routing policy: which destinations should bypass the VPN
 * ("Direct routing") for a country, used in two places:
 *
 * - route rules in the sing-box config (domain suffixes + IPv4 CIDRs → direct-out),
 * - DNS rules in the same config (region domains resolve via the system resolver).
 *
 * Data sources, both bundled as app assets (fully offline at connect time):
 * - IPv4 ranges: IPdeny per-country zone files (primary: ipdeny.com, public data),
 * - domain suffixes: the country TLD plus a short, curated list of popular services
 *   served on international domains (e.g. baidu.com for China).
 */
object RoutingPolicy {

    data class RegionInfo(
        val region: SettingsStore.RoutingRegion,
        /** Raw asset id for the IPdeny zone, null for NONE. */
        val zoneRawId: Int?,
        /** ccTLD + curated international-domain suffixes. */
        val domainSuffixes: List<String>,
    )

    val regions: List<RegionInfo> = listOf(
        RegionInfo(SettingsStore.RoutingRegion.NONE, null, emptyList()),
        RegionInfo(
            SettingsStore.RoutingRegion.IR, R.raw.routing_ir,
            listOf("ir", "aparat.com", "digikala.com", "filimo.com", "torob.com", "sheypoor.com", "namnak.com", "tsetmc.com"),
        ),
        RegionInfo(
            SettingsStore.RoutingRegion.CN, R.raw.routing_cn,
            listOf("cn", "baidu.com", "taobao.com", "tmall.com", "jd.com", "qq.com", "163.com", "126.com", "weibo.com", "bilibili.com", "zhihu.com", "alipay.com", "sina.com.cn"),
        ),
        RegionInfo(
            SettingsStore.RoutingRegion.RU, R.raw.routing_ru,
            listOf("ru", "su", "xn--p1ai", "vk.com", "vk.me", "yandex.com", "dzen.ru", "avito.ru", "ozon.ru", "mail.ru", "ok.ru"),
        ),
        RegionInfo(
            SettingsStore.RoutingRegion.AF, R.raw.routing_af,
            listOf("af", "tolafghan.com", "pajhwok.com"),
        ),
        RegionInfo(
            SettingsStore.RoutingRegion.TR, R.raw.routing_tr,
            listOf("tr", "xn--y9a3aq", "trendyol.com", "hepsiburada.com", "n11.com", "sahibinden.com", "gittigidiyor.com"),
        ),
        RegionInfo(
            SettingsStore.RoutingRegion.ID, R.raw.routing_id,
            listOf("id", "co.id", "ac.id", "go.id", "or.id", "web.id", "tokopedia.com", "bukalapak.com", "traveloka.com", "blibli.com", "gojek.com"),
        ),
        RegionInfo(
            SettingsStore.RoutingRegion.BR, R.raw.routing_br,
            listOf("br", "com.br", "globo.com", "uol.com.br", "mercadolivre.com.br", "americanas.com.br", "magazineluiza.com.br"),
        ),
    )

    fun suffixesFor(region: SettingsStore.RoutingRegion): List<String> =
        regions.firstOrNull { it.region == region }?.domainSuffixes ?: emptyList()

    /**
     * Reads and validates the bundled IPdeny zone for [region]. Results are cached for the
     * process lifetime; parse failures are logged and yield an empty list (domain rules still work).
     */
    @Volatile
    private var zoneCache: Map<SettingsStore.RoutingRegion, List<String>> = emptyMap()

    fun cidrsFor(context: Context, region: SettingsStore.RoutingRegion): List<String> {
        if (region == SettingsStore.RoutingRegion.NONE) return emptyList()
        zoneCache[region]?.let { return it }
        val info = regions.firstOrNull { it.region == region } ?: return emptyList()
        val rawId = info.zoneRawId ?: return emptyList()
        return synchronized(this) {
            zoneCache[region]?.let { return@synchronized it }
            val parsed = runCatching {
                context.resources.openRawResource(rawId).bufferedReader().useLines { lines ->
                    buildList {
                        for (line in lines) {
                            val candidate = line.trim()
                            if (candidate.isEmpty() || candidate.startsWith("#")) continue
                            if (!CIDR_V4.matches(candidate)) continue
                            add(candidate)
                            if (size >= MAX_CIDRS) break
                        }
                    }
                }
            }.getOrElse { failure ->
                AppLogger.e(TAG, "routing zone asset for $region could not be read", failure)
                emptyList()
            }
            zoneCache = zoneCache + (region to parsed)
            AppLogger.i(TAG, "loaded ${parsed.size} IPv4 CIDRs for $region (cached)")
            parsed
        }
    }

    private const val TAG = "RoutingPolicy"
    private const val MAX_CIDRS = 20_000
    private val CIDR_V4 = Regex("""^(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])(\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}/([0-9]|[12][0-9]|3[0-2])$""")
}
