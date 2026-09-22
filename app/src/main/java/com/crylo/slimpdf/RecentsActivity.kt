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
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast

/** The launcher screen: favourites, then everything else opened before, most recent first. */
class RecentsActivity : Activity() {

    companion object {
        private const val REQ_OPEN = 1
        private const val UNDO_MS = 4500L
        private const val PREFS = "app"
        private const val KEY_ASKED_FILES = "asked_all_files"
        private const val MENU_FAVORITE = 1
        private const val MENU_REMOVE = 2
    }

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var header: TextView
    private lateinit var openButton: Button
    private lateinit var undoBar: LinearLayout
    private lateinit var adapter: Adapter

    /** The entries in stored order, most recent first; undo puts one back by its index. */
    private val docs = mutableListOf<RecentDoc>()

    /** What the list shows: section titles (string resource ids) and entries. */
    private val rows = mutableListOf<Any>()

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
        if (!Sources.canAskFileAccess() || Sources.hasFileAccess()) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_FILES, false)) return
        prefs.edit().putBoolean(KEY_ASKED_FILES, true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.files_access_title)
            .setMessage(R.string.files_access_message)
            .setPositiveButton(R.string.files_access_allow) { _, _ ->
                Sources.openFileAccessSettings(this)
            }
            .setNegativeButton(R.string.files_access_later, null)
            .show()
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
        rows.clear()
        val (favorites, others) = docs.partition { it.favorite }
        // Headings only earn their space once there are two sections to tell apart.
        if (favorites.isEmpty()) {
            rows.addAll(others)
        } else {
            rows.add(R.string.favorites)
            rows.addAll(favorites)
            if (others.isNotEmpty()) {
                rows.add(R.string.recent)
                rows.addAll(others)
            }
        }
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

    private fun remove(doc: RecentDoc) {
        val position = docs.indexOfFirst { it.uri == doc.uri }
        if (position < 0) return
        Recents.remove(this, doc.uri)
        reload()

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

    /** The long-press menu: the swipe's removal, spelled out, plus favouriting. */
    private fun showMenu(row: View, doc: RecentDoc) {
        val menu = PopupMenu(this, row, Gravity.END)
        menu.menu.add(
            0, MENU_FAVORITE, 0,
            if (doc.favorite) R.string.remove_favorite else R.string.add_favorite,
        )
        menu.menu.add(0, MENU_REMOVE, 1, R.string.remove_from_recents)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_FAVORITE -> {
                    Recents.setFavorite(this, doc.uri, !doc.favorite)
                    reload()
                }
                MENU_REMOVE -> remove(doc)
            }
            true
        }
        menu.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ adapter

    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): Any = rows[position]

        override fun getItemId(position: Int): Long = rows[position].hashCode().toLong()

        override fun getViewTypeCount(): Int = 2

        override fun getItemViewType(position: Int): Int = if (rows[position] is Int) 1 else 0

        override fun isEnabled(position: Int): Boolean = rows[position] !is Int

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = rows[position]
            if (item is Int) {
                val title = (convertView as? TextView)
                    ?: LayoutInflater.from(this@RecentsActivity)
                        .inflate(R.layout.item_header, parent, false) as TextView
                title.setText(item)
                return title
            }

            val row = (convertView as? SwipeRow) ?: LayoutInflater.from(this@RecentsActivity)
                .inflate(R.layout.item_recent, parent, false) as SwipeRow
            row.reset()

            val doc = item as RecentDoc
            row.findViewById<TextView>(R.id.title).text = doc.name
            row.findViewById<TextView>(R.id.subtitle).text = subtitle(doc)
            row.findViewById<ImageView>(R.id.star).visibility =
                if (doc.favorite) View.VISIBLE else View.GONE
            row.setOnClickListener { open(Uri.parse(doc.uri), doc.name, reopen = true) }
            row.setOnLongClickListener { showMenu(row, doc); true }
            // A favourite is kept on purpose, so a stray sideways drag must not throw it
            // away; the long-press menu still removes it.
            row.swipeable = !doc.favorite
            row.onDismiss = { remove(doc) }
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
