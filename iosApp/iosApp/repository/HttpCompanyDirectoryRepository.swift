import Foundation
import SharedLogic

final class HttpCompanyDirectoryRepository: NSObject, CompanyDirectoryRepository {
    private let session: URLSession
    private let resolveURL: URL

    init(baseURL: URL = AromexConfig.gatewayBaseURL, session: URLSession = .shared) {
        self.session = session
        self.resolveURL = baseURL.appendingPathComponent("resolve-company")
    }

    // SKIE `__`-prefixes suspend interface members for Swift implementors. (Ticket #34)
    func __resolveCompanies(email: String) async throws -> [ResolvedCompany] {
        var request = URLRequest(url: resolveURL)
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(ResolveRequestDTO(email: email))

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw LoginException(error: LoginError.NetworkUnavailable.shared).asError()
        }

        guard let http = response as? HTTPURLResponse else {
            throw LoginException(error: LoginError.GatewayUnreachable.shared).asError()
        }
        guard (200..<300).contains(http.statusCode) else {
            throw LoginException(error: LoginError.GatewayUnreachable.shared).asError()
        }

        do {
            let parsed = try JSONDecoder().decode(ResolveResponseDTO.self, from: data)
            return parsed.companies.map { $0.toDomain() }
        } catch {
            throw LoginException(error: LoginError.GatewayUnreachable.shared).asError()
        }
    }

    // MARK: - DTOs

    private struct ResolveRequestDTO: Encodable {
        let email: String
    }

    private struct ResolveResponseDTO: Decodable {
        let companies: [ResolvedCompanyDTO]
    }

    private struct ResolvedCompanyDTO: Decodable {
        let companyId: String
        let firebaseConfig: FirebaseConfigDTO

        func toDomain() -> ResolvedCompany {
            return ResolvedCompany(
                companyId: companyId,
                firebaseConfig: firebaseConfig.toDomain()
            )
        }
    }

    private struct FirebaseConfigDTO: Decodable {
        let apiKey: String
        let appId: String
        let projectId: String
        let authDomain: String?
        let storageBucket: String?
        let messagingSenderId: String?
        // Populated once a company is registered for iOS in the gateway.
        let iosAppId: String?

        func toDomain() -> FirebaseClientConfig {
            return FirebaseClientConfig(
                apiKey: apiKey,
                applicationId: appId,
                projectId: projectId,
                authDomain: authDomain,
                storageBucket: storageBucket,
                messagingSenderId: messagingSenderId,
                iosApplicationId: iosAppId
            )
        }
    }
}
