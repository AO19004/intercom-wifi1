package com.example.intercom

/** IMA-ADPCM: هر نمونه ۴ بیت. هر فریم مستقل است (۳ بایت سرآیند + ۴ بیت × نمونه‌ها) => ۱۶۳ بایت به‌جای ۶۴۰ */
object Adpcm {
    private val STEP = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060,
        1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484,
        7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767)
    private val ADJ = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)

    fun encode(pcm: ShortArray): ByteArray {
        val n = pcm.size
        val out = ByteArray(3 + (n + 1) / 2)
        var pred = pcm[0].toInt()
        var idx = 0
        out[0] = pred.toByte(); out[1] = (pred shr 8).toByte(); out[2] = 0
        for (k in 0 until n) {
            var diff = pcm[k].toInt() - pred
            var code = 0
            if (diff < 0) { code = 8; diff = -diff }
            var step = STEP[idx]
            var vp = step shr 3
            if (diff >= step) { code = code or 4; diff -= step; vp += step }
            step = step shr 1
            if (diff >= step) { code = code or 2; diff -= step; vp += step }
            step = step shr 1
            if (diff >= step) { code = code or 1; vp += step }
            pred += if ((code and 8) != 0) -vp else vp
            pred = pred.coerceIn(-32768, 32767)
            idx = (idx + ADJ[code and 7]).coerceIn(0, 88)
            val bi = 3 + k / 2
            if (k % 2 == 0) out[bi] = code.toByte()
            else out[bi] = (out[bi].toInt() or (code shl 4)).toByte()
        }
        return out
    }

    fun decode(d: ByteArray, n: Int): ShortArray {
        var pred = (d[0].toInt() and 0xFF) or (d[1].toInt() shl 8)
        var idx = d[2].toInt().coerceIn(0, 88)
        val out = ShortArray(n)
        for (k in 0 until n) {
            val b = d[3 + k / 2].toInt() and 0xFF
            val code = if (k % 2 == 0) (b and 0x0F) else (b shr 4)
            val step = STEP[idx]
            var vp = step shr 3
            if ((code and 4) != 0) vp += step
            if ((code and 2) != 0) vp += step shr 1
            if ((code and 1) != 0) vp += step shr 2
            pred += if ((code and 8) != 0) -vp else vp
            pred = pred.coerceIn(-32768, 32767)
            idx = (idx + ADJ[code and 7]).coerceIn(0, 88)
            out[k] = pred.toShort()
        }
        return out
    }
}
