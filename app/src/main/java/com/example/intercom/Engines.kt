package com.example.intercom

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ClientSession(val id: Int, val addr: String) {
    lateinit var link: Link
    @Volatile var authed = false
    @Volatile var name = "..."
    @Volatile var muted = false        // صدای این کلاینت در مستر شنیده نشود
    @Volatile var volume = 1f          // 0..2 : بلندی صدای کلاینت در مستر
    @Volatile var hearMaster = true    // مستر با این کلاینت صحبت کند
    @Volatile var group = 1            // گروه 1..4 (A..D)
    @Volatile var micForced = false    // مستر میکروفون این کلاینت را بسته
    @Volatile var rtt = -1             // تأخیر رفت‌وبرگشت (ms)
    @Volatile var level = 0
    @Volatile var lastBuzz = 0L
    @Volatile var clientMicOff = false       // کلاینت میکروفونش را خاموش کرده
    @Volatile var clientMutedMaster = false  // کلاینت صدای مستر را قطع کرده
    @Volatile var lastMsg = ""
    @Volatile var lastMsgAt = 0L
    val inQ = ArrayBlockingQueue<ShortArray>(8)
}

class Offer(val uri: Uri, val name: String, val size: Long)

class MasterEngine(private val io: AudioIO, val pin: String) {
    val sessions = CopyOnWriteArrayList<ClientSession>()
    val recorder = Recorder()
    @Volatile var micOpen = true       // میکروفون مستر برای همه
    @Volatile var pttId = 0            // کلاینتی که کلید PTT او فشرده است (0 = هیچ‌کس)
    @Volatile var fileStatus = ""
    @Volatile var talkGroup = 0        // 0 = همه‌ی گروه‌ها
    @Volatile var conference = false   // کلاینت‌ها همدیگر را بشنوند
    @Volatile var lockedCount = 0      // 0 = قفل نیست
    @Volatile var soloId = 0           // 0 = حالت «فقط یک کلاینت» خاموش است
    @Volatile var myLevel = 0
    @Volatile var serverStatus = "در حال راه‌اندازی…"
    @Volatile var audioOk = true
    @Volatile var autoScan = true
    @Volatile var scanStatus = ""
    var onBuzz: (() -> Unit)? = null
    var onAlert: (() -> Unit)? = null
    @Volatile private var known: Set<String> = emptySet()
    @Volatile private var running = false
    private var saved: Map<Int, Pair<Boolean, Boolean>> = emptyMap()   // وضعیت قبل از solo
    private var server: ServerSocket? = null
    private var fileServer: ServerSocket? = null
    private val offers = ConcurrentHashMap<Long, Offer>()
    private var nextOffer = 1L
    private var pttWasSolo = false
    private var nextId = 1
    private val scanning = AtomicBoolean(false)
    private val micRecQ = ArrayBlockingQueue<ShortArray>(8)

