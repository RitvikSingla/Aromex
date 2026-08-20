import Foundation
import SharedLogic

/// How the phone's single search box read what was typed (ticket #84). Unlike Desktop's five
/// separate filter controls, phones get **one** box: as-you-type it filters the already-loaded
/// rows locally; on submit it runs a server-side query against full history using the reading
/// below, so a full-history result is reachable and explainable. Kept in the ViewModel layer per
/// the KMP architecture (filter enums aren't shared).
enum SalesSearchKind { case none, imei, invoice, customer }

/// Classify a submitted search string (ticket #84): all digits & 14–17 long → `.imei`; starts
/// with the invoice prefix `INV-` (case-insensitive) → `.invoice`; blank → `.none`; else
/// `.customer` (a free-text name, resolved to entity ids before querying). Pure + deterministic.
func detectSearchKind(_ text: String) -> SalesSearchKind {
    let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
    if t.isEmpty { return .none }
    if t.allSatisfy({ $0.isNumber }) && (14...17).contains(t.count) { return .imei }
    if t.uppercased().hasPrefix("INV-") { return .invoice }
    return .customer
}

/// iOS Sales History ViewModel (ticket #84) — bare-but-stable phone counterpart of the Desktop
/// screen (#83), bound to the **same** shared query layer (no new shared code). Sales are
/// unbounded, so this pages the collection: `QuerySalesUseCase` runs the query and `loadMore`
/// appends the next page via the cursor; nothing is re-fetched to filter client-side. The single
/// search box filters loaded rows instantly (`visibleSales`) and reaches full history on submit
/// (`onSearchSubmit`). Manual DI in `bind`; the unit-tested twin is the Android/Desktop VM.
@MainActor
final class SalesHistoryViewModel: ObservableObject {
    @Published private(set) var isLoading: Bool = true
    @Published private(set) var isLoadingMore: Bool = false
    @Published private(set) var permissionDenied: Bool = false
    @Published var errorMessage: String? = nil
    @Published private(set) var currency: String = ""
    @Published private(set) var timezone: String = "UTC"

    /// Every sale loaded so far for the current query (all pages). Local search derives from this.
    @Published private(set) var sales: [SaleSummary] = []
    @Published private(set) var hasMore: Bool = false
    /// The single search box text — an instant local filter; submit runs the server search.
    @Published var searchText: String = ""
    /// How the last **submitted** server search was read — drives the "Searched by …" hint.
    @Published private(set) var searchKind: SalesSearchKind = .none

    // Detail view.
    @Published private(set) var detailLoading: Bool = false
    @Published private(set) var detail: SaleDetail? = nil
    @Published private(set) var detailInvoice: SaleInvoice = SaleInvoice(status: .pending, number: nil, url: nil, error: nil)
    @Published private(set) var isRetryingInvoice: Bool = false
    @Published private(set) var invoiceRetryError: Bool = false
    @Published private(set) var sellerName: String? = nil

    // Void (ticket #85).
    @Published private(set) var isAdmin: Bool = false
    @Published private(set) var isVoiding: Bool = false
    @Published var voidError: String? = nil

    private var session: UserSession? = nil
    private var config: FirebaseClientConfig? = nil
    private var querySalesUseCase: QuerySalesUseCase? = nil
    private var getSaleUseCase: GetSaleUseCase? = nil
    private var observeSaleInvoiceUseCase: ObserveSaleInvoiceUseCase? = nil
    private var retryInvoiceUseCase: RetryInvoiceUseCase? = nil
    private var voidSaleUseCase: VoidSaleUseCase? = nil
    private let userRepo = FirestoreUserRepository()

    /// Cached parties, for resolving a typed customer name → entity ids (bounded collection).
    private var entities: [Entity] = []
    /// uid → display name, so re-opening a sale by the same seller doesn't re-read `users/{uid}`.
    private var sellerNameCache: [String: String] = [:]

    private var nextCursor: SalesCursor? = nil
    private var entitiesTask: Task<Void, Never>? = nil
    private var invoiceTask: Task<Void, Never>? = nil

    private let walkInLabel = "Walk-in Customer"
    private let customerInLimit = 30
    private let noMatchSentinel = "__no_customer_match__"

    // MARK: - Binding

