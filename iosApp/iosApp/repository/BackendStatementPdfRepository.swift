import Foundation
import FirebaseFunctions
import SharedLogic

/// Calls the `renderStatement` Cloud Function to turn an assembled `StatementDocument` into a PDF
/// (ticket #109). The device never reaches the (unauthenticated) Bill Engine directly — the
/// callable re-checks `profiles: view`, adds the seller/buyer letterhead, and returns the URL.
///
/// SKIE `__`-prefixes suspend interface members for Swift implementors; shared consumers still call
/// the clean `render(entityId:document:)` wrapper (ticket #34).
final class BackendStatementPdfRepository: NSObject, StatementPdfRepository {
    private let functions: Functions

    init(config: FirebaseClientConfig) {
        self.functions = Functions.functions(app: FirebaseAppFactory.app(for: config))
    }

    func __render(entityId: String, document: StatementDocument) async throws -> String {
        var statement: [String: Any] = [
            "periodTo": document.periodTo,
            "openingBalance": document.openingBalance,
            "totalDebits": document.totalDebits,
            "totalCredits": document.totalCredits,
            "closingBalance": document.closingBalance,
            "agingBuckets": document.agingBuckets.map { ["label": $0.label, "amount": $0.amount] },
            "statementRows": document.rows.map { row -> [String: Any] in
                var r: [String: Any] = [
                    "date": row.date,
                    "description": row.description_,
                    // Money columns carry money; the sale's value rides alongside.
                    "billed": row.billed,
                    "kind": row.kind,
                    "moneyIn": row.moneyIn,
                    "moneyOut": row.moneyOut,
                    "balance": row.balance,
                ]
                if let note = row.note { r["note"] = note }
                return r
            },
        ]
        if let periodFrom = document.periodFrom { statement["periodFrom"] = periodFrom }

        let payload: [String: Any] = ["entityId": entityId, "statement": statement]
        let result = try await functions.httpsCallable("renderStatement").call(payload)
        guard let data = result.data as? [String: Any], let url = data["url"] as? String else {
            throw NSError(
                domain: "renderStatement",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "The statement service returned no URL."]
            )
        }
        return url
    }
}
