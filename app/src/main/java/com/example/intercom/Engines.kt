package com.example.intercom

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ClientSession(val id: Int, val addr: String) {
    lateinit var link: Link
    @Volatile var name = "..."
    @Volatile var muted = false        // صدای این کلاینت در مستر شنیده نشود
    @Volatile var volume = 1f          // 0..2 : بلندی صدای کلاینت در مستر
    @Volatile var hearMaster = true    // مستر با این کلاینت صحبت کند
    @Volatile var level = 0
    val inQ = ArrayBlockingQueue<ShortArray>(8)
}

class MasterEngine(private val io: AudioIO) {
    val sessions = CopyOnWriteArrayList<ClientSession>()
    @Volatile var micOpen = true       // میکروفون مستر برای همه
    @Volatile var conference = false   // کلاینت‌ها همدیگر را بشنوند
    @Volatile var lockedCount = 0      // 0 = قفل نیست
    @Volatile var myLevel = 0
    @Volatile var serverStatus = "در حال راه‌اندازی…"
    @Volatile var audioOk = true
    @Volatile var autoScan = true
    @Volatile var scanStatus = ""
    @Volatile private var known: Set<String> = emptySet()   // IP کلاینت‌ها در لحظه‌ی قفل
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var nextId = 1
    private val scanning = AtomicBoolean(false)

    fun start() {
        running = true
        thread(name = "accept") {
            try {
                val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
                server = ss
                serverStatus = "منتظر کلاینت (پورت $PORT)"
                while (running) {
                    val s = ss.accept()
                    if (sessions.size >= MAX_CLIENTS) { s.close(); continue }
                    addClient(s)
                }
            } catch (e: Exception) { if (running) serverStatus = "خطای سرور: ${e.javaClass.simpleName} ${e.message}" }
        }
        thread(name = "ping") {   // ضربان مستقل از صدا
            while (running) {
                for (s in sessions) s.link.send(T_PING, ByteArray(0))
                Thread.sleep(1000)
            }
        }
        thread(name = "autoscan") {
            Thread.sleep(1500)
            var miss = 0
            while (running) {
                if (autoScan) {
                    val lc = lockedCount
                    if (lc == 0) {
                        if (sessions.size < MAX_CLIENTS) scanNow()
                    } else if (sessions.size >= lc) {
                        miss = 0                                   // تعداد کامل است: جستجو نکن
                        known = sessions.map { it.addr }.toSet()
                    } else {
                        val missing = known.filter { ip -> sessions.none { it.addr == ip } }
                        if (miss < 3 && missing.isNotEmpty()) { if (scanNow(missing)) miss++ }  // اول همان IPها
                        else scanNow()                                                           // بعد جستجوی کلی
                    }
                }
                Thread.sleep(5000)
            }
        }
        thread(name = "mic") { micLoop() }
        thread(name = "mix") { mixLoop() }
    }

    // ---------- قفل تعداد کلاینت‌ها ----------
    fun lock(): Boolean {
        if (sessions.isEmpty()) return false
        lockedCount = sessions.size
        known = sessions.map { it.addr }.toSet()
        scanStatus = "قفل شد روی $lockedCount کلاینت"
        return true
    }

    fun unlock() { lockedCount = 0; scanStatus = "قفل باز شد" }

    /** نگه داشتن ۳ ثانیه‌ای: قفل باز و جستجوی کلی از نو */
    fun fullSearch() { lockedCount = 0; known = emptySet(); scanNow() }

