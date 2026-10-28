package com.vauth.rxfoxyvpn.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import com.vauth.rxfoxyvpn.MainActivity
import com.vauth.rxfoxyvpn.R
import com.vauth.rxfoxyvpn.data.SettingsStore
import com.vauth.rxfoxyvpn.data.LanguageManager
import com.vauth.rxfoxyvpn.data.TokenStore
import com.vauth.rxfoxyvpn.data.model.ConnectionState
import com.vauth.rxfoxyvpn.vpn.RxFoxyVpnService

class QuickConnectWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            when (RxFoxyVpnService.state.value) {
                ConnectionState.CONNECTED, ConnectionState.CONNECTING -> RxFoxyVpnService.stop(context)
                ConnectionState.DISCONNECTED -> {
                    if (!TokenStore(context).hasStoredSession()) {
                        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else if (SettingsStore(context).proxyOnlyMode || VpnService.prepare(context) == null) {
                        RxFoxyVpnService.start(context)
                    } else {
                        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            updateAll(context, RxFoxyVpnService.state.value)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.vauth.rxfoxyvpn.action.TOGGLE_VPN"

        fun updateAll(context: Context, state: ConnectionState) {
            val manager = AppWidgetManager.getInstance(context)
            val component = android.content.ComponentName(context, QuickConnectWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_quick_connect)
                views.setTextViewText(R.id.widget_status, when (state) {
                    ConnectionState.CONNECTED -> LanguageManager.t("status_connected", "Connected")
                    ConnectionState.CONNECTING -> LanguageManager.t("status_connecting", "Connecting…")
                    ConnectionState.DISCONNECTED -> LanguageManager.t("status_disconnected", "Disconnected")
                })
                views.setTextColor(
                    R.id.widget_status,
                    when (state) {
                        ConnectionState.CONNECTED -> android.graphics.Color.rgb(52, 224, 161)
                        ConnectionState.CONNECTING -> android.graphics.Color.rgb(56, 217, 255)
                        ConnectionState.DISCONNECTED -> android.graphics.Color.rgb(154, 161, 214)
                    },
                )
                views.setImageViewResource(
                    R.id.widget_status_dot,
                    when (state) {
                        ConnectionState.CONNECTED -> R.drawable.widget_dot_connected
                        ConnectionState.CONNECTING -> R.drawable.widget_dot_connecting
                        ConnectionState.DISCONNECTED -> R.drawable.widget_dot_disconnected
                    },
                )
                views.setTextViewText(
                    R.id.widget_button,
                    when (state) {
                        ConnectionState.CONNECTED -> LanguageManager.t("tap_disconnect", "Tap to disconnect")
                        ConnectionState.CONNECTING -> LanguageManager.t("tap_cancel", "Tap to cancel")
                        ConnectionState.DISCONNECTED -> LanguageManager.t("tap_connect", "Tap to connect")
                    },
                )
                val intent = Intent(context, QuickConnectWidgetProvider::class.java).setAction(ACTION_TOGGLE)
                val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_button, pi)
                manager.updateAppWidget(id, views)
            }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) =
            updateAll(context, RxFoxyVpnService.state.value)
    }
}
