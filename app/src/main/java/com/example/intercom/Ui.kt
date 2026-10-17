package com.example.intercom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout

val C_BLUE = 0xFF1565C0.toInt()
val C_GREEN = 0xFF2E7D32.toInt()
val C_RED = 0xFFC62828.toInt()
val C_AMBER = 0xFFEF6C00.toInt()
val C_GRAY = 0xFF78909C.toInt()
val C_BG = 0xFFECEFF1.toInt()
val C_TEXT = 0xFF263238.toInt()

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.shape(fill: Int, stroke: Int = 0, radiusDp: Int = 14, oval: Boolean = false, strokeDp: Int = 2) =
    GradientDrawable().apply {
        shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        if (stroke != 0) setStroke(dp(strokeDp), stroke)
    }

fun lvl(peak: Int): Float = Math.sqrt(peak / 32767.0).toFloat()

/** کلید گرد رنگی با آیکون؛ روشن = سبز، خاموش = قرمز با آیکون خط‌خورده */
class IconToggle(
    ctx: Context, var onIcon: Int, var offIcon: Int, sizeDp: Int,
    private val onColor: Int = C_GREEN, private val offColor: Int = C_RED,
    private val toggle: Boolean = true, interactive: Boolean = true,
    var onChange: (Boolean) -> Unit = {}
) : ImageButton(ctx) {
    var on: Boolean = true
        set(v) { if (field != v) { field = v; refresh() } }

    init {
        scaleType = ScaleType.FIT_CENTER
        val p = ctx.dp(sizeDp * 24 / 100)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        elevation = ctx.dp(3).toFloat()
        if (interactive) setOnClickListener { if (toggle) on = !on; onChange(on) }
        else { isClickable = false; isFocusable = false }
        refresh()
    }

    fun setIcon(res: Int) { if (res != onIcon) { onIcon = res; offIcon = res; refresh() } }

    fun refresh() {
        setImageResource(if (on) onIcon else offIcon)
        background = context.shape(if (on) onColor else offColor, oval = true)
    }
}

/** ساندبار قطعه‌قطعه سبز/زرد/قرمز */
class LevelBar(ctx: Context) : View(ctx) {
    var level: Float = 0f
        set(v) { val n = v.coerceIn(0f, 1f); if (n != field) { field = n; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val n = 18

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val gap = w / n * 0.2f
        val sw = (w - gap * (n - 1)) / n
        val lit = Math.round(level * n)
        for (i in 0 until n) {
            p.color = if (i < lit) when { i < n * 0.6 -> C_GREEN; i < n * 0.85 -> 0xFFFFB300.toInt(); else -> C_RED }
                      else 0xFFCFD8DC.toInt()
            val x = i * (sw + gap)
            c.drawRoundRect(x, 0f, x + sw, h, 4f, 4f, p)
        }
    }
}
