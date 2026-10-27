package com.vauth.rxfoxyvpn.quicksettings

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vauth.rxfoxyvpn.MainActivity
import com.vauth.rxfoxyvpn.data.SettingsStore
import com.vauth.rxfoxyvpn.data.TokenStore
import com.vauth.rxfoxyvpn.data.model.ConnectionState
import com.vauth.rxfoxyvpn.vpn.RxFoxyVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class QuickConnectTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        stateJob?.cancel()
        stateJob = scope.launch {
            RxFoxyVpnService.state.collect { updateTile(it) }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        when (RxFoxyVpnService.state.value) {
            ConnectionState.CONNECTED, ConnectionState.CONNECTING -> RxFoxyVpnService.stop(this)
            ConnectionState.DISCONNECTED -> {
                if (!TokenStore(this).hasStoredSession()) {
                    openApp()
                    return
                }
                if (SettingsStore(this).proxyOnlyMode || VpnService.prepare(this) == null) {
                    RxFoxyVpnService.start(this)
                } else {
                    openApp()
                }
            }
        }
    }

    private fun updateTile(state: ConnectionState) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, android.R.drawable.ic_lock_lock)
        tile.label = "RXFoxyVPN"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (state) {
                ConnectionState.CONNECTED -> "Connected"
                ConnectionState.CONNECTING -> "Connecting…"
                ConnectionState.DISCONNECTED -> "Disconnected"
            }
        }
        tile.state = if (state == ConnectionState.DISCONNECTED) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
