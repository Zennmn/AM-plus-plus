package dev.amenhancer.module.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

/**
 * Resource-independent, 24 x 24 SVG glyphs for the embedded settings UI.
 *
 * Icons use a shared 1.75-unit rounded stroke. The application mark is owned by
 * [EmbeddedAmppBrandDrawable] and intentionally is not part of this catalogue.
 */
internal enum class EmbeddedSvgIcon {
    // Base actions and shared controls.
    AddLyrics,
    ImportTtml,
    GitHubSync,
    BackupRestore,
    Back,
    RestoreDefault,
    ApiEndpoint,
    ApiKey,
    Model,
    Thinking,
    Music,
    Exchange,
    DocumentSearch,
    TabletDualPane,
    BottomBar,
    Glass,
    LyricsBlur,
    VideoDisplay,
    Refresh,
    AddCircle,
    TtmlDocument,
    Search,
    Edit,
    Delete,
    ChevronRight,
    MoreVertical,
    DragHandle,
    Document,

    // Dedicated settings semantics.
    HideStatusBar,
    Cellular,
    NavigationFix,
    BarCollapse,
    Karaoke,
    LyricEffects,
    TitleCorrection,
    TitleMode,
    CustomLyrics,
    StartupPrewarm,
    BarAnimation,
    Plugins,
    AutomaticLyrics,
    Translation,

    // Widgets and content states.
    DisplayDpi,
    BarHeight,
    BarBlur,
    BlurRadius,
    Settings,
    Power,
    Conflict,
}

/** Marks drawables that own their SVG fill rather than inheriting ImageView tint. */
internal interface EmbeddedOwnColorDrawable

private data class EmbeddedSvgSpec(
    val pathData: String,
    val fillColor: Int = EmbeddedSettingsPalette.Light.primary,
    val viewport: Float = 24f,
    val strokeWidth: Float = DEFAULT_STROKE,
) {
    val path: Path by lazy {
        checkNotNull(PathParser.createPathFromPathData(pathData)) { "Invalid embedded SVG path" }
    }

    fun strokePaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = this@EmbeddedSvgSpec.strokeWidth
    }

    val endInsetFraction: Float by lazy {
        val stroke = Path()
        strokePaint().getFillPath(path, stroke)
        val bounds = RectF()
        stroke.computeBounds(bounds, true)
        ((viewport - bounds.right) / viewport).coerceIn(0f, 1f)
    }
}

private const val DEFAULT_STROKE = 1.75f

private fun stroked(pathData: String, strokeWidth: Float = DEFAULT_STROKE): EmbeddedSvgSpec =
    EmbeddedSvgSpec(pathData = pathData, strokeWidth = strokeWidth)

/**
 * All functional icons are authored directly as SVG path data. Paths below use
 * line, cubic, quadratic, and arc segments where they make a cleaner curve than
 * a polyline trace.
 */
