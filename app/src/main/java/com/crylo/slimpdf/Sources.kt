package com.crylo.slimpdf

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** Where a document's bytes can be read from, independent of the URI it arrived under. */
object Sources {
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    private const val DOWNLOADS = "com.android.providers.downloads.documents"

    /** Bytes hashed from each end of a file by [fingerprint]. */
    private const val EDGE = 64 * 1024

    /**
     * The file on shared storage behind [uri], or null when there is none this app can read.
     *
     * Only meaningful with all files access: a content URI's grant cannot be revived once
     * it lapses, not even with that access, but a path can be read for as long as the file
     * exists. Attachments from mail and chat apps live in the sender's private storage and
     * cloud documents have no local file at all, so for those this is null and the caller
     * falls back to a copy.
     */
    fun fileFor(ctx: Context, uri: Uri): File? {
        if (!hasFileAccess()) return null
        val path = runCatching { pathFor(ctx, uri) }.getOrNull() ?: return null
        return File(path).takeIf { it.isFile && it.canRead() }
    }

    /**
     * Whether all files access is granted. It first exists on API 30; Android 10 relies on
     * copies alone rather than on the legacy storage opt-out, which it would also need.
     */
    fun hasFileAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    private fun pathFor(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme != "content") return null
        return when (uri.authority) {
            // The document id spells the path out: "primary:Download/x.pdf", or
            // "1A2B-3C4D:x.pdf" for a removable volume.
            EXTERNAL_STORAGE -> {
                val id = DocumentsContract.getDocumentId(uri)
                val volume = id.substringBefore(':')
                val rest = id.substringAfter(':')
                if (volume == "primary") {
                    @Suppress("DEPRECATION")
                    "${Environment.getExternalStorageDirectory()}/$rest"
                } else {
                    "/storage/$volume/$rest"
                }
            }
            // "raw:/storage/…" carries the path; "msf:123" is a MediaStore row.
            DOWNLOADS -> {
                val id = DocumentsContract.getDocumentId(uri)
                when {
                    id.startsWith("raw:") -> id.removePrefix("raw:")
                    id.startsWith("msf:") -> dataColumn(
                        ctx,
                        ContentUris.withAppendedId(
                            MediaStore.Files.getContentUri("external"),
                            id.removePrefix("msf:").toLong(),
                        ),
                    )
                    else -> null
                }
            }
            // MediaStore, and any other provider that still exposes the legacy column.
            else -> dataColumn(ctx, uri)
        }
    }

    @Suppress("DEPRECATION")
    private fun dataColumn(ctx: Context, uri: Uri): String? {
        val column = MediaStore.MediaColumns.DATA
        ctx.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(column)
                if (i >= 0 && !c.isNull(i)) return c.getString(i)
            }
        }
        return null
    }

    /**
     * Identifies a document by its content, so it is recognised under any URI.
     *
     * Chat apps hand out a fresh URI every time the same file is shared, so the URI alone
     * cannot tell that a document was read before. Hashing the whole file would mean
     * reading all of it before the first page shows; the length plus 64 KB from each end
     * is enough in practice, because a PDF ends in its cross-reference table and trailer,
     * which change whenever anything in the file does.
     */
    fun fingerprint(file: File): String =
        RandomAccessFile(file, "r").use { fingerprint(it.channel) }

    /** As [fingerprint], for a provider's descriptor. Null if it cannot seek. */
    fun fingerprint(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { fingerprint(it.channel) }
        }
    }.getOrNull()

    private fun fingerprint(ch: FileChannel): String {
        val size = ch.size()
        val md = MessageDigest.getInstance("SHA-256")
        md.update(ByteBuffer.allocate(8).putLong(size).array())
        val span = minOf(size, EDGE.toLong()).toInt()
        md.update(read(ch, 0, span))
        md.update(read(ch, size - span, span))
        return md.digest().take(16).joinToString("") { "%02x".format(it) }
    }

    private fun read(ch: FileChannel, at: Long, len: Int): ByteArray {
        val buf = ByteBuffer.allocate(len)
        while (buf.hasRemaining()) {
            if (ch.read(buf, at + buf.position()) < 0) break
        }
        return buf.array()
    }
}