    val micLive: Boolean get() = micOpen || pttId != 0
    private fun inTalk(s: ClientSession) = s.hearMaster && (pttId != 0 || talkGroup == 0 || s.group == talkGroup)

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
        thread(name = "ping") {   // ضربان + اندازه‌گیری تأخیر
            while (running) {
                val ts = ByteBuffer.allocate(8).putLong(System.currentTimeMillis()).array()
                for (s in sessions) s.link.send(T_PING, ts)
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
                        miss = 0
                        known = sessions.map { it.addr }.toSet()
                    } else {
                        val missing = known.filter { ip -> sessions.none { it.addr == ip } }
                        if (miss < 3 && missing.isNotEmpty()) { if (scanNow(missing)) miss++ }
                        else scanNow()
                    }
                }
                Thread.sleep(5000)
            }
        }
        thread(name = "files") {
            try {
                val fs = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(FILE_PORT)) }
                fileServer = fs
                while (running) { val c = fs.accept(); thread(name = "file-conn") { fileConn(c) } }
            } catch (_: Exception) {}
        }
        thread(name = "mic") { micLoop() }
        thread(name = "mix") { mixLoop() }
    }

    // ---------- کنترل‌ها ----------
    fun pushCtrl(s: ClientSession) = s.link.send(T_CTRL, byteArrayOf(
        if (micLive && inTalk(s)) 1 else 0, s.group.toByte(), if (recorder.active) 1 else 0))

    private fun pushAll() { sessions.forEach { pushCtrl(it) } }

    fun setTalk(s: ClientSession, on: Boolean) { s.hearMaster = on; pushCtrl(s) }
    fun changeMic(on: Boolean) { micOpen = on; pushAll() }
    /** PTT یک کلاینت: تا وقتی فشرده است بقیه قطع می‌شوند و مستر (حتی با میکروفون بسته) فقط با او صحبت می‌کند */
    @Synchronized fun pttPress(s: ClientSession, down: Boolean) {
        if (down) {
            pttWasSolo = soloId == s.id
            if (!pttWasSolo) solo(s)
            pttId = s.id
            pushAll()
        } else if (pttId == s.id) {
            pttId = 0
            if (!pttWasSolo && soloId == s.id) restore()
            pushAll()
        }
    }
    fun changeTalkGroup(g: Int) { talkGroup = g; pushAll() }
    fun setGroup(s: ClientSession, g: Int) { s.group = g; pushCtrl(s) }
    fun forceMic(s: ClientSession, off: Boolean) { s.micForced = off; s.link.send(T_CMD, byteArrayOf(if (off) 1 else 0)) }
    fun buzz(s: ClientSession) = s.link.send(T_BUZZ, ByteArray(0))

    fun sendMsg(text: String) {
        val b = text.toByteArray(Charsets.UTF_8)
        val tg = if (soloId != 0) sessions.filter { it.id == soloId } else sessions.toList()
        tg.forEach { it.link.send(T_MSG, b) }
    }

    fun startRecording(ctx: Context): Boolean { val ok = recorder.start(ctx); pushAll(); return ok }
    fun stopRecording(ctx: Context) { recorder.stop(ctx); pushAll() }

    // ---------- فقط با یک کلاینت صحبت کن ----------
    @Synchronized fun solo(s: ClientSession) {
        if (soloId == s.id) { restore(); return }
        if (soloId == 0) saved = sessions.associate { it.id to (it.muted to it.hearMaster) }
        soloId = s.id
        for (x in sessions) { val me = x === s; x.muted = !me; setTalk(x, me) }
    }

    @Synchronized private fun restore() {
        for (x in sessions) {
            val p = saved[x.id]
            x.muted = p?.first ?: false
            setTalk(x, p?.second ?: true)
        }
        soloId = 0; saved = emptyMap()
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
    fun fullSearch() { lockedCount = 0; known = emptySet(); scanNow() }

    // ---------- جستجوی کلاینت‌ها در رنج IP ----------
    private fun localAddrs(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.matches(Regex("(rmnet|ccmni|tun|ppp|dummy|v4-|clat|tap).*")) }
            .flatMap { ni -> ni.inetAddresses.toList().filter { it is Inet4Address && it.isSiteLocalAddress }.map { it.hostAddress ?: "" } }
            .filter { it.isNotEmpty() }
    } catch (_: Exception) { emptyList() }

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

    // ---------- اتصال و احراز هویت ----------
    private fun addClient(sock: Socket) {
        sock.soTimeout = 15000
        val sess = ClientSession(nextId++, sock.inetAddress.hostAddress ?: "?")
        sess.link = Link(sock, { t, d -> onMsg(sess, t, d) }, {
            sessions.remove(sess)
            if (pttId == sess.id) pttId = 0
            if (sess.authed && soloId == sess.id) restore()
        })
        sess.link.start()     // تا قبل از HELLO درست، در فهرست نیست
    }

    private fun hello(s: ClientSession, d: ByteArray) {
        val txt = String(d, Charsets.UTF_8)
        val i = txt.indexOf('\u0001')
        val nm = if (i >= 0) txt.substring(0, i) else txt
        val pw = if (i >= 0) txt.substring(i + 1) else ""
        if (pin.isNotEmpty() && pw != pin) {
            s.link.send(T_DENY, ByteArray(0))
            thread { Thread.sleep(400); s.link.close() }
            return
        }
        s.name = nm.take(24)
        if (soloId != 0) { s.muted = true; s.hearMaster = false }
        sessions.filter { it.addr == s.addr && it !== s }.forEach { it.link.close() }   // اتصال قدیمی همان گوشی
        s.authed = true
        sessions.add(s)
        pushCtrl(s)
    }

    private fun onMsg(s: ClientSession, t: Int, d: ByteArray) {
        if (t == T_HELLO) { hello(s, d); return }
        if (!s.authed) return
        when (t) {
            T_AUDIO -> decodeFrame(d)?.let { s.inQ.pushDrop(it) }
            T_BUZZ -> { s.lastBuzz = System.currentTimeMillis(); onBuzz?.invoke() }
            T_STATE -> if (d.isNotEmpty()) {
                val f = d[0].toInt()
                s.clientMicOff = (f and 1) != 0
                s.clientMutedMaster = (f and 2) != 0
            }
            T_PONG -> if (d.size == 8) s.rtt = (System.currentTimeMillis() - ByteBuffer.wrap(d).long).toInt().coerceAtLeast(0)
            T_MSG -> { s.lastMsg = String(d, Charsets.UTF_8).take(60); s.lastMsgAt = System.currentTimeMillis(); onAlert?.invoke() }
        }
    }

    // ---------- انتقال فایل (کانال جدا روی FILE_PORT؛ کلاینت همیشه اتصال را برقرار می‌کند) ----------
    /** فایل را برای یک کلاینت (یا همه اگر target = null) پیشنهاد می‌دهد؛ کلاینت خودش دریافت می‌کند */
    fun sendFile(uri: Uri, name: String, size: Long, target: ClientSession?) {
        val id = nextOffer++
        offers[id] = Offer(uri, name, size)
        val nb = name.toByteArray(Charsets.UTF_8)
        val payload = ByteBuffer.allocate(16 + nb.size).putLong(id).putLong(size).put(nb).array()
        val tg = if (target != null) listOf(target) else sessions.toList()
        tg.forEach { it.link.send(T_FILE, payload) }
        fileStatus = "📤 «$name» برای ${tg.size} نفر ارسال شد؛ منتظر دریافت…"
    }

    private fun fileConn(sock: Socket) {
        val ctx = Intercom.appCtx ?: return
        try {
            sock.soTimeout = 30000
            val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), 65536))
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 65536))
            val mode = inp.readUTF()
            val pw = inp.readUTF()
            val addr = sock.inetAddress.hostAddress ?: ""
            val sess = sessions.firstOrNull { it.addr == addr }
            if (sess == null || (pin.isNotEmpty() && pw != pin)) { out.writeUTF("DENY"); out.flush(); return }
            if (mode == "UP") {
                val fname = inp.readUTF()
                val size = inp.readLong()
                out.writeUTF("OK"); out.flush()
                val sink = FileIO.openSink(ctx, fname)
                try {
                    val n = FileIO.copy(inp, sink.out, size) { d -> fileStatus = "📥 دریافت «$fname» از ${sess.name}: ${FileIO.pct(d, size)}٪" }
                    if (n < size) { sink.abort(); fileStatus = "❌ دریافت «$fname» ناقص ماند"; return }
                    sink.finish()
                    fileStatus = "✅ فایل از ${sess.name} ذخیره شد: ${sink.path}"
                    onAlert?.invoke()
                    out.writeUTF("DONE"); out.flush()
                } catch (e: Exception) { sink.abort(); throw e }
            } else if (mode == "DL") {
                val offer = offers[inp.readLong()]
                val ins = if (offer != null) ctx.contentResolver.openInputStream(offer.uri) else null
                if (offer == null || ins == null) { out.writeUTF("NONE"); out.flush(); return }
                out.writeUTF("OK"); out.writeUTF(offer.name); out.writeLong(offer.size); out.flush()
                ins.use { i ->
                    val n = FileIO.copy(i, out, offer.size) { d -> fileStatus = "📤 ارسال «${offer.name}» به ${sess.name}: ${FileIO.pct(d, offer.size)}٪" }
                    out.flush()
                    fileStatus = if (n >= offer.size) "✅ «${offer.name}» برای ${sess.name} ارسال شد" else "❌ ارسال «${offer.name}» ناقص ماند"
                }
            }
        } catch (e: Exception) { fileStatus = "❌ خطای انتقال فایل: ${e.message}" }
        finally { try { sock.close() } catch (_: Exception) {} }
    }

    // ---------- صدا ----------
    private fun micLoop() {
        val f = ShortArray(FRAME)
        while (running) {
            if (!io.read(f)) { audioOk = false; Thread.sleep(20); continue }
            audioOk = true
            val live = micLive
            myLevel = if (live) f.peak() else 0
            if (!live) continue
            if (recorder.active) micRecQ.pushDrop(f.copyOf())
            val pcm = encodeFrame(f)
            for (s in sessions) if (inTalk(s)) s.link.send(T_AUDIO, pcm)
        }
    }

    private fun mixLoop() {
        val acc = IntArray(FRAME)                       // آنچه مستر می‌شنود (با میوت و ولوم)
        val totG = Array(5) { IntArray(FRAME) }         // مجموع خام هر گروه برای حالت گفتگو
        val nnG = IntArray(5)
        val out = ShortArray(FRAME)
        val cf = ShortArray(FRAME)
        val rec = ShortArray(FRAME)
        while (running) {
            java.util.Arrays.fill(acc, 0); java.util.Arrays.fill(nnG, 0)
            for (a in totG) java.util.Arrays.fill(a, 0)
            val ss = sessions.toList()
            val frames = arrayOfNulls<ShortArray>(ss.size)
            var nn = 0
            for ((k, s) in ss.withIndex()) {
                val f = s.inQ.poll()
                frames[k] = f
                if (f == null) { s.level = 0; continue }
                nn++
                val g = s.group.coerceIn(1, 4)
                nnG[g]++
                s.level = if (s.muted) 0 else f.peak()
                val vol = s.volume
                val tg = totG[g]
                for (i in 0 until FRAME) {
                    tg[i] += f[i].toInt()
                    if (!s.muted) acc[i] += (f[i] * vol).toInt()
                }
            }
            if (conference && soloId == 0 && nn > 0) {
                for ((k, s) in ss.withIndex()) {
                    val g = s.group.coerceIn(1, 4)
                    val own = frames[k]
                    if (nnG[g] - (if (own != null) 1 else 0) <= 0) continue   // کسی از هم‌گروهی‌ها صحبت نمی‌کند
                    val tg = totG[g]
                    for (i in 0 until FRAME) {
                        val v = tg[i] - (own?.get(i)?.toInt() ?: 0)
                        cf[i] = v.coerceIn(-32768, 32767).toShort()
                    }
                    s.link.send(T_CONF, encodeFrame(cf))
                }
            }
            if (recorder.active) {
                val m = micRecQ.poll()
                for (i in 0 until FRAME) rec[i] = (acc[i] + (m?.get(i)?.toInt() ?: 0)).coerceIn(-32768, 32767).toShort()
                recorder.write(rec)
            }
            for (i in 0 until FRAME) out[i] = acc[i].coerceIn(-32768, 32767).toShort()
            if (!io.write(out)) Thread.sleep(20)   // write() بلوکه می‌شود و ساعت ۲۰ms را می‌سازد
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        try { fileServer?.close() } catch (_: Exception) {}
        offers.clear()
        sessions.forEach { it.link.close() }
    }
}

