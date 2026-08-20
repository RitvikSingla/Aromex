import Foundation
import SharedLogic

/// Client-side role filter for the entities list. Lives in the ViewModel layer
/// (not shared) per the ticket.
enum EntitiesFilter: CaseIterable, Identifiable {
    case all, customer, supplier, middleman
    var id: Self { self }
    var label: String {
        switch self {
        case .all: return "All"
        case .customer: return "Customers"
        case .supplier: return "Suppliers"
        case .middleman: return "Middlemen"
        }
    }
    var role: EntityRole? {
        switch self {
        case .all: return nil
        case .customer: return EntityRole.customer
        case .supplier: return EntityRole.supplier
        case .middleman: return EntityRole.middleman
        }
    }
}

/// One merged list row: an entity + its live HL balance (nil until balances load or
/// if HL has no customer yet — e.g. still PENDING).
struct EntityListItem: Identifiable {
    let entity: Entity
    let balance: EntityBalance?
    var id: String { entity.id }
}

@MainActor
final class EntitiesViewModel: ObservableObject {
    // Merged + filtered rows the UI renders.
    @Published private(set) var items: [EntityListItem] = []
    @Published var searchText: String = "" { didSet { recompute() } }
    @Published var filter: EntitiesFilter = .all { didSet { recompute() } }

    @Published private(set) var isLoadingList: Bool = true
    @Published private(set) var isLoadingBalances: Bool = false
    @Published private(set) var balancesError: HlError? = nil
    @Published private(set) var listError: String? = nil
    @Published var actionError: String? = nil

    /// Carry-over #2: a `profiles == NONE` user gets a clean denied state — never a crash.
    @Published private(set) var permissionDenied: Bool = false
    /// True only for `profiles == MANAGE` — gates add/edit/archive in the UI.
    @Published private(set) var canManage: Bool = false

    private let authRepo = FirebaseAuthRepositoryImpl()

    private var session: UserSession? = nil
    private var config: FirebaseClientConfig? = nil
    private var repo: BackendEntityRepository? = nil
    private var ledger: KtorEntityLedgerRepository? = nil   // owns its HttpClient — closed in deinit (carry-over #1)
    private var balancesUseCase: GetEntityBalancesUseCase? = nil
    private var observeTask: Task<Void, Never>? = nil

    private var allEntities: [Entity] = []
    private var balances: [String: EntityBalance] = [:]

    // Bind the active session + config and wire the repo stack via manual DI.
    // Idempotent — only the first bind for a (uid, config) pair wires things up.
    func bind(session: UserSession, config: FirebaseClientConfig) {
        if self.session?.uid == session.uid, self.config == config { return }
        self.session = session
        self.config = config

        // `canManage` is a UI capability (gates add/edit/archive) — not the list rule.
        // The list's permission gate + isActive filter come from ObserveEntitiesUseCase
        // (shared), which throws PermissionDeniedException for profiles == NONE; we catch
        // that below to show the denied state. (Ticket #34: single source of truth.)
        canManage = session.permissions.profiles == PermissionLevel.manage

        let tokens = HlTokenRepositoryImpl(authRepo: authRepo, activeConfig: config)
        let ledger = KtorEntityLedgerRepository(tokenProvider: tokens, baseUrl: AromexConfig.hlBaseURL.absoluteString)
        self.ledger = ledger
        self.balancesUseCase = GetEntityBalancesUseCase(ledger: ledger)
        self.repo = BackendEntityRepository(config: config, uid: session.uid)

        startObserving(session: session)
        loadBalances()
    }

