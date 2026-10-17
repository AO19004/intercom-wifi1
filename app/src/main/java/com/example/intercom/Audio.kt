package com.example.intercom

import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread
import kotlin.math.max

/** ضبط و پخش (میکروفون/اسپیکر یا هدست؛ مسیر را AudioRouter تعیین می‌کند) */
class AudioIO {
    @Volatile private var rec: AudioRecord? = null
    @Volatile private var trk: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null

    @Synchronized fun open() {
        close()
        val rb = max(FRAME * 8, AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, rb)
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return }
        if (AcousticEchoCanceler.isAvailable())
            aec = AcousticEchoCanceler.create(r.audioSessionId)?.also { it.enabled = true }
        r.startRecording()

        val tb = max(FRAME * 8, AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(tb)
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        t.play()
        rec = r; trk = t
    }

    @Synchronized fun close() {
        rec?.let { try { it.stop() } catch (_: Exception) {}; it.release() }; rec = null
        aec?.release(); aec = null
        trk?.let { try { it.stop() } catch (_: Exception) {}; it.release() }; trk = null
    }

    fun restart() { thread(name = "audio-restart") { open() } }

    /** خواندن بلوکه‌کننده؛ false یعنی دستگاه آماده نیست */
    fun read(f: ShortArray): Boolean {
        val r = rec ?: return false
        return try { r.read(f, 0, f.size) == f.size } catch (_: Exception) { false }
    }

    fun write(f: ShortArray): Boolean {
        val t = trk ?: return false
        return try { t.write(f, 0, f.size) == f.size } catch (_: Exception) { false }
    }
}

/** انتخاب مسیر: بلوتوث > هدست سیمی > بلندگو/گوشی */
class AudioRouter(ctx: Context, private val onRouteChanged: () -> Unit) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile var preferSpeaker = true
    @Volatile var routeName = ""

    private val cb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(a: Array<out AudioDeviceInfo>?) { if (apply()) onRouteChanged() }
        override fun onAudioDevicesRemoved(a: Array<out AudioDeviceInfo>?) { if (apply()) onRouteChanged() }
    }

    fun start() {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        apply()
    }

    fun stop() {
        am.unregisterAudioDeviceCallback(cb)
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
        else { am.stopBluetoothSco(); am.isBluetoothScoOn = false; am.isSpeakerphoneOn = false }
        am.mode = AudioManager.MODE_NORMAL
    }

    /** true اگر مسیر تغییر کرده (و باید جریان‌های صدا دوباره باز شوند) */
    @Synchronized fun apply(): Boolean {
        val name: String
        if (Build.VERSION.SDK_INT >= 31) {
            val c = am.availableCommunicationDevices
            fun find(vararg t: Int) = c.firstOrNull { it.type in t }
            val pick = find(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
                ?: find(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)
                ?: if (preferSpeaker) find(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) else find(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            pick?.let { am.setCommunicationDevice(it) }
            name = when (pick?.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "هدست بلوتوثی"
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> "هدست سیمی"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "بلندگوی گوشی"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "گوشی (Earpiece)"
                else -> "پیش‌فرض"
            }
        } else {
            val d = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val bt = d.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            val wired = d.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            if (bt) {
                am.isSpeakerphoneOn = false
                if (!am.isBluetoothScoOn) { am.startBluetoothSco(); am.isBluetoothScoOn = true }
                name = "هدست بلوتوثی"
            } else {
                if (am.isBluetoothScoOn) { am.stopBluetoothSco(); am.isBluetoothScoOn = false }
                am.isSpeakerphoneOn = !wired && preferSpeaker
                name = if (wired) "هدست سیمی" else if (preferSpeaker) "بلندگوی گوشی" else "گوشی (Earpiece)"
            }
        }
        val changed = name != routeName
        routeName = name
        return changed
    }
}
