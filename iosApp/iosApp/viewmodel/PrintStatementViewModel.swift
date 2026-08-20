import Foundation
import SharedLogic

/// Drives statement-PDF generation for one party (ticket #109). The heavy lifting — paging every
/// row, the opening-balance call, the FIFO aging — lives in the shared `BuildPartyStatementUseCase`;
/// this only wires the dependencies, holds the sheet's form state, and calls the `renderStatement`
/// Cloud Function via `BackendStatementPdfRepository`.
@MainActor
final class PrintStatementViewModel: ObservableObject {
    @Published var fromISO: String
    @Published var toISO: String
    @Published var includeNotes: Bool = false
    @Published private(set) var generating: Bool = false
    @Published var errorText: String? = nil
    /// Set once the PDF is ready; the view opens it and clears this.
    @Published var pdfURL: URL? = nil

    private let session: UserSession
    private let ledger: KtorEntityLedgerRepository   // owns its HttpClient — closed in deinit
    private let buildUseCase: BuildPartyStatementUseCase
    private let pdfRepo: BackendStatementPdfRepository

    private let authRepo = FirebaseAuthRepositoryImpl()

    init(session: UserSession, config: FirebaseClientConfig) {
        self.session = session
        let tokens = HlTokenRepositoryImpl(authRepo: authRepo, activeConfig: config)
        let ledger = KtorEntityLedgerRepository(tokenProvider: tokens, baseUrl: AromexConfig.hlBaseURL.absoluteString)
        self.ledger = ledger
        self.buildUseCase = BuildPartyStatementUseCase(ledger: ledger)
        self.pdfRepo = BackendStatementPdfRepository(config: config)

        // Default range: the last 3 months, editable in the sheet.
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd"
        fmt.timeZone = TimeZone(identifier: "UTC")
        let now = Date()
        self.toISO = fmt.string(from: now)
        self.fromISO = fmt.string(from: Calendar.current.date(byAdding: .month, value: -3, to: now) ?? now)
    }

    func generate(entity: Entity) {
        guard !generating else { return }
        let from = fromISO.trimmingCharacters(in: .whitespaces)
        let to = toISO.trimmingCharacters(in: .whitespaces)
        guard !to.isEmpty else {
            errorText = "Choose an end date."
            return
        }
        generating = true
        errorText = nil
        pdfURL = nil
        Task {
            do {
                let document = try await buildUseCase.execute(
                    entity: entity,
                    permissions: session.permissions,
                    fromIso: from.isEmpty ? nil : from,
                    toIso: to,
                    includeNotes: includeNotes,
                    noteByTransactionId: [:]
                )
                let urlString = try await pdfRepo.__render(entityId: entity.id, document: document)
                self.pdfURL = URL(string: urlString)
                self.generating = false
                if self.pdfURL == nil { self.errorText = "The statement URL was invalid." }
            } catch {
                self.generating = false
                self.errorText = self.describe(error)
            }
        }
    }

    private func describe(_ error: Error) -> String {
        let ns = error as NSError
        if let throwable = ns.userInfo["KotlinException"] as? KotlinThrowable {
            return throwable.message ?? "Couldn't generate the statement."
        }
        return ns.localizedDescription
    }

    deinit {
        ledger.close()
    }
}
