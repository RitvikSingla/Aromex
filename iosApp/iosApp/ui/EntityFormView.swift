import SwiftUI
import SharedLogic

/// Add/Edit ("Add Contact") — ticket #37 real UI.
///
/// Presentation-only over `EntityFormViewModel`. A FIXED flat blue header
/// (brand gradient, square bottom, not scrolling) holds the Close button, the
/// eyebrow + title, a decorative person avatar, and the Customer/Supplier
/// role-selection cards. Only the CONTACT INFO / NOTES / OPENING BALANCE cards
/// scroll below it; the Save button is pinned at the bottom safe-area inset.
/// The phone row has a searchable country-code dropdown (default: Canada).
struct EntityFormView: View {
    @ObservedObject var viewModel: EntityFormViewModel
    /// Company currency code (from `session.currency`) — for the amount chip.
    var currency: String = ""
    /// Pop this pushed screen (Back / discard). Owned by EntitiesView's stack.
    let onClose: () -> Void
    /// Saved successfully — refresh + navigate.
    let onDone: () -> Void

    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexTypography) private var typography
    @Environment(\.aromexDimensions) private var dimensions

    // Validation is surfaced only after a save attempt (or a blur).
    @State private var showNameError = false
    @State private var showEmailError = false
    @State private var showUnsavedConfirm = false

    // Country-code selection (presentation-only; VM's number field is unchanged).
    @State private var country: Country = Countries.shared.default
    @State private var showCountryPicker = false

    // Snapshot of the initial field values for the dirty check.
    @State private var initial: FormSnapshot? = nil

    // Per-field focus for the keyboard Next/Done toolbar.
    private enum Field: Hashable { case name, phone, email, address, taxNumber, amount, notes }
    @FocusState private var focused: Field?

    // Live keyboard height. The whole screen ignores the keyboard safe area (so
    // the pinned Save bar never rides up); we instead pad the scroll content by
    // this amount so fields can still scroll clear of the keyboard.
    @State private var keyboardHeight: CGFloat = 0

    var body: some View {
        VStack(spacing: 0) {
            fixedHeader

            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: dimensions.space24) {
                        if let error = viewModel.errorMessage {
                            Text(error)
                                .font(typography.hint)
                                .foregroundStyle(colors.error)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        contactInfoSection
                        notesSection
                        if viewModel.showsOpening {
                            openingSection
                        } else {
                            readonlyOpeningNote
                        }
                    }
                    .padding(.horizontal, dimensions.screenPadding)
                    .padding(.top, dimensions.space16)
                    // Reserve room for the pinned Save bar, plus the keyboard height
                    // while it's up (since the screen ignores the keyboard safe area)
                    // so focused fields can still scroll clear of the keyboard.
                    .padding(.bottom, dimensions.buttonHeight + dimensions.space24 + keyboardHeight)
                }
                .scrollDismissesKeyboard(.interactively)
                // No scroll-edge/safe-area bar tint below the fixed blue header.
                .scrollContentBackground(.hidden)
                // Kill the ScrollView's default top content inset so the blue
                // header meets the form content with no white band between them.
                .contentMargins(.top, 0, for: .scrollContent)
                // Proxy-scroll: keep the focused field visible above the keyboard
                // as the user advances through them with Next. A short delay lets
                // the keyboard finish animating up (otherwise its final frame
                // clobbers our scroll); the bottom-most fields anchor to .bottom so
                // they clear the keyboard, others center.
                .onChange(of: focused) { _, field in
                    guard let field else { return }
                    // The scroll viewport is full-height (the screen ignores the
                    // keyboard safe area), so a `.bottom` anchor would drop the
                    // field *behind* the keyboard. Center every focused field so
                    // it lands in the visible area above the keyboard.
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                        withAnimation(.easeInOut(duration: 0.25)) {
                            proxy.scrollTo(field, anchor: .center)
                        }
                    }
                }
            }
        }
        .background(colors.background.ignoresSafeArea())
        // Pin the Save bar hard to the screen bottom as a bottom overlay. The
        // ENTIRE screen ignores the keyboard safe area (below), so the keyboard
        // slides OVER the bar and nothing here ever rides up with the keyboard.
        .overlay(alignment: .bottom) {
            saveBar
        }
        .ignoresSafeArea(.keyboard, edges: .bottom)
        // Track the keyboard height so the scroll content can still pad/scroll a
        // focused field clear of the keyboard (the screen itself won't move).
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { note in
            guard let frame = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
            let screenH = UIScreen.main.bounds.height
            withAnimation(.easeInOut(duration: 0.2)) {
                keyboardHeight = max(0, screenH - frame.origin.y)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            withAnimation(.easeInOut(duration: 0.2)) { keyboardHeight = 0 }
        }
        .navigationBarHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                if let field = focused, isLastField(field) {
                    // Done — resign focus / dismiss the keyboard.
                    Button(loc.t(Strings.shared.entity_form_kbd_done)) { focused = nil }
                        .fontWeight(.semibold)
                } else {
                    // Next — advance to the following field.
                    Button(loc.t(Strings.shared.entity_form_kbd_next)) { advanceFocus() }
                        .fontWeight(.semibold)
                }
            }
        }
        .onAppear {
            if initial == nil {
                // Prime the country from an existing "+<code>…" number (display prefix).
                country = Countries.shared.byDialPrefix(phone: viewModel.phonesText)
                initial = snapshot()
            }
        }
        .sheet(isPresented: $showCountryPicker) {
            CountryPickerView(selection: $country, onDismiss: { showCountryPicker = false })
        }
        .modifier(ThemedDialog(
            isPresented: $showUnsavedConfirm,
            title: loc.t(Strings.shared.entity_form_unsaved_title),
            message: loc.t(Strings.shared.entity_form_unsaved_body),
            primaryLabel: loc.t(Strings.shared.entity_form_unsaved_discard),
            primaryDestructive: true,
            onPrimary: { onClose() },
            secondaryLabel: loc.t(Strings.shared.entity_form_unsaved_keep)
        ))
        .onChange(of: viewModel.saved) { saved in
            if saved { onDone() }
        }
    }

    // MARK: - Fixed flat blue header (does not scroll)

    private var fixedHeader: some View {
        let topInset = safeTop()
        return VStack(alignment: .leading, spacing: dimensions.space16) {
            HStack(alignment: .top) {
                Button { attemptClose() } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(colors.onBrand)
                        .frame(width: 40, height: 40)
                        .background(colors.onBrand.opacity(0.18), in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(loc.t(Strings.shared.entity_form_close_cd))

                VStack(alignment: .leading, spacing: 2) {
                    Text(loc.t(viewModel.isEditing ? Strings.shared.entity_form_edit_eyebrow : Strings.shared.entity_form_new_eyebrow))
                        .font(.system(size: 12, weight: .bold))
                        .tracking(1.5)
                        .foregroundStyle(colors.onBrand.opacity(0.7))
                    Text(loc.t(viewModel.isEditing ? Strings.shared.entity_form_edit_title : Strings.shared.entity_form_new_title))
                        .font(.system(size: 26, weight: .bold))
                        .foregroundStyle(colors.onBrand)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                Spacer(minLength: dimensions.space8)
                Image(systemName: "person")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(colors.onBrand)
                    .frame(width: 44, height: 44)
                    .background(colors.onBrand.opacity(0.12), in: Circle())
                    .overlay(Circle().stroke(colors.onBrand.opacity(0.3), lineWidth: 1))
            }

            HStack(spacing: dimensions.space12) {
                roleCard(
                    title: loc.t(Strings.shared.entity_form_role_customer_title),
                    subtitle: loc.t(Strings.shared.entity_form_role_customer_subtitle),
                    selected: viewModel.isCustomer
                ) { viewModel.isCustomer.toggle() }
                roleCard(
                    title: loc.t(Strings.shared.entity_form_role_supplier_title),
                    subtitle: loc.t(Strings.shared.entity_form_role_supplier_subtitle),
                    selected: viewModel.isSupplier
                ) { viewModel.isSupplier.toggle() }
            }
        }
        .padding(.horizontal, dimensions.screenPadding)
        .padding(.bottom, dimensions.space20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(
                colors: [colors.headerGradientStart, colors.headerGradientEnd],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
        )
        // Flat / square bottom edge — no corner rounding. Flush under the status bar.
    }

    private func safeTop() -> CGFloat {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }?
            .safeAreaInsets.top ?? 0
    }

    private func roleCard(title: String, subtitle: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(colors.textPrimary)
                    Text(subtitle)
                        .font(.system(size: 12))
                        .foregroundStyle(colors.textSecondary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                Spacer(minLength: dimensions.space8)
                ZStack {
                    Circle()
                        .stroke(selected ? colors.brand : colors.border, lineWidth: 2)
                        .frame(width: 20, height: 20)
                    if selected {
                        Circle().fill(colors.brand).frame(width: 12, height: 12)
                    }
                }
            }
            .padding(dimensions.space12)
            .frame(maxWidth: .infinity)
            .background(colors.surface, in: RoundedRectangle(cornerRadius: dimensions.radiusCard))
            .overlay(
                RoundedRectangle(cornerRadius: dimensions.radiusCard)
                    .stroke(selected ? colors.brand : colors.border, lineWidth: selected ? 1.5 : 0.5)
            )
        }
        .buttonStyle(.plain)
    }

    // MARK: CONTACT INFO

    private var contactInfoSection: some View {
        VStack(alignment: .leading, spacing: dimensions.space12) {
            EntitySectionHeader(title: loc.t(Strings.shared.entity_form_section_contact), color: colors.brand)
            VStack(spacing: 0) {
                fieldRow(
                    icon: "person",
                    label: loc.t(Strings.shared.entity_form_full_name_label),
                    placeholder: loc.t(Strings.shared.entity_form_full_name_placeholder),
                    text: $viewModel.name,
                    field: .name,
                    disabled: viewModel.isWalkIn,
                    error: showNameError && nameBlank,
                    errorText: loc.t(Strings.shared.entity_form_error_name_required)
                )
                divider
                phoneRow
                divider
                fieldRow(
                    icon: "envelope",
                    label: loc.t(Strings.shared.entity_form_email_label),
                    placeholder: loc.t(Strings.shared.entity_form_email_placeholder),
                    text: $viewModel.email,
                    field: .email,
                    keyboard: .emailAddress,
                    error: showEmailError && emailInvalid,
                    errorText: loc.t(Strings.shared.entity_form_error_email_invalid)
                )
                divider
                fieldRow(
                    icon: "mappin.and.ellipse",
                    label: loc.t(Strings.shared.entity_form_address_label),
                    placeholder: loc.t(Strings.shared.entity_form_address_placeholder),
                    text: $viewModel.address,
                    field: .address
                )
                divider
                // Tax number (ticket #106) — optional; prints on this party's invoices when they buy.
                fieldRow(
                    icon: "doc.text",
                    label: loc.t(Strings.shared.entity_form_tax_number_label),
                    placeholder: loc.t(Strings.shared.entity_form_tax_number_placeholder),
                    text: $viewModel.taxNumber,
                    field: .taxNumber
                )
            }
            .background(colors.surface, in: RoundedRectangle(cornerRadius: dimensions.radiusCard))
            .overlay(
                RoundedRectangle(cornerRadius: dimensions.radiusCard).stroke(colors.border, lineWidth: 0.5)
            )
        }
    }

    private var phoneRow: some View {
        HStack(alignment: .top, spacing: dimensions.space12) {
            Image(systemName: "phone")
                .font(.system(size: 15))
                .foregroundStyle(colors.brand)
                .frame(width: 22)
                .padding(.top, 2)
            VStack(alignment: .leading, spacing: 4) {
                Text(loc.t(Strings.shared.entity_form_phone_label))
                    .font(.system(size: 12, weight: .semibold))
                    .tracking(0.5)
                    .foregroundStyle(colors.textTertiary)
                HStack(spacing: dimensions.space8) {
                    // Tappable country-code control → searchable picker.
                    Button { showCountryPicker = true } label: {
                        HStack(spacing: 4) {
                            Text(CountryFlag.emoji(iso: country.iso))
                            Text(country.dialCode)
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(colors.brand)
                            Image(systemName: "chevron.down")
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundStyle(colors.textTertiary)
                        }
                    }
                    .buttonStyle(.plain)
                    Rectangle().fill(colors.border).frame(width: 1, height: 20)
                    TextField(loc.t(Strings.shared.entity_form_phone_placeholder), text: $viewModel.phonesText)
                        .font(typography.body)
                        .foregroundStyle(colors.textPrimary)
                        .keyboardType(.phonePad)
                        .focused($focused, equals: .phone)
                }
            }
        }
        .padding(dimensions.space16)
        .id(Field.phone) // scroll anchor for the keyboard proxy-scroll
    }

    // MARK: NOTES

    private var notesSection: some View {
        VStack(alignment: .leading, spacing: dimensions.space12) {
            EntitySectionHeader(title: loc.t(Strings.shared.entity_form_section_notes), color: colors.brand)
            HStack(alignment: .top, spacing: dimensions.space12) {
                Image(systemName: "note.text")
                    .font(.system(size: 15))
                    .foregroundStyle(colors.brand)
                    .frame(width: 22)
                    // Top-align the icon with the first line of the multi-line
                    // notes field / placeholder (TextEditor has ~8pt top inset).
                    .padding(.top, 8)
                ZStack(alignment: .topLeading) {
                    if viewModel.notes.isEmpty {
                        Text(loc.t(Strings.shared.entity_form_notes_placeholder))
                            .font(typography.body)
                            .foregroundStyle(colors.textTertiary)
                            .padding(.top, 8)
                            .padding(.leading, 5) // align with TextEditor's text inset
                            .allowsHitTesting(false)
                    }
                    TextEditor(text: $viewModel.notes)
                        .font(typography.body)
                        .foregroundStyle(colors.textPrimary)
                        .frame(minHeight: 80)
                        .scrollContentBackground(.hidden)
                        .focused($focused, equals: .notes)
                }
            }
            .padding(dimensions.space16)
            .background(colors.surface, in: RoundedRectangle(cornerRadius: dimensions.radiusCard))
            .overlay(
                RoundedRectangle(cornerRadius: dimensions.radiusCard).stroke(colors.border, lineWidth: 0.5)
            )
        }
        .id(Field.notes) // scroll anchor for the keyboard proxy-scroll
    }

    // MARK: OPENING BALANCE (create-only)

    private var openingSection: some View {
        VStack(alignment: .leading, spacing: dimensions.space12) {
            EntitySectionHeader(title: loc.t(Strings.shared.entity_form_section_opening), color: colors.brand)
            VStack(alignment: .leading, spacing: dimensions.space16) {
                HStack(spacing: 0) {
                    directionTab(
                        title: loc.t(Strings.shared.entity_form_opening_to_receive),
                        selected: viewModel.openingDirection == .receivable
                    ) { viewModel.openingDirection = .receivable }
                    directionTab(
                        title: loc.t(Strings.shared.entity_form_opening_to_give),
                        selected: viewModel.openingDirection == .credit
                    ) { viewModel.openingDirection = .credit }
                }
                .background(colors.surfaceAlt, in: RoundedRectangle(cornerRadius: dimensions.radiusButton))

                Divider().background(colors.border)

                VStack(alignment: .leading, spacing: dimensions.space8) {
                    Text(loc.t(Strings.shared.entity_form_amount_label))
                        .font(.system(size: 12, weight: .semibold))
                        .tracking(0.5)
                        .foregroundStyle(colors.textTertiary)
                    HStack(spacing: dimensions.space12) {
                        Text(MoneyFormat.shared.symbolOf(currency: currency))
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(colors.textSecondary)
                            .padding(.horizontal, dimensions.space8)
                            .padding(.vertical, 6)
                            .background(colors.surfaceAlt, in: RoundedRectangle(cornerRadius: 6))
                        TextField(loc.t(Strings.shared.entity_form_amount_placeholder), text: $viewModel.openingAmount)
                            .font(.system(size: 20, weight: .medium))
                            .foregroundStyle(colors.textPrimary)
                            .keyboardType(.decimalPad)
                            .focused($focused, equals: .amount)
                    }
                }
            }
            .padding(dimensions.space16)
            .background(colors.surface, in: RoundedRectangle(cornerRadius: dimensions.radiusCard))
            .overlay(
                RoundedRectangle(cornerRadius: dimensions.radiusCard).stroke(colors.border, lineWidth: 0.5)
            )
        }
        .id(Field.amount) // scroll anchor for the keyboard proxy-scroll
    }

    private var readonlyOpeningNote: some View {
        VStack(alignment: .leading, spacing: dimensions.space12) {
            EntitySectionHeader(title: loc.t(Strings.shared.entity_form_section_opening), color: colors.brand)
            Text(loc.t(Strings.shared.entity_form_opening_readonly_note))
                .font(typography.hint)
                .foregroundStyle(colors.textSecondary)
                .padding(dimensions.space16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(colors.surfaceAlt, in: RoundedRectangle(cornerRadius: dimensions.radiusCard))
        }
    }

    private func directionTab(title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(selected ? colors.onBrand : colors.textSecondary)
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .background(selected ? colors.brand : Color.clear, in: RoundedRectangle(cornerRadius: dimensions.radiusButton))
        }
        .buttonStyle(.plain)
    }

    // MARK: - Save bar

    private var saveBar: some View {
        VStack(spacing: dimensions.space8) {
            Button(action: attemptSave) {
                HStack(spacing: dimensions.space8) {
                    if viewModel.isSaving {
                        ProgressView().tint(colors.onBrand).controlSize(.small)
                        Text(loc.t(Strings.shared.entity_form_saving)).font(typography.button)
                    } else {
                        Image(systemName: "checkmark")
                        Text(loc.t(Strings.shared.entity_form_save)).font(typography.button)
                    }
                }
                .frame(maxWidth: .infinity)
                .frame(height: dimensions.buttonHeight)
                .foregroundStyle(colors.onBrand)
                .background(
                    RoundedRectangle(cornerRadius: dimensions.radiusButton)
                        .fill(canSave ? colors.brand : colors.brand.opacity(0.4))
                )
            }
            .buttonStyle(.plain)
            .disabled(!canSave)
        }
        .padding(.horizontal, dimensions.screenPadding)
        .padding(.top, dimensions.space12)
        .padding(.bottom, dimensions.space16)
        .frame(maxWidth: .infinity)
        // Fill the background down to the very bottom edge (behind the home
        // indicator) while the button content stays above the safe area.
        .background(colors.background.ignoresSafeArea(edges: .bottom))
    }

    // MARK: - Field focus (keyboard Next/Done)

    /// The last field in the tab order for the current form variant. On create,
    /// the opening-balance Amount field exists and is last; otherwise Notes is last.
    private func isLastField(_ field: Field) -> Bool {
        switch field {
        case .amount: return true
        case .notes: return !viewModel.showsOpening
        default: return false
        }
    }

    /// Advance focus: name → phone → email → address → taxNumber → notes → amount
    /// (create only) → done. On edit (no opening section) Notes is terminal.
    private func advanceFocus() {
        switch focused {
        case .name: focused = .phone
        case .phone: focused = .email
        case .email: focused = .address
        case .address: focused = .taxNumber
        case .taxNumber: focused = .notes
        case .notes: focused = viewModel.showsOpening ? .amount : nil
        default: focused = nil
        }
    }

    // MARK: - Validation + actions

    private var nameBlank: Bool {
        viewModel.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var emailInvalid: Bool {
        let e = viewModel.email.trimmingCharacters(in: .whitespacesAndNewlines)
        if e.isEmpty { return false }
        return !Self.isValidEmail(e)
    }

    private var canSave: Bool {
        !nameBlank && !viewModel.isSaving
    }

    private func attemptSave() {
        showNameError = nameBlank
        showEmailError = emailInvalid
        guard !nameBlank, !emailInvalid, !viewModel.isSaving else { return }
        viewModel.save()
    }

    private func attemptClose() {
        if isDirty {
            showUnsavedConfirm = true
        } else {
            onClose()
        }
    }

    private var isDirty: Bool {
        guard let initial = initial else { return false }
        return initial != snapshot()
    }

    private func snapshot() -> FormSnapshot {
        FormSnapshot(
            name: viewModel.name,
            phones: viewModel.phonesText,
            email: viewModel.email,
            address: viewModel.address,
            taxNumber: viewModel.taxNumber,
            notes: viewModel.notes,
            isCustomer: viewModel.isCustomer,
            isSupplier: viewModel.isSupplier,
            openingAmount: viewModel.openingAmount,
            openingDirectionReceivable: viewModel.openingDirection == .receivable
        )
    }

    private static func isValidEmail(_ email: String) -> Bool {
        let pattern = "^[A-Z0-9a-z._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
        return email.range(of: pattern, options: .regularExpression) != nil
    }

    // MARK: - Field row

    private var divider: some View {
        Rectangle().fill(colors.border).frame(height: 1).padding(.leading, 50)
    }

    private func fieldRow(
        icon: String,
        label: String,
        placeholder: String,
        text: Binding<String>,
        field: Field,
        disabled: Bool = false,
        keyboard: UIKeyboardType = .default,
        error: Bool = false,
        errorText: String = ""
    ) -> some View {
        HStack(alignment: .top, spacing: dimensions.space12) {
            Image(systemName: icon)
                .font(.system(size: 15))
                .foregroundStyle(error ? colors.error : colors.brand)
                .frame(width: 22)
                .padding(.top, 2)
            VStack(alignment: .leading, spacing: 4) {
                Text(label)
                    .font(.system(size: 12, weight: .semibold))
                    .tracking(0.5)
                    .foregroundStyle(error ? colors.error : colors.textTertiary)
                TextField(placeholder, text: text)
                    .font(typography.body)
                    .foregroundStyle(colors.textPrimary)
                    .keyboardType(keyboard)
                    .textInputAutocapitalization(keyboard == .emailAddress ? .never : .words)
                    .autocorrectionDisabled(keyboard == .emailAddress)
                    .disabled(disabled)
                    .focused($focused, equals: field)
                    .submitLabel(.next)
                    .onSubmit { advanceFocus() }
                if error {
                    Text(errorText)
                        .font(typography.hint)
                        .foregroundStyle(colors.error)
                }
            }
        }
        .padding(dimensions.space16)
        .overlay(
            error
                ? RoundedRectangle(cornerRadius: dimensions.radiusField).stroke(colors.error, lineWidth: 1.5).padding(4)
                : nil
        )
        .id(field) // scroll anchor for the keyboard proxy-scroll
    }
}

/// Immutable snapshot of the editable form fields for the dirty check.
private struct FormSnapshot: Equatable {
    let name: String
    let phones: String
    let email: String
    let address: String
    let taxNumber: String
    let notes: String
    let isCustomer: Bool
    let isSupplier: Bool
    let openingAmount: String
    let openingDirectionReceivable: Bool
}
