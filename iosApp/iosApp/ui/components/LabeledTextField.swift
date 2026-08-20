import SwiftUI
import UIKit
import SharedLogic

/// Standard Aromex form field: uppercase label above, native SwiftUI
/// `TextField` (or `SecureField` when `isPassword` is true), optional inline
/// error row underneath. When `errorMessage` is non-nil the label + border
/// turn red.
///
/// Keyboard behavior:
///  - `submitLabel` controls the return-key label (default `.done`).
///  - `onSubmit` fires when the user taps the return key. The parent uses
///    this to move focus to the next field (via its own `@FocusState`).
///  - A keyboard toolbar with a "Done" button dismisses focus on request.
///  - `onFocusChanged` fires when focus enters/leaves the field.
///
/// **Focus is managed by the parent** — the field takes an external
/// `FocusState<Bool>.Binding` so the Next-key on the software keyboard can
/// move to the next field (e.g. email → password on Login).
///
/// Password visibility toggle: SwiftUI reconciles `SecureField` and
/// `TextField` as different views, so a naïve `if isSecure ...` swap loses
/// focus and dismisses the keyboard. We re-request focus on the same
/// external `FocusState` right after the swap.
///
/// Accessibility:
///  - Eye toggle has a localized `.accessibilityLabel("Show password" /
///    "Hide password")`.
///  - Touch target of the eye toggle is expanded to 44 x 44 pt (Apple HIG
///    minimum).
///  - Label + error text use `.lineLimit(1..2)` and `.truncationMode(.tail)`
///    so localized strings can't push the layout wider than the field.
struct LabeledTextField: View {
    let label: String
    @Binding var value: String
    var placeholder: String = ""
    var enabled: Bool = true
    var isPassword: Bool = false
    var errorMessage: String? = nil
    var keyboard: UIKeyboardType = .default
    var textContentType: UITextContentType? = nil
    var submitLabel: SubmitLabel = .done
    var onSubmit: () -> Void = {}
    var onFocusChanged: (Bool) -> Void = { _ in }

    /// External focus binding, owned by the parent.
    var focus: FocusState<Bool>.Binding

    @State private var passwordVisible: Bool = false
    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexTypography) private var typography
    @Environment(\.aromexDimensions) private var dimensions

    private var hasError: Bool { errorMessage != nil }

    var body: some View {
        VStack(alignment: .leading, spacing: dimensions.space8) {
            Text(label)
                .font(typography.fieldLabel)
                .tracking(0.5)
                .foregroundStyle(hasError ? colors.error : colors.textTertiary)
                .lineLimit(1)
                .truncationMode(.tail)

            HStack(spacing: 4) {
                Group {
                    if isPassword && !passwordVisible {
                        SecureField(placeholder, text: $value)
                    } else {
                        TextField(placeholder, text: $value)
                    }
                }
                .focused(focus)
                .font(typography.body)
                .foregroundStyle(colors.textPrimary)
                .keyboardType(keyboard)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled(true)
                .disabled(!enabled)
                .textContentType(textContentType)
                .submitLabel(submitLabel)
                .onSubmit(onSubmit)
                .lineLimit(1)
                .truncationMode(.tail)
                .onChange(of: focus.wrappedValue) { newValue in
                    onFocusChanged(newValue)
                }
                .toolbar {
                    if focus.wrappedValue {
                        ToolbarItemGroup(placement: .keyboard) {
                            Spacer()
                            Button("Done") { focus.wrappedValue = false }
                        }
                    }
                }

                if isPassword {
                    Button {
                        let wasFocused = focus.wrappedValue
                        passwordVisible.toggle()
                        if wasFocused {
                            // Refocus on the newly-created SecureField /
                            // TextField after SwiftUI reconciles, so the
                            // keyboard stays up when toggling visibility.
                            DispatchQueue.main.async {
                                focus.wrappedValue = true
                            }
                        }
                    } label: {
                        Image(systemName: passwordVisible ? "eye.slash" : "eye")
                            .foregroundStyle(colors.textTertiary)
                            .frame(width: 44, height: 44) // HIG minimum
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(!enabled)
                    .accessibilityLabel(
                        loc.t(passwordVisible
                              ? Strings.shared.login_password_hide
                              : Strings.shared.login_password_show)
                    )
                }
            }
            .padding(.horizontal, dimensions.space16)
            .frame(height: dimensions.fieldHeight)
            .background(
                RoundedRectangle(cornerRadius: dimensions.radiusField)
                    .fill(enabled ? colors.surface : colors.surfaceAlt)
            )
            .overlay(
                RoundedRectangle(cornerRadius: dimensions.radiusField)
                    .stroke(
                        hasError ? colors.error : colors.border,
                        lineWidth: dimensions.borderField
                    )
            )

            if let errorMessage {
                HStack(spacing: dimensions.space8) {
                    Image(systemName: "exclamationmark.circle.fill")
                        .foregroundStyle(colors.error)
                        .font(.system(size: 14))
                    Text(errorMessage)
                        .font(typography.hint)
                        .foregroundStyle(colors.error)
                        .lineLimit(2)
                        .truncationMode(.tail)
                }
            }
        }
    }
}
