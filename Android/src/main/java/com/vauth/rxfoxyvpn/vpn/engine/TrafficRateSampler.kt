package com.vauth.rxfoxyvpn.vpn.engine

/** Turns cumulative counters into rates. A counter reset (core restart) yields 0, never a negative rate. */
internal class TrafficRateSampler {
    private var prevUp = 0L
    private var prevDown = 0L
    private var prevTime: Long? = null

    @Synchronized
    fun sample(upload: Long, download: Long, nowMillis: Long): TrafficSample {
        val up = upload.coerceAtLeast(0)
        val down = download.coerceAtLeast(0)
        val elapsed = prevTime?.let { nowMillis - it }
        val (tx, rx) = if (elapsed != null && elapsed > 0 && up >= prevUp && down >= prevDown) {
            ((up - prevUp).toDouble() * 1000 / elapsed).toLong() to ((down - prevDown).toDouble() * 1000 / elapsed).toLong()
        } else 0L to 0L
        prevUp = up; prevDown = down; prevTime = nowMillis
        return TrafficSample(up, down, tx, rx)
    }
}
