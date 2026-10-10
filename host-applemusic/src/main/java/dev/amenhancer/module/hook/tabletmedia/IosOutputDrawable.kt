package dev.amenhancer.module.hook.tabletmedia

// Adapted from Kifranei/AM-plus-plus-media-plugin, 9e3f72b (GPL-3.0).
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.host.applemusic.AppleMusicHostProfiles

import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser


/** Ordinary plugin asset, independent of Android XML resource loading. */
internal class IosOutputDrawable(private val path: Path, private val density: Float,
    private val viewportWidth: Float = 821f, private val viewportHeight: Float = 808f) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var tint: ColorStateList? = null
    override fun getIntrinsicWidth() = (24 * density).toInt()
    override fun getIntrinsicHeight() = (24 * density).toInt()
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / viewportWidth, bounds.height() / viewportHeight)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    override fun setTintList(tint: ColorStateList?) { this.tint = tint; onStateChange(state) }
    override fun isStateful() = tint?.isStateful == true
    override fun onStateChange(state: IntArray): Boolean {
        val color = tint?.getColorForState(state, tint!!.defaultColor) ?: Color.WHITE
        if (paint.color == color) return false
        paint.color = color
        invalidateSelf()
        return true
    }
    @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
    companion object {
        fun read(context: TabletMediaAssets, asset: String = "ios-media-output.path"): Path = context.openAsset(asset).bufferedReader().use {
            checkNotNull(PathParser.createPathFromPathData(it.readText()))
        }
    }
}
