import SwiftUI
import SharedLogic

/// Bare test-only Inventory feature (#45): list → drill-in → add-stock (with scan) →
/// edit/archive. Plain SwiftUI that exercises the repo/use-case/scanner stack — no theming
/// or polish. The real Inventory UI is a later ticket.
struct InventoryView: View {
    let session: UserSession
    let config: FirebaseClientConfig

    @StateObject private var listVM = InventoryListViewModel()
    @StateObject private var addVM = AddStockViewModel()
    @Environment(\.dismiss) private var dismiss
    @State private var showAdd = false

    var body: some View {
        NavigationStack {
            Group {
                if listVM.permissionDenied {
                    Text("You don't have access to inventory.").foregroundStyle(.secondary)
                } else {
                    List {
                        if let error = listVM.listError {
                            Text("Error: \(error)").foregroundStyle(.red)
                        }
                        ForEach(listVM.rows) { row in
                            NavigationLink {
                                SkuDetailView(row: row, listVM: listVM, onAddUnits: {
                                    addVM.reset()
                                    addVM.startAddUnits(productId: row.product.productId, attributes: row.product.attributes, sellingPrice: row.product.defaultSellingPrice)
                                    showAdd = true
                                })
                            } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(row.product.skuLabel).font(.headline)
                                    Text("\(row.inStockCount) in stock · $\(row.product.defaultSellingPrice)")
                                        .font(.subheadline).foregroundStyle(.secondary)
                                    if !row.product.isActive { Text("ARCHIVED").font(.caption).foregroundStyle(.orange) }
                                }
                            }
                        }
                    }
                    .searchable(text: $listVM.searchText, prompt: "Brand / model / IMEI / color / carrier")
                }
            }
            .navigationTitle("Inventory")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
                if listVM.canManage {
                    ToolbarItem(placement: .primaryAction) {
                        Button {
                            addVM.reset()
                            showAdd = true
                        } label: { Image(systemName: "plus") }
                    }
                }
            }
            .navigationDestination(isPresented: $showAdd) {
                AddStockView(vm: addVM, existingProducts: listVM.rows.map { $0.product }) { showAdd = false }
            }
            .alert("Action failed", isPresented: .constant(listVM.actionError != nil)) {
                Button("OK") { listVM.actionError = nil }
            } message: {
                Text(listVM.actionError ?? "")
            }
        }
        .onAppear {
            listVM.bind(session: session, config: config)
            addVM.bind(session: session, config: config)
        }
    }
}

/// Drill-in: a SKU's units + price edit + archive.
private struct SkuDetailView: View {
    let row: SkuRow
    @ObservedObject var listVM: InventoryListViewModel
    let onAddUnits: () -> Void

    @State private var priceText: String = ""

    var body: some View {
        List {
            Section {
                Text(row.product.skuLabel).font(.headline)
                if listVM.canManage {
                    HStack {
                        TextField("Selling price", text: $priceText)
                            .keyboardType(.decimalPad)
                        Button("Save") { listVM.editSellingPrice(productId: row.product.productId, price: priceText) }
                            .buttonStyle(.borderedProminent)
                    }
                    Button("Archive SKU", role: .destructive) {
                        listVM.archiveProduct(productId: row.product.productId)
                    }
                    Button("Add units") { onAddUnits() }
                }
            }
            Section("Units") {
                let units = listVM.units(for: row.product.productId)
                if units.isEmpty {
                    Text("No in-stock units.").foregroundStyle(.secondary)
                }
                ForEach(units, id: \.serialId) { unit in
                    VStack(alignment: .leading, spacing: 4) {
                        Text("IMEI \(unit.imei)").font(.subheadline.weight(.semibold))
                        Text("$\(unit.cost) · \(unit.condition.name) · \(unit.status.name) · \(unit.location.name)")
                            .font(.caption).foregroundStyle(.secondary)
                        if listVM.canManage {
                            HStack {
                                Button("Mark SOLD") { listVM.setUnitStatus(serialId: unit.serialId, status: SerialStatus.sold) }
                                Button("Archive") { listVM.archiveUnit(serialId: unit.serialId) }
                                Button("Toggle N/U") {
                                    let next: Condition = unit.condition == Condition.theNew ? Condition.used : Condition.theNew
                                    listVM.toggleCondition(serialId: unit.serialId, to: next)
                                }
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }
                    }
                }
            }
        }
        .navigationTitle("SKU")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { priceText = row.product.defaultSellingPrice }
    }
}

// MARK: - Add Stock two-screen flow

/// Router: switches between entry (Screen 1) and review (Screen 2), and
/// dismisses when the VM signals done.
private struct AddStockView: View {
    @ObservedObject var vm: AddStockViewModel
    let existingProducts: [Product]
    let onClose: () -> Void

    var body: some View {
        Group {
            switch vm.route {
            case .entry:
                AddInventoryEntryView(vm: vm, existingProducts: existingProducts, onClose: onClose)
            case .paste:
                AddInventoryPasteView(vm: vm)
            case .review:
                ReviewConfirmView(vm: vm, onBack: { vm.addMoreFromReview() })
            }
        }
        .onChange(of: vm.done) { done in
            if done { onClose() }
        }
    }
}

/// Screen 1 — pick SKU attributes, set batch defaults, stage IMEIs.
private struct AddInventoryEntryView: View {
    @ObservedObject var vm: AddStockViewModel
    let existingProducts: [Product]
    let onClose: () -> Void

    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexDimensions) private var dimensions
    @Environment(\.aromexTypography) private var typography

    @State private var showDiscardAlert = false
    @State private var scanning = false
    @State private var kbHeight: CGFloat = 0

