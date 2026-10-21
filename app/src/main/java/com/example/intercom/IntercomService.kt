package com.example.intercom

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

object Intercom {
    @Volatile var appCtx: Context? = null
    @Volatile var role = 0              // 0 هیچ، 1 مستر، 2 کلاینت
    var master: MasterEngine? = null
    var client: ClientEngine? = null
    var io: AudioIO? = null
    var router: AudioRouter? = null
}

class IntercomService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == "stop") { shutdown(); return START_NOT_STICKY }
        val role = i?.getIntExtra("role", 0) ?: 0
        goForeground()
        if (Intercom.master == null && Intercom.client == null && role != 0) begin(role, i!!)
        return START_NOT_STICKY
    }

    private fun begin(role: Int, i: Intent) {
        val sp = getSharedPreferences("p", 0)
        Cfg.compress = sp.getBoolean("compress", true)
        Cfg.noise = sp.getBoolean("noise", true)
        Cfg.buzzBeep = sp.getBoolean("buzzBeep", false)
        Intercom.appCtx = applicationContext
        val io = AudioIO()
        val router = AudioRouter(this) { h.postDelayed({ io.restart() }, 1200) } // زمان برای SCO
        router.start(); io.open()
        Intercom.io = io; Intercom.router = router
        if (role == 1) Intercom.master = MasterEngine(io, i.getStringExtra("pin") ?: "").also {
            it.onBuzz = { buzzPhone(this@IntercomService) }; it.onAlert = { alertPhone(this@IntercomService) }; it.start()
        }
        else Intercom.client = ClientEngine(io, i.getStringExtra("host") ?: "", i.getStringExtra("name") ?: "کلاینت", i.getStringExtra("pin") ?: "").also {
            it.onBuzz = { buzzPhone(this@IntercomService) }; it.onAlert = { alertPhone(this@IntercomService) }; it.start()
        }
        Intercom.role = role

        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "intercom:cpu").also { it.acquire() }
        wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                            else WifiManager.WIFI_MODE_FULL_HIGH_PERF, "intercom:wifi").also { it.acquire() }
    }

    private fun shutdown() {
        Intercom.master?.let { it.stopRecording(this); it.stop() }; Intercom.client?.stop()
        Intercom.io?.close(); Intercom.router?.stop()
        Intercom.master = null; Intercom.client = null; Intercom.io = null; Intercom.router = null
        Intercom.role = 0
        wake?.let { if (it.isHeld) it.release() }; wifi?.let { if (it.isHeld) it.release() }
        stopForeground(true); stopSelf()
    }

    override fun onDestroy() { if (Intercom.role != 0) shutdown(); super.onDestroy() }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val b = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("ic", "Intercom", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, "ic")
        } else Notification.Builder(this)
        val n = b.setContentTitle("اینترکام فعال است").setContentText("میکروفون در حال استفاده است")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }
}
