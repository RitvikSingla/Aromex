import Foundation
import SharedLogic

// Brokers a short-lived HL access token via the gateway's /hl-token endpoint,
// caches it in memory, and refreshes when it's within 30s of expiry (or when
// invalidate() is called after HL rejects the current token with 401).
//
// Gateway contract: POST /hl-token with just `Authorization: Bearer <firebase-id-token>` —
// no request body, no Content-Type header. Adding Content-Type: application/json
// with an empty body makes Fastify return 400 (FST_ERR_CTP_EMPTY_JSON_BODY).
final class HlTokenRepositoryImpl: HlTokenProvider {
    private let authRepo: AuthRepository
    private let activeConfig: FirebaseClientConfig
    private let session: URLSession
    private let tokenURL: URL
    private let refreshMarginSeconds: TimeInterval = 30
    private let store = TokenStore()

    init(
        authRepo: AuthRepository,
        activeConfig: FirebaseClientConfig,
        gatewayBaseURL: URL = AromexConfig.gatewayBaseURL,
        session: URLSession = .shared
    ) {
        self.authRepo = authRepo
        self.activeConfig = activeConfig
        self.session = session
        self.tokenURL = gatewayBaseURL.appendingPathComponent("hl-token")
    }

    // SKIE `__`-prefixes suspend interface members for Swift implementors; consumers
    // still call the clean `currentToken()` / `invalidate()` wrappers. (Ticket #34)
    func __currentToken() async throws -> String {
        if let cached = await store.getValid(marginSeconds: refreshMarginSeconds) {
            return cached
        }
        return try await broker()
    }

    func __invalidate() async throws {
        await store.clear()
    }

    private func broker() async throws -> String {
        if let inFlight = await store.getInFlight() {
            return try await inFlight.value
        }
        let task = Task<String, Error> { try await self.brokerNow() }
        await store.setInFlight(task)
        do {
            let value = try await task.value
            await store.setInFlight(nil)
            return value
        } catch {
            await store.setInFlight(nil)
            throw error
        }
    }

    private func brokerNow() async throws -> String {
        let firebaseIdToken = try await authRepo.idToken(config: activeConfig, forceRefresh: false)
        var request = URLRequest(url: tokenURL)
        request.httpMethod = "POST"
        request.setValue("Bearer \(firebaseIdToken)", forHTTPHeaderField: "Authorization")

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw HlException(error: HlError.NetworkUnavailable.shared).asError()
        }
        guard let http = response as? HTTPURLResponse else {
            throw HlException(error: HlError.GatewayUnreachable.shared).asError()
        }
        if http.statusCode == 401 || http.statusCode == 403 {
            throw HlException(error: HlError.TokenRejected.shared).asError()
        }
        guard (200..<300).contains(http.statusCode) else {
            throw HlException(error: HlError.GatewayUnreachable.shared).asError()
        }

        let parsed: BrokerResponse
        do {
            parsed = try JSONDecoder().decode(BrokerResponse.self, from: data)
        } catch {
            throw HlException(error: HlError.Unexpected(message: "Malformed /hl-token response")).asError()
        }
        let expiresAt = Date().addingTimeInterval(TimeInterval(parsed.expiresIn))
        await store.set(token: parsed.hlToken, expiresAt: expiresAt)
        return parsed.hlToken
    }

    private struct BrokerResponse: Decodable {
        let hlToken: String
        let expiresIn: Int
    }
}

private actor TokenStore {
    private var token: String?
    private var expiresAt: Date?
    private var inFlight: Task<String, Error>?

    func getValid(marginSeconds: TimeInterval) -> String? {
        guard let token, let expiresAt else { return nil }
        return Date() < expiresAt.addingTimeInterval(-marginSeconds) ? token : nil
    }

    func set(token: String, expiresAt: Date) {
        self.token = token
        self.expiresAt = expiresAt
    }

    func clear() {
        token = nil
        expiresAt = nil
    }

    func getInFlight() -> Task<String, Error>? { inFlight }
    func setInFlight(_ task: Task<String, Error>?) { inFlight = task }
}
