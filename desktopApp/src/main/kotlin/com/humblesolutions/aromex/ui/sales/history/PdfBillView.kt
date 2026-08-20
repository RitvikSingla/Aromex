package com.humblesolutions.aromex.ui.sales.history

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humblesolutions.aromex.ui.theme.AromexTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Inline rendering of the issued invoice PDF for the Sales History detail view (ticket #83).
 *
 * Compose Desktop has no WebView, and embedding a browser (JavaFX / Chromium via KCEF) would
 * bundle a ~100 MB engine just to show a one-page bill. Instead we download the PDF and rasterize
 * each page to an [ImageBitmap] with **PDFBox** — a small JVM library — so the customer sees the
 * bill exactly as generated. When there's no PDF yet (PENDING/FAILED) or the render fails, the
 * caller falls back to the app-drawn bill layout. T2 mobile will use the native PDF viewers.
 */
sealed interface PdfLoadState {
    data object Loading : PdfLoadState
    data class Ready(val pages: List<ImageBitmap>) : PdfLoadState
    data object Failed : PdfLoadState
}

/** Shared, lazily-built HTTP client with sane timeouts — the PDF URL is a public S3 object. */
private val pdfHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}

/** How sharp the rasterized pages are. 150 DPI reads crisply on screen without bloating memory. */
private const val RENDER_DPI = 150f

private suspend fun renderInvoicePdf(url: String): List<ImageBitmap> = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).build()
    pdfHttpClient.newCall(request).execute().use { response ->
        require(response.isSuccessful) { "PDF download failed: HTTP ${response.code}" }
        val bytes = response.body?.bytes() ?: error("Empty PDF response")
        PDDocument.load(bytes).use { document ->
            val renderer = PDFRenderer(document)
            (0 until document.numberOfPages).map { page ->
                renderer.renderImageWithDPI(page, RENDER_DPI).toComposeImageBitmap()
            }
        }
    }
}

/**
 * Downloads + rasterizes [url] off the main thread, re-running whenever [url] changes. Emits
 * [PdfLoadState.Loading] first, then [PdfLoadState.Ready] or [PdfLoadState.Failed] — a failure is
 * never fatal; the caller shows the built bill instead.
 */
@Composable
fun rememberInvoicePdf(url: String): PdfLoadState =
    produceState<PdfLoadState>(PdfLoadState.Loading, url) {
        value = PdfLoadState.Loading
        value = runCatching { PdfLoadState.Ready(renderInvoicePdf(url)) }.getOrElse { PdfLoadState.Failed }
    }.value

// ── Saving / sharing the actual PDF file (ticket #83 follow-up) ────────────────
// The top-bar Share/Download actions operate on the real PDF bytes, never the S3 link — the shop
// hands the customer a file, not a URL. Download reuses the OkHttp client above; save/reveal use
// AWT so it stays cross-platform (Finder on macOS, Explorer on Windows, the file manager on Linux).

/** Downloads the invoice PDF bytes off the main thread. Throws on a non-2xx or empty body. */
suspend fun downloadPdfBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).build()
    pdfHttpClient.newCall(request).execute().use { response ->
        require(response.isSuccessful) { "PDF download failed: HTTP ${response.code}" }
        response.body?.bytes() ?: error("Empty PDF response")
    }
}

/** A filesystem-safe invoice file name, e.g. "INV-1042" → "INV-1042.pdf" (fallback "invoice.pdf"). */
private fun invoiceFileName(invoiceNumber: String?): String {
    val base = invoiceNumber?.trim()?.takeIf { it.isNotEmpty() }
        ?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "invoice"
    return "$base.pdf"
}

/**
 * Download button: fetches [url] and writes it wherever the user picks in a native Save dialog.
 * Returns the saved file, or null if the user cancelled. Download runs off-thread; the dialog on
 * the caller's (Main/AWT) dispatcher.
 */
suspend fun downloadAndSavePdf(url: String, invoiceNumber: String?): File? {
    val bytes = downloadPdfBytes(url)
    val target = chooseSaveFile(invoiceFileName(invoiceNumber)) ?: return null
    withContext(Dispatchers.IO) { target.writeBytes(bytes) }
    return target
}

/**
 * Share button (Desktop = save + reveal): downloads [url] into the user's Downloads folder and
 * reveals the file in the OS file browser, so they can drag/attach it anywhere. Returns the file.
 */
suspend fun downloadAndRevealPdf(url: String, invoiceNumber: String?): File {
    val bytes = downloadPdfBytes(url)
    val target = withContext(Dispatchers.IO) {
        val downloads = File(System.getProperty("user.home"), "Downloads").takeIf { it.isDirectory }
            ?: File(System.getProperty("java.io.tmpdir"))
        uniqueFile(downloads, invoiceFileName(invoiceNumber)).also { it.writeBytes(bytes) }
    }
    revealInFileBrowser(target)
    return target
}

