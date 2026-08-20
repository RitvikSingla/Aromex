import Foundation
import SharedLogic

/// One product-list row: the SKU + its in-stock unit count (grouped in memory).
struct SkuRow: Identifiable {
    let product: Product
    let inStockCount: Int
    var id: String { product.productId }
}

/// Live inventory list + edit/archive (test-only UI). Mirrors the Android
/// `InventoryListViewModel`: the VM is the cache — it holds the live product stream and the
/// in-stock-serial stream (both Firestore snapshot listeners via `ObserveInventoryUseCase`),
/// **groups serials by `productId` in memory** for stock counts, and searches client-side
/// (no re-fetch). Mutations go through the shared use cases (which enforce the MANAGE gate).
@MainActor
final class InventoryListViewModel: ObservableObject {
    @Published private(set) var rows: [SkuRow] = []
    @Published var searchText: String = "" { didSet { recompute() } }

    @Published private(set) var canManage: Bool = false
    @Published private(set) var permissionDenied: Bool = false
    @Published private(set) var listError: String? = nil
    @Published var actionError: String? = nil

    private var session: UserSession? = nil
    private var config: FirebaseClientConfig? = nil
    private var repo: BackendInventoryRepository? = nil

    private var productsTask: Task<Void, Never>? = nil
    private var serialsTask: Task<Void, Never>? = nil

    private var allProducts: [Product] = []
    private var inStock: [Serial] = []

    func bind(session: UserSession, config: FirebaseClientConfig) {
        if self.session?.uid == session.uid, self.config == config { return }
        self.session = session
        self.config = config
        self.canManage = session.permissions.inventory == PermissionLevel.manage

        let inv = BackendInventoryRepository(config: config, uid: session.uid)
        self.repo = inv
        startObserving(session: session, repo: inv)
    }

    private func startObserving(session: UserSession, repo: BackendInventoryRepository) {
        let useCase = ObserveInventoryUseCase(repository: repo)
        productsTask?.cancel()
        serialsTask?.cancel()

        productsTask = Task { [weak self] in
            do {
                let flow = try useCase.observeProducts(session: session, includeArchived: false)
                for try await list in flow {
                    guard let self = self else { return }
                    self.allProducts = list
                    self.permissionDenied = false
                    self.recompute()
                }
            } catch {
                guard let self = self else { return }
                if self.isPermissionDenied(error) { self.permissionDenied = true }
                else { self.listError = self.describe(error) }
            }
        }

        serialsTask = Task { [weak self] in
            do {
                let flow = try useCase.observeInStockSerials(session: session)
                for try await list in flow {
                    guard let self = self else { return }
                    self.inStock = list
                    self.recompute()
                }
            } catch {
                guard let self = self else { return }
                if self.isPermissionDenied(error) { self.permissionDenied = true }
                else { self.listError = self.describe(error) }
            }
        }
    }

    /// In-stock units for a SKU (backs drill-in).
    func units(for productId: String) -> [Serial] {
        return inStock.filter { $0.productId == productId }
    }

    // MARK: - Mutations (shared use cases enforce the MANAGE gate)

    func editSellingPrice(productId: String, price: String) {
        guard let session = session, let repo = repo else { return }
        Task {
            do {
                try await UpdateProductUseCase(repository: repo)
                    .execute(session: session, productId: productId, edits: ProductEdits(defaultSellingPrice: price))
            } catch { self.actionError = describe(error) }
        }
    }

    func archiveProduct(productId: String) {
        guard let session = session, let repo = repo else { return }
        Task {
            do { try await ArchiveProductUseCase(repository: repo).execute(session: session, productId: productId) }
            catch { self.actionError = describe(error) }
        }
    }

    func setUnitStatus(serialId: String, status: SerialStatus) {
        guard let session = session, let repo = repo else { return }
        Task {
            do { try await SetUnitStatusUseCase(repository: repo).execute(session: session, serialId: serialId, status: status) }
            catch { self.actionError = describe(error) }
        }
    }

    func archiveUnit(serialId: String) {
        guard let session = session, let repo = repo else { return }
        Task {
            do { try await ArchiveUnitUseCase(repository: repo).execute(session: session, serialId: serialId) }
            catch { self.actionError = describe(error) }
        }
    }

    func toggleCondition(serialId: String, to condition: Condition) {
        guard let session = session, let repo = repo else { return }
        Task {
            do {
                try await UpdateUnitUseCase(repository: repo)
                    .execute(session: session, serialId: serialId,
                             edits: SerialEdits(cost: nil, condition: condition, location: nil, imei: nil))
            } catch { self.actionError = describe(error) }
        }
    }

    // MARK: - Client-side search (no re-fetch)

    private func recompute() {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        var counts: [String: Int] = [:]
        for serial in inStock { counts[serial.productId, default: 0] += 1 }
        rows = allProducts
            .filter { matches($0, query) }
            .map { SkuRow(product: $0, inStockCount: counts[$0.productId] ?? 0) }
            .sorted { $0.product.brandName.lowercased() < $1.product.brandName.lowercased() }
    }

    private func matches(_ product: Product, _ query: String) -> Bool {
        if query.isEmpty { return true }
        let q = query.lowercased()
        if product.attributes.values.contains(where: { $0.name.lowercased().contains(q) }) { return true }
        return inStock.contains { $0.productId == product.productId && $0.imei.lowercased().contains(q) }
    }

    private func isPermissionDenied(_ error: Error) -> Bool {
        return (error as NSError).kotlinException is PermissionDeniedException
    }

    private func describe(_ error: Error) -> String {
        let ns = error as NSError
        if let throwable = ns.kotlinException as? KotlinThrowable { return throwable.message ?? "Something went wrong" }
        return ns.localizedDescription
    }

    deinit {
        productsTask?.cancel()
        serialsTask?.cancel()
    }
}

extension Product {
    /// "Apple · iPhone 15 · 128 GB · Pink · Unlocked" for the bare list/detail rows.
    var skuLabel: String {
        let parts = AttributeType.companion.SKU_DEFINING.compactMap { attributes[$0]?.name }
        return parts.isEmpty ? "(incomplete SKU)" : parts.joined(separator: " · ")
    }
    var brandName: String { attributes[AttributeType.brand]?.name ?? "" }
}
