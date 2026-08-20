import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Sales History detail PDF export helpers (ticket #84). Both act on the **real** invoice PDF at
/// its issued URL, not the link text: `InvoicePdf.download` fetches the bytes; `ActivityView`
/// shares the saved file; `PdfFileDocument` backs the "Save to Files" exporter (the iOS
/// equivalent of a download). No new shared code — platform (iOS) glue in the app layer.
enum InvoicePdf {

    /// Downloads the PDF at [urlString]; nil on any failure. Call from an async context.
    static func download(_ urlString: String) async -> Data? {
        guard let url = URL(string: urlString) else { return nil }
        return try? await URLSession.shared.data(from: url).0
    }

    /// Writes [data] to a temp `invoices/<name>` file for sharing the actual file; nil on failure.
    static func writeTemp(_ data: Data, name: String) -> URL? {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("invoices", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent(name)
        do { try data.write(to: file, options: .atomic); return file } catch { return nil }
    }

    /// A stable, filesystem-safe file name for the invoice PDF; falls back to a generic name.
    static func fileName(_ invoiceNumber: String?) -> String {
        let trimmed = invoiceNumber?.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = (trimmed?.isEmpty == false) ? trimmed! : "invoice"
        let safe = base.replacingOccurrences(of: "[^A-Za-z0-9._-]", with: "_", options: .regularExpression)
        return "\(safe).pdf"
    }
}

/// Wraps `UIActivityViewController` so the **actual** downloaded PDF file can be shared via the
/// system share sheet (ticket #84 — share the file, not the link).
struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

/// A read/write PDF document backing the "Save to Files" exporter (ticket #84 Download).
struct PdfFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.pdf] }
    static var writableContentTypes: [UTType] { [.pdf] }

    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

/// An `Identifiable` URL box so a downloaded file can drive `.sheet(item:)` for the share sheet.
struct ShareFile: Identifiable {
    let id = UUID()
    let url: URL
}
