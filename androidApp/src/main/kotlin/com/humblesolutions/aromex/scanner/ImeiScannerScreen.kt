package com.humblesolutions.aromex.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * The Android half of the [ImeiScanner] seam — a CameraX preview + ML Kit barcode
 * analyzer that returns the **first** detected barcode string and dismisses. Handles the
 * CAMERA runtime permission inline; a denial reports [ImeiScanner.Result.PermissionDenied]
 * so the caller degrades to manual entry. Local capture only (#45) — no Firebase channel.
 *
 * The scanned value is passed up verbatim; IMEI validity is enforced by the shared
 * `Imei`/use-case layer when the unit is added, so a mis-scan surfaces as a field error
 * rather than being silently swallowed here.
 */
@Composable
fun ImeiScannerScreen(onResult: (ImeiScanner.Result) -> Unit) {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            currentOnResult(ImeiScanner.Result.PermissionDenied("Camera permission is required to scan."))
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (hasPermission) {
            CameraPreview(onBarcode = { value -> currentOnResult(ImeiScanner.Result.Scanned(value)) })
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            ) {
                Text("Camera permission is needed to scan an IMEI barcode.")
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant camera access")
                }
                TextButton(onClick = { currentOnResult(ImeiScanner.Result.Cancelled) }) {
                    Text("Enter IMEI manually")
                }
            }
        }
        if (hasPermission) {
            TextButton(
                onClick = { currentOnResult(ImeiScanner.Result.Cancelled) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            ) {
                Text("Cancel")
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreview(onBarcode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    // ML Kit reads Code128 / EAN — the formats IMEI labels are printed in.
    val scanner = remember {
        BarcodeScanning.getClient(
            com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_CODE_128, Barcode.FORMAT_EAN_13, Barcode.FORMAT_CODE_39)
                .build(),
        )
    }
    // Latch so the very first hit is reported exactly once (the analyzer runs on many frames).
    val handled = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val currentOnBarcode by rememberUpdatedState(onBarcode)

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { imageProxy: ImageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage == null || handled.get()) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val input = InputImage.fromMediaImage(
                        mediaImage,
                        imageProxy.imageInfo.rotationDegrees,
                    )
                    scanner.process(input)
                        .addOnSuccessListener { barcodes ->
                            val value = barcodes.firstNotNullOfOrNull { it.rawValue }
                            if (value != null && handled.compareAndSet(false, true)) {
                                currentOnBarcode(value)
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}