    func bind(session: UserSession, config: FirebaseClientConfig) {
        if self.session?.uid == session.uid, self.config == config { return }
        self.session = session
        self.config = config
        self.currency = session.currency
        self.timezone = session.timezone
        self.isAdmin = session.role == UserRole.admin

        let sales = BackendSalesRepository(config: config)
        let entityRepo = BackendEntityRepository(config: config, uid: session.uid)
        self.querySalesUseCase = QuerySalesUseCase(repository: sales)
        self.getSaleUseCase = GetSaleUseCase(repository: sales)
        self.observeSaleInvoiceUseCase = ObserveSaleInvoiceUseCase(repository: sales)
        self.retryInvoiceUseCase = RetryInvoiceUseCase(repository: sales)
        self.voidSaleUseCase = VoidSaleUseCase(repository: sales)

        // Cache entities for name → id resolution (bounded; a live stream keeps it fresh). Swallow
        // a PermissionDenied — name search just won't resolve; the sales list (its own gate) works.
        entitiesTask?.cancel()
        entitiesTask = Task { [weak self] in
            guard let self = self else { return }
            do {
                let flow = try ObserveEntitiesUseCase(repository: entityRepo)
                    .execute(session: session, includeArchived: false)
                for try await list in flow { self.entities = list }
            } catch {
                // Leave entities empty; name resolution degrades to ids.
            }
        }

        loadFirstPage()
    }

    // MARK: - Derived

    /// `sales` after the instant local filter (customer / invoice # / item label / IMEI).
    var visibleSales: [SaleSummary] {
        let q = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        if q.isEmpty { return sales }
        return sales.filter { matchesLocalSearch($0, query: q) }
    }

    /// True once a search has narrowed the list — distinguishes "no sales yet" from "no match".
    var hasActiveSearch: Bool {
        !searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || searchKind != .none
    }

    var canRetryInvoice: Bool { detailInvoice.status == .failed && !isRetryingInvoice }

    private func matchesLocalSearch(_ sale: SaleSummary, query: String) -> Bool {
        if customerName(for: sale).localizedCaseInsensitiveContains(query) { return true }
        if let inv = sale.invoiceNumber, inv.localizedCaseInsensitiveContains(query) { return true }
        if sale.itemLabels.contains(where: { $0.localizedCaseInsensitiveContains(query) }) { return true }
        if sale.imeis.contains(where: { $0.localizedCaseInsensitiveContains(query) }) { return true }
        return false
    }

    // MARK: - Search

    /// Submit (keyboard Search): run the server-side smart search over full history. The typed
    /// string is classified and mapped to the matching `SalesQuery` field; a blank submit resets
    /// to the plain newest-first list. The interpretation is surfaced as a hint.
    func onSearchSubmit() {
        searchKind = detectSearchKind(searchText)
        loadFirstPage()
    }

    /// Clear the box and return to the plain newest-first list.
    func clearSearch() {
        searchText = ""
        searchKind = .none
        loadFirstPage()
    }

    /// Translate the submitted search into a `SalesQuery` per its kind. A customer name is resolved
    /// to entity ids against the cached parties (capped at Firestore's `in` limit); no match → a
    /// sentinel id so the query returns nothing rather than everything. SKIE needs every arg.
    private func buildQuery(cursor: SalesCursor?) -> SalesQuery {
        let text = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        var customerIds: [String]? = nil
        var invoiceNumber: String? = nil
        var imei: String? = nil
        switch searchKind {
        case .none: break
        case .imei: imei = text
        case .invoice: invoiceNumber = text
        case .customer:
            let ids = entities
                .filter { $0.name.localizedCaseInsensitiveContains(text) }
                .prefix(customerInLimit)
                .map { $0.id }
            customerIds = ids.isEmpty ? [noMatchSentinel] : Array(ids)
        }
        return SalesQuery(
            customerEntityIds: customerIds,
            invoiceNumber: invoiceNumber,
            imei: imei,
            dateFromMillis: nil,
            dateToMillis: nil,
            onlyWithBalance: false,
            cursor: cursor,
            pageSize: 30
        )
    }

    // MARK: - Paging

    private func loadFirstPage() {
        guard let useCase = querySalesUseCase, let session = session else { return }
        nextCursor = nil
        isLoading = true
        errorMessage = nil
        permissionDenied = false
        Task {
            do {
                let page = try await useCase.execute(session: session, query: buildQuery(cursor: nil))
                self.nextCursor = page.nextCursor
                self.sales = page.sales
                self.hasMore = page.nextCursor != nil
                self.isLoading = false
            } catch {
                self.onLoadFailure(error)
            }
        }
    }

    /// Append the next page as the user nears the end of the list (ticket #84: never blank —
    /// append). No-op while a page is already loading or the end has been reached.
    func loadMoreIfNeeded(current: SaleSummary) {
        guard hasMore, !isLoadingMore, !isLoading else { return }
        // Trigger a few rows from the end of what's currently visible.
        let visible = visibleSales
        guard let idx = visible.firstIndex(where: { $0.saleId == current.saleId }) else { return }
        if idx < visible.count - 3 { return }
        loadMore()
    }

