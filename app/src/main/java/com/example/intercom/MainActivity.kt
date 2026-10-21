package com.example.intercom

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.os.PowerManager
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.*
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var box: LinearLayout
    private var afterPerm: (() -> Unit)? = null
    private val GC = intArrayOf(C_BLUE, C_GREEN, C_AMBER, 0xFF6A1B9A.toInt())   // رنگ گروه‌های A..D

    private class Row(
        val s: ClientSession, val title: TextView, val quality: TextView, val note: TextView, val bar: LevelBar,
        val sp: IconToggle, val mic: IconToggle, val solo: IconToggle, val remote: IconToggle, val grp: TextView,
        val ptt: IconToggle, val blink: ImageView
    ) { var prev = false; var blinking = false }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        window.statusBarColor = C_BLUE
        val sp = getSharedPreferences("p", 0)
        Cfg.compress = sp.getBoolean("compress", true)
        Cfg.noise = sp.getBoolean("noise", true)
        Cfg.buzzBeep = sp.getBoolean("buzzBeep", false)
        Intercom.appCtx = applicationContext
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

    private fun chip(text: String, color: Int, sizeDp: Int, click: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        background = shape(color, oval = true); elevation = dp(3).toFloat()
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        setOnClickListener { click() }
    }

    private fun setChip(tv: TextView, text: String, color: Int) {
        if (tv.text.toString() != text) tv.text = text
        tv.textSize = if (text.length > 1) 14f else 18f
        tv.background = shape(color, oval = true)
    }

    private fun pill(text: String, color: Int = C_BLUE, click: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10)); background = shape(color, radiusDp = 20)
        setOnClickListener { click() }
    }

    private fun loadMsgs(): MutableList<String> {
        val raw = getSharedPreferences("p", 0).getString("msgs", null)
        val l = raw?.split("\n")?.filter { it.isNotBlank() }?.toMutableList()
        return if (l == null || l.isEmpty()) mutableListOf("بیا", "صبر کن", "تمام شد", "کمک", "باشه") else l
    }

    private fun saveMsgs(l: List<String>) { getSharedPreferences("p", 0).edit().putString("msgs", l.joinToString("\n")).apply() }

    /** پیام جدید (index = -1) یا ویرایش/حذف پیام موجود */
    private fun editMsg(index: Int, done: () -> Unit) {
        val list = loadMsgs()
        val et = EditText(this).apply {
            setText(if (index >= 0) list[index] else ""); hint = "متن پیام"; setSingleLine(true)
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(60))
        }
        val b = AlertDialog.Builder(this).setTitle(if (index >= 0) "ویرایش پیام" else "پیام جدید").setView(et)
            .setPositiveButton("ذخیره") { _, _ ->
                val t = et.text.toString().trim().replace("\n", " ")
                if (t.isNotEmpty()) {
                    if (index >= 0) list[index] = t else if (list.size < 20) list.add(t) else toast("حداکثر ۲۰ پیام")
                    saveMsgs(list); done()
                }
            }
            .setNegativeButton("لغو", null)
        if (index >= 0) b.setNeutralButton("حذف") { _, _ -> list.removeAt(index); saveMsgs(list); done() }
        b.show()
    }

    private fun quickMsgs(send: (String) -> Unit): LinearLayout {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var editing = false
        fun rebuild() {
            c.removeAllViews()
            val list = loadMsgs()
            fun w() = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
            var r = row()
            c.addView(r)
            list.forEachIndexed { i, text ->
                if (i > 0 && i % 3 == 0) { r = row(); c.addView(r) }
                r.addView(pill(if (editing) "✏ $text" else text, if (editing) C_AMBER else C_BLUE) {
                    if (editing) editMsg(i) { rebuild() } else send(text)
                }, w())
            }
            val tools = row()
            tools.addView(pill("➕ پیام جدید", C_GREEN) { editMsg(-1) { rebuild() } }, w())
            tools.addView(pill(if (editing) "✔ پایان ویرایش" else "✏ ویرایش", C_GRAY) { editing = !editing; rebuild() }, w())
            c.addView(tools, lp(t = 8))
        }
        rebuild()
        return c
    }

    private fun groupLetter(g: Int) = ('A' + (g.coerceIn(1, 4) - 1)).toString()
    private fun qColor(q: Int) = when { q < 0 -> C_GRAY; q < 80 -> C_GREEN; q < 200 -> C_AMBER; else -> C_RED }

    private fun routeIcon(): Int {
        val n = Intercom.router?.routeName ?: ""
        return when { n.startsWith("هدست") -> R.drawable.ic_headset; n.startsWith("گوشی") -> R.drawable.ic_phone; else -> R.drawable.ic_speaker }
    }

    private fun routeBtn(size: Int = 64) = IconToggle(this, R.drawable.ic_speaker, R.drawable.ic_speaker, size, C_BLUE, C_BLUE, toggle = false) { toggleSpeaker() }
    private fun endBtn(size: Int = 64) = IconToggle(this, R.drawable.ic_power, R.drawable.ic_power, size, C_RED, C_RED, toggle = false) { stopSvc() }

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

    private fun settingsSwitches(): LinearLayout {
        val sp = getSharedPreferences("p", 0)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(Switch(this).apply {
            text = "فشرده‌سازی صدا (مصرف اینترنت کمتر)"; isChecked = Cfg.compress
            setOnCheckedChangeListener { _, v -> Cfg.compress = v; sp.edit().putBoolean("compress", v).apply() }
        })
        c.addView(Switch(this).apply {
            text = "حذف نویز و تنظیم خودکار بلندی میکروفون"; isChecked = Cfg.noise
            setOnCheckedChangeListener { _, v -> Cfg.noise = v; sp.edit().putBoolean("noise", v).apply(); Intercom.io?.restart() }
        })
        c.addView(Switch(this).apply {
            text = "بوق همراه ویبره (اگر ویبره کار نمی‌کند)"; isChecked = Cfg.buzzBeep
            setOnCheckedChangeListener { _, v -> Cfg.buzzBeep = v; sp.edit().putBoolean("buzzBeep", v).apply() }
        })
        c.addView(pill("استثنا از محدودیت باتری") { askBattery(true) }, lp(t = 8))
        return c
    }

    private fun askBattery(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < 23) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { if (force) toast("برنامه قبلاً مستثنا شده است"); return }
        val sp = getSharedPreferences("p", 0)
        if (!force && sp.getBoolean("battAsked", false)) return
        sp.edit().putBoolean("battAsked", true).apply()
        try {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
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
        if (Build.VERSION.SDK_INT < 29) p += Manifest.permission.WRITE_EXTERNAL_STORAGE
        val missing = p.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) then() else { afterPerm = then; requestPermissions(missing.toTypedArray(), 1) }
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) afterPerm?.invoke()
        else Toast.makeText(this, "دسترسی میکروفون لازم است", Toast.LENGTH_LONG).show()
        afterPerm = null
    }

    private fun startSvc(role: Int, host: String = "", name: String = "", pin: String = "") {
        Intercom.role = role
        val i = Intent(this, IntercomService::class.java).putExtra("role", role)
            .putExtra("host", host).putExtra("name", name).putExtra("pin", pin)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        show()
        askBattery()
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
        val ip = EditText(this).apply { hint = "IP مستر (خالی = پیدا شدن خودکار)"; setText(prefs.getString("ip", "")); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val nm = EditText(this).apply { hint = "نام شما"; setText(prefs.getString("name", Build.MODEL)) }
        val pin = EditText(this).apply {
            hint = "رمز اتصال (اختیاری) — مستر تعیین می‌کند، کلاینت وارد می‌کند"
            setText(prefs.getString("pin", "")); inputType = InputType.TYPE_CLASS_NUMBER; layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        fun save() = prefs.edit().putString("ip", ip.text.toString().trim()).putString("name", nm.text.toString())
            .putString("pin", pin.text.toString().trim()).apply()

        add(roleCard(R.drawable.ic_mic, C_BLUE, "مستر", "کلاینت‌ها خودکار پیدا می‌شوند") {
            save(); needPerms { startSvc(1, pin = pin.text.toString().trim()) }
        })
        add(roleCard(R.drawable.ic_headset, C_GREEN, "کلاینت", "اتصال به مستر") {
            save(); needPerms { startSvc(2, ip.text.toString().trim(), nm.text.toString().ifBlank { "کلاینت" }, pin.text.toString().trim()) }
        })
        val set = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ip); addView(nm); addView(pin); addView(settingsSwitches(), lp(t = 8)); addView(credit())
        }
        add(collapsible("⚙ تنظیمات", set, open = !prefs.getString("ip", "").isNullOrEmpty()))
    }

    // ---------- مستر ----------
    private fun lockClick() {
        val m = Intercom.master ?: return
        if (m.lockedCount > 0) { m.unlock(); toast("قفل باز شد؛ جستجوی خودکار فعال است") }
        else if (m.lock()) toast("تعداد کلاینت‌ها روی ${m.lockedCount} قفل شد")
        else toast("ابتدا باید کلاینت‌ها وصل شوند")
    }

    private fun toggleRec() {
        val m = Intercom.master ?: return
        if (m.recorder.active) { m.stopRecording(applicationContext); toast("ضبط ذخیره شد: Download/Intercom/${m.recorder.name}") }
        else if (m.startRecording(applicationContext)) toast("ضبط شروع شد؛ به کلاینت‌ها اعلام می‌شود")
        else toast("شروع ضبط ممکن نشد (مجوز ذخیره‌سازی؟)")
    }

    // ---------- انتخاب و انتقال فایل ----------
    private var filePickTarget = 0   // -1 = مستر به همه، >0 = مستر به یک کلاینت، 0 = کلاینت به مستر

    private fun pickFile(target: Int) {
        filePickTarget = target
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        try { startActivityForResult(i, 77) } catch (_: Exception) { toast("انتخاب فایل ممکن نیست") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 77 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val (name, size) = FileIO.nameSize(this, uri)
        if (size < 0) { toast("حجم فایل مشخص نیست"); return }
        if (Intercom.role == 1) {
            val m = Intercom.master ?: return
            if (filePickTarget == -1) { m.sendFile(uri, name, size, null); toast("فایل برای همه پیشنهاد شد") }
            else {
                val s = m.sessions.firstOrNull { it.id == filePickTarget }
                if (s == null) toast("آن کلاینت دیگر وصل نیست") else { m.sendFile(uri, name, size, s); toast("فایل برای ${s.name} پیشنهاد شد") }
            }
        } else if (Intercom.role == 2) {
            Intercom.client?.uploadFile(uri, name, size)
            toast("ارسال فایل شروع شد")
        }
    }

    private fun blinkAnim() = AlphaAnimation(1f, 0.1f).apply { duration = 450; repeatMode = Animation.REVERSE; repeatCount = Animation.INFINITE }

    private fun masterScreen() {
        header("مستر")
        val banner = label("", 13f, Color.WHITE, true).apply {
            background = shape(C_RED, radiusDp = 12); setPadding(dp(12), dp(8), dp(12), dp(8)); visibility = View.GONE
        }
        add(banner)
        val myMic = IconToggle(this, R.drawable.ic_mic, R.drawable.ic_mic_off, 64) { Intercom.master?.changeMic(it) }
        myMic.on = Intercom.master?.micOpen ?: true
        val allSp = IconToggle(this, R.drawable.ic_speaker, R.drawable.ic_speaker_off, 64) { on -> Intercom.master?.sessions?.forEach { it.muted = !on } }
        val conf = IconToggle(this, R.drawable.ic_group, R.drawable.ic_group, 64, C_GREEN, C_GRAY) { Intercom.master?.conference = it }
        conf.on = Intercom.master?.conference ?: false
        val buzzAll = IconToggle(this, R.drawable.ic_bell, R.drawable.ic_bell, 64, C_AMBER, C_AMBER, toggle = false) {
            Intercom.master?.let { m -> m.sessions.forEach { m.buzz(it) } }
            toast("ویبره برای همه ارسال شد")
        }
        val talkChip = chip("همه", C_GRAY, 64) { Intercom.master?.let { m -> m.changeTalkGroup((m.talkGroup + 1) % 5) } }
        val rec = IconToggle(this, R.drawable.ic_rec, R.drawable.ic_rec, 64, C_RED, C_GRAY, toggle = false) { toggleRec() }
        val lock = IconToggle(this, R.drawable.ic_lock, R.drawable.ic_lock_open, 64, C_GREEN, C_AMBER, toggle = false) { lockClick() }
        lock.onHold = { Intercom.master?.fullSearch(); toast("جستجوی کلی از نو شروع شد") }
        lock.on = (Intercom.master?.lockedCount ?: 0) > 0
        val fileAll = IconToggle(this, R.drawable.ic_file, R.drawable.ic_file, 64, C_BLUE, C_BLUE, toggle = false) { pickFile(-1) }
        val route = routeBtn(); val end = endBtn()
        val myBar = LevelBar(this)
        val status = label("", 12f, C_GRAY)

        val ctl = card(C_BLUE, 3)
        ctl.addView(equalCols(col(myMic, "میکروفون من"), col(allSp, "همه کلاینت‌ها"), col(conf, "گفتگوی کلاینت‌ها"), col(buzzAll, "ویبره همه")))
        ctl.addView(equalCols(col(talkChip, "گروه مخاطب"), col(rec, "ضبط"), col(lock, "قفل (۳ث=جستجو)")), lp(t = 12))
        ctl.addView(equalCols(col(route, "خروجی"), col(fileAll, "فایل برای همه"), col(end, "پایان")), lp(t = 12))
        ctl.addView(myBar, lp(hh = 14, t = 10))
        ctl.addView(status, lp(t = 8))
        add(ctl)

        // پیام‌های آماده (قابل ویرایش)
        add(collapsible("📩 پیام‌های آماده", quickMsgs { w -> Intercom.master?.sendMsg(w); toast("ارسال شد: $w") }))

        // تنظیمات IP، جستجو و ...
        val ipTxt = label(localIps(), 13f).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val scanTxt = label("", 12f, C_GRAY)
        val auto = IconToggle(this, R.drawable.ic_refresh, R.drawable.ic_refresh, 48, C_GREEN, C_GRAY) { Intercom.master?.autoScan = it }
        auto.on = Intercom.master?.autoScan ?: true
        val now = IconToggle(this, R.drawable.ic_search, R.drawable.ic_search, 48, C_BLUE, C_BLUE, toggle = false) { Intercom.master?.scanNow() }
        val ipBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ipBox.addView(label("آدرس‌های این گوشی (پورت $PORT):", 12f, C_GRAY))
        ipBox.addView(ipTxt, lp(b = 4))
        val pinNow = Intercom.master?.pin ?: ""
        ipBox.addView(label("رمز اتصال: " + if (pinNow.isEmpty()) "ندارد" else pinNow, 13f, C_TEXT, true), lp(b = 8))
        ipBox.addView(equalCols(col(auto, "جستجوی خودکار"), col(now, "جستجوی فوری")))
        ipBox.addView(scanTxt, lp(t = 8))
        ipBox.addView(settingsSwitches(), lp(t = 8))
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
                    val nowMs = System.currentTimeMillis()
                    status.text = "${m.serverStatus}  •  ${m.sessions.size} کلاینت" +
                        (if (m.lockedCount > 0) "  •  🔒 ${m.lockedCount}" else "") +
                        (if (m.conference) "  •  گفتگو فعال" else "") +
                        (if (m.recorder.active) "  •  🔴 ضبط" else "") +
                        (if (m.soloId != 0) "  •  🎯 فقط با ${m.sessions.firstOrNull { it.id == m.soloId }?.name ?: "?"}" else "") +
                        (if (m.fileStatus.isNotEmpty()) "\n${m.fileStatus}" else "") +
                        (if (!m.audioOk) "\n⚠ میکروفون/اسپیکر در دسترس نیست" else "")
                    scanTxt.text = m.scanStatus
                    route.setIcon(routeIcon())
                    myBar.level = lvl(m.myLevel)
                    myMic.on = m.micOpen
                    rec.on = m.recorder.active
                    allSp.on = m.sessions.none { it.muted }
                    lock.on = m.lockedCount > 0
                    conf.on = m.conference
                    if (m.talkGroup == 0) setChip(talkChip, "همه", C_GRAY) else setChip(talkChip, groupLetter(m.talkGroup), GC[m.talkGroup - 1])

                    val cut = m.sessions.filter { it.clientMutedMaster }.map { it.name }
                    if (cut.isEmpty()) banner.visibility = View.GONE
                    else { banner.text = "🔇 قطع شد: ${cut.joinToString("، ")} صدای مستر را قطع کرده‌اند"; banner.visibility = View.VISIBLE }

                    val ids = m.sessions.map { it.id }
                    if (ids != shown) {
                        shown = ids; list.removeAllViews(); rows.clear()
                        if (ids.isEmpty()) list.addView(label("هنوز کلاینتی وصل نشده…", 14f, C_GRAY).apply { gravity = Gravity.CENTER }, lp(t = 8, b = 8))
                        m.sessions.forEach { s -> val (v, r) = makeRow(m, s); list.addView(v, lp(b = 12)); rows[s.id] = r }
                    }
                    rows.values.forEach { r ->
                        val s = r.s
                        r.title.text = "${s.name}   •   ${s.addr}" + if (nowMs - s.lastBuzz < 3000) "   🔔" else ""
                        r.quality.text = if (s.rtt < 0) "📶 …" else "📶 ${s.rtt}ms"
                        r.quality.setTextColor(qColor(s.rtt))
                        r.bar.level = lvl(s.level)
                        r.sp.on = !s.muted
                        r.mic.on = s.hearMaster
                        r.solo.on = m.soloId == s.id
                        r.ptt.on = m.pttId == s.id
                        r.remote.on = !s.micForced
                        setChip(r.grp, groupLetter(s.group), GC[s.group.coerceIn(1, 4) - 1])
                        val cutIt = s.clientMutedMaster
                        if (cutIt && !r.blinking) { r.blink.visibility = View.VISIBLE; r.blink.startAnimation(blinkAnim()); r.blinking = true }
                        else if (!cutIt && r.blinking) { r.blink.clearAnimation(); r.blink.visibility = View.GONE; r.blinking = false }
                        val notes = listOfNotNull(
                            if (s.clientMutedMaster) "🔇 قطع: صدای مستر را قطع کرده" else null,
                            if (s.micForced) "🔒 میکروفون او را بسته‌اید" else if (s.clientMicOff) "🎙 میکروفون او خاموش است" else null,
                            if (nowMs - s.lastMsgAt < 6000) "📩 ${s.lastMsg}" else null)
                        r.note.text = notes.joinToString("   •   ")
                        r.note.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
                        if (s.clientMutedMaster && !r.prev) toast("${s.name} صدای شما را قطع کرد")
                        r.prev = s.clientMutedMaster
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
        val quality = label("📶 …", 12f, C_GRAY, true)
        val note = label("", 12f, C_RED, true).apply { visibility = View.GONE }
        val blink = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic_off); background = shape(C_RED, oval = true)
            setPadding(dp(5), dp(5), dp(5), dp(5)); visibility = View.GONE
        }
        val sp = IconToggle(this, R.drawable.ic_speaker, R.drawable.ic_speaker_off, 56) { s.muted = !it }
        sp.on = !s.muted
        val mic = IconToggle(this, R.drawable.ic_mic, R.drawable.ic_mic_off, 56) { m.setTalk(s, it) }
        mic.on = s.hearMaster
        val solo = IconToggle(this, R.drawable.ic_person, R.drawable.ic_person, 56, C_BLUE, C_GRAY, toggle = false) { m.solo(s) }
        solo.on = m.soloId == s.id
        val buzz = IconToggle(this, R.drawable.ic_bell, R.drawable.ic_bell, 56, C_AMBER, C_AMBER, toggle = false) {
            m.buzz(s); toast("ویبره برای ${s.name} ارسال شد")
        }
        val remote = IconToggle(this, R.drawable.ic_mic, R.drawable.ic_mic_off, 56, C_GREEN, C_RED) { m.forceMic(s, !it) }
        remote.on = !s.micForced
        val grp = chip(groupLetter(s.group), GC[s.group.coerceIn(1, 4) - 1], 56) { m.setGroup(s, s.group % 4 + 1) }
        val ptt = IconToggle(this, R.drawable.ic_ptt, R.drawable.ic_ptt, 56, C_GREEN, C_GRAY)
        ptt.onPress = { d -> m.pttPress(s, d); ptt.on = d }
        ptt.on = m.pttId == s.id
        val file = IconToggle(this, R.drawable.ic_file, R.drawable.ic_file, 56, C_BLUE, C_BLUE, toggle = false) { pickFile(s.id) }
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
        val low = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        low.addView(label("سطح صدا", 11f, C_GRAY)); low.addView(bar, lp(hh = 14, b = 6))
        low.addView(label("ولوم", 11f, C_GRAY)); low.addView(vol)

        val top = row()
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(blink, LinearLayout.LayoutParams(dp(30), dp(30)).apply { setMargins(dp(6), 0, dp(6), 0) })
        top.addView(quality)
        c.addView(top, lp(b = 4))
        c.addView(note, lp(b = 6))
        c.addView(equalCols(col(sp, "صدای او"), col(mic, "صدای من"), col(solo, "فقط او"), col(buzz, "ویبره")), lp(t = 4))
        c.addView(equalCols(col(remote, "میکروفون او"), col(grp, "گروه"), col(ptt, "PTT"), col(file, "فایل")), lp(t = 10))
        c.addView(low, lp(t = 10))
        return c to Row(s, title, quality, note, bar, sp, mic, solo, remote, grp, ptt, blink)
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

        val mic = IconToggle(this, R.drawable.ic_mic, R.drawable.ic_mic_off, 64) { on ->
            val c = Intercom.client
            if (c != null) { if (c.forcedMute) toast("مستر میکروفون شما را بسته است") else c.micMuted = !on }
        }
        mic.on = !(Intercom.client?.micMuted ?: false)
        val spk = IconToggle(this, R.drawable.ic_speaker, R.drawable.ic_speaker_off, 64) { Intercom.client?.masterMuted = !it }
        spk.on = !(Intercom.client?.masterMuted ?: false)
        val buzz = IconToggle(this, R.drawable.ic_bell, R.drawable.ic_bell, 64, C_AMBER, C_AMBER, toggle = false) {
            Intercom.client?.buzzMaster(); toast("ویبره برای مستر ارسال شد")
        }
        val file = IconToggle(this, R.drawable.ic_file, R.drawable.ic_file, 64, C_BLUE, C_BLUE, toggle = false) { pickFile(0) }
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
        ctl.addView(equalCols(col(mic, "میکروفون من"), col(spk, "صدای مستر"), col(buzz, "ویبره")))
        ctl.addView(equalCols(col(route, "خروجی"), col(file, "ارسال فایل"), col(end, "قطع")), lp(t = 12))
        ctl.addView(label("سطح صدای من", 11f, C_GRAY), lp(t = 10))
        ctl.addView(bar, lp(hh = 14, t = 2))
        ctl.addView(label("ولوم صدای مستر", 11f, C_GRAY), lp(t = 10))
        ctl.addView(vol)
        add(ctl)

        add(collapsible("📩 پیام‌های آماده", quickMsgs { w -> Intercom.client?.sendMsg(w); toast("ارسال شد: $w") }))

        val prefs = getSharedPreferences("p", 0)
        val ip = EditText(this).apply { hint = "IP مستر (خالی = خودکار)"; setText(Intercom.client?.host ?: ""); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val pin = EditText(this).apply {
            hint = "رمز اتصال"; setText(Intercom.client?.pin ?: ""); inputType = InputType.TYPE_CLASS_NUMBER; layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val apply = IconToggle(this, R.drawable.ic_refresh, R.drawable.ic_refresh, 48, C_BLUE, C_BLUE, toggle = false) {
            prefs.edit().putString("pin", pin.text.toString().trim()).apply()
            Intercom.client?.reconnect(ip.text.toString(), pin.text.toString())
        }
        val ipBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ipBox.addView(ip); ipBox.addView(pin); ipBox.addView(col(apply, "اتصال مجدد"), lp(t = 6))
        ipBox.addView(settingsSwitches(), lp(t = 8))
        ipBox.addView(credit())
        add(collapsible("⚙ تنظیمات", ipBox))

        val tick = object : Runnable {
            override fun run() {
                if (Intercom.role == 0) { show(); return }
                Intercom.client?.let {
                    val now = System.currentTimeMillis()
                    val sb = StringBuilder(it.status)
                    sb.append("\nمسیر صدا: ${Intercom.router?.routeName}")
                    if (it.status == "متصل") sb.append("   •   گروه ${groupLetter(it.group)}")
                    if (it.status == "متصل" && !it.masterTalking) sb.append("\n(مستر فعلاً با شما صحبت نمی‌کند)")
                    if (it.recording) sb.append("\n🔴 این مکالمه ضبط می‌شود")
                    if (it.forcedMute) sb.append("\n🔒 مستر میکروفون شما را بسته است")
                    if (now - it.lastBuzz < 3000) sb.append("\n🔔 مستر شما را صدا زد")
                    if (now - it.lastMsgAt < 6000) sb.append("\n📩 مستر: ${it.lastMsg}")
                    if (it.fileStatus.isNotEmpty()) sb.append("\n${it.fileStatus}")
                    if (!it.audioOk) sb.append("\n⚠ میکروفون/اسپیکر در دسترس نیست")
                    status.text = sb.toString()
                    dot.setTextColor(when { it.status == "متصل" -> C_GREEN; it.status.startsWith("رمز") || it.status.startsWith("قطع") -> C_RED; else -> C_AMBER })
                    bar.level = lvl(it.level)
                    mic.on = !(it.micMuted || it.forcedMute)
                    route.setIcon(routeIcon())
                }
                h.postDelayed(this, 120)
            }
        }
        h.post(tick)
    }
}
