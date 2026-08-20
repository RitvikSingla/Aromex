import Foundation

// TODO(M1-04 Phase B): switch to https:// once the gateway + HL move behind a
// TLS terminator; then drop the NSAppTransportSecurity exception in Info.plist.
enum AromexConfig {
    static let gatewayBaseURL = URL(string: "http://68.183.86.89/gateway")!
    static let hlBaseURL = URL(string: "http://68.183.86.89/api-server")!
}
