package dev.amenhancer.module.hook.tabletmedia

// Adapted from Kifranei/AM-plus-plus-media-plugin, 9e3f72b (GPL-3.0).
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.host.applemusic.AppleMusicHostProfiles

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/** iOS capsule track, backed by the actual STREAM_MUSIC level, with no separate player gain. */
internal class IosVolumeSliderView(context: Context) : View(context), AutoCloseable {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icons = IosVolumeIcons.read(TabletMediaAssets)
    private val density get() = resources.displayMetrics.density
    private var slop = ViewConfiguration.get(context).scaledTouchSlop
    private var registered = false
    private var active = false
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var minimum = 0
    private var maximum = 0
    private var level = -1
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refresh() }
    }
    // Some ROMs suppress the volume broadcast. Poll only while the expanded page is visible.
    private val poll = object : Runnable {
        override fun run() { if (active && isAttachedToWindow) { refresh(); main.postDelayed(this, 500) } }
    }
    init {
        contentDescription = "媒体音量"
        isFocusable = true; isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        refresh()
    }
    fun setPageVisible(visible: Boolean) {
        if (active == visible) return
        active = visible
        if (active && isAttachedToWindow) start() else stop()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (active) start() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        slop = ViewConfiguration.get(context).scaledTouchSlop
        invalidate()
    }
    private fun start() {
        if (!registered) runCatching {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION").apply {
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction(AudioManager.ACTION_HEADSET_PLUG)
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
            registered = true
        }.onFailure { ModernXposedRuntime.log("player_volume: broadcast unavailable; using visible-page polling", it) }
        refresh(); main.removeCallbacks(poll); main.postDelayed(poll, 500)
    }
    private fun stop() {
        main.removeCallbacks(poll)
        if (registered) { runCatching { context.unregisterReceiver(receiver) }; registered = false }
        dragging = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }
    private fun refresh() {
        runCatching {
            minimum = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0
            maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            isEnabled = !audio.isVolumeFixed && maximum > minimum
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (level != current) { level = current; invalidate() }
            if (Build.VERSION.SDK_INT >= 30) stateDescription = "${(PlayerVolumeGeometry.fraction(level, minimum, maximum) * 100).toInt()}%"
        }.onFailure { isEnabled = false }
    }
    private fun setLevel(value: Int) {
        if (!isEnabled) return
        val target = value.coerceIn(minimum, maximum)
        if (target != level) runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
            .onFailure { ModernXposedRuntime.log("player_volume: unable to set media volume", it) }
        refresh() // Read back the actual value, including safe-volume/fixed-device limits.
    }
    private fun seek(x: Float) {
        val left = 40 * density
        val right = width - 40 * density
        if (right > left) setLevel(PlayerVolumeGeometry.level((x - left) / (right - left), minimum, maximum))
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val center = height / 2f
        val left = 40 * density
        val right = width - 40 * density
        val radius = (if (dragging) 5f else 3f) * density
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        paint.alpha = if (isEnabled) 52 else 28
        canvas.drawRoundRect(left, center - radius, right, center + radius, radius, radius, paint)
        val filled = left + (right - left) * PlayerVolumeGeometry.fraction(level, minimum, maximum)
        paint.alpha = if (isEnabled) 195 else 75
        if (filled > left) canvas.drawRoundRect(left, center - radius, filled, center + radius, radius, radius, paint)
        fun icon(x: Float, path: Path) {
            val save = canvas.save()
            canvas.translate(x, center - 12 * density); canvas.scale(density, density)
            paint.alpha = 170; paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)
            canvas.restoreToCount(save)
        }
        icon(8 * density, icons.low); icon(width - 32 * density, icons.high)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; dragging = false; return true }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.y - downY) > slop && abs(event.y - downY) > abs(event.x - downX)) return false
                if (!dragging && abs(event.x - downX) > slop) { dragging = true; parent?.requestDisallowInterceptTouchEvent(true) }
                if (dragging) { seek(event.x); invalidate() }
                return true
            }
            MotionEvent.ACTION_UP -> {
                seek(event.x); performClick(); dragging = false
                parent?.requestDisallowInterceptTouchEvent(false); invalidate(); return true
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; parent?.requestDisallowInterceptTouchEvent(false); invalidate(); return true }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isEnabled && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            setLevel(level + if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1); return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
            minimum.toFloat(), maximum.toFloat(), level.coerceIn(minimum, maximum.coerceAtLeast(minimum)).toFloat())
        if (isEnabled) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (isEnabled) when (action) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> {
                if (arguments?.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) != true) return false
                val value = arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)
                if (!value.isFinite()) return false
                setLevel(value.toInt()); return true
            }
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { setLevel(level + 1); return true }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { setLevel(level - 1); return true }
        }
        return super.performAccessibilityAction(action, arguments)
    }
    override fun close() { active = false; stop() }
}