internal object EmbeddedSettingsSvgAssets {
    private val specs: Map<EmbeddedSvgIcon, EmbeddedSvgSpec> = mapOf(
        EmbeddedSvgIcon.AddLyrics to stroked(
            "M7 3.5H14.5L19 8V19Q19 20.5 17.5 20.5H7Q5.5 20.5 5.5 19V5Q5.5 3.5 7 3.5Z " +
                "M14.5 3.5V8H19 " +
                "M12 10.5v6 M9 13.5h6",
        ),
        EmbeddedSvgIcon.ImportTtml to stroked(
            "M7 3.5H14.5L19 8V19Q19 20.5 17.5 20.5H7Q5.5 20.5 5.5 19V5Q5.5 3.5 7 3.5Z " +
                "M14.5 3.5V8H19 " +
                "M12 10.5v6 M9 13.5l3 3 3-3",
        ),
        EmbeddedSvgIcon.GitHubSync to stroked(
            "M8 5.5a2 2 0 1 1-4 0 2 2 0 0 1 4 0Z " +
                "M8 18.5a2 2 0 1 1-4 0 2 2 0 0 1 4 0Z " +
                "M20 5.5a2 2 0 1 1-4 0 2 2 0 0 1 4 0Z " +
                "M6 7.5v9 M18 7.5v2.5a3 3 0 0 1-3 3H6",
        ),
        EmbeddedSvgIcon.BackupRestore to stroked(
            "M7.2 18.5h10.1a3.7 3.7 0 0 0 .5-7.36A5.3 5.3 0 0 0 7.6 9.4 " +
                "a4.55 4.55 0 0 0-.4 9.1Z " +
                "M12 15.5v-6 M9.5 12 12 9.5l2.5 2.5",
        ),
        EmbeddedSvgIcon.Back to stroked(
            "M19.5 12H4.5 M11 5.5 4.5 12 11 18.5",
        ),
        EmbeddedSvgIcon.RestoreDefault to stroked(
            "M4 9.5C5.1 6.3 8.3 4 12 4a8 8 0 1 1-6.5 12.5 " +
                "M4 4.5v5h5",
        ),
        EmbeddedSvgIcon.ApiEndpoint to stroked(
            "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Z " +
                "M3.5 12h17 " +
                "M12 3c2.2 2.45 3.35 5.45 3.35 9S14.2 18.55 12 21 " +
                "M12 3C9.8 5.45 8.65 8.45 8.65 12S9.8 18.55 12 21",
        ),
        EmbeddedSvgIcon.ApiKey to stroked(
            "M12.5 8a4.5 4.5 0 1 1-9 0 4.5 4.5 0 0 1 9 0Z " +
                "M11.2 11.2l9.3 9.3 M17.5 17.5l2.5-2.5",
        ),
        EmbeddedSvgIcon.Model to stroked(
            "M12 3.5 19.5 7.7v8.6L12 20.5l-7.5-4.2V7.7Z " +
                "M4.5 7.7l7.5 4.2 7.5-4.2 M12 11.9v8.6",
        ),
        EmbeddedSvgIcon.Thinking to stroked(
            "M12 3.2 13.2 6.5 16.5 7.7l-3.3 1.2L12 12.2l-1.2-3.3L7.5 7.7l3.3-1.2Z " +
                "M17.8 13.7l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8Z " +
                "M6.2 14.3l.6 1.7 1.7.6-1.7.6-.6 1.7-.6-1.7-1.7-.6 1.7-.6Z",
        ),
        EmbeddedSvgIcon.Music to stroked(
            "M9 17.5V5.8l9-2v10.7 " +
                "M9 8l9-2 " +
                "M9 17.5a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0Z " +
                "M18 14.5a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0Z",
        ),
        EmbeddedSvgIcon.Exchange to stroked(
            "M4.5 8h14 M15.5 5l3 3-3 3 " +
                "M19.5 16h-14 M8.5 13l-3 3 3 3",
        ),
        EmbeddedSvgIcon.DocumentSearch to stroked(
            "M9.5 20.5h-3a2 2 0 0 1-2-2v-13a2 2 0 0 1 2-2h7l4 4v3 " +
                "M13.5 3.5v4h4 M7.5 10h3.5 " +
                "M18.5 16a3.5 3.5 0 1 1-7 0 3.5 3.5 0 0 1 7 0Z " +
                "M17.5 18.5 21 22",
        ),
        EmbeddedSvgIcon.TabletDualPane to stroked(
            "M5.5 5h13a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z " +
                "M11 5v14 M6.5 9h1.5 M6.5 12h1.5 M14 9h3.5 M14 12h2.5",
        ),
        EmbeddedSvgIcon.BottomBar to stroked(
            "M7 3.5H17Q18.5 3.5 18.5 5V19Q18.5 20.5 17 20.5H7Q5.5 20.5 5.5 19V5Q5.5 3.5 7 3.5Z M5.5 16.5h13 " +
                "M9 18.5h.01 M12 18.5h.01 M15 18.5h.01",
        ),
        EmbeddedSvgIcon.Glass to stroked(
            "M5 8H14Q15 8 15 9V16Q15 17 14 17H5Q4 17 4 16V9Q4 8 5 8Z " +
                "M10 5H19Q20 5 20 6V13Q20 14 19 14H18 " +
                "M7 10.5h4 M13 7.5h4",
        ),
        EmbeddedSvgIcon.LyricsBlur to stroked(
            "M6 5.5h12 M4 12h16 M6 18.5h12 " +
                "M3 5.5h.01 M21 5.5h.01 M3 18.5h.01 M21 18.5h.01",
        ),
        EmbeddedSvgIcon.VideoDisplay to stroked(
            "M5 5Q3.5 5 3.5 6.5V15.5Q3.5 17 5 17H19Q20.5 17 20.5 15.5V6.5Q20.5 5 19 5Z " +
                "M8.5 21h7 M12 17v4 " +
                "M10 8.5l4.5 2.5-4.5 2.5Z",
        ),
        EmbeddedSvgIcon.Refresh to stroked(
            "M4.5 8.5A8 8 0 0 1 17.8 6L20 8.5 M20 4v4.5h-4.5 " +
                "M19.5 15.5A8 8 0 0 1 6.2 18L4 15.5 M4 20v-4.5h4.5",
        ),
        EmbeddedSvgIcon.AddCircle to stroked(
            "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17Z " +
                "M12 8v8 M8 12h8",
        ),
        EmbeddedSvgIcon.TtmlDocument to stroked(
            "M7 3.5H14.5L19 8V19Q19 20.5 17.5 20.5H7Q5.5 20.5 5.5 19V5Q5.5 3.5 7 3.5Z M14.5 3.5V8H19 " +
                "M9.5 12.5l-2 2 2 2 M14.5 12.5l2 2-2 2",
        ),
        EmbeddedSvgIcon.Search to stroked(
            "M10.5 4a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13Z " +
                "M15.2 15.2 21 21",
        ),
        EmbeddedSvgIcon.Edit to stroked(
            "M16 4a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z " +
                "M14.5 5.5l3 3 M4 16l3 3",
        ),
        EmbeddedSvgIcon.Delete to stroked(
            "M4.5 6.5h15 M9 6.5V5a1.5 1.5 0 0 1 1.5-1.5h3A1.5 1.5 0 0 1 15 5v1.5 " +
                "M6.5 6.5l.9 12.6A1.5 1.5 0 0 0 8.9 20.5h6.2a1.5 1.5 0 0 0 1.5-1.4l.9-12.6 " +
                "M10 10.5v6 M14 10.5v6",
        ),
        EmbeddedSvgIcon.ChevronRight to stroked("M9 5.5 15.5 12 9 18.5"),
        EmbeddedSvgIcon.MoreVertical to stroked(
            "M12 5.5h.01 M12 12h.01 M12 18.5h.01",
            2.8f,
        ),
        EmbeddedSvgIcon.DragHandle to stroked(
            "M9 7h.01 M15 7h.01 M9 12h.01 M15 12h.01 M9 17h.01 M15 17h.01",
            2.4f,
        ),
        EmbeddedSvgIcon.Document to stroked(
            "M7 3.5H14.5L19 8V19Q19 20.5 17.5 20.5H7Q5.5 20.5 5.5 19V5Q5.5 3.5 7 3.5Z M14.5 3.5V8H19 " +
                "M9 12h6 M9 15.5h6",
        ),
        EmbeddedSvgIcon.HideStatusBar to stroked(
            "M11.5 5h-6a2 2 0 0 0-2 2v11a2 2 0 0 0 2 2h13a2 2 0 0 0 2-2v-6.5 " +
                "M3.5 9.5h9 M6.5 7.3H9 " +
                "M15.5 3.5l5 5 M20.5 3.5l-5 5",
        ),
        EmbeddedSvgIcon.Cellular to stroked(
            "M4.5 19v-3 M9 19v-6 M13.5 19V8.5 M18 19V4",
        ),
        EmbeddedSvgIcon.NavigationFix to stroked(
            "M6 3.5H18Q19.5 3.5 19.5 5V19Q19.5 20.5 18 20.5H6Q4.5 20.5 4.5 19V5Q4.5 3.5 6 3.5Z M4.5 16h15 " +
                "M12 13V6.5 M9.5 9 12 6.5 14.5 9",
        ),
        EmbeddedSvgIcon.BarCollapse to stroked(
            "M6 3.5H18Q19.5 3.5 19.5 5V19Q19.5 20.5 18 20.5H6Q4.5 20.5 4.5 19V5Q4.5 3.5 6 3.5Z M4.5 15.5h15 " +
                "M9 9.5l3 3 3-3",
        ),
        EmbeddedSvgIcon.Karaoke to stroked(
            "M12 3.5a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0v-5a3 3 0 0 1 3-3Z " +
                "M6.5 11.5a5.5 5.5 0 0 0 11 0 M12 17v3.5 M8.5 20.5h7 " +
                "M10.5 7.5h3 M10.5 10.5h3",
        ),
        EmbeddedSvgIcon.LyricEffects to stroked(
            "M10.5 20h-4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v4 " +
                "M8 8.5h7 M8 12h3.5 M8 15.5h2 " +
                "M17.5 12.5l1.1 2.9 2.9 1.1-2.9 1.1-1.1 2.9-1.1-2.9-2.9-1.1 2.9-1.1Z",
        ),
        EmbeddedSvgIcon.TitleCorrection to stroked(
            "M3.5 18 7.75 5.5 12 18 M5 13.5h5.5 " +
                "M14 15.5l2.3 2.3L21 12.5",
        ),
        EmbeddedSvgIcon.TitleMode to stroked(
            "M10.5 18.5a8 8 0 1 1 7.7-10.2 M2.5 10.5h11 " +
                "M10.5 2.5c-2 2.3-3 5-3 8s1 5.7 3 8 " +
                "M10.5 2.5c1.6 1.8 2.5 3.7 2.8 5.5 " +
                "M17.5 21.5c-1.5-1.8-4-4.3-4-6.5a4 4 0 0 1 8 0c0 2.2-2.5 4.7-4 6.5Z " +
                "M17.5 15h.01",
        ),
        EmbeddedSvgIcon.CustomLyrics to stroked(
            "M9.5 20.5H6.5a2 2 0 0 1-2-2v-13a2 2 0 0 1 2-2h7l4 4V9 " +
                "M13.5 3.5v4h4 M7.5 10h5 M7.5 13.5h3 " +
                "M18 18v-6.5l3-.9v3.2 " +
                "M18 18a2.5 1.8 0 1 1-5 0 2.5 1.8 0 0 1 5 0Z",
        ),
        EmbeddedSvgIcon.StartupPrewarm to stroked(
            "M6 3.5H18Q19.5 3.5 19.5 5V19Q19.5 20.5 18 20.5H6Q4.5 20.5 4.5 19V5Q4.5 3.5 6 3.5Z " +
                "M13.5 7.5 8.5 13h3l-1 3.5 5-5.5h-3Z",
        ),
        EmbeddedSvgIcon.BarAnimation to stroked(
            "M3.5 16c2 0 2-10 4-10s2 12 4 12 2-7 4-7 2 5 5 5 " +
                "M3.5 21h17",
        ),
        EmbeddedSvgIcon.Plugins to stroked(
            "M6.5 8H9V5.5a2.5 2.5 0 0 1 5 0V8h3.5q1 0 1 1v3h-2 " +
                "a2.5 2.5 0 0 0 0 5h2v2q0 1-1 1h-11q-1 0-1-1V9q0-1 1-1Z",
        ),
        EmbeddedSvgIcon.AutomaticLyrics to stroked(
            "M9.5 20.5h-3a2 2 0 0 1-2-2v-13a2 2 0 0 1 2-2h7l4 4V10 " +
                "M13.5 3.5v4h4 M7.5 10h3.5 M7.5 13.5h1.5 " +
                "M12.5 14a4.5 4.5 0 1 1-.3 4.3 M12.5 10.5V14H16",
        ),
        EmbeddedSvgIcon.Translation to stroked(
            "M3.5 5h9 M8 3.5V5 M5.5 8.5c1.4 2.8 3.8 5 7 6.4 " +
                "M11.5 7.5c-1.4 3.5-4.2 6.2-8 8 " +
                "M12.5 20.5l3.5-9 3.5 9 M13.8 17.5h4.4",
        ),
        EmbeddedSvgIcon.DisplayDpi to stroked(
            "M5 5Q3.5 5 3.5 6.5V16.5Q3.5 18 5 18H19Q20.5 18 20.5 16.5V6.5Q20.5 5 19 5Z " +
                "M8.5 21h7 M12 18v3 " +
                "M8 9.5h.01 M12 9.5h.01 M16 9.5h.01 " +
                "M8 13.5h.01 M12 13.5h.01 M16 13.5h.01",
        ),
        EmbeddedSvgIcon.BarHeight to stroked(
            "M4 4h16 M4 20h16 M12 6.5v11 " +
                "M9 9.5l3-3 3 3 M9 14.5l3 3 3-3",
        ),
        EmbeddedSvgIcon.BarBlur to stroked(
            "M4 16.5h16 M4 19.5h16 " +
                "M5.5 10c2-3.2 4-3.2 6 0s4 3.2 6 0 " +
                "M7.5 13c1.3-2 2.7-2 4 0s2.7 2 4 0",
        ),
        EmbeddedSvgIcon.BlurRadius to stroked(
            "M12 11.5h.01 " +
                "M12 7a5 5 0 1 1 0 10 5 5 0 0 1 0-10Z " +
                "M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18Z",
        ),
        EmbeddedSvgIcon.Settings to stroked(
            "M4 6.5h2 M10 6.5h10 M4 12h9 M17 12h3 M4 17.5h3.5 M11.5 17.5H20 " +
                "M8 4.5a2 2 0 1 0 0 4 2 2 0 0 0 0-4Z " +
                "M15 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4Z " +
                "M9.5 15.5a2 2 0 1 0 0 4 2 2 0 0 0 0-4Z",
        ),
        EmbeddedSvgIcon.Power to stroked(
            "M12 3.5v8 " +
                "M7.3 6.2a7.2 7.2 0 1 0 9.4 0",
        ),
        EmbeddedSvgIcon.Conflict to stroked(
            "M12 3.5 21 19.5H3Z " +
                "M12 8v5.5 M12 16.5h.01",
        ),
    )

