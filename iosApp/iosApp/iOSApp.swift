import SwiftUI
import SharedLogic

// Note: FirebaseApp.configure() is deliberately NOT called here. Aromex is
// multi-tenant — each client company gets its own Firebase project, and we
// initialize named secondary FirebaseApp instances at runtime via
// FirebaseAppFactory using config brokered from the gateway. There is no
// bundled GoogleService-Info.plist.
@main
struct iOSApp: App {
    @StateObject private var localization = LocalizationManager()

    var body: some Scene {
        WindowGroup {
            AromexApp()
                .environmentObject(localization)
        }
    }
}
