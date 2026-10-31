package com.vauth.rxfoxyvpn.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

private const val TAG = "ControlPlaneHttp"

private const val FIREFOX_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0"

const val MOZILLA_VPN_USER_AGENT = FIREFOX_USER_AGENT

fun Request.Builder.applyMozillaVpnHeaders(): Request.Builder {
    header("User-Agent", FIREFOX_USER_AGENT)
    header("Accept", "*/*")
    return this
}

/**
 * CookieJar that retains the original OkHttp Cookie object, including domain,
 * host-only, path, secure, httpOnly, expiry and persistence attributes.
 */
class SimpleCookieJar : CookieJar {
    private data class CookieKey(val name: String, val domain: String, val path: String)
    private val cookies = ConcurrentHashMap<CookieKey, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (cookie in cookies) {
            val key = CookieKey(cookie.name, cookie.domain, cookie.path)
            if (cookie.expiresAt <= now) this.cookies.remove(key)
            else this.cookies[key] = cookie
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val result = ArrayList<Cookie>()
        for ((key, cookie) in cookies) {
            if (cookie.expiresAt <= now) {
                cookies.remove(key, cookie)
            } else if (cookie.matches(url)) {
                result.add(cookie)
            }
        }
        return result
    }

    /** Install original Cookie objects without rewriting their attributes. */
    fun addAll(values: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (cookie in values) {
            val key = CookieKey(cookie.name, cookie.domain, cookie.path)
            if (cookie.expiresAt <= now) cookies.remove(key)
            else cookies[key] = cookie
        }
    }

    fun snapshotFor(url: HttpUrl): List<Cookie> = loadForRequest(url)
}

private class ProtectingSocketFactory : SocketFactory() {

    private fun protect(socket: Socket, localAddress: InetSocketAddress?): Socket {

        runCatching {
            if (!socket.isBound) socket.bind(localAddress ?: InetSocketAddress(0))
        }.onFailure {
            AppLogger.w(TAG, "could not bind a control-plane socket before protecting it", it)
        }
        val protector = ControlPlaneHttp.socketProtector
        if (protector != null) {
            val protectedOk = runCatching { protector.invoke(socket) }.getOrDefault(false)
            if (!protectedOk) {
                AppLogger.w(
                    TAG,
                    "VpnService.protect() refused a control-plane socket; this request would be routed " +
                        "back into the tunnel instead of out to the network",
                )
            }
        }
        return socket
    }

    override fun createSocket(): Socket = protect(Socket(), null)

    override fun createSocket(host: String, port: Int): Socket =
        protect(Socket(), null).apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localAddr: InetAddress, localPort: Int): Socket =
        protect(Socket(), InetSocketAddress(localAddr, localPort))
            .apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        protect(Socket(), null).apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: InetAddress, port: Int, localAddr: InetAddress, localPort: Int): Socket =
        protect(Socket(), InetSocketAddress(localAddr, localPort))
            .apply { connect(InetSocketAddress(host, port)) }
}

object ControlPlaneHttp {
    val cookieJar = SimpleCookieJar()

    @Volatile
    var socketProtector: ((Socket) -> Boolean)? = null

    // Use the platform trust store and OkHttp's default hostname verification.
    // The custom socket factory only protects VPN control-plane sockets from
    // being routed back through the VPN tunnel; it does not alter TLS trust.
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .socketFactory(ProtectingSocketFactory())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
