package com.humblesolutions.aromex.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import com.humblesolutions.aromex.ui.components.DesktopSection
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.humblesolutions.aromex.model.AuthenticatedSession
import com.humblesolutions.aromex.ui.entities.EntitiesScreen
import com.humblesolutions.aromex.ui.entities.EntitiesViewModel
import com.humblesolutions.aromex.ui.home.HomeViewModel
import com.humblesolutions.aromex.ui.inventory.AddStockViewModel
import com.humblesolutions.aromex.ui.inventory.CommissionRulesScreen
import com.humblesolutions.aromex.ui.inventory.CommissionRulesViewModel
import com.humblesolutions.aromex.ui.inventory.InventoryListViewModel
import com.humblesolutions.aromex.ui.inventory.InventoryScreen
import com.humblesolutions.aromex.ui.inventory.StockHistoryScreen
import com.humblesolutions.aromex.ui.inventory.StockHistoryViewModel
import com.humblesolutions.aromex.ui.login.ChooseCompanyScreen
import com.humblesolutions.aromex.ui.login.LoginScreen
import com.humblesolutions.aromex.ui.login.LoginViewModel
import com.humblesolutions.aromex.ui.money.MoneyScreen
import com.humblesolutions.aromex.ui.money.MoneyViewModel
import com.humblesolutions.aromex.ui.settings.SettingsScreen
import com.humblesolutions.aromex.ui.settings.SettingsViewModel
import com.humblesolutions.aromex.ui.sales.SalesScreen
import com.humblesolutions.aromex.ui.sales.SalesViewModel
import com.humblesolutions.aromex.ui.sales.history.SalesHistoryScreen
import com.humblesolutions.aromex.ui.sales.history.SalesHistoryViewModel
import com.humblesolutions.aromex.ui.splash.SplashResult
import com.humblesolutions.aromex.ui.splash.SplashScreen
import com.humblesolutions.aromex.ui.splash.SplashViewModel
import com.humblesolutions.aromex.ui.theme.AromexTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState

/**
 * Top-level router for the Desktop app. Owns the three ViewModels for the
 * lifetime of the Compose composition; scopes are cancelled on
 * [DisposableEffect] teardown.
 *
 * Priority:
 *   1. A session the user just signed into via Login → Home.
 *   2. A session restored from prefs at launch (SplashResult.Authenticated) → Home.
 *   3. Otherwise: Splash while the restore is in flight, Login when it finishes.
 * After a successful sign-out the SplashViewModel is flipped back to
 * NeedsLogin and the LoginViewModel is reset so the user lands on a fresh
 * Login screen.
 */
