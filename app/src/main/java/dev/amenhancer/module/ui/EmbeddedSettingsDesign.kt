package dev.amenhancer.module.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.Outline
import android.graphics.Rect
import android.os.Build
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class EmbeddedSettingsPage {
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

internal data class EmbeddedLyricsEditorAction(
    val label: String,
    val compactLabel: String = label,
    val onClick: () -> Unit,
)

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

/** Soft contact shadow outside the same G3 outline used by the moving panel. */
internal class EmbeddedPanelShadow(private val density: Float) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
    }
    private val path = Path()
    private val localBounds = RectF()
    private var cachedRadius = -1f
    private var tile: Bitmap? = null
    private var tileRadius = -1f
    private var tileCorner = 0
    /** Pixel inset required by [draw]: 4dp of AO plus one guard pixel. */
    internal val outsetPx = kotlin.math.ceil(4f * density).toInt() + 1
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sourceStops = IntArray(4)
    private val targetX = FloatArray(4)
    private val targetY = FloatArray(4)
    private val sourceRect = Rect()
    private val targetRect = RectF()
    private val layerAlphas = IntArray(16) { index ->
        fun opacity(progress: Float): Float {
            val remaining = 1f - progress
            return 0.24f * remaining * remaining * remaining
        }
        val near = opacity(index / 16f)
        val far = opacity((index + 1) / 16f)
        ((near - far) / (1f - far) * 255f + 0.5f).toInt()
    }

    /** Prepare the fixed-radius shadow before an entrance animation starts. */
    fun prepare(radius: Float) {
        if (tile != null || radius <= 0f) return
        val corner = kotlin.math.ceil(radius * 1.5f).toInt()
        val edge = outsetPx + corner
        val size = edge * 2 + 1
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawVector(Canvas(bitmap), RectF(
            outsetPx.toFloat(), outsetPx.toFloat(),
            (size - outsetPx).toFloat(), (size - outsetPx).toFloat(),
        ), radius, 1f)
        tileRadius = radius
        tileCorner = corner
        sourceStops[1] = edge
        sourceStops[2] = edge + 1
        sourceStops[3] = size
        bitmap.prepareToDraw()
        tile = bitmap
    }

    fun draw(canvas: Canvas, bounds: RectF, radius: Float, opacity: Float = 1f) {
        if (bounds.isEmpty || opacity <= 0f) return
        val corner = kotlin.math.ceil(radius * 1.5f).toInt()
        // Fixed-radius panels change height during navigation. Rasterize the
        // existing shadow once; stretch only its straight edges, never corners.
        // Shrinking return targets retain the exact vector contour below.
        if (radius > 0f && bounds.width() > corner * 2 && bounds.height() > corner * 2 &&
            (tile == null || tileRadius == radius)
        ) {
            prepare(radius)
            targetX[0] = bounds.left - outsetPx
            targetX[1] = bounds.left + tileCorner
            targetX[2] = bounds.right - tileCorner
            targetX[3] = bounds.right + outsetPx
            targetY[0] = bounds.top - outsetPx
            targetY[1] = bounds.top + tileCorner
            targetY[2] = bounds.bottom - tileCorner
            targetY[3] = bounds.bottom + outsetPx
            tilePaint.alpha = (255f * opacity.coerceIn(0f, 1f)).toInt()
            val bitmap = requireNotNull(tile)
            for (row in 0..2) for (column in 0..2) {
                if (row == 1 && column == 1) continue
                sourceRect.set(sourceStops[column], sourceStops[row], sourceStops[column + 1], sourceStops[row + 1])
                targetRect.set(targetX[column], targetY[row], targetX[column + 1], targetY[row + 1])
                canvas.drawBitmap(bitmap, sourceRect, targetRect, tilePaint)
            }
            return
        }
        drawVector(canvas, bounds, radius, opacity)
    }

    private fun drawVector(canvas: Canvas, bounds: RectF, radius: Float, opacity: Float) {
        if (localBounds.width() != bounds.width() || localBounds.height() != bounds.height() || cachedRadius != radius) {
            localBounds.set(0f, 0f, bounds.width(), bounds.height())
            cachedRadius = radius
            path.setEmbeddedG3Rect(localBounds, radius)
        }
        val save = canvas.save()
        canvas.translate(bounds.left, bounds.top)
        // Keep the shadow outside even while a translucent panel is animating.
        canvas.clipOutPath(path)
        for (index in layerAlphas.indices.reversed()) {
            paint.alpha = (layerAlphas[index] * opacity.coerceIn(0f, 1f)).toInt()
            if (paint.alpha == 0) continue
            paint.strokeWidth = (index + 1) * density * 0.5f
            canvas.drawPath(path, paint)
        }
        canvas.restoreToCount(save)
    }
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


