import Foundation
import SwiftUI
import SharedLogic

@MainActor
final class LocalizationManager: ObservableObject {
    @Published var languageCode: String

    private let defaults: UserDefaults
    private static let storageKey = "selected_language_code"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.languageCode = defaults.string(forKey: Self.storageKey) ?? Language.companion.English.code
    }

    func setLanguage(_ code: String) {
        defaults.set(code, forKey: Self.storageKey)
        languageCode = code
    }

    func t(_ key: String) -> String {
        LocalizationRegistry.shared.get(code: languageCode, key: key)
    }

    func t(_ key: String, _ args: Any...) -> String {
        LocalizationRegistry.shared.format(code: languageCode, key: key, args: args)
    }

    func p(_ baseKey: String, count: Int, _ args: Any...) -> String {
        LocalizationRegistry.shared.plural(
            code: languageCode,
            baseKey: baseKey,
            count: Int32(count),
            args: args
        )
    }
}
