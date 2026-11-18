package com.vauth.rxfoxyvpn.vpn.engine

import android.os.SystemClock
import com.vauth.rxfoxyvpn.data.AppLogger
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Subscribes to libbox's in-process status stream (no network listener involved). Resubscribes if it drops. */
internal class NativeTrafficMonitor(private val scope: CoroutineScope, private val publish: (TrafficSample) -> Unit) {
    private var job: Job? = null

    fun start() {
        stop()
        job = scope.launch {
            while (isActive) {
                val disconnected = CompletableDeferred<String?>()
                val sampler = TrafficRateSampler()
                val handler = object : CommandClientHandler {
                    override fun connected() = Unit
                    override fun disconnected(message: String?) { disconnected.complete(message) }
                    override fun writeStatus(message: StatusMessage?) {
                        if (isActive && message?.trafficAvailable == true) {
                            publish(sampler.sample(message.uplinkTotal, message.downlinkTotal, SystemClock.elapsedRealtime()))
                        }
                    }
                    override fun clearLogs() = Unit
                    override fun setDefaultLogLevel(level: Int) = Unit
                    override fun writeLogs(messages: LogIterator?) = Unit
                    override fun writeGroups(groups: OutboundGroupIterator?) = Unit
                    override fun writeOutbounds(outbounds: OutboundGroupItemIterator?) = Unit
                    override fun initializeClashMode(modes: StringIterator?, current: String?) = Unit
                    override fun updateClashMode(mode: String?) = Unit
                    override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
                }
                val options = CommandClientOptions().apply {
                    addCommand(Libbox.CommandStatus)
                    statusInterval = TimeUnit.SECONDS.toNanos(2)
                }
                val client = CommandClient(handler, options)
                try {
                    client.connect()
                    val reason = disconnected.await()
                    if (isActive) AppLogger.w("TrafficMonitor", reason ?: "status stream disconnected")
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    AppLogger.w("TrafficMonitor", "native statistics temporarily unavailable", e)
                } finally {
                    runCatching { client.disconnect() }
                }
                delay(5_000L)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}