/**
 * Code-owned rendition of the actual AM++ application icon (ic_module.xml).
 *
 * It intentionally avoids loading the module drawable through Apple Music's
 * package Context, which is subject to package-visibility failures in the
 * injected process. The mark and gradients mirror the source icon; the tile
 * radius follows the settings UI rather than the launcher artwork.
 */
internal class EmbeddedAmppBrandDrawable(
    private val cornerRadiusPx: Float,
) : android.graphics.drawable.Drawable() {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var alphaValue = 255
    private var colorFilterValue: ColorFilter? = null
    private val tilePath = Path()
    private val tileBounds = RectF(0f, 0f, VIEWPORT, VIEWPORT)
    private var tileRadius = Float.NaN

    private val aPath = Path().apply {
        moveTo(407f, 326f)
        cubicTo(386f, 339f, 376f, 357f, 366f, 380f)
        lineTo(161f, 844f)
        cubicTo(149f, 871f, 158f, 899f, 181f, 915f)
        cubicTo(207f, 934f, 240f, 922f, 254f, 892f)
        lineTo(424f, 492f)
        cubicTo(428f, 481f, 432f, 473f, 439f, 471f)
        cubicTo(447f, 469f, 451f, 476f, 457f, 487f)
        lineTo(672f, 903f)
        cubicTo(684f, 926f, 711f, 932f, 735f, 920f)
        cubicTo(759f, 908f, 771f, 883f, 759f, 856f)
        lineTo(504f, 359f)
        cubicTo(494f, 338f, 475f, 322f, 454f, 317f)
        cubicTo(438f, 313f, 421f, 317f, 407f, 326f)
        close()
    }
    private val notePath = Path().apply {
        moveTo(509f, 638f)
        lineTo(431f, 646f)
        cubicTo(422f, 647f, 417f, 654f, 417f, 664f)
        lineTo(420f, 801f)
        cubicTo(406f, 794f, 391f, 790f, 374f, 790f)
        cubicTo(334f, 790f, 303f, 819f, 303f, 857f)
        cubicTo(303f, 898f, 334f, 929f, 373f, 929f)
        cubicTo(417f, 929f, 451f, 899f, 451f, 857f)
        lineTo(451f, 739f)
        cubicTo(451f, 726f, 457f, 719f, 469f, 717f)
        lineTo(512f, 712f)
        cubicTo(526f, 710f, 534f, 700f, 532f, 687f)
        lineTo(526f, 651f)
        cubicTo(524f, 642f, 519f, 638f, 509f, 638f)
        close()
    }
    private val mPath = Path().apply {
        moveTo(635f, 463f)
        cubicTo(618f, 478f, 610f, 500f, 614f, 522f)
        cubicTo(616f, 534f, 620f, 543f, 626f, 555f)
        lineTo(724f, 740f)
        lineTo(724f, 670f)
        cubicTo(724f, 660f, 728f, 651f, 734f, 650f)
        cubicTo(741f, 650f, 746f, 657f, 752f, 666f)
        lineTo(803f, 750f)
        cubicTo(813f, 764f, 826f, 772f, 840f, 769f)
        cubicTo(850f, 768f, 858f, 759f, 866f, 749f)
        lineTo(927f, 668f)
        cubicTo(934f, 659f, 938f, 656f, 942f, 661f)
        cubicTo(944f, 665f, 943f, 673f, 943f, 679f)
        lineTo(943f, 885f)
        cubicTo(943f, 911f, 965f, 930f, 991f, 930f)
        cubicTo(1020f, 930f, 1043f, 908f, 1043f, 880f)
        lineTo(1043f, 525f)
        cubicTo(1043f, 495f, 1020f, 473f, 990f, 473f)
        cubicTo(972f, 473f, 958f, 480f, 946f, 494f)
        lineTo(833f, 636f)
        lineTo(713f, 472f)
        cubicTo(694f, 447f, 658f, 445f, 635f, 463f)
        close()
    }

    override fun draw(canvas: Canvas) {
        val box = bounds
        if (box.width() <= 0 || box.height() <= 0) return
        val size = minOf(box.width(), box.height()).toFloat()
        val left = box.left + (box.width() - size) / 2f
        val top = box.top + (box.height() - size) / 2f
        val scale = size / VIEWPORT
        configurePaints()

        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        val radius = (cornerRadiusPx / scale).coerceIn(0f, VIEWPORT / 2f)
        if (tileRadius != radius) {
            tilePath.setEmbeddedG3Rect(tileBounds, radius)
            tileRadius = radius
        }
        canvas.drawPath(tilePath, backgroundPaint)
        canvas.drawPath(aPath, primaryPaint)
        canvas.drawPath(notePath, primaryPaint)
        canvas.drawPath(mPath, accentPaint)
        canvas.drawRoundRect(RectF(794f, 307f, 823f, 440f), 14.5f, 14.5f, primaryPaint)
        canvas.drawRoundRect(RectF(746f, 358f, 879f, 388f), 15f, 15f, primaryPaint)
        canvas.drawRoundRect(RectF(963f, 307f, 992f, 440f), 14.5f, 14.5f, primaryPaint)
        canvas.drawRoundRect(RectF(915f, 358f, 1047f, 388f), 15f, 15f, primaryPaint)
        canvas.restore()
    }

    private fun configurePaints() {
        backgroundPaint.shader = RadialGradient(
            627f, 564f, 941f,
            intArrayOf(Color.parseColor("#FDEEEE"), Color.parseColor("#FCEBEC")),
            null,
            Shader.TileMode.CLAMP,
        )
        primaryPaint.shader = LinearGradient(
            0f, 0f, VIEWPORT, VIEWPORT,
            Color.parseColor("#F45F6B"),
            Color.parseColor("#F66A72"),
            Shader.TileMode.CLAMP,
        )
        accentPaint.shader = LinearGradient(
            0f, 0f, VIEWPORT, VIEWPORT,
            Color.parseColor("#B05B91"),
            Color.parseColor("#AC5A8E"),
            Shader.TileMode.CLAMP,
        )
        listOf(backgroundPaint, primaryPaint, accentPaint).forEach { paint ->
            paint.alpha = alphaValue
            paint.colorFilter = colorFilterValue
        }
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        colorFilterValue = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val VIEWPORT = 1254f
    }
}