    var body: some View {
        VStack(spacing: 0) {
            entryHeader

            ScrollView {
                VStack(alignment: .leading, spacing: dimensions.space24) {

                    if !vm.attributesLocked {
                        pasteEntryButton

                        // PHONE section
                        sectionLabel(loc.t(Strings.shared.inventory_sku_section))

                        FilterableDropdownField(
                            label: loc.t(Strings.shared.inventory_brand_label),
                            items: vm.options(AttributeType.brand),
                            selected: vm.picked[AttributeType.brand],
                            onSelect: { vm.pick(AttributeType.brand, $0) },
                            onClear: { vm.clearPick(AttributeType.brand) },
                            onAddNew: { vm.addNewAttribute(AttributeType.brand, name: $0) },
                            placeholder: loc.t(Strings.shared.inventory_brand_placeholder),
                            enabled: true
                        )

                        FilterableDropdownField(
                            label: loc.t(Strings.shared.inventory_model_label),
                            items: vm.options(AttributeType.model),
                            selected: vm.picked[AttributeType.model],
                            onSelect: { vm.pick(AttributeType.model, $0) },
                            onClear: { vm.clearPick(AttributeType.model) },
                            onAddNew: { vm.addNewAttribute(AttributeType.model, name: $0) },
                            placeholder: vm.picked[AttributeType.brand] == nil
                                ? loc.t(Strings.shared.inventory_model_hint_brand)
                                : loc.t(Strings.shared.inventory_model_placeholder),
                            enabled: vm.picked[AttributeType.brand] != nil
                        )

                        FilterableDropdownField(
                            label: loc.t(Strings.shared.inventory_capacity_label),
                            items: vm.options(AttributeType.capacity),
                            selected: vm.picked[AttributeType.capacity],
                            onSelect: { vm.pick(AttributeType.capacity, $0) },
                            onClear: { vm.clearPick(AttributeType.capacity) },
                            onAddNew: { vm.addNewAttribute(AttributeType.capacity, name: $0) },
                            placeholder: loc.t(Strings.shared.inventory_capacity_placeholder),
                            enabled: true
                        )

                        FilterableDropdownField(
                            label: loc.t(Strings.shared.inventory_color_label),
                            items: vm.options(AttributeType.color),
                            selected: vm.picked[AttributeType.color],
                            onSelect: { vm.pick(AttributeType.color, $0) },
                            onClear: { vm.clearPick(AttributeType.color) },
                            onAddNew: { vm.addNewAttribute(AttributeType.color, name: $0) },
                            placeholder: loc.t(Strings.shared.inventory_color_placeholder),
                            enabled: true
                        )

                        FilterableDropdownField(
                            label: loc.t(Strings.shared.inventory_carrier_label),
                            items: vm.options(AttributeType.carrier),
                            selected: vm.picked[AttributeType.carrier],
                            onSelect: { vm.pick(AttributeType.carrier, $0) },
                            onClear: { vm.clearPick(AttributeType.carrier) },
                            onAddNew: { vm.addNewAttribute(AttributeType.carrier, name: $0) },
                            placeholder: loc.t(Strings.shared.inventory_carrier_placeholder),
                            enabled: true
                        )

                        sellingPriceField
                    } else {
                        sectionLabel(loc.t(Strings.shared.inventory_sku_section))
                        Text(vm.picked.skuLabel)
                            .font(typography.body.weight(.semibold))
                            .foregroundStyle(colors.textPrimary)
                            .padding(.horizontal, dimensions.screenPadding)
                    }

                    // BATCH DETAILS section
                    sectionLabel(loc.t(Strings.shared.inventory_batch_section))

                    costField
                    conditionPicker
                    FilterableDropdownField(
                        label: loc.t(Strings.shared.inventory_location_label),
                        items: vm.options(AttributeType.location),
                        selected: vm.batchLocation,
                        onSelect: { vm.setBatchLocation($0) },
                        onClear: { vm.clearBatchLocation() },
                        onAddNew: { vm.addNewAttribute(AttributeType.location, name: $0) },
                        placeholder: loc.t(Strings.shared.inventory_location_placeholder),
                        enabled: true
                    )

                    // ADD IMEIs section
                    sectionLabel(loc.t(Strings.shared.inventory_imei_section))

                    imeiRow
                    stagedList
                }
                .padding(.horizontal, dimensions.screenPadding)
                .padding(.top, dimensions.space16)
                .padding(.bottom, max(kbHeight + dimensions.space16, dimensions.buttonHeight + dimensions.space24 + 16))
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .background(colors.background.ignoresSafeArea())
        .overlay(alignment: .bottom) { reviewButton }
        .ignoresSafeArea(.keyboard, edges: .bottom)
        .navigationBarHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { n in
            if let rect = n.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect {
                withAnimation { kbHeight = rect.height }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            withAnimation { kbHeight = 0 }
        }
        .onChange(of: pickedSkuKey) { key in
            guard !vm.attributesLocked else { return }
            guard AttributeType.companion.SKU_DEFINING.allSatisfy({ vm.picked[$0] != nil }) else { return }
            if let match = existingProducts.first(where: { $0.productId == key }) {
                vm.defaultSellingPrice = match.defaultSellingPrice
            }
        }
        .fullScreenCover(isPresented: $scanning) {
            ImeiScannerScreen { result in
                scanning = false
                if case let .scanned(imei) = result {
                    vm.pendingImei = imei.trimmingCharacters(in: .whitespacesAndNewlines)
                    vm.checkAndAddImei()
                }
            }
        }
        .confirmationDialog(
            loc.t(Strings.shared.inventory_discard_title),
            isPresented: $showDiscardAlert,
            titleVisibility: .visible
        ) {
            Button(loc.t(Strings.shared.inventory_discard_confirm), role: .destructive) { onClose() }
            Button(loc.t(Strings.shared.inventory_discard_cancel), role: .cancel) { }
        } message: {
            Text(loc.t(Strings.shared.inventory_discard_body))
        }
    }

    // MARK: - Header

    private var entryHeader: some View {
        HStack(alignment: .top) {
            Button {
                if vm.hasUnsavedChanges { showDiscardAlert = true }
                else { onClose() }
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(colors.onBrand)
                    .frame(width: 40, height: 40)
                    .background(colors.onBrand.opacity(0.18), in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(loc.t(Strings.shared.inventory_close_cd))

            VStack(alignment: .leading, spacing: 2) {
                Text("INVENTORY")
                    .font(.system(size: 12, weight: .bold))
                    .tracking(1.5)
                    .foregroundStyle(colors.onBrand.opacity(0.7))
                Text(loc.t(Strings.shared.inventory_add_title))
                    .font(.system(size: 26, weight: .bold))
                    .foregroundStyle(colors.onBrand)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            Spacer(minLength: 8)
        }
        .padding(.horizontal, dimensions.screenPadding)
        .padding(.vertical, dimensions.space16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(
                colors: [colors.headerGradientStart, colors.headerGradientEnd],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
        )
    }

    // MARK: - Selling price

    @FocusState private var sellingPriceFocused: Bool
    private var sellingPriceField: some View {
        LabeledTextField(
            label: loc.t(Strings.shared.inventory_selling_price_label),
            value: $vm.defaultSellingPrice,
            placeholder: loc.t(Strings.shared.inventory_selling_price_placeholder),
            keyboard: .decimalPad,
            focus: $sellingPriceFocused
        )
    }

    // MARK: - Batch cost

    @FocusState private var costFocused: Bool
    private var costField: some View {
        LabeledTextField(
            label: loc.t(Strings.shared.inventory_cost_label),
            value: $vm.batchCost,
            placeholder: loc.t(Strings.shared.inventory_cost_placeholder),
            keyboard: .decimalPad,
            focus: $costFocused
        )
    }

    // MARK: - Condition picker

    private var conditionPicker: some View {
        VStack(alignment: .leading, spacing: dimensions.space8) {
            Text(loc.t(Strings.shared.inventory_condition_label))
                .font(typography.fieldLabel)
                .tracking(0.5)
                .foregroundStyle(colors.textTertiary)
            Picker("", selection: $vm.batchCondition) {
                Text(loc.t(Strings.shared.inventory_condition_new)).tag(Condition.theNew)
                Text(loc.t(Strings.shared.inventory_condition_used)).tag(Condition.used)
            }
            .pickerStyle(.segmented)
        }
    }

    // MARK: - IMEI row

    @FocusState private var imeiFocused: Bool
    private var imeiRow: some View {
        VStack(alignment: .leading, spacing: dimensions.space8) {
            HStack(spacing: dimensions.space8) {
                TextField(loc.t(Strings.shared.inventory_imei_placeholder), text: $vm.pendingImei)
                    .keyboardType(.numberPad)
                    .font(typography.body)
                    .foregroundStyle(colors.textPrimary)
                    .focused($imeiFocused)
                    .padding(.horizontal, dimensions.space16)
                    .frame(height: dimensions.fieldHeight)
                    .background(
                        RoundedRectangle(cornerRadius: dimensions.radiusField)
                            .fill(colors.surface)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: dimensions.radiusField)
                            .stroke(colors.border, lineWidth: dimensions.borderField)
                    )
                    .onSubmit { vm.checkAndAddImei() }
                    .toolbar {
                        if imeiFocused {
                            ToolbarItemGroup(placement: .keyboard) {
                                Button("Add") { imeiFocused = false; vm.checkAndAddImei() }
                                Spacer()
                            }
                        }
                    }

                // Check / add button
                Button {
                    imeiFocused = false
                    vm.checkAndAddImei()
                } label: {
                    Image(systemName: vm.imeiCheckState == .checking ? "ellipsis" : "checkmark")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(colors.onBrand)
                        .frame(width: 44, height: 44)
                        .background(colors.brand, in: Circle())
                }
                .buttonStyle(.plain)
                .disabled(vm.pendingImei.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || vm.imeiCheckState == .checking)
                .accessibilityLabel(loc.t(Strings.shared.inventory_imei_add_cd))

                // Camera / scan button
                Button {
                    imeiFocused = false
                    scanning = true
                } label: {
                    Image(systemName: "barcode.viewfinder")
                        .font(.system(size: 18))
                        .foregroundStyle(colors.brand)
                        .frame(width: 44, height: 44)
                        .background(colors.brand.opacity(0.1), in: Circle())
                }
                .buttonStyle(.plain)
            }

            // Inline state feedback
            switch vm.imeiCheckState {
            case .invalid:
                imeiHint(loc.t(Strings.shared.inventory_imei_error_invalid), isError: true)
            case .alreadyInBatch:
                imeiHint(loc.t(Strings.shared.inventory_imei_error_in_batch), isError: true)
            case .alreadyInStock:
                imeiHint(loc.t(Strings.shared.inventory_imei_error_in_stock), isError: true)
            case .checking:
                imeiHint(loc.t(Strings.shared.inventory_imei_checking), isError: false)
            case .idle, .available:
                EmptyView()
            }

            // Staged count badge
            if !vm.stagedImeis.isEmpty {
                let count = vm.stagedImeis.count
                let label = count == 1
                    ? loc.t(Strings.shared.inventory_imei_count_one)
                    : loc.t(Strings.shared.inventory_imei_count).replacingOccurrences(of: "{0}", with: "\(count)")
                Text(label)
                    .font(typography.hint)
                    .foregroundStyle(colors.brand)
            }
        }
    }

    private func imeiHint(_ text: String, isError: Bool) -> some View {
        HStack(spacing: 4) {
            Image(systemName: isError ? "exclamationmark.circle.fill" : "arrow.clockwise.circle.fill")
                .font(.system(size: 13))
                .foregroundStyle(isError ? colors.error : colors.textSecondary)
            Text(text)
                .font(typography.hint)
                .foregroundStyle(isError ? colors.error : colors.textSecondary)
        }
    }

    // MARK: - Staged IMEI list

    private var stagedList: some View {
        ForEach(vm.stagedImeis, id: \.self) { imei in
            HStack {
                Text(imei)
                    .font(typography.body.monospacedDigit())
                    .foregroundStyle(colors.textPrimary)
                Spacer()
                Button {
                    vm.removeImei(imei)
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(colors.textTertiary)
                        .frame(width: 36, height: 36)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(loc.t(Strings.shared.inventory_remove_unit_cd))
            }
            .padding(.horizontal, dimensions.space12)
            .padding(.vertical, dimensions.space8)
            .background(colors.surface, in: RoundedRectangle(cornerRadius: dimensions.radiusCard))
            .overlay(
                RoundedRectangle(cornerRadius: dimensions.radiusCard)
                    .stroke(colors.border, lineWidth: 0.5)
            )
        }
    }

    // MARK: - Review button (pinned)

    private var reviewButton: some View {
        VStack(spacing: 0) {
            PrimaryButton(
                label: loc.t(Strings.shared.inventory_review_action),
                action: { vm.proceedToReview() },
                enabled: !vm.stagedImeis.isEmpty && vm.batchLocation != nil
            )
        }
        .padding(.horizontal, dimensions.screenPadding)
        .padding(.top, dimensions.space12)
        .padding(.bottom, dimensions.space16)
        .frame(maxWidth: .infinity)
        .background(colors.background.ignoresSafeArea(edges: .bottom))
    }

    // MARK: - Helpers

    // Mirrors SkuKey.build(): attribute IDs in SKU_DEFINING order, joined by "_"
    private var pickedSkuKey: String {
        let ids = AttributeType.companion.SKU_DEFINING.compactMap { type -> String? in
            let id = vm.picked[type]?.attributeId.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return id.isEmpty ? nil : id
        }
        guard ids.count == AttributeType.companion.SKU_DEFINING.count else { return "" }
        return ids.joined(separator: "_")
    }

    private func sectionLabel(_ text: String) -> some View {
        HStack(spacing: 10) {
            Text(text.uppercased())
                .font(.system(size: 11, weight: .bold))
                .tracking(1.2)
                .foregroundStyle(colors.brand)
                .fixedSize()
            Rectangle()
                .fill(colors.border)
                .frame(height: 1)
        }
    }

    // MARK: - Paste-from-SICKW entry

    private var pasteEntryButton: some View {
        Button {
            vm.goToPaste()
        } label: {
            HStack(spacing: dimensions.space12) {
                Image(systemName: "doc.on.clipboard")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(colors.brand)
                    .frame(width: 40, height: 40)
                    .background(colors.brand.opacity(0.12), in: RoundedRectangle(cornerRadius: dimensions.radiusField))
                VStack(alignment: .leading, spacing: 2) {
                    Text(loc.t(Strings.shared.inventory_paste_button))
                        .font(typography.body.weight(.semibold))
                        .foregroundStyle(colors.textPrimary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                    Text(loc.t(Strings.shared.inventory_paste_subtitle))
                        .font(typography.hint)
                        .foregroundStyle(colors.textSecondary)
                        .lineLimit(2)
                        .truncationMode(.tail)
                }
                Spacer(minLength: 4)
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(colors.textTertiary)
            }
            .padding(dimensions.space12)
            .frame(maxWidth: .infinity)
            .background(colors.surface, in: RoundedRectangle(cornerRadius: dimensions.radiusCard))
            .overlay(
                RoundedRectangle(cornerRadius: dimensions.radiusCard)
                    .stroke(colors.brand.opacity(0.35), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(loc.t(Strings.shared.inventory_paste_button))
        .accessibilityHint(loc.t(Strings.shared.inventory_paste_subtitle))
    }
}

/// Paste-from-SICKW screen — a proper screen (not a sheet) whose text lives in the VM,
/// so navigating away and back never loses it. "Parse & add" is the only action that runs
/// the parser and moves to review; pasting text alone never navigates.
private struct AddInventoryPasteView: View {
    @ObservedObject var vm: AddStockViewModel

    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexDimensions) private var dimensions
    @Environment(\.aromexTypography) private var typography

    @FocusState private var editorFocused: Bool
    @State private var kbHeight: CGFloat = 0

    var body: some View {
        VStack(spacing: 0) {
            header

            ScrollView {
                VStack(alignment: .leading, spacing: dimensions.space16) {
                    Text(loc.t(Strings.shared.inventory_paste_subtitle))
                        .font(typography.hint)
                        .foregroundStyle(colors.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)

                    editor

                    if vm.pasteError == "none" {
                        pasteHint(loc.t(Strings.shared.inventory_parse_none), isError: true)
                    } else if let err = vm.pasteError, err.hasPrefix("network:") {
                        pasteHint(loc.t(Strings.shared.inventory_save_error_network) + " " + String(err.dropFirst("network:".count)), isError: true)
                    } else if vm.pasteText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        pasteHint(loc.t(Strings.shared.inventory_paste_empty), isError: false)
                    }
                }
                .padding(.horizontal, dimensions.screenPadding)
                .padding(.top, dimensions.space16)
                .padding(.bottom, max(kbHeight + dimensions.space16, dimensions.buttonHeight + dimensions.space24 + 16))
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .background(colors.background.ignoresSafeArea())
        .overlay(alignment: .bottom) { parseBar }
        .ignoresSafeArea(.keyboard, edges: .bottom)
        .navigationBarHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { n in
            if let rect = n.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect {
                withAnimation { kbHeight = rect.height }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            withAnimation { kbHeight = 0 }
        }
    }

    // MARK: - Header

    private var header: some View {
        HStack(alignment: .top) {
            Button {
                editorFocused = false
                vm.addMoreFromReview() // returns to entry (route = .entry); paste text is preserved in VM
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(colors.onBrand)
                    .frame(width: 40, height: 40)
                    .background(colors.onBrand.opacity(0.15), in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(loc.t(Strings.shared.inventory_back_cd))

            VStack(alignment: .leading, spacing: 2) {
                Text("INVENTORY")
                    .font(.system(size: 12, weight: .bold))
                    .tracking(1.5)
                    .foregroundStyle(colors.onBrand.opacity(0.7))
                Text(loc.t(Strings.shared.inventory_paste_title))
                    .font(.system(size: 26, weight: .bold))
                    .foregroundStyle(colors.onBrand)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            Spacer(minLength: 8)
        }
        .padding(.horizontal, dimensions.screenPadding)
        .padding(.vertical, dimensions.space16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(
                colors: [colors.headerGradientStart, colors.headerGradientEnd],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
        )
    }

    // MARK: - Multiline paste editor

    private var editor: some View {
        ZStack(alignment: .topLeading) {
            RoundedRectangle(cornerRadius: dimensions.radiusField)
                .fill(colors.surface)
            RoundedRectangle(cornerRadius: dimensions.radiusField)
                .stroke(colors.border, lineWidth: dimensions.borderField)

            if vm.pasteText.isEmpty {
                Text(loc.t(Strings.shared.inventory_paste_hint))
                    .font(typography.body)
                    .foregroundStyle(colors.textTertiary)
                    .padding(.horizontal, dimensions.space16 + 4)
                    .padding(.vertical, dimensions.space16 + 8)
                    .allowsHitTesting(false)
            }

            TextEditor(text: $vm.pasteText)
                .focused($editorFocused)
                .font(typography.body.monospaced())
                .foregroundStyle(colors.textPrimary)
                .scrollContentBackground(.hidden)
                .background(Color.clear)
                .padding(.horizontal, dimensions.space12)
                .padding(.vertical, dimensions.space8)
                .frame(minHeight: 220)
                .onChange(of: vm.pasteText) { _ in
                    if vm.pasteError != nil { vm.pasteError = nil }
                }
                .toolbar {
                    if editorFocused {
                        ToolbarItemGroup(placement: .keyboard) {
                            Spacer()
                            Button(loc.t(Strings.shared.inventory_dialog_save)) { editorFocused = false }
                        }
                    }
                }
        }
        .frame(minHeight: 220)
        .accessibilityLabel(loc.t(Strings.shared.inventory_paste_hint))
    }

    private func pasteHint(_ text: String, isError: Bool) -> some View {
        HStack(spacing: 6) {
            Image(systemName: isError ? "exclamationmark.circle.fill" : "info.circle")
                .font(.system(size: 13))
                .foregroundStyle(isError ? colors.error : colors.textTertiary)
            Text(text)
                .font(typography.hint)
                .foregroundStyle(isError ? colors.error : colors.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: - Parse / clear bar (pinned above keyboard)

    private var parseBar: some View {
        let hasText = !vm.pasteText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        return VStack(spacing: dimensions.space8) {
            if hasText {
                Button {
                    vm.pasteText = ""
                    vm.pasteError = nil
                } label: {
                    Text(loc.t(Strings.shared.inventory_paste_clear))
                        .font(typography.button)
                        .foregroundStyle(colors.textSecondary)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.plain)
            }
            PrimaryButton(
                label: loc.t(Strings.shared.inventory_paste_parse),
                loadingLabel: loc.t(Strings.shared.inventory_paste_parse),
                action: { editorFocused = false; vm.parseAndAdd() },
                enabled: hasText && !vm.parsing,
                loading: vm.parsing
            )
        }
        .padding(.horizontal, dimensions.screenPadding)
        .padding(.top, dimensions.space12)
        .padding(.bottom, dimensions.space16)
        .frame(maxWidth: .infinity)
        .background(colors.background.ignoresSafeArea(edges: .bottom))
    }
}

/// Screen 2 — review per-unit overrides and confirm save.
private struct ReviewConfirmView: View {
    @ObservedObject var vm: AddStockViewModel
    let onBack: () -> Void

    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexDimensions) private var dimensions
    @Environment(\.aromexTypography) private var typography

    @State private var editingUnit: ReviewUnit? = nil

    // Apply-to-all bar staging (not applied until the user taps "Apply to all rows").
    @State private var applySellingPrice: String = ""
    @State private var applyCost: String = ""
    @State private var applyCondition: Condition = .theNew
    @State private var applyLocation: AttributeRef? = nil
    @State private var applyExpanded: Bool = false
    @FocusState private var applySellingPriceFocused: Bool
    @FocusState private var applyCostFocused: Bool

    /// Ordered SKU groups preserving first-appearance order.
    private var skuGroups: [(label: String, units: [ReviewUnit])] {
        var order: [String] = []
        var map: [String: [ReviewUnit]] = [:]
        for unit in vm.reviewUnits {
            let key = unit.skuKey
            if map[key] == nil { order.append(key) }
            map[key, default: []].append(unit)
        }
        return order.compactMap { key in
            guard let units = map[key], !units.isEmpty else { return nil }
            let label = units.first!.attributes.skuLabel
            return (label, units)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            reviewHeader

            if let errMsg = vm.error, errMsg != "batchCap" {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(colors.error)
                    Text(errorText(errMsg))
                        .font(typography.hint)
                        .foregroundStyle(colors.error)
                    Spacer()
                    Button {
                        vm.dismissError()
                    } label: {
                        Image(systemName: "xmark").foregroundStyle(colors.textTertiary)
                    }
                }
                .padding(dimensions.space12)
                .background(colors.error.opacity(0.08), in: RoundedRectangle(cornerRadius: dimensions.radiusCard))
                .overlay(
                    RoundedRectangle(cornerRadius: dimensions.radiusCard)
                        .stroke(colors.error.opacity(0.3), lineWidth: 0.5)
                )
                .padding(.horizontal, dimensions.screenPadding)
                .padding(.top, dimensions.space12)
            }

            ScrollView {
                VStack(alignment: .leading, spacing: dimensions.space12) {
                    if let summary = vm.parseSummary { summaryBanner(summary) }

                    applyToAllBar

                    if !vm.unreadable.isEmpty { unreadableSection }

                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(skuGroups.enumerated()), id: \.offset) { _, group in
                            // SKU section header
                            HStack(spacing: 6) {
                                Image(systemName: "shippingbox.fill")
                                    .font(.system(size: 11))
                                    .foregroundStyle(colors.brand)
                                Text(group.label)
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundStyle(colors.brand)
                                    .lineLimit(1)
                                    .truncationMode(.tail)
                                Spacer()
                                let c = group.units.count
                                Text("· \(c) \(c == 1 ? "unit" : "units")")
                                    .font(.caption)
                                    .foregroundStyle(colors.textTertiary)
                            }
                            .padding(.horizontal, dimensions.screenPadding)
                            .padding(.vertical, 8)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(colors.brand.opacity(0.07))

                            VStack(spacing: dimensions.space8) {
                                ForEach(group.units) { unit in
                                    reviewUnitCard(unit)
                                }
                            }
                            .padding(.horizontal, dimensions.screenPadding)
                            .padding(.vertical, dimensions.space12)
                        }
                    }
                }
                .padding(.bottom, dimensions.buttonHeight + dimensions.space24 + 48)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .background(colors.background.ignoresSafeArea())
        .overlay(alignment: .bottom) { confirmBar }
        .overlay {
            if vm.showPurchaseDialog {
                PurchaseDialogView(vm: vm)
            }
        }
        .ignoresSafeArea(.keyboard, edges: .bottom)
        .navigationBarHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { vm.refreshAllRowImeiStatus() }
        .alert(
            loc.t(Strings.shared.inventory_batch_cap_title),
            isPresented: Binding(
                get: { vm.error == "batchCap" },
                set: { if !$0 { vm.dismissError() } }
            )
        ) {
            Button(loc.t(Strings.shared.inventory_dialog_cancel), role: .cancel) { vm.dismissError() }
        } message: {
            Text(
                loc.t(Strings.shared.inventory_batch_cap_body)
                    .replacingOccurrences(of: "{0}", with: "\(vm.reviewUnits.count)")
                    .replacingOccurrences(of: "{1}", with: "\(vm.safeBatchCeiling)")
            )
        }
        .navigationDestination(isPresented: Binding(
            get: { editingUnit != nil },
            set: { if !$0 { editingUnit = nil } }
        )) {
            if let unit = editingUnit {
                let others = Set(vm.reviewUnits.compactMap { u -> String? in u.id == unit.id ? nil : u.imei })
                EditUnitSheet(
                    unit: unit,
                    vm: vm,
                    otherImeis: others,
                    onSave: { updated in vm.updateReviewUnit(updated) }
                )
            }
        }
    }

    // MARK: - Header

    private var reviewHeader: some View {
        HStack(alignment: .top) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(colors.onBrand)
                    .frame(width: 40, height: 40)
                    .background(colors.onBrand.opacity(0.15), in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(loc.t(Strings.shared.inventory_back_cd))

            VStack(alignment: .leading, spacing: 2) {
                Text(loc.t(Strings.shared.inventory_review_title))
                    .font(.system(size: 26, weight: .bold))
                    .foregroundStyle(colors.onBrand)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)

                let count = vm.reviewUnits.count
                let countLabel = count == 1
                    ? loc.t(Strings.shared.inventory_review_unit_count_one)
                    : loc.t(Strings.shared.inventory_review_unit_count).replacingOccurrences(of: "{0}", with: "\(count)")
                Text(countLabel)
                    .font(.system(size: 13))
                    .foregroundStyle(colors.onBrand.opacity(0.75))
            }
            Spacer(minLength: 8)
        }
        .padding(.horizontal, dimensions.screenPadding)
        .padding(.vertical, dimensions.space16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(
                colors: [colors.headerGradientStart, colors.headerGradientEnd],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
        )
    }

    // MARK: - Unit card (inline-editable, status-coloured)

    private func reviewUnitCard(_ unit: ReviewUnit) -> some View {
        ReviewUnitRow(unit: unit, vm: vm) { editingUnit = unit }
    }

    // MARK: - Summary banner (post-parse)

    private func summaryBanner(_ raw: String) -> some View {
        let parts = raw.split(separator: "|").map { Int($0) ?? 0 }
        let parsed = parts.first ?? 0
        let bad = parts.count > 1 ? parts[1] : 0
        let text: String
        if bad > 0 {
            text = loc.t(Strings.shared.inventory_parse_summary)
                .replacingOccurrences(of: "{0}", with: "\(parsed)")
                .replacingOccurrences(of: "{1}", with: "\(bad)")
        } else {
            text = loc.t(Strings.shared.inventory_parse_summary_one)
                .replacingOccurrences(of: "{0}", with: "\(parsed)")
        }
        return HStack(spacing: 8) {
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(colors.brand)
            Text(text)
                .font(typography.hint)
                .foregroundStyle(colors.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer()
            Button { vm.dismissParseSummary() } label: {
                Image(systemName: "xmark").foregroundStyle(colors.textTertiary)
            }
            .accessibilityLabel(loc.t(Strings.shared.inventory_unreadable_dismiss))
        }
        .padding(dimensions.space12)
        .background(colors.brand.opacity(0.08), in: RoundedRectangle(cornerRadius: dimensions.radiusCard))
        .overlay(
            RoundedRectangle(cornerRadius: dimensions.radiusCard)
                .stroke(colors.brand.opacity(0.25), lineWidth: 0.5)
        )
        .padding(.horizontal, dimensions.screenPadding)
        .padding(.top, dimensions.space12)
    }

    // MARK: - Apply-to-all bar

    private var applyToAllBar: some View {
        VStack(alignment: .leading, spacing: dimensions.space8) {
            Button {
                withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { applyExpanded.toggle() }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "square.stack.3d.up.fill")
                        .font(.system(size: 13))
                        .foregroundStyle(colors.brand)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(loc.t(Strings.shared.inventory_apply_all_title))
                            .font(typography.body.weight(.semibold))
                            .foregroundStyle(colors.textPrimary)
                        Text(loc.t(Strings.shared.inventory_apply_all_hint))
                            .font(typography.hint)
                            .foregroundStyle(colors.textSecondary)
                            .lineLimit(1)
                            .truncationMode(.tail)
                    }
                    Spacer()
                    Image(systemName: "chevron.down")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(colors.brand)
                        .rotationEffect(.degrees(applyExpanded ? 180 : 0))
                }
            }
            .buttonStyle(.plain)

            if applyExpanded {
                LabeledTextField(
                    label: loc.t(Strings.shared.inventory_selling_price_label),
                    value: $applySellingPrice,
                    placeholder: loc.t(Strings.shared.inventory_selling_price_placeholder),
                    keyboard: .decimalPad,
                    focus: $applySellingPriceFocused
                )
                LabeledTextField(
                    label: loc.t(Strings.shared.inventory_cost_label),
                    value: $applyCost,
                    placeholder: loc.t(Strings.shared.inventory_cost_placeholder),
                    keyboard: .decimalPad,
                    focus: $applyCostFocused
                )
                VStack(alignment: .leading, spacing: dimensions.space8) {
                    Text(loc.t(Strings.shared.inventory_condition_label))
                        .font(typography.fieldLabel)
                        .tracking(0.5)
                        .foregroundStyle(colors.textTertiary)
                    Picker("", selection: $applyCondition) {
                        Text(loc.t(Strings.shared.inventory_condition_new)).tag(Condition.theNew)
                        Text(loc.t(Strings.shared.inventory_condition_used)).tag(Condition.used)
                    }
                    .pickerStyle(.segmented)
                }
                FilterableDropdownField(
                    label: loc.t(Strings.shared.inventory_location_label),
                    items: vm.options(AttributeType.location),
                    selected: applyLocation,
                    onSelect: { applyLocation = AttributeRef(attributeId: $0.attributeId, name: $0.name) },
                    onClear: { applyLocation = nil },
                    onAddNew: { name in
                        vm.addNewAttribute(AttributeType.location, name: name)
                    },
                    placeholder: loc.t(Strings.shared.inventory_location_placeholder),
                    enabled: true
                )
                Button {
                    applyCostFocused = false
                    applySellingPriceFocused = false
                    let cost = applyCost.trimmingCharacters(in: .whitespacesAndNewlines)
                    let price = applySellingPrice.trimmingCharacters(in: .whitespacesAndNewlines)
                    vm.applyToAll(
                        sellingPrice: price.isEmpty ? nil : price,
                        cost: cost.isEmpty ? nil : cost,
                        condition: applyCondition,
                        location: applyLocation
                    )
                } label: {
                    Text(loc.t(Strings.shared.inventory_apply_all_action))
                        .font(typography.button)
                        .foregroundStyle(colors.brand)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, dimensions.space8)
                        .background(colors.brand.opacity(0.1), in: RoundedRectangle(cornerRadius: dimensions.radiusField))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(dimensions.space12)
        .background(colors.surface, in: RoundedRectangle(cornerRadius: dimensions.radiusCard))
        .overlay(
            RoundedRectangle(cornerRadius: dimensions.radiusCard)
                .stroke(colors.border, lineWidth: 0.5)
        )
        .padding(.horizontal, dimensions.screenPadding)
    }

    // MARK: - Couldn't-read section

    private var unreadableSection: some View {
        VStack(alignment: .leading, spacing: dimensions.space8) {
            HStack(spacing: 6) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 12))
                    .foregroundStyle(colors.error)
                Text(loc.t(Strings.shared.inventory_unreadable_title))
                    .font(typography.body.weight(.semibold))
                    .foregroundStyle(colors.textPrimary)
                Spacer()
                Button { vm.dismissAllUnreadable() } label: {
                    Text(loc.t(Strings.shared.inventory_unreadable_dismiss))
                        .font(typography.hint)
                        .foregroundStyle(colors.brand)
                }
                .buttonStyle(.plain)
            }
            ForEach(vm.unreadable) { block in
                HStack(alignment: .top, spacing: 8) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(unreadableReason(block.reason))
                            .font(typography.hint.weight(.semibold))
                            .foregroundStyle(colors.error)
                        Text(block.rawText)
                            .font(typography.hint.monospaced())
                            .foregroundStyle(colors.textSecondary)
                            .lineLimit(3)
                            .truncationMode(.tail)
                    }
                    Spacer(minLength: 4)
                    Button { vm.dismissUnreadable(block) } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(colors.textTertiary)
                            .frame(width: 32, height: 32)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(loc.t(Strings.shared.inventory_unreadable_dismiss))
                }
                .padding(dimensions.space8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(colors.error.opacity(0.06), in: RoundedRectangle(cornerRadius: dimensions.radiusField))
            }
        }
        .padding(dimensions.space12)
        .background(colors.surface, in: RoundedRectangle(cornerRadius: dimensions.radiusCard))
        .overlay(
            RoundedRectangle(cornerRadius: dimensions.radiusCard)
                .stroke(colors.error.opacity(0.3), lineWidth: 0.5)
        )
        .padding(.horizontal, dimensions.screenPadding)
    }

    private func unreadableReason(_ reason: UnreadableBlock.Reason) -> String {
        switch reason {
        case .noModel: return loc.t(Strings.shared.inventory_unreadable_no_model)
        case .notIphone: return loc.t(Strings.shared.inventory_unreadable_not_iphone)
        case .noImei: return loc.t(Strings.shared.inventory_unreadable_no_imei)
        default: return loc.t(Strings.shared.inventory_unreadable_title)
        }
    }

    // MARK: - Confirm bar

    private var confirmBar: some View {
        VStack(spacing: dimensions.space8) {
            Button {
                vm.addMoreFromReview()
            } label: {
                Text(loc.t(Strings.shared.inventory_review_add_more))
                    .font(typography.button)
                    .foregroundStyle(colors.brand)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.plain)

            PrimaryButton(
                label: loc.t(Strings.shared.inventory_confirm_btn),
                loadingLabel: loc.t(Strings.shared.inventory_saving),
                action: { vm.save() },
                enabled: vm.canConfirm && !vm.saving,
                loading: vm.saving
            )
        }
        .padding(.horizontal, dimensions.screenPadding)
        .padding(.top, dimensions.space12)
        .padding(.bottom, dimensions.space16)
        .frame(maxWidth: .infinity)
        .background(colors.background.ignoresSafeArea(edges: .bottom))
    }

    // MARK: - Error text

    private func errorText(_ raw: String) -> String {
        if raw == "duplicate" { return loc.t(Strings.shared.inventory_save_error_duplicate) }
        if raw.hasPrefix("network:") {
            let detail = String(raw.dropFirst("network:".count))
            return loc.t(Strings.shared.inventory_save_error_network) + " \(detail)"
        }
        return raw
    }
}

/// Purchase dialog (#58): who the batch was bought from and what was paid on the spot,
/// captured before the inventory write commits. All fields are optional with safe defaults
/// (Unspecified Supplier, nothing paid); confirming — or tapping the scrim to dismiss — is
/// the valid "skip" path and never cancels the underlying inventory save.
private struct PurchaseDialogView: View {
    @ObservedObject var vm: AddStockViewModel

    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexDimensions) private var dimensions
    @Environment(\.aromexTypography) private var typography

    @FocusState private var cashFocused: Bool
    @FocusState private var bankFocused: Bool

    private var symbol: String { MoneyFormat.shared.symbolOf(currency: vm.currency) }
    private var total: String { MoneyFormat.shared.format(amount: vm.batchTotalCost, currency: vm.currency) }

    var body: some View {
        ZStack {
            // Tap-away = confirm at all-defaults (never cancels the inventory save).
            colors.background.opacity(0.75)
                .ignoresSafeArea()
                .onTapGesture { vm.dismissPurchaseDialog() }

            VStack(alignment: .leading, spacing: dimensions.space16) {
                Text(loc.t(Strings.shared.inventory_purchase_title))
                    .font(typography.sectionTitle)
                    .foregroundStyle(colors.textPrimary)
                // Supplier block — labelled so it's clearly distinct from the Commission block.
                Text(loc.t(Strings.shared.commission_section_supplier))
                    .font(typography.fieldLabel)
                    .foregroundStyle(colors.textTertiary)
                Text(loc.t(Strings.shared.inventory_purchase_total).replacingOccurrences(of: "{0}", with: total))
                    .font(typography.hint)
                    .foregroundStyle(colors.textTertiary)

                FilterableDropdownField(
                    label: loc.t(Strings.shared.inventory_purchase_bought_from),
                    items: vm.purchasePartyOptions(),
                    selected: vm.purchaseParty,
                    onSelect: { vm.setPurchaseParty($0) },
                    onClear: nil,
                    onAddNew: vm.canAddSupplierInline() ? { vm.addNewSupplier(name: $0) } : nil,
                    placeholder: loc.t(Strings.shared.inventory_purchase_bought_from_hint),
                    enabled: true
                )

                LabeledTextField(
                    label: loc.t(Strings.shared.inventory_purchase_cash).replacingOccurrences(of: "{0}", with: symbol),
                    value: $vm.purchaseCash,
                    placeholder: "0",
                    keyboard: .decimalPad,
                    focus: $cashFocused
                )
                LabeledTextField(
                    label: loc.t(Strings.shared.inventory_purchase_bank).replacingOccurrences(of: "{0}", with: symbol),
                    value: $vm.purchaseBank,
                    placeholder: "0",
                    errorMessage: vm.purchasePaidExceedsTotal
                        ? loc.t(Strings.shared.inventory_purchase_exceeds).replacingOccurrences(of: "{0}", with: total)
                        : nil,
                    keyboard: .decimalPad,
                    focus: $bankFocused
                )

                // Commission on intake (#97) — only shown when a rule matched this batch.
                if !vm.commissionLines.isEmpty {
                    CommissionSectionView(vm: vm)
                }

                PrimaryButton(
                    label: loc.t(Strings.shared.inventory_purchase_confirm),
                    loadingLabel: loc.t(Strings.shared.inventory_saving),
                    action: { vm.confirmPurchaseAndSave() },
                    enabled: !vm.purchasePaidExceedsTotal && !vm.commissionGiveExceeds && !vm.saving,
                    loading: vm.saving
                )
            }
            .padding(dimensions.space24)
            .frame(maxHeight: 640)
            .background(
                RoundedRectangle(cornerRadius: dimensions.radiusCard)
                    .fill(colors.surface)
            )
            .padding(.horizontal, dimensions.screenPadding)
        }
    }
}

/// Commission on intake (#97) — bare but complete. One block per payee a rule proposed for this
/// batch, each decided separately: accrue ("add to what I owe") or pay now, its amount editable
/// (marked Edited when changed), or skipped. Every block shows how the figure was reached.
private struct CommissionSectionView: View {
    @ObservedObject var vm: AddStockViewModel
    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexDimensions) private var dimensions
    @Environment(\.aromexTypography) private var typography

    private var symbol: String { MoneyFormat.shared.symbolOf(currency: vm.currency) }

    var body: some View {
        VStack(alignment: .leading, spacing: dimensions.space12) {
            Divider().background(colors.border)
            Text(loc.t(Strings.shared.commission_section_title))
                .font(typography.fieldLabel)
                .foregroundStyle(colors.textTertiary)
            ScrollView {
                VStack(alignment: .leading, spacing: dimensions.space12) {
                    ForEach(vm.commissionLines, id: \.ruleId) { line in
                        if let decision = vm.commissionDecisions[line.ruleId] {
                            CommissionLineView(vm: vm, line: line, decision: decision, symbol: symbol)
                        }
                    }
                }
            }
            .frame(maxHeight: 260)
        }
    }
}

private struct CommissionLineView: View {
    @ObservedObject var vm: AddStockViewModel
    let line: CommissionLine
    let decision: CommissionDecisionState
    let symbol: String

    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexDimensions) private var dimensions
    @Environment(\.aromexTypography) private var typography

    private var reach: String {
        if line.rateKind == .perUnit {
            return loc.t(Strings.shared.commission_reach_per_unit)
                .replacingOccurrences(of: "{0}", with: vm.payeeName(line.payeeEntityId))
                .replacingOccurrences(of: "{1}", with: "\(line.unitCount)")
                .replacingOccurrences(of: "{2}", with: vm.locationName(line.locationAttributeId))
                .replacingOccurrences(of: "{3}", with: MoneyFormat.shared.format(amount: line.rate, currency: vm.currency))
        } else {
            return loc.t(Strings.shared.commission_reach_percent)
                .replacingOccurrences(of: "{0}", with: vm.payeeName(line.payeeEntityId))
                .replacingOccurrences(of: "{1}", with: commissionPercentLabel(line.rate))
                .replacingOccurrences(of: "{2}", with: MoneyFormat.shared.format(amount: line.basisAmount, currency: vm.currency))
                .replacingOccurrences(of: "{3}", with: vm.locationName(line.locationAttributeId))
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(reach).font(typography.hint).foregroundStyle(colors.textSecondary)
                Spacer()
                Text(MoneyFormat.shared.format(amount: line.amount, currency: vm.currency))
                    .font(typography.body).foregroundStyle(colors.textPrimary)
            }
            if !decision.included {
                HStack {
                    Text(loc.t(Strings.shared.commission_skipped)).font(typography.hint).foregroundStyle(colors.textTertiary)
                    Spacer()
                    Button(loc.t(Strings.shared.commission_undo_skip)) { vm.setCommissionIncluded(line.ruleId, true) }
                        .foregroundStyle(colors.brand)
                }
            } else {
                HStack(spacing: 8) {
                    CommissionChip(label: loc.t(Strings.shared.commission_accrue), selected: !decision.giveNow) { vm.setCommissionGiveNow(line.ruleId, false) }
                    CommissionChip(label: loc.t(Strings.shared.commission_pay_now), selected: decision.giveNow) { vm.setCommissionGiveNow(line.ruleId, true) }
                }
                HStack(alignment: .bottom, spacing: 8) {
                    commissionField(
                        label: loc.t(Strings.shared.commission_amount_label).replacingOccurrences(of: "{0}", with: symbol)
                            + (decision.overridden ? "  •  " + loc.t(Strings.shared.commission_overridden) : ""),
                        text: Binding(get: { decision.amount }, set: { vm.setCommissionAmount(line.ruleId, $0) }),
                        isError: false
                    )
                    Button(loc.t(Strings.shared.commission_skip)) { vm.setCommissionIncluded(line.ruleId, false) }
                        .foregroundStyle(colors.error)
                }
                // Give-now split: cash + bank, the amount to give above (like the supplier UI),
                // and the running sum below.
                if decision.giveNow {
                    Text(loc.t(Strings.shared.commission_give_owed)
                        .replacingOccurrences(of: "{0}", with: MoneyFormat.shared.format(amount: decision.amount.isEmpty ? "0" : decision.amount, currency: vm.currency)))
                        .font(typography.hint).foregroundStyle(colors.textTertiary)
                    HStack(spacing: 8) {
                        commissionField(
                            label: loc.t(Strings.shared.commission_cash_field).replacingOccurrences(of: "{0}", with: symbol),
                            text: Binding(get: { decision.cash }, set: { vm.setCommissionCash(line.ruleId, $0) }),
                            isError: false
                        )
                        commissionField(
                            label: loc.t(Strings.shared.commission_bank_field).replacingOccurrences(of: "{0}", with: symbol),
                            text: Binding(get: { decision.bank }, set: { vm.setCommissionBank(line.ruleId, $0) }),
                            isError: decision.giveExceedsAmount
                        )
                    }
                    if decision.giveExceedsAmount {
                        Text(loc.t(Strings.shared.commission_give_exceeds)
                            .replacingOccurrences(of: "{0}", with: MoneyFormat.shared.format(amount: decision.amount.isEmpty ? "0" : decision.amount, currency: vm.currency)))
                            .font(typography.hint).foregroundStyle(colors.error)
                    } else {
                        Text(loc.t(Strings.shared.commission_giving_now)
                            .replacingOccurrences(of: "{0}", with: MoneyFormat.shared.format(amount: decision.givenNow, currency: vm.currency))
                            + "  ·  "
                            + loc.t(Strings.shared.commission_left_on_balance)
                            .replacingOccurrences(of: "{0}", with: MoneyFormat.shared.format(amount: decision.leftOnBalance, currency: vm.currency)))
                            .font(typography.hint).foregroundStyle(colors.textSecondary)
                    }
                }
            }
        }
    }

    /// A minimal labelled numeric field (numeric input is sanitized in the ViewModel setters).
    @ViewBuilder
    private func commissionField(label: String, text: Binding<String>, isError: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(typography.fieldLabel).foregroundStyle(colors.textTertiary)
            TextField("0", text: text)
                .font(typography.body)
                .foregroundStyle(colors.textPrimary)
                .keyboardType(.decimalPad)
                .padding(.horizontal, 10).padding(.vertical, 8)
                .background(
                    RoundedRectangle(cornerRadius: dimensions.radiusField)
                        .stroke(isError ? colors.error : colors.border, lineWidth: 1)
                )
        }
    }
}

/// A minimal two-way pill toggle (accrue/pay-now, Cash/Bank) — no dropdown.
private struct CommissionChip: View {
    let label: String
    let selected: Bool
    let action: () -> Void
    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexDimensions) private var dimensions
    @Environment(\.aromexTypography) private var typography

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(typography.hint)
                .foregroundStyle(selected ? colors.brand : colors.textSecondary)
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background(
                    RoundedRectangle(cornerRadius: dimensions.radiusField)
                        .fill(selected ? colors.brand.opacity(0.14) : colors.surface)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: dimensions.radiusField)
                        .stroke(selected ? colors.brand : colors.border, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }
}

/// Fraction string → percent label, e.g. "0.02" → "2%". Pure shared string maths.
func commissionPercentLabel(_ fraction: String) -> String {
    let pct = Money.shared.multiplyRate(amount: fraction, rate: "100")
    var trimmed = pct
    if trimmed.contains(".") {
        while trimmed.hasSuffix("0") { trimmed.removeLast() }
        if trimmed.hasSuffix(".") { trimmed.removeLast() }
    }
    return trimmed + "%"
}

/// Sheet for editing all fields of a single review unit (SKU, IMEI, cost, condition, location).
private struct EditUnitSheet: View {
    let unit: ReviewUnit
    @ObservedObject var vm: AddStockViewModel
    let otherImeis: Set<String>
    let onSave: (ReviewUnit) -> Void

    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexDimensions) private var dimensions
    @Environment(\.aromexTypography) private var typography
    @Environment(\.dismiss) private var dismiss

    @State private var imei: String = ""
    @State private var imeiError: String? = nil
    @State private var checking = false
    @State private var cost: String = ""
    @State private var condition: Condition = .theNew
    @State private var location: AttributeRef? = nil

    @FocusState private var costFocused: Bool
    @FocusState private var imeiFocused: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: dimensions.space24) {

                // ── PHONE (SKU + price) ───────────────────────────────────
                sectionLabel(loc.t(Strings.shared.inventory_sku_section))

                    FilterableDropdownField(
                        label: loc.t(Strings.shared.inventory_brand_label),
                        items: vm.options(AttributeType.brand),
                        selected: vm.picked[AttributeType.brand],
                        onSelect: { vm.pick(AttributeType.brand, $0) },
                        onClear: { vm.clearPick(AttributeType.brand) },
                        onAddNew: { vm.addNewAttribute(AttributeType.brand, name: $0) },
                        placeholder: loc.t(Strings.shared.inventory_brand_placeholder),
                        enabled: true
                    )
                    FilterableDropdownField(
                        label: loc.t(Strings.shared.inventory_model_label),
                        items: vm.options(AttributeType.model),
                        selected: vm.picked[AttributeType.model],
                        onSelect: { vm.pick(AttributeType.model, $0) },
                        onClear: { vm.clearPick(AttributeType.model) },
                        onAddNew: { vm.addNewAttribute(AttributeType.model, name: $0) },
                        placeholder: vm.picked[AttributeType.brand] == nil
                            ? loc.t(Strings.shared.inventory_model_hint_brand)
                            : loc.t(Strings.shared.inventory_model_placeholder),
                        enabled: vm.picked[AttributeType.brand] != nil
                    )
                    FilterableDropdownField(
                        label: loc.t(Strings.shared.inventory_capacity_label),
                        items: vm.options(AttributeType.capacity),
                        selected: vm.picked[AttributeType.capacity],
                        onSelect: { vm.pick(AttributeType.capacity, $0) },
                        onClear: { vm.clearPick(AttributeType.capacity) },
                        onAddNew: { vm.addNewAttribute(AttributeType.capacity, name: $0) },
                        placeholder: loc.t(Strings.shared.inventory_capacity_placeholder),
                        enabled: true
                    )
                    FilterableDropdownField(
                        label: loc.t(Strings.shared.inventory_color_label),
                        items: vm.options(AttributeType.color),
                        selected: vm.picked[AttributeType.color],
                        onSelect: { vm.pick(AttributeType.color, $0) },
                        onClear: { vm.clearPick(AttributeType.color) },
                        onAddNew: { vm.addNewAttribute(AttributeType.color, name: $0) },
                        placeholder: loc.t(Strings.shared.inventory_color_placeholder),
                        enabled: true
                    )
                    FilterableDropdownField(
                        label: loc.t(Strings.shared.inventory_carrier_label),
                        items: vm.options(AttributeType.carrier),
                        selected: vm.picked[AttributeType.carrier],
                        onSelect: { vm.pick(AttributeType.carrier, $0) },
                        onClear: { vm.clearPick(AttributeType.carrier) },
                        onAddNew: { vm.addNewAttribute(AttributeType.carrier, name: $0) },
                        placeholder: loc.t(Strings.shared.inventory_carrier_placeholder),
                        enabled: true
                    )
                    LabeledTextField(
                        label: loc.t(Strings.shared.inventory_selling_price_label),
                        value: $vm.defaultSellingPrice,
                        placeholder: loc.t(Strings.shared.inventory_selling_price_placeholder),
                        keyboard: .decimalPad,
                        focus: $costFocused
                    )

                    // ── IMEI ──────────────────────────────────────────────────
                    sectionLabel(loc.t(Strings.shared.inventory_imei_section))

                    VStack(alignment: .leading, spacing: 6) {
                        TextField(loc.t(Strings.shared.inventory_imei_placeholder), text: $imei)
                            .keyboardType(.numberPad)
                            .font(typography.body)
                            .foregroundStyle(colors.textPrimary)
                            .focused($imeiFocused)
                            .padding(.horizontal, dimensions.space16)
                            .frame(height: dimensions.fieldHeight)
                            .background(
                                RoundedRectangle(cornerRadius: dimensions.radiusField)
                                    .fill(colors.surface)
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: dimensions.radiusField)
                                    .stroke(imeiError != nil ? colors.error : colors.border, lineWidth: dimensions.borderField)
                            )
                            .onChange(of: imei) { _ in imeiError = nil }
                            .toolbar {
                                if imeiFocused {
                                    ToolbarItemGroup(placement: .keyboard) {
                                        Spacer()
                                        Button("Done") { imeiFocused = false }
                                    }
                                }
                            }

                        if let err = imeiError {
                            Label(err, systemImage: "exclamationmark.circle.fill")
                                .font(typography.hint)
                                .foregroundStyle(colors.error)
                        }
                    }

                    // ── UNIT DETAILS ──────────────────────────────────────────
                    sectionLabel(loc.t(Strings.shared.inventory_batch_section))

                    LabeledTextField(
                        label: loc.t(Strings.shared.inventory_cost_label),
                        value: $cost,
                        placeholder: loc.t(Strings.shared.inventory_cost_placeholder),
                        keyboard: .decimalPad,
                        focus: $costFocused
                    )
                    VStack(alignment: .leading, spacing: dimensions.space8) {
                        Text(loc.t(Strings.shared.inventory_condition_label))
                            .font(typography.fieldLabel)
                            .tracking(0.5)
                            .foregroundStyle(colors.textTertiary)
                        Picker("", selection: $condition) {
                            Text(loc.t(Strings.shared.inventory_condition_new)).tag(Condition.theNew)
                            Text(loc.t(Strings.shared.inventory_condition_used)).tag(Condition.used)
                        }
                        .pickerStyle(.segmented)
                    }
                    FilterableDropdownField(
                        label: loc.t(Strings.shared.inventory_location_label),
                        items: vm.options(AttributeType.location),
                        selected: location,
                        onSelect: { location = AttributeRef(attributeId: $0.attributeId, name: $0.name) },
                        onClear: { location = nil },
                        onAddNew: { vm.addNewAttribute(AttributeType.location, name: $0) },
                        placeholder: loc.t(Strings.shared.inventory_location_placeholder),
                        enabled: true
                    )
                }
                .padding(dimensions.screenPadding)
            }
        .background(colors.background.ignoresSafeArea())
        .navigationTitle(loc.t(Strings.shared.inventory_edit_unit_title))
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(true)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(loc.t(Strings.shared.inventory_dialog_cancel)) { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                if checking {
                    ProgressView().tint(colors.brand)
                } else {
                    Button(loc.t(Strings.shared.inventory_dialog_save)) {
                        attemptSave()
                    }
                    .fontWeight(.semibold)
                    .foregroundStyle(colors.brand)
                    .disabled(cost.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || location == nil)
                }
            }
        }
        .onAppear {
            imei = unit.imei
            cost = unit.cost
            condition = unit.condition
            location = unit.location
            vm.setPickedFromUnit(attributes: unit.attributes, sellingPrice: unit.sellingPrice)
        }
    }

    private func sectionLabel(_ text: String) -> some View {
        HStack(spacing: 10) {
            Text(text.uppercased())
                .font(.system(size: 11, weight: .bold))
                .tracking(1.2)
                .foregroundStyle(colors.brand)
                .fixedSize()
            Rectangle()
                .fill(colors.border)
                .frame(height: 1)
        }
    }

    private func attemptSave() {
        let trimmed = imei.trimmingCharacters(in: .whitespacesAndNewlines)
        imeiError = nil
        if trimmed == unit.imei {
            saveUnit(imei: trimmed)
        } else {
            checking = true
            Task {
                let result = await vm.checkImeiAvailability(imei: trimmed, existingImeis: otherImeis)
                checking = false
                if result == .available {
                    saveUnit(imei: trimmed)
                } else {
                    imeiError = describe(result)
                }
            }
        }
    }

    private func saveUnit(imei: String) {
        guard let finalLocation = location else { return }
        var updated = unit
        updated.imei = imei
        updated.cost = cost.trimmingCharacters(in: .whitespacesAndNewlines)
        updated.condition = condition
        updated.location = finalLocation
        updated.attributes = vm.picked
        updated.sellingPrice = vm.defaultSellingPrice
        onSave(updated)
        dismiss()
    }

    private func describe(_ state: ImeiCheckState) -> String {
        switch state {
        case .invalid: return loc.t(Strings.shared.inventory_imei_error_invalid)
        case .alreadyInBatch: return loc.t(Strings.shared.inventory_imei_error_in_batch)
        case .alreadyInStock: return loc.t(Strings.shared.inventory_imei_error_in_stock)
        default: return ""
        }
    }
}


/// A single review-table row with inline-editable shop fields (cost / condition /
/// location), a per-row status colour, live IMEI checks, and "new"-tagged attribute
/// cells. Full-edit (SKU/IMEI) still goes through the pencil → `EditUnitSheet`.
private struct ReviewUnitRow: View {
    let unit: ReviewUnit
    @ObservedObject var vm: AddStockViewModel
    let onEdit: () -> Void

    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexDimensions) private var dimensions
    @Environment(\.aromexTypography) private var typography

