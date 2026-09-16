package com.crylo.slimpdf

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.StrictMode
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Covers the pinch-zoom and detail-tile path in [PdfView].
 *
 * The events are dispatched straight into the view rather than injected through the input
 * system: SELinux blocks /dev/input writes from the shell on a Play system image, and
 * `input tap` spawns a JVM per call, which is far too slow to land two taps inside the
 * double-tap window. Dispatching from inside the process sidesteps both.
 */
@RunWith(AndroidJUnit4::class)
class ReaderZoomTest {

    companion object {
        private lateinit var pdf: File

        @BeforeClass
        @JvmStatic
        fun preparePdf() {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            pdf = File(target.cacheDir, "test-sample.pdf")
            InstrumentationRegistry.getInstrumentation().context.assets.open("sample.pdf")
                .use { input -> pdf.outputStream().use { out -> input.copyTo(out) } }
            // The test process is the one handing over a file:// URI, so its own VM policy
            // is what would trip on it.
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        }
    }

    private fun launch(): ActivityScenario<ReaderActivity> {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(target, ReaderActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(pdf), "application/pdf")
            .putExtra(ReaderActivity.EXTRA_NAME, "sample.pdf")
        return ActivityScenario.launch(intent)
    }

    private fun ActivityScenario<ReaderActivity>.pdfView(): PdfView {
        var view: PdfView? = null
        onActivity { view = it.findViewById(R.id.pdf) }
        return view!!
    }

    private fun awaitDocument(view: PdfView) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (view.hasDocument && view.width > 0) return
            Thread.sleep(50)
        }
        throw AssertionError("document never loaded")
    }

    @Test
    fun pinchZoomsInAndHoldsThePage() {
        launch().use { scenario ->
            val view = scenario.pdfView()
            awaitDocument(view)
            Thread.sleep(600)

            val pageBefore = view.anchor().first
            assertEquals("starts at fit width", 1f, view.scale, 0.001f)

            scenario.onActivity { pinch(view, spanFrom = 120f, spanTo = 560f) }
            Thread.sleep(400)

            // ScaleGestureDetector does not turn an injected span ratio into an equal zoom
            // ratio -- it begins only once the span clears its slop, so the span used here
            // lands around 2.4x. The requirement is that a pinch zooms well past
            // DETAIL_ZOOM and stays put, not that it hits a particular number.
            assertTrue("pinch should zoom in, got ${view.scale}", view.scale > 2f)
            assertEquals("zoom must not jump to another page", pageBefore, view.anchor().first)

            // Pinching closed unwinds the zoom, and MIN_ZOOM is a hard floor: however far
            // the gesture goes, the page never shrinks below fit width.
            val zoomedIn = view.scale
            scenario.onActivity { pinch(view, spanFrom = 560f, spanTo = 60f) }
            Thread.sleep(300)
            assertTrue("pinching closed should zoom out", view.scale < zoomedIn)
            assertTrue("never below fit width, got ${view.scale}", view.scale >= 1f)

            scenario.onActivity { pinch(view, spanFrom = 560f, spanTo = 60f) }
            Thread.sleep(300)
            assertEquals("clamps at fit width", 1f, view.scale, 0.001f)
        }
    }

    @Test
    fun detailTileSharpensTheZoomedPage() {
        launch().use { scenario ->
            val view = scenario.pdfView()
            awaitDocument(view)
            Thread.sleep(600)

            scenario.onActivity { pinch(view, spanFrom = 120f, spanTo = 560f) }

            // Grabbed before the settle delay elapses, so this is the fit-width bitmap
            // stretched over the viewport.
            val blurry = capture(scenario, view)
            Thread.sleep(2500)
            // By now the detail tile has been rendered at the real zoom level.
            val sharp = capture(scenario, view)

            val before = edgeEnergy(blurry)
            val after = edgeEnergy(sharp)
            assertTrue(
                "detail tile should sharpen the page: before=$before after=$after",
                after > before * 1.4f,
            )
        }
    }

    // ---------------------------------------------------------------- gesture plumbing

    /** Dispatches a two-finger pinch centred on the view, opening from one span to another. */
    private fun pinch(view: View, spanFrom: Float, spanTo: Float) {
        val cx = view.width / 2f
        val cy = view.height / 2f
        val start = SystemClock.uptimeMillis()

        fun send(action: Int, count: Int, span: Float, time: Long) {
            val props = Array(count) { i ->
                MotionEvent.PointerProperties().apply {
                    id = i
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coords = Array(count) { i ->
                MotionEvent.PointerCoords().apply {
                    x = if (i == 0) cx - span else cx + span
                    y = cy
                    pressure = 1f
                    size = 1f
                }
            }
            val event = MotionEvent.obtain(
                start, time, action, count, props, coords,
                0, 0, 1f, 1f, 0, 0, 0, 0,
            )
            view.dispatchTouchEvent(event)
            event.recycle()
        }

        send(MotionEvent.ACTION_DOWN, 1, spanFrom, start)
        send(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, spanFrom, start + 10,
        )
        val steps = 16
        for (i in 1..steps) {
            val span = spanFrom + (spanTo - spanFrom) * i / steps
            send(MotionEvent.ACTION_MOVE, 2, span, start + 10 + i * 12L)
        }
        val end = start + 10 + steps * 12L
        send(
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, spanTo, end + 5,
        )
        send(MotionEvent.ACTION_UP, 1, spanTo, end + 10)
    }

    // ---------------------------------------------------------------- image measures

    private fun capture(scenario: ActivityScenario<ReaderActivity>, view: PdfView): Bitmap {
        lateinit var bmp: Bitmap
        scenario.onActivity {
            bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
        }
        return bmp
    }

    /**
     * Fraction of horizontally adjacent pixels separated by a steep luminance step.
     *
     * An upscaled bitmap smears every glyph edge across several pixels, so steep steps are
     * rare; a bitmap rendered at the target scale keeps them crisp. That difference is the
     * whole point of the detail tile, so it is what the test measures.
     */
    private fun edgeEnergy(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        var steep = 0L
        var total = 0L
        var y = 0
        while (y < h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 1 until w) {
                val a = luma(row[x - 1])
                val b = luma(row[x])
                if (abs(a - b) > 96) steep++
                total++
            }
            y += 3
        }
        return if (total == 0L) 0f else steep.toFloat() / total
    }

    private fun luma(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (r * 77 + g * 151 + b * 28) shr 8
    }
}
