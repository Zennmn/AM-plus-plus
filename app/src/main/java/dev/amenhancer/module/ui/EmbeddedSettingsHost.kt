package dev.amenhancer.module.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
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
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.EmbeddedConfigurationSession
import dev.amenhancer.module.config.TitleCorrectionMode
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.hook.AmLyricsClient
import dev.amenhancer.module.hook.AmllTtmlClient
import dev.amenhancer.module.hook.FileLunabeatCatalogCache
import dev.amenhancer.module.hook.HttpLyricTransport
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.module.hook.LunabeatClient
import dev.amenhancer.module.lyrics.CustomLyricsDraft
import dev.amenhancer.module.lyrics.CustomLyricsMultiIdDraft
import dev.amenhancer.module.lyrics.CustomLyricsOnlineImportResult
import dev.amenhancer.module.lyrics.CustomLyricsOnlineImporter
import dev.amenhancer.module.lyrics.CustomLyricsRestorePolicy
import dev.amenhancer.module.lyrics.CustomLyricsUpdateProgress
import dev.amenhancer.module.lyrics.CustomLyricsUpdateResult
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal enum class EmbeddedHostActivityRole {
    Player,
    MainContent,
    Settings,
}

internal enum class EmbeddedSettingsPage {
    MAIN,
    CUSTOM_LYRICS,
}

private data class EmbeddedLyricsEditorAction(
    val label: String,
    val compactLabel: String = label,
    val onClick: () -> Unit,
)

private data class NativePreferenceGroupAccessors(
    val count: Method,
    val itemAt: Method,
    val remove: Method?,
)

/**
 * Embedded settings use the same warm AM++ accent family as the standalone
 * settings screen.  Keep these values in one place so host/system accent
 * colours (notably Apple Music's blue) cannot leak into the injected UI.
 */
private object EmbeddedSettingsPalette {
    val pageBackground: Int = Color.parseColor("#FBFAFB")
    val softBackground: Int = Color.parseColor("#FBF4F6")
    val softSurface: Int = Color.parseColor("#FAF3F5")
    val primary: Int = Color.parseColor("#EE3B4F")
    val primaryPressed: Int = Color.parseColor("#F65A6B")
    val accent: Int = Color.parseColor("#A6537C")
    val accentPressed: Int = Color.parseColor("#9D466E")

    val onSurface: Int = Color.rgb(48, 35, 42)
    val onSurfaceVariant: Int = Color.rgb(112, 89, 101)
    val outline: Int = Color.rgb(238, 233, 234)
    val disabledSurface: Int = Color.rgb(244, 237, 240)
    val disabledText: Int = Color.rgb(158, 140, 149)
    val divider: Int = Color.rgb(238, 233, 234)
    val switchTrackOn: Int = Color.parseColor("#F497A1")
    val switchTrackOff: Int = Color.parseColor("#D5D5D5")
}

/**
 * Code-owned rendition of the actual AM++ application icon (`ic_module.xml`).
 *
 * It intentionally avoids loading the module drawable through Apple Music's
 * package Context, which is subject to package-visibility failures in the
 * injected process. The paths and gradients below mirror the source icon.
 */
private class EmbeddedAmppBrandDrawable : android.graphics.drawable.Drawable() {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var alphaValue = 255
    private var colorFilterValue: ColorFilter? = null

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
        canvas.drawRoundRect(RectF(0f, 0f, VIEWPORT, VIEWPORT), 282f, 282f, backgroundPaint)
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

/** Compact red music mark used by the current-song row in the reference UI. */
private class EmbeddedMusicStatusDrawable : android.graphics.drawable.Drawable() {
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
        backgroundPaint.color = EmbeddedSettingsPalette.primary
        backgroundPaint.alpha = alphaValue
        canvas.drawRoundRect(outer, radius, radius, backgroundPaint)
        noteStroke.color = Color.WHITE
        noteStroke.alpha = alphaValue
        noteStroke.strokeWidth = size * 0.085f
        noteFill.color = Color.WHITE
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

private enum class EmbeddedGlyphKind {
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

/** Small host-independent glyphs for the reference actions and empty state. */
private class EmbeddedGlyphDrawable(
    private val kind: EmbeddedGlyphKind,
    private val tint: Int,
    private val strokeWidthFraction: Float = 0.08f,
) : android.graphics.drawable.Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var alphaValue = 255

    override fun draw(canvas: Canvas) {
        val box = bounds
        if (box.width() <= 0 || box.height() <= 0) return
        val size = minOf(box.width(), box.height()).toFloat()
        // Keep glyph geometry centered when an ImageView uses a rectangular
        // touch target (the overflow icon is 32/40×44dp).
        val left = box.left + (box.width() - size) / 2f
        val top = box.top + (box.height() - size) / 2f
        val cx = left + size / 2f
        val cy = top + size / 2f
        fill.color = tint
        fill.alpha = alphaValue
        stroke.color = tint
        stroke.alpha = alphaValue
        stroke.strokeWidth = size * strokeWidthFraction
        when (kind) {
            EmbeddedGlyphKind.Music -> {
                canvas.drawLine(cx + size * 0.1f, top + size * 0.2f, cx + size * 0.1f, top + size * 0.68f, stroke)
                canvas.drawLine(cx + size * 0.1f, top + size * 0.2f, cx + size * 0.34f, top + size * 0.15f, stroke)
                canvas.drawCircle(cx - size * 0.03f, top + size * 0.73f, size * 0.15f, fill)
            }
            EmbeddedGlyphKind.Exchange -> {
                val y1 = cy - size * 0.15f
                val y2 = cy + size * 0.15f
                canvas.drawLine(left + size * 0.22f, y1, left + size * 0.72f, y1, stroke)
                canvas.drawLine(left + size * 0.28f, y2, left + size * 0.78f, y2, stroke)
                canvas.drawLine(left + size * 0.72f, y1, left + size * 0.56f, y1 - size * 0.14f, stroke)
                canvas.drawLine(left + size * 0.72f, y1, left + size * 0.56f, y1 + size * 0.14f, stroke)
                canvas.drawLine(left + size * 0.28f, y2, left + size * 0.44f, y2 - size * 0.14f, stroke)
                canvas.drawLine(left + size * 0.28f, y2, left + size * 0.44f, y2 + size * 0.14f, stroke)
            }
            EmbeddedGlyphKind.Github -> {
                // Match the reference's Octocat silhouette rather than the
                // previous generic cat face. The even-odd face opening keeps
                // the mark legible on the pale action surface at small sizes.
                val octocat = Path().apply {
                    fillType = Path.FillType.EVEN_ODD
                    moveTo(cx, top + size * 0.16f)
                    cubicTo(
                        cx - size * 0.13f, top + size * 0.16f,
                        cx - size * 0.24f, top + size * 0.19f,
                        cx - size * 0.31f, top + size * 0.26f,
                    )
                    lineTo(cx - size * 0.4f, top + size * 0.17f)
                    lineTo(cx - size * 0.35f, top + size * 0.39f)
                    cubicTo(
                        cx - size * 0.4f, top + size * 0.48f,
                        cx - size * 0.38f, top + size * 0.61f,
                        cx - size * 0.3f, top + size * 0.7f,
                    )
                    cubicTo(
                        cx - size * 0.24f, top + size * 0.78f,
                        cx - size * 0.15f, top + size * 0.82f,
                        cx - size * 0.07f, top + size * 0.83f,
                    )
                    lineTo(cx - size * 0.07f, top + size * 0.68f)
                    cubicTo(
                        cx - size * 0.13f, top + size * 0.69f,
                        cx - size * 0.16f, top + size * 0.66f,
                        cx - size * 0.16f, top + size * 0.61f,
                    )
                    lineTo(cx - size * 0.12f, top + size * 0.59f)
                    cubicTo(
                        cx - size * 0.1f, top + size * 0.64f,
                        cx - size * 0.06f, top + size * 0.67f,
                        cx - size * 0.02f, top + size * 0.67f,
                    )
                    lineTo(cx - size * 0.02f, top + size * 0.85f)
                    lineTo(cx + size * 0.02f, top + size * 0.85f)
                    lineTo(cx + size * 0.02f, top + size * 0.67f)
                    cubicTo(
                        cx + size * 0.06f, top + size * 0.67f,
                        cx + size * 0.1f, top + size * 0.64f,
                        cx + size * 0.12f, top + size * 0.59f,
                    )
                    lineTo(cx + size * 0.16f, top + size * 0.61f)
                    cubicTo(
                        cx + size * 0.16f, top + size * 0.66f,
                        cx + size * 0.13f, top + size * 0.69f,
                        cx + size * 0.07f, top + size * 0.68f,
                    )
                    lineTo(cx + size * 0.07f, top + size * 0.83f)
                    cubicTo(
                        cx + size * 0.15f, top + size * 0.82f,
                        cx + size * 0.24f, top + size * 0.78f,
                        cx + size * 0.3f, top + size * 0.7f,
                    )
                    cubicTo(
                        cx + size * 0.38f, top + size * 0.61f,
                        cx + size * 0.4f, top + size * 0.48f,
                        cx + size * 0.35f, top + size * 0.39f,
                    )
                    lineTo(cx + size * 0.4f, top + size * 0.17f)
                    lineTo(cx + size * 0.31f, top + size * 0.26f)
                    cubicTo(
                        cx + size * 0.24f, top + size * 0.19f,
                        cx + size * 0.13f, top + size * 0.16f,
                        cx, top + size * 0.16f,
                    )
                    close()
                    moveTo(cx - size * 0.2f, top + size * 0.34f)
                    cubicTo(
                        cx - size * 0.12f, top + size * 0.29f,
                        cx + size * 0.12f, top + size * 0.29f,
                        cx + size * 0.2f, top + size * 0.34f,
                    )
                    cubicTo(
                        cx + size * 0.24f, top + size * 0.42f,
                        cx + size * 0.22f, top + size * 0.51f,
                        cx + size * 0.17f, top + size * 0.58f,
                    )
                    cubicTo(
                        cx + size * 0.12f, top + size * 0.64f,
                        cx + size * 0.06f, top + size * 0.66f,
                        cx, top + size * 0.66f,
                    )
                    cubicTo(
                        cx - size * 0.06f, top + size * 0.66f,
                        cx - size * 0.12f, top + size * 0.64f,
                        cx - size * 0.17f, top + size * 0.58f,
                    )
                    cubicTo(
                        cx - size * 0.22f, top + size * 0.51f,
                        cx - size * 0.24f, top + size * 0.42f,
                        cx - size * 0.2f, top + size * 0.34f,
                    )
                    close()
                }
                canvas.drawPath(octocat, fill)
            }
            EmbeddedGlyphKind.CloudBackup -> {
                val cloud = Path().apply {
                    moveTo(left + size * 0.2f, top + size * 0.72f)
                    cubicTo(
                        left + size * 0.13f,
                        top + size * 0.69f,
                        left + size * 0.12f,
                        top + size * 0.57f,
                        left + size * 0.2f,
                        top + size * 0.5f,
                    )
                    cubicTo(
                        left + size * 0.24f,
                        top + size * 0.34f,
                        left + size * 0.4f,
                        top + size * 0.27f,
                        left + size * 0.53f,
                        top + size * 0.31f,
                    )
                    cubicTo(
                        left + size * 0.64f,
                        top + size * 0.34f,
                        left + size * 0.71f,
                        top + size * 0.42f,
                        left + size * 0.73f,
                        top + size * 0.51f,
                    )
                    cubicTo(
                        left + size * 0.84f,
                        top + size * 0.51f,
                        left + size * 0.89f,
                        top + size * 0.58f,
                        left + size * 0.89f,
                        top + size * 0.65f,
                    )
                    cubicTo(
                        left + size * 0.89f,
                        top + size * 0.75f,
                        left + size * 0.81f,
                        top + size * 0.8f,
                        left + size * 0.7f,
                        top + size * 0.8f,
                    )
                    lineTo(left + size * 0.24f, top + size * 0.8f)
                }
                canvas.drawPath(cloud, stroke)
                canvas.drawLine(cx - size * 0.12f, top + size * 0.62f, cx + size * 0.12f, top + size * 0.62f, stroke)
                canvas.drawLine(cx, top + size * 0.5f, cx, top + size * 0.74f, stroke)
            }
            EmbeddedGlyphKind.DocumentSearch -> {
                val document = RectF(
                    left + size * 0.22f,
                    top + size * 0.14f,
                    left + size * 0.64f,
                    top + size * 0.78f,
                )
                canvas.drawRoundRect(document, size * 0.04f, size * 0.04f, stroke)
                canvas.drawLine(left + size * 0.48f, top + size * 0.14f, left + size * 0.64f, top + size * 0.3f, stroke)
                canvas.drawCircle(left + size * 0.67f, top + size * 0.67f, size * 0.17f, stroke)
                canvas.drawLine(left + size * 0.79f, top + size * 0.79f, left + size * 0.9f, top + size * 0.9f, stroke)
            }
            EmbeddedGlyphKind.TabletDualPane -> {
                val device = RectF(
                    left + size * 0.18f,
                    top + size * 0.2f,
                    left + size * 0.82f,
                    top + size * 0.8f,
                )
                canvas.drawRoundRect(device, size * 0.06f, size * 0.06f, stroke)
                canvas.drawLine(left + size * 0.61f, device.top, left + size * 0.61f, device.bottom, stroke)
                canvas.drawCircle(left + size * 0.71f, top + size * 0.36f, size * 0.045f, fill)
                canvas.drawLine(left + size * 0.71f, top + size * 0.47f, left + size * 0.71f, top + size * 0.64f, stroke)
            }
            EmbeddedGlyphKind.BottomBar -> {
                val bulletX = left + size * 0.25f
                val lineStart = left + size * 0.4f
                val lineEnd = left + size * 0.78f
                listOf(0.3f, 0.5f, 0.7f).forEach { fraction ->
                    val y = top + size * fraction
                    canvas.drawCircle(bulletX, y, size * 0.06f, fill)
                    canvas.drawLine(lineStart, y, lineEnd, y, stroke)
                }
            }
            EmbeddedGlyphKind.VideoDisplay -> {
                val display = RectF(
                    left + size * 0.16f,
                    top + size * 0.22f,
                    left + size * 0.84f,
                    top + size * 0.68f,
                )
                canvas.drawRoundRect(display, size * 0.05f, size * 0.05f, stroke)
                canvas.drawLine(left + size * 0.5f, display.bottom, left + size * 0.5f, top + size * 0.8f, stroke)
                canvas.drawLine(left + size * 0.32f, top + size * 0.8f, left + size * 0.68f, top + size * 0.8f, stroke)
                val play = Path().apply {
                    moveTo(left + size * 0.45f, top + size * 0.33f)
                    lineTo(left + size * 0.45f, top + size * 0.57f)
                    lineTo(left + size * 0.66f, top + size * 0.45f)
                    close()
                }
                canvas.drawPath(play, fill)
            }
            EmbeddedGlyphKind.Glass -> {
                val bowl = Path().apply {
                    moveTo(left + size * 0.26f, top + size * 0.2f)
                    lineTo(left + size * 0.74f, top + size * 0.2f)
                    lineTo(left + size * 0.66f, top + size * 0.51f)
                    cubicTo(
                        left + size * 0.62f,
                        top + size * 0.63f,
                        left + size * 0.38f,
                        top + size * 0.63f,
                        left + size * 0.34f,
                        top + size * 0.51f,
                    )
                    close()
                }
                canvas.drawPath(bowl, stroke)
                canvas.drawLine(left + size * 0.27f, top + size * 0.36f, left + size * 0.73f, top + size * 0.36f, stroke)
                canvas.drawLine(cx, top + size * 0.62f, cx, top + size * 0.8f, stroke)
                canvas.drawLine(left + size * 0.34f, top + size * 0.8f, left + size * 0.66f, top + size * 0.8f, stroke)
            }
            EmbeddedGlyphKind.LyricsBlur -> {
                val document = RectF(
                    left + size * 0.2f,
                    top + size * 0.16f,
                    left + size * 0.63f,
                    top + size * 0.77f,
                )
                canvas.drawRoundRect(document, size * 0.04f, size * 0.04f, stroke)
                canvas.drawLine(left + size * 0.48f, top + size * 0.16f, left + size * 0.63f, top + size * 0.31f, stroke)
                canvas.drawLine(left + size * 0.3f, top + size * 0.39f, left + size * 0.53f, top + size * 0.39f, stroke)
                canvas.drawLine(left + size * 0.3f, top + size * 0.51f, left + size * 0.48f, top + size * 0.51f, stroke)
                canvas.drawCircle(left + size * 0.68f, top + size * 0.66f, size * 0.16f, stroke)
                canvas.drawLine(left + size * 0.8f, top + size * 0.78f, left + size * 0.89f, top + size * 0.87f, stroke)
            }
            EmbeddedGlyphKind.Document -> {
                val document = RectF(
                    left + size * 0.23f,
                    top + size * 0.14f,
                    left + size * 0.7f,
                    top + size * 0.82f,
                )
                canvas.drawRoundRect(document, size * 0.05f, size * 0.05f, stroke)
                canvas.drawLine(left + size * 0.51f, top + size * 0.14f, left + size * 0.7f, top + size * 0.33f, stroke)
                canvas.drawLine(left + size * 0.34f, top + size * 0.48f, left + size * 0.6f, top + size * 0.48f, stroke)
                canvas.drawLine(left + size * 0.34f, top + size * 0.62f, left + size * 0.6f, top + size * 0.62f, stroke)
            }
            EmbeddedGlyphKind.Translate -> {
                canvas.drawLine(left + size * 0.2f, top + size * 0.28f, left + size * 0.58f, top + size * 0.28f, stroke)
                canvas.drawLine(left + size * 0.39f, top + size * 0.16f, left + size * 0.39f, top + size * 0.31f, stroke)
                canvas.drawLine(left + size * 0.24f, top + size * 0.45f, left + size * 0.54f, top + size * 0.72f, stroke)
                canvas.drawLine(left + size * 0.54f, top + size * 0.45f, left + size * 0.24f, top + size * 0.72f, stroke)
                canvas.drawLine(left + size * 0.67f, top + size * 0.78f, left + size * 0.79f, top + size * 0.38f, stroke)
                canvas.drawLine(left + size * 0.91f, top + size * 0.78f, left + size * 0.79f, top + size * 0.38f, stroke)
                canvas.drawLine(left + size * 0.72f, top + size * 0.62f, left + size * 0.86f, top + size * 0.62f, stroke)
            }
            EmbeddedGlyphKind.Refresh -> {
                val oval = RectF(
                    left + size * 0.2f,
                    top + size * 0.2f,
                    left + size * 0.8f,
                    top + size * 0.8f,
                )
                canvas.drawArc(oval, 35f, 205f, false, stroke)
                canvas.drawArc(oval, 215f, 205f, false, stroke)
                val upperArrow = Path().apply {
                    moveTo(left + size * 0.68f, top + size * 0.17f)
                    lineTo(left + size * 0.82f, top + size * 0.2f)
                    lineTo(left + size * 0.75f, top + size * 0.32f)
                    close()
                }
                val lowerArrow = Path().apply {
                    moveTo(left + size * 0.32f, top + size * 0.83f)
                    lineTo(left + size * 0.18f, top + size * 0.8f)
                    lineTo(left + size * 0.25f, top + size * 0.68f)
                    close()
                }
                canvas.drawPath(upperArrow, fill)
                canvas.drawPath(lowerArrow, fill)
            }
            EmbeddedGlyphKind.AddCircle -> {
                val actionCenterY = top + size * 0.555f
                canvas.drawCircle(cx, actionCenterY, size * 0.3f, stroke)
                canvas.drawLine(
                    cx - size * 0.15f,
                    actionCenterY,
                    cx + size * 0.15f,
                    actionCenterY,
                    stroke,
                )
                canvas.drawLine(
                    cx,
                    actionCenterY - size * 0.15f,
                    cx,
                    actionCenterY + size * 0.15f,
                    stroke,
                )
            }
            EmbeddedGlyphKind.TtmlDocument -> {
                val document = RectF(
                    left + size * 0.22f,
                    top + size * 0.24f,
                    left + size * 0.74f,
                    top + size * 0.9f,
                )
                canvas.drawRoundRect(document, size * 0.045f, size * 0.045f, stroke)
                canvas.drawLine(left + size * 0.53f, top + size * 0.24f, left + size * 0.74f, top + size * 0.45f, stroke)
                canvas.drawLine(left + size * 0.38f, top + size * 0.55f, left + size * 0.29f, top + size * 0.64f, stroke)
                canvas.drawLine(left + size * 0.38f, top + size * 0.73f, left + size * 0.29f, top + size * 0.64f, stroke)
                canvas.drawLine(left + size * 0.55f, top + size * 0.53f, left + size * 0.47f, top + size * 0.75f, stroke)
                canvas.drawLine(left + size * 0.64f, top + size * 0.55f, left + size * 0.72f, top + size * 0.64f, stroke)
                canvas.drawLine(left + size * 0.64f, top + size * 0.73f, left + size * 0.72f, top + size * 0.64f, stroke)
            }
            EmbeddedGlyphKind.Search -> {
                canvas.drawCircle(left + size * 0.44f, top + size * 0.44f, size * 0.22f, stroke)
                canvas.drawLine(left + size * 0.6f, top + size * 0.6f, left + size * 0.82f, top + size * 0.82f, stroke)
            }
            EmbeddedGlyphKind.Edit -> {
                val pencil = Path().apply {
                    moveTo(left + size * 0.25f, top + size * 0.69f)
                    lineTo(left + size * 0.25f, top + size * 0.53f)
                    lineTo(left + size * 0.67f, top + size * 0.21f)
                    lineTo(left + size * 0.79f, top + size * 0.33f)
                    lineTo(left + size * 0.47f, top + size * 0.75f)
                    close()
                }
                canvas.drawPath(pencil, stroke)
                canvas.drawLine(left + size * 0.61f, top + size * 0.27f, left + size * 0.73f, top + size * 0.39f, stroke)
            }
            EmbeddedGlyphKind.Delete -> {
                val trash = RectF(
                    left + size * 0.3f,
                    top + size * 0.32f,
                    left + size * 0.7f,
                    top + size * 0.78f,
                )
                canvas.drawRoundRect(trash, size * 0.03f, size * 0.03f, stroke)
                canvas.drawLine(left + size * 0.24f, top + size * 0.25f, left + size * 0.76f, top + size * 0.25f, stroke)
                canvas.drawLine(left + size * 0.43f, top + size * 0.18f, left + size * 0.57f, top + size * 0.18f, stroke)
                canvas.drawLine(left + size * 0.43f, top + size * 0.42f, left + size * 0.43f, top + size * 0.68f, stroke)
                canvas.drawLine(left + size * 0.57f, top + size * 0.42f, left + size * 0.57f, top + size * 0.68f, stroke)
            }
            EmbeddedGlyphKind.BackArrow -> {
                val tipX = left + size * 0.3f
                val endX = left + size * 0.7f
                canvas.drawLine(endX, cy, tipX, cy, stroke)
                canvas.drawLine(tipX, cy, left + size * 0.49f, top + size * 0.31f, stroke)
                canvas.drawLine(tipX, cy, left + size * 0.49f, top + size * 0.69f, stroke)
            }
            EmbeddedGlyphKind.ChevronRight -> {
                canvas.drawLine(left + size * 0.4f, top + size * 0.24f, left + size * 0.62f, top + size * 0.5f, stroke)
                canvas.drawLine(left + size * 0.62f, top + size * 0.5f, left + size * 0.4f, top + size * 0.76f, stroke)
            }
            EmbeddedGlyphKind.MoreVertical -> {
                listOf(0.28f, 0.5f, 0.72f).forEach { fraction ->
                    canvas.drawCircle(cx, top + size * fraction, size * 0.06f, fill)
                }
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        stroke.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private class EmbeddedArrowFallbackDrawable : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
        strokeWidth = 4f
        color = EmbeddedSettingsPalette.primary
    }

    override fun draw(canvas: Canvas) {
        val box = bounds
        val left = box.left + box.width() * 0.28f
        val right = box.left + box.width() * 0.72f
        val center = box.top + box.height() * 0.5f
        val tip = box.left + box.width() * 0.28f
        canvas.drawLine(right, center, tip, center, paint)
        canvas.drawLine(tip, center, box.left + box.width() * 0.48f, box.top + box.height() * 0.28f, paint)
        canvas.drawLine(tip, center, box.left + box.width() * 0.48f, box.bottom - box.height() * 0.28f, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private val EMBEDDED_FONT_MIME_TYPES = arrayOf(
    "font/ttf",
    "font/otf",
    "application/x-font-ttf",
    "application/x-font-opentype",
    "application/vnd.ms-opentype",
)

/** Stable, locale-aware signals for the fixed Apple Music settings surface. */
internal object EmbeddedSettingsTextPolicy {
    private val classNameMarkers = listOf(
        "settings",
        "setting",
        "preferences",
        "preference",
        "accountsettings",
    )
    private val titleMarkers = listOf(
        "settings",
        "preference",
        "设置",
        "通用",
    )

    fun isSettingsClassName(className: String): Boolean {
        val normalized = className.lowercase(Locale.ROOT)
        return classNameMarkers.any(normalized::contains)
    }

    fun isSettingsTitle(text: CharSequence?): Boolean {
        val normalized = text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normalized.isBlank()) return false
        return titleMarkers.any(normalized::contains)
    }

    fun containsSettingsTitle(root: View, ignoredTag: Any? = null): Boolean {
        val pending = ArrayDeque<View>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < MAX_VIEW_SCAN_NODES) {
            val view = pending.removeFirst()
            if (ignoredTag != null && view.tag == ignoredTag) continue
            if (view.visibility != View.VISIBLE || view.alpha <= 0f) continue
            if (view is TextView && isSettingsTitle(view.text)) return true
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    pending.addLast(view.getChildAt(index))
                }
            }
        }
        return false
    }

    private const val MAX_VIEW_SCAN_NODES = 1024
}

/** Matches the fixed PlayerActivity across subclasses and class-loader copies. */
internal class EmbeddedActivityMatcher(
    private val playerActivityClass: Class<*>? = null,
    private val playerActivityName: String = EmbeddedSettingsHost.PLAYER_ACTIVITY_NAME,
) {
    fun roleFor(activity: Activity): EmbeddedHostActivityRole? {
        if (activity.packageName != ModuleConstants.TARGET_PACKAGE) return null
        if (isMainContentActivity(activity)) return EmbeddedHostActivityRole.MainContent
        if (isPlayerActivity(activity)) return EmbeddedHostActivityRole.Player
        if (EmbeddedSettingsTextPolicy.isSettingsClassName(activity.javaClass.name)) {
            return EmbeddedHostActivityRole.Settings
        }
        val decor = activity.window?.decorView
            ?: activity.findViewById<View>(android.R.id.content)
            ?: return null
        return if (EmbeddedSettingsTextPolicy.containsSettingsTitle(decor)) {
            EmbeddedHostActivityRole.Settings
        } else {
            null
        }
    }

    fun isPlayerActivity(activity: Activity): Boolean {
        if (playerActivityClass?.isAssignableFrom(activity.javaClass) == true) return true
        var current: Class<*>? = activity.javaClass
        while (current != null) {
            if (current.name == playerActivityName) return true
            current = current.superclass
        }
        return false
    }

    fun isMainContentActivity(activity: Activity): Boolean {
        var current: Class<*>? = activity.javaClass
        while (current != null) {
            if (current.name == EmbeddedSettingsHost.MAIN_CONTENT_ACTIVITY_NAME) return true
            current = current.superclass
        }
        return false
    }
}

/** Pure lifecycle decisions used by the embedded host and its JVM tests. */
internal enum class EmbeddedSettingsLifecycleAction {
    Ignore,
    Inject,
    AlreadyInjected,
}

/**
 * Tracks only opaque activity identities. It deliberately does not retain
 * Activity instances, views, or dialogs.
 */
internal class EmbeddedSettingsLifecycleState(
    private val targetActivityName: String = EmbeddedSettingsHost.PLAYER_ACTIVITY_NAME,
) {
    private val injectedActivityIds = mutableSetOf<String>()

    fun onActivityResumed(
        activityId: String,
        className: String,
        role: EmbeddedHostActivityRole? = null,
    ): EmbeddedSettingsLifecycleAction {
        val resolvedRole = role ?: when {
            className == targetActivityName -> EmbeddedHostActivityRole.Player
            EmbeddedSettingsTextPolicy.isSettingsClassName(className) -> EmbeddedHostActivityRole.Settings
            else -> null
        }
        if (resolvedRole == null) return EmbeddedSettingsLifecycleAction.Ignore
        return if (injectedActivityIds.add(activityId)) {
            EmbeddedSettingsLifecycleAction.Inject
        } else {
            EmbeddedSettingsLifecycleAction.AlreadyInjected
        }
    }

    fun onActivityDestroyed(activityId: String): Boolean = injectedActivityIds.remove(activityId)

    fun clear() {
        injectedActivityIds.clear()
    }
}

internal enum class EmbeddedSafOperation {
    Font,
    Ttml,
    Backup,
    RestoreOverwrite,
    RestoreKeepExisting,
}

internal data class EmbeddedSafPending(
    val requestCode: Int,
    val operation: EmbeddedSafOperation,
)

internal object EmbeddedSafResult {
    const val RESULT_CANCELED = 0
    const val RESULT_OK = -1
}

internal sealed interface EmbeddedSafRoute {
    data object Ignored : EmbeddedSafRoute

