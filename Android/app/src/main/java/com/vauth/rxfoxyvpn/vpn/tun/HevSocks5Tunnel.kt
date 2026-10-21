package com.vauth.rxfoxyvpn.vpn.tun

import com.vauth.rxfoxyvpn.data.AppLogger

private const val TAG = "HevSocks5Tunnel"

object HevSocks5Tunnel {
    @Volatile
    private var isLoaded = false

    init {
        runCatching {
            System.loadLibrary("hev-socks5-tunnel")
            isLoaded = true
        }.onFailure {
            AppLogger.e(TAG, "failed to load hev-socks5-tunnel library", it)
        }
    }

    private external fun TProxyStartService(config_path: String, fd: Int): Boolean
    private external fun TProxyStopService(): Boolean
    private external fun TProxyIsRunning(): Boolean
    private external fun TProxyGetStats(): LongArray

    fun start(configPath: String, tunFd: Int): Boolean {
        if (!isLoaded) return false
        return runCatching { TProxyStartService(configPath, tunFd) }
            .onFailure { AppLogger.e(TAG, "failed to start hev-socks5-tunnel", it) }
            .getOrDefault(false)
    }

    fun stop(): Boolean {
        if (!isLoaded) return false
        return runCatching { TProxyStopService() }
            .onFailure { AppLogger.w(TAG, "failed to stop hev-socks5-tunnel", it) }
            .getOrDefault(false)
    }

    fun isRunning(): Boolean {
        if (!isLoaded) return false
        return runCatching { TProxyIsRunning() }.getOrDefault(false)
    }

    fun stats(): LongArray {
        if (!isLoaded) return LongArray(4)
        return runCatching { TProxyGetStats() }.getOrDefault(LongArray(4))
    }
}
