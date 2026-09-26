package com.crylo.slimpdf

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.RenderParams
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
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
    private val renderer: Engine,
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
            renderer.measure(i, out)
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
        renderer.render(page, bmp, m)
    }

    fun close() {
        runCatching { renderer.close() }
        runCatching { fd.close() }
        spill?.delete()
    }

    /**
     * What [PdfDoc] needs of a renderer. [PdfRenderer] and [PdfRendererPreV] do the same
     * job but share no type, so each gets a thin wrapper.
     */
    private interface Engine {
        val pageCount: Int

        /** Writes [page]'s width and height in points into [out] at `page * 2`. */
        fun measure(page: Int, out: FloatArray)

        fun render(page: Int, bmp: Bitmap, m: Matrix)

        fun close()
    }

    private class Platform(private val r: PdfRenderer) : Engine {
        override val pageCount: Int get() = r.pageCount

        override fun measure(page: Int, out: FloatArray) = r.openPage(page).use {
            out[page * 2] = it.width.toFloat()
            out[page * 2 + 1] = it.height.toFloat()
        }

        override fun render(page: Int, bmp: Bitmap, m: Matrix) = r.openPage(page).use {
            it.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }

        override fun close() = r.close()
    }

    /**
     * Only ever built behind [hasPreV]. Lint cannot see that from inside the class, and
     * the annotation that would tell it, `@RequiresExtension`, is AndroidX-only.
     */
    @SuppressLint("NewApi", "InlinedApi")
    private class PreV(private val r: PdfRendererPreV) : Engine {
        private val params = RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY).build()

        override val pageCount: Int get() = r.pageCount

        override fun measure(page: Int, out: FloatArray) = r.openPage(page).use {
            out[page * 2] = it.width.toFloat()
            out[page * 2 + 1] = it.height.toFloat()
        }

        override fun render(page: Int, bmp: Bitmap, m: Matrix) = r.openPage(page).use {
            it.render(bmp, null, m, params)
        }

        override fun close() = r.close()
    }

    /**
     * Thrown for an encrypted document opened without its password, or with a wrong one.
     * [PdfRenderer] reports both the same way, so [wrong] only records whether a password
     * was tried.
     */
    class PasswordProtected(val wrong: Boolean) : IOException("PDF is password protected")

    companion object {
        /**
         * Whether [PdfRendererPreV] is here: Android 12–14 with the PDF module at SDK
         * extension 13, which arrives through a Google Play system update rather than with
         * the OS. From Android 15 [PdfRenderer] takes a password itself.
         */
        private val hasPreV: Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13

        /**
         * Whether an encrypted document can be opened here. Anything older than the two
         * cases above would mean bundling a PDF engine, which this app does not do.
         */
        val canUnlock: Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM || hasPreV

        /**
         * Documents without a password always go through [PdfRenderer], so [PdfRendererPreV]
         * is only ever used for the encrypted ones it is needed for.
         */
        private fun renderer(fd: ParcelFileDescriptor, password: String?): Engine = when {
            password == null -> Platform(PdfRenderer(fd))
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                Platform(PdfRenderer(fd, LoadParams.Builder().setPassword(password).build()))
            hasPreV ->
                PreV(PdfRendererPreV(fd, LoadParams.Builder().setPassword(password).build()))
            else -> Platform(PdfRenderer(fd))
        }

        /** [password] is ignored where [canUnlock] is false. */
        fun open(ctx: Context, uri: Uri, password: String? = null): PdfDoc {
            // The fast path: a seekable descriptor straight from the provider.
            val direct = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }
                .getOrNull()
            if (direct != null) {
                try {
                    return PdfDoc(direct, renderer(direct, password), null)
                } catch (e: SecurityException) {
                    direct.close()
                    throw PasswordProtected(wrong = password != null)
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
                    return PdfDoc(fd, renderer(fd, password), spill)
                } catch (e: SecurityException) {
                    fd.close()
                    throw PasswordProtected(wrong = password != null)
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
