package com.humblesolutions.aromex.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.humblesolutions.aromex.model.AuthenticatedSession

/**
 * Holds the authenticated session in a retained [ViewModel] so it survives
 * configuration changes. `remember` would be cleared on rotation — dropping the
 * session and bouncing the user to Login — whereas a ViewModel is retained
 * across the Activity recreate. Together with a `rememberSaveable` route, this
 * keeps the user exactly where they were after a rotation or theme change.
 */
class AppStateViewModel : ViewModel() {
    var session by mutableStateOf<AuthenticatedSession?>(null)
}
