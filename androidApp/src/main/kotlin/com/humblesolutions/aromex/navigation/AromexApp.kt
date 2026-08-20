package com.humblesolutions.aromex.navigation

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.AuthenticatedSession
import com.humblesolutions.aromex.ui.entities.EntitiesFeature
import com.humblesolutions.aromex.ui.inventory.CommissionRulesFeature
import com.humblesolutions.aromex.ui.inventory.InventoryFeature
import com.humblesolutions.aromex.ui.sales.SalesFeature
import com.humblesolutions.aromex.ui.sales.history.SalesHistoryFeature
import com.humblesolutions.aromex.ui.home.HomeScreen
import com.humblesolutions.aromex.ui.home.HomeViewModel
import com.humblesolutions.aromex.ui.i18n.LocalStrings
import com.humblesolutions.aromex.ui.login.ChooseCompanyScreen
import com.humblesolutions.aromex.ui.login.LoginScreen
import com.humblesolutions.aromex.ui.login.LoginViewModel
import com.humblesolutions.aromex.ui.splash.SplashResult
import com.humblesolutions.aromex.ui.splash.SplashScreen
import com.humblesolutions.aromex.ui.splash.SplashViewModel

/**
 * Top-level navigator. Single source of truth: [currentRoute] + activeSession.
 * Three states (Splash, Login, Home). When we grow past ~8 screens we'll move
 * to androidx.navigation:navigation-compose.
 */
@Composable
fun AromexApp() {
    val splashVm: SplashViewModel = viewModel()
    val splashState by splashVm.uiState.collectAsStateWithLifecycle()

    // Route is saveable (enum) and the session is held in a retained ViewModel,
    // so a rotation / theme change keeps the user on the current screen instead
    // of bouncing back to Home.
    val appState: AppStateViewModel = viewModel()
    var currentRoute by rememberSaveable { mutableStateOf(Route.Splash) }

    LaunchedEffect(splashState.result) {
        when (val r = splashState.result) {
            SplashResult.Loading -> Unit
            SplashResult.NeedsLogin -> if (currentRoute == Route.Splash) currentRoute = Route.Login
            is SplashResult.Authenticated -> {
                appState.session = r.authenticated
                // Only route off the splash on first resolve — never override the
                // user's current screen after a config change re-fires this effect.
                if (currentRoute == Route.Splash) currentRoute = Route.Home
            }
        }
    }

    when (currentRoute) {
        Route.Splash -> SplashScreen()
        Route.Login -> LoginRoute(
            onAuthenticated = { auth ->
                appState.session = auth
                currentRoute = Route.Home
            },
        )
        Route.Home -> {
            val auth = appState.session
            if (auth == null) {
                currentRoute = Route.Login
            } else {
                HomeRoute(
                    authenticated = auth,
                    onSignedOut = {
                        appState.session = null
                        currentRoute = Route.Login
                    },
                    onOpenEntities = { currentRoute = Route.Entities },
                    onOpenInventory = { currentRoute = Route.Inventory },
                    onOpenSales = { currentRoute = Route.Sales },
                    onOpenSalesHistory = { currentRoute = Route.SalesHistory },
                    onOpenCommissionRules = { currentRoute = Route.CommissionRules },
                )
            }
        }
        Route.Entities -> {
            val auth = appState.session
            if (auth == null) {
                currentRoute = Route.Login
            } else {
                EntitiesFeature(authenticated = auth, onExit = { currentRoute = Route.Home })
            }
        }
        Route.Inventory -> {
            val auth = appState.session
            if (auth == null) {
                currentRoute = Route.Login
            } else {
                InventoryFeature(authenticated = auth, onExit = { currentRoute = Route.Home })
            }
        }
        Route.Sales -> {
            val auth = appState.session
            if (auth == null) {
                currentRoute = Route.Login
            } else {
                SalesFeature(authenticated = auth, onExit = { currentRoute = Route.Home })
            }
        }
        Route.SalesHistory -> {
            val auth = appState.session
            if (auth == null) {
                currentRoute = Route.Login
            } else {
                SalesHistoryFeature(authenticated = auth, onExit = { currentRoute = Route.Home })
            }
        }
        Route.CommissionRules -> {
            val auth = appState.session
            if (auth == null) {
                currentRoute = Route.Login
            } else {
                CommissionRulesFeature(authenticated = auth, onExit = { currentRoute = Route.Home })
            }
        }
    }
}

@Composable
private fun LoginRoute(
    onAuthenticated: (AuthenticatedSession) -> Unit,
) {
    val loginVm: LoginViewModel = viewModel()
    val state by loginVm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.authenticated) {
        state.authenticated?.let(onAuthenticated)
    }

    val context = LocalContext.current
    val stringProvider = LocalStrings.current
    val forgotToastText = stringProvider.get(Strings.login_forgot_password_soon)

    if (state.candidates != null) {
        ChooseCompanyScreen(
            candidates = state.candidates!!,
            onChoose = { loginVm.onChooseCompany(it) },
            onCancel = { loginVm.onCancelChooseCompany() },
        )
    } else {
        LoginScreen(
            state = state,
            onEmailChange = loginVm::onEmailChange,
            onPasswordChange = loginVm::onPasswordChange,
            onSubmit = loginVm::onSubmit,
            onForgotPassword = {
                Toast.makeText(context, forgotToastText, Toast.LENGTH_LONG).show()
            },
            onContactAdmin = {
                // Placeholder recipient — PM to swap when the support inbox is live.
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:support@aromex.example?subject=Aromex%20-%20Access%20request")
                }
                runCatching { context.startActivity(intent) }
            },
        )
    }
}

@Composable
private fun HomeRoute(
    authenticated: AuthenticatedSession,
    onSignedOut: () -> Unit,
    onOpenEntities: () -> Unit,
    onOpenInventory: () -> Unit,
    onOpenSales: () -> Unit,
    onOpenSalesHistory: () -> Unit,
    onOpenCommissionRules: () -> Unit,
) {
    val homeVm: HomeViewModel = viewModel()
    val state by homeVm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(authenticated.session.uid) {
        homeVm.bind(authenticated.session, authenticated.config)
    }

    LaunchedEffect(state.signedOut) {
        if (state.signedOut) onSignedOut()
    }

    HomeScreen(
        session = authenticated.session,
        state = state,
        onSignOut = homeVm::signOut,
        onRetryBalances = homeVm::retryBalances,
        onOpenEntities = onOpenEntities,
        onOpenInventory = onOpenInventory,
        onOpenSales = onOpenSales,
        onOpenSalesHistory = onOpenSalesHistory,
        onOpenCommissionRules = onOpenCommissionRules,
    )
}
