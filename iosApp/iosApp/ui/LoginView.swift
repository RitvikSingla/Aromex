import SwiftUI
import SharedLogic

/// Login view — ticket #21 visual redesign.
///
/// State ↔ visual mapping (three PM-provided screenshots):
///   - **Default**  → both fields empty; submit enabled once both are non-blank.
///   - **Error**    → `viewModel.error` non-nil → password label + border turn red, message + icon below.
///   - **Loading**  → `viewModel.isSubmitting` true → fields disabled, submit stays filled blue with spinner + "Signing in…".
///
/// Keyboard:
///  - Email → Next moves focus to password.
///  - Password → Done submits the form (identical to tapping Sign in).
///  - The **Sign in button is a bottom-pinned bar** via `.safeAreaInset(edge: .bottom)` —
///    SwiftUI automatically raises the safe-area inset above the keyboard as it
///    animates in, so the primary action smoothly translates upward with the
///    keyboard and settles exactly above it. No scroll-into-view needed. The
///    form body scrolls in its own region above the pinned bar, so nothing
///    ever overlaps the primary action.
///
/// Presentation only. `LoginViewModel` / `LoginUseCase` / flow are unchanged.
struct LoginView: View {
    @EnvironmentObject private var loc: LocalizationManager
    @ObservedObject var viewModel: LoginViewModel

    var onForgotPassword: () -> Void = {}
    var onContactAdmin: () -> Void = {}

    /// Owned by LoginView so the Next key on email can move focus to
    /// password (LabeledTextField exposes an external FocusState.Binding).
    @FocusState private var emailFocused: Bool
    @FocusState private var passwordFocused: Bool

    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexTypography) private var typography
    @Environment(\.aromexDimensions) private var dimensions

    private var canSubmit: Bool {
        !viewModel.email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !viewModel.password.isEmpty
    }

    var body: some View {
        VStack(spacing: 0) {
            AromexGradientHeader(
                title: loc.t(Strings.shared.login_welcome),
                subtitle: loc.t(Strings.shared.login_welcome_subtitle)
            )

            // Scrollable body — everything except the pinned Sign in button.
            // If the content is taller than the space between the header and
            // the pinned bar (or the keyboard is up), the user can scroll.
            ScrollView {
                VStack(spacing: dimensions.fieldGap) {
                    LabeledTextField(
                        label: loc.t(Strings.shared.login_email_label_upper),
                        value: Binding(
                            get: { viewModel.email },
                            set: { viewModel.onEmailChange($0) }
                        ),
                        placeholder: loc.t(Strings.shared.login_email_placeholder),
                        enabled: !viewModel.isSubmitting,
                        keyboard: .emailAddress,
                        textContentType: .emailAddress,
                        submitLabel: .next,
                        onSubmit: {
                            // Next key on email → move focus to password.
                            passwordFocused = true
                        },
                        focus: $emailFocused
                    )

                    LabeledTextField(
                        label: loc.t(Strings.shared.login_password_label_upper),
                        value: Binding(
                            get: { viewModel.password },
                            set: { viewModel.onPasswordChange($0) }
                        ),
                        placeholder: loc.t(Strings.shared.login_password_placeholder),
                        enabled: !viewModel.isSubmitting,
                        isPassword: true,
                        errorMessage: viewModel.error.map(errorMessage(_:)),
                        textContentType: .password,
                        submitLabel: .done,
                        onSubmit: {
                            if canSubmit && !viewModel.isSubmitting {
                                viewModel.onSubmit()
                            }
                        },
                        focus: $passwordFocused
                    )

                    HStack {
                        Spacer()
                        Button(action: onForgotPassword) {
                            Text(loc.t(Strings.shared.login_forgot_password))
                                .font(typography.button)
                                .foregroundStyle(colors.brand)
                        }
                        .buttonStyle(.plain)
                        .disabled(viewModel.isSubmitting)
                    }

                    SectionDivider(label: loc.t(Strings.shared.login_need_access))
                        .padding(.top, dimensions.space8)

                    SecondaryButton(
                        label: loc.t(Strings.shared.login_contact_admin),
                        action: onContactAdmin,
                        enabled: !viewModel.isSubmitting
                    )
                }
                .padding(.horizontal, dimensions.screenPadding)
                .padding(.top, dimensions.space24)
                .padding(.bottom, dimensions.space24)
                .frame(maxWidth: 480)
            }
            .scrollDismissesKeyboard(.interactively)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            // Pinned Sign in bar. SwiftUI raises the bottom safe-area inset
            // above the keyboard as it animates in, so this view smoothly
            // translates upward with the keyboard and settles exactly above
            // it — no scroll-into-view / two-shot scroll needed. The scroll
            // region above resizes to fit the remaining space, so the two
            // regions never overlap.
            .safeAreaInset(edge: .bottom) {
                VStack {
                    PrimaryButton(
                        label: loc.t(Strings.shared.login_submit),
                        loadingLabel: loc.t(Strings.shared.login_loading),
                        action: viewModel.onSubmit,
                        enabled: canSubmit,
                        loading: viewModel.isSubmitting
                    )
                    .frame(maxWidth: 480)
                }
                .frame(maxWidth: .infinity)
                .padding(.horizontal, dimensions.screenPadding)
                .padding(.vertical, dimensions.space16)
                .background(colors.background)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(colors.background.ignoresSafeArea())
        // Tap outside any text field dismisses the keyboard. SwiftUI's
        // hit-test lets the text fields consume their own taps first, so this
        // only fires on background / header / non-field surfaces.
        .contentShape(Rectangle())
        .onTapGesture { dismissKeyboard() }
    }

    /// Clearing both @FocusState bindings resigns first responder on the
    /// active TextField / SecureField, which dismisses the keyboard.
    private func dismissKeyboard() {
        emailFocused = false
        passwordFocused = false
    }

    private func errorMessage(_ error: LoginError) -> String {
        if error is LoginError.UnknownEmail {
            return loc.t(Strings.shared.login_error_unknown_email)
        } else if error is LoginError.WrongPassword {
            return loc.t(Strings.shared.login_error_wrong_password)
        } else if error is LoginError.AccountDisabled {
            return loc.t(Strings.shared.login_error_account_disabled)
        } else if error is LoginError.MissingUserRecord {
            return loc.t(Strings.shared.login_error_missing_user_record)
        } else if error is LoginError.NetworkUnavailable {
            return loc.t(Strings.shared.login_error_network)
        } else if error is LoginError.GatewayUnreachable {
            return loc.t(Strings.shared.login_error_gateway)
        } else if error is LoginError.FirebaseFailure {
            return loc.t(Strings.shared.login_error_firebase)
        } else {
            return loc.t(Strings.shared.login_error_unexpected)
        }
    }
}