/** Native Save dialog seeded with [defaultName]; null on cancel. */
private fun chooseSaveFile(defaultName: String): File? {
    val dialog = FileDialog(null as Frame?, "Save invoice", FileDialog.SAVE)
    dialog.file = defaultName
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(dir, name)
}

/** "name.pdf" → "name (1).pdf" when the target already exists, so a second share never clobbers. */
private fun uniqueFile(dir: File, name: String): File {
    var candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val dot = name.lastIndexOf('.')
    val base = if (dot >= 0) name.substring(0, dot) else name
    val ext = if (dot >= 0) name.substring(dot) else ""
    var i = 1
    while (candidate.exists()) candidate = File(dir, "$base ($i)$ext").also { i++ }
    return candidate
}

/** Reveals [file] in the OS file browser, falling back to opening its containing folder. */
private fun revealInFileBrowser(file: File) {
    val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
    if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
        if (runCatching { desktop.browseFileDirectory(file) }.isSuccess) return
    }
    val dir = file.parentFile ?: return
    if (desktop != null && desktop.isSupported(Desktop.Action.OPEN)) {
        runCatching { desktop.open(dir) }
    }
}

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
private const val ZOOM_STEP = 0.5f

/**
 * A zoomable, pannable invoice viewer (ticket #83). One page at a time, **scaled to fit the
 * pane's height** so the whole bill is visible with no scrolling. Zoom like on a laptop —
 * trackpad pinch or mouse-wheel — then drag to pan; the +/−/reset controls cover a plain mouse.
 * A page flipper appears only for a multi-page invoice. Resetting or flipping returns to fit.
 */
@Composable
fun ZoomablePdfViewer(pages: List<ImageBitmap>, modifier: Modifier = Modifier) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions

    var pageIndex by remember(pages) { mutableStateOf(0) }
    var scale by remember(pages) { mutableStateOf(1f) }
    var offset by remember(pages) { mutableStateOf(Offset.Zero) }
    // Every page change starts fresh at fit-to-height.
    LaunchedEffect(pageIndex) { scale = 1f; offset = Offset.Zero }

    Box(modifier.clipToBounds().background(colors.surfaceAlt)) {
        val idx = pageIndex.coerceIn(0, pages.lastIndex)
        Image(
            bitmap = pages[idx],
            contentDescription = "Invoice page ${idx + 1}",
            contentScale = ContentScale.Fit,
            alignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().padding(dims.space12)
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                }
                // Trackpad pinch-zoom + two-finger pan.
                .pointerInput(pages) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        offset = if (scale > 1f) offset + pan else Offset.Zero
                    }
                }
                // Mouse-wheel zoom (scroll up = in).
                .pointerInput(pages) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll) {
                                val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                if (dy != 0f) {
                                    scale = (scale * if (dy < 0f) 1.12f else 0.9f).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                    if (scale <= 1f) offset = Offset.Zero
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                },
        )

        // Zoom controls (bottom-right).
        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(dims.space12),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PdfControl(Icons.Filled.Remove, "Zoom out") {
                scale = (scale - ZOOM_STEP).coerceAtLeast(MIN_ZOOM)
                if (scale <= 1f) offset = Offset.Zero
            }
            PdfControl(Icons.Filled.Add, "Zoom in") { scale = (scale + ZOOM_STEP).coerceAtMost(MAX_ZOOM) }
            PdfControl(Icons.Filled.Refresh, "Reset zoom") { scale = 1f; offset = Offset.Zero }
        }

        // Page flipper (bottom-centre) — only when the invoice has more than one page.
        if (pages.size > 1) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(dims.space12),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PdfControl(Icons.Filled.ChevronLeft, "Previous page") { if (pageIndex > 0) pageIndex-- }
                Box(
                    Modifier.clip(RoundedCornerShape(dims.radiusField)).background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(dims.radiusField))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text("${idx + 1} / ${pages.size}", style = AromexTheme.typography.hint.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium), color = colors.textSecondary)
                }
                PdfControl(Icons.Filled.ChevronRight, "Next page") { if (pageIndex < pages.lastIndex) pageIndex++ }
            }
        }
    }
}

@Composable
private fun PdfControl(icon: ImageVector, cd: String, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    Box(
        modifier = Modifier.size(32.dp).clip(CircleShape).background(colors.surface)
            .border(1.dp, colors.border, CircleShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, cd, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
    }
}

/** Placeholder while the PDF downloads + renders. */
@Composable
fun PdfLoadingBox() {
    Box(Modifier.fillMaxSize().heightIn(min = 220.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AromexTheme.colors.brand)
    }
}