    private func loadMore() {
        guard let useCase = querySalesUseCase, let session = session, let cursor = nextCursor else { return }
        isLoadingMore = true
        Task {
            do {
                let page = try await useCase.execute(session: session, query: buildQuery(cursor: cursor))
                self.nextCursor = page.nextCursor
                self.sales += page.sales
                self.hasMore = page.nextCursor != nil
                self.isLoadingMore = false
            } catch {
                self.onLoadFailure(error)
            }
        }
    }

    private func onLoadFailure(_ error: Error) {
        isLoading = false
        isLoadingMore = false
        let ns = error as NSError
        if ns.kotlinException is PermissionDeniedException {
            permissionDenied = true
            sales = []
            return
        }
        errorMessage = describe(error)
    }

    // MARK: - Customer name resolution

    func customerName(for sale: SaleSummary) -> String {
        resolveCustomerName(entityId: sale.customerEntityId, isWalkIn: sale.isWalkIn, buyerName: sale.buyerName)
    }

    func customerName(for detail: SaleDetail) -> String {
        resolveCustomerName(entityId: detail.customerEntityId, isWalkIn: detail.isWalkIn, buyerName: detail.buyerName)
    }

    private func resolveCustomerName(entityId: String, isWalkIn: Bool, buyerName: String?) -> String {
        if let name = entities.first(where: { $0.id == entityId })?.name, !name.isEmpty { return name }
        if isWalkIn { return (buyerName?.isEmpty == false) ? buyerName! : walkInLabel }
        return (buyerName?.isEmpty == false) ? buyerName! : "—"
    }

    // MARK: - Detail

    func openSale(saleId: String) {
        guard let useCase = getSaleUseCase, let session = session else { return }
        invoiceTask?.cancel()
        detailLoading = true
        detail = nil
        detailInvoice = SaleInvoice(status: .pending, number: nil, url: nil, error: nil)
        isRetryingInvoice = false
        invoiceRetryError = false
        sellerName = nil
        Task {
            do {
                let loaded = try await useCase.execute(session: session, saleId: saleId)
                self.detail = loaded
                self.detailLoading = false
                if let loaded = loaded {
                    self.detailInvoice = loaded.invoice
                    self.resolveSeller(uid: loaded.createdBy)
                    self.observeInvoice(saleId: saleId)
                }
            } catch {
                self.detailLoading = false
                self.errorMessage = describe(error)
            }
        }
    }

    func closeDetail() {
        invoiceTask?.cancel()
        invoiceTask = nil
        detail = nil
        sellerName = nil
        voidError = nil
    }

    /// Resolve a sale's `createdBy` uid to a display name for the "Sold by" line: the session user
    /// directly, others from `users/{uid}` once (cached). Applied only if it's still the open sale.
    private func resolveSeller(uid: String) {
        guard !uid.isEmpty else { sellerName = nil; return }
        if let s = session, s.uid == uid {
            sellerName = s.displayName.isEmpty ? nil : s.displayName
            return
        }
        if let cached = sellerNameCache[uid] { sellerName = cached; return }
        guard let config = config else { return }
        Task {
            let name = try? await self.userRepo.__getUserProfile(config: config, uid: uid)?.displayName
            let resolved = (name?.isEmpty == false) ? name : nil
            if let resolved = resolved { self.sellerNameCache[uid] = resolved }
            if self.detail?.createdBy == uid { self.sellerName = resolved }
        }
    }

    /// Observe the open sale's invoice live so the reused row resolves in place; also patches the
    /// matching list row so a newly-issued number/status shows there too (mirrors Android). Errors
    /// are swallowed.
    private func observeInvoice(saleId: String) {
        guard let useCase = observeSaleInvoiceUseCase else { return }
        invoiceTask?.cancel()
        invoiceTask = Task { [weak self] in
            guard let self = self else { return }
            do {
                let flow = try useCase.execute(saleId: saleId)
                for try await inv in flow {
                    // Resolve the detail's invoice row in place (a slow render, a Retry, or a
                    // reconcile fix all surface here without reopening)...
                    self.detailInvoice = inv
                    // ...and patch the matching list row so a newly-issued number/status shows in
                    // the list too, without waiting for the next page load.
                    self.sales = self.sales.map { $0.saleId == saleId ? Self.withInvoice($0, inv) : $0 }
                }
            } catch {
                // Leave the row as-is; the sale itself is unaffected.
            }
        }
    }

