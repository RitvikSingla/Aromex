package com.humblesolutions.aromex.ui.sales.history

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The detail-view PDF actions (ticket #84). Both act on the **real** invoice PDF at its issued
 * URL, not the link text:
 * - [sharePdf] downloads the bytes into a private cache dir and shares the **file** (so the target
 *   app receives an actual PDF, not a URL).
 * - [downloadPdfToDownloads] hands the URL to the system [DownloadManager], which saves it into the
 *   phone's public **Downloads** folder and shows the standard download notification.
 *
 * No new shared code — this is platform (Android) glue in the app layer.
 */
object PdfActions {

    private const val AUTHORITY_SUFFIX = ".fileprovider"

    /**
     * Downloads [url] to `cacheDir/invoices/<name>.pdf` and returns a shareable `content://` URI.
     * Blocking network IO — call from a coroutine on [Dispatchers.IO]. Returns null on failure.
     */
    suspend fun sharePdf(context: Context, url: String, invoiceNumber: String?): Uri? =
        withContext(Dispatchers.IO) {
            val fileName = pdfFileName(invoiceNumber)
            val dir = File(context.cacheDir, "invoices").apply { mkdirs() }
            val file = File(dir, fileName)
            val ok = runCatching { downloadTo(url, file) }.getOrDefault(false)
            if (!ok) return@withContext null
            runCatching {
                FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
            }.getOrNull()
        }

    /** Fires an OS share sheet for a PDF [uri] previously produced by [sharePdf]. */
    fun startShareChooser(context: Context, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, null)) }
    }

    /**
     * Enqueues a download of [url] straight into the public **Downloads** folder via the system
     * [DownloadManager] (its own notification tracks progress). Returns true if the request was
     * accepted. No storage permission is needed on API 29+; the manifest grants a maxSdk=28
     * fallback for older devices.
     */
    fun downloadPdfToDownloads(context: Context, url: String, invoiceNumber: String?): Boolean {
        val fileName = pdfFileName(invoiceNumber)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
        return runCatching {
            val request = DownloadManager.Request(Uri.parse(url))
                .setMimeType("application/pdf")
                .setTitle(fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            dm.enqueue(request)
            true
        }.getOrDefault(false)
    }

    private fun downloadTo(url: String, file: File): Boolean {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.use { input -> file.outputStream().use(input::copyTo) }
            return true
        } finally {
            conn.disconnect()
        }
    }

    /** A stable, filesystem-safe file name for the invoice PDF; falls back to a generic name. */
    private fun pdfFileName(invoiceNumber: String?): String {
        val base = invoiceNumber?.trim()?.takeIf { it.isNotEmpty() }
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?: "invoice"
        return "$base.pdf"
    }
}
