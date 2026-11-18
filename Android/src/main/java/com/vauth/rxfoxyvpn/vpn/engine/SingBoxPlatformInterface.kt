package com.vauth.rxfoxyvpn.vpn.engine

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import com.vauth.rxfoxyvpn.BuildConfig
import com.vauth.rxfoxyvpn.data.AppLogger
import io.nekohasekai.libbox.AutoRedirectHandler
import io.nekohasekai.libbox.AutoRedirectSession
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface

internal class SingBoxPlatformInterface(
    private val host: EngineHost,
    private val includeOnly: Boolean,
) : PlatformInterface, CommandServerHandler {

    private val vpnService: android.net.VpnService get() = host.vpnService

    companion object {
        private const val TAG = "SingBoxPlatform"

        // Prefix-only matching avoids false positives from words inside log messages.
        private val LOG_LEVEL_REGEX = Regex(
            """^\s*\[?(TRACE|DEBUG|INFO|WARN(?:ING)?|ERROR|FATAL|PANIC)(?:\]|\b)""",
            RegexOption.IGNORE_CASE,
        )
        private val ANSI_ESCAPE_REGEX = Regex("""\u001B\[[;\d]*m""")
    }

    @Volatile private var vpnInterface: ParcelFileDescriptor? = null

    fun closeTun() {
        runCatching { vpnInterface?.close() }
            .onFailure { AppLogger.w(TAG, "error closing VPN ParcelFileDescriptor", it) }
        vpnInterface = null
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {

        if (!host.nativeProtectionRequired) return
        val protected = vpnService.protect(fd)
        if (!protected) {
            throw java.io.IOException("VPN refused native socket protection")
        }
    }

    override fun openTun(options: TunOptions): Int {
        AppLogger.i(TAG, "openTun called with MTU=${options.mtu}")
        val builder =
            vpnService.Builder().setSession("RxFoxyVPN").setMtu(options.mtu).setBlocking(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        var hasIpv4 = false
        var hasIpv6 = false

        val inet4 = options.inet4Address
        if (inet4 != null) {
            while (inet4.hasNext()) {
                val prefix = inet4.next()
                builder.addAddress(prefix.address(), prefix.prefix())
                hasIpv4 = true
            }
        }

        val inet6 = options.inet6Address
        if (inet6 != null) {
            while (inet6.hasNext()) {
                val prefix = inet6.next()
                builder.addAddress(prefix.address(), prefix.prefix())
                hasIpv6 = true
            }
        }

        if (!hasIpv4 && !hasIpv6) {
            builder.addAddress("172.19.0.1", 30)
            hasIpv4 = true
        }

        if (options.autoRoute) {
            if (hasIpv4) {
                val routes = options.inet4RouteRange
                require(routes != null && routes.hasNext()) { "Missing IPv4 tunnel routes" }
                while (routes.hasNext()) {
                    val prefix = routes.next()
                    builder.addRoute(prefix.address(), prefix.prefix())
                }
            }
            if (hasIpv6) {
                val routes = options.inet6RouteRange
                require(routes != null && routes.hasNext()) { "Missing IPv6 tunnel routes" }
                while (routes.hasNext()) {
                    val prefix = routes.next()
                    builder.addRoute(prefix.address(), prefix.prefix())
                }
            }
            var addedDns = false
            val dnsIter = runCatching { options.dnsServerAddress }.getOrNull()
            if (dnsIter != null) {
                while (dnsIter.hasNext()) {
                    val dns = dnsIter.next()
                    if (dns.isNotBlank()) {
                        builder.addDnsServer(dns)
                        builder.addRoute(dns, if (':' in dns) 128 else 32)
                        addedDns = true
                    }
                }
            }
            if (!addedDns) {
                if (hasIpv4) {
                    builder.addDnsServer("172.19.0.2")
                    builder.addRoute("172.19.0.2", 32)
                } else if (hasIpv6) {
                    builder.addDnsServer("fdfe:dcba:9876::2")
                    builder.addRoute("fdfe:dcba:9876::2", 128)
                }
            }
        }

        if (includeOnly) {
            val included = options.includePackage
            var allowedCount = 0
            while (included != null && included.hasNext()) {
                val pkg = included.next()
                if (pkg.isBlank() || pkg == host.appPackageName) continue
                try {
                    builder.addAllowedApplication(pkg)
                    allowedCount++
                } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                    AppLogger.w(TAG, "included app is no longer installed: $pkg")
                }
            }

            require(allowedCount > 0) {
                "No included apps are available. Update split tunneling settings."
            }
        } else {
            val excluded = options.excludePackage
            val packages = mutableSetOf(host.appPackageName)
            while (excluded != null && excluded.hasNext()) {
                excluded.next().takeIf { it.isNotBlank() }?.let { packages.add(it) }
            }
            packages.forEach { pkg ->
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                    AppLogger.w(TAG, "excluded app is no longer installed: $pkg")
                }
            }
        }

        vpnInterface?.close()
        val pfd =
            builder.establish()
                ?: error(
                    "Failed to establish Android VPN interface (Builder.establish() returned null)"
                )
        vpnInterface = pfd
        val fd = pfd.fd
        host.onTunOpened()
        return fd
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cm = vpnService.getSystemService(ConnectivityManager::class.java)
            if (cm != null) {
                val uid =
                    runCatching {
                            cm.getConnectionOwnerUid(
                                ipProtocol,
                                InetSocketAddress(sourceAddress, sourcePort),
                                InetSocketAddress(destinationAddress, destinationPort),
                            )
                        }
                        .getOrDefault(Process.INVALID_UID)
                if (uid != Process.INVALID_UID) {
                    val packages =
                        runCatching { vpnService.packageManager.getPackagesForUid(uid) }.getOrNull()
                    val owner = ConnectionOwner()
                    owner.userId = uid
                    owner.userName = packages?.firstOrNull() ?: ""
                    return owner
                }
            }
        }
        throw java.io.FileNotFoundException("connection owner not found")
    }

    @Volatile private var defaultInterfaceListener: InterfaceUpdateListener? = null

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        defaultInterfaceListener = listener
        notifyDefaultInterface()
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        if (defaultInterfaceListener == listener) {
            defaultInterfaceListener = null
        }
    }

    fun notifyDefaultInterface() {
        val listener = defaultInterfaceListener ?: return
        val cm = vpnService.getSystemService(ConnectivityManager::class.java) ?: return
        val activeNet = host.firstUsableNetwork()
        if (activeNet == null) {

            listener.updateDefaultInterface("", -1, false, false)
            return
        }
        val caps = cm.getNetworkCapabilities(activeNet) ?: return
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
        val lp = cm.getLinkProperties(activeNet) ?: return
        val ifaceName = lp.interfaceName ?: return
        val nif =
            runCatching { NetworkInterface.getByName(ifaceName) }.getOrNull()
                ?: runCatching {
                        NetworkInterface.getNetworkInterfaces()?.toList()?.find {
                            it.name == ifaceName
                        }
                    }
                    .getOrNull()
        val index = nif?.index ?: return
        val isExpensive = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val isConstrained =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
        AppLogger.d(
            TAG,
            "reporting default interface to sing-box: $ifaceName index=$index expensive=$isExpensive",
        )
        runCatching {
                listener.updateDefaultInterface(ifaceName, index, isExpensive, isConstrained)
            }
            .onFailure { AppLogger.w(TAG, "updateDefaultInterface failed", it) }
    }

    @Suppress("DEPRECATION")
    override fun getInterfaces(): NetworkInterfaceIterator {
        val cm =
            vpnService.getSystemService(ConnectivityManager::class.java)
                ?: return emptyNetworkInterfaceIterator()
        val networks = runCatching { cm.allNetworks }.getOrNull() ?: emptyArray()
        val networkInterfaces =
            runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        val interfaces = mutableListOf<LibboxNetworkInterface>()

        for (network in networks) {
            val linkProperties = cm.getLinkProperties(network) ?: continue
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val ifaceName = linkProperties.interfaceName ?: continue
            val nif = networkInterfaces.find { it.name == ifaceName } ?: continue

            val boxInterface =
                LibboxNetworkInterface().apply {
                    name = ifaceName
                    index = nif.index
                    dnsServer = StringArray(linkProperties.dnsServers.mapNotNull { it.hostAddress })
                    gateway =
                        StringArray(
                            linkProperties.routes
                                .filter { it.destination?.prefixLength == 0 }
                                .mapNotNull { it.gateway }
                                .filterNot { it.isAnyLocalAddress }
                                .mapNotNull { it.hostAddress }
                        )
                    type =
                        when {
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                                Libbox.InterfaceTypeWIFI
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                                Libbox.InterfaceTypeCellular
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                                Libbox.InterfaceTypeEthernet
                            else -> Libbox.InterfaceTypeOther
                        }
                    mtu = runCatching { nif?.mtu }.getOrNull() ?: 1500
                    addresses =
                        StringArray(
                            nif?.interfaceAddresses?.mapNotNull { addr ->
                                runCatching {
                                        if (addr.address is Inet6Address) {
                                            "${Inet6Address.getByAddress(addr.address.address).hostAddress}/${addr.networkPrefixLength}"
                                        } else {
                                            "${addr.address.hostAddress}/${addr.networkPrefixLength}"
                                        }
                                    }
                                    .getOrNull()
                            } ?: linkProperties.linkAddresses.map { it.toString() }
                        )
                    var dumpFlags = 0
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        dumpFlags = dumpFlags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                    }
                    if (nif?.isLoopback == true) dumpFlags = dumpFlags or OsConstants.IFF_LOOPBACK
                    if (nif?.isPointToPoint == true)
                        dumpFlags = dumpFlags or OsConstants.IFF_POINTOPOINT
                    if (runCatching { nif?.supportsMulticast() == true }.getOrDefault(false)) {
                        dumpFlags = dumpFlags or OsConstants.IFF_MULTICAST
                    }
                    flags = dumpFlags
                    metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
            interfaces.add(boxInterface)
        }

        return object : NetworkInterfaceIterator {
            private val iter = interfaces.iterator()

            override fun hasNext(): Boolean = iter.hasNext()

            override fun next(): LibboxNetworkInterface = iter.next()
        }
    }

    private fun emptyNetworkInterfaceIterator() =
        object : NetworkInterfaceIterator {
            override fun hasNext(): Boolean = false

            override fun next(): LibboxNetworkInterface = error("no network interfaces")
        }

    private class StringArray(private val list: List<String>) : StringIterator {
        private val iterator = list.iterator()

        override fun len(): Int = list.size

        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): String = if (iterator.hasNext()) iterator.next() else ""
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState {
        val wm = vpnService.getSystemService(android.net.wifi.WifiManager::class.java)
        @Suppress("DEPRECATION") val info = runCatching { wm?.connectionInfo }.getOrNull()
        var ssid = info?.ssid ?: ""
        if (ssid == "<unknown ssid>") ssid = ""
        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length >= 2) {
            ssid = ssid.substring(1, ssid.length - 1)
        }
        return WIFIState(ssid, info?.bssid ?: "")
    }

    override fun clearDNSCache() {}

    override fun sendNotification(notification: Notification) {}

    override fun cancelNotification(identifier: String, typeID: Int) {}

    override fun startNeighborMonitor(listener: NeighborUpdateListener) {}

    override fun closeNeighborMonitor(listener: NeighborUpdateListener) {}

    override fun registerMyInterface(name: String) {}

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {}

    override fun openShellSession(
        user: PlatformUser,
        command: String,
        environ: StringIterator,
        term: String,
        rows: Int,
        cols: Int,
    ): ShellSession = error("shell session not supported")

    override fun lookupUser(username: String): PlatformUser = error("user lookup not supported")

    override fun lookupSFTPServer(): String = error("sftp not supported")

    override fun readSystemSSHHostKey(): String = error("ssh not supported")

    override fun tailscaleHostname(): String = ""

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions): BridgeSession = error("bridge not supported")

    override fun usePlatformAutoRedirect(): Boolean = false

    override fun createAutoRedirect(
        options: ByteArray,
        handler: AutoRedirectHandler,
    ): AutoRedirectSession = error("auto-redirect not supported")

    override fun localDNSTransport(): LocalDNSTransport =
        object : LocalDNSTransport {
            override fun raw(): Boolean = false

            override fun exchange(ctx: ExchangeContext, message: ByteArray) {
                ctx.errorCode(4)
            }

            override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
                val cleanDomain = domain.trimEnd('.')
                try {
                    val activeNet = host.firstUsableNetwork()
                    if (activeNet == null) {
                        ctx.errorCode(2)
                        return
                    }
                    val addresses = activeNet.getAllByName(cleanDomain)
                    val filtered =
                        when (network) {
                            "ip4" -> addresses.filterIsInstance<Inet4Address>()
                            "ip6" -> addresses.filterIsInstance<Inet6Address>()
                            else -> addresses.toList()
                        }
                    ctx.success(filtered.joinToString("\n") { it.hostAddress.orEmpty() })
                } catch (e: Exception) {
                    AppLogger.d(TAG, "system lookup failed for $cleanDomain: ${e.message}")
                    ctx.errorCode(2)
                }
            }
        }

    override fun serviceStop() {
        host.onCoreRequestedStop()
    }

    override fun serviceReload(): Unit =
        throw UnsupportedOperationException("Reconnect through RXFoxyVPN to reload settings")

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()

    override fun setSystemProxyEnabled(enabled: Boolean) {}

    override fun triggerNativeCrash() {}

    override fun writeDebugMessage(message: String) {
        // libbox sends formatted log lines through this callback. Do not infer severity
        // by searching the whole message: words such as "error" may appear in ordinary
        // text, and callback origin does not itself mean ERROR.
        val clean = ANSI_ESCAPE_REGEX.replace(message, "").trim()
        if (clean.isEmpty()) return

        val match = LOG_LEVEL_REGEX.find(clean)
        val level = match?.groupValues?.getOrNull(1)?.uppercase()
        val body = if (match != null) {
            clean.removeRange(match.range).trimStart(':', ' ', '\t', '-', ']')
        } else {
            clean
        }

        // Drop high-volume per-connection / DNS chatter so the in-app log stays readable.
        // Keep lifecycle, routing policy changes, and real failures.
        if (isNoisySingBoxLine(body)) return

        val formatted = "sing-box: $body"

        // Some libbox "ERROR" lines are routine for individual app flows (peer reset a
        // short-lived connection, or a non-HTTP port got 502 from the HTTP proxy).
        // The VPN tunnel itself is fine; do not surface these as user-visible errors.
        if (level == "ERROR" && isBenignConnectionError(body)) {
            AppLogger.d(TAG, formatted)
            return
        }

        when (level) {
            "TRACE", "DEBUG" -> {
                // Verbose core diagnostics stay out of the user-visible log buffer.
                AppLogger.d(TAG, "[$level] $formatted")
            }
            "INFO" -> AppLogger.i(TAG, formatted)
            "WARN", "WARNING" -> AppLogger.w(TAG, formatted)
            "ERROR", "FATAL", "PANIC" -> AppLogger.e(TAG, formatted)
            else -> {
                // Unknown formats are not errors; do not pollute the in-app log.
                AppLogger.d(TAG, "[UNPARSED] $formatted")
            }
        }
    }

    /**
     * True for routine per-flow lines that flood the log within seconds of normal use
     * (every DNS query, every TCP open/close, router match dumps).
     */
    private fun isNoisySingBoxLine(body: String): Boolean {
        val b = body
        // Connection lifecycle spam
        if (b.contains("inbound connection from")) return true
        if (b.contains("inbound connection to")) return true
        if (b.contains("inbound packet connection")) return true
        if (b.contains("inbound DNS packet")) return true
        if (b.contains("outbound connection to")) return true
        if (b.contains("outbound packet connection")) return true
        if (b.contains("connection upload finished")) return true
        if (b.contains("connection download finished")) return true
        if (b.contains("connection upload closed")) return true
        if (b.contains("connection download closed")) return true
        // Router / process attribution on every flow
        if (b.contains("router: found user")) return true // "found user:" and "found user id:"
        if (b.contains("router: match[")) return true
        if (b.contains("router: pre-match[")) return true
        if (b.contains("failed to search process")) return true
        // DNS query chatter
        if (b.contains("dns: exchange ")) return true
        if (b.contains("dns: exchanged ")) return true
        if (b.contains("dns: cached ")) return true
        if (b.contains("dns: lookup domain")) return true
        if (b.contains("dns: lookup succeed")) return true
        // Network environment noise (often repeats on cellular)
        if (b.contains("updated available networks:")) return true
        if (b.contains("updated default interface")) return true
        if (b.contains("updated network environment:")) return true
        return false
    }

    /**
     * Per-app stream failures that do not mean the VPN tunnel is down.
     * Example: Meta/Instagram cancels a probe; messaging apps hit HTTP CONNECT 502 on 5222.
     */
    private fun isBenignConnectionError(body: String): Boolean {
        if (body.contains("connection reset by peer")) return true
        if (body.contains("report handshake success")) return true
        // Non-HTTP ports through HTTP proxy often get 502; app will retry another path.
        if (body.contains("unexpected status: 502") && body.contains("proxy-out")) return true
        if (body.contains("unexpected status: 503") && body.contains("proxy-out")) return true
        return false
    }


    override fun connectSSHAgent(): Int = -1
}
