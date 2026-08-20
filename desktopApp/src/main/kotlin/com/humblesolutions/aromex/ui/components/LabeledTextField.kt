package com.humblesolutions.aromex.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.theme.AromexTheme

/**
 * Uppercase label + Material 3 [OutlinedTextField] styled to spec + optional
 * inline error row. `isPassword = true` adds a keyboard-reachable eye toggle
 * with a localized accessibility description.
 */
@Composable
fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    isPassword: Boolean = false,
    errorMessage: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions
    val hasError = errorMessage != null
    val focusManager = LocalFocusManager.current

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = AromexTheme.typography.fieldLabel.copy(
                color = if (hasError) colors.error else colors.textTertiary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(dimensions.space8))

        var passwordVisible by remember { mutableStateOf(false) }
        val eyeContentDescription = strings(
            if (passwordVisible) Strings.login_password_hide else Strings.login_password_show,
        )

        val noAutoBehavior = isPassword || keyboardType == KeyboardType.Email

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            isError = hasError,
            shape = RoundedCornerShape(dimensions.radiusField),
            placeholder = placeholder?.let {
                {
                    Text(
                        text = it,
                        style = AromexTheme.typography.body,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            trailingIcon = if (isPassword) {
                {
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        enabled = enabled,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = eyeContentDescription,
                            tint = colors.textTertiary,
                        )
                    }
                }
            } else null,
            visualTransformation = if (isPassword && !passwordVisible) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isPassword) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
                autoCorrectEnabled = !noAutoBehavior,
                capitalization = if (noAutoBehavior) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
            ),
            keyboardActions = KeyboardActions(
                onDone = { onImeAction?.invoke() },
                onGo = { onImeAction?.invoke() },
                onNext = {
                    focusManager.moveFocus(FocusDirection.Next)
                    onImeAction?.invoke()
                },
                onSearch = { onImeAction?.invoke() },
                onSend = { onImeAction?.invoke() },
            ),
            textStyle = AromexTheme.typography.body,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                disabledTextColor = colors.textTertiary,
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                disabledContainerColor = colors.surfaceAlt,
                errorContainerColor = colors.surface,
                focusedBorderColor = colors.brand,
                unfocusedBorderColor = colors.border,
                disabledBorderColor = colors.border,
                errorBorderColor = colors.error,
                cursorColor = colors.brand,
                errorCursorColor = colors.error,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(dimensions.fieldHeight)
                .pointerHoverIcon(PointerIcon.Text)
                .onFocusChanged { onFocusChanged(it.isFocused) },
        )

        if (hasError) {
            Spacer(Modifier.height(dimensions.space8))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = null,
                    tint = colors.error,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(dimensions.space8))
                Text(
                    text = errorMessage,
                    style = AromexTheme.typography.hint,
                    color = colors.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
