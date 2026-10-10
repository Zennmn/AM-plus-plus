package dev.amenhancer.module.hook.tabletmedia

// Adapted from Kifranei/AM-plus-plus-media-plugin, 9e3f72b (GPL-3.0).
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.host.applemusic.AppleMusicHostProfiles

import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

import kotlin.math.roundToInt

internal enum class OutputDeviceIcon { SYSTEM, HEADPHONES, AIRPODS, SPEAKER }
internal data class BluetoothOutputState(val name: String, val icon: OutputDeviceIcon)

/** Keep one native indicator instance; change its contents when the connected device changes. */
internal class IosDeviceOutputDrawable(private val children: Map<OutputDeviceIcon, Drawable>, private val density: Float) : Drawable(), Drawable.Callback {
    var kind = OutputDeviceIcon.SYSTEM
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    private val selected get() = children.getValue(kind)
    init { children.values.forEach { it.callback = this; it.setTint(Color.WHITE) } }
    override fun getIntrinsicWidth() = (24 * density).roundToInt()
    override fun getIntrinsicHeight() = (24 * density).roundToInt()
    override fun draw(canvas: Canvas) {
        val child = selected
        val width = child.intrinsicWidth.coerceAtLeast(1)
        val height = child.intrinsicHeight.coerceAtLeast(1)
        val scale = minOf(bounds.width().toFloat() / width, bounds.height().toFloat() / height)
        val w = (width * scale).roundToInt(); val h = (height * scale).roundToInt()
        val left = bounds.left + (bounds.width() - w) / 2; val top = bounds.top + (bounds.height() - h) / 2
        child.bounds = Rect(left, top, left + w, top + h)
        child.draw(canvas)
    }
    override fun setAlpha(alpha: Int) { children.values.forEach { it.alpha = alpha } }
    override fun setColorFilter(colorFilter: ColorFilter?) { children.values.forEach { it.colorFilter = colorFilter } }
    override fun setTintList(tint: ColorStateList?) { children.values.forEach { it.setTintList(tint ?: ColorStateList.valueOf(Color.WHITE)) } }
    override fun setTintMode(tintMode: android.graphics.PorterDuff.Mode?) {
        children.values.forEach { it.setTintMode(tintMode ?: android.graphics.PorterDuff.Mode.SRC_IN) }
    }
    override fun isStateful() = children.values.any(Drawable::isStateful)
    override fun onStateChange(state: IntArray): Boolean {
        var changed = false
        children.values.forEach { if (it.setState(state)) changed = true }
        return changed
    }
    @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun invalidateDrawable(who: Drawable) = invalidateSelf()
    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)
    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)

    class Factory(context: TabletMediaAssets, private val resources: Resources) {
        private val outputPath = IosOutputDrawable.read(context)
        private val headphonesPath = IosOutputDrawable.read(context, "ios-output-headphones.path")
        private fun bitmap(context: TabletMediaAssets, name: String) = context.openAsset(name).use {
            checkNotNull(BitmapFactory.decodeStream(it)).apply { density = Bitmap.DENSITY_NONE }
        }
        private val speaker = bitmap(context, "ios-output-speaker.png")
        private val airpods = bitmap(context, "ios-output-airpods.png")
        fun create(): IosDeviceOutputDrawable {
            val density = resources.displayMetrics.density
            fun image(bitmap: Bitmap) = BitmapDrawable(resources, bitmap).apply { isFilterBitmap = true }
            return IosDeviceOutputDrawable(mapOf(
                OutputDeviceIcon.SYSTEM to IosOutputDrawable(outputPath, density),
                OutputDeviceIcon.HEADPHONES to IosOutputDrawable(headphonesPath, density, 32f, 32f),
                OutputDeviceIcon.SPEAKER to image(speaker), OutputDeviceIcon.AIRPODS to image(airpods)), density)
        }
    }
}
