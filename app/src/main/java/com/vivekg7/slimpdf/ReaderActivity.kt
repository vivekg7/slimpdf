package com.vivekg7.slimpdf

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** The viewer. Owns the document; [PdfView] owns the rendering. */
class ReaderActivity : Activity() {

    companion object {
        const val EXTRA_NAME = "com.vivekg7.slimpdf.NAME"
        private const val STATE_PAGE = "page"
        private const val STATE_OFFSET = "offset"
        private const val CHROME_HIDE_MS = 2500L

        /** Quiet period after the last page change before the position is written. */
        private const val SAVE_DEBOUNCE_MS = 1200L
    }

    private lateinit var pdf: PdfView
    private lateinit var topbar: LinearLayout
    private lateinit var indicator: TextView
    private lateinit var progress: ProgressBar
    private lateinit var error: TextView

    private var uri: Uri? = null
    private var name: String = ""
    private var pageCount = 0
    private var restorePage = 0
    private var restoreOffset = 0f
    private var loaded = false
    private var chromeShown = true

    private val handler = Handler(Looper.getMainLooper())
    private val hideChrome = Runnable { setChrome(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Insets.goEdgeToEdge(this)
        setContentView(R.layout.activity_reader)

        pdf = findViewById(R.id.pdf)
        topbar = findViewById(R.id.topbar)
        indicator = findViewById(R.id.indicator)
        progress = findViewById(R.id.progress)
        error = findViewById(R.id.error)

        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }

        Insets.onSystemBars(findViewById(R.id.root)) { top, bottom ->
            topbar.setPadding(0, top, topbar.paddingRight, 0)
            (indicator.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                bottom + (28 * resources.displayMetrics.density).toInt()
            indicator.requestLayout()
        }

        val source = resolveUri()
        if (source == null) {
            showError(getString(R.string.error_open))
            return
        }
        uri = source
        name = intent.getStringExtra(EXTRA_NAME) ?: displayName(source)
        findViewById<TextView>(R.id.title).text = name

        // A saved instance state is the more recent truth; the recents entry is the
        // fallback when the activity is started cold.
        // getInt on a bundle without the key returns 0, not null, so the elvis chain has
        // to test for the key -- otherwise a state bundle saved before the document
        // finished loading would silently override the remembered position with page 0.
        val saved = savedInstanceState?.takeIf { it.containsKey(STATE_PAGE) }
        val remembered = Recents.find(this, source.toString())
        restorePage = saved?.getInt(STATE_PAGE) ?: remembered?.page ?: 0
        restoreOffset = saved?.getFloat(STATE_OFFSET) ?: remembered?.offset ?: 0f

        pdf.onPageChanged = { page -> showPage(page) }
        pdf.onTap = { setChrome(!chromeShown) }

        load(source)
        scheduleHideChrome()
    }

    private fun resolveUri(): Uri? = when (intent?.action) {
        Intent.ACTION_SEND -> {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
        else -> intent?.data
    }

    private fun load(source: Uri) {
        progress.visibility = View.VISIBLE
        Thread({
            val result = runCatching { PdfDoc.open(this, source) }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    result.getOrNull()?.close()
                    return@runOnUiThread
                }
                result.fold(
                    onSuccess = { show(it) },
                    onFailure = { showError(messageFor(it)) },
                )
            }
        }, "pdf-open").start()
    }

    private fun show(doc: PdfDoc) {
        loaded = true
        pageCount = doc.pageCount
        progress.visibility = View.GONE
        pdf.setDoc(doc, restorePage, restoreOffset)
        showPage(restorePage)
        // Record it straight away. Waiting for onStop would mean a document vanishes from
        // recents entirely if the process is killed while it is open.
        savePositionNow()
    }

    private fun messageFor(t: Throwable): String = when (t) {
        is PdfDoc.PasswordProtected -> getString(R.string.error_protected)
        is SecurityException -> getString(R.string.error_permission)
        else -> getString(R.string.error_open)
    }

    private fun showError(message: String) {
        progress.visibility = View.GONE
        error.text = message
        error.visibility = View.VISIBLE
        indicator.visibility = View.GONE
        setChrome(true)
    }

    // ------------------------------------------------------------------ chrome

    private fun showPage(page: Int) {
        if (!loaded || pageCount <= 0) return
        indicator.text = getString(R.string.page_of, page + 1, pageCount)
        handler.removeCallbacks(savePosition)
        handler.postDelayed(savePosition, SAVE_DEBOUNCE_MS)
        if (indicator.visibility != View.VISIBLE) indicator.visibility = View.VISIBLE
        indicator.alpha = 1f
        if (!chromeShown) {
            // While scrolling with the chrome hidden the page number still matters, so it
            // is shown on its own and fades back out.
            handler.removeCallbacks(fadeIndicator)
            handler.postDelayed(fadeIndicator, CHROME_HIDE_MS)
        }
    }

    private val fadeIndicator = Runnable {
        if (!chromeShown) indicator.animate().alpha(0f).setDuration(220).start()
    }

    private fun setChrome(show: Boolean) {
        chromeShown = show
        handler.removeCallbacks(hideChrome)
        handler.removeCallbacks(fadeIndicator)
        // Toolbar visible means a dark scrim behind the status bar; hidden means bare
        // white paper. The bar glyphs have to follow, or they vanish into the page.
        Insets.darkSystemBarIcons(this, dark = !show)
        topbar.animate()
            .alpha(if (show) 1f else 0f)
            .translationY(if (show) 0f else -topbar.height.toFloat())
            .setDuration(180)
            .withStartAction { if (show) topbar.visibility = View.VISIBLE }
            .withEndAction { if (!show) topbar.visibility = View.INVISIBLE }
            .start()
        if (show) {
            indicator.animate().alpha(1f).setDuration(180).start()
            scheduleHideChrome()
        } else {
            handler.postDelayed(fadeIndicator, CHROME_HIDE_MS)
        }
    }

    private fun scheduleHideChrome() {
        handler.removeCallbacks(hideChrome)
        handler.postDelayed(hideChrome, CHROME_HIDE_MS)
    }

    // ------------------------------------------------------------------ lifecycle

    private val savePosition = Runnable { savePositionNow() }

    private fun savePositionNow() {
        val source = uri ?: return
        if (!loaded) return
        val (page, offset) = pdf.anchor()
        Recents.put(
            this,
            RecentDoc(
                uri = source.toString(),
                name = name,
                page = page,
                offset = offset,
                pageCount = pageCount,
                openedAt = System.currentTimeMillis(),
            ),
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Nothing meaningful to save yet, and writing zeroes here would lose the position
        // this activity was started to restore.
        if (!loaded) return
        val (page, offset) = pdf.anchor()
        outState.putInt(STATE_PAGE, page)
        outState.putFloat(STATE_OFFSET, offset)
    }

    override fun onStop() {
        handler.removeCallbacks(savePosition)
        savePositionNow()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        pdf.close()
        super.onDestroy()
    }

    private fun displayName(source: Uri): String {
        runCatching {
            contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getString(0) }
        }
        return source.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.app_name)
    }
}
