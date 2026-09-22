package com.crylo.slimpdf

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * One previously opened document and where the reader left off.
 *
 * [offset] is the fraction of [page] that had scrolled past the top of the viewport, so a
 * position survives rotation and window resizing — storing raw pixels would not.
 *
 * [uri] is what the document is opened from: a file path when it has one on shared
 * storage, else a content URI. [copy], when set, names the private copy under
 * [Recents.docsDir] that is read instead, because the URI's grant has lapsed — see
 * [Recents.copyIn]. [hash] is the content [Sources.fingerprint], which is what recognises
 * the same document arriving under a new URI.
 */
data class RecentDoc(
    val uri: String,
    val name: String,
    val page: Int,
    val offset: Float,
    val pageCount: Int,
    val openedAt: Long,
    val copy: String? = null,
    val hash: String? = null,
)

/**
 * The recents list, kept in SharedPreferences as a JSON array.
 *
 * A database would be the reflex here, but the list is capped at [LIMIT] entries and is
 * rewritten whole on every change, so a room/sqlite dependency would buy nothing but
 * bytes. org.json ships with the platform.
 */
object Recents {
    private const val FILE = "recents"
    private const val KEY = "docs"
    private const val LIMIT = 50

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableList<RecentDoc> {
        val raw = prefs(ctx).getString(KEY, null) ?: return mutableListOf()
        val out = mutableListOf<RecentDoc>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += RecentDoc(
                    uri = o.getString("uri"),
                    name = o.optString("name", "Document"),
                    page = o.optInt("page", 0),
                    offset = o.optDouble("offset", 0.0).toFloat(),
                    pageCount = o.optInt("pages", 0),
                    openedAt = o.optLong("at", 0L),
                    copy = o.optString("copy").ifEmpty { null },
                    hash = o.optString("hash").ifEmpty { null },
                )
            }
        }
        return out
    }

    private fun save(ctx: Context, docs: List<RecentDoc>) {
        val arr = JSONArray()
        docs.take(LIMIT).forEach { d ->
            arr.put(
                JSONObject()
                    .put("uri", d.uri)
                    .put("name", d.name)
                    .put("page", d.page)
                    .put("offset", d.offset.toDouble())
                    .put("pages", d.pageCount)
                    .put("at", d.openedAt)
                    .put("copy", d.copy)
                    .put("hash", d.hash)
            )
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /**
     * Inserts or updates [doc], moving it to the front of the list.
     *
     * An entry with the same content replaces this one too, so a document shared again
     * under a new URI stays a single entry.
     */
    fun put(ctx: Context, doc: RecentDoc) {
        val docs = load(ctx)
        docs.removeAll { it.uri == doc.uri || (doc.hash != null && it.hash == doc.hash) }
        docs.add(0, doc)
        save(ctx, docs)
    }

    fun remove(ctx: Context, uri: String) {
        val docs = load(ctx)
        if (docs.removeAll { it.uri == uri }) save(ctx, docs)
    }

    /** Re-inserts [doc] at [index], for undoing a swipe. */
    fun insertAt(ctx: Context, index: Int, doc: RecentDoc) {
        val docs = load(ctx)
        docs.removeAll { it.uri == doc.uri }
        docs.add(index.coerceIn(0, docs.size), doc)
        save(ctx, docs)
    }

    fun find(ctx: Context, uri: String): RecentDoc? = load(ctx).firstOrNull { it.uri == uri }

    fun findByHash(ctx: Context, hash: String): RecentDoc? =
        load(ctx).firstOrNull { it.hash == hash }

    // ------------------------------------------------------------------ private copies

    /** Copies younger than this are never pruned: their entry may not be written yet. */
    private const val PRUNE_GRACE_MS = 60_000L

    fun docsDir(ctx: Context): File = File(ctx.applicationContext.filesDir, "docs")

    /**
     * Copies the document at [uri] into app storage and returns the copy's file name.
     *
     * A PDF handed over by "Open with" or "Share" comes with a read grant that dies with
     * the activity it was sent to, and almost no sender makes that grant persistable. The
     * recents entry would be a dead link by the time it is tapped, so the bytes are kept
     * instead. The name is the content [Sources.fingerprint], so the same document shared
     * again replaces its copy rather than piling up another.
     */
    fun copyIn(ctx: Context, uri: Uri): String {
        val dir = docsDir(ctx).apply { mkdirs() }
        // Written aside and renamed, so a failed copy never clobbers a good one.
        val tmp = File.createTempFile("copy", ".tmp", dir)
        try {
            val input = ctx.contentResolver.openInputStream(uri)
                ?: throw IOException("no stream for $uri")
            input.use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o) } }
            val name = Sources.fingerprint(tmp) + ".pdf"
            if (!tmp.renameTo(File(dir, name))) throw IOException("could not store $name")
            return name
        } finally {
            tmp.delete()
        }
    }

    /** The content fingerprint a copy made by [copyIn] is named after. */
    fun hashOf(copy: String): String = copy.removeSuffix(".pdf")

    /**
     * Deletes copies no entry refers to any more.
     *
     * Not done on [remove], because a removal can still be undone; the caller runs this
     * once it no longer can.
     */
    fun prune(ctx: Context) {
        val keep = load(ctx).mapNotNullTo(HashSet()) { it.copy }
        val now = System.currentTimeMillis()
        docsDir(ctx).listFiles()?.forEach { f ->
            if (f.name !in keep && now - f.lastModified() > PRUNE_GRACE_MS) f.delete()
        }
    }
}