class ClientEngine(private val io: AudioIO, initialHost: String, private val name: String, initialPin: String) {
    @Volatile var host = initialHost   // خالی = منتظر پیدا شدن توسط مستر
    @Volatile var pin = initialPin
    @Volatile var micMuted = false
    @Volatile var forcedMute = false   // مستر میکروفون ما را بسته
    @Volatile var masterMuted = false  // میوت صدای مستر (فقط برای خودم)
    @Volatile var masterVolume = 1f    // 0..2
    @Volatile var masterTalking = true
    @Volatile var group = 1
    @Volatile var recording = false
    @Volatile var fileStatus = ""
    @Volatile var denied = false
    @Volatile var status = "…"
    @Volatile var audioOk = true
    @Volatile var level = 0
    @Volatile var lastBuzz = 0L
    @Volatile var lastMsg = ""
    @Volatile var lastMsgAt = 0L
    var onBuzz: (() -> Unit)? = null
    var onAlert: (() -> Unit)? = null
    @Volatile private var running = false
    @Volatile private var link: Link? = null
    private var disc: ServerSocket? = null
    private val inQ = ArrayBlockingQueue<ShortArray>(8)     // صدای مستر
    private val confQ = ArrayBlockingQueue<ShortArray>(8)   // صدای بقیه‌ی کلاینت‌ها

