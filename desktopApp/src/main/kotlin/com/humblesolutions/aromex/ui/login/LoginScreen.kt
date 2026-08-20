package com.humblesolutions.aromex.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.LoginError
import com.humblesolutions.aromex.ui.components.AromexBrandPanel
import com.humblesolutions.aromex.ui.components.LabeledTextField
import com.humblesolutions.aromex.ui.components.PrimaryButton
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.theme.AromexTheme
import java.awt.Desktop
import java.net.URI

/**
 * Desktop Login (ticket #23). Two-pane split above ~800 dp width; below that,
 * the left brand panel collapses and the form takes the full width. All state
 * comes from the shared [LoginUiState] — no logic changes.
 */
@Composable
fun LoginScreen(
    state: LoginUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val dims = AromexTheme.dimensions
    val colors = AromexTheme.colors

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(colors.background)) {
        val showBrandPanel = maxWidth >= dims.desktopTwoPaneBreakpoint

        if (showBrandPanel) {
            Row(modifier = Modifier.fillMaxSize()) {
                AromexBrandPanel(modifier = Modifier.weight(0.40f).fillMaxHeight())
                FormPane(
                    state = state,
                    onEmailChange = onEmailChange,
                    onPasswordChange = onPasswordChange,
                    onSubmit = onSubmit,
                    modifier = Modifier.weight(0.60f).fillMaxHeight(),
                )
            }
        } else {
            FormPane(
                state = state,
                onEmailChange = onEmailChange,
                onPasswordChange = onPasswordChange,
                onSubmit = onSubmit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun FormPane(
    state: LoginUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions

    var showForgotDialog by remember { mutableStateOf(false) }
    val canSubmit = !state.isSubmitting && state.email.isNotBlank() && state.password.isNotBlank()

    val submitOnEnter: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { evt ->
        if (evt.type == KeyEventType.KeyDown && evt.key == Key.Enter && canSubmit) {
            onSubmit(); true
        } else false
    }

    Box(
        modifier = modifier.background(colors.background).padding(dims.space32),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = dims.desktopFormMaxWidth)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = strings(Strings.login_welcome),
                style = AromexTheme.typography.screenTitle,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(dims.space8))
            Text(
                text = strings(Strings.login_welcome_subtitle),
                style = AromexTheme.typography.body.copy(fontSize = 14.sp),
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(dims.space32))

            LabeledTextField(
                label = strings(Strings.login_email_label_upper),
                value = state.email,
                onValueChange = onEmailChange,
                placeholder = strings(Strings.login_email_placeholder),
                enabled = !state.isSubmitting,
                keyboardType = KeyboardType.Email,
                modifier = Modifier.onPreviewKeyEvent(submitOnEnter),
            )

            Spacer(Modifier.height(dims.fieldGap))

            LabeledTextField(
                label = strings(Strings.login_password_label_upper),
                value = state.password,
                onValueChange = onPasswordChange,
                placeholder = strings(Strings.login_password_placeholder),
                enabled = !state.isSubmitting,
                isPassword = true,
                errorMessage = state.error?.let { errorMessage(it) },
                modifier = Modifier.onPreviewKeyEvent(submitOnEnter),
            )

            Spacer(Modifier.height(dims.space12))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { showForgotDialog = true },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    enabled = !state.isSubmitting,
                ) {
                    Text(
                        text = strings(Strings.login_forgot_password),
                        style = AromexTheme.typography.button,
                        color = colors.brand,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(dims.space8))

            PrimaryButton(
                label = strings(Strings.login_submit),
                loadingLabel = strings(Strings.login_loading),
                loading = state.isSubmitting,
                enabled = canSubmit,
                onClick = onSubmit,
            )

            Spacer(Modifier.height(dims.space20))

            ContactAdminLine(enabled = !state.isSubmitting)

            Spacer(Modifier.height(dims.space16))

            Text(
                text = versionTag(),
                style = AromexTheme.typography.hint,
                color = colors.textTertiary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showForgotDialog) {
        AlertDialog(
            onDismissRequest = { showForgotDialog = false },
            confirmButton = {
                TextButton(onClick = { showForgotDialog = false }) {
                    Text("OK", color = colors.brand)
                }
            },
            title = { Text(strings(Strings.login_forgot_password)) },
            text = { Text(strings(Strings.login_forgot_password_soon)) },
            containerColor = colors.surface,
        )
    }
}

@Composable
private fun ContactAdminLine(enabled: Boolean) {
    val colors = AromexTheme.colors
    val need = strings(Strings.login_need_access)
    val cta = strings(Strings.login_contact_admin)

    val annotated = buildAnnotatedString {
        withStyle(SpanStyle(color = colors.textSecondary)) { append(need) }
        append(" ")
        withStyle(
            SpanStyle(
                color = colors.brand,
                fontWeight = FontWeight.SemiBold,
            ),
        ) { append(cta) }
    }

    val interactionSource = remember { MutableInteractionSource() }
    Text(
        text = annotated,
        style = AromexTheme.typography.hint.copy(fontSize = 13.sp),
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = ::openMailto,
            ),
    )
}

private fun openMailto() {
    runCatching {
        val uri = URI("mailto:support@aromex.example?subject=Aromex%20access%20request")
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MAIL)) {
            Desktop.getDesktop().mail(uri)
        }
    }
}

/**
 * Real app version if the JVM was launched with `-Djpackage.app-version=...`
 * (jpackage sets this on packaged desktop builds), else the fallback string
 * from i18n. Suffixed with " · desktop" per the design.
 */
@Composable
private fun versionTag(): String {
    val fallback = strings(Strings.login_desktop_version_fallback)
    val platform = strings(Strings.login_desktop_platform_tag)
    val v = System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() }
        ?.let { "v$it" }
        ?: fallback
    return "$v · $platform"
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
