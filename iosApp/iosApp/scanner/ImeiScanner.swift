import SwiftUI
import UIKit
import AVFoundation

/// The camera seam (#45). SwiftUI/ViewModel code only ever deals with an IMEI **string** —
/// AVFoundation stays behind this boundary. Scanning is **local only** (camera → barcode →
/// fills the add-unit IMEI field); no Firebase channel or cross-device hand-off in M4.
enum ImeiScanResult {
    case scanned(String)
    case cancelled
    case permissionDenied(String)
}

/// Full-screen scanner: an `AVCaptureSession` preview reading the barcode symbologies IMEI
/// labels are printed in (Code128 / EAN / Code39). Reports the **first** hit and dismisses.
/// Handles the camera permission inline; a denial reports `.permissionDenied` so the caller
/// degrades to manual entry. The scanned value is passed up verbatim — IMEI validity is
/// enforced by the shared `Imei`/use-case layer when the unit is added.
struct ImeiScannerScreen: View {
    let onResult: (ImeiScanResult) -> Void

    @State private var authorization: AVAuthorizationStatus = AVCaptureDevice.authorizationStatus(for: .video)

    var body: some View {
        ZStack(alignment: .bottom) {
            switch authorization {
            case .authorized:
                ScannerPreview(onScanned: { value in onResult(.scanned(value)) })
                    .ignoresSafeArea()
                Button("Cancel") { onResult(.cancelled) }
                    .padding()
            case .notDetermined:
                Color.black.ignoresSafeArea()
                ProgressView().tint(.white)
            default:
                VStack(spacing: 12) {
                    Text("Camera permission is needed to scan an IMEI barcode.")
                        .multilineTextAlignment(.center)
                    Button("Enter IMEI manually") { onResult(.cancelled) }
                }
                .padding()
            }
        }
        .task {
            if authorization == .notDetermined {
                let granted = await AVCaptureDevice.requestAccess(for: .video)
                authorization = granted ? .authorized : .denied
                if !granted {
                    onResult(.permissionDenied("Camera permission is required to scan."))
                }
            } else if authorization != .authorized {
                onResult(.permissionDenied("Camera permission is required to scan."))
            }
        }
    }
}

/// UIKit bridge hosting the `AVCaptureSession` + metadata output.
private struct ScannerPreview: UIViewControllerRepresentable {
    let onScanned: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerViewController {
        let vc = ScannerViewController()
        vc.onScanned = onScanned
        return vc
    }

    func updateUIViewController(_ uiViewController: ScannerViewController, context: Context) {}
}

final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onScanned: ((String) -> Void)?

    private let session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var handled = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        configureSession()
    }

    private func configureSession() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { return }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.code128, .ean13, .ean8, .code39]

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.layer.bounds
        view.layer.addSublayer(layer)
        previewLayer = layer

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.session.startRunning()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.layer.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if session.isRunning { session.stopRunning() }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !handled,
              let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let value = object.stringValue else { return }
        handled = true
        session.stopRunning()
        onScanned?(value)
    }
}
