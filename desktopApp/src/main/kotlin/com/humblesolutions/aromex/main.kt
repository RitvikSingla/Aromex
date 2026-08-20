package com.humblesolutions.aromex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.humblesolutions.aromex.data.DesktopPreferencesRepository
import com.humblesolutions.aromex.devlog.DevLogFab
import com.humblesolutions.aromex.devlog.LogBus
import com.humblesolutions.aromex.devlog.LogWindow
import com.humblesolutions.aromex.model.Language
import com.humblesolutions.aromex.navigation.AromexApp
import com.humblesolutions.aromex.ui.i18n.LocalStrings
import com.humblesolutions.aromex.ui.i18n.StringProvider
import com.humblesolutions.aromex.ui.theme.AromexTheme
import com.humblesolutions.aromex.usecase.GetSelectedLanguageUseCase
import java.awt.Dimension

// Aromex is multi-tenant. Each client company gets its own Firebase project;
// on Desktop we can't use a Firebase client SDK, so:
//   - Firebase Auth goes through the REST APIs (identitytoolkit +
//     securetoken) — see FirebaseRestAuthRepository.
//   - Firestore is reached via google-cloud-firestore, authenticated with a
//     short-lived, datastore-scoped OAuth token brokered by the gateway
//     (POST /firestore-token) — see FirestoreTokenBroker and
//     FirestoreUserRepository.
// The service-account key never touches the desktop.
fun main() {
    // Route stdout/stderr + java.util.logging into the in-app dev-log overlay before anything
    // else runs, so startup (gateway/Firestore init) is captured too. See LogBus/DevLogUi.
    LogBus.install()

    val preferencesRepository = DesktopPreferencesRepository()
    val getSelectedLanguage = GetSelectedLanguageUseCase(preferencesRepository)

    application {
        val windowState: WindowState = rememberWindowState(width = 1200.dp, height = 800.dp)
        var showLogs by remember { mutableStateOf(false) }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Aromex",
            state = windowState,
            resizable = true,
        ) {
            // Enforce a minimum window size so the responsive form is always usable.
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(420, 600)
            }
            AromexTheme {
                var languageCode by remember { mutableStateOf(Language.English.code) }
                LaunchedEffect(Unit) {
                    languageCode = getSelectedLanguage.execute().code
                }
                CompositionLocalProvider(
                    LocalStrings provides remember(languageCode) { StringProvider(languageCode) },
                ) {
                    // App content with the dev-log button overlaid bottom-right.
                    Box(Modifier.fillMaxSize()) {
                        AromexApp()
                        DevLogFab(
                            onClick = { showLogs = true },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        )
                    }
                }
            }
        }

        // Separate floating window for the logs, opened on demand.
        if (showLogs) {
            LogWindow(onClose = { showLogs = false })
        }
    }
}
