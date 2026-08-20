package com.humblesolutions.aromex.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.LoginError
import com.humblesolutions.aromex.ui.components.AromexGradientHeader
import com.humblesolutions.aromex.ui.components.LabeledTextField
import com.humblesolutions.aromex.ui.components.PrimaryButton
import com.humblesolutions.aromex.ui.components.SecondaryButton
import com.humblesolutions.aromex.ui.components.SectionDivider
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.theme.AromexTheme

/**
 * Login screen — ticket #21 visual redesign.
 *
 * State ↔ visual mapping (three PM-provided screenshots):
 *  - **Default**  → both fields empty; submit enabled once both are non-blank.
 *  - **Error**    → [LoginUiState.error] non-null → password label + border turn red, message + icon below.
 *  - **Loading**  → [LoginUiState.isSubmitting] true → fields disabled, submit stays filled blue with spinner + "Signing in…".
 *
 * Keyboard: the Sign in button is a **bottom-pinned bar** — the outer
 * container wraps it in `Modifier.imePadding()`, so it translates smoothly
 * upward with the keyboard as the IME animates in, and settles exactly above
 * it. No scroll-into-view / bringIntoView needed. The form body sits in its
 * own scroll region above the pinned button, so nothing ever overlaps the
 * primary action.
 *
 * Presentation only. `LoginViewModel` / `LoginUseCase` / flow are unchanged.
 */
@Composable
fun LoginScreen(
    state: LoginUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onForgotPassword: () -> Unit = {},
    onContactAdmin: () -> Unit = {},
) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions
    val canSubmit = state.email.isNotBlank() && state.password.isNotBlank()

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            // Tap-outside-a-field dismisses the keyboard. detectTapGestures
            // is transparent to descendants (fields still receive their taps
            // because they consume the event first).
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                })
            },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AromexGradientHeader(
                title = strings(Strings.login_welcome),
                subtitle = strings(Strings.login_welcome_subtitle),
            )

            // Scrollable body — everything except the pinned Sign in button.
            // Takes weight(1f) so it consumes exactly the space between the
            // header and the pinned footer; if the content is taller than the
            // available space (or the keyboard is up), the user can scroll.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = dimensions.screenPadding,
                        vertical = dimensions.space24,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 480.dp),
                ) {
                    LabeledTextField(
                        label = strings(Strings.login_email_label_upper),
                        value = state.email,
                        onValueChange = onEmailChange,
                        placeholder = strings(Strings.login_email_placeholder),
                        enabled = !state.isSubmitting,
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    )

                    Spacer(Modifier.height(dimensions.fieldGap))

                    LabeledTextField(
                        label = strings(Strings.login_password_label_upper),
                        value = state.password,
                        onValueChange = onPasswordChange,
                        placeholder = strings(Strings.login_password_placeholder),
                        enabled = !state.isSubmitting,
                        isPassword = true,
                        errorMessage = state.error?.let { errorMessage(it) },
                        imeAction = ImeAction.Done,
                        onImeAction = { if (canSubmit && !state.isSubmitting) onSubmit() },
                    )

                    Spacer(Modifier.height(dimensions.space8))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onForgotPassword, enabled = !state.isSubmitting) {
                            Text(
                                text = strings(Strings.login_forgot_password),
                                style = AromexTheme.typography.button,
                                color = colors.brand,
                            )
                        }
                    }

                    Spacer(Modifier.height(dimensions.space24))
                    SectionDivider(label = strings(Strings.login_need_access))
                    Spacer(Modifier.height(dimensions.space16))

                    SecondaryButton(
                        label = strings(Strings.login_contact_admin),
                        onClick = onContactAdmin,
                        enabled = !state.isSubmitting,
                    )
                }
            }

            // Pinned Sign in bar. imePadding() gives it bottom padding equal
            // to the animated IME height, so the button smoothly translates
            // upward as the keyboard rises and settles exactly above it.
            // navigationBarsPadding() keeps it clear of the system gesture
            // inset when the keyboard is closed. Because the scrollable body
            // above uses weight(1f), the two regions never overlap.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(
                        horizontal = dimensions.screenPadding,
                        vertical = dimensions.space16,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 480.dp),
                ) {
                    PrimaryButton(
                        label = strings(Strings.login_submit),
                        loadingLabel = strings(Strings.login_loading),
                        onClick = onSubmit,
                        enabled = canSubmit,
                        loading = state.isSubmitting,
                    )
                }
            }
        }
    }
}

@Composable
private fun errorMessage(error: LoginError): String = when (error) {
    LoginError.UnknownEmail -> strings(Strings.login_error_unknown_email)
    LoginError.WrongPassword -> strings(Strings.login_error_wrong_password)
    LoginError.AccountDisabled -> strings(Strings.login_error_account_disabled)
    LoginError.MissingUserRecord -> strings(Strings.login_error_missing_user_record)
    LoginError.NetworkUnavailable -> strings(Strings.login_error_network)
    LoginError.GatewayUnreachable -> strings(Strings.login_error_gateway)
    is LoginError.FirebaseFailure -> strings(Strings.login_error_firebase)
    is LoginError.Unexpected -> strings(Strings.login_error_unexpected)
}
