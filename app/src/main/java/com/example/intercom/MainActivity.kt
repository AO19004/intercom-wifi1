package com.example.intercom

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var box: LinearLayout
    private var afterPerm: (() -> Unit)? = null

    private class Row(val s: ClientSession, val title: TextView, val bar: LevelBar, val sp: IconToggle, val mic: IconToggle)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        window.statusBarColor = C_BLUE
        val sv = ScrollView(this).apply { setBackgroundColor(C_BG) }
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(24)) }
        sv.addView(box); setContentView(sv)
        show()
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }

    // ---------- ابزارهای ساخت UI ----------
    private fun lp(w: Int = -1, hh: Int = -2, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, hh).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private fun label(t: String, size: Float = 14f, color: Int = C_TEXT, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun card(stroke: Int = C_GRAY, strokeDp: Int = 2) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(Color.WHITE, stroke, 16, strokeDp = strokeDp)
        setPadding(dp(12), dp(10), dp(12), dp(12)); elevation = dp(2).toFloat()
    }

    private fun add(v: View) = box.addView(v, lp(b = 12))

    /** آیکون + عنوان کوچک زیرش */
    private fun col(v: View, cap: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(v); addView(label(cap, 11f, C_GRAY).apply { gravity = Gravity.CENTER })
    }

    private fun equalCols(vararg c: View) = row().apply {
        c.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    }

    private fun header(sub: String) {
        val r = row().apply { background = shape(C_BLUE, radiusDp = 16); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        r.addView(ImageView(this).apply { setImageResource(R.drawable.ic_launcher) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        r.addView(label("اینترکام", 20f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(10), 0, dp(10), 0) })
        r.addView(label(sub, 14f, Color.WHITE))
        add(r)
    }

    private fun collapsible(title: String, content: View, open: Boolean = false): LinearLayout {
        val c = card()
        val head = label("$title   ${if (open) "▲" else "▼"}", 15f, C_BLUE, true).apply { setPadding(0, dp(4), 0, dp(4)) }
        content.visibility = if (open) View.VISIBLE else View.GONE
        head.setOnClickListener {
            val o = content.visibility != View.VISIBLE
            content.visibility = if (o) View.VISIBLE else View.GONE
            head.text = "$title   ${if (o) "▲" else "▼"}"
        }
        c.addView(head); c.addView(content)
        return c
    }

    private fun routeIcon(): Int {
        val n = Intercom.router?.routeName ?: ""
        return when { n.startsWith("هدست") -> R.drawable.ic_headset; n.startsWith("گوشی") -> R.drawable.ic_phone; else -> R.drawable.ic_speaker }
    }

    private fun routeBtn() = IconToggle(this, R.drawable.ic_speaker, R.drawable.ic_speaker, 64, C_BLUE, C_BLUE, toggle = false) { toggleSpeaker() }
    private fun endBtn() = IconToggle(this, R.drawable.ic_power, R.drawable.ic_power, 64, C_RED, C_RED, toggle = false) { stopSvc() }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    /** خط ریز سازنده؛ فقط پایین بخش تنظیمات */
    private fun credit() = label("سازنده: aliomidi9@gmail.com", 10f, C_GRAY).apply {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(0, dp(10), 0, 0)
        setOnClickListener {
            try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:aliomidi9@gmail.com")), "ایمیل")) }
            catch (_: Exception) { toast("aliomidi9@gmail.com") }
        }
    }

    private fun show() {
        h.removeCallbacksAndMessages(null); box.removeAllViews()
        when (Intercom.role) { 0 -> roleScreen(); 1 -> masterScreen(); else -> clientScreen() }
    }

    // ---------- دسترسی‌ها و سرویس ----------
    private fun needPerms(then: () -> Unit) {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) p += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        val missing = p.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) then() else { afterPerm = then; requestPermissions(missing.toTypedArray(), 1) }
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) afterPerm?.invoke()
        else Toast.makeText(this, "دسترسی میکروفون لازم است", Toast.LENGTH_LONG).show()
        afterPerm = null
    }

    private fun startSvc(role: Int, host: String = "", name: String = "") {
        Intercom.role = role
        val i = Intent(this, IntercomService::class.java).putExtra("role", role).putExtra("host", host).putExtra("name", name)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        show()
    }

    private fun stopSvc() = startService(Intent(this, IntercomService::class.java).setAction("stop"))

    private fun toggleSpeaker() {
        val r = Intercom.router ?: return
        r.preferSpeaker = !r.preferSpeaker
        if (r.apply()) Intercom.io?.restart()
    }

    private fun localIps(): String = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filter { it is Inet4Address }.map { "${it.hostAddress}   (${ni.name})" } }
            .joinToString("\n")
    } catch (_: Exception) { "?" }

    // ---------- انتخاب نقش ----------
    private fun roleCard(icon: Int, color: Int, title: String, sub: String, go: () -> Unit): LinearLayout {
        val c = row().apply {
            background = shape(Color.WHITE, color, 16, strokeDp = 3)
            setPadding(dp(14), dp(14), dp(14), dp(14)); elevation = dp(2).toFloat()
            setOnClickListener { go() }
        }
        c.addView(IconToggle(this, icon, icon, 64, color, color, toggle = false, interactive = false))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        t.addView(label(title, 20f, color, true)); t.addView(label(sub, 12f, C_GRAY))
        c.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        return c
    }

    private fun roleScreen() {
        val prefs = getSharedPreferences("p", 0)
        header("")
        add(roleCard(R.drawable.ic_mic, C_BLUE, "مستر", "کلاینت‌ها خودکار پیدا می‌شوند") { needPerms { startSvc(1) } })

        val ip = EditText(this).apply { hint = "IP مستر (خالی = پیدا شدن خودکار)"; setText(prefs.getString("ip", "")); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val nm = EditText(this).apply { hint = "نام شما"; setText(prefs.getString("name", Build.MODEL)) }
        add(roleCard(R.drawable.ic_headset, C_GREEN, "کلاینت", "اتصال به مستر") {
            prefs.edit().putString("ip", ip.text.toString().trim()).putString("name", nm.text.toString()).apply()
            needPerms { startSvc(2, ip.text.toString().trim(), nm.text.toString().ifBlank { "کلاینت" }) }
        })
        val set = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(ip); addView(nm); addView(credit()) }
        add(collapsible("⚙ تنظیمات IP کلاینت", set, open = prefs.getString("ip", "").isNullOrEmpty().not()))
    }

    // ---------- مستر ----------
    private fun lockClick() {
        val m = Intercom.master ?: return
        if (m.lockedCount > 0) { m.unlock(); toast("قفل باز شد؛ جستجوی خودکار فعال است") }
        else if (m.lock()) toast("تعداد کلاینت‌ها روی ${m.lockedCount} قفل شد")
        else toast("ابتدا باید کلاینت‌ها وصل شوند")
    }

    private fun masterScreen() {
        header("مستر")
        val myMic = IconToggle(this, R.drawable.ic_mic, R.drawable.ic_mic_off, 64) { Intercom.master?.changeMic(it) }
        myMic.on = Intercom.master?.micOpen ?: true
        val allSp = IconToggle(this, R.drawable.ic_speaker, R.drawable.ic_speaker_off, 64) { on -> Intercom.master?.sessions?.forEach { it.muted = !on } }
        val conf = IconToggle(this, R.drawable.ic_group, R.drawable.ic_group, 64, C_GREEN, C_GRAY) { Intercom.master?.conference = it }
        conf.on = Intercom.master?.conference ?: false
        val lock = IconToggle(this, R.drawable.ic_lock, R.drawable.ic_lock_open, 64, C_GREEN, C_AMBER, toggle = false) { lockClick() }
        lock.onHold = { Intercom.master?.fullSearch(); toast("جستجوی کلی از نو شروع شد") }
        lock.on = (Intercom.master?.lockedCount ?: 0) > 0
        val route = routeBtn(); val end = endBtn()
        val myBar = LevelBar(this)
        val status = label("", 12f, C_GRAY)

        val ctl = card(C_BLUE, 3)
        ctl.addView(equalCols(col(myMic, "میکروفون من"), col(allSp, "همه کلاینت‌ها"), col(conf, "گفتگوی کلاینت‌ها")))
        ctl.addView(equalCols(col(lock, "قفل تعداد (۳ث = جستجو)"), col(route, "خروجی"), col(end, "پایان")), lp(t = 12))
        ctl.addView(myBar, lp(hh = 14, t = 10))
        ctl.addView(status, lp(t = 8))
        add(ctl)

        // تنظیمات IP (کشویی)
        val ipTxt = label(localIps(), 13f).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val scanTxt = label("", 12f, C_GRAY)
        val auto = IconToggle(this, R.drawable.ic_refresh, R.drawable.ic_refresh, 48, C_GREEN, C_GRAY) { Intercom.master?.autoScan = it }
        auto.on = Intercom.master?.autoScan ?: true
        val now = IconToggle(this, R.drawable.ic_search, R.drawable.ic_search, 48, C_BLUE, C_BLUE, toggle = false) { Intercom.master?.scanNow() }
        val ipBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ipBox.addView(label("آدرس‌های این گوشی (پورت $PORT):", 12f, C_GRAY))
        ipBox.addView(ipTxt, lp(b = 8))
        ipBox.addView(equalCols(col(auto, "جستجوی خودکار"), col(now, "جستجوی فوری")))
        ipBox.addView(scanTxt, lp(t = 8))
        ipBox.addView(credit())
        add(collapsible("⚙ تنظیمات IP و جستجو", ipBox))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list, lp())

        var shown = listOf(-1)
        val rows = mutableMapOf<Int, Row>()
        val tick = object : Runnable {
            override fun run() {
                if (Intercom.role == 0) { show(); return }
                val m = Intercom.master
                if (m != null) {
                    status.text = "${m.serverStatus}  •  ${m.sessions.size} کلاینت" +
                        (if (m.lockedCount > 0) "  •  🔒 ${m.lockedCount}" else "") +
                        (if (m.conference) "  •  گفتگو فعال" else "") +
                        (if (!m.audioOk) "\n⚠ میکروفون/اسپیکر در دسترس نیست" else "")
                    scanTxt.text = m.scanStatus
                    route.setIcon(routeIcon())
                    myBar.level = lvl(m.myLevel)
                    allSp.on = m.sessions.none { it.muted }
                    lock.on = m.lockedCount > 0
                    conf.on = m.conference
                    val ids = m.sessions.map { it.id }
                    if (ids != shown) {
                        shown = ids; list.removeAllViews(); rows.clear()
                        if (ids.isEmpty()) list.addView(label("هنوز کلاینتی وصل نشده…", 14f, C_GRAY).apply { gravity = Gravity.CENTER }, lp(t = 8, b = 8))
                        m.sessions.forEach { s -> val (v, r) = makeRow(m, s); list.addView(v, lp(b = 12)); rows[s.id] = r }
                    }
                    rows.values.forEach { r ->
                        r.title.text = "${r.s.name}   •   ${r.s.addr}"
                        r.bar.level = lvl(r.s.level)
                        r.sp.on = !r.s.muted
                        r.mic.on = r.s.hearMaster
                    }
                }
                h.postDelayed(this, 120)
            }
        }
        h.post(tick)
    }

    private fun makeRow(m: MasterEngine, s: ClientSession): Pair<View, Row> {
        val colors = longArrayOf(0xFF1565C0, 0xFF2E7D32, 0xFFEF6C00, 0xFF6A1B9A, 0xFF00838F, 0xFFAD1457, 0xFF4E342E, 0xFF37474F).map { it.toInt() }
        val cc = colors[(s.id - 1) % colors.size]
        val c = card(cc, 3)
        val title = label(s.name, 16f, cc, true)
        val sp = IconToggle(this, R.drawable.ic_speaker, R.drawable.ic_speaker_off, 60) { s.muted = !it }
        sp.on = !s.muted
        val mic = IconToggle(this, R.drawable.ic_mic, R.drawable.ic_mic_off, 60) { m.setTalk(s, it) }
        mic.on = s.hearMaster
        val bar = LevelBar(this)
        val vol = SeekBar(this).apply {
            max = 200; progress = (s.volume * 100).toInt()
            progressTintList = ColorStateList.valueOf(cc); thumbTintList = ColorStateList.valueOf(cc)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { s.volume = p / 100f }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
        right.addView(label("سطح صدا", 11f, C_GRAY)); right.addView(bar, lp(hh = 14, b = 6))
        right.addView(label("ولوم", 11f, C_GRAY)); right.addView(vol)
        c.addView(title, lp(b = 8))
        val r = row()
        r.addView(col(sp, "صدای او"), lp(w = -2, r = 6)); r.addView(col(mic, "صدای من"), lp(w = -2, r = 6))
        r.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(r)
        return c to Row(s, title, bar, sp, mic)
    }

    // ---------- کلاینت ----------
    private fun clientScreen() {
        header("کلاینت")
        val dot = label("●", 24f, C_AMBER)
        val status = label("", 13f)
        val sc = card(C_BLUE, 3)
        val sr = row()
        sr.addView(dot, lp(w = -2, r = 10)); sr.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        sc.addView(sr)
        add(sc)

        val mic = IconToggle(this, R.drawable.ic_mic, R.drawable.ic_mic_off, 64) { Intercom.client?.micMuted = !it }
        mic.on = !(Intercom.client?.micMuted ?: false)
        val spk = IconToggle(this, R.drawable.ic_speaker, R.drawable.ic_speaker_off, 64) { Intercom.client?.masterMuted = !it }
        spk.on = !(Intercom.client?.masterMuted ?: false)
        val route = routeBtn(); val end = endBtn()
        val bar = LevelBar(this)
        val vol = SeekBar(this).apply {
            max = 200; progress = ((Intercom.client?.masterVolume ?: 1f) * 100).toInt()
            progressTintList = ColorStateList.valueOf(C_GREEN); thumbTintList = ColorStateList.valueOf(C_GREEN)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { Intercom.client?.masterVolume = p / 100f }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val ctl = card(C_GREEN, 3)
        ctl.addView(equalCols(col(mic, "میکروفون من"), col(spk, "صدای مستر"), col(route, "خروجی"), col(end, "قطع")))
        ctl.addView(label("سطح صدای من", 11f, C_GRAY), lp(t = 10))
        ctl.addView(bar, lp(hh = 14, t = 2))
        ctl.addView(label("ولوم صدای مستر", 11f, C_GRAY), lp(t = 10))
        ctl.addView(vol)
        add(ctl)

        val ip = EditText(this).apply { hint = "IP مستر (خالی = خودکار)"; setText(Intercom.client?.host ?: ""); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val apply = IconToggle(this, R.drawable.ic_refresh, R.drawable.ic_refresh, 48, C_BLUE, C_BLUE, toggle = false) {
            Intercom.client?.reconnect(ip.text.toString())
        }
        val ipBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ipBox.addView(ip); ipBox.addView(col(apply, "اتصال مجدد"), lp(t = 6))
        ipBox.addView(credit())
        add(collapsible("⚙ تنظیمات IP", ipBox))

        val tick = object : Runnable {
            override fun run() {
                if (Intercom.role == 0) { show(); return }
                Intercom.client?.let {
                    status.text = it.status + "\nمسیر صدا: ${Intercom.router?.routeName}" +
                        (if (it.status == "متصل" && !it.masterTalking) "\n(مستر فعلاً با شما صحبت نمی‌کند)" else "") +
                        (if (!it.audioOk) "\n⚠ میکروفون/اسپیکر در دسترس نیست" else "")
                    dot.setTextColor(when { it.status == "متصل" -> C_GREEN; it.status.startsWith("قطع") -> C_RED; else -> C_AMBER })
                    bar.level = lvl(it.level)
                    route.setIcon(routeIcon())
                }
                h.postDelayed(this, 120)
            }
        }
        h.post(tick)
    }
}
