import Foundation
import SharedLogic

// Reads the chart of accounts + balances from HL via the balance-sheet report
// (the only HL read that bundles accounts + balances). Money is kept as a
// decimal String end-to-end — never parsed to Double/Float.
final class HlLedgerRepositoryImpl: LedgerRepository {
    private let tokenProvider: HlTokenProvider
    private let session: URLSession
    private let balanceSheetURL: URL

    init(
        tokenProvider: HlTokenProvider,
        hlBaseURL: URL = AromexConfig.hlBaseURL,
        session: URLSession = .shared
    ) {
        self.tokenProvider = tokenProvider
        self.session = session
        var comps = URLComponents(
            url: hlBaseURL.appendingPathComponent("api/v1/reports/balance-sheet"),
            resolvingAgainstBaseURL: false
        )!
        comps.queryItems = [URLQueryItem(name: "date", value: "2999-12-31")]
        self.balanceSheetURL = comps.url!
    }

    // SKIE `__`-prefixes suspend interface members for Swift implementors. (Ticket #34)
    func __getAccounts() async throws -> [LedgerAccount] {
        return try await fetchAccounts(retryOn401: true)
    }

    private func fetchAccounts(retryOn401: Bool) async throws -> [LedgerAccount] {
        let token = try await tokenProvider.currentToken()
        var request = URLRequest(url: balanceSheetURL)
        request.httpMethod = "GET"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw HlException(error: HlError.NetworkUnavailable.shared).asError()
        }
        guard let http = response as? HTTPURLResponse else {
            throw HlException(error: HlError.HlUnreachable.shared).asError()
        }
        if http.statusCode == 401 {
            if retryOn401 {
                try await tokenProvider.invalidate()
                return try await fetchAccounts(retryOn401: false)
            }
            throw HlException(error: HlError.Unauthorized.shared).asError()
        }
        guard (200..<300).contains(http.statusCode) else {
            throw HlException(error: HlError.HlUnreachable.shared).asError()
        }

        let envelope: BalanceSheetEnvelope
        do {
            envelope = try JSONDecoder().decode(BalanceSheetEnvelope.self, from: data)
        } catch {
            throw HlException(error: HlError.Unexpected(message: "HL returned malformed balance-sheet body")).asError()
        }
        guard envelope.success, let payload = envelope.data else {
            throw HlException(error: HlError.Unexpected(message: "HL balance-sheet success=false")).asError()
        }
        return payload.flatten()
    }

    // MARK: - DTOs

    private struct BalanceSheetEnvelope: Decodable {
        let success: Bool
        let data: BalanceSheetData?
    }

    private struct BalanceSheetData: Decodable {
        let assets: [BalanceRow]?
        let liabilities: [BalanceRow]?
        let equity: [BalanceRow]?

        func flatten() -> [LedgerAccount] {
            var out: [LedgerAccount] = []
            for row in assets ?? [] {
                out.append(row.toDomain(category: AccountCategory.asset))
            }
            for row in liabilities ?? [] {
                out.append(row.toDomain(category: AccountCategory.liability))
            }
            for row in equity ?? [] {
                out.append(row.toDomain(category: AccountCategory.equity))
            }
            return out
        }
    }

    private struct BalanceRow: Decodable {
        let accountId: String
        let name: String
        let balance: String

        func toDomain(category: AccountCategory) -> LedgerAccount {
            return LedgerAccount(
                id: accountId,
                name: name,
                category: category,
                type: AccountType.companion.fromName(name: name),
                balance: balance
            )
        }
    }
}