    // ---------- جستجوی کلاینت‌ها در رنج IP ----------
    private fun localAddrs(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.matches(Regex("(rmnet|ccmni|tun|ppp|dummy|v4-|clat|tap).*")) }
            .flatMap { ni -> ni.inetAddresses.toList().filter { it is Inet4Address && it.isSiteLocalAddress }.map { it.hostAddress ?: "" } }
            .filter { it.isNotEmpty() }
    } catch (_: Exception) { emptyList() }

    /** only = فقط همین IPها؛ null = کل رنج شبکه. true یعنی جستجو شروع شد */
    fun scanNow(only: List<String>? = null): Boolean {
        if (!scanning.compareAndSet(false, true)) return false
        thread(name = "scan") {
            try {
                val own = localAddrs()
                val prefixes = own.map { it.substringBeforeLast('.') }.distinct()
                val targets: List<String> = (only ?: prefixes.flatMap { p -> (1..254).map { "$p.$it" } })
                    .filter { it !in own }.filter { ip -> sessions.none { it.addr == ip } }
                if (targets.isEmpty()) { scanStatus = "موردی برای جستجو نیست"; return@thread }
                scanStatus = if (only != null) "جستجوی IPهای قبلی…"
                             else "در حال جستجو در ${prefixes.joinToString(", ") { "$it.x" }} …"
                val hits = AtomicInteger()
                val pool = Executors.newFixedThreadPool(minOf(48, targets.size))
                val tasks = targets.map { ip ->
                    Callable<Unit> {
                        try {
                            Socket().use { it.connect(InetSocketAddress(ip, DISC_PORT), 500) }
                            hits.incrementAndGet()
                        } catch (_: Exception) {}
                        Unit
                    }
                }
                pool.invokeAll(tasks); pool.shutdown()
                scanStatus = "جستجو تمام شد: ${hits.get()} کلاینت دعوت شد"
            } finally { scanning.set(false) }
        }
        return true
    }

    private fun addClient(sock: Socket) {
        sock.soTimeout = 15000
        val sess = ClientSession(nextId++, sock.inetAddress.hostAddress ?: "?")
        sess.link = Link(sock, { t, d -> onMsg(sess, t, d) }, { sessions.remove(sess) })
        sessions.add(sess)
        sess.link.start()
    }

    private fun onMsg(s: ClientSession, t: Int, d: ByteArray) {
        when (t) {
            T_HELLO -> { s.name = String(d, Charsets.UTF_8).take(24); pushCtrl(s) }
            T_AUDIO -> if (d.size == FRAME * 2) s.inQ.pushDrop(d.toShorts())
        }
    }

    fun pushCtrl(s: ClientSession) =
        s.link.send(T_CTRL, byteArrayOf(if (micOpen && s.hearMaster) 1 else 0))

    fun setTalk(s: ClientSession, on: Boolean) { s.hearMaster = on; pushCtrl(s) }
    fun changeMic(on: Boolean) { micOpen = on; sessions.forEach { pushCtrl(it) } }

    private fun micLoop() {
        val f = ShortArray(FRAME)
        while (running) {
            if (!io.read(f)) { audioOk = false; Thread.sleep(20); continue }
            audioOk = true
            myLevel = if (micOpen) f.peak() else 0
            if (!micOpen) continue
            val pcm = f.toBytes()
            for (s in sessions) if (s.hearMaster) s.link.send(T_AUDIO, pcm)
        }
    }

    private fun mixLoop() {
        val acc = IntArray(FRAME)   // آنچه مستر می‌شنود (با میوت و ولوم)
        val tot = IntArray(FRAME)   // مجموع خام همه‌ی کلاینت‌ها برای حالت گفتگو
        val out = ShortArray(FRAME)
        val cf = ShortArray(FRAME)
        while (running) {
            java.util.Arrays.fill(acc, 0); java.util.Arrays.fill(tot, 0)
            val ss = sessions.toList()
            val frames = arrayOfNulls<ShortArray>(ss.size)
            var nn = 0
            for ((k, s) in ss.withIndex()) {
                val f = s.inQ.poll()
                frames[k] = f
                if (f == null) { s.level = 0; continue }
                nn++
                s.level = if (s.muted) 0 else f.peak()
                val g = s.volume
                for (i in 0 until FRAME) {
                    tot[i] += f[i].toInt()
                    if (!s.muted) acc[i] += (f[i] * g).toInt()
                }
            }
            if (conference && nn > 0) {
                for ((k, s) in ss.withIndex()) {
                    val own = frames[k]
                    if (nn - (if (own != null) 1 else 0) <= 0) continue   // کسی غیر از خودش صحبت نمی‌کند
                    for (i in 0 until FRAME) {
                        val v = tot[i] - (own?.get(i)?.toInt() ?: 0)
                        cf[i] = v.coerceIn(-32768, 32767).toShort()
                    }
                    s.link.send(T_CONF, cf.toBytes())
                }
            }
            for (i in 0 until FRAME) out[i] = acc[i].coerceIn(-32768, 32767).toShort()
            if (!io.write(out)) Thread.sleep(20)   // write() بلوکه می‌شود و ساعت ۲۰ms را می‌سازد
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        sessions.forEach { it.link.close() }
    }
}