@Composable
fun AromexApp() {
    val splash = remember { SplashViewModel() }
    val login = remember { LoginViewModel() }
    val home = remember { HomeViewModel() }

    DisposableEffect(Unit) {
        onDispose {
            splash.close()
            login.close()
            home.close()
        }
    }

    val splashState by splash.uiState.collectAsStateSafely()
    val loginState by login.uiState.collectAsStateSafely()
    val homeState by home.uiState.collectAsStateSafely()

    val active = activeSession(loginState.authenticated, splashState.result)

    // Cache the last non-null session so the outgoing Home layer can still
    // render itself during a Home → Login crossfade after sign-out (when
    // `active` has already flipped to null).
    var lastActive by remember { mutableStateOf<AuthenticatedSession?>(null) }
    LaunchedEffect(active) { if (active != null) lastActive = active }

    val route = when {
        active != null -> Route.Home
        splashState.result == SplashResult.Loading -> Route.Splash
        loginState.candidates != null -> Route.ChooseCompany
        else -> Route.Login
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AromexTheme.colors.background),
    ) {
    Crossfade(
        targetState = route,
        animationSpec = tween(durationMillis = 400),
        label = "aromexRoute",
    ) { current ->
        when (current) {
            Route.Home -> {
                val a = lastActive
                if (a != null) {
                    val entitiesVm = remember { EntitiesViewModel() }
                    val inventoryVm = remember { InventoryListViewModel() }
                    val addStockVm = remember { AddStockViewModel() }
                    val salesVm = remember { SalesViewModel() }
                    val salesHistoryVm = remember { SalesHistoryViewModel() }
                    val moneyVm = remember { MoneyViewModel() }
                    val settingsVm = remember { SettingsViewModel() }
                    val commissionRulesVm = remember { CommissionRulesViewModel() }
                    val stockHistoryVm = remember { StockHistoryViewModel() }
                    LaunchedEffect(a.session.uid, a.config.projectId) {
                        entitiesVm.bind(a.session, a.config)
                        inventoryVm.bind(a.session, a.config)
                        addStockVm.bind(a.session, a.config)
                        salesVm.bind(a.session, a.config)
                        salesHistoryVm.bind(a.session, a.config)
                        moneyVm.bind(a.session, a.config)
                        settingsVm.bind(a.session, a.config)
                        commissionRulesVm.bind(a.session, a.config)
                        stockHistoryVm.bind(a.session, a.config)
                    }
                    DisposableEffect(entitiesVm, inventoryVm, addStockVm, salesVm, salesHistoryVm, moneyVm, commissionRulesVm) {
                        onDispose {
                            // Each section ViewModel owns an Admin-SDK Firestore client whose
                            // close() blocks while its gRPC channel terminates. Running that on the
                            // Compose UI thread (which is where onDispose executes) froze the app on
                            // logout — worse, ~9 channels closed back-to-back. Hand the whole
                            // teardown to a background scope so sign-out returns immediately; the
                            // ViewModels are already out of composition and never touched again.
                            teardownScope.launch {
                                runCatching { entitiesVm.dispose() }
                                runCatching { inventoryVm.dispose() }
                                runCatching { addStockVm.dispose() }
                                runCatching { salesVm.dispose() }
                                runCatching { salesHistoryVm.dispose() }
                                runCatching { moneyVm.dispose() }
                                runCatching { settingsVm.dispose() }
                                runCatching { commissionRulesVm.dispose() }
                                runCatching { stockHistoryVm.dispose() }
                            }
                        }
                    }
                    val entitiesState by entitiesVm.uiState.collectAsStateSafely()
                    val moneyState by moneyVm.uiState.collectAsStateSafely()
                    val settingsState by settingsVm.uiState.collectAsStateSafely()
                    val commissionRulesState by commissionRulesVm.uiState.collectAsStateSafely()
                    val stockHistoryState by stockHistoryVm.uiState.collectAsStateSafely()

                    var section by remember { mutableStateOf(DesktopSection.ENTITIES) }

                    // Balances are a request-time read from Humble Ledger, not a live stream, so
                    // arriving on a money-facing screen re-reads them. Throttled in the ViewModels
                    // so flipping between screens doesn't hammer the ledger.
                    LaunchedEffect(section) {
                        when (section) {
                            DesktopSection.ENTITIES -> entitiesVm.refreshBalancesIfStale()
                            DesktopSection.MONEY -> moneyVm.refreshBalancesIfStale()
                            else -> Unit
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        when (section) {
                            DesktopSection.ENTITIES -> EntitiesScreen(
                                state = entitiesState,
                                onBack = {},
                                onQueryChange = entitiesVm::onQueryChange,
                                onFilterChange = entitiesVm::onFilterChange,
                                onBalanceFilterChange = entitiesVm::onBalanceFilterChange,
                                onSortChange = entitiesVm::onSortChange,
                                onRefresh = entitiesVm::refreshBalances,
                                onSave = entitiesVm::save,
                                onArchive = entitiesVm::archive,
                                onLoadStatement = { entitiesVm.loadStatement(it) },
                                onReverseMoney = entitiesVm::askReverse,
                                onConfirmReverse = entitiesVm::confirmReverse,
                                onDismissReverse = entitiesVm::dismissReverse,
                                onLoadMoreStatement = {
                                    val s = entitiesVm.uiState.value
                                    s.statementEntityId?.let { id ->
                                        entitiesVm.loadStatement(id, page = (s.statement?.page ?: 1) + 1)
                                    }
                                },
                                onStatementSearch = entitiesVm::setStatementSearch,
                                onStatementRange = entitiesVm::setStatementDateRange,
                                onStatementSortToggle = entitiesVm::toggleStatementSort,
                                onStatementClearFilters = entitiesVm::clearStatementFilters,
                                onPrintOpen = entitiesVm::openPrintDialog,
                                onPrintRange = entitiesVm::setPrintRange,
                                onPrintNotesToggle = entitiesVm::togglePrintNotes,
                                onPrintGenerate = entitiesVm::generateStatementPdf,
                                onPrintDialogClose = entitiesVm::closePrintDialog,
                                onPdfClose = entitiesVm::closePdf,
                                onNavigateToInventory = { section = DesktopSection.INVENTORY },
                                onNavigateToSales = { section = DesktopSection.SALES },
                                onNavigateToSalesHistory = { section = DesktopSection.SALES_HISTORY },
                                onNavigateToMoney = { section = DesktopSection.MONEY },
                                onNavigateToCommissionRules = { section = DesktopSection.COMMISSION_RULES },
                                onNavigateToSettings = { section = DesktopSection.SETTINGS },
                                onNavigateToStockHistory = { section = DesktopSection.STOCK_HISTORY },
                                onSignOut = home::signOut,
                            )
                            DesktopSection.INVENTORY -> InventoryScreen(
                                listVm = inventoryVm,
                                addVm = addStockVm,
                                onNavigateToEntities = { section = DesktopSection.ENTITIES },
                                onNavigateToSales = { section = DesktopSection.SALES },
                                onNavigateToSalesHistory = { section = DesktopSection.SALES_HISTORY },
                                onNavigateToMoney = { section = DesktopSection.MONEY },
                                onNavigateToCommissionRules = { section = DesktopSection.COMMISSION_RULES },
                                onNavigateToSettings = { section = DesktopSection.SETTINGS },
                                onNavigateToStockHistory = { section = DesktopSection.STOCK_HISTORY },
                                onSignOut = home::signOut,
                            )
                            DesktopSection.SALES -> SalesScreen(
                                vm = salesVm,
                                session = a.session,
                                onNavigateToEntities = { section = DesktopSection.ENTITIES },
                                onNavigateToInventory = { section = DesktopSection.INVENTORY },
                                onNavigateToSalesHistory = { section = DesktopSection.SALES_HISTORY },
                                onNavigateToMoney = { section = DesktopSection.MONEY },
                                onNavigateToCommissionRules = { section = DesktopSection.COMMISSION_RULES },
                                onNavigateToSettings = { section = DesktopSection.SETTINGS },
                                onNavigateToStockHistory = { section = DesktopSection.STOCK_HISTORY },
                                onSignOut = home::signOut,
                            )
                            DesktopSection.SALES_HISTORY -> SalesHistoryScreen(
                                vm = salesHistoryVm,
                                session = a.session,
                                onNavigateToEntities = { section = DesktopSection.ENTITIES },
                                onNavigateToInventory = { section = DesktopSection.INVENTORY },
                                onNavigateToSales = { section = DesktopSection.SALES },
                                onNavigateToMoney = { section = DesktopSection.MONEY },
                                onNavigateToSettings = { section = DesktopSection.SETTINGS },
                                onNavigateToStockHistory = { section = DesktopSection.STOCK_HISTORY },
                                onSignOut = home::signOut,
                            )
                            DesktopSection.MONEY -> MoneyScreen(
                                state = moneyState,
                                vm = moneyVm,
                                session = a.session,
                                onNavigateToEntities = { section = DesktopSection.ENTITIES },
                                onNavigateToInventory = { section = DesktopSection.INVENTORY },
                                onNavigateToSales = { section = DesktopSection.SALES },
                                onNavigateToSalesHistory = { section = DesktopSection.SALES_HISTORY },
                                onNavigateToCommissionRules = { section = DesktopSection.COMMISSION_RULES },
                                onNavigateToSettings = { section = DesktopSection.SETTINGS },
                                onNavigateToStockHistory = { section = DesktopSection.STOCK_HISTORY },
                                onSignOut = home::signOut,
                            )
                            DesktopSection.SETTINGS -> SettingsScreen(
                                state = settingsState,
                                vm = settingsVm,
                                session = a.session,
                                onNavigateToEntities = { section = DesktopSection.ENTITIES },
                                onNavigateToInventory = { section = DesktopSection.INVENTORY },
                                onNavigateToSales = { section = DesktopSection.SALES },
                                onNavigateToSalesHistory = { section = DesktopSection.SALES_HISTORY },
                                onNavigateToMoney = { section = DesktopSection.MONEY },
                                onSignOut = home::signOut,
                            )
                            DesktopSection.STOCK_HISTORY -> StockHistoryScreen(
                                state = stockHistoryState,
                                vm = stockHistoryVm,
                                session = a.session,
                                onNavigateToEntities = { section = DesktopSection.ENTITIES },
                                onNavigateToInventory = { section = DesktopSection.INVENTORY },
                                onNavigateToSales = { section = DesktopSection.SALES },
                                onNavigateToSalesHistory = { section = DesktopSection.SALES_HISTORY },
                                onNavigateToMoney = { section = DesktopSection.MONEY },
                                onNavigateToCommissionRules = { section = DesktopSection.COMMISSION_RULES },
                                onNavigateToSettings = { section = DesktopSection.SETTINGS },
                                onSignOut = home::signOut,
                            )
                            DesktopSection.COMMISSION_RULES -> CommissionRulesScreen(
                                vm = commissionRulesVm,
                                state = commissionRulesState,
                                session = a.session,
                                onNavigateToEntities = { section = DesktopSection.ENTITIES },
                                onNavigateToInventory = { section = DesktopSection.INVENTORY },
                                onNavigateToSales = { section = DesktopSection.SALES },
                                onNavigateToSalesHistory = { section = DesktopSection.SALES_HISTORY },
                                onNavigateToMoney = { section = DesktopSection.MONEY },
                                onNavigateToSettings = { section = DesktopSection.SETTINGS },
                                onNavigateToStockHistory = { section = DesktopSection.STOCK_HISTORY },
                                onSignOut = home::signOut,
                            )
                        }
                    }
                    LaunchedBind(a.session, a.config, home)
                    LaunchedSignOutObserver(homeState.signedOut, splash, login, home)
                }
            }
            Route.Splash -> SplashScreen()
            Route.ChooseCompany -> ChooseCompanyScreen(
                candidates = loginState.candidates.orEmpty(),
                onChoose = login::onChooseCompany,
                onCancel = login::onCancelChooseCompany,
            )
            Route.Login -> LoginScreen(
                state = loginState,
                onEmailChange = login::onEmailChange,
                onPasswordChange = login::onPasswordChange,
                onSubmit = login::onSubmit,
            )
        }
    }
    }
}

