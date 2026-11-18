package com.vauth.rxfoxyvpn.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vauth.rxfoxyvpn.RxFoxyVpnApp
import com.vauth.rxfoxyvpn.ui.screens.BrowserSignInScreen
import com.vauth.rxfoxyvpn.ui.screens.HomeScreen
import com.vauth.rxfoxyvpn.ui.screens.LogsScreen
import com.vauth.rxfoxyvpn.ui.screens.ServerListScreen
import com.vauth.rxfoxyvpn.ui.screens.SettingsScreen
import com.vauth.rxfoxyvpn.ui.screens.SplashScreen
import com.vauth.rxfoxyvpn.ui.theme.ThemeController
import com.vauth.rxfoxyvpn.data.GUARDIAN_ENDPOINT_DEFAULT
import com.vauth.rxfoxyvpn.data.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object FoxyRoutes {
    const val SPLASH = "splash"
    const val BROWSER_SIGNIN = "browser_signin"
    const val HOME = "home"
    const val SERVERS = "servers"
    const val SETTINGS = "settings"
    const val ACCOUNTS = "accounts"
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
    // Scope for post-sign-in background work (e.g. refreshing the Guardian entitlement).
    val scope = rememberCoroutineScope()

    /** Registers a completed sign-in in the shared account store and returns to the accounts list. */
    fun handleSignedIn(email: String, password: String?) {
        val auth = app.tokenStore.loadAuth()
        val signedIn = auth?.let {
            runCatching {
                app.multiAccountStore.upsertFromLogin(
                    email.ifBlank { "account" },
                    it,
                    password?.takeIf { p -> p.isNotBlank() },
                )
            }.getOrElse { e ->
                // No raw tokens/credentials in the log — class name only.
                AppLogger.w("RxFoxyNavGraph", "account store update after sign-in failed: ${e::class.java.simpleName}")
                null
            }
        }
        // Browser sign-in (like email sign-in) should show quota immediately: ask Guardian for the
        // entitlement once per fresh sign-in instead of waiting for a manual refresh.
        val quotaToken = signedIn?.accessToken?.takeIf { it.isNotBlank() } ?: auth?.accessToken?.takeIf { it.isNotBlank() }
        if (signedIn != null && quotaToken != null) {
            val accountId = signedIn.id
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val info = app.guardianClient.fetchUserInfo(GUARDIAN_ENDPOINT_DEFAULT, quotaToken)
                    app.multiAccountStore.observeEntitlement(accountId, info)
                }.onFailure { e ->
                    AppLogger.w("RxFoxyNavGraph", "quota refresh after sign-in failed: ${e::class.java.simpleName}")
                }
            }
        }
        navController.navigate(FoxyRoutes.ACCOUNTS) {
            // Consume the transient sign-in screen(s) so Back from Accounts returns to Home.
            popUpTo(FoxyRoutes.HOME) { inclusive = false }
            launchSingleTop = true
        }
    }

    NavHost(navController = navController, startDestination = FoxyRoutes.SPLASH) {
        composable(FoxyRoutes.SPLASH) {
            // Soft login: always land on Home; connect/account actions gate on what is actually needed.
            SplashScreen(
                authRepository = app.authRepository,
                onSignedIn = {
                    navController.navigate(FoxyRoutes.HOME) { popUpTo(FoxyRoutes.SPLASH) { inclusive = true } }
                },
                onNeedsLogin = {
                    navController.navigate(FoxyRoutes.HOME) { popUpTo(FoxyRoutes.SPLASH) { inclusive = true } }
                },
            )
        }
        composable(FoxyRoutes.HOME) {
            HomeScreen(
                app = app,
                onRequestConnect = {
                    when {
                        // First-connect order: account before anything else.
                        !app.multiAccountStore.hasAnyAccount() ->
                            navController.navigate(FoxyRoutes.ACCOUNTS)
                        // Account exists; a location must be chosen before connecting.
                        app.proxyStateStore.load() == null ->
                            navController.navigate(FoxyRoutes.SERVERS)
                        else -> {
                            // Soft rotation before connect when the active account reached its threshold.
                            runCatching { app.multiAccountStore.rotateIfNeeded() }
                            onRequestConnect()
                        }
                    }
                },
                onDisconnect = onDisconnect,
                onOpenServers = { navController.navigate(FoxyRoutes.SERVERS) },
                onOpenSettings = { navController.navigate(FoxyRoutes.SETTINGS) },
                onOpenAccounts = { navController.navigate(FoxyRoutes.ACCOUNTS) },
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
                onOpenAccount = { navController.navigate(FoxyRoutes.ACCOUNTS) },
                onOpenServers = { navController.navigate(FoxyRoutes.SERVERS) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(FoxyRoutes.ACCOUNTS) {
            com.vauth.rxfoxyvpn.ui.screens.AccountsScreen(
                multiAccountStore = app.multiAccountStore,
                settingsStore = app.settingsStore,
                authRepository = app.authRepository,
                backupManager = app.backupManager,
                onBack = { navController.popBackStack() },
                onSignedIn = { email, password -> handleSignedIn(email, password) },
                onOpenBrowserSignIn = { email ->
                    navController.navigate("${FoxyRoutes.BROWSER_SIGNIN}?email=${android.net.Uri.encode(email)}")
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
                onSignedIn = { sessionEmail ->
                    val navEmail = entry.arguments?.getString("email").orEmpty()
                    handleSignedIn(sessionEmail.ifBlank { navEmail }, null)
                },
                onUseDirectSignIn = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable(FoxyRoutes.LOGS) {
            LogsScreen(onBack = { navController.popBackStack() })
        }
    }
}
