import Foundation
import SharedLogic

@MainActor
final class HomeViewModel: ObservableObject {
    @Published private(set) var session: UserSession? = nil
    /// The bound company config — exposed so screens (e.g. Entities) can build their
    /// own repo stack via manual DI.
    @Published private(set) var boundConfig: FirebaseClientConfig? = nil
    @Published private(set) var accounts: [LedgerAccount] = []
    @Published private(set) var isLoadingBalances: Bool = false
    @Published private(set) var balancesError: HlError? = nil
    @Published private(set) var isSigningOut: Bool = false
    @Published private(set) var signedOut: Bool = false

    private let prefs: PreferencesRepository
    private let authRepo: AuthRepository
    private let logoutUseCase: LogoutUseCase

    private var activeConfig: FirebaseClientConfig? = nil
    private var tokenRepo: HlTokenProvider? = nil
    private var balancesUseCase: GetAccountBalancesUseCase? = nil

    init() {
        let prefs = UserDefaultsPreferencesRepository()
        let authRepo = FirebaseAuthRepositoryImpl()
        self.prefs = prefs
        self.authRepo = authRepo
        self.logoutUseCase = LogoutUseCase(auth: authRepo)
    }

    // Bind the active session + config and kick off the HL balances fetch.
    // Safe to call multiple times — only the first bind for a (uid, config) pair
    // wires up the HL stack and triggers a load.
    func bind(session: UserSession, config: FirebaseClientConfig) {
        if self.session?.uid == session.uid, activeConfig == config { return }
        signedOut = false
        activeConfig = config
        boundConfig = config
        let tokens = HlTokenRepositoryImpl(authRepo: authRepo, activeConfig: config)
        let ledger = HlLedgerRepositoryImpl(tokenProvider: tokens)
        tokenRepo = tokens
        balancesUseCase = GetAccountBalancesUseCase(ledger: ledger)
        self.session = session
        loadBalances()
    }

    func retryBalances() {
        if isLoadingBalances { return }
        loadBalances()
    }

    private func loadBalances() {
        guard let useCase = balancesUseCase else { return }
        isLoadingBalances = true
        balancesError = nil
        Task {
            do {
                let result = try await useCase.execute()
                accounts = result
                isLoadingBalances = false
                balancesError = nil
            } catch {
                isLoadingBalances = false
                balancesError = mapHlError(from: error)
            }
        }
    }

    private func mapHlError(from error: Error) -> HlError {
        let ns = error as NSError
        if let ex = ns.userInfo["KotlinException"] as? HlException {
            return ex.error
        }
        return HlError.Unexpected(message: error.localizedDescription)
    }

    func signOut() {
        guard let config = activeConfig else { return }
        if isSigningOut { return }
        isSigningOut = true
        Task {
            do {
                try await logoutUseCase.execute(config: config)
            } catch {
                // Best-effort: even if remote sign-out failed, clear local session.
            }
            try? await prefs.setLastSignedInEmail(email: nil)
            try? await prefs.setLastCompanyId(companyId: nil)
            isSigningOut = false
            signedOut = true
            session = nil
        }
    }
}
