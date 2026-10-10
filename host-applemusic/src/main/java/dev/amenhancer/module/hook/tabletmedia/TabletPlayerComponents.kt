package dev.amenhancer.module.hook.tabletmedia

import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** Selected media-plugin components, scoped to the host's native 1606 tablet session. */
internal class TabletPlayerComponents(
    private val root: ViewGroup, private val songHost: ViewGroup,
    private val rightHost: ViewGroup, private val songVisible: () -> Boolean,
    private val lyricsExpanded: () -> Boolean, private val lyricsClick: () -> Unit,
    private val horizontalOffset: () -> Float = { 0f },
    private val currentLyricsAvailability: (() -> Boolean?)? = null,
) : AutoCloseable {
    private val overlay = FrameLayout(root.context).apply { clipChildren = false; clipToPadding = false }
    private val rows = TabletNativeControlRows()
    private val point = IntArray(2)
    private val origin = IntArray(2)
    private val ids = HashMap<String, Int>()
    private fun id(name: String) = ids.getOrPut(name) { root.resources.getIdentifier(name, "id", root.context.packageName) }
    private fun find(parent: View, name: String): View? = id(name).takeIf { it != 0 }?.let { parent.findViewById(it) }
    private fun dp(value: Int) = (value * root.resources.displayMetrics.density).roundToInt()
    private data class Hidden(val alpha: Float, val clickable: Boolean, val accessibility: Int)
    private val hidden = IdentityHashMap<View, Hidden>()
    private val translations = IdentityHashMap<View, Pair<Float, Float>>()
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var closed = false
    private var placed = false
    private val actionStyle = TabletNativeActionStyle()
    private val transportRipple = TabletTransportRipple()
    private val outputIcon = IosDeviceOutputDrawable.Factory(TabletMediaAssets, root.resources).create()
    private val output = button().apply {
        setImageDrawable(outputIcon); contentDescription = "音频输出"
        setOnClickListener { PlatformAudioOutputSwitcher.open(context) }
    }
    private var lyricsSource: View? = null
    private var queueSource: View? = null
    private val lyrics = nativeButton("selector_nowplaying_lyrics").apply {
        setOnClickListener { if (lyricsAvailable() == true) lyricsClick() }
        setOnLongClickListener { lyricsAvailable() == true && lyricsSource?.performLongClick() == true }
    }
    private val queue = nativeButton("selector_nowplaying_queue").apply {
        setOnClickListener { queueSource?.takeIf { it.isEnabled }?.performClick() }
        setOnLongClickListener { queueSource?.takeIf { it.isEnabled }?.performLongClick() == true }
    }
    private val volume = IosVolumeSliderView(root.context)
    private val views get() = listOf(output, lyrics, queue, volume)
    private val audio = SystemAudioOutput(root.context, root.context.getSystemService(AudioManager::class.java)) {
        root.postInvalidateOnAnimation()
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!running || closed) return
            refreshOutput(); root.postInvalidateOnAnimation()
            main.postDelayed(this, 500)
        }
    }
    init {
        views.forEach { view -> view.visibility = View.INVISIBLE; overlay.addView(view, FrameLayout.LayoutParams(1, 1)) }
        overlay.visibility = View.INVISIBLE
        root.addView(overlay, ViewGroup.LayoutParams(-1, -1))
        refreshOutput()
    }
    private fun button() = ImageButton(root.context).apply {
        background = null; scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        imageTintList = ColorStateList.valueOf(Color.WHITE)
    }
    private fun nativeButton(drawable: String) = button().apply {
        // Keep Apple's intrinsic symbol size inside the existing 44dp touch target.
        scaleType = ImageView.ScaleType.CENTER; setPadding(0, 0, 0, 0)
        root.resources.getIdentifier(drawable, "drawable", root.context.packageName)
            .takeIf { it != 0 }?.let(::setImageResource)
    }
    private fun refreshOutput() {
        val state = audio.current()
        outputIcon.kind = state.icon
        output.contentDescription = state.name?.let { "音频输出 · $it" } ?: "音频输出"
    }
    private fun location(view: View): Pair<Int, Int> {
        view.getLocationInWindow(point)
        return point[0] - origin[0] to point[1] - origin[1]
    }
    private fun alpha(view: View): Float {
        var current: View? = view; var value = 1f
        while (current != null && current !== root) {
            val child = current
            value *= hidden[child]?.let { if (child.alpha == 0f) it.alpha else child.alpha } ?: child.alpha
            current = child.parent as? View
        }
        return value
    }
    private fun groups(start: View): List<ViewGroup> {
        val found = ArrayList<ViewGroup>()
        fun visit(view: View) {
            if (view is ViewGroup) {
                if (view.id == id("player_controls") && find(view, "play_pause") != null) found += view
                else for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(start)
        return found
    }
    private fun controls(): ViewGroup? = groups(songHost).filter { it.isShown && it.height > 0 }
        .maxByOrNull(::alpha)
    /** Current-item state overrides retained controls, which can still describe the previous song. */
    fun lyricsAvailable(): Boolean? = currentLyricsAvailability?.let { it() == true }
        ?: controls()?.let { find(it, "player_lyrics")?.isEnabled }

    /** Prepare retained panes before sheet opening or native shared-element capture. */
    fun prepare(start: View = songHost): Boolean {
        var changed = false
        val nativeGroups = groups(start)
        nativeGroups.maxByOrNull(::alpha)?.let {
            actionStyle.bind(lyrics, find(it, "player_lyrics") as? ImageView)
            actionStyle.bind(queue, find(it, "player_queue") as? ImageView)
        }
        nativeGroups.forEach {
            nativeActions(it).forEach(::hideSource)
            transportButtons(it).forEach(transportRipple::apply)
            if (prepareGroup(it)) changed = true
        }
        return changed
    }
    private fun nativeActions(group: ViewGroup) =
        listOf("player_lyrics", "player_queue", "media_route_button", "badge_platter", "shuffle_repeat_badge",
            "shareplay_badge", "router_name_textview").mapNotNull { find(group, it) }
    private fun transportButtons(group: ViewGroup) =
        listOf("play_pause", "previous_rewind", "next_fast_forward").mapNotNull { find(group, it) as? ImageView }
    private fun suppressNativeActions() {
        val retained = groups(songHost).flatMap(::nativeActions)
        hidden.keys.toList().filter { it !in retained }.forEach(::restoreSource)
        retained.forEach(::hideSource)
    }
    private fun percentTarget(group: ViewGroup): View? = (group.parent as? View)?.takeIf { it.id == id("controls") }
    private fun prepareGroup(group: ViewGroup): Boolean =
        percentTarget(group)?.let { rows.percentage(it, .25f) } ?: false
    private fun hideSource(view: View) {
        val current = hidden[view]
        if (current == null) hidden[view] = Hidden(view.alpha, view.isClickable, view.importantForAccessibility)
        else if (view.alpha != 0f) hidden[view] = current.copy(alpha = view.alpha)
        view.alpha = 0f; view.isClickable = false
        view.importantForAccessibility = if (view is ViewGroup) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun restoreSource(view: View) {
        val saved = hidden.remove(view) ?: return
        if (view.alpha == 0f) view.alpha = saved.alpha
        view.isClickable = saved.clickable; view.importantForAccessibility = saved.accessibility
    }
    private fun place(view: View, left: Int, top: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || left < 0 || top < 0 || left + width > root.width || top + height > root.height) {
            view.visibility = View.INVISIBLE; return
        }
        val p = view.layoutParams as FrameLayout.LayoutParams
        if (p.width != width || p.height != height || p.leftMargin != left || p.topMargin != top) {
            p.width = width; p.height = height; p.leftMargin = left; p.topMargin = top; view.layoutParams = p
        }
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(left, top, left + width, top + height); view.visibility = View.VISIBLE
    }
    private fun move(view: View, left: Int, top: Int) {
        val saved = translations.getOrPut(view) { view.translationX to view.translationY }
        val at = location(view)
        val nativeLeft = at.first - (view.translationX - saved.first).roundToInt()
        val nativeTop = at.second - (view.translationY - saved.second).roundToInt()
        view.translationX = saved.first + left - nativeLeft
        view.translationY = saved.second + top - nativeTop
    }
    private fun restoreTranslation(view: View) {
        translations.remove(view)?.let { (x, y) -> view.translationX = x; view.translationY = y }
    }

    fun update(expansion: Float, transitioning: Boolean) {
        if (closed) return
        // Both retained fragments can draw during a queue crossfade. Hide their
        // native actions before the transition fast path or any relayout return.
        suppressNativeActions()
        if (!transitioning) prepare()
        val visible = root.isShown && expansion > .001f
        if (transitioning && placed) {
            overlay.alpha = expansion
            overlay.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            listOf(lyrics, queue, output).forEach { it.isEnabled = false }
            volume.setPageVisible(false)
            if (!visible) stopPolling()
            return
        }
        val group = controls() ?: run { overlay.visibility = View.INVISIBLE; return }
        val play = find(group, "play_pause") ?: return
        val previous = find(group, "previous_rewind") ?: return
        val next = find(group, "next_fast_forward") ?: return
        val nativeLyrics = find(group, "player_lyrics") ?: return
        val nativeQueue = find(group, "player_queue") ?: return
        if (group.isLayoutRequested) { overlay.visibility = View.INVISIBLE; return }
        val progress = listOf("seek_bar_controls", "live_radio_container").mapNotNull { find(group, it) }
            .firstOrNull { it.isShown && it.height > 0 } ?: return
        root.getLocationInWindow(origin)
        val column = location(group)
        // Keep the right actions at their current 44dp / 12dp-from-bottom positions.
        val footerTop = column.second + group.height - dp(12) - dp(44)
        overlay.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        overlay.layout(0, 0, root.width, root.height)
        overlay.z = maxOf(songHost.z, rightHost.z) + dp(2)
        overlay.alpha = alpha(group)
        val size = dp(44)
        val systemInsets = if (Build.VERSION.SDK_INT >= 30)
            root.rootWindowInsets?.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()) else null
        val rightInset = systemInsets?.right ?: 0
        val queueLeft = root.width - rightInset - dp(44) - size
        val lyricsLeft = queueLeft - dp(8) - size
        val pairFits = lyricsLeft >= column.first + group.width + dp(16)
        lyricsSource = nativeLyrics; queueSource = nativeQueue
        lyrics.isEnabled = lyricsAvailable() == true; queue.isEnabled = nativeQueue.isEnabled; output.isEnabled = true
        lyrics.contentDescription = nativeLyrics.contentDescription; queue.contentDescription = nativeQueue.contentDescription
        actionStyle.bind(lyrics, nativeLyrics as? ImageView)
        actionStyle.bind(queue, nativeQueue as? ImageView)
        lyrics.isSelected = lyricsExpanded(); queue.isSelected = !songVisible()
        if (pairFits) {
            place(lyrics, lyricsLeft, footerTop, size, size); place(queue, queueLeft, footerTop, size, size)
        } else {
            val a = location(nativeLyrics); val b = location(nativeQueue)
            place(lyrics, a.first, footerTop, size, size); place(queue, b.first, footerTop, size, size)
        }
        val nativeProgress = find(progress, "progress")?.takeIf { it.isShown && it.width > 0 } ?: progress
        val progressAt = location(nativeProgress)
        val progressLeft = progressAt.first + nativeProgress.paddingLeft
        val progressRight = progressAt.first + nativeProgress.width - nativeProgress.paddingRight
        // The invisible native actions retain their dimensions and constraints:
        // Align output to the existing raised row, then lift volume another 2dp.
        val nativeFooterAt = location(nativeLyrics)
        val center = nativeFooterAt.second + nativeLyrics.height / 2
        val outputCenter = center - dp(4)
        val volumeCenter = outputCenter - dp(2)
        val contentBottom = (listOf(play, previous, next, progress)).maxOf { location(it).second + it.height }
        // Volume owns the replaced footer. Fit it first, keeping the visible glyph sizes.
        val slot = if (nativeLyrics.height > 0) TabletComponentGeometry.bottomVolume(progressLeft,
            progressRight, volumeCenter, contentBottom, minOf(root.height, column.second + group.height),
            root.resources.displayMetrics.density) else null
        if (slot != null) place(volume, slot.left, slot.top, slot.width, slot.height)
        else volume.visibility = View.INVISIBLE
        volume.setPageVisible(visible && expansion >= .999f && volume.visibility == View.VISIBLE)
        // Follow any necessary volume displacement, retaining the requested 2dp center separation.
        val outputTop = slot?.let { it.top + it.height / 2 + dp(2) - size / 2 }
            ?: if (nativeLyrics.height > 0) outputCenter - size / 2 else footerTop - dp(4)
        // Keep the corner action outside the progress edge, even when the song column centers.
        val corner = TabletComponentGeometry.cornerOutput(systemInsets?.left ?: 0,
            progressLeft - horizontalOffset().roundToInt(), outputTop,
            root.resources.displayMetrics.density)
        if (corner != null) place(output, corner.left, corner.top, corner.width, corner.height)
        else output.visibility = View.INVISIBLE

        val vocal = find(rightHost, "vocal_ctrl")?.takeIf { it.isShown && it.width > 0 }
        val limits = vocal?.let { find(rightHost, "vocal_ctrl_drag_limits") }
        val topInset = if (Build.VERSION.SDK_INT >= 30)
            root.rootWindowInsets?.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars())?.top ?: 0 else 0
        var top = (topInset - origin[1]).coerceAtLeast(0) + dp(12)
        val edge = root.width - rightInset - dp(24)
        vocal?.let {
            move(it, edge - it.width, top)
            limits?.let { limit ->
                val delta = translations.getValue(it)
                val saved = translations.getOrPut(limit) { limit.translationX to limit.translationY }
                limit.translationX = saved.first + it.translationX - delta.first
                limit.translationY = saved.second + it.translationY - delta.second
            }
            top += it.height + dp(4)
        }
        val gradients = find(rightHost, "recycler_view_gradients")
        if (gradients != null && rightHost.isShown) {
            val parentTop = location(gradients.parent as View).second
            rows.lyricViewport(gradients, if (vocal != null) (top + dp(8) - parentTop).coerceAtLeast(0) else 0,
                0)
        }
        translations.keys.toList().filter { it !== vocal && it !== limits }.forEach(::restoreTranslation)
        // Keep every retained pane prepared, including the collapsed sheet and outgoing queue.
        val retained = groups(songHost).mapNotNull(::percentTarget)
        rows.retain(retained + listOfNotNull(gradients))
        transportRipple.retain(groups(songHost).flatMap(::transportButtons))
        overlay.visibility = if (visible) View.VISIBLE else View.INVISIBLE; placed = true
        if (visible && !running) { running = true; main.postDelayed(poll, 500) }
        else if (!visible) stopPolling()
    }
    private fun stopPolling() { running = false; main.removeCallbacks(poll) }
    private fun restore() {
        overlay.visibility = View.INVISIBLE; placed = false; volume.setPageVisible(false); stopPolling()
        hidden.keys.toList().forEach(::restoreSource); translations.keys.toList().forEach(::restoreTranslation)
        rows.close()
        transportRipple.close()
        actionStyle.clear()
        lyricsSource = null; queueSource = null
    }
    override fun close() {
        if (closed) return
        closed = true; restore(); volume.close(); audio.close(); root.removeView(overlay)
    }
}
