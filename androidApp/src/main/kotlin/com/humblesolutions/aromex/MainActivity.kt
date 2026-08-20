package com.humblesolutions.aromex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.humblesolutions.aromex.data.AndroidPreferencesRepository
import com.humblesolutions.aromex.model.Language
import com.humblesolutions.aromex.navigation.AromexApp
import com.humblesolutions.aromex.ui.i18n.LocalStrings
import com.humblesolutions.aromex.ui.i18n.StringProvider
import com.humblesolutions.aromex.ui.splash.SplashScreen
import com.humblesolutions.aromex.ui.theme.AromexTheme
import com.humblesolutions.aromex.usecase.GetSelectedLanguageUseCase

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val preferencesRepository = AndroidPreferencesRepository(this)
        val getSelectedLanguage = GetSelectedLanguageUseCase(preferencesRepository)

        setContent {
            AromexTheme {
                var languageCode by remember { mutableStateOf(Language.English.code) }
                LaunchedEffect(Unit) {
                    languageCode = getSelectedLanguage.execute().code
                }
                CompositionLocalProvider(
                    LocalStrings provides remember(languageCode) { StringProvider(languageCode) },
                ) {
                    AromexApp()
                }
            }
        }
    }
}

@Preview
@Composable
fun SplashAndroidPreview() {
    AromexTheme {
        CompositionLocalProvider(
            LocalStrings provides StringProvider(Language.English.code),
        ) {
            SplashScreen()
        }
    }
}