    @State private var costText: String = ""
    @State private var priceText: String = ""
    @FocusState private var costFocused: Bool
    @FocusState private var priceFocused: Bool

    private var status: RowImeiStatus { vm.status(for: unit) }
    private var needsDetails: Bool { vm.rowNeedsDetails(unit) }
    private var hasProblem: Bool { vm.rowHasProblem(unit) }

    /// Left accent + border colour: problem (red) beats must-fill (amber) beats confident (brand).
    private var accent: Color {
        if hasProblem { return colors.error }
        if needsDetails { return colors.warning }
        return colors.brand
    }

    var body: some View {
        HStack(spacing: 0) {
            RoundedRectangle(cornerRadius: 2)
                .fill(accent)
                .frame(width: 4)
                .padding(.vertical, 2)

            VStack(alignment: .leading, spacing: dimensions.space8) {
                header
                statusLine
                skuCells
                inlineFields
            }
            .padding(dimensions.space12)
        }
        .background(colors.surface, in: RoundedRectangle(cornerRadius: dimensions.radiusCard))
        .overlay(
            RoundedRectangle(cornerRadius: dimensions.radiusCard)
                .stroke(accent.opacity(0.35), lineWidth: 0.75)
        )
        .onAppear { costText = unit.cost; priceText = unit.sellingPrice }
        .onChange(of: unit.cost) { new in
            if new != costText { costText = new }
        }
        .onChange(of: unit.sellingPrice) { new in
            if new != priceText { priceText = new }
        }
    }

