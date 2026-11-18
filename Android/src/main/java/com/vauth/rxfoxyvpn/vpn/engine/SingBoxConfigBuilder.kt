package com.vauth.rxfoxyvpn.vpn.engine

import com.vauth.rxfoxyvpn.BuildConfig
import com.vauth.rxfoxyvpn.data.SettingsStore
import com.vauth.rxfoxyvpn.data.model.ProxyCandidate
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the sing-box JSON for RXFoxyVPN.
 *
 * The proxy pass is NOT part of the JSON: the outbound uses `dynamic_bearer_token` and the credential is handed to the
 * core through Libbox.setBearerToken, so it never touches disk or logs and can be rotated without a restart.
 * No Clash/controller API is configured; statistics use libbox's in-process command channel.
 */
object SingBoxConfigBuilder {

    const val PROXY_TAG = "proxy-out"
    const val DIRECT_TAG = "direct-out"
    const val UPSTREAM_TAG = "upstream-detour"

    private val localV4 = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16")
    private val localV6 = listOf("fc00::/7", "fe80::/10")

    private const val TUN_V4 = "172.19.0.1/30"
    private const val TUN_V6 = "fdfe:dcba:9876::1/126"
    private const val TUN_DNS_V4 = "172.19.0.2"
    private const val TUN_DNS_V6 = "fdfe:dcba:9876::2"

    private fun validate(candidate: ProxyCandidate, s: TunnelSettings) {
        require(candidate.port in 1..65_535 && SettingsStore.isValidEdgeHost(candidate.host)) { "Invalid VPN server address" }
        require(s.socksPort in 1..65_535) { "Invalid local proxy port" }
        require(s.socksBindAddress == SettingsStore.DEFAULT_SOCKS_BIND_ADDRESS || s.socksBindAddress == "0.0.0.0") {
            "Local proxy listen address must be 127.0.0.1 or 0.0.0.0"
        }
        require(!s.ipMode.hasIpv6 || s.mtu >= 1280) { "IPv6 requires a tunnel MTU of at least 1280" }
        if (s.upstreamProxyEnabled) {
            require(SettingsStore.isValidProxyHost(s.upstreamProxyHost) && s.upstreamProxyPort in 1..65_535) {
                "Set the upstream proxy address before connecting"
            }
            require(!SettingsStore.isLocalProxyLoop(s.upstreamProxyHost, s.upstreamProxyPort, s.socksPort)) {
                "Upstream proxy must not point to RXFoxyVPN's own listener"
            }
            require(s.upstreamProxyType != SettingsStore.UpstreamProxyType.HTTP || s.httpVersion != SettingsStore.HttpVersion.HTTP_3) {
                "HTTP/3 (QUIC) cannot be chained over an upstream HTTP proxy"
            }
            require(s.upstreamProxyType != SettingsStore.UpstreamProxyType.SOCKS5 || s.httpVersion != SettingsStore.HttpVersion.HTTP_3) {
                "HTTP/3 (QUIC) cannot be chained over an upstream SOCKS5 proxy"
            }
        }
        if (s.splitTunnelMode == SettingsStore.SplitTunnelMode.INCLUDE && !s.proxyOnlyMode) {
            require(s.includedApps.any { it.isNotBlank() }) { "Select at least one app to include in the VPN" }
        }
    }

    private fun dohOrigin(ip: String) = ip

