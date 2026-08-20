import Foundation
import FirebaseFirestore
import SharedLogic

/// Firestore-backed implementation of the shared `CompanySettingsRepository` — `companySettings/profile`.
///
/// ── Why this exists ──────────────────────────────────────────────────────────
/// So a phone's till follows a tax-rate change. `UserSession.tax` is captured at sign-in and never
/// refreshed, so without a live profile an admin changing GST at noon would leave every open phone
/// charging the old rate for the rest of the day, silently — the hole closed on Desktop (M10) and
/// Android, and now here.
///
/// ── Flow-interop (SKIE) ──────────────────────────────────────────────────────
/// `observeProfile()` returns a Kotlin `Flow<CompanyProfile>`, built with the shared
/// `companyProfileCallbackFlow(subscribe:)` adapter feeding a Firestore snapshot listener — the
/// same shape as `BackendCommissionRuleRepository` and `BackendEntityRepository`. SKIE
/// `__`-prefixes the suspend members.
///
/// Reads only. The Settings *screen* is Desktop-only, so the write paths throw rather than
/// pretend: a phone that appeared to save a tax rate but didn't would be worse than one that
/// plainly can't.
final class BackendCompanySettingsRepository: NSObject, CompanySettingsRepository {

    private let db: Firestore

    init(config: FirebaseClientConfig) {
        self.db = FirebaseAppFactory.firestore(for: config)
        super.init()
    }

    // MARK: - Live read (produces the shared Flow the use case consumes)

    func observeProfile() -> SkieSwiftFlow<CompanyProfile> {
        return companyProfileCallbackFlow { onEach, onError in
            let registration = self.db.collection("companySettings").document("profile")
                .addSnapshotListener { snapshot, error in
                    if let error = error {
                        _ = onError(error.localizedDescription)
                        return
                    }
                    // An absent profile means provisioning hasn't finished. Emitting a default
                    // would quietly say "no tax"; staying silent lets the caller keep what it had.
                    guard let data = snapshot?.data(), snapshot?.exists == true else { return }
                    _ = onEach(Self.mapProfile(data))
                }
            return FirestoreCompanyProfileObservation(registration)
        }
    }

    /// The change log is part of the Desktop-only Settings screen; an empty history is the honest
    /// answer here rather than a pretence that one was fetched.
    func observeChanges(limit: Int32) -> SkieSwiftFlow<[CompanySettingsChange]> {
        return emptySettingsChangesFlow()
    }

    // MARK: - Writes (Desktop-only)

    func __updateTax(config: TaxConfig, changedBy: String, changedByName: String?) async throws {
        throw NSError(
            domain: "Aromex", code: 1,
            userInfo: [NSLocalizedDescriptionKey: "Tax settings are edited on Desktop"])
    }

    func __updateDetails(details: CompanyDetails, changedBy: String, changedByName: String?) async throws {
        throw NSError(
            domain: "Aromex", code: 1,
            userInfo: [NSLocalizedDescriptionKey: "Company details are edited on Desktop"])
    }

    // MARK: - Mapping (mirrors the Desktop and Android implementations)

    private static func mapProfile(_ d: [String: Any]) -> CompanyProfile {
        CompanyProfile(
            hlCompanyId: d["hlCompanyId"] as? String ?? "",
            currency: d["currency"] as? String ?? "",
            companyName: d["companyName"] as? String ?? "",
            tax: mapTax(d["tax"]),
            timezone: (d["timezone"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "UTC",
            legalName: d["legalName"] as? String,
            taxNumber: d["taxNumber"] as? String,
            logoUrl: d["logoUrl"] as? String,
            businessAddress: d["businessAddress"] as? String,
            contactEmail: d["contactEmail"] as? String,
            contactPhone: d["contactPhone"] as? String)
    }

    /// Rates may be stored as a number (provisioning) or a string (the Settings screen) — accept
    /// both, and never let a rate become a Double on the way through.
    private static func mapTax(_ raw: Any?) -> TaxConfig {
        guard let t = raw as? [String: Any] else {
            return TaxConfig(gstEnabled: false, gstRate: "0", pstEnabled: false, pstRate: "0", isHST: false)
        }
        func rate(_ v: Any?) -> String {
            if let s = v as? String {
                let trimmed = s.trimmingCharacters(in: .whitespaces)
                return trimmed.isEmpty ? "0" : trimmed
            }
            if let n = v as? NSNumber { return n.stringValue }
            return "0"
        }
        return TaxConfig(
            gstEnabled: t["gstEnabled"] as? Bool ?? false,
            gstRate: rate(t["gstRate"]),
            pstEnabled: t["pstEnabled"] as? Bool ?? false,
            pstRate: rate(t["pstRate"]),
            isHST: t["isHST"] as? Bool ?? false)
    }
}

/// Detaches the Firestore listener when the shared Flow is cancelled.
private final class FirestoreCompanyProfileObservation: NSObject, CompanyProfileObservation {
    private let registration: ListenerRegistration
    init(_ registration: ListenerRegistration) { self.registration = registration }
    func cancel() { registration.remove() }
}
