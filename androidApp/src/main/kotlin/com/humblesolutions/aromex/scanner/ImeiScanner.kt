package com.humblesolutions.aromex.scanner

/**
 * The camera seam (#45). The ViewModel/UI only ever deals with an IMEI **string** — the
 * platform camera SDKs (CameraX + ML Kit on Android, AVFoundation on iOS) stay behind
 * this boundary so shared/testable code never imports them. Scanning is **local only**
 * (camera → barcode → fills the add-unit IMEI field); there is no Firebase scanner
 * channel or cross-device hand-off in M4.
 *
 * On Android the actual capture is a Compose screen ([ImeiScannerScreen]) that reports an
 * [ImeiScanner.Result]; the caller only ever sees a string (or a cancel/denied outcome).
 */
interface ImeiScanner {
    /** Result of a scan attempt. */
    sealed interface Result {
        data class Scanned(val imei: String) : Result
        data object Cancelled : Result
        data class PermissionDenied(val message: String) : Result
    }
}
