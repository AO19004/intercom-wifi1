package com.example.intercom

import java.io.DataInputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

const val RATE = 16000          // 16 kHz mono PCM16
const val FRAME = 320           // 20 ms
const val PORT = 50505
const val MAX_CLIENTS = 16      // حداقل ۱۰ لازم بود؛ ۱۶ برای اطمینان

const val T_HELLO = 1           // نام کلاینت
const val T_AUDIO = 2           // یک فریم صدا (640 بایت)
const val T_PING = 3
const val T_CTRL = 4            // مستر -> کلاینت: ۱ = مستر با تو صحبت می‌کند

fun ShortArray.toBytes(): ByteArray {
    val b = ByteArray(size * 2)
    ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(this)
    return b
}

fun ByteArray.toShorts(): ShortArray {
    val s = ShortArray(size / 2)
    ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s)
    return s
}

fun ShortArray.peak(): Int {
    var m = 0
    for (v in this) { val a = abs(v.toInt()); if (a > m) m = a }
    return m
}

fun ArrayBlockingQueue<ShortArray>.pushDrop(f: ShortArray) {
    if (!offer(f)) { poll(); offer(f) }   // تاخیر جمع نشود: قدیمی‌ترین را دور بریز
}

/** اتصال TCP با فریم‌بندی [نوع:1][طول:2][داده] و صف ارسال مستقل */
class Link(
    private val sock: Socket,
    private val onMsg: (Int, ByteArray) -> Unit,
    private val onClose: () -> Unit
) {
    private val q = ArrayBlockingQueue<ByteArray>(16)
    @Volatile var alive = true

    init { sock.tcpNoDelay = true; sock.keepAlive = true }

    fun start() {
        thread(name = "link-r") {
            try {
                val i = DataInputStream(sock.getInputStream().buffered())
                while (alive) {
                    val t = i.readUnsignedByte()
                    val len = i.readUnsignedShort()
                    val b = ByteArray(len)
                    i.readFully(b)
                    onMsg(t, b)
                }
            } catch (_: Exception) {}
            close()
        }
        thread(name = "link-w") {
            try {
                val o = sock.getOutputStream()
                while (alive) {
                    val p = q.poll(1, TimeUnit.SECONDS) ?: continue
                    o.write(p); o.flush()
                }
            } catch (_: Exception) {}
            close()
        }
    }

    fun send(type: Int, payload: ByteArray) {
        if (!alive) return
        val p = ByteArray(3 + payload.size)
        p[0] = type.toByte()
        p[1] = (payload.size shr 8).toByte()
        p[2] = payload.size.toByte()
        System.arraycopy(payload, 0, p, 3, payload.size)
        if (!q.offer(p)) { q.poll(); q.offer(p) }
    }

    @Synchronized fun close() {
        if (!alive) return
        alive = false
        try { sock.close() } catch (_: Exception) {}
        onClose()
    }
}
const val DISC_PORT = 50506     // پورت شنود کلاینت برای پیدا شدن توسط مستر
const val T_CONF = 5            // مستر -> کلاینت: صدای سایر کلاینت‌ها (حالت گفتگو)