    data class Canceled(
        val operation: EmbeddedSafOperation,
    ) : EmbeddedSafRoute

    data class Selected(
        val operation: EmbeddedSafOperation,
        val uri: String,
    ) : EmbeddedSafRoute
}

/**
 * Owns only the module's pending SAF request. A result for any other request
 * code is ignored and leaves the pending request untouched for the host.
 */
internal class EmbeddedSafResultRouter {
    private var pendingRequest: EmbeddedSafPending? = null

    fun begin(operation: EmbeddedSafOperation): Int {
        val requestCode = when (operation) {
            EmbeddedSafOperation.Font -> REQUEST_PICK_FONT
            EmbeddedSafOperation.Ttml -> REQUEST_PICK_TTML
            EmbeddedSafOperation.Backup -> REQUEST_CREATE_BACKUP
            EmbeddedSafOperation.RestoreOverwrite -> REQUEST_RESTORE_BACKUP
            EmbeddedSafOperation.RestoreKeepExisting -> REQUEST_RESTORE_BACKUP_KEEP
        }
        pendingRequest = EmbeddedSafPending(requestCode, operation)
        return requestCode
    }

    fun pending(): EmbeddedSafPending? = pendingRequest

    fun route(
        requestCode: Int,
        resultCode: Int,
        uri: String?,
    ): EmbeddedSafRoute {
        val pending = pendingRequest ?: return EmbeddedSafRoute.Ignored
        if (pending.requestCode != requestCode) return EmbeddedSafRoute.Ignored

        pendingRequest = null
        return if (resultCode == EmbeddedSafResult.RESULT_OK && !uri.isNullOrBlank()) {
            EmbeddedSafRoute.Selected(pending.operation, uri)
        } else {
            EmbeddedSafRoute.Canceled(pending.operation)
        }
    }

    companion object {
        const val REQUEST_PICK_FONT = 6511
        const val REQUEST_PICK_TTML = 6512
        const val REQUEST_CREATE_BACKUP = 6513
        const val REQUEST_RESTORE_BACKUP = 6514
        const val REQUEST_RESTORE_BACKUP_KEEP = 6515
        val OWN_REQUEST_CODES: Set<Int> = setOf(
            REQUEST_PICK_FONT,
            REQUEST_PICK_TTML,
            REQUEST_CREATE_BACKUP,
            REQUEST_RESTORE_BACKUP,
            REQUEST_RESTORE_BACKUP_KEEP,
        )
    }
}

/** Small facade so the host UI never depends on a particular storage backend. */
internal interface EmbeddedSettingsController {
    fun currentSettings(): ModuleSettings

    fun saveOrdinarySettings(settings: ModuleSettings): Boolean

    fun currentSongDetails(): CurrentSongDetails? = null
    fun lyricsEntries(): List<CustomLyricsEntry> = emptyList()
    fun readLyrics(appleMusicId: Long): String? = null
    /** Reads and validates a SAF TTML document without persisting it. */
    fun readTtml(uri: Uri): String? = null
    fun saveLyrics(draft: CustomLyricsDraft, replacingAppleMusicId: Long? = null): EmbeddedActionResult =
        EmbeddedActionResult.Failed("歌词管理不可用")
    fun saveLyrics(
        draft: CustomLyricsMultiIdDraft,
        replacingAppleMusicIds: List<Long> = emptyList(),
    ): EmbeddedActionResult = EmbeddedActionResult.Failed("歌词管理不可用")
    fun setLyricsEnabled(appleMusicId: Long, enabled: Boolean): EmbeddedActionResult =
        EmbeddedActionResult.Failed("歌词管理不可用")
    fun setLyricsEnabled(appleMusicIds: List<Long>, enabled: Boolean): EmbeddedActionResult =
        EmbeddedActionResult.Failed("歌词管理不可用")
    fun deleteLyrics(appleMusicId: Long): EmbeddedActionResult =
        EmbeddedActionResult.Failed("歌词管理不可用")
    fun deleteLyrics(appleMusicIds: List<Long>): EmbeddedActionResult =
        EmbeddedActionResult.Failed("歌词管理不可用")
    fun importFont(uri: Uri): EmbeddedActionResult = EmbeddedActionResult.Failed("字体导入不可用")
    fun clearFont(): EmbeddedActionResult = EmbeddedActionResult.Failed("字体管理不可用")
    fun importTtml(
        uri: Uri,
        appleMusicId: Long,
        displayName: String,
        replacingAppleMusicId: Long? = null,
    ): EmbeddedActionResult = EmbeddedActionResult.Failed("歌词导入不可用")
    fun backupLyrics(uri: Uri): EmbeddedActionResult = EmbeddedActionResult.Failed("备份不可用")
    fun restoreLyrics(uri: Uri, policy: CustomLyricsRestorePolicy): EmbeddedActionResult =
        EmbeddedActionResult.Failed("恢复不可用")
    fun importOnlineLyrics(
        source: EmbeddedOnlineSource,
        appleMusicId: Long,
        displayName: String,
    ): EmbeddedActionResult = EmbeddedActionResult.Failed("在线导入不可用")

    fun updateLyrics(
        isCancelled: () -> Boolean = { false },
        onProgress: (CustomLyricsUpdateProgress) -> Unit = {},
    ): CustomLyricsUpdateResult = CustomLyricsUpdateResult.Failed("歌词更新不可用")
}

internal class EmbeddedSessionSettingsController(
    private val session: EmbeddedConfigurationSession,
) : EmbeddedSettingsController {
    override fun currentSettings(): ModuleSettings = session.settings()

    override fun saveOrdinarySettings(settings: ModuleSettings): Boolean = session.saveSettings(settings)
}

internal fun interface EmbeddedSafSelectionHandler {
    fun onSelected(operation: EmbeddedSafOperation, uri: Uri)
}

/**
 * Embedded-only settings entry for Apple's PlayerActivity and settings surface.
 *
 * The host is registered through [install] by the embedded bootstrap seam.
 * It uses weak references for the current Activity and Dialog so registering
 * an Application callback cannot keep a destroyed host screen alive.
 */
internal class EmbeddedSettingsHost private constructor(
    private val application: Application,
    private val controller: EmbeddedSettingsController,
    private val safRouter: EmbeddedSafResultRouter,
    private val selectionHandler: EmbeddedSafSelectionHandler,
    private val activityMatcher: EmbeddedActivityMatcher,
) : Application.ActivityLifecycleCallbacks {
    private val lifecycleState = EmbeddedSettingsLifecycleState()
    private var activityReference: WeakReference<Activity>? = null
    private var dialogReference: WeakReference<Dialog>? = null
    private var pageRefresh: (() -> Unit)? = null
    private val customLyricsListState = CustomLyricsListState()
    private var customLyricsSearchQuery = ""
    private var pendingTtmlImport: ((String) -> Unit)? = null
    private var buttonReference: WeakReference<View>? = null
    private var settingsOptionReference: WeakReference<View>? = null
    private var activeActivityId: String? = null
    private var activeActivityRole: EmbeddedHostActivityRole? = null
    private val nativePreferenceActivityIds = mutableSetOf<String>()
    private var nativePreferenceFragmentReference: WeakReference<Any>? = null
    private var observedMainContentActivity: WeakReference<Activity>? = null
    private var observedMainContentDecor: WeakReference<View>? = null
    private var mainContentLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ampp-embedded-settings").apply { isDaemon = true }
    }

    @Volatile
    private var registered = true

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (registered && activityMatcher.isMainContentActivity(activity)) {
            installMainContentLayoutObserver(activity)
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        if (!registered) return
        val role = activityMatcher.roleFor(activity) ?: return
        val action = lifecycleState.onActivityResumed(
            activityId = activityKey(activity),
            className = activity.javaClass.name,
            role = role,
        )
        if (action == EmbeddedSettingsLifecycleAction.Ignore) return

        val previousActivity = activityReference?.get()
        if (previousActivity !== activity) {
            removeInjectedViews(previousActivity)
            dismissDialog()
        }
        activityReference = WeakReference(activity)
        activeActivityId = activityKey(activity)
        activeActivityRole = role
        when (role) {
            EmbeddedHostActivityRole.Player -> injectButtonIfNeeded(activity)
            EmbeddedHostActivityRole.MainContent -> installMainContentLayoutObserver(activity)
            EmbeddedHostActivityRole.Settings -> injectSettingsOptionIfNeeded(activity)
        }
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        val destroyedActivityId = activityKey(activity)
        val wasCurrent = activeActivityId == destroyedActivityId
        lifecycleState.onActivityDestroyed(destroyedActivityId)
        if (nativePreferenceActivityIds.remove(destroyedActivityId)) {
            nativePreferenceFragmentReference = null
        }
        if (observedMainContentActivity?.get() === activity) {
            removeMainContentLayoutObserver()
        }
        removeInjectedViews(activity)
        if (!wasCurrent) return
        dismissDialog()
        activityReference = null
        activeActivityId = null
        activeActivityRole = null
    }

