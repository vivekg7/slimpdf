package com.crylo.slimpdf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One previously opened document and where the reader left off.
 *
 * [offset] is the fraction of [page] that had scrolled past the top of the viewport, so a
 * position survives rotation and window resizing — storing raw pixels would not.
 */
data class RecentDoc(
    val uri: String,
    val name: String,
    val page: Int,
    val offset: Float,
    val pageCount: Int,
    val openedAt: Long,
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
            )
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** Inserts or updates [doc], moving it to the front of the list. */
    fun put(ctx: Context, doc: RecentDoc) {
        val docs = load(ctx)
        docs.removeAll { it.uri == doc.uri }
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
}
