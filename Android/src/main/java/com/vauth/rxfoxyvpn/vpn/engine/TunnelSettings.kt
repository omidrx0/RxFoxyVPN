package com.vauth.rxfoxyvpn.vpn.engine

import com.vauth.rxfoxyvpn.data.SettingsStore

/** Immutable snapshot of everything the config builder reads, taken once per connect attempt. */
data class TunnelSettings(
    val mtu: Int,
    val ipMode: SettingsStore.IpMode,
    val httpVersion: SettingsStore.HttpVersion,
    val tlsFragmentMode: SettingsStore.TlsFragmentMode,
    val fragmentFallbackDelayMs: Int,
    val utlsFingerprint: SettingsStore.UtlsFingerprint,
    val maxConcurrentStreams: Int,
    val bypassLocalNetwork: Boolean,
    val splitTunnelMode: SettingsStore.SplitTunnelMode,
    val excludedApps: Set<String>,
    val includedApps: Set<String>,
    val proxyOnlyMode: Boolean,
    val socksBindAddress: String,
    val socksPort: Int,
    val customDnsServer: String?,
    val dohProvider: SettingsStore.DohProvider,
    val bootstrapDohProvider: SettingsStore.DohProvider,
    val automaticRecovery: Boolean,
    val latencyProbeUrl: String,
    val effectiveCustomEdgeAddress: String?,
    val upstreamProxyEnabled: Boolean,
    val upstreamProxyType: SettingsStore.UpstreamProxyType,
    val upstreamProxyHost: String,
    val upstreamProxyPort: Int,
    val upstreamProxyUsername: String,
    val upstreamProxyPassword: String,
    val routingRegion: SettingsStore.RoutingRegion,
    val routingDomainSuffixes: List<String>,
    val routingIpCidrs: List<String>,
) {
    // Never print credentials.
    override fun toString() = "TunnelSettings(proxyOnly=$proxyOnlyMode, http=$httpVersion, ip=$ipMode, frag=$tlsFragmentMode)"
}

fun SettingsStore.tunnelSnapshot(): TunnelSettings = tunnelSnapshot(null)

/** Snapshot used to build the config. Pass a Context to bundle the routing ruleset. */
fun SettingsStore.tunnelSnapshot(context: android.content.Context?): TunnelSettings {
    val region = routingRegion
    val suffixes = com.vauth.rxfoxyvpn.data.routing.RoutingPolicy.suffixesFor(region)
    val cidrs = context?.let { com.vauth.rxfoxyvpn.data.routing.RoutingPolicy.cidrsFor(it, region) } ?: emptyList()
    val upstream = upstreamProxyEnabled && upstreamProxyHost.isNotBlank()
    return TunnelSettings(
        mtu = tunnelMtu,
        ipMode = ipMode,
        httpVersion = httpVersion,
        tlsFragmentMode = tlsFragmentMode,
        fragmentFallbackDelayMs = fragmentFallbackDelayMs,
        utlsFingerprint = utlsFingerprint,
        maxConcurrentStreams = maxConcurrentStreams,
        bypassLocalNetwork = bypassLocalNetwork,
        splitTunnelMode = splitTunnelMode,
        excludedApps = excludedApps.toSet(),
        includedApps = includedApps.toSet(),
        proxyOnlyMode = proxyOnlyMode,
        socksBindAddress = socksBindAddress,
        socksPort = socksPort,
        customDnsServer = effectiveCustomDnsServer,
        dohProvider = dohProvider,
        bootstrapDohProvider = bootstrapDohProvider,
        automaticRecovery = automaticRecovery,
        latencyProbeUrl = latencyProbeUrl,
        effectiveCustomEdgeAddress = effectiveCustomEdgeAddress,
        upstreamProxyEnabled = upstream,
        upstreamProxyType = if (upstream) upstreamProxyType else SettingsStore.UpstreamProxyType.SOCKS5,
        upstreamProxyHost = if (upstream) upstreamProxyHost else "",
        upstreamProxyPort = if (upstream) upstreamProxyPort else SettingsStore.DEFAULT_UPSTREAM_PROXY_PORT,
        upstreamProxyUsername = if (upstream) upstreamProxyUsername else "",
        upstreamProxyPassword = if (upstream) upstreamProxyPassword else "",
        routingRegion = region,
        routingDomainSuffixes = suffixes,
        routingIpCidrs = cidrs,
    )
}