class ClientEngine(private val io: AudioIO, initialHost: String, private val name: String) {
    @Volatile var host = initialHost   // خالی = منتظر پیدا شدن توسط مستر
    @Volatile var micMuted = false
    @Volatile var masterMuted = false  // میوت صدای مستر (فقط برای خودم)
    @Volatile var masterVolume = 1f    // 0..2
    @Volatile var masterTalking = true
    @Volatile var status = "…"
    @Volatile var audioOk = true
    @Volatile var level = 0
    @Volatile private var running = false
    @Volatile private var link: Link? = null
    private var disc: ServerSocket? = null
    private val inQ = ArrayBlockingQueue<ShortArray>(8)     // صدای مستر
    private val confQ = ArrayBlockingQueue<ShortArray>(8)   // صدای بقیه‌ی کلاینت‌ها

    fun start() {
        running = true
        thread(name = "connect") {
            while (running) {
                val h = host
                if (h.isBlank()) { status = "در انتظار پیدا شدن توسط مستر…"; Thread.sleep(500); continue }
                val latch = CountDownLatch(1)
                var err = ""
                try {
                    status = "در حال اتصال به $h …"
                    val s = Socket()
                    s.connect(InetSocketAddress(h, PORT), 5000)
                    s.soTimeout = 15000
                    val l = Link(s, { t, d -> onMsg(t, d) }, { latch.countDown() })
                    inQ.clear(); confQ.clear(); link = l; l.start()
                    l.send(T_HELLO, name.toByteArray(Charsets.UTF_8))
                    status = "متصل"
                    latch.await()
                    err = "ارتباط از طرف مستر قطع شد"
                } catch (e: Exception) { err = "${e.javaClass.simpleName}: ${e.message}" }
                link = null
                if (running) { status = "قطع شد ($err)؛ تلاش مجدد…"; Thread.sleep(1000) }
            }
        }
        thread(name = "discovery") {   // مستر با جستجو به این پورت وصل می‌شود تا ما را پیدا کند
            try {
                val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(DISC_PORT)) }
                disc = ss
                while (running) {
                    val s = ss.accept()
                    val a = s.inetAddress.hostAddress ?: ""
                    s.close()
                    if (link == null && a.isNotEmpty() && a != host) host = a
                }
            } catch (_: Exception) {}
        }
        thread(name = "ping") {   // ضربان مستقل از صدا
            while (running) { link?.send(T_PING, ByteArray(0)); Thread.sleep(1000) }
        }
        thread(name = "capture") {
            val f = ShortArray(FRAME)
            while (running) {
                if (!io.read(f)) { audioOk = false; Thread.sleep(20); continue }
                audioOk = true
                if (micMuted) { level = 0 }
                else { level = f.peak(); link?.send(T_AUDIO, f.toBytes()) }
            }
        }
        thread(name = "play") {   // هر ۲۰ms یک فریم از مستر + یک فریم از بقیه‌ی کلاینت‌ها میکس می‌شود
            val out = ShortArray(FRAME)
            while (running) {
                val a = inQ.poll()
                val b = confQ.poll()
                val g = if (masterMuted) 0f else masterVolume
                for (i in 0 until FRAME) {
                    var v = 0
                    if (a != null) v += (a[i] * g).toInt()
                    if (b != null) v += b[i].toInt()
                    out[i] = v.coerceIn(-32768, 32767).toShort()
                }
                if (!io.write(out)) Thread.sleep(20)
            }
        }
    }

    private fun onMsg(t: Int, d: ByteArray) {
        when (t) {
            T_AUDIO -> if (d.size == FRAME * 2) inQ.pushDrop(d.toShorts())
            T_CONF -> if (d.size == FRAME * 2) confQ.pushDrop(d.toShorts())
            T_CTRL -> if (d.isNotEmpty()) masterTalking = d[0].toInt() == 1
        }
    }

    fun reconnect(newHost: String) { host = newHost.trim(); link?.close() }

    fun stop() {
        running = false
        try { disc?.close() } catch (_: Exception) {}
        link?.close()
    }
}