private enum class Route { Splash, Login, ChooseCompany, Home }

/**
 * Background scope for tearing down Home's section ViewModels (and their blocking
 * Admin-SDK Firestore `close()` calls) off the Compose UI thread. Process-scoped
 * on purpose — a logout must never wait on gRPC channel shutdown, and there is
 * nothing meaningful to cancel it against.
 */
private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun activeSession(
    justSignedIn: AuthenticatedSession?,
    splashResult: SplashResult,
): AuthenticatedSession? {
    if (justSignedIn != null) return justSignedIn
    if (splashResult is SplashResult.Authenticated) return splashResult.authenticated
    return null
}

@Composable
private fun LaunchedBind(
    session: com.humblesolutions.aromex.model.UserSession,
    config: com.humblesolutions.aromex.model.FirebaseClientConfig,
    home: HomeViewModel,
) {
    androidx.compose.runtime.LaunchedEffect(session.uid, config.projectId) {
        home.bind(session, config)
    }
}

@Composable
private fun LaunchedSignOutObserver(
    signedOut: Boolean,
    splash: SplashViewModel,
    login: LoginViewModel,
    home: HomeViewModel,
) {
    androidx.compose.runtime.LaunchedEffect(signedOut) {
        if (signedOut) {
            splash.returnToLogin()
            login.reset()
            // Consume the one-shot flag so a subsequent login doesn't re-trigger
            // this observer and bounce the user back to Login.
            home.acknowledgeSignedOut()
        }
    }
}

@Composable
private fun <T> StateFlow<T>.collectAsStateSafely() = collectAsState()
