package com.crylo.slimpdf

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A continuous vertical PDF viewer.
 *
 * There are three coordinate spaces and keeping them straight is most of the work here:
 *
 *  - **points** — the PDF's own units, what [PdfDoc] reports.
 *  - **content px** — points scaled so the widest page exactly fills the view. The page
 *    strip is laid out once in this space and never re-laid-out on zoom.
 *  - **device px** — content px times [zoom], offset by [scrollXf]/[scrollYf].
 *
 * Rendering is two-tier. Every visible page gets a fit-width bitmap, which is cheap and
 * cached; at zoom 1 that is already 1:1 with the screen. Past [DETAIL_ZOOM] those bitmaps
 * would be upscaled and blurry — exactly when you are zooming in to read small print — so
 * a second bitmap covering just the visible slice of the current page is rendered at the
 * real zoom level and drawn over the top. That is a tile manager's job done with one tile,
 * which is enough for a reader and a fraction of the code.
 */
class PdfView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        private const val PAGE_GAP_DP = 10f
        private const val MIN_ZOOM = 1f
        private const val MAX_ZOOM = 10f

        /** Below this, the fit-width bitmaps are still at or above device resolution. */
        private const val DETAIL_ZOOM = 1.2f

        /** Cap on fit-width bitmaps. Covers every phone and most tablets at 1:1. */
        private const val MAX_BASE_PX = 1600

        /** Pixel budget for the detail bitmap — 4M px is 16 MB at ARGB_8888. */
        private const val DETAIL_BUDGET_PX = 4_000_000f

        /** Render this fraction beyond the viewport so small pans do not blur. */
        private const val DETAIL_MARGIN = 0.25f

        /** Pages either side of the viewport to keep warm. */
        private const val PRELOAD = 1

        /** Quiet period after the last gesture before the detail tile is rendered. */
        private const val SETTLE_MS = 90L

        private const val DOUBLE_TAP_ZOOM = 2.5f
    }

    /** Current zoom; 1 means a page fits the view width exactly. */
    val scale: Float get() = zoom

    /** True once a document has been handed over, i.e. the view can draw something. */
    val hasDocument: Boolean get() = doc != null

    /** Fired when the page under the viewport centre changes. Zero-based. */
    var onPageChanged: ((Int) -> Unit)? = null

    /** Fired on a confirmed single tap, for toggling the reader chrome. */
    var onTap: (() -> Unit)? = null

    // PdfRenderer is single-page, single-thread, so all rendering funnels through here.
    private val renderThread: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "pdf-render").apply { priority = Thread.NORM_PRIORITY - 1 }
        }

    private var doc: PdfDoc? = null
    private var closed = false

    // ---- content-space layout ----
    private var baseScale = 1f
    private var tops = FloatArray(0)
    private var contentW = 0f
    private var contentH = 0f
    private val gapPx get() = PAGE_GAP_DP * resources.displayMetrics.density

    // ---- viewport ----
    private var zoom = 1f
    private var scrollXf = 0f
    private var scrollYf = 0f
    private var lastReportedPage = -1
    private var pendingRestore: Pair<Int, Float>? = null

    // ---- fit-width bitmaps ----
    private var baseGen = 0
    private val pending = HashSet<Int>()
    private val cache: LruCache<Int, Bitmap>

    // ---- detail bitmap ----
    private var detailBmp: Bitmap? = null
    private val detailDst = RectF()
    private var detailKey: String? = null
    private var detailGen = 0

    private val scroller = OverScroller(context)
    private val dst = RectF()
    private val paperPaint = Paint().apply { color = android.graphics.Color.WHITE }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var zoomAnim: ValueAnimator? = null

    init {
        // A quarter of the heap, capped. Evicted bitmaps are not recycled: one may still
        // be referenced by a Canvas op that has not been flushed yet.
        val budget = min(Runtime.getRuntime().maxMemory() / 4L, 64L * 1024 * 1024)
        cache = object : LruCache<Int, Bitmap>(budget.toInt()) {
            override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
        }
        isFocusable = true
    }

    // ------------------------------------------------------------------ public API

    fun setDoc(d: PdfDoc, page: Int, offset: Float) {
        doc = d
        closed = false
        baseGen++
        pending.clear()
        cache.evictAll()
        clearDetail()
        pendingRestore = page to offset
        buildLayout()
        invalidate()
    }

    /** Page at the viewport top, and how far into it we have scrolled (0..1). */
    fun anchor(): Pair<Int, Float> {
        if (tops.isEmpty()) return pendingRestore ?: (0 to 0f)
        val topC = scrollYf / zoom
        val p = pageAt(topC)
        val h = pageHeight(p)
        return p to if (h > 0f) ((topC - tops[p]) / h).coerceIn(0f, 1f) else 0f
    }

    fun close() {
        closed = true
        removeCallbacks(settle)
        zoomAnim?.cancel()
        cache.evictAll()
        detailBmp = null
        val d = doc
        doc = null
        renderThread.execute { d?.close() }
        renderThread.shutdown()
    }

    // ------------------------------------------------------------------ layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Hold the reading position across a rotation or a window resize.
        val keep = if (tops.isNotEmpty()) anchor() else pendingRestore
        pendingRestore = keep
        baseGen++
        pending.clear()
        cache.evictAll()
        clearDetail()
        buildLayout()
    }

    private fun buildLayout() {
        val d = doc ?: return
        if (width == 0 || height == 0) return

        baseScale = width / d.widestPoints
        contentW = width.toFloat()
        val gap = gapPx
        tops = FloatArray(d.pageCount)
        var y = gap
        for (i in 0 until d.pageCount) {
            tops[i] = y
            y += d.heightPoints(i) * baseScale + gap
        }
        contentH = y

        pendingRestore?.let { (p, off) ->
            pendingRestore = null
            scrollYf = ((tops[p.coerceIn(0, tops.size - 1)] +
                off * pageHeight(p.coerceIn(0, tops.size - 1))) * zoom)
        }
        clampScroll()
        notifyPage()
        invalidate()
        scheduleDetail()
    }

    private fun pageWidth(i: Int): Float = (doc?.widthPoints(i) ?: 0f) * baseScale

    private fun pageHeight(i: Int): Float = (doc?.heightPoints(i) ?: 0f) * baseScale

    /** Index of the page whose band contains content-space y. */
    private fun pageAt(y: Float): Int {
        if (tops.isEmpty()) return 0
        var lo = 0
        var hi = tops.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (tops[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun maxScrollX(): Float = max(0f, contentW * zoom - width)

    private fun maxScrollY(): Float = max(0f, contentH * zoom - height)

    /** Horizontal centring when the zoomed content is narrower than the view. */
    private fun offsetX(): Float = max(0f, (width - contentW * zoom) / 2f)

    private fun clampScroll() {
        scrollXf = scrollXf.coerceIn(0f, maxScrollX())
        scrollYf = scrollYf.coerceIn(0f, maxScrollY())
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val d = doc ?: return
        if (tops.isEmpty()) return

        canvas.save()
        canvas.translate(offsetX() - scrollXf, -scrollYf)
        canvas.scale(zoom, zoom)

        val topC = scrollYf / zoom
        val botC = (scrollYf + height) / zoom
        val first = pageAt(topC)
        var last = first
        var i = first
        while (i < d.pageCount && tops[i] < botC) {
            drawPage(canvas, i)
            last = i
            i++
        }

        detailBmp?.let { b -> if (!b.isRecycled) canvas.drawBitmap(b, null, detailDst, bmpPaint) }
        canvas.restore()

        for (p in (first - PRELOAD)..(last + PRELOAD)) {
            if (p in 0 until d.pageCount && cache.get(p) == null) requestBase(p)
        }
    }

    private fun drawPage(canvas: Canvas, i: Int) {
        val w = pageWidth(i)
        val h = pageHeight(i)
        val l = (contentW - w) / 2f
        dst.set(l, tops[i], l + w, tops[i] + h)
        canvas.drawRect(dst, paperPaint)
        val bmp = cache.get(i)
        if (bmp != null && !bmp.isRecycled) canvas.drawBitmap(bmp, null, dst, bmpPaint)
    }

    private fun requestBase(page: Int) {
        val d = doc ?: return
        if (closed || !pending.add(page)) return
        val gen = baseGen
        val bw = pageWidth(page).toInt().coerceIn(1, MAX_BASE_PX)
        val scale = bw / d.widthPoints(page)
        val bh = (d.heightPoints(page) * scale).toInt().coerceAtLeast(1)

        renderThread.execute {
            val out = try {
                if (closed) null
                else Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    .also { d.render(page, it, scale, 0f, 0f) }
            } catch (e: Throwable) {
                null
            }
            post {
                pending.remove(page)
                if (closed || gen != baseGen) return@post
                if (out != null) {
                    cache.put(page, out)
                    invalidate()
                }
            }
        }
    }

    // ------------------------------------------------------------------ detail tile

    private val settle = Runnable { updateDetail() }

    private fun scheduleDetail() {
        removeCallbacks(settle)
        postDelayed(settle, SETTLE_MS)
    }

    private fun clearDetail() {
        detailGen++
        detailKey = null
        detailBmp = null
    }

    private fun updateDetail() {
        val d = doc ?: return
        if (closed || tops.isEmpty() || width == 0) return
        if (zoom < DETAIL_ZOOM) {
            if (detailBmp != null || detailKey != null) {
                clearDetail()
                invalidate()
            }
            return
        }

        val p = pageAt((scrollYf + height / 2f) / zoom)
        val pw = pageWidth(p)
        val ph = pageHeight(p)
        val pl = (contentW - pw) / 2f
        val ox = offsetX()

        // Visible slice of this page, in content space, grown by a pan margin.
        val mx = width / zoom * DETAIL_MARGIN
        val my = height / zoom * DETAIL_MARGIN
        val left = max(pl, (scrollXf - ox) / zoom - mx)
        val right = min(pl + pw, (scrollXf - ox + width) / zoom + mx)
        val top = max(tops[p], scrollYf / zoom - my)
        val bottom = min(tops[p] + ph, (scrollYf + height) / zoom + my)
        if (right - left < 1f || bottom - top < 1f) return

        // The same slice expressed in the page's own points.
        val xPt = (left - pl) / baseScale
        val yPt = (top - tops[p]) / baseScale
        val wPt = (right - left) / baseScale
        val hPt = (bottom - top) / baseScale

        var s = baseScale * zoom
        var wPx = wPt * s
        var hPx = hPt * s
        if (wPx * hPx > DETAIL_BUDGET_PX) {
            val k = sqrt(DETAIL_BUDGET_PX / (wPx * hPx))
            s *= k
            wPx *= k
            hPx *= k
        }
        val bw = wPx.toInt().coerceAtLeast(1)
        val bh = hPx.toInt().coerceAtLeast(1)

        val key = "$p:${left.toInt()}:${top.toInt()}:$bw:$bh:${(s * 50f).toInt()}"
        if (key == detailKey) return
        detailKey = key

        val gen = ++detailGen
        val rect = RectF(left, top, right, bottom)
        renderThread.execute {
            val out = try {
                if (closed) null
                else Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    .also { d.render(p, it, s, xPt * s, yPt * s) }
            } catch (e: Throwable) {
                null
            }
            post {
                if (closed || gen != detailGen) return@post
                if (out == null) {
                    detailKey = null   // let a later settle retry
                } else {
                    detailBmp = out
                    detailDst.set(rect)
                    invalidate()
                }
            }
        }
    }

    // ------------------------------------------------------------------ gestures

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                zoomAnim?.cancel()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                dx: Float,
                dy: Float,
            ): Boolean {
                scrollXf += dx
                scrollYf += dy
                clampScroll()
                notifyPage()
                invalidate()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                vx: Float,
                vy: Float,
            ): Boolean {
                scroller.forceFinished(true)
                scroller.fling(
                    scrollXf.toInt(), scrollYf.toInt(),
                    -vx.toInt(), -vy.toInt(),
                    0, maxScrollX().toInt(),
                    0, maxScrollY().toInt(),
                    0, 0,
                )
                postInvalidateOnAnimation()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Routed through performClick so an accessibility service's activation
                // gesture toggles the chrome exactly as a tap does.
                performClick()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                animateZoom(if (zoom > MIN_ZOOM + 0.01f) MIN_ZOOM else DOUBLE_TAP_ZOOM, e.x, e.y)
                return true
            }
        },
    )

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                applyZoom(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) = scheduleDetail()
        },
    )

    // performClick() is called, but from the GestureDetector's onSingleTapConfirmed --
    // a tap must not fire until a double tap has been ruled out. Lint only looks for the
    // call lexically inside onTouchEvent and cannot see it there.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scheduleDetail()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollXf = scroller.currX.toFloat()
            scrollYf = scroller.currY.toFloat()
            clampScroll()
            notifyPage()
            postInvalidateOnAnimation()
            // computeScrollOffset() returns false once the fling is over, so this branch
            // is the last chance to react. scheduleDetail() is debounced, so calling it
            // every frame means exactly one detail render, after the fling comes to rest.
            scheduleDetail()
        }
    }

    /** Re-anchors the viewport so the content under (fx, fy) stays put across a zoom. */
    private fun applyZoom(target: Float, fx: Float, fy: Float) {
        val nz = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (nz == zoom) return
        val cx = (scrollXf + fx - offsetX()) / zoom
        val cy = (scrollYf + fy) / zoom
        zoom = nz
        scrollXf = cx * zoom - fx + offsetX()
        scrollYf = cy * zoom - fy
        clampScroll()
        notifyPage()
        invalidate()
        scheduleDetail()
    }

    private fun animateZoom(target: Float, fx: Float, fy: Float) {
        zoomAnim?.cancel()
        zoomAnim = ValueAnimator.ofFloat(zoom, target).apply {
            duration = 200L
            addUpdateListener { applyZoom(it.animatedValue as Float, fx, fy) }
            start()
        }
    }

    private fun notifyPage() {
        if (tops.isEmpty()) return
        val p = pageAt((scrollYf + height / 2f) / zoom)
        if (p != lastReportedPage) {
            lastReportedPage = p
            onPageChanged?.invoke(p)
        }
    }
}
