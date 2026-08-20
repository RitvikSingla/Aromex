import Foundation
import SharedLogic

final class UserDefaultsPreferencesRepository: PreferencesRepository {
    private let defaults: UserDefaults
    private enum Keys {
        static let languageCode = "selected_language_code"
        static let lastEmail = "last_signed_in_email"
        static let lastCompanyId = "last_company_id"
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    // MARK: - Language

    // SKIE `__`-prefixes suspend interface members for Swift implementors. (Ticket #34)
    func __getSelectedLanguageCode() async throws -> String? {
        return defaults.string(forKey: Keys.languageCode)
    }

    func __setSelectedLanguageCode(code: String) async throws {
        defaults.set(code, forKey: Keys.languageCode)
    }

    // MARK: - Last signed-in email

    func __getLastSignedInEmail() async throws -> String? {
        return defaults.string(forKey: Keys.lastEmail)
    }

    func __setLastSignedInEmail(email: String?) async throws {
        if let email {
            defaults.set(email, forKey: Keys.lastEmail)
        } else {
            defaults.removeObject(forKey: Keys.lastEmail)
        }
    }

    // MARK: - Last companyId

    func __getLastCompanyId() async throws -> String? {
        return defaults.string(forKey: Keys.lastCompanyId)
    }

    func __setLastCompanyId(companyId: String?) async throws {
        if let companyId {
            defaults.set(companyId, forKey: Keys.lastCompanyId)
        } else {
            defaults.removeObject(forKey: Keys.lastCompanyId)
        }
    }
}