    /// Returns [summary] with its invoice number/status overwritten from [inv]. SKIE exposes no
    /// `copy` on the shared data class, so the row is rebuilt through the full initializer.
    private static func withInvoice(_ summary: SaleSummary, _ inv: SaleInvoice) -> SaleSummary {
        SaleSummary(
            saleId: summary.saleId,
            createdAtMillis: summary.createdAtMillis,
            customerEntityId: summary.customerEntityId,
            isWalkIn: summary.isWalkIn,
            buyerName: summary.buyerName,
            firstItemLabel: summary.firstItemLabel,
            itemCount: summary.itemCount,
            firstImei: summary.firstImei,
            itemLabels: summary.itemLabels,
            imeis: summary.imeis,
            grandTotal: summary.grandTotal,
            amountPaid: summary.amountPaid,
            balanceRemaining: summary.balanceRemaining,
            syncStatus: summary.syncStatus,
            invoiceNumber: inv.number ?? summary.invoiceNumber,
            invoiceStatus: inv.status,
            status: summary.status
        )
    }

    /// Retry a FAILED invoice from history (ticket #84) — same behaviour as the counter (#77):
    /// optimistic PENDING + button lock, the live stream carries the settled result, and a
    /// client-side failure never forces FAILED over an invoice the stream has already settled.
    func retryInvoice() {
        guard let detail = detail, let useCase = retryInvoiceUseCase, canRetryInvoice else { return }
        isRetryingInvoice = true
        invoiceRetryError = false
        detailInvoice = SaleInvoice(status: .pending, number: detailInvoice.number, url: detailInvoice.url, error: nil)
        Task {
            do {
                _ = try await useCase.execute(saleId: detail.saleId)
            } catch {
                if !self.detailInvoice.hasSettled {
                    self.invoiceRetryError = true
                    self.detailInvoice = SaleInvoice(status: .failed, number: self.detailInvoice.number, url: self.detailInvoice.url, error: nil)
                }
            }
            self.isRetryingInvoice = false
        }
    }

    // MARK: - Void (ticket #85)

    /// The open sale can be voided iff the user is an admin and it isn't already VOIDED.
    var canVoidOpenSale: Bool { isAdmin && (detail?.isVoided == false) }

    func clearVoidError() { voidError = nil }

    /// Void the open sale — a full reversal, admin-only, via the `voidSale` callable (which the CF
    /// re-verifies as admin server-side). Optimistically locks the action; on success reloads the
    /// now-VOIDED detail and marks the list row; on failure surfaces the reason for the dialog.
    func voidSale(reason: String) {
        guard let detail = detail, let useCase = voidSaleUseCase, let session = session, !isVoiding else { return }
        let saleId = detail.saleId
        isVoiding = true
        voidError = nil
        Task {
            do {
                try await useCase.execute(session: session, saleId: saleId, reason: reason)
                await self.reloadAfterVoid(saleId: saleId)
                self.isVoiding = false
            } catch {
                self.isVoiding = false
                self.voidError = describe(error)
            }
        }
    }

    /// Re-fetch the voided sale so the detail flips to VOIDED read-only, and mark its list row.
    private func reloadAfterVoid(saleId: String) async {
        guard let useCase = getSaleUseCase, let session = session else { return }
        if let loaded = try? await useCase.execute(session: session, saleId: saleId) {
            self.detail = loaded
        }
        self.sales = self.sales.map { $0.saleId == saleId ? Self.withStatus($0, .voided) : $0 }
    }

    /// Rebuilds a summary with a new lifecycle status (SKIE exposes no `copy` on the data class).
    private static func withStatus(_ s: SaleSummary, _ status: SaleStatus) -> SaleSummary {
        SaleSummary(
            saleId: s.saleId,
            createdAtMillis: s.createdAtMillis,
            customerEntityId: s.customerEntityId,
            isWalkIn: s.isWalkIn,
            buyerName: s.buyerName,
            firstItemLabel: s.firstItemLabel,
            itemCount: s.itemCount,
            firstImei: s.firstImei,
            itemLabels: s.itemLabels,
            imeis: s.imeis,
            grandTotal: s.grandTotal,
            amountPaid: s.amountPaid,
            balanceRemaining: s.balanceRemaining,
            syncStatus: s.syncStatus,
            invoiceNumber: s.invoiceNumber,
            invoiceStatus: s.invoiceStatus,
            status: status
        )
    }

    // MARK: - Helpers

    private func describe(_ error: Error) -> String {
        let ns = error as NSError
        if let throwable = ns.kotlinException as? KotlinThrowable { return throwable.message ?? "Couldn't load sales" }
        return ns.localizedDescription
    }
}
