package com.vauth.rxfoxyvpn.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.vauth.rxfoxyvpn.ui.screens.BrowserSignInScreen
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vauth.rxfoxyvpn.RxFoxyVpnApp
import com.vauth.rxfoxyvpn.ui.screens.AccountScreen
import com.vauth.rxfoxyvpn.ui.screens.HomeScreen
import com.vauth.rxfoxyvpn.ui.screens.LoginScreen
import com.vauth.rxfoxyvpn.ui.screens.LogsScreen
import com.vauth.rxfoxyvpn.ui.screens.ServerListScreen
import com.vauth.rxfoxyvpn.ui.screens.SettingsScreen
import com.vauth.rxfoxyvpn.ui.screens.SplashScreen
import com.vauth.rxfoxyvpn.ui.theme.ThemeController

object FoxyRoutes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val BROWSER_SIGNIN = "browser_signin"
    const val HOME = "home"
    const val SERVERS = "servers"
    const val SETTINGS = "settings"
    const val ACCOUNT = "account"
    const val LOGS = "logs"
}

@Composable
fun RxFoxyNavGraph(
    navController: NavHostController = rememberNavController(),
    app: RxFoxyVpnApp,
    themeController: ThemeController,
    onRequestConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    NavHost(navController = navController, startDestination = FoxyRoutes.SPLASH) {
        composable(FoxyRoutes.SPLASH) {
            SplashScreen(
                authRepository = app.authRepository,
                onSignedIn = {
                    navController.navigate(FoxyRoutes.HOME) { popUpTo(FoxyRoutes.SPLASH) { inclusive = true } }
                },
                onNeedsLogin = {
                    navController.navigate(FoxyRoutes.LOGIN) { popUpTo(FoxyRoutes.SPLASH) { inclusive = true } }
                },
            )
        }
        composable(FoxyRoutes.LOGIN) {
            LoginScreen(
                authRepository = app.authRepository,
                onSignedIn = {
                    navController.navigate(FoxyRoutes.HOME) { popUpTo(FoxyRoutes.LOGIN) { inclusive = true } }
                },
                onOpenBrowserSignIn = { email ->
                    navController.navigate("${FoxyRoutes.BROWSER_SIGNIN}?email=${Uri.encode(email)}")
                },
            )
        }
        composable(
            route = "${FoxyRoutes.BROWSER_SIGNIN}?email={email}",
            arguments = listOf(navArgument("email") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            BrowserSignInScreen(
                authRepository = app.authRepository,
                initialEmail = entry.arguments?.getString("email").orEmpty(),
                onSignedIn = {
                    navController.navigate(FoxyRoutes.HOME) { popUpTo(FoxyRoutes.LOGIN) { inclusive = true } }
                },
                onUseDirectSignIn = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable(FoxyRoutes.HOME) {
            HomeScreen(
                app = app,
                onRequestConnect = onRequestConnect,
                onDisconnect = onDisconnect,
                onOpenServers = { navController.navigate(FoxyRoutes.SERVERS) },
                onOpenSettings = { navController.navigate(FoxyRoutes.SETTINGS) },
            )
        }
        composable(FoxyRoutes.SERVERS) {
            ServerListScreen(
                serverListClient = app.serverListClient,
                proxyStateStore = app.proxyStateStore,
                onServerSelected = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable(FoxyRoutes.SETTINGS) {
            SettingsScreen(
                settingsStore = app.settingsStore,
                onOpenLogs = { navController.navigate(FoxyRoutes.LOGS) },
                onOpenAccount = { navController.navigate(FoxyRoutes.ACCOUNT) },
                onSignOut = {
                    onDisconnect()
                    app.authRepository.signOut()
                    navController.navigate(FoxyRoutes.LOGIN) { popUpTo(0) { inclusive = true } }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(FoxyRoutes.ACCOUNT) {
            AccountScreen(
                authRepository = app.authRepository,
                onBack = { navController.popBackStack() },
            )
        }
        composable(FoxyRoutes.LOGS) {
            LogsScreen(onBack = { navController.popBackStack() })
        }
    }
}
