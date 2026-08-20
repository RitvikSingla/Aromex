import Foundation
import FirebaseFirestore
import SharedLogic

// SKIE `__`-prefixes suspend interface members for Swift implementors. (Ticket #34)
final class FirestoreUserRepository: UserRepository {

    func __getUserProfile(config: FirebaseClientConfig, uid: String) async throws -> UserProfile? {
        let firestore = FirebaseAppFactory.firestore(for: config)
        let snapshot: DocumentSnapshot
        do {
            snapshot = try await firestore.collection("users").document(uid).getDocument()
        } catch let error as NSError {
            throw LoginException(error: LoginError.FirebaseFailure(message: error.localizedDescription)).asError()
        }
        guard snapshot.exists, let data = snapshot.data() else { return nil }
        return UserProfile(
            uid: snapshot.documentID,
            email: (data["email"] as? String) ?? "",
            displayName: (data["displayName"] as? String) ?? "",
            role: parseRole(data["role"] as? String),
            permissions: parsePermissions(data["permissions"] as? [String: Any]),
            isActive: (data["isActive"] as? Bool) ?? false
        )
    }

    func __getCompanyProfile(config: FirebaseClientConfig) async throws -> CompanyProfile? {
        let firestore = FirebaseAppFactory.firestore(for: config)
        let snapshot: DocumentSnapshot
        do {
            snapshot = try await firestore.collection("companySettings").document("profile").getDocument()
        } catch let error as NSError {
            throw LoginException(error: LoginError.FirebaseFailure(message: error.localizedDescription)).asError()
        }
        guard snapshot.exists, let data = snapshot.data() else { return nil }
        // Explicit args throughout — SKIE doesn't expose Kotlin default params to Swift (ticket #76).
        return CompanyProfile(
            hlCompanyId: (data["hlCompanyId"] as? String) ?? "",
            currency: (data["currency"] as? String) ?? "",
            companyName: (data["companyName"] as? String) ?? "",
            tax: Self.parseTax(data["tax"] as? [String: Any]),
            // Shop timezone (ticket #80), carried into the session for Sales History date formatting
            // (#84). SKIE doesn't surface the Kotlin default, so it's passed explicitly; "UTC" fallback.
            timezone: (data["timezone"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "UTC",
            legalName: data["legalName"] as? String,
            taxNumber: data["taxNumber"] as? String,
            logoUrl: data["logoUrl"] as? String,
            businessAddress: data["businessAddress"] as? String,
            contactEmail: data["contactEmail"] as? String,
            contactPhone: data["contactPhone"] as? String
        )
    }

    /// Builds a `TaxConfig` from the nested `tax` map; rates kept as decimal strings.
    private static func parseTax(_ tax: [String: Any]?) -> TaxConfig {
        guard let tax = tax else {
            // SKIE doesn't expose the Kotlin default (no-arg) init — pass explicit defaults.
            return TaxConfig(gstEnabled: false, gstRate: "0", pstEnabled: false, pstRate: "0", isHST: false)
        }
        return TaxConfig(
            gstEnabled: (tax["gstEnabled"] as? Bool) ?? false,
            gstRate: rateToString(tax["gstRate"]),
            pstEnabled: (tax["pstEnabled"] as? Bool) ?? false,
            pstRate: rateToString(tax["pstRate"]),
            isHST: (tax["isHST"] as? Bool) ?? false
        )
    }

    private static func rateToString(_ v: Any?) -> String {
        if let s = v as? String { return s.trimmingCharacters(in: .whitespaces).isEmpty ? "0" : s }
        if let n = v as? NSNumber { return n.stringValue }
        return "0"
    }

    private func parseRole(_ raw: String?) -> UserRole {
        switch raw?.lowercased() {
        case "admin": return UserRole.admin
        default: return UserRole.member
        }
    }

    private func parsePermissions(_ raw: [String: Any]?) -> Permissions {
        let map = raw ?? [:]
        func level(_ key: String) -> PermissionLevel {
            switch (map[key] as? String)?.lowercased() {
            case "manage": return PermissionLevel.manage
            case "view": return PermissionLevel.view
            default: return PermissionLevel.none
            }
        }
        return Permissions(
            sales: level("sales"),
            purchases: level("purchases"),
            inventory: level("inventory"),
            transactions: level("transactions"),
            profiles: level("profiles"),
            balances: level("balances"),
            reports: level("reports"),
            statistics: level("statistics"),
            histories: level("histories"),
            ledgers: level("ledgers"),
            settings: level("settings"),
            userMgmt: (map["userMgmt"] as? Bool) ?? false
        )
    }
}
