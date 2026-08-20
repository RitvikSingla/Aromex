package com.humblesolutions.aromex.ui.sales.history

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runtime smoke test for the inline-PDF capability (ticket #83): proves PDFBox is on the
 * classpath and actually rasterizes a page to an image at runtime — the part `compileKotlin`
 * can't verify. Mirrors what `PdfBillView.renderInvoicePdf` does once the bytes are downloaded.
 */
class PdfRenderSmokeTest {

    init {
        // PDF rasterization uses AWT; force headless so it works on a CI box with no display.
        System.setProperty("java.awt.headless", "true")
    }

    @Test
    fun rendersPdfBytesToAnImage() {
        val bytes = ByteArrayOutputStream().use { out ->
            PDDocument().use { doc ->
                doc.addPage(PDPage())
                doc.save(out)
            }
            out.toByteArray()
        }

        PDDocument.load(bytes).use { doc ->
            val image = PDFRenderer(doc).renderImageWithDPI(0, 150f)
            assertTrue(image.width > 0 && image.height > 0, "PDFBox must rasterize a page to a real image")
        }
    }
}
