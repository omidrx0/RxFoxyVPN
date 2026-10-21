package com.vauth.rxfoxyvpn.vpn

object ConnectionSession {
    @Volatile var connectedAtMillis = 0L
    @Volatile var lastTotalBytes = 0L
    @Volatile var txRate = 0L
    @Volatile var rxRate = 0L

    fun elapsedSeconds(): Long =
        if (connectedAtMillis == 0L) 0L
        else ((System.currentTimeMillis() - connectedAtMillis) / 1000L).coerceAtLeast(0L)

    fun clear() {
        connectedAtMillis = 0L
        lastTotalBytes = 0L
        txRate = 0L
        rxRate = 0L
    }
}
