import SwiftUI
import SharedLogic

/// Ticket-#21 restyle: apply Aromex tokens to the workspace chooser. Flow
/// unchanged; still shows `projectId` only (PRD §7.1).
struct ChooseCompanyView: View {
    @EnvironmentObject private var loc: LocalizationManager
    let candidates: [ResolvedCompany]
    let isSubmitting: Bool
    let onChoose: (ResolvedCompany) -> Void
    let onCancel: () -> Void

    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexTypography) private var typography
    @Environment(\.aromexDimensions) private var dimensions

    var body: some View {
        NavigationStack {
            ZStack {
                colors.background.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: dimensions.space12) {
                        Text(loc.t(Strings.shared.choose_company_title))
                            .font(typography.screenTitle)
                            .foregroundStyle(colors.textPrimary)
                        Text(loc.t(Strings.shared.choose_company_subtitle))
                            .font(typography.body)
                            .foregroundStyle(colors.textSecondary)

                        VStack(spacing: dimensions.space8) {
                            ForEach(candidates, id: \.companyId) { company in
                                Button {
                                    onChoose(company)
                                } label: {
                                    HStack {
                                        Text(company.firebaseConfig.projectId)
                                            .font(typography.bodyStrong)
                                            .foregroundStyle(colors.textPrimary)
                                        Spacer()
                                        if isSubmitting {
                                            ProgressView()
                                                .tint(colors.brand)
                                        } else {
                                            Image(systemName: "chevron.right")
                                                .foregroundStyle(colors.textTertiary)
                                        }
                                    }
                                    .padding(dimensions.space20)
                                    .background(
                                        RoundedRectangle(cornerRadius: dimensions.radiusCard)
                                            .fill(colors.surface)
                                    )
                                }
                                .buttonStyle(.plain)
                                .disabled(isSubmitting)
                            }
                        }
                        .padding(.top, dimensions.space8)
                    }
                    .padding(.horizontal, dimensions.screenPadding)
                    .padding(.vertical, dimensions.space24)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: onCancel) {
                        Image(systemName: "xmark")
                            .foregroundStyle(colors.textPrimary)
                    }
                    .disabled(isSubmitting)
                }
            }
        }
    }
}