    val micLive: Boolean get() = !micMuted && !forcedMute

    fun start() {
        running = true
        thread(name = "connect") {
            while (running) {
                if (denied) { status = "رمز اشتباه است — در تنظیمات رمز را درست کنید"; Thread.sleep(500); continue }
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
                    l.send(T_HELLO, (name + "\u0001" + pin).toByteArray(Charsets.UTF_8))
                    status = "متصل"
                    latch.await()
                    err = "ارتباط از طرف مستر قطع شد"
                } catch (e: Exception) { err = "${e.javaClass.simpleName}: ${e.message}" }
                link = null
                if (denied) continue
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
                    if (link == null && !denied && a.isNotEmpty() && a != host) host = a
                }
            } catch (_: Exception) {}
        }
        thread(name = "state") {   // ضربان + وضعیت میوت‌ها برای مستر (هر ۰.۵ ثانیه)
            while (running) {
                val flags = (if (micMuted || forcedMute) 1 else 0) or (if (masterMuted || masterVolume < 0.05f) 2 else 0)
                link?.send(T_STATE, byteArrayOf(flags.toByte()))
                Thread.sleep(500)
            }
        }
        thread(name = "capture") {
            val f = ShortArray(FRAME)
            while (running) {
                if (!io.read(f)) { audioOk = false; Thread.sleep(20); continue }
                audioOk = true
                if (!micLive) { level = 0 }
                else { level = f.peak(); link?.send(T_AUDIO, encodeFrame(f)) }
            }
        }
        thread(name = "play") {   // هر ۲۰ms یک فریم از مستر + یک فریم از هم‌گروهی‌ها میکس می‌شود
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
            T_AUDIO -> decodeFrame(d)?.let { inQ.pushDrop(it) }
            T_CONF -> decodeFrame(d)?.let { confQ.pushDrop(it) }
            T_CTRL -> if (d.isNotEmpty()) {
                masterTalking = d[0].toInt() == 1
                if (d.size > 1) group = d[1].toInt().coerceIn(1, 4)
                if (d.size > 2) recording = d[2].toInt() == 1
            }
            T_PING -> if (d.size == 8) link?.send(T_PONG, d)
            T_BUZZ -> { lastBuzz = System.currentTimeMillis(); onBuzz?.invoke() }
            T_DENY -> { denied = true; status = "رمز اشتباه است" }
            T_CMD -> if (d.isNotEmpty()) forcedMute = d[0].toInt() == 1
            T_MSG -> { lastMsg = String(d, Charsets.UTF_8).take(60); lastMsgAt = System.currentTimeMillis(); onAlert?.invoke() }
            T_FILE -> startDownload(d)
        }
    }

    /** ارسال فایل از حافظه‌ی این گوشی به مستر */
    fun uploadFile(uri: Uri, name: String, size: Long) {
        val ctx = Intercom.appCtx ?: return
        val h = host
        if (h.isBlank() || link == null) { fileStatus = "❌ به مستر وصل نیستید"; return }
        thread(name = "upload") {
            try {
                fileStatus = "📤 در حال اتصال برای ارسال «$name»…"
                Socket().use { s ->
                    s.connect(InetSocketAddress(h, FILE_PORT), 5000)
                    s.soTimeout = 30000
                    val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 65536))
                    val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
                    out.writeUTF("UP"); out.writeUTF(pin); out.writeUTF(name); out.writeLong(size); out.flush()
                    val ans = inp.readUTF()
                    if (ans != "OK") { fileStatus = "❌ مستر فایل را نپذیرفت ($ans)"; return@use }
                    val ins = ctx.contentResolver.openInputStream(uri) ?: throw IOException("خواندن فایل ممکن نیست")
                    ins.use { i -> FileIO.copy(i, out, size) { d -> fileStatus = "📤 ارسال «$name»: ${FileIO.pct(d, size)}٪" } }
                    out.flush()
                    fileStatus = if (inp.readUTF() == "DONE") "✅ «$name» برای مستر ارسال شد" else "❌ ارسال «$name» ناقص ماند"
                }
            } catch (e: Exception) { fileStatus = "❌ خطای ارسال فایل: ${e.message}" }
        }
    }

    /** مستر فایلی پیشنهاد داده: خودکار دریافت و در Download/Intercom/Received ذخیره می‌شود */
    private fun startDownload(d: ByteArray) {
        if (d.size < 16) return
        val bb = ByteBuffer.wrap(d)
        val id = bb.long
        bb.long   // حجم؛ مقدار دقیق از مستر دوباره می‌آید
        val offerName = String(d, 16, d.size - 16, Charsets.UTF_8)
        val ctx = Intercom.appCtx ?: return
        val h = host
        thread(name = "download") {
            try {
                fileStatus = "📥 در حال دریافت «$offerName»…"
                Socket().use { s ->
                    s.connect(InetSocketAddress(h, FILE_PORT), 5000)
                    s.soTimeout = 30000
                    val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 65536))
                    val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 65536))
                    out.writeUTF("DL"); out.writeUTF(pin); out.writeLong(id); out.flush()
                    val ans = inp.readUTF()
                    if (ans != "OK") { fileStatus = "❌ دریافت «$offerName» ممکن نشد ($ans)"; return@use }
                    val name = inp.readUTF()
                    val size = inp.readLong()
                    val sink = FileIO.openSink(ctx, name)
                    try {
                        val n = FileIO.copy(inp, sink.out, size) { x -> fileStatus = "📥 دریافت «$name»: ${FileIO.pct(x, size)}٪" }
                        if (n < size) { sink.abort(); fileStatus = "❌ دریافت «$name» ناقص ماند"; return@use }
                        sink.finish()
                        fileStatus = "✅ فایل ذخیره شد: ${sink.path}"
                        onAlert?.invoke()
                    } catch (e: Exception) { sink.abort(); throw e }
                }
            } catch (e: Exception) { fileStatus = "❌ خطای دریافت فایل: ${e.message}" }
        }
    }

    fun buzzMaster() { link?.send(T_BUZZ, ByteArray(0)) }
    fun sendMsg(text: String) { link?.send(T_MSG, text.toByteArray(Charsets.UTF_8)) }

    fun reconnect(newHost: String, newPin: String) {
        host = newHost.trim(); pin = newPin.trim(); denied = false
        link?.close()
    }

    fun stop() {
        running = false
        try { disc?.close() } catch (_: Exception) {}
        link?.close()
    }
}
