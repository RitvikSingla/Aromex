import SwiftUI
import UIKit
import SharedLogic

// Top-level router. Owns the three ViewModels and switches between Splash,
// Login (+ ChooseCompany sheet when the email resolved to multiple companies),
// and Home. Mirrors androidApp's AromexApp.kt one-to-one.
struct AromexApp: View {
    @StateObject private var splash = SplashViewModel()
    @StateObject private var login = LoginViewModel()
    @StateObject private var home = HomeViewModel()
    @State private var showForgotPasswordAlert = false
    @EnvironmentObject private var loc: LocalizationManager

    var body: some View {
        AromexTheme {
            Group {
                if let authenticated = activeSession() {
                    homeFlow(for: authenticated)
                } else {
                    switch splash.result {
                    case .loading:
                        SplashView()
                    case .needsLogin, .authenticated:
                        // .authenticated is handled above via activeSession(); reaching
                        // this branch means we've been reset to .needsLogin post sign-out.
                        loginFlow()
                    }
                }
            }
        }
    }

    // The signed-in session, if any. Priority:
    //  1. A session the user just signed into via Login.
    //  2. A session restored from prefs at launch (SplashResult.authenticated).
    // Cleared after sign-out.
    private func activeSession() -> AuthenticatedSession? {
        if let just = login.authenticated {
            return just
        }
        if case .authenticated(let restored) = splash.result {
            return restored
        }
        return nil
    }

    @ViewBuilder
    private func loginFlow() -> some View {
        LoginView(
            viewModel: login,
            onForgotPassword: { showForgotPasswordAlert = true },
            onContactAdmin: {
                // Placeholder recipient — PM to swap when the support inbox is live.
                if let url = URL(string: "mailto:support@aromex.example?subject=Aromex%20-%20Access%20request") {
                    UIApplication.shared.open(url)
                }
            }
        )
        .alert(
            loc.t(Strings.shared.login_forgot_password),
            isPresented: $showForgotPasswordAlert,
            actions: {
                Button("OK", role: .cancel) {}
            },
            message: {
                Text(loc.t(Strings.shared.login_forgot_password_soon))
            }
        )
        .sheet(
            isPresented: Binding(
                get: { login.candidates != nil },
                set: { newValue in
                    if !newValue { login.onCancelChooseCompany() }
                }
            )
        ) {
            ChooseCompanyView(
                candidates: login.candidates ?? [],
                isSubmitting: login.isSubmitting,
                onChoose: { company in login.onChooseCompany(company) },
                onCancel: { login.onCancelChooseCompany() }
            )
        }
    }

    @ViewBuilder
    private func homeFlow(for authenticated: AuthenticatedSession) -> some View {
        HomeView(viewModel: home)
            .onAppear {
                home.bind(session: authenticated.session, config: authenticated.config)
            }
            .onChange(of: home.signedOut) { signedOut in
                if signedOut {
                    // Reset router state so the user lands back on a fresh Login.
                    splash.returnToLogin()
                    login.reset()
                }
            }
    }
}
