package com.vauth.rxfoxyvpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.vauth.rxfoxyvpn.data.CrashReporter
import com.vauth.rxfoxyvpn.data.FxaAuthRepository
import com.vauth.rxfoxyvpn.data.GuardianClient
import com.vauth.rxfoxyvpn.data.LanguageManager
import com.vauth.rxfoxyvpn.data.ProxyStateStore
import com.vauth.rxfoxyvpn.data.ServerListClient
import com.vauth.rxfoxyvpn.data.SettingsStore
import com.vauth.rxfoxyvpn.data.MultiAccountStore
import com.vauth.rxfoxyvpn.data.TokenStore
import com.vauth.rxfoxyvpn.data.backup.BackupManager
import com.vauth.rxfoxyvpn.data.manifest.ManifestRuntime
import com.vauth.rxfoxyvpn.data.relay.RelayConfigSource
import com.vauth.rxfoxyvpn.data.relay.RelayRuntime
import org.conscrypt.Conscrypt
import java.security.Security

class RxFoxyVpnApp : Application() {

    lateinit var tokenStore: TokenStore
    lateinit var multiAccountStore: MultiAccountStore
    lateinit var proxyStateStore: ProxyStateStore
    lateinit var settingsStore: SettingsStore
    lateinit var guardianClient: GuardianClient
    lateinit var serverListClient: ServerListClient
    lateinit var authRepository: FxaAuthRepository
    lateinit var backupManager: BackupManager

    override fun onCreate() {
        super.onCreate()

        LanguageManager.init(this)
        CrashReporter.install(this)
        CrashReporter.replayLastCrashIfAny(this)

        installConscrypt()
        tokenStore = TokenStore(this)
        multiAccountStore = MultiAccountStore(this, tokenStore)
        proxyStateStore = ProxyStateStore(this)
        settingsStore = SettingsStore(this)
        ManifestRuntime.install(this, settingsStore)
        RelayRuntime.configSource = RelayConfigSource { settingsStore.relayConfig }
        guardianClient = GuardianClient()
        serverListClient = ServerListClient()
        authRepository = FxaAuthRepository(tokenStore)
        backupManager = BackupManager(multiAccountStore)
        createNotificationChannel()
    }

    private fun installConscrypt() {
        runCatching {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }.onFailure {
            Log.w("RxFoxyVpnApp", "failed to install Conscrypt security provider", it)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            VPN_NOTIFICATION_CHANNEL_ID,
            "VPN status",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            ?: Log.w("RxFoxyVpnApp", "NotificationManager unavailable; status notifications disabled")
    }

    companion object {
        const val VPN_NOTIFICATION_CHANNEL_ID = "rxfoxyvpn_status"
    }
}
