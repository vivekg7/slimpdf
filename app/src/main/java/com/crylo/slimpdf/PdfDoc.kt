package com.crylo.slimpdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * A PDF open for rendering.
 *
 * [PdfRenderer] permits exactly one open page at a time and is not thread safe, so every
 * method here must be called from a single thread — [PdfView] owns one for that purpose.
 * The class guarantees a page is always closed before the next one opens; it does not
 * police which thread the caller is on.
 */
class PdfDoc private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** Non-null when the source had to be copied locally; deleted on [close]. */
    private val spill: File?,
) {
    val pageCount: Int = renderer.pageCount

    /**
     * Page dimensions in PostScript points, laid out as [w0, h0, w1, h1, …].
     *
     * Measured eagerly because the scroll extent cannot be known without it, and a reader
     * whose scrollbar grows as you go feels broken. One open/close per page costs well
     * under a millisecond, so even a thousand-page document stays inside a single frame
     * or two on the loading thread.
     */
    val sizes: FloatArray = FloatArray(pageCount * 2).also { out ->
        for (i in 0 until pageCount) {
            renderer.openPage(i).use { page ->
                out[i * 2] = page.width.toFloat()
                out[i * 2 + 1] = page.height.toFloat()
            }
        }
    }

    /** Width in points of the widest page; the layout scale is derived from it. */
    val widestPoints: Float = run {
        var w = 1f
        for (i in 0 until pageCount) if (sizes[i * 2] > w) w = sizes[i * 2]
        w
    }

    fun widthPoints(page: Int): Float = sizes[page * 2]

    fun heightPoints(page: Int): Float = sizes[page * 2 + 1]

    /**
     * Draws a region of [page] into [bmp].
     *
     * The region is expressed in *rendered pixels*: [scale] converts points to pixels, and
     * [offsetX]/[offsetY] are the top-left of the wanted region in that same pixel space.
     * Passing the full page at `bmp.width / widthPoints(page)` renders the whole page;
     * passing a sub-rectangle at a higher scale is how the zoomed detail layer stays sharp.
     */
    fun render(page: Int, bmp: Bitmap, scale: Float, offsetX: Float, offsetY: Float) {
        // PdfRenderer composites onto whatever is already there and leaves uncovered
        // areas untouched, so the bitmap has to start as paper.
        bmp.eraseColor(Color.WHITE)
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate(-offsetX, -offsetY)
        renderer.openPage(page).use { p ->
            p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    fun close() {
        runCatching { renderer.close() }
        runCatching { fd.close() }
        spill?.delete()
    }

    /** Thrown for encrypted documents, which [PdfRenderer] cannot open. */
    class PasswordProtected : IOException("PDF is password protected")

    companion object {
        fun open(ctx: Context, uri: Uri): PdfDoc {
            // The fast path: a seekable descriptor straight from the provider.
            val direct = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }
                .getOrNull()
            if (direct != null) {
                try {
                    return PdfDoc(direct, PdfRenderer(direct), null)
                } catch (e: SecurityException) {
                    direct.close()
                    throw PasswordProtected()
                } catch (e: Exception) {
                    // Some providers (mail attachments, a few cloud clients) hand back a
                    // pipe, which PdfRenderer rejects because it must seek. Fall through
                    // and spill to cache.
                    direct.close()
                }
            }

            val spill = File.createTempFile("doc", ".pdf", ctx.cacheDir)
            try {
                val input = ctx.contentResolver.openInputStream(uri)
                    ?: throw IOException("no stream for $uri")
                input.use { i -> FileOutputStream(spill).use { o -> i.copyTo(o) } }
                val fd = ParcelFileDescriptor.open(spill, ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    return PdfDoc(fd, PdfRenderer(fd), spill)
                } catch (e: SecurityException) {
                    fd.close()
                    throw PasswordProtected()
                } catch (e: Throwable) {
                    fd.close()
                    throw e
                }
            } catch (e: Throwable) {
                spill.delete()
                throw e
            }
        }
    }
}
