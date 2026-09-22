package com.crylo.slimpdf

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
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
import android.widget.Toast
import java.io.File

/** The viewer. Owns the document; [PdfView] owns the rendering. */
class ReaderActivity : Activity() {

    companion object {
        const val EXTRA_NAME = "com.crylo.slimpdf.NAME"

        /** Set when opening a recents entry, whose source was settled when it was added. */
        const val EXTRA_REOPEN = "com.crylo.slimpdf.REOPEN"
        private const val REQ_PICK = 1
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

    /** Where the document is read from and what its recents entry records. */
    private var target: Target? = null
    private var name: String = ""
    /** What this activity was asked to open, kept for a retry once access is sorted. */
    private var source: Uri? = null
    /** Set while the user is in Settings deciding on all files access. */
    private var awaitingAccess = false
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

        Insets.onSystemBars(findViewById(android.R.id.content)) { top, bottom ->
            topbar.setPaddingRelative(0, top, topbar.paddingEnd, 0)
            pdf.topInset = top
            (indicator.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                bottom + (28 * resources.displayMetrics.density).toInt()
            indicator.requestLayout()
        }

        val source = resolveUri()
        if (source == null) {
            showError(getString(R.string.error_open))
            return
        }
        name = intent.getStringExtra(EXTRA_NAME) ?: displayName(source)
        findViewById<TextView>(R.id.title).text = name

        // A saved instance state is the more recent truth; the recents entry is the
        // fallback when the activity is started cold, applied once [locate] has found it.
        // getInt on a bundle without the key returns 0, not null, so this has to test for
        // the key -- otherwise a state bundle saved before the document finished loading
        // would silently override the remembered position with page 0.
        val saved = savedInstanceState?.takeIf { it.containsKey(STATE_PAGE) }

        pdf.onPageChanged = { page -> showPage(page) }
        pdf.onTap = { setChrome(!chromeShown) }

        this.source = source
        load(source, saved, intent.getBooleanExtra(EXTRA_REOPEN, false))
        scheduleHideChrome()
    }

    private fun resolveUri(): Uri? = when (intent?.action) {
        Intent.ACTION_SEND -> {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
        else -> intent?.data
    }

    /**
     * A document's resolved source.
     *
     * [read] is what is opened now. [key], [copy] and [hash] are what its recents entry
     * records, and [remembered] is that entry as found, for the position to resume.
     */
    private class Target(
        val read: Uri,
        val key: String,
        val copy: String?,
        val hash: String?,
        val remembered: RecentDoc?,
    )

    private fun copyFile(name: String) = File(Recents.docsDir(this), name)

    /**
     * Settles where [source] is read from, now and every time it is reopened from recents.
     *
     * A handed-over document's read grant ends with this activity, and all files access
     * cannot revive it. So, best first: the file's own path on shared storage, which lasts
     * as long as the file does; the URI itself, if its grant could be kept; otherwise a
     * private copy. Each is matched to an earlier entry by content as well as by key,
     * because chat apps share the same file under a new URI every time.
     * Runs on the loading thread: it reads the file, and a copy can be large.
     */
    private fun locate(source: Uri, reopen: Boolean): Target {
        if (reopen) {
            val doc = Recents.find(this, source.toString())
            val kept = doc?.copy?.let(::copyFile)?.takeIf { it.isFile }
            // Kept by its path, and all files access has since been switched off.
            if (kept == null && source.scheme == "file" && Sources.canAskFileAccess() &&
                !Sources.hasFileAccess() && !File(source.path.orEmpty()).canRead()
            ) {
                throw NeedsFileAccess()
            }
            return Target(
                read = kept?.let(Uri::fromFile) ?: source,
                key = source.toString(),
                copy = kept?.name,
                hash = doc?.hash,
                remembered = doc,
            )
        }

        fun earlier(key: String, hash: String?) =
            Recents.find(this, key) ?: hash?.let { Recents.findByHash(this, it) }

        Sources.fileFor(this, source)?.let { file ->
            val read = Uri.fromFile(file)
            val hash = runCatching { Sources.fingerprint(file) }.getOrNull()
            return Target(read, read.toString(), null, hash, earlier(read.toString(), hash))
        }

        // Succeeds again for a document from the picker, which already kept its grant.
        val persisted = runCatching {
            contentResolver.takePersistableUriPermission(
                source, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.isSuccess
        if (persisted) {
            val hash = Sources.fingerprint(this, source)
            return Target(source, source.toString(), null, hash, earlier(source.toString(), hash))
        }

        val key = source.toString()
        val copy = runCatching { Recents.copyIn(this, source) }.getOrNull()
            ?: return Target(source, key, null, null, earlier(key, null))
        val hash = Recents.hashOf(copy)
        val doc = earlier(key, hash)
        // Read before from a path that still exists: keep reading that, and drop the copy.
        // No other entry can own it, since entries with the same content are merged.
        if (doc != null && doc.copy == null && doc.uri.startsWith("file:")) {
            val file = File(Uri.parse(doc.uri).path.orEmpty())
            if (file.isFile && file.canRead()) {
                copyFile(copy).delete()
                return Target(Uri.fromFile(file), doc.uri, null, doc.hash ?: hash, doc)
            }
        }
        return Target(Uri.fromFile(copyFile(copy)), key, copy, hash, doc)
    }

    private fun load(source: Uri, saved: Bundle?, reopen: Boolean) {
        error.visibility = View.GONE
        progress.visibility = View.VISIBLE
        Thread({
            val result = runCatching {
                val t = locate(source, reopen)
                t to PdfDoc.open(this, t.read)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    result.getOrNull()?.second?.close()
                    return@runOnUiThread
                }
                result.fold(
                    onSuccess = { (t, doc) ->
                        target = t
                        restorePage = saved?.getInt(STATE_PAGE) ?: t.remembered?.page ?: 0
                        restoreOffset =
                            saved?.getFloat(STATE_OFFSET) ?: t.remembered?.offset ?: 0f
                        show(doc)
                    },
                    onFailure = {
                        showError(messageFor(it))
                        if (it is NeedsFileAccess) askFileAccess()
                    },
                )
            }
        }, "pdf-open").start()
    }

    /** A recents entry kept by its path cannot be read without all files access. */
    private class NeedsFileAccess : Exception("all files access is off")

    /**
     * Asks for all files access again, for an entry that was kept by its path.
     *
     * Without that access the file cannot be read where it is, so there is nothing to
     * copy either. The way round is to pick it once in the system picker, whose grant
     * can be kept for good; the content fingerprint then matches it to this entry, so the
     * position survives and the entry switches over to the picked file.
     */
    private fun askFileAccess() {
        AlertDialog.Builder(this)
            .setTitle(R.string.files_access_again_title)
            .setMessage(R.string.files_access_again_message)
            .setPositiveButton(R.string.files_access_allow) { _, _ ->
                awaitingAccess = true
                Sources.openFileAccessSettings(this)
            }
            .setNeutralButton(R.string.files_access_pick) { _, _ -> pickInstead() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickInstead() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        try {
            startActivityForResult(intent, REQ_PICK)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_SHORT).show()
        }
    }

    // As in RecentsActivity: the platform callback is the only option without AndroidX.
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val picked = data?.data ?: return
        // Taken again by locate; a fresh open then finds this entry by its content.
        source = picked
        // The picker allows any PDF, not only the one this entry was for.
        name = displayName(picked)
        findViewById<TextView>(R.id.title).text = name
        load(picked, null, reopen = false)
    }

    override fun onResume() {
        super.onResume()
        if (!awaitingAccess) return
        awaitingAccess = false
        val s = source ?: return
        if (Sources.hasFileAccess()) load(s, null, reopen = true) else askFileAccess()
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
        is NeedsFileAccess -> getString(R.string.error_needs_access)
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
        val t = target ?: return
        if (!loaded) return
        val (page, offset) = pdf.anchor()
        Recents.put(
            this,
            RecentDoc(
                uri = t.key,
                name = name,
                page = page,
                offset = offset,
                pageCount = pageCount,
                openedAt = System.currentTimeMillis(),
                copy = t.copy,
                hash = t.hash,
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