    // MARK: IMEI + actions

    private var header: some View {
        HStack {
            Text(unit.imei)
                .font(typography.body.monospacedDigit().weight(.semibold))
                .foregroundStyle(colors.textPrimary)
                .lineLimit(1)
                .truncationMode(.middle)
            Spacer()
            Button(action: onEdit) {
                Image(systemName: "pencil")
                    .font(.system(size: 14))
                    .foregroundStyle(colors.brand)
                    .frame(width: 36, height: 36)
                    .background(colors.brand.opacity(0.1), in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(loc.t(Strings.shared.inventory_review_edit_cd))

            Button {
                vm.removeReviewUnit(unit)
            } label: {
                Image(systemName: "trash")
                    .font(.system(size: 14))
                    .foregroundStyle(colors.error)
                    .frame(width: 36, height: 36)
                    .background(colors.error.opacity(0.1), in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(loc.t(Strings.shared.inventory_review_delete_cd))
        }
    }

    // MARK: Status line (SICKW / must-fill / problem)

    @ViewBuilder
    private var statusLine: some View {
        HStack(spacing: 6) {
            if hasProblem {
                statusTag(
                    status == .inStock
                        ? loc.t(Strings.shared.inventory_status_in_stock)
                        : loc.t(Strings.shared.inventory_status_dup_batch),
                    color: colors.error, icon: "exclamationmark.octagon.fill"
                )
            } else if needsDetails {
                statusTag(loc.t(Strings.shared.inventory_status_must_fill), color: colors.warning, icon: "pencil.circle.fill")
            } else if unit.fromSickw {
                statusTag(loc.t(Strings.shared.inventory_status_parsed), color: colors.brand, icon: "checkmark.seal.fill")
            }
            if status == .checking {
                ProgressView().controlSize(.mini).tint(colors.textTertiary)
            }
            Spacer()
        }
    }

    private func statusTag(_ text: String, color: Color, icon: String) -> some View {
        HStack(spacing: 4) {
            Image(systemName: icon).font(.system(size: 10))
            Text(text).font(.system(size: 11, weight: .semibold))
        }
        .foregroundStyle(color)
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(color.opacity(0.12), in: Capsule())
    }

    // MARK: SKU attribute cells (with "new" tags)

    private var skuCells: some View {
        let cells = AttributeType.companion.SKU_DEFINING.compactMap { type -> (AttributeRef, Bool)? in
            guard let ref = unit.attributes[type] else { return nil }
            return (ref, vm.newlyCreatedIds.contains(ref.attributeId))
        }
        return FlowChips(cells: cells, newLabel: loc.t(Strings.shared.inventory_status_new_tag))
    }

    // MARK: Inline shop fields

    private var inlineFields: some View {
        VStack(spacing: dimensions.space8) {
            HStack(spacing: dimensions.space8) {
                // Cost (numeric)
                moneyCell(
                    label: loc.t(Strings.shared.inventory_cost_label),
                    placeholder: loc.t(Strings.shared.inventory_cost_placeholder),
                    text: $costText,
                    focus: $costFocused,
                    isEmpty: unit.cost.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                    commit: { vm.setRowCost(unit.id, costText.trimmingCharacters(in: .whitespacesAndNewlines)) }
                )
                // Selling price (numeric) — required by AddStockUseCase for a new SKU.
                moneyCell(
                    label: loc.t(Strings.shared.inventory_selling_price_label),
                    placeholder: loc.t(Strings.shared.inventory_selling_price_placeholder),
                    text: $priceText,
                    focus: $priceFocused,
                    isEmpty: unit.sellingPrice.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                    commit: { vm.setRowSellingPrice(unit.id, priceText.trimmingCharacters(in: .whitespacesAndNewlines)) }
                )
            }

            // Condition
            VStack(alignment: .leading, spacing: 4) {
                Text(loc.t(Strings.shared.inventory_condition_label))
                    .font(typography.fieldLabel)
                    .tracking(0.5)
                    .foregroundStyle(colors.textTertiary)
                Picker("", selection: Binding(
                    get: { unit.condition },
                    set: { vm.setRowCondition(unit.id, $0) }
                )) {
                    Text(loc.t(Strings.shared.inventory_condition_new)).tag(Condition.theNew)
                    Text(loc.t(Strings.shared.inventory_condition_used)).tag(Condition.used)
                }
                .pickerStyle(.segmented)
            }

            // Location (inline dropdown)
            FilterableDropdownField(
                label: loc.t(Strings.shared.inventory_location_label),
                items: vm.options(AttributeType.location),
                selected: unit.location.attributeId.isEmpty ? nil : unit.location,
                onSelect: { vm.setRowLocation(unit.id, AttributeRef(attributeId: $0.attributeId, name: $0.name)) },
                onClear: { vm.setRowLocation(unit.id, nil) },
                onAddNew: { name in vm.addNewAttribute(AttributeType.location, name: name) },
                placeholder: loc.t(Strings.shared.inventory_location_placeholder),
                enabled: true
            )
        }
    }

    /// Inline numeric money cell (cost / selling price) with a small label and a
    /// must-fill border while empty. Commits on blur and on the keyboard Done button.
    private func moneyCell(
        label: String,
        placeholder: String,
        text: Binding<String>,
        focus: FocusState<Bool>.Binding,
        isEmpty: Bool,
        commit: @escaping () -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(typography.fieldLabel)
                .tracking(0.5)
                .foregroundStyle(colors.textTertiary)
                .lineLimit(1)
                .truncationMode(.tail)
            HStack(spacing: 4) {
                Text("$").font(typography.hint).foregroundStyle(colors.textTertiary)
                TextField(placeholder, text: text)
                    .keyboardType(.decimalPad)
                    .font(typography.body)
                    .foregroundStyle(colors.textPrimary)
                    .focused(focus)
                    .onChange(of: focus.wrappedValue) { focused in
                        if !focused { commit() }
                    }
                    .toolbar {
                        if focus.wrappedValue {
                            ToolbarItemGroup(placement: .keyboard) {
                                Spacer()
                                Button(loc.t(Strings.shared.inventory_dialog_save)) {
                                    commit()
                                    focus.wrappedValue = false
                                }
                            }
                        }
                    }
            }
            .padding(.horizontal, dimensions.space12)
            .frame(height: dimensions.fieldHeight)
            .background(colors.surfaceAlt, in: RoundedRectangle(cornerRadius: dimensions.radiusField))
            .overlay(
                RoundedRectangle(cornerRadius: dimensions.radiusField)
                    .stroke(isEmpty ? colors.warning.opacity(0.6) : colors.border, lineWidth: dimensions.borderField)
            )
            .accessibilityLabel(label)
        }
        .frame(maxWidth: .infinity)
    }
}

/// Wraps attribute chips onto multiple lines, appending a "new" badge for freshly-minted vocab.
private struct FlowChips: View {
    let cells: [(AttributeRef, Bool)]
    let newLabel: String

    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexTypography) private var typography

    var body: some View {
        FlowLayout(spacing: 6) {
            ForEach(Array(cells.enumerated()), id: \.offset) { _, cell in
                HStack(spacing: 4) {
                    Text(cell.0.name)
                        .font(typography.hint)
                        .foregroundStyle(colors.textSecondary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    if cell.1 {
                        Text(newLabel.uppercased())
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(colors.onBrand)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(colors.brand, in: Capsule())
                    }
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(colors.surfaceAlt, in: Capsule())
            }
        }
    }
}

/// Minimal flow layout (wrapping HStack) — native SwiftUI `Layout`.
private struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x + size.width > maxWidth && x > 0 {
                x = 0; y += rowHeight + spacing; rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: maxWidth == .infinity ? x : maxWidth, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let maxWidth = bounds.width
        var x: CGFloat = bounds.minX, y: CGFloat = bounds.minY, rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x - bounds.minX + size.width > maxWidth && x > bounds.minX {
                x = bounds.minX; y += rowHeight + spacing; rowHeight = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

// MARK: - SKU label extension

private extension Dictionary where Key == AttributeType, Value == AttributeRef {
    var skuLabel: String {
        let parts = AttributeType.companion.SKU_DEFINING.compactMap { self[$0]?.name }
        return parts.isEmpty ? "(incomplete SKU)" : parts.joined(separator: " · ")
    }
}
