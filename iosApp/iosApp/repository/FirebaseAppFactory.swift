import Foundation
import FirebaseCore
import FirebaseAuth
import FirebaseFirestore
import SharedLogic

// Turns a per-company FirebaseClientConfig into a cached named FirebaseApp.
// Each client Firebase project = one named app whose name is its projectId, so
// multiple client projects can coexist inside one process.
enum FirebaseAppFactory {
    private static var cache: [String: FirebaseApp] = [:]
    private static let queue = DispatchQueue(label: "com.humblesolutions.aromex.FirebaseAppFactory")

    static func app(for config: FirebaseClientConfig) -> FirebaseApp {
        return queue.sync {
            if let existing = cache[config.projectId] { return existing }
            if let already = FirebaseApp.app(name: config.projectId) {
                cache[config.projectId] = already
                return already
            }
            // Firebase iOS SDK validates the googleAppID format and rejects
            // Android/web IDs (`1:...:android:...`). Prefer the company's iOS
            // app ID from the gateway; fall back to the shared one only if the
            // company hasn't been registered for iOS yet (which will crash
            // during configure — surfacing that as an obvious setup error).
            let googleAppID = config.iosApplicationId ?? config.applicationId
            let options = FirebaseOptions(
                googleAppID: googleAppID,
                gcmSenderID: config.messagingSenderId ?? ""
            )
            options.apiKey = config.apiKey
            options.projectID = config.projectId
            if let authDomain = config.authDomain { options.clientID = authDomain }
            if let bucket = config.storageBucket { options.storageBucket = bucket }
            FirebaseApp.configure(name: config.projectId, options: options)
            let app = FirebaseApp.app(name: config.projectId)!
            cache[config.projectId] = app
            return app
        }
    }

    static func auth(for config: FirebaseClientConfig) -> Auth {
        return Auth.auth(app: app(for: config))
    }

    static func firestore(for config: FirebaseClientConfig) -> Firestore {
        return Firestore.firestore(app: app(for: config))
    }
}
