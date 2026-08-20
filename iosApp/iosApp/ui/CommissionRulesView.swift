import SwiftUI
import SharedLogic

/// Admin-only commission-rules settings on iOS (ticket #97) — bare but complete. The standing
/// arrangements in plain terms: location → payee → per-phone or percent-of-cost. Add / edit /
/// switch off; a switched-off rule stops firing at intake but never touches earned commission.
struct CommissionRulesView: View {
    let session: UserSession
    let config: FirebaseClientConfig

    @StateObject private var vm = CommissionRulesViewModel()
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var loc: LocalizationManager

    var body: some View {
        NavigationStack {
            Group {
                if !vm.isAdmin {
                    Text(loc.t(Strings.shared.commission_rules_no_access)).foregroundStyle(.secondary)
                } else if vm.rules.isEmpty {
                    Text(loc.t(Strings.shared.commission_rules_empty)).foregroundStyle(.secondary)
                } else {
                    List {
                        if let err = vm.error {
                            Text(err).foregroundStyle(.red).font(.footnote)
                        }
                        ForEach(vm.rules, id: \.ruleId) { rule in
                            RuleRow(vm: vm, rule: rule)
                        }
                    }
                }
            }
            .navigationTitle(loc.t(Strings.shared.commission_rules_title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(loc.t(Strings.shared.commission_rule_close_cd)) { dismiss() }
                }
                if vm.isAdmin {
                    ToolbarItem(placement: .primaryAction) {
                        Button { vm.openAddDialog() } label: { Image(systemName: "plus") }
                    }
                }
            }
            .onAppear { vm.bind(session: session, config: config) }
            .sheet(isPresented: $vm.showDialog) { CommissionRuleFormView(vm: vm) }
        }
    }
}

private struct RuleRow: View {
    @ObservedObject var vm: CommissionRulesViewModel
    let rule: CommissionRule
    @EnvironmentObject private var loc: LocalizationManager

    private var locationName: String { vm.locations.first { $0.attributeId == rule.locationAttributeId }?.name ?? rule.locationAttributeId }
    private var payeeName: String { vm.payees.first { $0.id == rule.payeeEntityId }?.name ?? rule.payeeEntityId }
    private var rateText: String {
        if rule.rateKind == .perUnit {
            return loc.t(Strings.shared.commission_rate_per_unit_value)
                .replacingOccurrences(of: "{0}", with: MoneyFormat.shared.format(amount: rule.rate, currency: vm.currency))
        } else {
            return loc.t(Strings.shared.commission_rate_percent_value)
                .replacingOccurrences(of: "{0}", with: commissionPercentLabel(rule.rate))
        }
    }

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("\(payeeName) — \(locationName)").font(.body)
                Text(rateText + (rule.isActive ? "" : "  •  " + loc.t(Strings.shared.commission_rule_off)))
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Spacer()
            Button(loc.t(Strings.shared.commission_rule_edit)) { vm.openEditDialog(rule) }
                .buttonStyle(.borderless)
            // On/off switch — toggles isActive both ways (off never touches earned commission).
            Toggle("", isOn: Binding(get: { rule.isActive }, set: { vm.setRuleActive(rule, $0) }))
                .labelsHidden()
        }
    }
}

private struct CommissionRuleFormView: View {
    @ObservedObject var vm: CommissionRulesViewModel
    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexDimensions) private var dimensions

    var body: some View {
        NavigationStack {
            Form {
                Section(loc.t(Strings.shared.commission_rule_field_location)) {
                    FilterableDropdownField(
                        label: loc.t(Strings.shared.commission_rule_field_location),
                        items: vm.locations,
                        selected: vm.form.location,
                        onSelect: { vm.setFormLocation($0) },
                        onClear: nil,
                        onAddNew: { vm.addLocationInline($0) },
                        placeholder: loc.t(Strings.shared.commission_rule_field_location_hint),
                        enabled: true
                    )
                }
                Section(loc.t(Strings.shared.commission_rule_field_payee)) {
                    FilterableDropdownField(
                        label: loc.t(Strings.shared.commission_rule_field_payee),
                        items: vm.payees.map { $0.asAttributeValue() },
                        selected: vm.form.payeeId.isEmpty ? nil : AttributeRef(attributeId: vm.form.payeeId, name: vm.form.payeeName),
                        onSelect: { picked in
                            if let entity = vm.payees.first(where: { $0.id == picked.attributeId }) { vm.setFormPayee(entity) }
                        },
                        onClear: nil,
                        onAddNew: { vm.addPayeeInline($0) },
                        placeholder: loc.t(Strings.shared.commission_rule_field_payee_hint),
                        enabled: true
                    )
                }
                Section(loc.t(Strings.shared.commission_rule_field_kind)) {
                    Picker(loc.t(Strings.shared.commission_rule_field_kind), selection: Binding(
                        get: { vm.form.rateKind == .perUnit },
                        set: { vm.setFormRateKind($0 ? .perUnit : .percentOfCost) }
                    )) {
                        Text(loc.t(Strings.shared.commission_rate_per_unit)).tag(true)
                        Text(loc.t(Strings.shared.commission_rate_percent)).tag(false)
                    }
                    .pickerStyle(.segmented)
                }
                Section(loc.t(Strings.shared.commission_rule_field_rate)) {
                    TextField(
                        loc.t(vm.form.rateKind == .perUnit
                              ? Strings.shared.commission_rule_rate_hint_per_unit
                              : Strings.shared.commission_rule_rate_hint_percent),
                        text: Binding(get: { vm.form.rateInput }, set: { vm.setFormRate($0) })
                    )
                    .keyboardType(.decimalPad)
                }
                if let err = vm.error {
                    Text(err).foregroundStyle(.red).font(.footnote)
                }
            }
            .navigationTitle(loc.t(vm.form.isEditing ? Strings.shared.commission_rule_dialog_edit : Strings.shared.commission_rule_dialog_add))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(loc.t(Strings.shared.commission_rule_cancel)) { vm.dismissDialog() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(loc.t(Strings.shared.commission_rule_save)) { vm.save() }
                        .disabled(vm.saving)
                }
            }
        }
    }
}

private extension Entity {
    /// Adapt to the `AttributeValue` the shared dropdown renders (id + name only used).
    func asAttributeValue() -> AttributeValue {
        AttributeValue(attributeId: id, type: .location, name: name, nameKey: "", parentId: nil, isActive: true)
    }
}
