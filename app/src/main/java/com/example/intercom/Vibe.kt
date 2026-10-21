package com.example.intercom

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

private var lastBuzzMs = 0L

/** ویبره با usage=ALARM تا سیستم آن را به‌خاطر حالت سکوت، DND یا پس‌زمینه بودن نادیده نگیرد */
@Suppress("DEPRECATION")
private fun vib(ctx: Context, pattern: LongArray) {
    try {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31)
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (!v.hasVibrator()) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1), attrs)
        else v.vibrate(pattern, -1, attrs)
    } catch (_: Exception) {}
}

private fun beep(stream: Int, tone: Int, ms: Int) {
    try {
        val tg = ToneGenerator(stream, 80)
        tg.startTone(tone, ms)
        Handler(Looper.getMainLooper()).postDelayed({ tg.release() }, (ms + 400).toLong())
    } catch (_: Exception) {}
}

/** سه ویبره‌ی بلند؛ اگر در تنظیمات فعال باشد بوق هم می‌دهد. حداقل فاصله ۰.۸ ثانیه تا اسپم نشود */
fun buzzPhone(ctx: Context) {
    val now = System.currentTimeMillis()
    if (now - lastBuzzMs < 800) return
    lastBuzzMs = now
    vib(ctx, longArrayOf(0, 400, 150, 400, 150, 400))
    if (Cfg.buzzBeep) beep(AudioManager.STREAM_ALARM, ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900)
}

/** هشدار پیام/فایل: دو ویبره‌ی کوتاه + بوق */
fun alertPhone(ctx: Context) {
    vib(ctx, longArrayOf(0, 150, 100, 150))
    beep(AudioManager.STREAM_VOICE_CALL, ToneGenerator.TONE_PROP_BEEP2, 250)
}
