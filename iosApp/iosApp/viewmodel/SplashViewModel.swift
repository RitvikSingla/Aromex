import Foundation
import SharedLogic

enum SplashResult {
    case loading
    case needsLogin
    case authenticated(AuthenticatedSession)
}

@MainActor
final class SplashViewModel: ObservableObject {
    @Published var result: SplashResult = .loading

    private let prefs: PreferencesRepository
    private let directory: CompanyDirectoryRepository
    private let authRepo: AuthRepository
    private let userRepo: UserRepository
    private let restoreUseCase: RestoreSessionUseCase

    init() {
        let prefs = UserDefaultsPreferencesRepository()
        let directory = HttpCompanyDirectoryRepository()
        let authRepo = FirebaseAuthRepositoryImpl()
        let userRepo = FirestoreUserRepository()
        self.prefs = prefs
        self.directory = directory
        self.authRepo = authRepo
        self.userRepo = userRepo
        self.restoreUseCase = RestoreSessionUseCase(directory: directory, auth: authRepo, user: userRepo)
        restore()
    }

    // Returns the router to the "signed out" state after a successful sign-out.
    func returnToLogin() {
        result = .needsLogin
    }

    private func restore() {
        Task {
            // Kick off the restore in the background. Because `async let`
            // starts the child task immediately, the sleep below runs in
            // parallel — the minimum splash time doesn't extend total launch
            // time when restore is slower than 2s.
            async let restored: AuthenticatedSession? = {
                let email = try? await prefs.getLastSignedInEmail()
                let companyId = try? await prefs.getLastCompanyId()
                return try? await restoreUseCase.execute(
                    lastEmail: email ?? nil,
                    lastCompanyId: companyId ?? nil
                )
            }()

            // Minimum splash-display time so the brand treatment is visible
            // on cold launch even when restore is instant.
            try? await Task.sleep(nanoseconds: SplashViewModel.minDisplayNs)

            let authenticated = await restored

            if let authenticated {
                self.result = .authenticated(authenticated)
            } else {
                self.result = .needsLogin
            }
        }
    }

    private static let minDisplayNs: UInt64 = 2_000_000_000 // 2 seconds
}