/**
 * Apple Music application mark used by the current-song row.
 *
 * This is an application identity, like [EmbeddedAmppBrandDrawable], and keeps
 * its original red-tile composition rather than using the functional SVG set.
 */
internal class EmbeddedMusicStatusDrawable : android.graphics.drawable.Drawable(), EmbeddedPaletteDrawable {
    private var colors = EmbeddedSettingsPalette.Light

    override fun applyPalette(colors: EmbeddedSettingsColors) {
        this.colors = colors
        invalidateSelf()
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val noteStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val noteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var alphaValue = 255

    override fun draw(canvas: Canvas) {
        val box = bounds
        if (box.width() <= 0 || box.height() <= 0) return
        val size = minOf(box.width(), box.height()).toFloat()
        val outer = RectF(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
        val radius = size * 0.18f
        backgroundPaint.color = colors.primary
        backgroundPaint.alpha = alphaValue
        canvas.drawRoundRect(outer, radius, radius, backgroundPaint)
        noteStroke.color = colors.onPrimary
        noteStroke.alpha = alphaValue
        noteStroke.strokeWidth = size * 0.085f
        noteFill.color = colors.onPrimary
        noteFill.alpha = alphaValue
        val stemX = outer.left + size * 0.59f
        canvas.drawLine(stemX, outer.top + size * 0.22f, stemX, outer.top + size * 0.67f, noteStroke)
        canvas.drawLine(stemX, outer.top + size * 0.22f, outer.left + size * 0.76f, outer.top + size * 0.17f, noteStroke)
        canvas.drawCircle(outer.left + size * 0.41f, outer.top + size * 0.7f, size * 0.14f, noteFill)
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        backgroundPaint.colorFilter = colorFilter
        noteStroke.colorFilter = colorFilter
        noteFill.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal enum class EmbeddedGlyphKind {
    Music,
    Exchange,
    Github,
    CloudBackup,
    DocumentSearch,
    TabletDualPane,
    BottomBar,
    VideoDisplay,
    Glass,
    LyricsBlur,
    Document,
    Translate,
    Refresh,
    AddCircle,
    TtmlDocument,
    Search,
    Edit,
    Delete,
    BackArrow,
    ChevronRight,
    MoreVertical,
}

/**
 * Compatibility entry point for existing call sites. Every functional glyph is
 * now rendered from the shared SVG catalogue instead of hand-authored Canvas
 * geometry.
 */
internal class EmbeddedGlyphDrawable(
    kind: EmbeddedGlyphKind,
    tint: Int,
) : Drawable(), EmbeddedPaletteDrawable {
    private val delegate = EmbeddedSettingsSvgAssets.glyphDrawable(kind.toSvgIcon(), tint)
    private var alphaValue = 255
    private var colorFilterValue: ColorFilter? = null

    override fun applyPalette(colors: EmbeddedSettingsColors) {
        (delegate as? EmbeddedPaletteDrawable)?.applyPalette(colors)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val icon = delegate ?: return
        icon.setBounds(bounds)
        icon.alpha = alphaValue
        icon.colorFilter = colorFilterValue
        icon.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        colorFilterValue = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
