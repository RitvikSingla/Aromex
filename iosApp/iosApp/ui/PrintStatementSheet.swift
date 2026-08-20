import SwiftUI
import UIKit
import SharedLogic

/// The "Print statement" sheet (ticket #109): a date range, an off-by-default "Include notes"
/// toggle, and Generate. On success the PDF opens in the system viewer (which carries Share/Save),
/// then the sheet dismisses. Bare-minimum chrome — the real work is the shared use case + callable.
struct PrintStatementSheet: View {
    let entity: Entity
    @StateObject private var vm: PrintStatementViewModel
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var loc: LocalizationManager

    init(entity: Entity, session: UserSession, config: FirebaseClientConfig) {
        self.entity = entity
        _vm = StateObject(wrappedValue: PrintStatementViewModel(session: session, config: config))
    }

    var body: some View {
        NavigationView {
            Form {
                Section(header: Text(loc.t(Strings.shared.statement_range))) {
                    TextField("YYYY-MM-DD", text: $vm.fromISO)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    TextField("YYYY-MM-DD", text: $vm.toISO)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                }

                Section(footer: Text(loc.t(Strings.shared.statement_include_notes_hint))) {
                    Toggle(loc.t(Strings.shared.statement_include_notes), isOn: $vm.includeNotes)
                }

                if let error = vm.errorText {
                    Section { Text(error).foregroundStyle(.red) }
                }

                Section {
                    Button(action: { vm.generate(entity: entity) }) {
                        HStack {
                            if vm.generating {
                                ProgressView()
                                Text(loc.t(Strings.shared.statement_generating))
                            } else {
                                Text(loc.t(Strings.shared.statement_generate))
                            }
                        }
                    }
                    .disabled(vm.generating)
                }
            }
            .navigationTitle(loc.t(Strings.shared.statement_print_title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(loc.t(Strings.shared.money_cancel)) { dismiss() }
                        .disabled(vm.generating)
                }
            }
        }
        .onChange(of: vm.pdfURL) { url in
            guard let url = url else { return }
            UIApplication.shared.open(url)
            vm.pdfURL = nil
            dismiss()
        }
    }
}
