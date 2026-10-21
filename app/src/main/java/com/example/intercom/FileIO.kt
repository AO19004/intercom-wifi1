package com.example.intercom

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** ابزارهای خواندن فایل انتخاب‌شده و ذخیره‌ی فایل دریافتی در Download/Intercom/Received */
object FileIO {
    fun nameSize(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = "file"
        var size = -1L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {}
        if (size < 0) {
            try { ctx.contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize } } catch (_: Exception) {}
        }
        return name to size
    }

    fun pct(done: Long, total: Long): Int = if (total <= 0) 0 else (done * 100 / total).toInt()

    private fun safeName(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100).ifBlank { "file" }

    private fun mime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    class Sink(val out: OutputStream, val path: String, private val onFinish: () -> Unit, private val onAbort: () -> Unit) {
        fun finish() { try { out.close() } catch (_: Exception) {}; onFinish() }
        fun abort() { try { out.close() } catch (_: Exception) {}; onAbort() }
    }

    fun openSink(ctx: Context, rawName: String): Sink {
        val name = safeName(rawName)
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Intercom/Received")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("ساخت فایل ممکن نشد")
            val os = ctx.contentResolver.openOutputStream(u) ?: throw IOException("نوشتن فایل ممکن نشد")
            return Sink(os, "Download/Intercom/Received/$name",
                { ctx.contentResolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) },
                { ctx.contentResolver.delete(u, null, null) })
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Intercom/Received")
            dir.mkdirs()
            var f = File(dir, name)
            var n = 1
            while (f.exists()) { f = File(dir, "${n++}_$name") }
            val target = f
            return Sink(FileOutputStream(target), "Download/Intercom/Received/${target.name}", {}, { target.delete() })
        }
    }

    /** دقیقاً size بایت کپی می‌کند؛ تعداد بایت‌های کپی‌شده را برمی‌گرداند */
    fun copy(inp: InputStream, out: OutputStream, size: Long, progress: (Long) -> Unit): Long {
        val buf = ByteArray(32768)
        var done = 0L
        var last = 0L
        while (done < size) {
            val n = inp.read(buf, 0, minOf(buf.size.toLong(), size - done).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            val now = System.currentTimeMillis()
            if (now - last > 250) { last = now; progress(done) }
        }
        out.flush()
        progress(done)
        return done
    }
}