    fun drawable(
        icon: EmbeddedSvgIcon,
        fillColor: Int? = null,
        followsPalette: Boolean = false,
    ): Drawable? = runCatching {
        EmbeddedSvgPathDrawable(
            icon = icon,
            spec = specs.getValue(icon),
            initialFillColor = fillColor,
            tintOriginal = null,
            followsPalette = followsPalette,
        )
    }.getOrNull()

    fun glyphDrawable(icon: EmbeddedSvgIcon, tintOriginal: Int): Drawable? = runCatching {
        EmbeddedSvgPathDrawable(
            icon = icon,
            spec = specs.getValue(icon),
            initialFillColor = null,
            tintOriginal = tintOriginal,
            followsPalette = false,
        )
    }.getOrNull()

    /** Optical whitespace comes from the same stroked path used for drawing. */
    fun endInset(icon: EmbeddedSvgIcon, iconSize: Int): Float =
        specs.getValue(icon).endInsetFraction * iconSize
}

internal fun EmbeddedGlyphKind.toSvgIcon(): EmbeddedSvgIcon = when (this) {
    EmbeddedGlyphKind.Music -> EmbeddedSvgIcon.Music
    EmbeddedGlyphKind.Exchange -> EmbeddedSvgIcon.Exchange
    EmbeddedGlyphKind.Github -> EmbeddedSvgIcon.GitHubSync
    EmbeddedGlyphKind.CloudBackup -> EmbeddedSvgIcon.BackupRestore
    EmbeddedGlyphKind.DocumentSearch -> EmbeddedSvgIcon.DocumentSearch
    EmbeddedGlyphKind.TabletDualPane -> EmbeddedSvgIcon.TabletDualPane
    EmbeddedGlyphKind.BottomBar -> EmbeddedSvgIcon.BottomBar
    EmbeddedGlyphKind.VideoDisplay -> EmbeddedSvgIcon.VideoDisplay
    EmbeddedGlyphKind.Glass -> EmbeddedSvgIcon.Glass
    EmbeddedGlyphKind.LyricsBlur -> EmbeddedSvgIcon.LyricsBlur
    EmbeddedGlyphKind.Document -> EmbeddedSvgIcon.Document
    EmbeddedGlyphKind.Translate -> EmbeddedSvgIcon.Translation
    EmbeddedGlyphKind.Refresh -> EmbeddedSvgIcon.Refresh
    EmbeddedGlyphKind.AddCircle -> EmbeddedSvgIcon.AddCircle
    EmbeddedGlyphKind.TtmlDocument -> EmbeddedSvgIcon.TtmlDocument
    EmbeddedGlyphKind.Search -> EmbeddedSvgIcon.Search
    EmbeddedGlyphKind.Edit -> EmbeddedSvgIcon.Edit
    EmbeddedGlyphKind.Delete -> EmbeddedSvgIcon.Delete
    EmbeddedGlyphKind.BackArrow -> EmbeddedSvgIcon.Back
    EmbeddedGlyphKind.ChevronRight -> EmbeddedSvgIcon.ChevronRight
    EmbeddedGlyphKind.MoreVertical -> EmbeddedSvgIcon.MoreVertical
}

private class EmbeddedSvgPathDrawable(
    private val icon: EmbeddedSvgIcon,
    private val spec: EmbeddedSvgSpec,
    initialFillColor: Int?,
    private val tintOriginal: Int?,
    private val followsPalette: Boolean,
) : Drawable(), EmbeddedOwnColorDrawable, EmbeddedPaletteDrawable {
    private var fillColor = initialFillColor ?: spec.fillColor
    private val path = spec.path
    private val paint = spec.strokePaint()
    private var alphaValue = 255
    private var colorFilterValue: ColorFilter? = null

    override fun applyPalette(colors: EmbeddedSettingsColors) {
        fillColor = when {
            tintOriginal != null -> colors.iconColor(tintOriginal)
            followsPalette -> embeddedSvgColor(icon, colors)
            else -> fillColor
        }
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val box = bounds
        if (box.width() <= 0 || box.height() <= 0) return
        val size = minOf(box.width(), box.height()).toFloat()
        val left = box.left + (box.width() - size) / 2f
        val top = box.top + (box.height() - size) / 2f
        val scale = size / spec.viewport
        paint.color = fillColor
        paint.alpha = alphaValue
        paint.colorFilter = colorFilterValue

        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        canvas.drawPath(path, paint)
        canvas.restore()
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