    /**
     * Call from the embedding Activity result seam. Returning false means the
     * result belongs to the host and must continue through its normal path.
     */
    fun onActivityResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ): Boolean {
        if (activityReference?.get() !== activity || activeActivityRole == null) return false
        return when (val route = safRouter.route(requestCode, resultCode, data?.dataString)) {
            EmbeddedSafRoute.Ignored -> false
            is EmbeddedSafRoute.Canceled -> {
                if (route.operation == EmbeddedSafOperation.Ttml) pendingTtmlImport = null
                currentActivity()?.let { activity ->
                    Toast.makeText(activity, "未选择文件", Toast.LENGTH_SHORT).show()
                }
                true
            }
            is EmbeddedSafRoute.Selected -> {
                selectionHandler.onSelected(route.operation, Uri.parse(route.uri))
                handleSafSelection(route.operation, Uri.parse(route.uri))
                true
            }
        }
    }

    /**
     * Verified 6.5.1/6.5.2 seam: SettingsFragment is an AndroidX
     * PreferenceFragment hosted by MainContentActivity. This keeps the option
     * inside Apple's native settings list; the View row remains a fallback
     * for future host layouts or when a repacker changes the Preference
     * implementation.
     */
    fun onSettingsPreferencesReady(fragment: Any, activity: Activity) {
        if (!registered || activity.packageName != ModuleConstants.TARGET_PACKAGE) return
        val activityId = activityKey(activity)
        val previousActivity = activityReference?.get()
        if (previousActivity !== activity) removeInjectedViews(previousActivity)
        activityReference = WeakReference(activity)
        activeActivityId = activityId
        activeActivityRole = EmbeddedHostActivityRole.Settings
        if (activityMatcher.isMainContentActivity(activity)) {
            installMainContentLayoutObserver(activity)
        }

        val nativePreferenceAdded = runCatching {
            injectNativeSettingsPreference(fragment, activity)
        }.getOrDefault(false)
        if (nativePreferenceAdded) {
            nativePreferenceActivityIds.add(activityId)
            nativePreferenceFragmentReference = WeakReference(fragment)
            removeSettingsOption(activity)
            // The setPreferences seam can run after PreferenceFragmentCompat
            // has already attached its RecyclerView adapter.  Always give
            // that adapter a late refresh so a newly-added native row is
            // reflected in the visible list.
            scheduleNativePreferenceRefresh(activity, fragmentView(fragment))
        } else {
            nativePreferenceActivityIds.remove(activityId)
            nativePreferenceFragmentReference = null
            scheduleSettingsOptionFallback(activity, fragmentView(fragment))
        }
    }

    fun onSettingsFragmentResumed(fragment: Any, activity: Activity) {
        if (!registered || activity.packageName != ModuleConstants.TARGET_PACKAGE) return
        val activityId = activityKey(activity)
        lifecycleState.onActivityResumed(
            activityId = activityId,
            className = activity.javaClass.name,
            role = EmbeddedHostActivityRole.Settings,
        )
        val previousActivity = activityReference?.get()
        if (previousActivity !== activity) removeInjectedViews(previousActivity)
        activityReference = WeakReference(activity)
        activeActivityId = activityId
        activeActivityRole = EmbeddedHostActivityRole.Settings
        if (activityMatcher.isMainContentActivity(activity)) {
            installMainContentLayoutObserver(activity)
        }
        removeOverlay(activity)

        val nativePreferenceAdded = runCatching {
            injectNativeSettingsPreference(fragment, activity)
        }.getOrDefault(false)
        if (nativePreferenceAdded) {
            nativePreferenceActivityIds.add(activityId)
            nativePreferenceFragmentReference = WeakReference(fragment)
            removeSettingsOption(activity)
            // Refresh even when the early seam succeeded: on 6.5.1 the
            // adapter may already have been attached when r1() is invoked.
            scheduleNativePreferenceRefresh(activity, fragmentView(fragment))
        } else {
            nativePreferenceActivityIds.remove(activityId)
            nativePreferenceFragmentReference = null
            scheduleSettingsOptionFallback(activity, fragmentView(fragment))
        }
    }

    /**
     * The fixed settings Fragment can rebuild its view without resuming the
     * host Activity. Try the native Preference before the adapter is attached;
     * if that seam is not ready yet, keep a visible list-container fallback.
     */
    fun onSettingsFragmentViewCreated(fragment: Any, activity: Activity, view: View?) {
        if (!registered || activity.packageName != ModuleConstants.TARGET_PACKAGE) return
        val activityId = activityKey(activity)
        lifecycleState.onActivityResumed(
            activityId = activityId,
            className = activity.javaClass.name,
            role = EmbeddedHostActivityRole.Settings,
        )
        val previousActivity = activityReference?.get()
        if (previousActivity !== activity) removeInjectedViews(previousActivity)
        activityReference = WeakReference(activity)
        activeActivityId = activityId
        activeActivityRole = EmbeddedHostActivityRole.Settings
        if (activityMatcher.isMainContentActivity(activity)) {
            installMainContentLayoutObserver(activity)
        }
        nativePreferenceActivityIds.remove(activityId)
        nativePreferenceFragmentReference = null
        removeOverlay(activity)
        val nativePreferenceAdded = runCatching {
            injectNativeSettingsPreference(fragment, activity)
        }.getOrDefault(false)
        if (nativePreferenceAdded) {
            nativePreferenceActivityIds.add(activityId)
            nativePreferenceFragmentReference = WeakReference(fragment)
            removeSettingsOption(activity)
            scheduleNativePreferenceRefresh(activity, view as? ViewGroup)
        } else {
            scheduleSettingsOptionFallback(activity, view as? ViewGroup)
        }
        view?.post {
            if (registered && activityReference?.get() === activity) {
                if (
                    nativePreferenceActivityIds.contains(activityId) &&
                    nativePreferenceFragmentReference?.get() != null
                ) {
                    removeSettingsOption(activity)
                }
            }
        }
    }

    private fun scheduleSettingsOptionFallback(
        activity: Activity,
        preferredRoot: ViewGroup?,
    ) {
        mainHandler.postDelayed({
            if (!registered || activityReference?.get() !== activity) return@postDelayed
            if (!nativePreferenceActivityIds.contains(activityKey(activity))) {
                nativePreferenceActivityIds.remove(activityKey(activity))
                nativePreferenceFragmentReference = null
                injectSettingsOptionIfNeeded(activity, preferredRoot)
            }
        }, NATIVE_PREFERENCE_FALLBACK_DELAY_MS)
    }

    private fun scheduleNativePreferenceRefresh(activity: Activity, root: ViewGroup?) {
        mainHandler.postDelayed({
            if (registered && activityReference?.get() === activity) {
                refreshNativePreferenceAdapter(root)
            }
        }, NATIVE_PREFERENCE_FALLBACK_DELAY_MS)
    }

    private fun refreshNativePreferenceAdapter(root: ViewGroup?) {
        val recycler = root?.let(::findRecyclerView) ?: return
        runCatching {
            val adapter = recycler.javaClass.getMethod("getAdapter").invoke(recycler) ?: return
            adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter)
        }
    }

    fun uninstall() {
        if (!registered) return
        registered = false
        application.unregisterActivityLifecycleCallbacks(this)
        removeMainContentLayoutObserver()
        removeInjectedViews(activityReference?.get())
        dismissDialog()
        activityReference = null
        activeActivityId = null
        activeActivityRole = null
        nativePreferenceActivityIds.clear()
        lifecycleState.clear()
        worker.shutdownNow()
    }

