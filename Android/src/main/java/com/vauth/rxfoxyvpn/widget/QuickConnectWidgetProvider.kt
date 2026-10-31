package com.vauth.rxfoxyvpn.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.RemoteViews
import com.vauth.rxfoxyvpn.MainActivity
import com.vauth.rxfoxyvpn.R
import com.vauth.rxfoxyvpn.data.SettingsStore
import com.vauth.rxfoxyvpn.data.LanguageManager
import com.vauth.rxfoxyvpn.data.TokenStore
import com.vauth.rxfoxyvpn.data.model.ConnectionState
import com.vauth.rxfoxyvpn.vpn.RxFoxyVpnService

/* App brand accents (mirrors ui/theme/Color.kt DarkStatusColors / FoxSeed), kept in sync by hand
   since RemoteViews can't read Compose theme values. */
private object WidgetBrand {
    const val ELECTRIC_BLUE = 0xFF38D9FF.toInt()   // connected
    const val NEON_PINK = 0xFFFF5FB0.toInt()       // connecting
    const val NEON_PURPLE = 0xFFB388FF.toInt()     // disconnected
    const val MUTED_LAVENDER = 0xFF9A8FC9.toInt()  // subtitle
}

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

                // two-tone brand text, same split the app itself uses: "RX" in electric blue, "FoxyVPN" in white
                val brand = SpannableString("RXFoxyVPN").apply {
                    setSpan(ForegroundColorSpan(WidgetBrand.ELECTRIC_BLUE), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                views.setTextViewText(R.id.widget_brand, brand)

                views.setTextViewText(R.id.widget_status, when (state) {
                    ConnectionState.CONNECTED -> LanguageManager.t("status_connected", "Connected")
                    ConnectionState.CONNECTING -> LanguageManager.t("status_connecting", "Connecting…")
                    ConnectionState.DISCONNECTED -> LanguageManager.t("status_disconnected", "Disconnected")
                })
                views.setTextColor(
                    R.id.widget_status,
                    when (state) {
                        ConnectionState.CONNECTED -> WidgetBrand.ELECTRIC_BLUE
                        ConnectionState.CONNECTING -> WidgetBrand.NEON_PINK
                        ConnectionState.DISCONNECTED -> WidgetBrand.NEON_PURPLE
                    },
                )
                views.setTextViewText(
                    R.id.widget_subtitle,
                    when (state) {
                        ConnectionState.CONNECTED -> LanguageManager.t("tap_disconnect", "Tap to disconnect")
                        ConnectionState.CONNECTING -> LanguageManager.t("tap_cancel", "Tap to cancel")
                        ConnectionState.DISCONNECTED -> LanguageManager.t("tap_connect", "Tap to connect")
                    },
                )
                views.setTextColor(R.id.widget_subtitle, WidgetBrand.MUTED_LAVENDER)

                // the toggle itself: the app's own glass power-button style, just re-drawn as a
                // static RemoteViews drawable (widgets can't host the animated Compose fox orb)
                views.setImageViewResource(
                    R.id.widget_button,
                    when (state) {
                        ConnectionState.CONNECTED -> R.drawable.widget_power_btn_connected
                        ConnectionState.CONNECTING -> R.drawable.widget_power_btn_connecting
                        ConnectionState.DISCONNECTED -> R.drawable.widget_power_btn_disconnected
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
