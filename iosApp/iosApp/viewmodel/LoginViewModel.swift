import Foundation
import SharedLogic

@MainActor
final class LoginViewModel: ObservableObject {
    @Published var email: String = ""
    @Published var password: String = ""
    @Published private(set) var isSubmitting: Bool = false
    @Published private(set) var error: LoginError? = nil
    @Published private(set) var candidates: [ResolvedCompany]? = nil
    @Published private(set) var authenticated: AuthenticatedSession? = nil

    private let prefs: PreferencesRepository
    private let directory: CompanyDirectoryRepository
    private let authRepo: AuthRepository
    private let userRepo: UserRepository
    private let loginUseCase: LoginUseCase

    init() {
        let prefs = UserDefaultsPreferencesRepository()
        let directory = HttpCompanyDirectoryRepository()
        let authRepo = FirebaseAuthRepositoryImpl()
        let userRepo = FirestoreUserRepository()
        self.prefs = prefs
        self.directory = directory
        self.authRepo = authRepo
        self.userRepo = userRepo
        self.loginUseCase = LoginUseCase(directory: directory, auth: authRepo, user: userRepo)
    }

    func onEmailChange(_ value: String) {
        email = value
        error = nil
    }

    func onPasswordChange(_ value: String) {
        password = value
        error = nil
    }

    func onDismissError() {
        error = nil
    }

    func onSubmit() {
        if isSubmitting { return }
        let trimmedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        let pass = password
        if trimmedEmail.isEmpty || pass.isEmpty { return }

        isSubmitting = true
        error = nil
        Task {
            do {
                let result = try await loginUseCase.execute(email: trimmedEmail, password: pass)
                if let success = result as? LoginResult.Success {
                    try? await rememberLastLogin(email: trimmedEmail, companyId: success.authenticated.session.companyId)
                    isSubmitting = false
                    authenticated = success.authenticated
                    candidates = nil
                } else if let needsChoice = result as? LoginResult.NeedsCompanyChoice {
                    isSubmitting = false
                    candidates = needsChoice.candidates
                }
            } catch {
                isSubmitting = false
                self.error = mapLoginError(from: error)
            }
        }
    }

    func onChooseCompany(_ company: ResolvedCompany) {
        if isSubmitting { return }
        let trimmedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        let pass = password
        isSubmitting = true
        error = nil
        Task {
            do {
                let session = try await loginUseCase.finishLogin(
                    company: company,
                    email: trimmedEmail,
                    password: pass
                )
                try? await rememberLastLogin(email: trimmedEmail, companyId: session.session.companyId)
                isSubmitting = false
                authenticated = session
                candidates = nil
            } catch {
                isSubmitting = false
                self.error = mapLoginError(from: error)
                candidates = nil
            }
        }
    }

    // Kotlin exceptions arrive on Swift as NSError. When the underlying Kotlin
    // exception is a LoginException, Kotlin/Native tags it in userInfo under
    // the "KotlinException" key — unwrap it to get our typed LoginError.
    private func mapLoginError(from error: Error) -> LoginError {
        let ns = error as NSError
        if let ex = ns.userInfo["KotlinException"] as? LoginException {
            return ex.error
        }
        return LoginError.Unexpected(message: error.localizedDescription)
    }

    func onCancelChooseCompany() {
        candidates = nil
    }

    // Clears local state so the Login screen is fresh (used after sign-out).
    func reset() {
        email = ""
        password = ""
        isSubmitting = false
        error = nil
        candidates = nil
        authenticated = nil
    }

    private func rememberLastLogin(email: String, companyId: String) async throws {
        try await prefs.setLastSignedInEmail(email: email)
        try await prefs.setLastCompanyId(companyId: companyId)
    }
}