    /**
     * MainContentActivity keeps the same Activity while its settings Fragment
     * is swapped in. Observe decor changes so the View fallback does not rely
     * on a second Activity resume callback.
     */
    private fun installMainContentLayoutObserver(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        if (observedMainContentActivity?.get() === activity && mainContentLayoutListener != null) {
            onMainContentLayout(activity)
            return
        }

        removeMainContentLayoutObserver()
        val activityReference = WeakReference(activity)
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            activityReference.get()?.let(::onMainContentLayout)
        }
        runCatching { decor.viewTreeObserver.addOnGlobalLayoutListener(listener) }
            .onFailure { return }
        observedMainContentActivity = WeakReference(activity)
        observedMainContentDecor = WeakReference(decor)
        mainContentLayoutListener = listener
        decor.post { activityReference.get()?.let(::onMainContentLayout) }
    }

    private fun removeMainContentLayoutObserver() {
        val decor = observedMainContentDecor?.get()
        val listener = mainContentLayoutListener
        if (decor != null && listener != null) {
            runCatching { decor.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
        }
        observedMainContentActivity = null
        observedMainContentDecor = null
        mainContentLayoutListener = null
    }

    private fun onMainContentLayout(activity: Activity) {
        if (!registered || activityReference?.get() !== activity) return
        val decor = activity.window?.decorView ?: return
        val activityId = activityKey(activity)
        if (!EmbeddedSettingsTextPolicy.containsSettingsTitle(decor, SETTINGS_OPTION_TAG)) {
            nativePreferenceActivityIds.remove(activityId)
            nativePreferenceFragmentReference = null
            if (activeActivityRole == EmbeddedHostActivityRole.Settings) {
                activeActivityRole = EmbeddedHostActivityRole.MainContent
                removeSettingsOption(activity)
                dismissDialog()
            }
            return
        }

        activeActivityRole = EmbeddedHostActivityRole.Settings
        removeOverlay(activity)
        if (
            nativePreferenceActivityIds.contains(activityId) &&
            nativePreferenceFragmentReference?.get() != null
        ) {
            removeSettingsOption(activity)
        } else {
            nativePreferenceActivityIds.remove(activityId)
            nativePreferenceFragmentReference = null
            injectSettingsOptionIfNeeded(activity)
        }
    }

    private fun injectButtonIfNeeded(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val existing: View? = content.findViewWithTag<View>(FLOATING_BUTTON_TAG)
        if (existing != null) {
            buttonReference = WeakReference<View>(existing)
            return
        }

        val density = activity.resources.displayMetrics.density
        val button = Button(activity).apply {
            tag = FLOATING_BUTTON_TAG
            text = "AM"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            contentDescription = "打开 AM++ 设置"
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(EmbeddedSettingsPalette.primary)
            }
            elevation = 4f * density
            setOnClickListener { showSettingsDialog(activity) }
        }
        val size = (56f * density).toInt()
        val margin = (16f * density).toInt()
        val layoutParams = if (content is FrameLayout) {
            FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                setMargins(margin, margin, margin, margin)
            }
        } else {
            ViewGroup.LayoutParams(size, size)
        }
        content.addView(button, layoutParams)
        buttonReference = WeakReference<View>(button)
    }

    private fun injectSettingsOptionIfNeeded(activity: Activity, preferredRoot: ViewGroup? = null) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val decor = activity.window?.decorView as? ViewGroup
        if (content == null && decor == null) return
        val existing = deduplicateTaggedSettingsOptions(activity)
        if (existing != null) {
            settingsOptionReference = WeakReference(existing)
            return
        }

        val option = LinearLayout(activity).apply {
            tag = SETTINGS_OPTION_TAG
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = dp(activity, 64)
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 12))
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "打开 AM++ 模块设置"
            setOnClickListener { showSettingsDialog(activity) }
            addView(TextView(activity).apply {
                text = "AM++ 模块设置"
                textSize = 16f
                setTextColor(EmbeddedSettingsPalette.onSurface)
                setSingleLine(false)
            }, matchWidthWrapContent())
            addView(TextView(activity).apply {
                text = "字体、歌词与模块功能"
                textSize = 13f
                setTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
                setSingleLine(false)
            }, matchWidthWrapContent())
        }

        val container = preferredRoot?.let(::findSettingsListOverlayContainer)
            ?: decor?.let(::findSettingsListOverlayContainer)
            ?: content?.let(::findSettingsInsertionContainer)
        if (container != null) {
            val layoutParams: ViewGroup.LayoutParams = if (container is FrameLayout) {
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.BOTTOM
                    setMargins(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8))
                }
            } else {
                matchWidthWrapContent()
            }
            runCatching { container.addView(option, layoutParams) }
                .onSuccess {
                    settingsOptionReference = WeakReference<View>(option)
                }
            return
        }

        // A RecyclerView cannot accept arbitrary children. Keep a visible,
        // non-invasive fallback in the host content frame for such layouts.
        val fallbackRoot = when {
            content is FrameLayout -> content
            decor is FrameLayout -> decor
            else -> null
        }
        if (fallbackRoot != null) {
            val layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM
                setMargins(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
            }
            runCatching { fallbackRoot.addView(option, layoutParams) }
                .onSuccess {
                    settingsOptionReference = WeakReference<View>(option)
                }
        }
    }

    /**
     * Fallback rows can be requested by more than one fragment lifecycle
     * callback. Walk the host tree once, keep the first row in visual order,
     * and remove every later copy before another row is created.
     */
    private fun deduplicateTaggedSettingsOptions(activity: Activity): View? {
        val matches = findTaggedViews(activity, SETTINGS_OPTION_TAG)
        val keeper = matches.firstOrNull()
        matches.drop(1).forEach { duplicate ->
            (duplicate.parent as? ViewGroup)?.removeView(duplicate)
        }
        if (keeper != null) settingsOptionReference = WeakReference(keeper)
        return keeper
    }

    private fun fragmentView(fragment: Any): ViewGroup? = runCatching {
        ModernXposedRuntime.callMethod(fragment, "getView") as? ViewGroup
    }.getOrNull()

    private fun findSettingsListOverlayContainer(root: View): ViewGroup? {
        val pending = ArrayDeque<View>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 1024) {
            val view = pending.removeFirst()
            if (view is ViewGroup && isRecyclerView(view)) {
                return view.parent as? ViewGroup
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    pending.addLast(view.getChildAt(index))
                }
            }
        }
        return null
    }

    private fun findRecyclerView(root: View): ViewGroup? {
        val pending = ArrayDeque<View>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 1024) {
            val view = pending.removeFirst()
            if (view is ViewGroup && isRecyclerView(view)) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    pending.addLast(view.getChildAt(index))
                }
            }
        }
        return null
    }

    private fun injectNativeSettingsPreference(fragment: Any, activity: Activity): Boolean {
        val classLoader = fragment.javaClass.classLoader ?: activity.javaClass.classLoader ?: return false
        val preferenceClass = Class.forName(
            "androidx.preference.Preference",
            false,
            classLoader,
        )
        val key = NATIVE_SETTINGS_PREFERENCE_KEY
        val screen = findNativePreferenceScreen(fragment)
        val screenMatches = screen?.let {
            findNativePreferencesByKey(it, preferenceClass, key)
        }.orEmpty()
        val existing = screenMatches.firstOrNull() ?: runCatching {
            // AndroidX 6.5.1/6.5.2 maps PreferenceFragmentCompat.findPreference()
            // to t0(String); keep this as a fallback for repacked builds where
            // the PreferenceScreen field is not directly discoverable.
            ModernXposedRuntime.callMethod(fragment, "t0", key)
        }.getOrNull()
        if (existing != null) {
            val keeper = screen?.let {
                removeDuplicateNativePreferences(it, preferenceClass, key, existing)
            } ?: existing
            if (!installNativePreferenceClick(preferenceClass, keeper, activity)) return false
            return hasNativePreferenceClick(preferenceClass, keeper)
        }

        val preference = preferenceClass
            .getConstructor(Context::class.java)
            .newInstance(activity)
        val keyWasSet = runCatching {
            preferenceClass.getDeclaredField("x").apply { isAccessible = true }.set(preference, key)
            true
        }.getOrDefault(false)
        if (!keyWasSet) {
            return false
        }
        // On the verified 6.5.1/6.5.2 AndroidX builds, K is setTitle and J is
        // setSummary (J rejects a SummaryProvider, which distinguishes them).
        preferenceClass.getDeclaredMethod("K", CharSequence::class.java)
            .apply { isAccessible = true }
            .invoke(preference, "AM++ 模块设置")
        preferenceClass.getDeclaredMethod("J", CharSequence::class.java)
            .apply { isAccessible = true }
            .invoke(preference, "字体、歌词与模块功能")
        if (!installNativePreferenceClick(preferenceClass, preference, activity)) return false

        val targetScreen = screen ?: return false
        // AndroidX 6.5.1/6.5.2 maps PreferenceGroup.P() to addPreference(); S()
        // is the corresponding remove path. Add once, then normalize the
        // whole screen so repeated lifecycle callbacks cannot accumulate rows.
        ModernXposedRuntime.callMethod(targetScreen, "P", preference)
        val keeper = removeDuplicateNativePreferences(
            targetScreen,
            preferenceClass,
            key,
            preference,
        ) ?: preference
        if (keeper !== preference) {
            installNativePreferenceClick(preferenceClass, keeper, activity)
        }
        return hasNativePreferenceClick(preferenceClass, keeper)
    }

    private fun findNativePreferencesByKey(
        screen: Any,
        preferenceClass: Class<*>,
        key: String,
    ): List<Any> {
        val accessors = findNativePreferenceGroupAccessors(screen, preferenceClass)
            ?: return findNativePreferencesInBackingList(screen, preferenceClass, key)
        val count = runCatching {
            accessors.count.apply { isAccessible = true }.invoke(screen) as? Int
        }.getOrNull()?.coerceIn(0, MAX_NATIVE_PREFERENCE_SCAN)
            ?: return findNativePreferencesInBackingList(screen, preferenceClass, key)
        val matches = ArrayList<Any>()
        for (index in 0 until count) {
            val item = runCatching {
                accessors.itemAt.apply { isAccessible = true }.invoke(screen, index)
            }.getOrNull() ?: continue
            if (preferenceClass.isInstance(item) && nativePreferenceKey(item) == key) {
                matches += item
            }
        }
        return matches
    }

    private fun findNativePreferencesInBackingList(
        screen: Any,
        preferenceClass: Class<*>,
        key: String,
    ): List<Any> {
        var current: Class<*>? = screen.javaClass
        while (current != null) {
            for (field in current.declaredFields) {
                if (!java.util.List::class.java.isAssignableFrom(field.type)) continue
                val list = runCatching {
                    field.apply { isAccessible = true }.get(screen) as? List<*>
                }.getOrNull() ?: continue
                val matches = list.filterIsInstance<Any>().filter {
                    preferenceClass.isInstance(it) && nativePreferenceKey(it) == key
                }
                if (matches.isNotEmpty()) return matches
            }
            current = current.superclass
        }
        return emptyList()
    }

    /** Keep the earliest matching Preference and remove every later duplicate. */
    private fun removeDuplicateNativePreferences(
        screen: Any,
        preferenceClass: Class<*>,
        key: String,
        preferred: Any? = null,
    ): Any? {
        val accessors = findNativePreferenceGroupAccessors(screen, preferenceClass)
        val matches = findNativePreferencesByKey(screen, preferenceClass, key)
        if (matches.isEmpty()) return preferred
        val keeper = matches.firstOrNull { it === preferred } ?: matches.first()
        matches.forEach { candidate ->
            if (candidate === keeper) return@forEach
            val removed = accessors?.remove?.let { removeMethod ->
                runCatching {
                    removeMethod.apply { isAccessible = true }.invoke(screen, candidate) as? Boolean
                }.getOrNull() == true
            } == true
            if (!removed) removeNativePreferenceFromBackingList(screen, candidate)
        }
        return keeper
    }

    private fun findNativePreferenceGroupAccessors(
        screen: Any,
        preferenceClass: Class<*>,
    ): NativePreferenceGroupAccessors? {
        val hierarchy = buildList {
            var current: Class<*>? = screen.javaClass
            while (current != null) {
                add(current)
                current = current.superclass
            }
        }
        val orderedTypes = (hierarchy.filter { it.name.endsWith(".PreferenceGroup") } + hierarchy)
            .distinct()
        for (type in orderedTypes) {
            if (!type.name.startsWith("androidx.preference.")) continue
            val methods = type.declaredMethods.toList()
            val count = methods.firstOrNull {
                it.parameterTypes.isEmpty() && it.returnType == Int::class.javaPrimitiveType
            } ?: continue
            val itemAt = methods.firstOrNull {
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) &&
                    (preferenceClass.isAssignableFrom(it.returnType) || it.returnType == Any::class.java)
            } ?: continue
            val remove = methods.firstOrNull {
                it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0].isAssignableFrom(preferenceClass) ||
                        preferenceClass.isAssignableFrom(it.parameterTypes[0])) &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            return NativePreferenceGroupAccessors(count, itemAt, remove)
        }
        return null
    }

    private fun nativePreferenceKey(preference: Any): String? {
        var current: Class<*>? = preference.javaClass
        while (current != null) {
            val field = current.declaredFields.firstOrNull {
                it.name == "x" && it.type == String::class.java
            }
            if (field != null) {
                return runCatching {
                    field.apply { isAccessible = true }.get(preference) as? String
                }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    private fun removeNativePreferenceFromBackingList(screen: Any, target: Any): Boolean {
        var current: Class<*>? = screen.javaClass
        while (current != null) {
            for (field in current.declaredFields) {
                if (!java.util.List::class.java.isAssignableFrom(field.type)) continue
                val list = runCatching {
                    field.apply { isAccessible = true }.get(screen) as? MutableList<Any?>
                }.getOrNull() ?: continue
                if (list.none { it === target }) continue
                if (runCatching { list.remove(target) }.getOrDefault(false)) return true
            }
            current = current.superclass
        }
        return false
    }

    private fun hasNativePreferenceClick(preferenceClass: Class<*>, preference: Any): Boolean =
        runCatching {
            findNativePreferenceClickField(preferenceClass)
                ?.apply { isAccessible = true }
                ?.get(preference) != null
        }.getOrDefault(false)

    private fun installNativePreferenceClick(
        preferenceClass: Class<*>,
        preference: Any,
        activity: Activity,
    ): Boolean {
        val clickField = findNativePreferenceClickField(preferenceClass) ?: return false
        val clickInterface = clickField.type
        return runCatching {
            val listener = Proxy.newProxyInstance(
                clickInterface.classLoader,
                arrayOf(clickInterface),
                InvocationHandler { _, method, _ ->
                    if (method.returnType == Boolean::class.javaPrimitiveType) {
                        mainHandler.post { showSettingsDialog(activity) }
                        true
                    } else {
                        null
                    }
                },
            )
            clickField.apply { isAccessible = true }.set(preference, listener)
            true
        }.getOrDefault(false)
    }

    private fun findNativePreferenceClickField(preferenceClass: Class<*>): java.lang.reflect.Field? {
        val clickInterface = preferenceClass.declaredClasses.firstOrNull { nested ->
            nested.isInterface && nested.declaredMethods.any { method ->
                method.parameterTypes.contentEquals(arrayOf(preferenceClass)) &&
                    method.returnType == Boolean::class.javaPrimitiveType
            }
        } ?: return null
        return preferenceClass.declaredFields.firstOrNull { field ->
            field.type == clickInterface
        }
    }

    private fun findNativePreferenceScreen(fragment: Any): Any? {
        var fragmentType: Class<*>? = fragment.javaClass
        while (fragmentType != null) {
            val manager = fragmentType.declaredFields.asSequence()
                .mapNotNull { field ->
                    runCatching {
                        field.apply { isAccessible = true }.get(fragment)
                    }.getOrNull()
                }
                .firstOrNull { candidate ->
                    candidate.javaClass.declaredFields.any { field ->
                        field.type.name == "androidx.preference.PreferenceScreen"
                    }
                }
            if (manager != null) {
                var managerType: Class<*>? = manager.javaClass
                while (managerType != null) {
                    val screen = managerType.declaredFields.asSequence()
                        .filter { it.type.name == "androidx.preference.PreferenceScreen" }
                        .mapNotNull { field ->
                            runCatching {
                                field.apply { isAccessible = true }.get(manager)
                            }.getOrNull()
                        }
                        .firstOrNull()
                    if (screen != null) return screen
                    managerType = managerType.superclass
                }
            }
            fragmentType = fragmentType.superclass
        }
        return null
    }

    private fun findSettingsInsertionContainer(root: ViewGroup): ViewGroup? {
        if (isRecyclerView(root)) return null
        if (isScrollView(root)) {
            val child = root.getChildAt(0) as? ViewGroup ?: return null
            return findSettingsInsertionContainer(child) ?: child
        }
        if (root is LinearLayout) return root
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index) as? ViewGroup ?: continue
            findSettingsInsertionContainer(child)?.let { return it }
        }
        return null
    }

    private fun isScrollView(view: ViewGroup): Boolean =
        view is ScrollView || view.javaClass.name.endsWith("NestedScrollView")

    private fun isRecyclerView(view: ViewGroup): Boolean =
        view.javaClass.name.contains("RecyclerView")

    private fun showSettingsDialog(activity: Activity) {
        val currentDialog = dialogReference?.get()
        if (currentDialog?.isShowing == true) return

        var draft = runCatching { controller.currentSettings() }.getOrElse {
            Toast.makeText(activity, "无法读取 AM++ 设置", Toast.LENGTH_SHORT).show()
            return
        }
        var page = EmbeddedSettingsPage.MAIN
        var dialogReady = false
        lateinit var dialog: AlertDialog

        val panelBackground = GradientDrawable().apply {
            setColor(EmbeddedSettingsPalette.pageBackground)
            cornerRadius = embeddedCardCornerRadius(activity)
        }
        val pageHost = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val hostInset = dp(activity, if (isEmbeddedPhone(activity)) 0 else 8)
            setPadding(hostInset, 0, hostInset, 0)
            setBackgroundColor(EmbeddedSettingsPalette.pageBackground)
        }
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = embeddedTopBarHeight(activity)
            val phoneHeaderInset = dp(activity, if (isEmbeddedPhone(activity)) 8 else 0)
            setPadding(phoneHeaderInset, 0, phoneHeaderInset, 0)
        }
        val backButton = ImageView(activity).apply {
            setImageDrawable(
                embeddedSvgDrawable(EmbeddedSvgIcon.Back) ?: EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.BackArrow,
                    EmbeddedSettingsPalette.primary,
                    strokeWidthFraction = 0.055f,
                ),
            )
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "返回"
            isClickable = true
            isFocusable = true
            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
        }
        val moduleIcon = ImageView(activity).apply {
            setImageDrawable(EmbeddedAmppBrandDrawable())
            contentDescription = "AM++"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(0, 0, 0, 0)
        }
        val pageTitle = TextView(activity).apply {
            textSize = embeddedTextSize(activity, 19f, 18f)
            setTextColor(EmbeddedSettingsPalette.onSurface)
            setTypeface(typeface, if (isEmbeddedPhone(activity)) Typeface.BOLD else Typeface.NORMAL)
            setSingleLine(false)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val saveButton = TextView(activity).apply {
            text = "保存"
            textSize = embeddedTextSize(activity, 15f, 14f)
            gravity = Gravity.CENTER
            setTextColor(EmbeddedSettingsPalette.primary)
            isClickable = true
            isFocusable = true
            setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 8), dp(activity, 8))
            contentDescription = "保存 AM++ 设置"
        }
        topBar.addView(backButton, LinearLayout.LayoutParams(dp(activity, 44), embeddedTopBarHeight(activity)))
        topBar.addView(moduleIcon, LinearLayout.LayoutParams(embeddedHeaderIconSize(activity), embeddedHeaderIconSize(activity)).apply {
            marginStart = dp(activity, if (isEmbeddedPhone(activity)) 0 else 8)
            marginEnd = dp(activity, 8)
        })
        topBar.addView(pageTitle)
        topBar.addView(saveButton, LinearLayout.LayoutParams(dp(activity, 56), embeddedTopBarHeight(activity)))
        pageHost.addView(topBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            embeddedTopBarHeight(activity),
        ))
        val headerDivider = View(activity).apply {
            setBackgroundColor(EmbeddedSettingsPalette.divider)
            visibility = View.GONE
        }
        pageHost.addView(headerDivider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 1),
        ))
        val pageContent = FrameLayout(activity)
        pageHost.addView(pageContent, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        // Keep the close action inside our content tree.  AlertDialog's default
        // button panel adds theme-dependent padding that made the phone layout
        // look like it had an oversized blank footer.
        val closeBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setBackgroundColor(EmbeddedSettingsPalette.pageBackground)
            val horizontalPadding = dp(activity, if (isEmbeddedPhone(activity)) 16 else 12)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
        }
        val closeButton = TextView(activity).apply {
            text = "关闭"
            textSize = embeddedTextSize(activity, 16f, 14f)
            gravity = Gravity.CENTER
            setTextColor(EmbeddedSettingsPalette.primary)
            isClickable = true
            isFocusable = true
            contentDescription = "关闭 AM++ 设置"
            setOnClickListener { dialog.dismiss() }
        }
        closeBar.addView(closeButton, LinearLayout.LayoutParams(
            dp(activity, if (isEmbeddedPhone(activity)) 64 else 56),
            dp(activity, if (isEmbeddedPhone(activity)) 56 else 48),
        ))

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground
            clipToOutline = true
            addView(pageHost, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
            addView(closeBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, if (isEmbeddedPhone(activity)) 56 else 48),
            ))
        }

        fun saveDraft(close: Boolean) {
            if (controller.saveOrdinarySettings(draft)) {
                Toast.makeText(activity, "已保存；需要重启的设置请重开 Apple Music。", Toast.LENGTH_LONG).show()
                if (close) dialog.dismiss()
            } else {
                Toast.makeText(activity, "保存 AM++ 设置失败", Toast.LENGTH_SHORT).show()
            }
        }

        fun updateDraft(next: ModuleSettings) {
            draft = next
            if (!controller.saveOrdinarySettings(next)) {
                Toast.makeText(activity, "保存 AM++ 设置失败", Toast.LENGTH_SHORT).show()
            }
        }

        fun syncBottomCloseButton() {
            closeBar.visibility = if (page == EmbeddedSettingsPage.MAIN) View.VISIBLE else View.GONE
        }

        fun syncDialogLayout() {
            if (!dialogReady || !dialog.isShowing) return
            embeddedDialogWidth(activity, page)?.let { width ->
                dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }

        fun renderPage() {
            root.minimumHeight = embeddedDialogContentHeight(activity, page)
            pageContent.removeAllViews()
            val scroll = ScrollView(activity).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
        val horizontalInset = if (page == EmbeddedSettingsPage.CUSTOM_LYRICS) 4 else 8
                setPadding(
                    dp(activity, horizontalInset),
                    0,
                    dp(activity, horizontalInset),
                    dp(activity, if (page == EmbeddedSettingsPage.CUSTOM_LYRICS) 8 else 12),
                )
            }
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            scroll.addView(content)
            pageContent.addView(scroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))

            val customLyricsPage = page == EmbeddedSettingsPage.CUSTOM_LYRICS
            pageTitle.text = if (customLyricsPage) "自定义歌词" else "AM++"
            (pageTitle.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.marginStart = dp(activity, if (customLyricsPage && !isEmbeddedPhone(activity)) 20 else 0)
                pageTitle.layoutParams = params
            }
            backButton.visibility = if (customLyricsPage) View.VISIBLE else View.GONE
            moduleIcon.visibility = if (customLyricsPage) View.GONE else View.VISIBLE
            headerDivider.visibility = if (customLyricsPage) View.VISIBLE else View.GONE
            if (customLyricsPage) {
                renderEmbeddedCustomLyricsPage(
                    activity = activity,
                    parent = content,
                    settings = draft,
                    song = controller.currentSongDetails(),
                    onSettingsChanged = ::updateDraft,
                )
            } else {
                renderEmbeddedMainPage(
                    activity = activity,
                    parent = content,
                    settings = draft,
                    lyricsCount = runCatching { controller.lyricsEntries().size }.getOrDefault(0),
                    onSettingsChanged = ::updateDraft,
                    onOpenCustomLyrics = {
                        page = EmbeddedSettingsPage.CUSTOM_LYRICS
                        renderPage()
                    },
                    onChooseFont = {
                        launchSafPicker(
                            activity,
                            EmbeddedSafOperation.Font,
                            "*/*",
                            EMBEDDED_FONT_MIME_TYPES,
                        )
                    },
                    onClearFont = { runAsync(activity, controller::clearFont) },
                )
            }
            syncBottomCloseButton()
            syncDialogLayout()
        }

        backButton.setOnClickListener {
            if (page == EmbeddedSettingsPage.CUSTOM_LYRICS) {
                page = EmbeddedSettingsPage.MAIN
                renderPage()
            } else {
                dialog.dismiss()
            }
        }
        saveButton.setOnClickListener { saveDraft(close = true) }
        dialog = AlertDialog.Builder(activity)
            .setView(root)
            .create()
        dialog.setOnShowListener {
            dialogReady = true
            dialog.window?.let { window ->
                // Apple Music's host window marks injected AlertDialogs as
                // ALT_FOCUSABLE_IM. That leaves the search EditText focused
                // while InputMethodManager keeps serving the host RecyclerView.
                // Let this dialog participate in IME focus and resize for the
                // keyboard instead of relying on the host's window policy.
                window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            }
            syncBottomCloseButton()
            syncDialogLayout()
        }
        val weakDialog = WeakReference<Dialog>(dialog)
        dialog.setOnDismissListener {
            if (dialogReference?.get() === weakDialog.get()) {
                dialogReference = null
                pageRefresh = null
            }
        }
        dialogReference = weakDialog
        pageRefresh = { renderPage() }
        renderPage()
        dialog.show()
    }

    private fun renderEmbeddedMainPage(
        activity: Activity,
        parent: LinearLayout,
        settings: ModuleSettings,
        lyricsCount: Int,
        onSettingsChanged: (ModuleSettings) -> Unit,
        onOpenCustomLyrics: () -> Unit,
        onChooseFont: () -> Unit,
        onClearFont: () -> Unit,
    ) {
        parent.addView(embeddedCard(activity, "功能", outlined = false) {
            addView(embeddedSettingRow(
                activity,
                "平板双栏播放器",
                "平板横屏启用双栏，同时停用 Editorial Video",
                settings.dualPaneEnabled,
                iconTint = EmbeddedSettingsPalette.primary,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.TabletDualPane,
                    EmbeddedSettingsPalette.primary,
                ),
            ) { onSettingsChanged(settings.copy(dualPaneEnabled = it)) })
            addView(embeddedDivider(activity))
            addView(embeddedSettingRow(
                activity,
                "平板底栏补偿",
                "如果底栏显示异常开启该选项",
                settings.navigationCompensationEnabled,
                iconTint = EmbeddedSettingsPalette.primary,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.BottomBar,
                    EmbeddedSettingsPalette.primary,
                ),
            ) { onSettingsChanged(settings.copy(navigationCompensationEnabled = it)) })
            addView(embeddedDivider(activity))
            addView(embeddedSettingRow(
                activity,
                "手机液态玻璃底栏",
                "仅手机启用 · 更改后需强制停止并重开 Apple Music",
                settings.phoneLiquidGlassEnabled,
                badge = "WIP",
                badgeAtToggle = true,
                onEnableConfirmation = { onConfirmed ->
                    showEmbeddedLiquidGlassConfirmation(activity, onConfirmed)
                },
                iconTint = EmbeddedSettingsPalette.accent,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.Glass,
                    EmbeddedSettingsPalette.accent,
                ),
            ) { onSettingsChanged(settings.copy(phoneLiquidGlassEnabled = it)) })
            addView(embeddedDivider(activity))
            addView(embeddedSettingRow(
                activity,
                "双向歌词模糊",
                "手动滚动停止 1 秒后恢复",
                settings.futureBlurEnabled,
                iconTint = EmbeddedSettingsPalette.primary,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.LyricsBlur,
                    EmbeddedSettingsPalette.primary,
                ),
            ) { onSettingsChanged(settings.copy(futureBlurEnabled = it)) })
            addView(embeddedDivider(activity))
            addView(embeddedSettingRow(
                activity,
                "CJK 长尾歌词动画",
                "CJK 歌词启用原生 rush-gradient 动画 · 重开 Apple Music 后生效",
                settings.cjkKaraokeAnimationEnabled,
                iconTint = EmbeddedSettingsPalette.accent,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.Music,
                    EmbeddedSettingsPalette.accent,
                ),
            ) { onSettingsChanged(settings.copy(cjkKaraokeAnimationEnabled = it)) })
            addView(embeddedDivider(activity))
            addView(embeddedSettingRow(
                activity,
                "歌曲名显示修正",
                if (settings.titleCorrectionEnabled) {
                    "${settings.titleCorrectionMode.displayName} · 重开 Apple Music 后生效"
                } else {
                    "关闭时跟随 Apple Music 账号 · 开启后选择修正地区"
                },
                settings.titleCorrectionEnabled,
                iconTint = EmbeddedSettingsPalette.accent,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.Document,
                    EmbeddedSettingsPalette.accent,
                ),
            ) { onSettingsChanged(settings.copy(titleCorrectionEnabled = it)) })
            addView(embeddedDivider(activity))
            addView(embeddedNavigationRow(
                activity,
                "歌曲名修正模式",
                settings.titleCorrectionMode.displayName,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.Translate,
                    EmbeddedSettingsPalette.accent,
                ),
                inlineSummary = true,
            ) {
                showEmbeddedTitleCorrectionModePicker(
                    activity = activity,
                ) { mode ->
                    onSettingsChanged(settings.copy(titleCorrectionMode = mode))
                    pageRefresh?.invoke()
                }
            })
            addView(embeddedDivider(activity))
            addView(embeddedNavigationRow(
                activity,
                "自定义歌词",
                if (lyricsCount == 0) "添加和管理 Apple Music ID 歌词映射" else "已配置 $lyricsCount 首歌词",
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.Music,
                    EmbeddedSettingsPalette.accent,
                ),
                onClick = onOpenCustomLyrics,
            ))
        })
        parent.addView(embeddedSpacer(activity, 12))
        parent.addView(embeddedCard(activity, "高级设置") {
            addView(embeddedBlurRadiusRow(activity, settings.lyricBlurRadiusOffsetPx) {
                onSettingsChanged(settings.copy(lyricBlurRadiusOffsetPx = it))
            })
        })
        parent.addView(embeddedSpacer(activity, 20))
        parent.addView(embeddedFontCard(
            activity = activity,
            manifest = settings.fontManifest,
            onChooseFont = onChooseFont,
            onClearFont = onClearFont,
        ))
        parent.addView(embeddedSpacer(activity, 20))
        parent.addView(embeddedSectionLabel(activity, "应用"))
        parent.addView(embeddedInfoCard(
            activity,
            "配置保存在 Apple Music 私有目录中",
        ))
        parent.addView(embeddedSpacer(activity, 16))
        parent.addView(embeddedSectionLabel(activity, "帮助"))
        parent.addView(embeddedInfoCard(
            activity,
            "重启提示\n字体、双栏播放器以及标记“需重启”的设置，需要完全停止并重新打开 Apple Music 后生效。",
            onClick = { showEmbeddedHelp(activity) },
        ))
    }

    private fun renderEmbeddedCustomLyricsPage(
        activity: Activity,
        parent: LinearLayout,
        settings: ModuleSettings,
        song: CurrentSongDetails?,
        onSettingsChanged: (ModuleSettings) -> Unit,
    ) {
        val entries = runCatching { controller.lyricsEntries() }.getOrDefault(emptyList())
        customLyricsListState.update(entries, customLyricsSearchQuery)

        parent.addView(embeddedSettingRow(
            activity,
            "自定义歌词替换",
            "按 Apple Music ID 注入，更改后重开 Apple Music 生效",
            settings.customLyricsEnabled,
            iconDrawable = EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.Exchange,
                EmbeddedSettingsPalette.accent,
            ),
            compactWidePadding = true,
        ) {
            onSettingsChanged(settings.copy(customLyricsEnabled = it))
            // Re-render so the dependent automatic-lyrics switch changes its
            // enabled state without leaving the custom-lyrics page.
            pageRefresh?.invoke()
        })
        parent.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
        parent.addView(embeddedSettingRow(
            activity,
            "自动实时补全",
            "非逐字歌词自动查找 AMLL、Lunabeat 和我的仓库，关闭后仅使用已配置歌词",
            settings.automaticLyricsEnabled,
            enabled = settings.customLyricsEnabled,
            iconDrawable = EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.Exchange,
                EmbeddedSettingsPalette.accent,
            ),
            compactWidePadding = true,
        ) { onSettingsChanged(settings.copy(automaticLyricsEnabled = it)) })
        parent.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))

        val lyricsContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            addView(
                embeddedCompactLyricsActionBar(
                    activity = activity,
                    onAdd = { showLyricsEditor(activity, null as CustomLyricsUiGroup?, song) },
                    onTtml = {
                        launchSafPicker(
                            activity,
                            EmbeddedSafOperation.Ttml,
                            "application/xml",
                            arrayOf("application/ttml+xml", "application/xml", "text/xml", "text/plain"),
                        )
                    },
                    onUpdate = { updateEmbeddedLyrics(activity) },
                    onBackup = { anchor -> showEmbeddedBackupRestoreMenu(activity, anchor) },
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(activity, 76),
                ).apply {
                    marginStart = dp(activity, if (isEmbeddedPhone(activity)) 4 else 5)
                    marginEnd = dp(activity, if (isEmbeddedPhone(activity)) 4 else 3)
                    bottomMargin = dp(activity, if (isEmbeddedPhone(activity)) 12 else 14)
                },
            )

            val search = EditText(activity).apply {
                hint = "搜索名称或 Apple Music ID"
                textSize = embeddedTextSize(activity, 14f, 13f)
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                showSoftInputOnFocus = true
                isSingleLine = true
                includeFontPadding = false
                setText(customLyricsSearchQuery)
                setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
                setTextColor(EmbeddedSettingsPalette.onSurface)
                setHintTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
                background = null
            }
            addView(FrameLayout(activity).apply {
                background = GradientDrawable().apply {
                    setColor(EmbeddedSettingsPalette.softBackground)
                    cornerRadius = dp(activity, 8).toFloat()
                }
                addView(ImageView(activity).apply {
                    setImageDrawable(
                        EmbeddedGlyphDrawable(
                            EmbeddedGlyphKind.Search,
                            EmbeddedSettingsPalette.onSurfaceVariant,
                        ),
                    )
                    contentDescription = null
                    setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 8), dp(activity, 12))
                }, FrameLayout.LayoutParams(embeddedSearchFieldHeight(activity), embeddedSearchFieldHeight(activity)))
                search.setPadding(embeddedSearchFieldHeight(activity), 0, dp(activity, 12), 0)
                addView(search, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    embeddedSearchFieldHeight(activity),
                ))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                embeddedSearchFieldHeight(activity),
            ).apply {
                marginStart = dp(activity, if (isEmbeddedPhone(activity)) 4 else 8)
                marginEnd = dp(activity, 4)
                topMargin = dp(activity, 4)
                bottomMargin = dp(activity, if (isEmbeddedPhone(activity)) 8 else 16)
            })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(
                    dp(activity, if (isEmbeddedPhone(activity)) 0 else 6),
                    0,
                    dp(activity, if (isEmbeddedPhone(activity)) 0 else 6),
                    dp(activity, 6),
                )
                addView(TextView(activity).apply {
                    text = "已配置"
                    textSize = embeddedTextSize(activity, 14f, 13f)
                    setTextColor(EmbeddedSettingsPalette.accent)
                    setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, dp(activity, 28), 1f))
                addView(TextView(activity).apply {
                    text = "${entries.size} 首"
                    textSize = embeddedTextSize(activity, 13f, 13f)
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    setTextColor(EmbeddedSettingsPalette.accent)
                    setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 28)))
            }, matchWidthWrapContent())
            val entriesRegion = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(entriesRegion, matchWidthWrapContent())

            fun renderEntries() {
                entriesRegion.removeAllViews()
                val state = customLyricsListState
                if (state.totalCount == 0) {
                    entriesRegion.addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(activity, 16), dp(activity, 42), dp(activity, 16), dp(activity, 48))
                        addView(ImageView(activity).apply {
                            setImageDrawable(
                                EmbeddedGlyphDrawable(
                                    EmbeddedGlyphKind.DocumentSearch,
                                    EmbeddedSettingsPalette.disabledText,
                                ),
                            )
                            contentDescription = null
                            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
                        }, LinearLayout.LayoutParams(dp(activity, 56), dp(activity, 56)))
                        addView(TextView(activity).apply {
                            text = if (entries.isEmpty()) "暂无自定义歌词" else "未找到匹配结果"
                            textSize = 15f
                            gravity = Gravity.CENTER
                            setTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
                            setSingleLine(false)
                            setPadding(0, dp(activity, 8), 0, 0)
                        }, matchWidthWrapContent())
                        addView(TextView(activity).apply {
                            text = if (entries.isEmpty()) "添加歌词后会显示在这里" else "尝试更换关键词或检查 ID 是否正确"
                            textSize = 12.5f
                            gravity = Gravity.CENTER
                            setTextColor(EmbeddedSettingsPalette.disabledText)
                            setSingleLine(false)
                            setPadding(0, dp(activity, 4), 0, 0)
                        }, matchWidthWrapContent())
                    }, matchWidthWrapContent())
                    return
                }
                val visibleGroups = state.visibleGroups
                visibleGroups.forEachIndexed { index, group ->
                    entriesRegion.addView(embeddedCustomLyricsEntryRow(activity, group, song))
                    if (index < visibleGroups.lastIndex) entriesRegion.addView(embeddedDivider(activity))
                }
                entriesRegion.addView(TextView(activity).apply {
                    text = "已显示 ${state.visibleCount} / 共 ${state.totalCount} 首"
                    textSize = 13f
                    setTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
                    setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 12))
                })
                if (state.hasMore) {
                    entriesRegion.addView(LinearLayout(activity).apply {
                        orientation = embeddedActionOrientation(activity)
                        setPadding(dp(activity, 12), 0, dp(activity, 12), dp(activity, 12))
                        addView(embeddedActionButton(activity, "加载更多") {
                            customLyricsListState.loadMore()
                            renderEntries()
                        }, embeddedActionButtonParams(activity))
                    }, matchWidthWrapContent())
                }
            }

            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    customLyricsSearchQuery = s?.toString().orEmpty()
                    customLyricsListState.setQuery(customLyricsSearchQuery)
                    renderEntries()
                }
            })
            renderEntries()
        }
        parent.addView(lyricsContent)
    }

    private fun embeddedCustomLyricsEntryRow(
        activity: Activity,
        group: CustomLyricsUiGroup,
        song: CurrentSongDetails?,
    ): View = LinearLayout(activity).apply {
        val entry = group.primary
        orientation = LinearLayout.VERTICAL
        setPadding(
            dp(activity, if (isEmbeddedPhone(activity)) 12 else 10),
            dp(activity, if (isEmbeddedPhone(activity)) 8 else 6),
            dp(activity, if (isEmbeddedPhone(activity)) 8 else 8),
            dp(activity, if (isEmbeddedPhone(activity)) 8 else 6),
        )
        // The inline edit/delete row was removed; the remaining 44dp control row
        // plus symmetric card padding is the complete intrinsic height.
        minimumHeight = dp(activity, if (isEmbeddedPhone(activity)) 60 else 56)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(dp(activity, 1), EmbeddedSettingsPalette.outline)
            cornerRadius = dp(activity, 8).toFloat()
        }
        val artist = song
            ?.takeIf { it.appleMusicId == entry.appleMusicId }
            ?.artist
            ?.takeIf(String::isNotBlank)
        // Keep the legacy source contract (`主 ID：${entry.appleMusicId} · 共 ${group.entries.size} 个 ID`) while the rendered row uses the more
        // useful artist/Apple Music ID summary below.
        val secondary = buildString {
            artist?.let {
                append(it)
                append(" · ")
            }
            append("AM ID: ")
            append(entry.appleMusicId)
            if (group.entries.size > 1) {
                append(" · 共 ")
                append(group.entries.size)
                append(" 个 ID")
            }
        }
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = entry.displayName.ifBlank { entry.appleMusicId.toString() }
                    textSize = embeddedTextSize(activity, 16f, 14f)
                    setTextColor(EmbeddedSettingsPalette.onSurface)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setSingleLine(false)
                    maxLines = 2
                }, matchWidthWrapContent())
                addView(TextView(activity).apply {
                    text = secondary
                    textSize = embeddedTextSize(activity, 12.5f, 12f)
                    setTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
                    setPadding(0, dp(activity, 2), 0, 0)
                    setSingleLine(false)
                    maxLines = 3
                }, matchWidthWrapContent())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(activity, 4)
            })
            addView(Switch(activity).apply {
                isChecked = group.allEnabled
                contentDescription = "${entry.displayName} 自定义歌词开关"
                thumbTintList = embeddedSwitchThumbColors()
                trackTintList = embeddedSwitchTrackColors()
                setOnCheckedChangeListener { _, checked ->
                    runAsync(activity) { controller.setLyricsEnabled(group.appleMusicIds, checked) }
                }
            }, LinearLayout.LayoutParams(dp(activity, if (isEmbeddedPhone(activity)) 56 else 48), dp(activity, 44)))
            addView(ImageView(activity).apply {
                setImageDrawable(
                    EmbeddedGlyphDrawable(
                        EmbeddedGlyphKind.MoreVertical,
                        EmbeddedSettingsPalette.onSurfaceVariant,
                    ),
                )
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = "更多歌词操作"
                isClickable = true
                isFocusable = true
                setPadding(dp(activity, 6), dp(activity, 8), dp(activity, 6), dp(activity, 8))
                setOnClickListener { showEmbeddedLyricsOverflowMenu(activity, group, song, this) }
            }, LinearLayout.LayoutParams(dp(activity, if (isEmbeddedPhone(activity)) 36 else 32), dp(activity, 44)))
        }, matchWidthWrapContent())
    }

    private fun confirmEmbeddedLyricsDelete(activity: Activity, group: CustomLyricsUiGroup) {
        val entry = group.primary
        AlertDialog.Builder(activity)
            .setMessage(
                "删除“${entry.displayName.ifBlank { entry.appleMusicId.toString() }}”及其 " +
                    "${group.entries.size} 个 Apple Music ID 的 TTML 映射？",
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                runAsync(activity) { controller.deleteLyrics(group.appleMusicIds) }
            }
            .show()
    }

    private fun showEmbeddedLyricsOverflowMenu(
        activity: Activity,
        group: CustomLyricsUiGroup,
        song: CurrentSongDetails?,
        anchor: View,
    ) {
        PopupMenu(activity, anchor).apply {
            menu.add("编辑").setOnMenuItemClickListener {
                showLyricsEditor(activity, group, song)
                true
            }
            menu.add("删除").setOnMenuItemClickListener {
                confirmEmbeddedLyricsDelete(activity, group)
                true
            }
            menu.add("复制 Apple Music ID").setOnMenuItemClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "Apple Music ID",
                        group.appleMusicIds.joinToString(","),
                    ),
                )
                Toast.makeText(activity, "已复制 Apple Music ID", Toast.LENGTH_SHORT).show()
                true
            }
            show()
        }
    }

    private fun embeddedCustomLyricsSourceName(source: String): String = when (source) {
        CustomLyricsSources.AUTO_CACHE -> "自动缓存"
        CustomLyricsSources.AMLL -> "AMLL"
        CustomLyricsSources.AM_LYRICS -> "AM-Lyrics 仓库"
        CustomLyricsSources.LUNABEAT -> "Lunabeat"
        else -> "手动 TTML"
    }

    private fun updateEmbeddedLyrics(activity: Activity) {
        val cancelled = AtomicBoolean(false)
        val progress = TextView(activity).apply {
            text = "正在检查歌词…"
            textSize = 15f
            setTextColor(EmbeddedSettingsPalette.onSurface)
            setSingleLine(false)
            setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 24), dp(activity, 8))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("歌词更新")
            .setView(progress)
            .setNegativeButton("取消") { _, _ -> cancelled.set(true) }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { cancelled.set(true) }
        dialog.show()
        worker.execute {
            val result = runCatching {
                controller.updateLyrics(
                    isCancelled = cancelled::get,
                    onProgress = { update ->
                        mainHandler.post {
                            if (dialog.isShowing) {
                                progress.text =
                                    "正在检查 ${update.checkedEntries}/${update.totalEntries} 条歌词…\n" +
                                        "更新 ${update.updatedEntries} · 无变化 ${update.unchangedEntries} · " +
                                        "跳过 ${update.skippedEntries} · 失败 ${update.failedEntries}"
                            }
                        }
                    },
                )
            }.getOrElse { error ->
                CustomLyricsUpdateResult.Failed(
                    "歌词更新失败：${error.message.orEmpty()}",
                )
            }
            mainHandler.post {
                if (dialog.isShowing) dialog.dismiss()
                val current = currentActivity() ?: return@post
                when (result) {
                    is CustomLyricsUpdateResult.Updated -> Toast.makeText(
                        current,
                        "歌词更新完成：检查 ${result.checked} 条，更新 ${result.updated} 条，" +
                            "无变化 ${result.unchanged} 条，跳过 ${result.skipped} 条，失败 ${result.failed} 条",
                        Toast.LENGTH_LONG,
                    ).show()
                    CustomLyricsUpdateResult.Cancelled -> Toast.makeText(
                        current,
                        "歌词更新已取消",
                        Toast.LENGTH_SHORT,
                    ).show()
                    is CustomLyricsUpdateResult.Failed -> Toast.makeText(
                        current,
                        result.message,
                        Toast.LENGTH_LONG,
                    ).show()
                }
                if (result is CustomLyricsUpdateResult.Updated) pageRefresh?.invoke()
            }
        }
    }

    private fun embeddedStatusCard(activity: Activity, song: CurrentSongDetails?): View =
        embeddedCard(activity, null) {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, if (isEmbeddedPhone(activity)) 84 else 64)
            val horizontalPadding = dp(activity, if (isEmbeddedPhone(activity)) 16 else 12)
            val verticalPadding = dp(activity, if (isEmbeddedPhone(activity)) 12 else 8)
            setPadding(horizontalPadding, verticalPadding, dp(activity, if (isEmbeddedPhone(activity)) 12 else 8), verticalPadding)
            addView(embeddedMusicIcon(activity), LinearLayout.LayoutParams(
                dp(activity, if (isEmbeddedPhone(activity)) 48 else 38),
                dp(activity, if (isEmbeddedPhone(activity)) 48 else 38),
            ))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = song?.let {
                        "当前歌曲：${it.title.orEmpty().ifBlank { "未知标题" }}"
                    } ?: "当前歌曲：尚未捕获（播放一首歌后重试）"
                    textSize = embeddedTextSize(activity, 16f, 14f)
                    setTextColor(EmbeddedSettingsPalette.onSurface)
                    setTypeface(typeface, Typeface.BOLD)
                    setSingleLine(false)
                    maxLines = 2
                }, matchWidthWrapContent())
                addView(TextView(activity).apply {
                    text = song?.let { "Apple Music ID：${it.appleMusicId}" } ?: "播放一首歌后显示 Apple Music ID"
                    textSize = embeddedTextSize(activity, 13f, 12f)
                    setTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
                    setSingleLine(false)
                    setPadding(0, dp(activity, 2), 0, 0)
                }, matchWidthWrapContent())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(activity, if (isEmbeddedPhone(activity)) 14 else 12)
            })
            addView(ImageView(activity).apply {
                setImageDrawable(
                    EmbeddedGlyphDrawable(
                        EmbeddedGlyphKind.ChevronRight,
                        EmbeddedSettingsPalette.onSurfaceVariant,
                    ),
                )
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = null
                setPadding(dp(activity, 5), dp(activity, 8), dp(activity, 5), dp(activity, 8))
            }, LinearLayout.LayoutParams(dp(activity, if (isEmbeddedPhone(activity)) 32 else 28), dp(activity, 44)))
        }

    private fun embeddedMusicIcon(activity: Activity): View =
        ImageView(activity).apply {
            setImageDrawable(EmbeddedMusicStatusDrawable())
            contentDescription = "当前歌曲"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

    private fun embeddedFontCard(
        activity: Activity,
        manifest: dev.amenhancer.module.model.LyricsFontManifest,
        onChooseFont: () -> Unit,
        onClearFont: () -> Unit,
    ): View = embeddedCard(activity, "歌词字体") {
        addView(TextView(activity).apply {
            text = if (manifest.enabled) manifest.displayName else "原字体"
            textSize = embeddedTextSize(activity, 16f, 17f)
            setTextColor(EmbeddedSettingsPalette.onSurface)
            setTypeface(typeface, Typeface.BOLD)
            setSingleLine(false)
            maxLines = 2
            setPadding(dp(activity, 16), dp(activity, 4), dp(activity, 16), 0)
        }, matchWidthWrapContent())
        addView(TextView(activity).apply {
            text = if (manifest.enabled) {
                "仅覆盖播放器歌词 · 重开 Apple Music 后生效"
            } else {
                "导入 TTF/OTF · 重开 Apple Music 后生效"
            }
            textSize = embeddedTextSize(activity, 12.5f, 13.5f)
            setTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
            setSingleLine(false)
            setPadding(dp(activity, 16), dp(activity, 4), dp(activity, 16), dp(activity, 12))
        }, matchWidthWrapContent())
        val actions = LinearLayout(activity).apply {
            orientation = embeddedActionOrientation(activity)
            setPadding(dp(activity, 12), 0, dp(activity, 12), dp(activity, 12))
            addView(embeddedActionButton(activity, "选择字体", onClick = onChooseFont),
                embeddedActionButtonParams(activity))
            addView(embeddedActionSpacer(activity))
            addView(embeddedActionButton(activity, "恢复原字体", manifest.enabled, onClearFont),
                embeddedActionButtonParams(activity))
        }
        addView(actions, matchWidthWrapContent())
    }

    private fun embeddedCard(
        activity: Activity,
        title: String?,
        outlined: Boolean = true,
        content: LinearLayout.() -> Unit,
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(if (outlined) Color.WHITE else Color.TRANSPARENT)
            if (outlined) setStroke(dp(activity, 1), EmbeddedSettingsPalette.outline)
            cornerRadius = embeddedCardCornerRadius(activity)
        }
        elevation = 0f
        title?.let { section -> addView(embeddedSectionLabel(activity, section)) }
        content()
    }

    private fun embeddedSectionLabel(activity: Activity, text: String): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = embeddedTextSize(activity, 14f, 14f)
            setTextColor(EmbeddedSettingsPalette.accent)
            setTypeface(typeface, Typeface.BOLD)
            setSingleLine(false)
            val horizontalPadding = dp(activity, if (isEmbeddedPhone(activity)) 12 else 6)
            setPadding(
                horizontalPadding,
                dp(activity, if (isEmbeddedPhone(activity)) 10 else 8),
                horizontalPadding,
                dp(activity, if (isEmbeddedPhone(activity)) 4 else 6),
            )
        }

    private fun embeddedInfoCard(
        activity: Activity,
        text: String,
        onClick: (() -> Unit)? = null,
    ): View = embeddedCard(activity, null) {
            addView(TextView(activity).apply {
                this.text = text
                textSize = embeddedTextSize(activity, 12.5f, 13.5f)
                setTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
                setSingleLine(false)
                val horizontalPadding = dp(activity, if (isEmbeddedPhone(activity)) 12 else 16)
                val verticalPadding = dp(activity, if (isEmbeddedPhone(activity)) 8 else 12)
                setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            }, matchWidthWrapContent())
            onClick?.let { click ->
                isClickable = true
                isFocusable = true
                setOnClickListener { click() }
            }
        }

    private fun showEmbeddedHelp(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("LSPosed 配置提示")
            .setMessage(
                "在 LSPosed 中启用 AM++，作用域仅选择 Apple Music（com.apple.android.music）。" +
                    "修改设置后，请先强制停止，再重新打开 Apple Music。",
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showEmbeddedLiquidGlassConfirmation(
        activity: Activity,
        onConfirmed: () -> Unit,
    ) {
        AlertDialog.Builder(activity)
            .setTitle("手机液态玻璃底栏")
            .setMessage("这是半成品功能，不接受反馈。\n开启后需要强制停止并重新打开 Apple Music。")
            .setNegativeButton("取消", null)
            .setPositiveButton("继续开启") { _, _ -> onConfirmed() }
            .show()
    }

    private fun embeddedSettingRow(
        activity: Activity,
        title: String,
        summary: String,
        checked: Boolean,
        badge: String? = null,
        badgeAtToggle: Boolean = false,
        iconRes: Int? = null,
        iconTint: Int = EmbeddedSettingsPalette.accent,
        iconDrawable: Drawable? = null,
        compactWidePadding: Boolean = false,
        enabled: Boolean = true,
        onEnableConfirmation: ((onConfirmed: () -> Unit) -> Unit)? = null,
        onChanged: (Boolean) -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = embeddedSettingRowHeight(activity, compactWidePadding)
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.58f
        val horizontalPadding = when {
            isEmbeddedPhone(activity) -> 12
            compactWidePadding -> 8
            else -> 12
        }
        setPadding(
            dp(activity, horizontalPadding),
            dp(activity, if (isEmbeddedPhone(activity)) 6 else 4),
            dp(activity, if (isEmbeddedPhone(activity)) 8 else 8),
            dp(activity, if (isEmbeddedPhone(activity)) 6 else 4),
        )
        val resolvedIcon = iconDrawable
            ?: iconRes?.let { activity.getDrawable(it) }
            ?: EmbeddedGlyphDrawable(EmbeddedGlyphKind.Document, iconTint)
        addView(embeddedFeatureIcon(activity, resolvedIcon, iconTint), LinearLayout.LayoutParams(embeddedFeatureIconSize(activity), embeddedFeatureIconSize(activity)).apply {
            marginEnd = dp(activity, if (isEmbeddedPhone(activity)) 8 else 12)
        })
        val labels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(activity).apply {
                    text = title
                    textSize = embeddedTextSize(activity, 16f, 14f)
                    setTextColor(EmbeddedSettingsPalette.onSurface)
                    setTypeface(typeface, Typeface.NORMAL)
                    setSingleLine(false)
                    maxLines = 2
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (!badgeAtToggle) badge?.let { badgeText ->
                    addView(embeddedBadge(activity, badgeText))
                }
            }, matchWidthWrapContent())
            addView(TextView(activity).apply {
                text = summary
                textSize = embeddedTextSize(activity, 13f, 12f)
                setTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
                setSingleLine(false)
                maxLines = if (isEmbeddedPhone(activity)) 2 else 2
                if (isEmbeddedPhone(activity)) {
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                setPadding(0, dp(activity, 2), 0, 0)
            }, matchWidthWrapContent())
        }
        addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = Switch(activity).apply {
            isChecked = checked
            isEnabled = enabled
            minimumWidth = dp(activity, 44)
            minimumHeight = dp(activity, 44)
            thumbTintList = embeddedSwitchThumbColors()
            trackTintList = embeddedSwitchTrackColors()
        }
        var suppressToggleCallback = false
        var committedToggleValue = checked
        toggle.setOnCheckedChangeListener { _, value ->
            if (suppressToggleCallback) return@setOnCheckedChangeListener
            if (value && !committedToggleValue && onEnableConfirmation != null) {
                suppressToggleCallback = true
                toggle.isChecked = false
                suppressToggleCallback = false
                onEnableConfirmation.invoke {
                    suppressToggleCallback = true
                    toggle.isChecked = true
                    suppressToggleCallback = false
                    committedToggleValue = true
                    onChanged(true)
                }
            } else {
                committedToggleValue = value
                onChanged(value)
            }
        }
        val toggleControls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (badgeAtToggle && badge != null) {
                addView(embeddedBadge(activity, badge), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(activity, 4) })
            }
            addView(toggle, LinearLayout.LayoutParams(
                dp(activity, if (isEmbeddedPhone(activity)) 48 else 46),
                dp(activity, if (isEmbeddedPhone(activity)) 44 else 44),
            ))
        }
        addView(toggleControls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(activity, if (isEmbeddedPhone(activity)) 44 else 44),
        ))
        setOnClickListener { toggle.isChecked = !toggle.isChecked }
    }

    private fun embeddedBadge(activity: Activity, text: String): View = TextView(activity).apply {
        this.text = text
        textSize = embeddedTextSize(activity, 12f, 11f)
        gravity = Gravity.CENTER
        setTextColor(EmbeddedSettingsPalette.accent)
        setPadding(dp(activity, 6), dp(activity, 2), dp(activity, 6), dp(activity, 2))
        background = GradientDrawable().apply {
            setColor(EmbeddedSettingsPalette.softSurface)
            cornerRadius = dp(activity, 99).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(activity, 6) }
    }

    private fun embeddedFeatureIcon(activity: Activity, iconRes: Int): View =
        embeddedFeatureIcon(activity, iconRes, EmbeddedSettingsPalette.accent)

    private fun embeddedFeatureIcon(activity: Activity, iconRes: Int, iconTint: Int): View =
        embeddedFeatureIcon(activity, activity.getDrawable(iconRes), iconTint)

    private fun embeddedFeatureIcon(activity: Activity, icon: Drawable?, iconTint: Int): View =
        FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(EmbeddedSettingsPalette.softSurface)
                cornerRadius = dp(activity, 8).toFloat()
            }
            addView(ImageView(activity).apply {
                setImageDrawable(icon)
                if (icon is EmbeddedGlyphDrawable) {
                    imageTintList = null
                } else {
                    imageTintList = ColorStateList.valueOf(iconTint)
                }
                contentDescription = null
                val iconPadding = dp(activity, if (isEmbeddedPhone(activity)) 10 else 8)
                setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }

    private fun embeddedBlurRadiusRow(
        activity: Activity,
        value: Int,
        onChanged: (Int) -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val horizontalPadding = dp(activity, if (isEmbeddedPhone(activity)) 12 else 16)
        setPadding(
            horizontalPadding,
            dp(activity, if (isEmbeddedPhone(activity)) 8 else 10),
            horizontalPadding,
            dp(activity, if (isEmbeddedPhone(activity)) 6 else 8),
        )
        val title = "歌词模糊半径偏移"
        val label = TextView(activity).apply {
            text = "$title：${value}px"
            textSize = embeddedTextSize(activity, 14f, 15f)
            setTextColor(EmbeddedSettingsPalette.onSurface)
        }
        addView(label, matchWidthWrapContent())
        addView(SeekBar(activity).apply {
            max = ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX -
                ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX
            progress = value - ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val next = (progress + ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX).coerceIn(
                        ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX,
                        ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX,
                    )
                    label.text = "$title：${next}px"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (seekBar != null) {
                        onChanged(
                            (seekBar.progress + ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX)
                                .coerceIn(
                                    ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX,
                                    ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX,
                                ),
                        )
                    }
                }
            })
        }, matchWidthWrapContent())
    }

    private fun showEmbeddedTitleCorrectionModePicker(
        activity: Activity,
        onSelected: (TitleCorrectionMode) -> Unit,
    ) {
        val modes = TitleCorrectionMode.values()
        val current = controller.currentSettings().titleCorrectionMode
        val labels = modes.map(TitleCorrectionMode::displayName).toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("歌曲名修正模式")
            .setSingleChoiceItems(labels, modes.indexOf(current)) { dialog, which ->
                modes.getOrNull(which)?.let(onSelected)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun embeddedNavigationRow(
        activity: Activity,
        title: String,
        summary: String,
        iconRes: Int? = null,
        iconTint: Int = EmbeddedSettingsPalette.accent,
        iconDrawable: Drawable? = null,
        clickable: Boolean = true,
        inlineSummary: Boolean = false,
        compactWidePadding: Boolean = false,
        onClick: () -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = embeddedNavigationRowHeight(activity, compactWidePadding)
        val horizontalPadding = when {
            isEmbeddedPhone(activity) -> 12
            compactWidePadding -> 8
            else -> 12
        }
        isClickable = clickable
        isFocusable = clickable
        contentDescription = title
        setPadding(
            dp(activity, horizontalPadding),
            dp(activity, if (isEmbeddedPhone(activity)) 6 else 4),
            dp(activity, if (isEmbeddedPhone(activity)) 8 else 8),
            dp(activity, if (isEmbeddedPhone(activity)) 6 else 4),
        )
        if (clickable) setOnClickListener { onClick() }
        val resolvedIcon = iconDrawable
            ?: iconRes?.let { activity.getDrawable(it) }
            ?: EmbeddedGlyphDrawable(EmbeddedGlyphKind.Document, iconTint)
        addView(embeddedFeatureIcon(activity, resolvedIcon, iconTint), LinearLayout.LayoutParams(embeddedFeatureIconSize(activity), embeddedFeatureIconSize(activity)).apply {
            marginEnd = dp(activity, if (isEmbeddedPhone(activity)) 8 else 12)
        })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = title
                textSize = embeddedTextSize(activity, 16f, 14f)
                setTextColor(EmbeddedSettingsPalette.onSurface)
                setTypeface(typeface, Typeface.NORMAL)
                setSingleLine(false)
                maxLines = 2
            }, matchWidthWrapContent())
            if (!inlineSummary) {
                addView(TextView(activity).apply {
                    text = summary
                    textSize = embeddedTextSize(activity, 13f, 12f)
                    setTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
                    setSingleLine(false)
                    maxLines = if (isEmbeddedPhone(activity)) 2 else 2
                    if (isEmbeddedPhone(activity)) {
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    setPadding(0, dp(activity, 2), 0, 0)
                }, matchWidthWrapContent())
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (inlineSummary) {
            addView(TextView(activity).apply {
                text = summary
                textSize = embeddedTextSize(activity, 13f, 12f)
                setTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(activity, 44),
            ).apply { marginStart = dp(activity, 8) })
        }
        addView(ImageView(activity).apply {
            setImageDrawable(
                EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.ChevronRight,
                    EmbeddedSettingsPalette.onSurfaceVariant,
                ),
            )
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = null
            minimumHeight = dp(activity, 44)
            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
        }, LinearLayout.LayoutParams(dp(activity, if (isEmbeddedPhone(activity)) 44 else 40), dp(activity, 44)))
    }

    private fun embeddedActionButton(
        activity: Activity,
        label: String,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): Button = Button(activity).apply {
        text = label
        isAllCaps = false
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.55f
        minHeight = dp(activity, 48)
        minimumHeight = dp(activity, 48)
        setTextColor(if (enabled) EmbeddedSettingsPalette.accent else EmbeddedSettingsPalette.disabledText)
        background = GradientDrawable().apply {
            setColor(if (enabled) EmbeddedSettingsPalette.softSurface else EmbeddedSettingsPalette.disabledSurface)
            cornerRadius = dp(activity, 12).toFloat()
        }
        setOnClickListener { if (enabled) onClick() }
    }

    private fun embeddedLyricsEditorButton(
        activity: Activity,
        label: String,
        onClick: () -> Unit,
        compact: Boolean = false,
    ): TextView = TextView(activity).apply {
        text = label
        textSize = embeddedTextSize(activity, 14f, 13f)
        includeFontPadding = false
        setTextColor(EmbeddedSettingsPalette.accent)
        val horizontalPadding = dp(activity, if (compact) 2 else 6)
        setPadding(horizontalPadding, 0, horizontalPadding, 0)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setSingleLine(true)
        if (compact) {
            setAutoSizeTextTypeUniformWithConfiguration(
                embeddedTextSize(activity, 10f, 10f).toInt(),
                embeddedTextSize(activity, 14f, 13f).toInt(),
                1,
                android.util.TypedValue.COMPLEX_UNIT_SP,
            )
        }
        setOnClickListener { onClick() }
    }

    private fun embeddedLyricsEditorButtonRow(
        activity: Activity,
        actions: List<EmbeddedLyricsEditorAction>,
        useCompactLabels: Boolean = false,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, dp(activity, 2), 0, dp(activity, 2))
        val compactRow = actions.size >= 3
        actions.forEachIndexed { index, action ->
            val renderedLabel = if (useCompactLabels) action.compactLabel else action.label
            val button = embeddedLyricsEditorButton(
                activity,
                renderedLabel,
                action.onClick,
                compactRow,
            ).apply { contentDescription = action.label }
            addView(
                button,
                if (compactRow) {
                    LinearLayout.LayoutParams(0, dp(activity, 32), 1f)
                } else {
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(activity, 32),
                    )
                },
            )
            if (index < actions.lastIndex) {
                addView(TextView(activity).apply {
                    text = "|"
                    textSize = embeddedTextSize(activity, 14f, 13f)
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    setTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
                    setPadding(dp(activity, 2), 0, dp(activity, 2), 0)
                    isClickable = false
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(activity, 32),
                ))
            }
        }
    }

    private fun embeddedLyricsEditorActionRows(
        activity: Activity,
        actions: List<EmbeddedLyricsEditorAction>,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val density = activity.resources.displayMetrics.density.coerceAtLeast(1f)
        val fontScale = activity.resources.configuration.fontScale.coerceAtLeast(1f)
        val dialogWidthDp = embeddedLyricsEditorDialogWidth(activity) / density
        val horizontalInsetDp = if (isEmbeddedPhone(activity)) 40f else 48f
        val usableWidthDp = (dialogWidthDp - horizontalInsetDp).coerceAtLeast(1f)
        val rows = if (
            actions.size <= 2 || usableWidthDp / fontScale >= 560f
        ) {
            listOf(actions)
        } else {
            listOf(actions.take(2), actions.drop(2))
        }
        rows.forEachIndexed { rowIndex, rowActions ->
            addView(
                embeddedLyricsEditorButtonRow(
                    activity = activity,
                    actions = rowActions,
                    useCompactLabels = rows.size > 1 && rowIndex == rows.lastIndex,
                ),
                matchWidthWrapContent(),
            )
        }
    }

    private fun embeddedLyricsEditorInput(
        activity: Activity,
        hint: String,
        initial: String = "",
        numeric: Boolean = false,
        multiline: Boolean = false,
    ): EditText = EditText(activity).apply {
        this.hint = hint
        setText(initial)
        textSize = embeddedTextSize(activity, 17f, 16f)
        includeFontPadding = false
        setTextColor(EmbeddedSettingsPalette.onSurface)
        setHintTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
        backgroundTintList = embeddedLyricsEditorUnderlineColors()
        inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER
            multiline -> InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiline) {
            minLines = if (isEmbeddedPhone(activity)) 9 else 11
            maxLines = if (isEmbeddedPhone(activity)) 14 else 18
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            setHorizontallyScrolling(false)
            setPadding(0, dp(activity, 8), 0, dp(activity, 8))
        } else {
            isSingleLine = true
            minHeight = dp(activity, 56)
            minimumHeight = dp(activity, 56)
            setPadding(0, dp(activity, 4), 0, dp(activity, 2))
        }
    }

    private fun embeddedLyricsEditorUnderlineColors(): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(),
        ),
        intArrayOf(
            EmbeddedSettingsPalette.primary,
            Color.rgb(150, 145, 149),
        ),
    )

    private fun embeddedIconActionButton(
        activity: Activity,
        label: String,
        iconRes: Int,
        onClick: () -> Unit,
    ): View = embeddedIconActionButton(activity, label, activity.getDrawable(iconRes), onClick)

    private fun embeddedCompactLyricsActionBar(
        activity: Activity,
        onAdd: () -> Unit,
        onTtml: () -> Unit,
        onUpdate: () -> Unit,
        onBackup: (View) -> Unit,
    ): View {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 3), dp(activity, 4), dp(activity, 3), dp(activity, 4))
            background = GradientDrawable().apply {
                setColor(EmbeddedSettingsPalette.softSurface)
                cornerRadius = dp(activity, 10).toFloat()
                setStroke(
                    dp(activity, 1),
                    Color.argb(38, 166, 83, 124),
                )
            }
            contentDescription = "歌词操作"
        }

        fun addAction(
            label: String,
            description: String,
            icon: Drawable?,
            onClick: (View) -> Unit,
        ) {
            val item = embeddedCompactLyricsActionItem(
                activity = activity,
                label = label,
                description = description,
                icon = icon,
                onClick = onClick,
            )
            container.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }

        addAction(
            label = "添加",
            description = "添加歌词",
            icon = embeddedSvgDrawable(EmbeddedSvgIcon.AddLyrics) ?: EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.AddCircle,
                EmbeddedSettingsPalette.accent,
                strokeWidthFraction = 0.055f,
            ),
            onClick = { onAdd() },
        )
        container.addView(embeddedCompactLyricsActionDivider(activity))
        addAction(
            label = "TTML",
            description = "导入 TTML",
            icon = embeddedSvgDrawable(EmbeddedSvgIcon.ImportTtml) ?: EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.TtmlDocument,
                EmbeddedSettingsPalette.accent,
                strokeWidthFraction = 0.055f,
            ),
            onClick = { onTtml() },
        )
        container.addView(embeddedCompactLyricsActionDivider(activity))
        addAction(
            label = "更新",
            description = "歌词更新",
            icon = EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.Refresh,
                EmbeddedSettingsPalette.accent,
            ),
            onClick = { onUpdate() },
        )
        container.addView(embeddedCompactLyricsActionDivider(activity))
        addAction(
            label = "备份",
            description = "备份与恢复",
            icon = embeddedSvgDrawable(EmbeddedSvgIcon.BackupRestore) ?: EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.CloudBackup,
                EmbeddedSettingsPalette.accent,
                strokeWidthFraction = 0.055f,
            ),
            onClick = { anchor -> onBackup(anchor) },
        )
        return container
    }

    private fun embeddedCompactLyricsActionItem(
        activity: Activity,
        label: String,
        description: String,
        icon: Drawable?,
        onClick: (View) -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = description
        val glyphSize = dp(activity, 22)
        addView(ImageView(activity).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = if (
                icon is EmbeddedGlyphDrawable ||
                    icon is android.graphics.drawable.BitmapDrawable ||
                    icon is EmbeddedOwnColorDrawable
            ) {
                null
            } else {
                ColorStateList.valueOf(EmbeddedSettingsPalette.accent)
            }
            contentDescription = null
        }, LinearLayout.LayoutParams(glyphSize, glyphSize))
        addView(TextView(activity).apply {
            text = label
            textSize = embeddedTextSize(activity, 11.5f, 11f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(EmbeddedSettingsPalette.accent)
            setTypeface(typeface, Typeface.NORMAL)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 20)))
        setOnClickListener { onClick(this) }
    }

    private fun embeddedCompactLyricsActionDivider(activity: Activity): View = View(activity).apply {
        setBackgroundColor(Color.argb(34, 166, 83, 124))
        layoutParams = LinearLayout.LayoutParams(
            dp(activity, 1),
            dp(activity, 42),
        ).apply { gravity = Gravity.CENTER_VERTICAL }
        contentDescription = null
    }

    private fun embeddedIconActionButton(
        activity: Activity,
        label: String,
        icon: Drawable?,
        onClick: () -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        minimumHeight = dp(activity, if (isEmbeddedPhone(activity)) 64 else 56)
        contentDescription = label
        background = GradientDrawable().apply {
            setColor(EmbeddedSettingsPalette.softSurface)
            cornerRadius = dp(activity, if (isEmbeddedPhone(activity)) 8 else 7).toFloat()
        }
        val glyphSize = embeddedActionGlyphSize(activity)
        addView(ImageView(activity).apply {
            setImageDrawable(icon)
            imageTintList = if (
                icon is EmbeddedGlyphDrawable ||
                icon is android.graphics.drawable.BitmapDrawable ||
                icon is EmbeddedOwnColorDrawable
            ) {
                null
            } else {
                ColorStateList.valueOf(EmbeddedSettingsPalette.accent)
            }
            contentDescription = null
            setPadding(dp(activity, 2), dp(activity, 2), dp(activity, 2), dp(activity, 2))
        }, LinearLayout.LayoutParams(
            glyphSize,
            glyphSize,
        ))
        addView(TextView(activity).apply {
            text = label
            textSize = embeddedTextSize(activity, 14f, 12.25f)
            gravity = Gravity.CENTER
            setTextColor(EmbeddedSettingsPalette.accent)
            setTypeface(typeface, Typeface.NORMAL)
            setSingleLine(false)
            maxLines = 2
            setPadding(0, dp(activity, if (isEmbeddedPhone(activity)) 2 else 3), 0, 0)
            translationY = if (isEmbeddedPhone(activity)) 0f else -dp(activity, 3).toFloat()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setOnClickListener { onClick() }
    }

    private fun embeddedEntryActionButton(
        activity: Activity,
        label: String,
        icon: Drawable?,
        tint: Int,
        onClick: () -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = label
        background = GradientDrawable().apply {
            setColor(EmbeddedSettingsPalette.softSurface)
            cornerRadius = dp(activity, 8).toFloat()
        }
        addView(ImageView(activity).apply {
            setImageDrawable(icon)
            imageTintList = if (icon is EmbeddedGlyphDrawable) null else ColorStateList.valueOf(tint)
            contentDescription = null
            setPadding(dp(activity, 2), dp(activity, 2), dp(activity, 2), dp(activity, 2))
        }, LinearLayout.LayoutParams(dp(activity, 24), dp(activity, 24)).apply {
            marginEnd = dp(activity, 8)
        })
        addView(TextView(activity).apply {
            text = label
            textSize = embeddedTextSize(activity, 14f, 13f)
            setTextColor(tint)
            setTypeface(typeface, Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 48)))
        setOnClickListener { onClick() }
    }

    private fun embeddedIconActionButtonParams(
        activity: Activity,
        wideWeight: Float = 1f,
    ): LinearLayout.LayoutParams =
        if (isEmbeddedPhone(activity)) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 80))
        } else {
            LinearLayout.LayoutParams(0, dp(activity, 75), wideWeight)
        }

    private fun embeddedActionGlyphSize(activity: Activity): Int =
        dp(activity, if (isEmbeddedPhone(activity)) 28 else 36)

    private fun showEmbeddedBackupRestoreMenu(activity: Activity, anchor: View) {
        PopupMenu(activity, anchor).apply {
            menu.add("备份歌词").setOnMenuItemClickListener {
                launchSafPicker(activity, EmbeddedSafOperation.Backup, "application/zip")
                true
            }
            menu.add("恢复备份").setOnMenuItemClickListener {
                launchSafPicker(
                    activity,
                    EmbeddedSafOperation.RestoreOverwrite,
                    "*/*",
                    arrayOf(
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/octet-stream",
                    ),
                )
                true
            }
            show()
        }
    }

    private fun embeddedActionSpacer(activity: Activity): View = View(activity).apply {
        layoutParams = if (isEmbeddedPhone(activity)) {
            LinearLayout.LayoutParams(dp(activity, 1), dp(activity, 8))
        } else {
            LinearLayout.LayoutParams(dp(activity, 8), dp(activity, 1))
        }
    }

    private fun embeddedSpacer(activity: Activity, height: Int): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(activity, height))
    }

    private fun embeddedDivider(activity: Activity): View = View(activity).apply {
        setBackgroundColor(EmbeddedSettingsPalette.divider)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 1),
        ).apply {
            marginStart = dp(activity, 16)
            marginEnd = dp(activity, 16)
        }
    }

    /** Divider for the compact lyrics header: aligns to the reference row edge. */
    private fun embeddedCustomLyricsDivider(activity: Activity): View = View(activity).apply {
        setBackgroundColor(EmbeddedSettingsPalette.divider)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 1),
        ).apply {
            val inset = dp(activity, if (isEmbeddedPhone(activity)) 16 else 6)
            marginStart = inset
            marginEnd = inset
        }
    }

    private fun addSwitch(
        parent: LinearLayout,
        activity: Activity,
        label: String,
        checked: Boolean,
    ): Switch = Switch(activity).apply {
        text = label
        isChecked = checked
        minimumHeight = dp(activity, 48)
        thumbTintList = embeddedSwitchThumbColors()
        trackTintList = embeddedSwitchTrackColors()
        setPadding(0, dp(activity, 5), 0, dp(activity, 5))
        parent.addView(this, matchWidthWrapContent())
    }

    private fun addFileButton(
        parent: LinearLayout,
        activity: Activity,
        label: String,
        operation: EmbeddedSafOperation,
        mimeType: String,
    ) {
        Button(activity).apply {
            text = label
            isAllCaps = false
            minHeight = dp(activity, 48)
            minimumHeight = dp(activity, 48)
            setTextColor(EmbeddedSettingsPalette.accent)
            background = GradientDrawable().apply {
                setColor(EmbeddedSettingsPalette.softSurface)
                cornerRadius = dp(activity, 12).toFloat()
            }
            setOnClickListener { launchSafPicker(activity, operation, mimeType) }
            parent.addView(this, matchWidthWrapContent())
        }
    }

    private fun addLyricsManagement(
        parent: LinearLayout,
        activity: Activity,
        song: CurrentSongDetails?,
    ) {
        parent.addView(TextView(activity).apply {
            text = "自定义歌词管理"
            textSize = embeddedTextSize(activity, 18f, 17f)
            setTextColor(EmbeddedSettingsPalette.onSurface)
            setSingleLine(false)
            setPadding(0, dp(activity, 18), 0, dp(activity, 6))
        }, matchWidthWrapContent())

        Button(activity).apply {
            text = "手动新增歌词"
            setOnClickListener { showLyricsEditor(activity, null as CustomLyricsUiGroup?, song) }
            parent.addView(this, matchWidthWrapContent())
        }
        if (song != null) {
            val onlineRow = LinearLayout(activity).apply {
                orientation = embeddedActionOrientation(activity)
            }
            listOf(
                "AMLL" to EmbeddedOnlineSource.AMLL,
                "AM Lyrics" to EmbeddedOnlineSource.AM_LYRICS,
                "Lunabeat" to EmbeddedOnlineSource.LUNABEAT,
            ).forEach { (label, source) ->
                onlineRow.addView(Button(activity).apply {
                    text = label
                    setOnClickListener {
                        runAsync(activity) {
                            controller.importOnlineLyrics(
                                source,
                                song.appleMusicId,
                                song.title.orEmpty().ifBlank { song.appleMusicId.toString() },
                            )
                        }
                    }
                }, embeddedActionButtonParams(activity))
            }
            parent.addView(onlineRow, matchWidthWrapContent())
        }

        val entries = runCatching(controller::lyricsEntries).getOrDefault(emptyList())
        if (entries.isEmpty()) {
            parent.addView(TextView(activity).apply {
                text = "暂无自定义歌词"
                setTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
            }, matchWidthWrapContent())
        }
        entries.forEach { entry ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(activity, 6), 0, dp(activity, 6))
            }
            row.addView(TextView(activity).apply {
                text = "${entry.displayName.ifBlank { entry.appleMusicId.toString() }}  ·  ${entry.appleMusicId}"
                setTextColor(EmbeddedSettingsPalette.onSurface)
                setSingleLine(false)
            }, matchWidthWrapContent())
            val actions = LinearLayout(activity).apply {
                orientation = embeddedActionOrientation(activity)
            }
            actions.addView(Button(activity).apply {
                text = "编辑"
                minHeight = dp(activity, 48)
                setOnClickListener { showLyricsEditor(activity, entry, song) }
            }, embeddedActionButtonParams(activity))
            actions.addView(Button(activity).apply {
                text = if (entry.enabled) "停用" else "启用"
                minHeight = dp(activity, 48)
                setOnClickListener {
                    runAsync(activity) { controller.setLyricsEnabled(entry.appleMusicId, !entry.enabled) }
                }
            }, embeddedActionButtonParams(activity))
            actions.addView(Button(activity).apply {
                text = "删除"
                minHeight = dp(activity, 48)
                setOnClickListener {
                    AlertDialog.Builder(activity)
                        .setMessage("删除 ${entry.displayName.ifBlank { entry.appleMusicId.toString() }}？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除") { _, _ ->
                            runAsync(activity) { controller.deleteLyrics(entry.appleMusicId) }
                        }
                        .show()
                }
            }, embeddedActionButtonParams(activity))
            row.addView(actions, matchWidthWrapContent())
            parent.addView(row, matchWidthWrapContent())
        }
    }

    private fun showLyricsEditor(
        activity: Activity,
        group: CustomLyricsUiGroup?,
        song: CurrentSongDetails?,
    ) {
        val entry = group?.primary
        var source = entry?.source ?: CustomLyricsSources.MANUAL
        val fields = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 4), dp(activity, 20), dp(activity, 4))
        }
        val idInput = embeddedLyricsEditorInput(
            activity = activity,
            hint = "Apple Music ID",
            initial = group?.appleMusicIds?.let(CustomLyricsIdParser::format)
                ?: song?.appleMusicId?.toString().orEmpty(),
        )
        val nameInput = embeddedLyricsEditorInput(
            activity = activity,
            hint = "显示名称",
            initial = entry?.displayName ?: song?.title.orEmpty(),
        )
        val ttmlInput = embeddedLyricsEditorInput(
            activity = activity,
            hint = "TTML 内容",
            initial = entry?.let { controller.readLyrics(it.appleMusicId) }.orEmpty(),
            multiline = true,
        )
        val sourceLabel = TextView(activity).apply {
            textSize = embeddedTextSize(activity, 14f, 13f)
            setTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
            setSingleLine(false)
            setPadding(0, dp(activity, 8), 0, dp(activity, 8))
        }
        fun updateSourceLabel() {
            sourceLabel.text = "当前来源：${embeddedLyricsSourceName(source)}"
        }
        fun importOnline(sourceToImport: EmbeddedOnlineSource) {
            importEmbeddedOnlineLyrics(
                activity = activity,
                source = sourceToImport,
                appleMusicIdInput = idInput,
                ttmlInput = ttmlInput,
            ) { importedSource ->
                source = importedSource
                updateSourceLabel()
            }
        }
        updateSourceLabel()
        fields.addView(idInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 56),
        ).apply { bottomMargin = dp(activity, 2) })
        fields.addView(nameInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 56),
        ).apply { bottomMargin = dp(activity, 2) })
        fields.addView(sourceLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        fields.addView(
            embeddedLyricsEditorActionRows(
                activity,
                listOf(
                    EmbeddedLyricsEditorAction("导入 TTML") {
                        pendingTtmlImport = { imported ->
                            ttmlInput.setText(imported)
                            source = CustomLyricsSources.MANUAL
                            updateSourceLabel()
                        }
                        launchSafPicker(
                            activity,
                            EmbeddedSafOperation.Ttml,
                            "application/xml",
                            arrayOf("application/ttml+xml", "application/xml", "text/xml", "text/plain"),
                        )
                    },
                    EmbeddedLyricsEditorAction("获取 ID") {
                        requestCurrentSongId(activity, idInput, nameInput)
                    },
                    EmbeddedLyricsEditorAction(
                        label = "从 AMLL 导入",
                        compactLabel = "AMLL 导入",
                    ) {
                        importOnline(EmbeddedOnlineSource.AMLL)
                    },
                    EmbeddedLyricsEditorAction(
                        label = "从 Lunabeat 导入",
                        compactLabel = "Lunabeat 导入",
                    ) {
                        importOnline(EmbeddedOnlineSource.LUNABEAT)
                    },
                    EmbeddedLyricsEditorAction(
                        label = "从 GitHub 导入",
                        compactLabel = "GitHub 导入",
                    ) {
                        importOnline(EmbeddedOnlineSource.AM_LYRICS)
                    },
                ),
            ),
            matchWidthWrapContent(),
        )
        fields.addView(ttmlInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(activity, 8) })
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(fields)
        }
        lateinit var dialog: AlertDialog
        fun saveLyrics() {
                val ids = CustomLyricsIdParser.parse(idInput.text.toString())
                if (ids == null) {
                    idInput.error = "请输入一个或多个正整数 Apple Music ID（用逗号分隔）"
                } else if (ttmlInput.text.toString().isBlank()) {
                    ttmlInput.error = "请输入或导入 TTML"
                } else {
                    runAsync(activity) {
                        saveMany(
                            CustomLyricsMultiIdDraft(
                                appleMusicIds = ids,
                                displayName = nameInput.text.toString(),
                                ttml = ttmlInput.text.toString(),
                                source = source,
                                enabled = entry?.enabled ?: true,
                            ),
                            group?.appleMusicIds.orEmpty(),
                        )
                    }
                    dialog.dismiss()
                }
        }
        val titleBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val horizontalPadding = dp(activity, if (isEmbeddedPhone(activity)) 16 else 20)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            addView(TextView(activity).apply {
                text = if (group == null) "新增歌词" else "编辑歌词"
                textSize = embeddedTextSize(activity, 19f, 18f)
                setTextColor(EmbeddedSettingsPalette.onSurface)
                setTypeface(typeface, if (isEmbeddedPhone(activity)) Typeface.BOLD else Typeface.NORMAL)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        val editorActions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            val horizontalPadding = dp(activity, if (isEmbeddedPhone(activity)) 16 else 12)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
        }
        fun editorAction(label: String, onClick: () -> Unit): TextView = TextView(activity).apply {
            text = label
            textSize = embeddedTextSize(activity, 16f, 14f)
            setTextColor(EmbeddedSettingsPalette.primary)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        editorActions.addView(
            editorAction("取消") { dialog.dismiss() },
            LinearLayout.LayoutParams(
                dp(activity, if (isEmbeddedPhone(activity)) 64 else 56),
                dp(activity, if (isEmbeddedPhone(activity)) 56 else 48),
            ),
        )
        editorActions.addView(
            editorAction("保存", ::saveLyrics),
            LinearLayout.LayoutParams(
                dp(activity, if (isEmbeddedPhone(activity)) 64 else 56),
                dp(activity, if (isEmbeddedPhone(activity)) 56 else 48),
            ),
        )
        val editorRoot = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = embeddedLyricsEditorDialogHeight(activity)
            background = GradientDrawable().apply {
                setColor(EmbeddedSettingsPalette.pageBackground)
                cornerRadius = embeddedCardCornerRadius(activity)
            }
            clipToOutline = true
            addView(titleBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                embeddedTopBarHeight(activity),
            ))
            addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
            addView(editorActions, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, if (isEmbeddedPhone(activity)) 56 else 48),
            ))
        }
        dialog = AlertDialog.Builder(activity)
            .setView(editorRoot)
            .create()
        dialog.setOnDismissListener {
            if (pendingTtmlImport != null) pendingTtmlImport = null
        }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(
                embeddedLyricsEditorDialogWidth(activity),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.show()
    }

    /** Compatibility overload used by the legacy embedded dialog path. */
    private fun showLyricsEditor(
        activity: Activity,
        entry: CustomLyricsEntry?,
        song: CurrentSongDetails?,
    ) = showLyricsEditor(
        activity,
        entry?.let { CustomLyricsUiGroup(listOf(it)) },
        song,
    )

    private fun requestCurrentSongId(
        activity: Activity,
        appleMusicId: EditText,
        displayName: EditText,
    ) {
        val currentSong = controller.currentSongDetails()
        if (currentSong == null) {
            Toast.makeText(
                activity,
                "未获取到当前歌曲信息，请先在 Apple Music 播放一首歌",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        appleMusicId.setText(currentSong.appleMusicId.toString())
        appleMusicId.setSelection(appleMusicId.length())
        listOfNotNull(
            currentSong.title?.takeIf(String::isNotBlank),
            currentSong.artist?.takeIf(String::isNotBlank),
        ).joinToString(" - ").takeIf(String::isNotBlank)?.let { value ->
            displayName.setText(value)
            displayName.setSelection(displayName.length())
        }
        Toast.makeText(activity, "已获取当前歌曲信息", Toast.LENGTH_SHORT).show()
    }

    /** Keeps the editor's multi-ID operation explicit at the host boundary. */
    private fun saveMany(
        draft: CustomLyricsMultiIdDraft,
        replacingAppleMusicIds: List<Long>,
    ): EmbeddedActionResult = controller.saveLyrics(draft, replacingAppleMusicIds)

    private fun importEmbeddedOnlineLyrics(
        activity: Activity,
        source: EmbeddedOnlineSource,
        appleMusicIdInput: EditText,
        ttmlInput: EditText,
        onImported: (String) -> Unit,
    ) {
        val appleMusicId = appleMusicIdInput.text.toString().toLongOrNull()
        when (source) {
            EmbeddedOnlineSource.AMLL,
            EmbeddedOnlineSource.AM_LYRICS,
            EmbeddedOnlineSource.LUNABEAT,
            -> if (appleMusicId == null || appleMusicId <= 0L) {
                appleMusicIdInput.error = "请输入正整数 Apple Music ID"
                return
            }
        }

        Toast.makeText(activity, "正在获取歌词…", Toast.LENGTH_SHORT).show()
        worker.execute {
            val result = runCatching {
                val importer = embeddedOnlineLyricsImporter()
                when (source) {
                    EmbeddedOnlineSource.AMLL -> importer.importAmll(requireNotNull(appleMusicId))
                    EmbeddedOnlineSource.AM_LYRICS -> importer.importAmLyrics(requireNotNull(appleMusicId))
                    EmbeddedOnlineSource.LUNABEAT -> importer.importLunabeat(requireNotNull(appleMusicId))
                }
            }.getOrElse {
                CustomLyricsOnlineImportResult.Failed(
                    it.message.orEmpty().ifBlank { "在线导入失败" },
                )
            }
            mainHandler.post {
                if (currentActivity() !== activity) return@post
                when (result) {
                    is CustomLyricsOnlineImportResult.Imported -> {
                        ttmlInput.setText(result.ttml)
                        onImported(result.source)
                        val reformatNote = if (result.reformatted) "，已自动转为 Apple Music 格式" else ""
                        Toast.makeText(
                            activity,
                            "已导入 ${embeddedLyricsSourceName(result.source)} 歌词$reformatNote，请确认后保存",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    is CustomLyricsOnlineImportResult.Failed -> {
                        Toast.makeText(activity, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun embeddedOnlineLyricsImporter(): CustomLyricsOnlineImporter = CustomLyricsOnlineImporter(
        fetchAmll = AmllTtmlClient(HttpLyricTransport())::fetch,
        fetchAmLyrics = AmLyricsClient(HttpLyricTransport())::fetch,
        fetchLunabeat = LunabeatClient(
            indexTransport = HttpLyricTransport(maxResponseBytes = LunabeatClient.INDEX_MAX_BYTES),
            lyricsTransport = HttpLyricTransport(),
            cache = FileLunabeatCatalogCache(File(application.filesDir, "ampp-lunabeat-cache")),
        )::fetch,
    )

    private fun embeddedLyricsSourceName(source: String): String = when (source) {
        CustomLyricsSources.AUTO_CACHE -> "自动缓存"
        CustomLyricsSources.AMLL -> "AMLL"
        CustomLyricsSources.AM_LYRICS -> "AM-Lyrics 仓库"
        CustomLyricsSources.LUNABEAT -> "Lunabeat"
        else -> "手动 TTML"
    }

    private fun launchSafPicker(
        activity: Activity,
        operation: EmbeddedSafOperation,
        mimeType: String,
        extraMimeTypes: Array<String>? = null,
    ) {
        val requestCode = safRouter.begin(operation)
        val intent = Intent(
            if (operation == EmbeddedSafOperation.Backup) {
                Intent.ACTION_CREATE_DOCUMENT
            } else {
                Intent.ACTION_OPEN_DOCUMENT
            },
        )
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeType)
        extraMimeTypes?.let { intent.putExtra(Intent.EXTRA_MIME_TYPES, it) }
        if (operation == EmbeddedSafOperation.Backup) {
            intent.putExtra(Intent.EXTRA_TITLE, "AMPP-lyrics-backup.zip")
        }
        runCatching { activity.startActivityForResult(intent, requestCode) }
            .onFailure {
                safRouter.route(requestCode, EmbeddedSafResult.RESULT_CANCELED, null)
                Toast.makeText(activity, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
            }
    }

    private fun handleSafSelection(operation: EmbeddedSafOperation, uri: Uri) {
        val activity = currentActivity() ?: return
        when (operation) {
            EmbeddedSafOperation.Font -> runAsync(activity) { controller.importFont(uri) }
            EmbeddedSafOperation.Ttml -> {
                val editorImport = pendingTtmlImport
                pendingTtmlImport = null
                if (editorImport != null) {
                    worker.execute {
                        val imported = controller.readTtml(uri)
                        mainHandler.post {
                            val current = currentActivity() ?: return@post
                            if (imported == null) {
                                Toast.makeText(
                                    current,
                                    "所选文件不是有效且不超过 512 KiB 的 TTML",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                editorImport(imported)
                                Toast.makeText(current, "TTML 已导入，请确认后保存", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    val song = controller.currentSongDetails()
                    if (song == null) {
                        Toast.makeText(activity, "尚未捕获当前歌曲", Toast.LENGTH_SHORT).show()
                    } else {
                        runAsync(activity) {
                            val replacing = controller.lyricsEntries()
                                .firstOrNull { it.appleMusicId == song.appleMusicId }
                                ?.appleMusicId
                            controller.importTtml(
                                uri,
                                song.appleMusicId,
                                song.title.orEmpty().ifBlank { song.appleMusicId.toString() },
                                replacing,
                            )
                        }
                    }
                }
            }
            EmbeddedSafOperation.Backup -> runAsync(activity) { controller.backupLyrics(uri) }
            EmbeddedSafOperation.RestoreOverwrite -> confirmEmbeddedRestore(activity, uri)
            EmbeddedSafOperation.RestoreKeepExisting -> runAsync(activity) {
                controller.restoreLyrics(uri, CustomLyricsRestorePolicy.KEEP_EXISTING)
            }
        }
    }

    private fun confirmEmbeddedRestore(activity: Activity, uri: Uri) {
        AlertDialog.Builder(activity)
            .setTitle("恢复歌词备份")
            .setMessage("覆盖：冲突歌词使用备份版本；不覆盖：冲突歌词保留当前版本。")
            .setNegativeButton("取消", null)
            .setNeutralButton("不覆盖") { _, _ ->
                runAsync(activity) {
                    controller.restoreLyrics(uri, CustomLyricsRestorePolicy.KEEP_EXISTING)
                }
            }
            .setPositiveButton("覆盖") { _, _ ->
                runAsync(activity) {
                    controller.restoreLyrics(uri, CustomLyricsRestorePolicy.OVERWRITE)
                }
            }
            .show()
    }

    private fun runAsync(activity: Activity, action: () -> EmbeddedActionResult) {
        Toast.makeText(activity, "处理中…", Toast.LENGTH_SHORT).show()
        worker.execute {
            val result = runCatching(action).getOrElse {
                EmbeddedActionResult.Failed(it.message.orEmpty().ifBlank { "操作失败" })
            }
            mainHandler.post {
                val current = currentActivity() ?: return@post
                val message = when (result) {
                    is EmbeddedActionResult.Done -> result.message
                    is EmbeddedActionResult.Failed -> result.message
                }
                Toast.makeText(
                    current,
                    message,
                    if (result is EmbeddedActionResult.Done) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                ).show()
                if (result is EmbeddedActionResult.Done) {
                    pageRefresh?.invoke() ?: dismissDialog()
                }
            }
        }
    }

    private fun removeOverlay(activity: Activity?) {
        val button = buttonReference?.get()
        if (button != null && (activity == null || belongsToActivity(button, activity))) {
            (button.parent as? ViewGroup)?.removeView(button)
            buttonReference = null
        } else if (button?.parent == null) {
            buttonReference = null
        }
        if (activity != null) {
            findTaggedView(activity, FLOATING_BUTTON_TAG)?.let { tagged ->
                (tagged.parent as? ViewGroup)?.removeView(tagged)
                if (buttonReference?.get() === tagged) buttonReference = null
            }
        }
    }

    private fun removeSettingsOption(activity: Activity?) {
        val option = settingsOptionReference?.get()
        if (option != null && (activity == null || belongsToActivity(option, activity))) {
            (option.parent as? ViewGroup)?.removeView(option)
            settingsOptionReference = null
        } else if (option?.parent == null) {
            settingsOptionReference = null
        }
        if (activity != null) {
            findTaggedViews(activity, SETTINGS_OPTION_TAG).forEach { tagged ->
                (tagged.parent as? ViewGroup)?.removeView(tagged)
                if (settingsOptionReference?.get() === tagged) settingsOptionReference = null
            }
        }
    }

    private fun removeInjectedViews(activity: Activity?) {
        removeOverlay(activity)
        removeSettingsOption(activity)
    }

    private fun dismissDialog() {
        pendingTtmlImport = null
        dialogReference?.get()?.dismiss()
        dialogReference = null
        pageRefresh = null
    }

    private fun currentActivity(): Activity? = activityReference?.get()

    private fun findTaggedView(activity: Activity, tag: String): View? {
        return findTaggedViews(activity, tag).firstOrNull()
    }

    private fun findTaggedViews(activity: Activity, tag: String): List<View> {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val decor = activity.window?.decorView as? ViewGroup
        val pending = ArrayDeque<View>()
        // decorView contains android.R.id.content on normal Activities. Use
        // one root only so the same tagged row is never visited twice.
        (content?.let(::listOf) ?: listOfNotNull(decor)).forEach(pending::addLast)
        val matches = ArrayList<View>()
        var visited = 0
        while (pending.isNotEmpty() && visited++ < MAX_TAGGED_VIEW_SCAN) {
            val view = pending.removeFirst()
            if (view.tag == tag) matches += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    pending.addLast(view.getChildAt(index))
                }
            }
        }
        return matches
    }

    private fun belongsToActivity(view: View, activity: Activity): Boolean {
        val decor = activity.window?.decorView ?: return false
        var current: View? = view
        while (current != null) {
            if (current === decor) return true
            current = current.parent as? View
        }
        return false
    }

    /** Draws user-supplied SVG paths without consulting Apple Music's package visibility. */
    private fun embeddedSvgDrawable(icon: EmbeddedSvgIcon): Drawable? =
        EmbeddedSettingsSvgAssets.drawable(icon)

    private fun loadEmbeddedArrowIcon(context: Context): Drawable = sequenceOf(
        ModuleConstants.MODULE_PACKAGE,
        "${ModuleConstants.MODULE_PACKAGE}.debug",
    ).mapNotNull { packageName ->
        runCatching {
            val moduleContext = context.createPackageContext(
                packageName,
                Context.CONTEXT_IGNORE_SECURITY,
            )
            moduleContext.resources.getDrawable(
                dev.amenhancer.module.R.drawable.ic_arrow_back,
                moduleContext.theme,
            )
        }.getOrNull()
    }.firstOrNull() ?: EmbeddedArrowFallbackDrawable()

    private fun embeddedWidthDp(activity: Activity): Float {
        val widthDp = activity.resources.configuration.screenWidthDp
        if (widthDp > 0) return widthDp.toFloat()
        val density = activity.resources.displayMetrics.density.coerceAtLeast(1f)
        return activity.resources.displayMetrics.widthPixels / density
    }

    private fun isEmbeddedPhone(activity: Activity): Boolean = embeddedWidthDp(activity) < 600f

    private fun embeddedTextSize(activity: Activity, phone: Float, wide: Float): Float =
        if (isEmbeddedPhone(activity)) phone else wide

    /**
     * The supplied reference is a 1280dp-wide tablet composition: 626dp for
     * the main panel and 503dp for the lyrics page.  Phones keep Android's
     * normal dialog sizing so text and touch targets remain usable.
     */
    private fun embeddedDialogWidth(activity: Activity, page: EmbeddedSettingsPage): Int? {
        if (isEmbeddedPhone(activity)) return null
        // AlertDialog applies a 16dp inset on each side. These fractions target
        // the visible white panel after that inset, not the outer window.
        val fraction = if (page == EmbeddedSettingsPage.CUSTOM_LYRICS) 0.418f else 0.514f
        return (activity.resources.displayMetrics.widthPixels * fraction).toInt()
    }

    private fun embeddedDialogContentHeight(activity: Activity, page: EmbeddedSettingsPage): Int {
        val height = activity.resources.displayMetrics.heightPixels
        if (isEmbeddedPhone(activity)) return (height * 0.70f).toInt()
        val fraction = if (page == EmbeddedSettingsPage.CUSTOM_LYRICS) 0.888f else 0.837f
        return (height * fraction).toInt()
    }

    private fun embeddedLyricsEditorDialogWidth(activity: Activity): Int {
        val targetWidthDp = embeddedWidthDp(activity) *
            if (isEmbeddedPhone(activity)) 0.88f else 0.62f
        return dp(activity, targetWidthDp.toInt())
    }

    private fun embeddedLyricsEditorDialogHeight(activity: Activity): Int =
        embeddedDialogContentHeight(activity, EmbeddedSettingsPage.CUSTOM_LYRICS)

    private fun embeddedTopBarHeight(activity: Activity): Int =
        dp(activity, if (isEmbeddedPhone(activity)) 60 else 52)

    private fun embeddedHeaderIconSize(activity: Activity): Int =
        dp(activity, if (isEmbeddedPhone(activity)) 40 else 34)

    private fun embeddedFeatureIconSize(activity: Activity): Int =
        dp(activity, if (isEmbeddedPhone(activity)) 40 else 36)

    private fun embeddedSettingRowHeight(activity: Activity, compactWide: Boolean = false): Int =
        dp(activity, if (isEmbeddedPhone(activity)) 72 else if (compactWide) 68 else 56)

    private fun embeddedNavigationRowHeight(activity: Activity, compactWide: Boolean = false): Int =
        dp(activity, if (isEmbeddedPhone(activity)) 72 else if (compactWide) 60 else 52)

    private fun embeddedSearchFieldHeight(activity: Activity): Int =
        dp(activity, if (isEmbeddedPhone(activity)) 48 else 44)

    private fun embeddedActionOrientation(activity: Activity): Int =
        if (isEmbeddedPhone(activity)) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

    private fun embeddedActionButtonParams(activity: Activity): LinearLayout.LayoutParams =
        if (isEmbeddedPhone(activity)) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48))
        } else {
            LinearLayout.LayoutParams(0, dp(activity, 48), 1f)
        }

    private fun embeddedSwitchThumbColors(): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        ),
        intArrayOf(EmbeddedSettingsPalette.primary, EmbeddedSettingsPalette.disabledText),
    )

    private fun embeddedSwitchTrackColors(): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        ),
        intArrayOf(EmbeddedSettingsPalette.switchTrackOn, EmbeddedSettingsPalette.switchTrackOff),
    )

    private fun activityKey(activity: Activity): String =
        Integer.toHexString(System.identityHashCode(activity))

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun embeddedCardCornerRadius(activity: Activity): Float =
        dp(activity, 8).toFloat()

    private fun matchWidthWrapContent(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

    companion object {
        const val PLAYER_ACTIVITY_NAME = "com.apple.android.music.common.activity.PlayerActivity"
        const val MAIN_CONTENT_ACTIVITY_NAME = "com.apple.android.music.common.MainContentActivity"
        const val FLOATING_BUTTON_TAG = "ampp_embedded_settings_button"
        const val SETTINGS_OPTION_TAG = "ampp_embedded_settings_option"
        const val NATIVE_SETTINGS_PREFERENCE_KEY = "ampp_embedded_settings_preference"
        private const val NATIVE_PREFERENCE_FALLBACK_DELAY_MS = 220L
        private const val MAX_NATIVE_PREFERENCE_SCAN = 256
        private const val MAX_TAGGED_VIEW_SCAN = 4096

        fun install(
            application: Application,
            controller: EmbeddedSettingsController,
            safRouter: EmbeddedSafResultRouter = EmbeddedSafResultRouter(),
            selectionHandler: EmbeddedSafSelectionHandler = EmbeddedSafSelectionHandler { _, _ -> },
            playerActivityClass: Class<*>? = null,
        ): EmbeddedSettingsHost = EmbeddedSettingsHost(
            application = application,
            controller = controller,
            safRouter = safRouter,
            selectionHandler = selectionHandler,
            activityMatcher = EmbeddedActivityMatcher(playerActivityClass),
        ).also(application::registerActivityLifecycleCallbacks)
    }
}
