package dev.amenhancer.module.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.Outline
import android.graphics.Rect
import android.os.Build
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import top.yukonga.miuix.kmp.nav.core.NavKey

internal enum class EmbeddedSettingsPage : NavKey {
    MAIN,
    CUSTOM_LYRICS,
    AUTOMATIC_LYRICS,
    LYRICS_EFFECTS,
    INTERFACE,
    TITLE_REGION,
    ADVANCED,
    PLUGINS,
    LLM_LYRICS,
    BOTTOM_BAR_ANIMATION,
}

private const val EMBEDDED_G3_SEGMENTS = 32

/**
 * Unit-speed corner with theta(s) = pi/2 * smootherstep(s). Both theta' and
 * theta'' vanish at either end: curvature and its rate join the straight edge
 * continuously (G3). Integrate once; Android renders the curve as short Hermite
 * cubic spans, shared by fills, strokes, clips and transition silhouettes.
 */
private val embeddedG3Corner = FloatArray((EMBEDDED_G3_SEGMENTS + 1) * 4).apply {
    fun angle(t: Double): Double = PI / 2.0 * t * t * t * (10.0 + t * (-15.0 + 6.0 * t))
    val step = 1.0 / EMBEDDED_G3_SEGMENTS
    var x = 0.0
    var y = 0.0
    this[2] = 1f
    for (index in 1..EMBEDDED_G3_SEGMENTS) {
        val start = angle((index - 1) * step)
        val middle = angle((index - 0.5) * step)
        val end = angle(index * step)
        x += step / 6.0 * (cos(start) + 4.0 * cos(middle) + cos(end))
        y += step / 6.0 * (sin(start) + 4.0 * sin(middle) + sin(end))
        val offset = index * 4
        this[offset] = x.toFloat()
        this[offset + 1] = y.toFloat()
        this[offset + 2] = cos(end).toFloat()
        this[offset + 3] = sin(end).toFloat()
    }
    val extent = ((x + y) / 2.0).toFloat()
    for (index in indices) this[index] /= extent
    val last = EMBEDDED_G3_SEGMENTS * 4
    this[last] = 1f
    this[last + 1] = 1f
    this[last + 2] = 0f
}

internal fun Path.setEmbeddedG3Rect(rect: RectF, cornerRadius: Float) {
    rewind()
    if (rect.isEmpty) return
    val halfSide = minOf(rect.width(), rect.height()) / 2f
    val radius = cornerRadius.coerceIn(0f, halfSide)
    if (radius == 0f) {
        addRect(rect, Path.Direction.CW)
        return
    }
    // Actual circles and capsules retain their circular profile.
    if (radius == halfSide) {
        addRoundRect(rect, radius, radius, Path.Direction.CW)
        return
    }
    val reach = minOf(radius * 1.5f, halfSide)
    val handle = 1f / (3f * EMBEDDED_G3_SEGMENTS)
    fun corner(originX: Float, originY: Float, xx: Float, xy: Float, yx: Float, yy: Float) {
        for (index in 1..EMBEDDED_G3_SEGMENTS) {
            val previous = (index - 1) * 4
            val next = index * 4
            val startX = embeddedG3Corner[previous]
            val startY = embeddedG3Corner[previous + 1]
            val e = embeddedG3Corner[next]
            val f = embeddedG3Corner[next + 1]
            // Keep tiny interpolation error at the almost-flat endpoints inside
            // the corner, including for API 26 convex-path outlines.
            val a = (startX + embeddedG3Corner[previous + 2] * handle).coerceIn(startX, e)
            val b = (startY + embeddedG3Corner[previous + 3] * handle).coerceIn(startY, f)
            val c = (e - embeddedG3Corner[next + 2] * handle).coerceIn(startX, e)
            val d = (f - embeddedG3Corner[next + 3] * handle).coerceIn(startY, f)
            cubicTo(
                originX + reach * (xx * a + yx * b), originY + reach * (xy * a + yy * b),
                originX + reach * (xx * c + yx * d), originY + reach * (xy * c + yy * d),
                originX + reach * (xx * e + yx * f), originY + reach * (xy * e + yy * f),
            )
        }
    }
    moveTo(rect.left + reach, rect.top)
    lineTo(rect.right - reach, rect.top)
    corner(rect.right - reach, rect.top, 1f, 0f, 0f, 1f)
    lineTo(rect.right, rect.bottom - reach)
    corner(rect.right, rect.bottom - reach, 0f, 1f, -1f, 0f)
    lineTo(rect.left + reach, rect.bottom)
    corner(rect.left + reach, rect.bottom, -1f, 0f, 0f, -1f)
    lineTo(rect.left, rect.top + reach)
    corner(rect.left, rect.top + reach, 0f, -1f, 1f, 0f)
    close()
}


/** Solid native View surface; geometry is cached until its bounds or radius change. */
internal class EmbeddedG3Drawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPath = Path()
    private val strokePath = Path()
    private val shapeBounds = RectF()
    private var dirty = true
    private var fillColor = Color.TRANSPARENT
    private var strokeColor = Color.TRANSPARENT
    private var strokeWidth = 0f
    private var drawableAlpha = 255
    private var filter: ColorFilter? = null

    var cornerRadius: Float = 0f
        set(value) {
            field = value
            dirty = true
            invalidateSelf()
        }

    fun setColor(color: Int) {
        fillColor = color
        invalidateSelf()
    }

    fun setStroke(width: Int, color: Int) {
        strokeWidth = width.coerceAtLeast(0).toFloat()
        strokeColor = color
        dirty = true
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        dirty = true
    }

    private fun updatePaths() {
        if (!dirty) return
        shapeBounds.set(bounds)
        fillPath.setEmbeddedG3Rect(shapeBounds, cornerRadius)
        shapeBounds.inset(strokeWidth / 2f, strokeWidth / 2f)
        strokePath.setEmbeddedG3Rect(shapeBounds, (cornerRadius - strokeWidth / 2f).coerceAtLeast(0f))
        dirty = false
    }

    override fun draw(canvas: Canvas) {
        updatePaths()
        paint.colorFilter = filter
        paint.style = Paint.Style.FILL
        paint.color = fillColor
        paint.alpha = Color.alpha(fillColor) * drawableAlpha / 255
        if (paint.alpha > 0) canvas.drawPath(fillPath, paint)
        if (strokeWidth > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.color = strokeColor
            paint.alpha = Color.alpha(strokeColor) * drawableAlpha / 255
            if (paint.alpha > 0) canvas.drawPath(strokePath, paint)
        }
    }

    @Suppress("DEPRECATION")
    override fun getOutline(outline: Outline) {
        updatePaths()
        if (fillPath.isEmpty) {
            outline.setEmpty()
            return
        }
        if (Build.VERSION.SDK_INT >= 30) outline.setPath(fillPath) else outline.setConvexPath(fillPath)
        outline.alpha = Color.alpha(fillColor) / 255f * drawableAlpha / 255f
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        filter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
