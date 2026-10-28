package com.vauth.rxfoxyvpn

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.vauth.rxfoxyvpn.data.LanguageManager
import com.vauth.rxfoxyvpn.data.LocalAppLanguage
import com.vauth.rxfoxyvpn.ui.navigation.RxFoxyNavGraph
import com.vauth.rxfoxyvpn.ui.screens.AutomaticUpdatePrompt
import com.vauth.rxfoxyvpn.ui.theme.RxFoxyVpnTheme
import com.vauth.rxfoxyvpn.ui.theme.rememberThemeController
import com.vauth.rxfoxyvpn.vpn.RxFoxyVpnService

class MainActivity : ComponentActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            RxFoxyVpnService.start(this)
        } else {

            Toast.makeText(
                this,
                "RxFoxyVPN needs Android's VPN permission to tunnel this device's traffic. " +
                    "Proxy-only mode in Settings works without it.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {  }

    private fun requestConnect() {
        requestNotificationPermissionIfNeeded()

        if ((application as RxFoxyVpnApp).settingsStore.proxyOnlyMode) {
            RxFoxyVpnService.start(this)
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            RxFoxyVpnService.start(this)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val alreadyGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!alreadyGranted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as RxFoxyVpnApp
        setContent {
            val themeController = rememberThemeController(app.settingsStore)
            val currentLang by LanguageManager.currentLang.collectAsState()
            val layoutDirection = if (currentLang == "fa" || currentLang == "ar") {
                LayoutDirection.Rtl
            } else {
                LayoutDirection.Ltr
            }

            RxFoxyVpnTheme(themeMode = themeController.mode) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides layoutDirection,
                    LocalAppLanguage provides currentLang,
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val navController = rememberNavController()
                        Box(modifier = Modifier.fillMaxSize()) {
                            RxFoxyNavGraph(
                                navController = navController,
                                app = app,
                                themeController = themeController,
                                onRequestConnect = { requestConnect() },
                                onDisconnect = { RxFoxyVpnService.stop(this@MainActivity) },
                            )
                            AutomaticUpdatePrompt()
                        }
                    }
                }
            }
        }
    }
}