    fun build(candidate: ProxyCandidate, s: TunnelSettings, appPackageName: String): String {
        validate(candidate, s)
        val localNets = buildList {
            if (s.ipMode.hasIpv4) addAll(localV4)
            if (s.ipMode.hasIpv6) addAll(localV6)
        }
        val root = JSONObject()
        // Keep the core quiet: only warn/error from sing-box in release.
        // Debug builds use "info" (not "debug/trace") so routine per-flow lines are not emitted.
        root.put(
            "log",
            JSONObject()
                .put("level", if (BuildConfig.DEBUG) "info" else "warn")
                .put("timestamp", true),
        )
        root.put("certificate", JSONObject().put("store", "mozilla"))

        // ---- DNS ----
        val servers = JSONArray()
        servers.put(JSONObject().put("type", "local").put("tag", "local-dns"))
        val bootstrapIp = s.bootstrapDohProvider.addresses.firstOrNull()
        val remoteIp = s.dohProvider.addresses.firstOrNull()
        val bootstrapTag = if (bootstrapIp != null) {
            servers.put(
                JSONObject().put("type", "https").put("tag", "bootstrap-dns").put("server", dohOrigin(bootstrapIp)),
            )
            "bootstrap-dns"
        } else "local-dns"
        val finalDns = when {
            s.customDnsServer != null -> {
                // Plain DNS over TCP through the tunnel: UDP cannot ride an HTTP CONNECT outbound.
                servers.put(
                    JSONObject().put("type", "tcp").put("tag", "remote-dns").put("server", s.customDnsServer)
                        .put("detour", PROXY_TAG),
                )
                "remote-dns"
            }
            remoteIp != null -> {
                servers.put(
                    JSONObject().put("type", "https").put("tag", "remote-dns").put("server", remoteIp)
                        .put("detour", PROXY_TAG),
                )
                "remote-dns"
            }
            else -> "local-dns"
        }
        val dns = JSONObject().put("servers", servers).put("final", finalDns).put("disable_cache", false)
        if (s.routingDomainSuffixes.isNotEmpty()) {
            // Region domains must resolve like any Direct connection (system resolver),
            // not through the tunnel's upstream DoH, or the bypass is undone by DNS.
            dns.put(
                "rules",
                JSONArray().put(
                    JSONObject().put("domain_suffix", JSONArray(s.routingDomainSuffixes)).put("server", "local-dns"),
                ),
            )
        }
        s.ipMode.dnsStrategy?.let { dns.put("strategy", it) }
        root.put("dns", dns)

        root.put(
            "http_clients",
            JSONArray().put(
                JSONObject().put("tag", "default-http-client").put("engine", "go").put("version", 2)
                    .put("tls", JSONObject().put("utls", JSONObject().put("enabled", true).put("fingerprint", s.utlsFingerprint.identifier))),
            ),
        )

        // ---- Inbounds ----
        val inbounds = JSONArray()
        if (!s.proxyOnlyMode) {
            val tun = JSONObject().put("type", "tun").put("tag", "tun-in").put("mtu", s.mtu)
                .put("address", JSONArray().apply { if (s.ipMode.hasIpv4) put(TUN_V4); if (s.ipMode.hasIpv6) put(TUN_V6) })
                .put("dns_address", JSONArray().apply { if (s.ipMode.hasIpv4) put(TUN_DNS_V4); if (s.ipMode.hasIpv6) put(TUN_DNS_V6) })
                .put("auto_route", true).put("strict_route", true)
            if (s.bypassLocalNetwork && localNets.isNotEmpty()) tun.put("route_exclude_address", JSONArray(localNets))
            if (s.splitTunnelMode == SettingsStore.SplitTunnelMode.INCLUDE) {
                tun.put("include_package", JSONArray(s.includedApps.filter { it.isNotBlank() && it != appPackageName }.sorted()))
            } else {
                val excluded = s.excludedApps.filter { it.isNotBlank() }.toMutableSet().apply { add(appPackageName) }
                tun.put("exclude_package", JSONArray(excluded.sorted()))
            }
            tun.put("platform", JSONObject().put("http_proxy", JSONObject().put("enabled", false)))
            inbounds.put(tun)
        }
        // The mixed inbound serves proxy-only mode and the app's own exit check / health probe. With a TUN it is
        // always loopback-only, whatever the saved bind address says.
        val listen = if (s.proxyOnlyMode) s.socksBindAddress else SettingsStore.DEFAULT_SOCKS_BIND_ADDRESS
        inbounds.put(
            JSONObject().put("type", "mixed").put("tag", "mixed-in").put("listen", listen)
                .put("listen_port", s.socksPort).put("reuse_addr", true),
        )
        root.put("inbounds", inbounds)

        // ---- Outbounds ----
        val outbounds = JSONArray()
        outbounds.put(JSONObject().put("type", "direct").put("tag", DIRECT_TAG))
        val v = s.httpVersion.versionNumber
        val hasDetour = s.upstreamProxyEnabled
        val node = JSONObject().put("type", "http").put("tag", PROXY_TAG)
            .put("server", s.effectiveCustomEdgeAddress ?: candidate.host)
            .put("server_port", candidate.port)
            .put("domain_resolver", bootstrapTag)
            .put("dynamic_bearer_token", true)
            .put("version", v)
            .put("disable_version_fallback", true)
        if (v == 2) {
            node.put("max_concurrent_streams", s.maxConcurrentStreams)
                .put("stream_receive_window", "16 MB").put("connection_receive_window", "32 MB")
                .put("idle_timeout", "30s").put("keep_alive_period", "15s")
        }
        val tls = JSONObject().put("enabled", true).put("server_name", candidate.host)
        if (v != 3) {
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", s.utlsFingerprint.identifier))
            val delay = "${s.fragmentFallbackDelayMs}ms"
            when (s.tlsFragmentMode) {
                SettingsStore.TlsFragmentMode.OFF -> {}
                SettingsStore.TlsFragmentMode.RECORD -> tls.put("record_fragment", true)
                SettingsStore.TlsFragmentMode.PACKET -> tls.put("fragment", true).put("fragment_fallback_delay", delay)
                SettingsStore.TlsFragmentMode.COMBINED ->
                    tls.put("fragment", true).put("record_fragment", true).put("fragment_fallback_delay", delay)
            }
        }
        node.put("tls", tls)
        if (hasDetour) node.put("detour", UPSTREAM_TAG)
        outbounds.put(node)

        if (hasDetour) {
            val http = s.upstreamProxyType == SettingsStore.UpstreamProxyType.HTTP
            val d = JSONObject().put("type", if (http) "http" else "socks").put("tag", UPSTREAM_TAG)
                .put("server", s.upstreamProxyHost).put("server_port", s.upstreamProxyPort)
                .put("domain_resolver", bootstrapTag)
            if (http) d.put("dynamic_bearer_token", false).put("version", 1) else d.put("version", "5")
            if (s.upstreamProxyUsername.isNotEmpty()) d.put("username", s.upstreamProxyUsername).put("password", s.upstreamProxyPassword)
            outbounds.put(d)
        }
        root.put("outbounds", outbounds)

        // ---- Route ----
        val rules = JSONArray()
        rules.put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
        rules.put(JSONObject().put("port", 53).put("action", "hijack-dns"))
        rules.put(JSONObject().put("port", 853).put("action", "reject")) // DoT would bypass the hijack
        if (s.bypassLocalNetwork && localNets.isNotEmpty()) {
            rules.put(JSONObject().put("ip_cidr", JSONArray(localNets)).put("outbound", DIRECT_TAG))
        }
        // Direct routing for the selected country: national traffic bypasses the VPN.
        // Rules sit before the FCM remap and the catch-all, after local networks.
        if (s.routingDomainSuffixes.isNotEmpty()) {
            rules.put(
                JSONObject().put("domain_suffix", JSONArray(s.routingDomainSuffixes)).put("outbound", DIRECT_TAG),
            )
        }
        if (s.routingIpCidrs.isNotEmpty()) {
            rules.put(
                JSONObject().put("ip_cidr", JSONArray(s.routingIpCidrs)).put("outbound", DIRECT_TAG),
            )
        }
        if (!s.ipMode.hasIpv6) rules.put(JSONObject().put("ip_version", 6).put("action", "reject"))
        if (!s.ipMode.hasIpv4) rules.put(JSONObject().put("ip_version", 4).put("action", "reject"))
        // Google push (FCM) uses ports 5228-5230; the edge only accepts 443, so remap (keeps notifications working).
        rules.put(
            JSONObject().put("domain", JSONArray().apply { put("mtalk.google.com"); for (i in 1..8) put("alt$i-mtalk.google.com") })
                .put("override_port", 443).put("outbound", PROXY_TAG),
        )
        rules.put(JSONObject().put("port", JSONArray().put(5228).put(5229).put(5230)).put("override_port", 443).put("outbound", PROXY_TAG))
        rules.put(JSONObject().put("outbound", PROXY_TAG))
        root.put(
            "route",
            JSONObject().put("rules", rules).put("final", PROXY_TAG).put("auto_detect_interface", true)
                .put("find_process", false).put("default_domain_resolver", bootstrapTag)
                .put("default_http_client", "default-http-client"),
        )
        root.put("experimental", JSONObject().put("cache_file", JSONObject().put("enabled", false)))
        return root.toString(2)
    }
}
