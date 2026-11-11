package com.vauth.rxfoxyvpn.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
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
        updateAll(context, RxFoxyVpnService.state.value, force = true)
    }

    // the user resized the widget: scale the button to the new size
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, manager, id, newOptions)
        updateAll(context, RxFoxyVpnService.state.value)
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

        private fun Float.clampTo(min: Float, max: Float) = if (this < min) min else if (this > max) max else this

        // what each widget instance currently shows; an identical update would restart its animation
        private val shown = HashMap<Int, String>()

        /** The widget is only the app's own connect orb: no card, no text. It follows the widget size.
         *  Widgets can't host the animated Compose orb, so the active states play a looping frame sequence
         *  (tail sway, breathing, power-icon pulse, spinning ring, sparks, glint) rendered from the same artwork. */
        fun updateAll(context: Context, state: ConnectionState, force: Boolean = false) {
            val manager = AppWidgetManager.getInstance(context)
            val component = android.content.ComponentName(context, QuickConnectWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return

            // disconnected without a saved Firefox session looks "asleep" (still), like the app's own orb
            val signedIn = runCatching { TokenStore(context).hasStoredSession() }.getOrDefault(true)
            val asleep = state == ConnectionState.DISCONNECTED && !signedIn
            val layout = when {
                state == ConnectionState.CONNECTED -> R.layout.widget_orb_on_anim
                state == ConnectionState.CONNECTING -> R.layout.widget_orb_connecting_anim
                asleep -> R.layout.widget_quick_connect
                else -> R.layout.widget_orb_ready_anim
            }
            val description = when (state) {
                ConnectionState.CONNECTED -> LanguageManager.t("status_connected", "Connected")
                ConnectionState.CONNECTING -> LanguageManager.t("status_connecting", "Connecting…")
                ConnectionState.DISCONNECTED -> LanguageManager.t("status_disconnected", "Disconnected")
            }

            val intent = Intent(context, QuickConnectWidgetProvider::class.java).setAction(ACTION_TOGGLE)
            val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            shown.keys.retainAll(ids.toSet())
            ids.forEach { id ->
                val options = manager.getAppWidgetOptions(id)
                val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
                val key = "$layout|$w|$h|$description"
                if (!force && shown[id] == key) return@forEach
                val views = RemoteViews(context.packageName, layout)
                views.setContentDescription(R.id.widget_button, description)
                // the art carries a transparent glow margin around the orb, so the view fills the widget
                if (w > 0 && h > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val size = (minOf(w, h) - 2f).clampTo(40f, 260f)
                    views.setViewLayoutWidth(R.id.widget_button, size, TypedValue.COMPLEX_UNIT_DIP)
                    views.setViewLayoutHeight(R.id.widget_button, size, TypedValue.COMPLEX_UNIT_DIP)
                }
                views.setOnClickPendingIntent(R.id.widget_root, pi)
                views.setOnClickPendingIntent(R.id.widget_button, pi)
                manager.updateAppWidget(id, views)
                shown[id] = key
            }
        }
    }
}
