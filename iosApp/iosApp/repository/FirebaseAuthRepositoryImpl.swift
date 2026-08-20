import Foundation
import FirebaseAuth
import SharedLogic

// SKIE renames Kotlin suspend interface members to `__`-prefixed names for Swift
// *implementors* (consumers still call the clean `signIn`/`idToken`/… wrappers SKIE
// generates). So the conformance methods below carry the `__` prefix. (Ticket #34)
final class FirebaseAuthRepositoryImpl: AuthRepository {

    func __signIn(config: FirebaseClientConfig, email: String, password: String) async throws -> String {
        let auth = FirebaseAppFactory.auth(for: config)
        do {
            let result = try await auth.signIn(withEmail: email, password: password)
            return result.user.uid
        } catch let error as NSError {
            throw mapSignInError(error).asError()
        }
    }

    func __signOut(config: FirebaseClientConfig) async throws {
        let auth = FirebaseAppFactory.auth(for: config)
        try auth.signOut()
    }

    func __currentUid(config: FirebaseClientConfig) async throws -> String? {
        return FirebaseAppFactory.auth(for: config).currentUser?.uid
    }

    func __idToken(config: FirebaseClientConfig, forceRefresh: Bool) async throws -> String {
        let auth = FirebaseAppFactory.auth(for: config)
        guard let user = auth.currentUser else {
            throw LoginException(error: LoginError.Unexpected(message: "no signed-in user")).asError()
        }
        do {
            return try await user.idTokenForcingRefresh(forceRefresh)
        } catch let error as NSError {
            throw LoginException(error: LoginError.FirebaseFailure(message: error.localizedDescription)).asError()
        }
    }

    private func mapSignInError(_ error: NSError) -> LoginException {
        guard error.domain == AuthErrorDomain,
              let code = AuthErrorCode(rawValue: error.code) else {
            return LoginException(error: LoginError.FirebaseFailure(message: error.localizedDescription))
        }
        switch code {
        case .wrongPassword, .invalidCredential, .invalidEmail, .userNotFound:
            return LoginException(error: LoginError.WrongPassword.shared)
        case .userDisabled:
            return LoginException(error: LoginError.AccountDisabled.shared)
        case .networkError:
            return LoginException(error: LoginError.NetworkUnavailable.shared)
        default:
            return LoginException(error: LoginError.FirebaseFailure(message: error.localizedDescription))
        }
    }
}
