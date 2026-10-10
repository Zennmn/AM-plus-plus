package dev.amenhancer.module.hook.tabletmedia

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Reference footer symbols in a fixed 44dp target; selection changes color only. */
internal class TabletFooterDrawable(private val kind: Kind) : Drawable() {
    enum class Kind { LYRICS, QUEUE }
    var active = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubble = Path().apply {
        addRoundRect(13f, 13.5f, 31f, 27.5f, 4f, 4f, Path.Direction.CW)
        moveTo(17f, 26f); lineTo(17f, 31f); lineTo(22f, 26f); close()
    }
    private val quote = Path().apply {
        addRoundRect(17f, 17.5f, 21f, 21.5f, 1f, 1f, Path.Direction.CW)
        moveTo(19f, 20f); lineTo(18f, 24f); quadTo(21f, 22.5f, 21f, 20f); close()
        addRoundRect(23f, 17.5f, 27f, 21.5f, 1f, 1f, Path.Direction.CW)
        moveTo(25f, 20f); lineTo(24f, 24f); quadTo(27f, 22.5f, 27f, 20f); close()
    }
    private var opacity = 255
    override fun draw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 44f, bounds.height() / 44f)
        paint.style = Paint.Style.FILL
        if (kind == Kind.LYRICS) {
            color(Color.WHITE, if (active) 160 else 85)
            canvas.drawCircle(22f, 22f, 18f, paint)
            color(Color.BLACK, 140); canvas.drawPath(bubble, paint)
            color(Color.WHITE, 220); canvas.drawPath(quote, paint)
        } else {
            color(Color.WHITE, if (active) 235 else 160)
            for (y in listOf(16f, 22f, 28f)) {
                canvas.drawCircle(12.5f, y, .85f, paint)
                canvas.drawRoundRect(16f, y - .65f, 31.5f, y + .65f, .65f, .65f, paint)
            }
        }
        canvas.restoreToCount(saved)
    }
    private fun color(color: Int, alpha: Int) {
        paint.color = color; paint.alpha = alpha * opacity / 255
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
