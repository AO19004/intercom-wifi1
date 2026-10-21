package com.example.intercom

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** ضبط مکالمه به‌صورت WAV در Download/Intercom */
class Recorder {
    @Volatile var active = false
    @Volatile var name = ""
    private var fos: FileOutputStream? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var bytes = 0L

    @Synchronized fun start(ctx: Context): Boolean {
        if (active) return true
        name = "intercom_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Intercom")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return false
                uri = u
                val p = ctx.contentResolver.openFileDescriptor(u, "rw") ?: return false
                pfd = p
                fos = FileOutputStream(p.fileDescriptor)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Intercom")
                dir.mkdirs()
                fos = FileOutputStream(File(dir, name))
            }
            fos!!.write(header(0))
            bytes = 0
            active = true
            return true
        } catch (_: Exception) {
            cleanup()
            return false
        }
    }

    @Synchronized fun write(f: ShortArray) {
        if (!active) return
        try { val b = f.toBytes(); fos?.write(b); bytes += b.size } catch (_: Exception) {}
    }

    @Synchronized fun stop(ctx: Context) {
        if (!active) return
        active = false
        try { fos?.channel?.position(0); fos?.write(header(bytes)) } catch (_: Exception) {}
        cleanup()
        val u = uri
        if (Build.VERSION.SDK_INT >= 29 && u != null) {
            try {
                val cv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                ctx.contentResolver.update(u, cv, null, null)
            } catch (_: Exception) {}
        }
    }

    private fun cleanup() {
        try { fos?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        fos = null; pfd = null
    }

    private fun header(dataLen: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataLen).toInt())
        b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray()); b.putInt(16)
        b.putShort(1); b.putShort(1); b.putInt(RATE); b.putInt(RATE * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataLen.toInt())
        return b.array()
    }
}
