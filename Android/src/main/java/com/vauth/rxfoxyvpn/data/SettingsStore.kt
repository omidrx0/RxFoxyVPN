package com.vauth.rxfoxyvpn.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vauth.rxfoxyvpn.data.relay.RelayConfig
import com.vauth.rxfoxyvpn.ui.theme.ThemeMode

class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("foxyvpn_settings", Context.MODE_PRIVATE)

    private val credentialsPrefs by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "foxyvpn_settings_credentials",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            runCatching {
                appContext.deleteSharedPreferences("foxyvpn_settings_credentials")
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    "foxyvpn_settings_credentials",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.getOrElse {
                appContext.getSharedPreferences("foxyvpn_settings_credentials_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    var themeMode: ThemeMode
        get() = ThemeMode.entries.firstOrNull {
            it.name == prefs.getString(KEY_THEME_MODE, null)
        } ?: ThemeMode.DARK
        set(value) { prefs.edit().putString(KEY_THEME_MODE, value.name).apply() }

    var tunnelMtu: Int
        get() = prefs.getInt(KEY_TUNNEL_MTU, DEFAULT_TUNNEL_MTU).coerceIn(MIN_TUNNEL_MTU, MAX_TUNNEL_MTU)
        set(value) { prefs.edit().putInt(KEY_TUNNEL_MTU, value.coerceIn(MIN_TUNNEL_MTU, MAX_TUNNEL_MTU)).apply() }

    var exitCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_EXIT_CHECK, true)
        set(value) { prefs.edit().putBoolean(KEY_EXIT_CHECK, value).apply() }

    var signInMethod: SignInMethod
        get() = prefs.getString(KEY_SIGN_IN_METHOD, null)?.let { runCatching { SignInMethod.valueOf(it) }.getOrNull() } ?: SignInMethod.DIRECT
        set(value) { prefs.edit().putString(KEY_SIGN_IN_METHOD, value.name).apply() }

    var socksBindAddress: String
        get() = prefs.getString(KEY_SOCKS_BIND_ADDRESS, DEFAULT_SOCKS_BIND_ADDRESS) ?: DEFAULT_SOCKS_BIND_ADDRESS
        set(value) {
            prefs.edit().putString(KEY_SOCKS_BIND_ADDRESS, value.ifBlank { DEFAULT_SOCKS_BIND_ADDRESS }).apply()
        }

    var socksPort: Int
        get() = prefs.getInt(KEY_SOCKS_PORT, DEFAULT_SOCKS_PORT)
        set(value) { prefs.edit().putInt(KEY_SOCKS_PORT, value.coerceIn(1, 65_535)).apply() }

    var dohProvider: DohProvider
        get() = DohProvider.entries.firstOrNull {
            it.name == prefs.getString(KEY_DOH_PROVIDER, null)
        } ?: DohProvider.AUTOMATIC
        set(value) { prefs.edit().putString(KEY_DOH_PROVIDER, value.name).apply() }

    var customDnsEnabled: Boolean
        get() = prefs.getBoolean(KEY_CUSTOM_DNS_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_CUSTOM_DNS_ENABLED, value).apply() }

    var customDnsServer: String
        get() = (prefs.getString(KEY_CUSTOM_DNS_SERVER, null) ?: DEFAULT_CUSTOM_DNS_SERVER)
            .let { if (isValidDnsServer(it)) it else DEFAULT_CUSTOM_DNS_SERVER }
        set(value) {
            val trimmed = value.trim()
            if (!isValidDnsServer(trimmed)) return
            prefs.edit().putString(KEY_CUSTOM_DNS_SERVER, trimmed).apply()
        }

    val effectiveCustomDnsServer: String?
        get() = if (customDnsEnabled) customDnsServer.takeIf { isValidDnsServer(it) } else null

    var proxyOnlyMode: Boolean
        get() = prefs.getBoolean(KEY_PROXY_ONLY_MODE, false)
        set(value) { prefs.edit().putBoolean(KEY_PROXY_ONLY_MODE, value).apply() }

    var customEdgeAddress: String
        get() = prefs.getString(KEY_CUSTOM_EDGE_ADDRESS, "") ?: ""
        set(value) {
            val normalized = value.trim().lowercase()
            if (normalized.isNotEmpty() && !isValidEdgeHost(normalized)) return
            prefs.edit().putString(KEY_CUSTOM_EDGE_ADDRESS, normalized).apply()
        }

    val effectiveCustomEdgeAddress: String?
        get() = customEdgeAddress.takeIf { isValidEdgeHost(it) }

    var upstreamProxyEnabled: Boolean
        get() = prefs.getBoolean(KEY_UPSTREAM_PROXY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_UPSTREAM_PROXY_ENABLED, value).apply() }

    var upstreamProxyType: UpstreamProxyType
        get() = UpstreamProxyType.entries.firstOrNull {
            it.name == prefs.getString(KEY_UPSTREAM_PROXY_TYPE, null)
        } ?: UpstreamProxyType.SOCKS5
        set(value) { prefs.edit().putString(KEY_UPSTREAM_PROXY_TYPE, value.name).apply() }

    var upstreamProxyHost: String
        get() = prefs.getString(KEY_UPSTREAM_PROXY_HOST, "") ?: ""
        set(value) { prefs.edit().putString(KEY_UPSTREAM_PROXY_HOST, value.trim()).apply() }

    var upstreamProxyPort: Int
        get() = prefs.getInt(KEY_UPSTREAM_PROXY_PORT, DEFAULT_UPSTREAM_PROXY_PORT)
        set(value) { prefs.edit().putInt(KEY_UPSTREAM_PROXY_PORT, value.coerceIn(1, 65_535)).apply() }

    var upstreamProxyUsername: String
        get() = credentialsPrefs.getString(KEY_UPSTREAM_PROXY_USERNAME, "") ?: ""
        set(value) { credentialsPrefs.edit().putString(KEY_UPSTREAM_PROXY_USERNAME, value).apply() }

    var upstreamProxyPassword: String
        get() = credentialsPrefs.getString(KEY_UPSTREAM_PROXY_PASSWORD, "") ?: ""
        set(value) { credentialsPrefs.edit().putString(KEY_UPSTREAM_PROXY_PASSWORD, value).apply() }

    // ---- Control-plane relay (Mozilla sign-in / Guardian / server list only; never VPN traffic) ----
    // Kept apart from the upstream proxy settings above. The personal relay URL carries a secret, so it lives in
    // the encrypted store; the on/off switches are ordinary preferences, and turning the personal relay off keeps
    // the saved URL.

    var sharedRelayEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHARED_RELAY_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_SHARED_RELAY_ENABLED, value).apply() }

    var personalRelayEnabled: Boolean
        get() = prefs.getBoolean(KEY_PERSONAL_RELAY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_PERSONAL_RELAY_ENABLED, value).apply() }

    var personalRelayOnly: Boolean
        get() = prefs.getBoolean(KEY_PERSONAL_RELAY_ONLY, false)
        set(value) { prefs.edit().putBoolean(KEY_PERSONAL_RELAY_ONLY, value).apply() }

    /** Stored exactly as entered (trimmed). Validate with RelayPolicy.validatePersonalRelayUrl before saving. */
    var personalRelayUrl: String
        get() = credentialsPrefs.getString(KEY_PERSONAL_RELAY_URL, "") ?: ""
        set(value) { credentialsPrefs.edit().putString(KEY_PERSONAL_RELAY_URL, value.trim()).apply() }

    /**
     * Optional shared relay secret managed privately in encrypted preferences or BuildConfig,
     * completely separate from the public GitHub `updates.json` Manifest.
     */
    var sharedRelaySecret: String
        get() = credentialsPrefs.getString(KEY_SHARED_RELAY_SECRET, null)
            ?.takeIf { it.isNotEmpty() }
            ?: com.vauth.rxfoxyvpn.BuildConfig.SHARED_RELAY_SECRET
        set(value) { credentialsPrefs.edit().putString(KEY_SHARED_RELAY_SECRET, value.trim()).apply() }

    /** Optional override for the primary Manifest URL (blank uses ManifestConfig.DEFAULT_PRIMARY_URL). */
    var manifestPrimaryUrlOverride: String
        get() = prefs.getString(KEY_MANIFEST_PRIMARY_URL_OVERRIDE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_MANIFEST_PRIMARY_URL_OVERRIDE, value.trim()).apply() }

    /** Optional override for the fallback CDN Manifest URL (blank uses ManifestConfig.DEFAULT_FALLBACK_URL). */
    var manifestFallbackUrlOverride: String
        get() = prefs.getString(KEY_MANIFEST_FALLBACK_URL_OVERRIDE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_MANIFEST_FALLBACK_URL_OVERRIDE, value.trim()).apply() }

    val relayConfig: RelayConfig
        get() = RelayConfig(
            sharedEnabled = sharedRelayEnabled,
            personalEnabled = personalRelayEnabled,
            personalUrl = personalRelayUrl,
            personalOnly = personalRelayOnly,
            sharedSecret = sharedRelaySecret,
        )

    var excludedApps: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED_APPS, emptySet())?.toSet() ?: emptySet()
        set(value) {

            prefs.edit().putStringSet(KEY_EXCLUDED_APPS, HashSet(value)).apply()
        }

    // ---- sing-box engine settings (no UI yet; defaults reproduce the previous behaviour) ----

    var splitTunnelMode: SplitTunnelMode
        get() = SplitTunnelMode.entries.firstOrNull {
            it.name == prefs.getString(KEY_SPLIT_TUNNEL_MODE, null)
        } ?: SplitTunnelMode.EXCLUDE
        set(value) { prefs.edit().putString(KEY_SPLIT_TUNNEL_MODE, value.name).apply() }

    var includedApps: Set<String>
        get() = prefs.getStringSet(KEY_INCLUDED_APPS, emptySet())?.toSet() ?: emptySet()
        set(value) { prefs.edit().putStringSet(KEY_INCLUDED_APPS, HashSet(value)).apply() }

    var ipMode: IpMode
        get() = IpMode.entries.firstOrNull { it.name == prefs.getString(KEY_IP_MODE, null) } ?: IpMode.DUAL_STACK
        set(value) { prefs.edit().putString(KEY_IP_MODE, value.name).apply() }

    var httpVersion: HttpVersion
        get() = HttpVersion.entries.firstOrNull { it.name == prefs.getString(KEY_HTTP_VERSION, null) } ?: HttpVersion.HTTP_2
        set(value) { prefs.edit().putString(KEY_HTTP_VERSION, value.name).apply() }

    var tlsFragmentMode: TlsFragmentMode
        get() = TlsFragmentMode.entries.firstOrNull { it.name == prefs.getString(KEY_TLS_FRAGMENT_MODE, null) } ?: TlsFragmentMode.OFF
        set(value) { prefs.edit().putString(KEY_TLS_FRAGMENT_MODE, value.name).apply() }

    var fragmentFallbackDelayMs: Int
        get() = prefs.getInt(KEY_FRAGMENT_FALLBACK_DELAY, DEFAULT_FRAGMENT_FALLBACK_DELAY_MS).coerceIn(50, 2_000)
        set(value) { prefs.edit().putInt(KEY_FRAGMENT_FALLBACK_DELAY, value.coerceIn(50, 2_000)).apply() }

    var utlsFingerprint: UtlsFingerprint
        get() = UtlsFingerprint.entries.firstOrNull { it.name == prefs.getString(KEY_UTLS_FINGERPRINT, null) } ?: UtlsFingerprint.FIREFOX
        set(value) { prefs.edit().putString(KEY_UTLS_FINGERPRINT, value.name).apply() }

    var maxConcurrentStreams: Int
        get() = prefs.getInt(KEY_MAX_CONCURRENT_STREAMS, DEFAULT_MAX_CONCURRENT_STREAMS).coerceIn(2, 128)
        set(value) { prefs.edit().putInt(KEY_MAX_CONCURRENT_STREAMS, value.coerceIn(2, 128)).apply() }

    var bypassLocalNetwork: Boolean
        get() = prefs.getBoolean(KEY_BYPASS_LOCAL_NETWORK, true)
        set(value) { prefs.edit().putBoolean(KEY_BYPASS_LOCAL_NETWORK, value).apply() }

    /** Country whose traffic should bypass the VPN ("Direct routing"). NONE = everything follows the VPN. */
    var routingRegion: RoutingRegion
        get() = RoutingRegion.entries.firstOrNull { it.name == prefs.getString(KEY_ROUTING_REGION, null) } ?: RoutingRegion.NONE
        set(value) { prefs.edit().putString(KEY_ROUTING_REGION, value.name).apply() }

    /** DNS used only to resolve hostnames the core itself needs (edge, upstream proxy, DoH servers). */
    var bootstrapDohProvider: DohProvider
        get() = DohProvider.entries.firstOrNull { it.name == prefs.getString(KEY_BOOTSTRAP_DOH, null) } ?: DohProvider.AUTOMATIC
        set(value) { prefs.edit().putString(KEY_BOOTSTRAP_DOH, value.name).apply() }

    var latencyProbeUrl: String
        get() = (prefs.getString(KEY_LATENCY_URL, null) ?: DEFAULT_LATENCY_PROBE_URL).let { if (isValidProbeUrl(it)) it else DEFAULT_LATENCY_PROBE_URL }
        set(value) {
            val v = value.trim().ifEmpty { DEFAULT_LATENCY_PROBE_URL }
            if (!isValidProbeUrl(v)) return   // invalid input never replaces the last valid value
            prefs.edit().putString(KEY_LATENCY_URL, v).apply()
        }

    var automaticRecovery: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECOVERY, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_RECOVERY, value).apply() }

    enum class SignInMethod { DIRECT, BROWSER }

    /** Regions with a bundled Direct-routing ruleset (IPdeny zone + curated suffixes). */
    enum class RoutingRegion { NONE, IR, CN, RU, AF, TR, ID, BR }

    enum class SplitTunnelMode { EXCLUDE, INCLUDE }

    enum class IpMode(val hasIpv4: Boolean, val hasIpv6: Boolean, val dnsStrategy: String?) {
        DUAL_STACK(true, true, null),
        PREFER_IPV4(true, true, "prefer_ipv4"),
        PREFER_IPV6(true, true, "prefer_ipv6"),
        IPV4_ONLY(true, false, "ipv4_only"),
        IPV6_ONLY(false, true, "ipv6_only"),
    }

    enum class HttpVersion(val versionNumber: Int) { HTTP_1(1), HTTP_2(2), HTTP_3(3) }

    enum class TlsFragmentMode { OFF, RECORD, PACKET, COMBINED }

    enum class UtlsFingerprint(val identifier: String) {
        FIREFOX("firefox"), CHROME("chrome"), SAFARI("safari"), IOS("ios"), EDGE("edge"), RANDOMIZED("randomized"),
    }

    enum class UpstreamProxyType { SOCKS5, HTTP }

    enum class DohProvider(val label: String) {
        AUTOMATIC("Automatic"),
        CLOUDFLARE("Cloudflare"),
        GOOGLE("Google"),
        QUAD9("Quad9"),
        OFF("Off (use the network's resolver)"),
        ;

        val addresses: List<String>
            get() = when (this) {
                AUTOMATIC -> listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
                CLOUDFLARE -> listOf("1.1.1.1", "1.0.0.1")
                GOOGLE -> listOf("8.8.8.8", "8.8.4.4")
                QUAD9 -> listOf("9.9.9.9", "149.112.112.112")
                OFF -> emptyList()
            }
    }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_SIGN_IN_METHOD = "sign_in_method"
        private const val KEY_EXIT_CHECK = "exit_check_enabled"
        private const val KEY_TUNNEL_MTU = "tunnel_mtu"
        private const val KEY_SOCKS_BIND_ADDRESS = "socks_bind_address"
        private const val KEY_SOCKS_PORT = "socks_port"
        private const val KEY_DOH_PROVIDER = "doh_provider"
        private const val KEY_CUSTOM_DNS_ENABLED = "custom_dns_enabled"
        private const val KEY_CUSTOM_DNS_SERVER = "custom_dns_server"
        private const val KEY_PROXY_ONLY_MODE = "proxy_only_mode"
        private const val KEY_CUSTOM_EDGE_ADDRESS = "custom_edge_address"
        private const val KEY_UPSTREAM_PROXY_ENABLED = "upstream_proxy_enabled"
        private const val KEY_UPSTREAM_PROXY_TYPE = "upstream_proxy_type"
        private const val KEY_UPSTREAM_PROXY_HOST = "upstream_proxy_host"
        private const val KEY_UPSTREAM_PROXY_PORT = "upstream_proxy_port"
        private const val KEY_UPSTREAM_PROXY_USERNAME = "upstream_proxy_username"
        private const val KEY_UPSTREAM_PROXY_PASSWORD = "upstream_proxy_password"
        private const val KEY_EXCLUDED_APPS = "excluded_apps"
        private const val KEY_SHARED_RELAY_ENABLED = "relay_shared_enabled"
        private const val KEY_PERSONAL_RELAY_ENABLED = "relay_personal_enabled"
        private const val KEY_PERSONAL_RELAY_ONLY = "relay_personal_only"
        private const val KEY_PERSONAL_RELAY_URL = "relay_personal_url"
        private const val KEY_SHARED_RELAY_SECRET = "relay_shared_secret"
        private const val KEY_MANIFEST_PRIMARY_URL_OVERRIDE = "manifest_primary_url_override"
        private const val KEY_MANIFEST_FALLBACK_URL_OVERRIDE = "manifest_fallback_url_override"
        private const val KEY_BOOTSTRAP_DOH = "bootstrap_doh_provider"
        private const val KEY_LATENCY_URL = "latency_probe_url"
        private const val KEY_AUTO_RECOVERY = "automatic_recovery"
        const val DEFAULT_LATENCY_PROBE_URL = "https://www.gstatic.com/generate_204"
        private const val KEY_SPLIT_TUNNEL_MODE = "split_tunnel_mode"
        private const val KEY_INCLUDED_APPS = "included_apps"
        private const val KEY_IP_MODE = "ip_mode"
        private const val KEY_HTTP_VERSION = "http_version"
        private const val KEY_TLS_FRAGMENT_MODE = "tls_fragment_mode"
        private const val KEY_FRAGMENT_FALLBACK_DELAY = "fragment_fallback_delay_ms"
        private const val KEY_UTLS_FINGERPRINT = "utls_fingerprint"
        private const val KEY_MAX_CONCURRENT_STREAMS = "max_concurrent_streams"
        private const val KEY_BYPASS_LOCAL_NETWORK = "bypass_local_network"
        private const val KEY_ROUTING_REGION = "routing_region"
        const val DEFAULT_FRAGMENT_FALLBACK_DELAY_MS = 100
        const val DEFAULT_MAX_CONCURRENT_STREAMS = 32
        const val DEFAULT_TUNNEL_MTU = 1280
        const val MIN_TUNNEL_MTU = 1280
        const val MAX_TUNNEL_MTU = 9000
        const val DEFAULT_SOCKS_BIND_ADDRESS = "127.0.0.1"
        const val DEFAULT_SOCKS_PORT = 1080
        const val DEFAULT_UPSTREAM_PROXY_PORT = 1080
        const val DEFAULT_CUSTOM_DNS_SERVER = "1.1.1.1"

        val SOCKS_BIND_ADDRESS_PRESETS = listOf(
            "127.0.0.1" to "Loopback only (127.0.0.1)",
            "0.0.0.0" to "All interfaces (0.0.0.0)",
        )

        val CUSTOM_DNS_PRESETS = listOf(
            "1.1.1.1" to "Cloudflare (1.1.1.1)",
            "8.8.8.8" to "Google (8.8.8.8)",
            "9.9.9.9" to "Quad9 (9.9.9.9)",
        )

        fun isValidIpAddress(value: String): Boolean {
            return if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.net.InetAddresses.isNumericAddress(value)
            } else {
                @Suppress("DEPRECATION")
                android.util.Patterns.IP_ADDRESS.matcher(value).matches()
            }
        }

        fun isValidDnsServer(value: String): Boolean {
            return isValidIpAddress(value)
        }

        fun isValidHostname(value: String): Boolean {
            val host = value.trim().removeSuffix(".")
            if (host.isEmpty() || host.length > 253) return false

            if (host.contains(':')) return false
            if (host.all { it.isDigit() || it == '.' }) return false
            val labels = host.split('.')
            if (labels.size < 2) return false
            return labels.all { label ->
                label.isNotEmpty() &&
                    label.length <= 63 &&
                    !label.startsWith('-') &&
                    !label.endsWith('-') &&
                    label.all { char ->
                        char == '-' || (char.code < 128 && (char.isLetterOrDigit()))
                    }
            }
        }


        fun isValidProbeUrl(value: String): Boolean {
            val v = value.trim()
            if (v.length > 512 || !v.startsWith("https://")) return false  // https only: no cleartext probes
            val host = runCatching { java.net.URI(v).host }.getOrNull() ?: return false
            return host.isNotBlank() && java.net.URI(v).userInfo == null
        }

        fun isValidProxyHost(value: String): Boolean = isValidEdgeHost(value) || value.trim() == "localhost"

        fun isLocalProxyLoop(host: String, port: Int, localPort: Int): Boolean {
            val h = host.trim().lowercase()
            val local = h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "0.0.0.0"
            return local && port == localPort
        }

        fun isValidEdgeHost(value: String): Boolean {
            val host = value.trim().removeSuffix(".")
            return isValidHostname(host) || isValidIpAddress(host)
        }
    }
}