    /// Consume the shared `ObserveEntitiesUseCase` Flow (SKIE `AsyncSequence`). The gate
    /// (profiles == NONE → PermissionDeniedException) and the `isActive` filter both come
    /// from shared code — no duplicated list logic here.
    private func startObserving(session: UserSession) {
        guard let repo = repo else { return }
        observeTask?.cancel()
        isLoadingList = true
        let useCase = ObserveEntitiesUseCase(repository: repo)
        observeTask = Task { [weak self] in
            do {
                let flow = try useCase.execute(session: session, includeArchived: false)
                for try await entities in flow {
                    guard let self = self else { return }
                    self.allEntities = entities.sorted { $0.name.lowercased() < $1.name.lowercased() }
                    self.isLoadingList = false
                    self.listError = nil
                    self.permissionDenied = false
                    self.recompute()
                }
            } catch {
                guard let self = self else { return }
                self.isLoadingList = false
                if self.isPermissionDenied(error) {
                    self.permissionDenied = true
                } else {
                    self.listError = self.describe(error)
                }
            }
        }
    }

    /// Fetch all HL balances once and merge by externalId (== entity id). Called on
    /// bind, on pull-to-refresh, and after a save.
    func loadBalances() {
        guard let useCase = balancesUseCase, let session = session else { return }
        if isLoadingBalances { return }
        isLoadingBalances = true
        balancesError = nil
        Task {
            do {
                let result = try await useCase.execute(session: session)
                self.balances = result
                self.isLoadingBalances = false
                self.balancesError = nil
                self.recompute()
            } catch {
                self.isLoadingBalances = false
                self.balancesError = mapHlError(error)
            }
        }
    }

    /// A single party's fresh balance for the detail screen.
    func fetchBalance(id: String) async -> EntityBalance? {
        guard let useCase = balancesUseCase, let session = session else { return nil }
        return try? await useCase.executeSingle(session: session, externalId: id)
    }

    /// Soft-archive via the shared use case (guards Walk-in + MANAGE). The live
    /// listener drops the row automatically once Firestore reflects isActive=false.
    func archive(_ entity: Entity) {
        guard let session = session, let repo = repo else { return }
        let useCase = ArchiveEntityUseCase(repository: repo)
        Task {
            do {
                try await useCase.execute(session: session, entity: entity)
            } catch {
                self.actionError = describe(error)
            }
        }
    }

    // MARK: - Client-side merge / search / filter (no re-fetch)

    private func recompute() {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let roleFilter = filter.role
        let filtered = allEntities.filter { entity in
            matchesRole(entity, roleFilter) && matchesSearch(entity, query)
        }
        items = filtered.map { EntityListItem(entity: $0, balance: balances[$0.id]) }
    }

    private func matchesRole(_ entity: Entity, _ role: EntityRole?) -> Bool {
        guard let role = role else { return true }
        return entity.roles.contains(role)
    }

    private func matchesSearch(_ entity: Entity, _ query: String) -> Bool {
        if query.isEmpty { return true }
        if entity.name.lowercased().contains(query) { return true }
        if entity.phones.contains(where: { $0.lowercased().contains(query) }) { return true }
        if entity.roles.contains(where: { $0.name.lowercased().contains(query) }) { return true }
        return false
    }

    // MARK: - Error mapping

    private func mapHlError(_ error: Error) -> HlError {
        let ns = error as NSError
        if let ex = ns.userInfo["KotlinException"] as? HlException {
            return ex.error
        }
        return HlError.Unexpected(message: error.localizedDescription)
    }

    /// The observe Flow surfaces a shared `PermissionDeniedException` (profiles == NONE)
    /// as an NSError carrying the Kotlin exception. Detect it to show the denied state.
    private func isPermissionDenied(_ error: Error) -> Bool {
        return (error as NSError).userInfo["KotlinException"] is PermissionDeniedException
    }

    private func describe(_ error: Error) -> String {
        let ns = error as NSError
        if let throwable = ns.userInfo["KotlinException"] as? KotlinThrowable {
            return throwable.message ?? "Something went wrong"
        }
        return ns.localizedDescription
    }

    deinit {
        // Carry-over #1: this VM owns the KtorEntityLedgerRepository's HttpClient, so
        // it MUST close it on clear to avoid a connection leak. Cancelling the observe
        // Task tears down the shared Flow, which detaches the Firestore listener.
        observeTask?.cancel()
        ledger?.close()
    }
}
