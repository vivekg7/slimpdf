package com.crylo.slimpdf

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/** The launcher screen: everything opened before, most recent first. */
class RecentsActivity : Activity() {

    companion object {
        private const val REQ_OPEN = 1
        private const val UNDO_MS = 4500L
        private const val PREFS = "app"
        private const val KEY_ASKED_FILES = "asked_all_files"
    }

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var header: TextView
    private lateinit var openButton: Button
    private lateinit var undoBar: LinearLayout
    private lateinit var adapter: Adapter

    private val docs = mutableListOf<RecentDoc>()
    private val handler = Handler(Looper.getMainLooper())
    private var undoDoc: RecentDoc? = null
    private var undoIndex = 0
    private val hideUndo = Runnable {
        showUndoBar(false)
        undoDoc = null
        // Only now is a removed document's private copy truly unreferenced.
        Recents.prune(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Insets.goEdgeToEdge(this)
        setContentView(R.layout.activity_recents)
        // Catches copies orphaned by an undo window cut short, or entries pushed off the
        // end of the list.
        Thread({ Recents.prune(this) }, "prune").start()

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        header = findViewById(R.id.header)
        openButton = findViewById(R.id.open)
        undoBar = findViewById(R.id.undobar)

        adapter = Adapter()
        list.adapter = adapter

        openButton.setOnClickListener { pickDocument() }
        findViewById<TextView>(R.id.undo).setOnClickListener { undoRemove() }

        Insets.onSystemBars(findViewById(R.id.root)) { top, bottom ->
            header.setPaddingRelative(
                header.paddingStart, top + dp(20), header.paddingEnd, dp(12),
            )
            // The list sits behind the header and the floating button, so pad it past both.
            header.post {
                list.setPadding(0, header.height, 0, bottom + dp(100))
            }
            (openButton.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom + dp(28)
            openButton.requestLayout()
            undoBar.setPaddingRelative(
                undoBar.paddingStart, undoBar.paddingTop,
                undoBar.paddingEnd, dp(14) + bottom,
            )
        }

        if (savedInstanceState == null) offerFileAccess()
    }

    // ------------------------------------------------------------------ all files access

    /**
     * Asks once, on first launch, for all files access.
     *
     * With it a PDF opened from another app is kept in recents by its path on shared
     * storage, so it follows the real file instead of a private copy. It is a special
     * access granted on a Settings screen, not a runtime prompt, hence the explanation
     * first. Declining is fine: copies cover everything, only less neatly.
     */
    private fun offerFileAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Sources.hasFileAccess()) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_FILES, false)) return
        prefs.edit().putBoolean(KEY_ASKED_FILES, true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.files_access_title)
            .setMessage(R.string.files_access_message)
            .setPositiveButton(R.string.files_access_allow) { _, _ -> openFileAccessSettings() }
            .setNegativeButton(R.string.files_access_later, null)
            .show()
    }

    private fun openFileAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val forApp = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", packageName, null),
        )
        try {
            startActivity(forApp)
        } catch (e: ActivityNotFoundException) {
            // Some builds only offer the list of all apps.
            val all = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            runCatching { startActivity(all) }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideUndo)
        super.onDestroy()
    }

    private fun reload() {
        docs.clear()
        docs += Recents.load(this)
        adapter.notifyDataSetChanged()
        val none = docs.isEmpty()
        empty.visibility = if (none) View.VISIBLE else View.GONE
        list.visibility = if (none) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ opening

    private fun pickDocument() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        try {
            startActivityForResult(intent, REQ_OPEN)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_SHORT).show()
        }
    }

    // registerForActivityResult is AndroidX. The platform callback is deprecated but is
    // the only option here, and it works.
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_OPEN || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        // Without this the grant dies with the process and the recents entry is a dead link.
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        open(uri, displayName(uri))
    }

    private fun open(uri: Uri, name: String, reopen: Boolean = false) {
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .setData(uri)
                .putExtra(ReaderActivity.EXTRA_NAME, name)
                .putExtra(ReaderActivity.EXTRA_REOPEN, reopen)
        )
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.app_name)
    }

    // ------------------------------------------------------------------ removal

    private fun remove(position: Int) {
        if (position !in docs.indices) return
        val doc = docs.removeAt(position)
        Recents.remove(this, doc.uri)
        adapter.notifyDataSetChanged()
        if (docs.isEmpty()) reload()

        undoDoc = doc
        undoIndex = position
        showUndoBar(true)
        handler.removeCallbacks(hideUndo)
        handler.postDelayed(hideUndo, UNDO_MS)
    }

    private fun undoRemove() {
        val doc = undoDoc ?: return
        Recents.insertAt(this, undoIndex, doc)
        handler.removeCallbacks(hideUndo)
        hideUndo.run()
        reload()
    }

    /**
     * Shows or hides the undo bar, lifting the floating button clear of it.
     *
     * Both sit at the bottom of the same FrameLayout, so without the shift the bar is
     * drawn straight over the button.
     */
    private fun showUndoBar(show: Boolean) {
        if (show) {
            undoBar.visibility = View.VISIBLE
            // The bar has no measured height until it has been laid out.
            undoBar.post {
                openButton.animate()
                    .translationY(-undoBar.height.toFloat())
                    .setDuration(180)
                    .start()
            }
        } else {
            openButton.animate().translationY(0f).setDuration(180).start()
            undoBar.animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction {
                    undoBar.visibility = View.GONE
                    undoBar.alpha = 1f
                }
                .start()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ adapter

    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = docs.size

        override fun getItem(position: Int): Any = docs[position]

        override fun getItemId(position: Int): Long = docs[position].uri.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? SwipeRow) ?: LayoutInflater.from(this@RecentsActivity)
                .inflate(R.layout.item_recent, parent, false) as SwipeRow
            row.reset()

            val doc = docs[position]
            row.findViewById<TextView>(R.id.title).text = doc.name
            row.findViewById<TextView>(R.id.subtitle).text = subtitle(doc)
            row.setOnClickListener { open(Uri.parse(doc.uri), doc.name, reopen = true) }
            row.onDismiss = { remove(docs.indexOfFirst { it.uri == doc.uri }) }
            return row
        }

        private fun subtitle(doc: RecentDoc): String {
            val where = when {
                doc.pageCount <= 0 -> null
                doc.page <= 0 ->
                    resources.getQuantityString(
                        R.plurals.page_count, doc.pageCount, doc.pageCount,
                    )
                else -> getString(R.string.page_of, doc.page + 1, doc.pageCount)
            }
            val now = System.currentTimeMillis()
            val when_ = when {
                doc.openedAt <= 0 -> null
                // Anything under a minute formats as "0 minutes ago", which reads as a bug.
                now - doc.openedAt < DateUtils.MINUTE_IN_MILLIS -> getString(R.string.just_now)
                else -> DateUtils.getRelativeTimeSpanString(
                    doc.openedAt,
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString()
            }
            return listOfNotNull(where, when_).joinToString("  ·  ")
        }
    }
}
