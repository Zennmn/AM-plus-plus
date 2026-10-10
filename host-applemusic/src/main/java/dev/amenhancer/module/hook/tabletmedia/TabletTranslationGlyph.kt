package dev.amenhancer.module.hook.tabletmedia

// Adapted from Kifranei/AM-plus-plus-media-plugin, 9e3f72b (GPL-3.0).

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser
import org.xmlpull.v1.XmlPullParser

/** The native off vector also contains a square backdrop. Keep its glyph path only. */
internal class TabletTranslationGlyph private constructor(private val path: Path, private val viewport: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / viewport, bounds.height() / viewport)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    override fun setTint(tintColor: Int) { paint.color = tintColor; invalidateSelf() }
    @Suppress("DEPRECATION") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    companion object {
        fun read(resources: Resources, id: Int): Drawable? = runCatching {
            val ns = "http://schemas.android.com/apk/res/android"
            resources.getXml(id).use { parser ->
                var viewport = 0f
                var glyph: Path? = null
                var backgrounds = 0
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != XmlPullParser.START_TAG) continue
                    when (parser.name) {
                        "vector" -> viewport = parser.getAttributeFloatValue(ns, "viewportWidth", 0f)
                        "path" -> {
                            if (parser.getAttributeFloatValue(ns, "fillAlpha", 1f) < .5f) { backgrounds++; continue }
                            check(glyph == null)
                            glyph = checkNotNull(PathParser.createPathFromPathData(parser.getAttributeValue(ns, "pathData"))).apply {
                                if (parser.getAttributeValue(ns, "fillType") in setOf("evenOdd", "1")) fillType = Path.FillType.EVEN_ODD
                            }
                        }
                    }
                }
                check(viewport == 36f && backgrounds == 1)
                TabletTranslationGlyph(checkNotNull(glyph), viewport)
            }
        }.getOrNull()
    }
}
