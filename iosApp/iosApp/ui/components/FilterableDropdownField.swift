import SwiftUI
import SharedLogic

/// Inline filterable dropdown matching the Android FilterableDropdownField UX:
/// tap to open a list below the field, type to filter, "Add" row when no exact match.
///
/// Animation strategy — the outer VStack carries `.animation(.spring, value: expanded)`
/// so only the open/close transition is spring-animated; content updates while filtering
/// are instant (no jank as the list changes).
///
/// Glitch prevention — `PressDetectingButtonStyle` sets `isSelectingItem = true` at
/// Touch Down, before iOS can fire the focus-loss event. This lets `onChange(of: isFocused)`
/// skip the close-on-blur path and let the button action drive the close instead, so the
/// view is never removed from the hierarchy while a touch is still in progress.
struct FilterableDropdownField: View {
    let label: String
    let items: [AttributeValue]
    let selected: AttributeRef?
    let onSelect: (AttributeValue) -> Void
    let onClear: (() -> Void)?
    let onAddNew: ((String) -> Void)?
    let placeholder: String
    let enabled: Bool

    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexDimensions) private var dimensions
    @Environment(\.aromexTypography) private var typography

    @FocusState private var isFocused: Bool
    @State private var searchText = ""
    @State private var expanded = false
    @State private var isAdding = false
    @State private var isSelectingItem = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(label)
                .font(typography.fieldLabel)
                .tracking(0.5)
                .foregroundStyle(colors.textTertiary)
                .padding(.bottom, dimensions.space8)

            // ── Text field ────────────────────────────────────────────────────
            HStack(spacing: 0) {
                TextField(placeholder, text: $searchText)
                    .focused($isFocused)
                    .font(typography.body)
                    .foregroundStyle(colors.textPrimary)
                    .disabled(!enabled || isAdding)
                    .autocorrectionDisabled(true)
                    .textInputAutocapitalization(.words)
                    .onChange(of: isFocused) { focused in
                        if focused && enabled && !isAdding {
                            expanded = true
                        } else if !focused {
                            if isSelectingItem {
                                // Let the button action drive close; clear the flag.
                                isSelectingItem = false
                            } else {
                                expanded = false
                            }
                        }
                    }
                    .toolbar {
                        if isFocused {
                            ToolbarItemGroup(placement: .keyboard) {
                                Spacer()
                                Button("Done") { isFocused = false }
                            }
                        }
                    }

                // Trailing icon
                if isAdding {
                    ProgressView()
                        .scaleEffect(0.75)
                        .tint(colors.brand)
                        .frame(width: 36, height: 36)
                } else if !searchText.isEmpty {
                    Button {
                        searchText = ""
                        onClear?()
                        isFocused = true
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(colors.textTertiary)
                            .frame(width: 36, height: 36)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                } else {
                    Image(systemName: "chevron.down")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(enabled && !isAdding ? colors.brand : colors.textTertiary)
                        .rotationEffect(.degrees(expanded ? 180 : 0))
                        .frame(width: 36, height: 36)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            guard enabled && !isAdding else { return }
                            if expanded {
                                expanded = false
                                isFocused = false
                            } else {
                                isFocused = true  // focus change drives `expanded = true`
                            }
                        }
                }
            }
            .padding(.horizontal, dimensions.space16)
            .frame(height: dimensions.fieldHeight)
            .background(
                RoundedRectangle(cornerRadius: dimensions.radiusField)
                    .fill(enabled && !isAdding ? colors.surface : colors.surfaceAlt)
            )
            .overlay(
                RoundedRectangle(cornerRadius: dimensions.radiusField)
                    .stroke(colors.border, lineWidth: dimensions.borderField)
            )

            // ── Inline dropdown ───────────────────────────────────────────────
            if expanded && enabled && !isAdding {
                dropdownContent
                    .transition(.asymmetric(
                        insertion: .opacity.combined(with: .scale(scale: 0.97, anchor: .top)),
                        removal: .opacity.combined(with: .scale(scale: 0.98, anchor: .top))
                    ))
                    .padding(.top, 4)
            }
        }
        // Scope the spring animation to open/close only — filter updates stay instant.
        .animation(.spring(response: 0.3, dampingFraction: 0.85), value: expanded)
        .onChange(of: selected) { sel in
            isAdding = false
            searchText = sel?.name ?? ""
        }
    }

    // MARK: - Dropdown content

    @ViewBuilder
    private var dropdownContent: some View {
        let trimmed = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let filtered = filteredItems
        let canAdd = onAddNew != nil && !trimmed.isEmpty && !hasExactMatch

        VStack(spacing: 0) {
            if filtered.isEmpty && !canAdd {
                Text(trimmed.isEmpty ? "No items available" : "No results found")
                    .font(typography.hint)
                    .foregroundStyle(colors.textTertiary)
                    .padding(.horizontal, dimensions.space16)
                    .padding(.vertical, 12)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(filtered, id: \.attributeId) { item in
                            Button {
                                searchText = item.name
                                expanded = false
                                isFocused = false
                                isSelectingItem = false
                                onSelect(item)
                            } label: {
                                HStack {
                                    Text(item.name)
                                        .font(typography.body)
                                        .foregroundStyle(colors.textPrimary)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    if selected?.attributeId == item.attributeId {
                                        Image(systemName: "checkmark")
                                            .font(.system(size: 14, weight: .semibold))
                                            .foregroundStyle(colors.brand)
                                    }
                                }
                                .padding(.horizontal, dimensions.space16)
                                .frame(height: 48)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(DropdownItemStyle { isSelectingItem = true })
                        }

                        if canAdd {
                            Divider().padding(.leading, dimensions.space16)
                            Button {
                                let name = capitalize(trimmed)
                                isAdding = true
                                expanded = false
                                isFocused = false
                                isSelectingItem = false
                                onAddNew?(name)
                            } label: {
                                HStack(spacing: 8) {
                                    Image(systemName: "plus.circle")
                                        .font(.system(size: 15))
                                        .foregroundStyle(colors.brand)
                                    Text("Add \"\(capitalize(trimmed))\"")
                                        .font(typography.body.weight(.medium))
                                        .foregroundStyle(colors.brand)
                                        .lineLimit(1)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, dimensions.space16)
                                .frame(height: 48)
                                .background(colors.brand.opacity(0.07))
                            }
                            .buttonStyle(DropdownItemStyle { isSelectingItem = true })
                        }
                    }
                }
                .frame(maxHeight: 192) // ~4 items visible; scrollable for more
            }
        }
        .background(
            RoundedRectangle(cornerRadius: dimensions.radiusField)
                .fill(colors.surface)
                .shadow(color: .black.opacity(0.1), radius: 6, x: 0, y: 3)
        )
        .overlay(
            RoundedRectangle(cornerRadius: dimensions.radiusField)
                .stroke(colors.border, lineWidth: 0.5)
        )
        // Prevent filter-driven list changes from inheriting the open/close spring.
        .animation(nil, value: searchText)
    }

    // MARK: - Helpers

    private var filteredItems: [AttributeValue] {
        let trimmed = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? items : items.filter { $0.name.localizedCaseInsensitiveContains(trimmed) }
    }

    private var hasExactMatch: Bool {
        let trimmed = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        return items.contains { $0.name.caseInsensitiveCompare(trimmed) == .orderedSame }
    }

    private func capitalize(_ s: String) -> String {
        guard !s.isEmpty else { return s }
        return s.prefix(1).uppercased() + s.dropFirst()
    }
}

// MARK: - Press-detecting button style

/// Calls `onPressStarted` at Touch Down so the parent can set `isSelectingItem = true`
/// before the keyboard / focus system fires its blur event.
private struct DropdownItemStyle: ButtonStyle {
    let onPressStarted: () -> Void

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Color.black.opacity(0.05) : Color.clear)
            .onChange(of: configuration.isPressed) { pressed in
                if pressed { onPressStarted() }
            }
    }
}
