package dev.amenhancer.module.hook.tabletmedia

import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** Selected media-plugin components, scoped to the host's native 1606 tablet session. */
internal class TabletPlayerComponents(
    private val root: ViewGroup, private val songHost: ViewGroup,
    private val rightHost: ViewGroup, private val songVisible: () -> Boolean,
    private val lyricsExpanded: () -> Boolean, private val lyricsClick: () -> Unit,
    private val horizontalOffset: () -> Float = { 0f },
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
    private val transportStyle = TabletTransportStyle()
    private val outputIcon = IosDeviceOutputDrawable.Factory(TabletMediaAssets, root.resources).create()
    private val output = button().apply {
        setImageDrawable(outputIcon); contentDescription = "音频输出"
        setOnClickListener { PlatformAudioOutputSwitcher.open(context) }
    }
    private val caption = TextView(root.context).apply {
        textSize = 12f; setTextColor(Color.argb(180, 255, 255, 255)); maxLines = 1
        ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }
    private var lyricsSource: View? = null
    private var queueSource: View? = null
    private val lyricsIcon = TabletFooterDrawable(TabletFooterDrawable.Kind.LYRICS)
    private val queueIcon = TabletFooterDrawable(TabletFooterDrawable.Kind.QUEUE)
    private val lyrics = button().apply {
        imageTintList = null; setPadding(0, 0, 0, 0); setImageDrawable(lyricsIcon)
        setOnClickListener { lyricsClick() }
        setOnLongClickListener { lyricsSource?.takeIf { it.isEnabled }?.performLongClick() == true }
    }
    private val queue = button().apply {
        imageTintList = null; setPadding(0, 0, 0, 0); setImageDrawable(queueIcon)
        setOnClickListener { queueSource?.takeIf { it.isEnabled }?.performClick() }
        setOnLongClickListener { queueSource?.takeIf { it.isEnabled }?.performLongClick() == true }
    }
    private val volume = IosVolumeSliderView(root.context)
    private val views get() = listOf(output, caption, lyrics, queue, volume)
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
    private fun refreshOutput() {
        val state = audio.current()
        outputIcon.kind = state.icon; caption.text = state.name.orEmpty()
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

    /** Prepare retained panes before sheet opening or native shared-element capture. */
    fun prepare(start: View = songHost): Boolean {
        var changed = false
        groups(start).forEach {
            nativeActions(it).forEach(::hideSource)
            if (prepareGroup(it)) changed = true
        }
        return changed
    }
    private fun nativeActions(group: ViewGroup) =
        listOf("player_lyrics", "player_queue", "media_route_button").mapNotNull { find(group, it) }
    private fun suppressNativeActions() {
        val retained = groups(songHost).flatMap(::nativeActions)
        hidden.keys.toList().filter { it !in retained }.forEach(::restoreSource)
        retained.forEach(::hideSource)
    }
    private fun percentTarget(group: ViewGroup): View? = (group.parent as? View)?.takeIf { it.id == id("controls") }
    private fun metadata(group: ViewGroup): List<TextView> = (group.parent as? View)?.takeIf { it.id == id("player_container") }
        ?.let { song -> listOf("title", "subtitle").mapNotNull { find(song, it) as? TextView } }.orEmpty()
    private fun prepareGroup(group: ViewGroup): Boolean {
        var changed = percentTarget(group)?.let { rows.percentage(it, .25f) } ?: false
        val times = listOf("current_time_progress", "total_time_progress").mapNotNull { find(group, it) }
        if (rows.prepareTimes(times, dp(4))) changed = true
        times.filterIsInstance<TextView>().forEach { if (rows.text(it, 10f, false)) changed = true }
        (find(group, "audio_badge_text") as? TextView)?.let { if (rows.text(it, 10f, false)) changed = true }
        metadata(group).forEach { if (it.height > 0 && rows.text(it, if (it.id == id("title")) 18f else 16f, true)) changed = true }
        listOf("player_lyrics", "player_queue", "media_route_button").mapNotNull { find(group, it) }
            .forEach { if (rows.footer(it, dp(44), dp(12))) changed = true }
        val progress = listOf("seek_bar_controls", "live_radio_container").mapNotNull { find(group, it) }
            .firstOrNull { it.visibility != View.GONE && it.height > 0 }
        if (group.height > 0 && progress != null) TabletComponentGeometry.rows(group.height, progress.height,
            root.resources.displayMetrics.density)?.let { layout ->
            listOf("play_pause", "previous_rewind", "next_fast_forward").mapNotNull { find(group, it) }.forEach {
                if (rows.transport(it, layout.transportTop, layout.transportHeight, dp(7))) changed = true
            }
        }
        return changed
    }
    private fun hideSource(view: View) {
        val current = hidden[view]
        if (current == null) hidden[view] = Hidden(view.alpha, view.isClickable, view.importantForAccessibility)
        else if (view.alpha != 0f) hidden[view] = current.copy(alpha = view.alpha)
        view.alpha = 0f; view.isClickable = false
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
        val nativeOutput = find(group, "media_route_button")
        val times = listOf("current_time_progress", "total_time_progress").mapNotNull { find(group, it) }
        val footer = listOfNotNull(nativeLyrics, nativeQueue, nativeOutput)
        var changed = rows.prepareTimes(times, dp(4))
        footer.forEach { if (rows.footer(it, dp(44), dp(12))) changed = true }
        if (changed || group.isLayoutRequested) { overlay.visibility = View.INVISIBLE; return }
        val progress = listOf("seek_bar_controls", "live_radio_container").mapNotNull { find(group, it) }
            .firstOrNull { it.isShown && it.height > 0 } ?: return
        val layout = TabletComponentGeometry.rows(group.height, progress.height, root.resources.displayMetrics.density)
            ?: run {
                // Keep the prepared compact rows. Restoring them here would request
                // another layout that prepares them again on every short-window frame.
                overlay.visibility = View.INVISIBLE; placed = false; volume.setPageVisible(false); stopPolling()
                translations.keys.toList().forEach(::restoreTranslation)
                rows.retain(times + footer)
                return
            }
        listOf(play, previous, next).forEach {
            if (rows.transport(it, layout.transportTop, layout.transportHeight, dp(7))) changed = true
        }
        if (changed) { overlay.visibility = View.INVISIBLE; return }
        root.getLocationInWindow(origin)
        val column = location(group); val footerTop = column.second + layout.footerTop
        overlay.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        overlay.layout(0, 0, root.width, root.height)
        overlay.z = maxOf(songHost.z, rightHost.z) + dp(2)
        overlay.alpha = alpha(group)
        val size = dp(44)
        val rightInset = if (Build.VERSION.SDK_INT >= 30)
            root.rootWindowInsets?.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())?.right ?: 0 else 0
        val queueLeft = root.width - rightInset - dp(44) - size
        val lyricsLeft = queueLeft - dp(8) - size
        val pairFits = lyricsLeft >= column.first + group.width + dp(16)
        lyricsSource = nativeLyrics; queueSource = nativeQueue
        // Pane visibility is independent of a song's native lyric availability.
        lyrics.isEnabled = true; queue.isEnabled = nativeQueue.isEnabled; output.isEnabled = true
        lyrics.contentDescription = nativeLyrics.contentDescription; queue.contentDescription = nativeQueue.contentDescription
        lyrics.isSelected = lyricsExpanded(); queue.isSelected = !songVisible()
        lyricsIcon.active = lyrics.isSelected; queueIcon.active = queue.isSelected
        if (pairFits) {
            place(lyrics, lyricsLeft, footerTop, size, size); place(queue, queueLeft, footerTop, size, size)
        } else {
            val a = location(nativeLyrics); val b = location(nativeQueue)
            place(lyrics, a.first, footerTop, size, size); place(queue, b.first, footerTop, size, size)
        }
        val outputLeft = column.first - horizontalOffset().roundToInt() + dp(10)
        val sharePlay = find(group, "shareplay_badge")?.takeIf { it.isShown && alpha(it) > .01f }
        if (sharePlay == null) place(output, outputLeft, footerTop, size, size) else output.visibility = View.INVISIBLE
        val captionHeight = maxOf(dp(18), caption.paint.fontSpacing.roundToInt())
        val captionLeft = outputLeft + dp(42)
        val blockers = listOf(lyrics, queue).filter { it.visibility == View.VISIBLE && it.left >= captionLeft }
            .map { it.left } + listOfNotNull(sharePlay?.let { location(it).first })
        val captionRight = minOf(root.width / 2 - dp(8), outputLeft + group.width - dp(8))
        val captionWidth = TabletComponentGeometry.deviceLabelWidth(captionLeft, captionRight, blockers, dp(8), dp(240))
        if (sharePlay == null && caption.text.isNotBlank() && captionHeight <= size)
            place(caption, captionLeft, footerTop + (size - captionHeight) / 2, captionWidth, captionHeight)
        else caption.visibility = View.INVISIBLE
        val track = find(progress, "progress") ?: progress
        val trackAt = location(track)
        if (layout.volumeTop != null) {
            val left = maxOf(column.first, trackAt.first + track.paddingLeft - dp(20))
            val right = minOf(column.first + group.width, trackAt.first + track.width - track.paddingRight + dp(20))
            place(volume, left, column.second + layout.volumeTop, right - left, layout.volumeHeight)
        } else volume.visibility = View.INVISIBLE
        volume.setPageVisible(visible && expansion >= .999f && volume.visibility == View.VISIBLE)

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
                (root.height - footerTop + dp(8)).coerceAtLeast(0))
        }
        translations.keys.toList().filter { it !== vocal && it !== limits }.forEach(::restoreTranslation)
        // Keep every retained pane prepared, including the collapsed sheet and outgoing queue.
        val retained = groups(songHost).flatMap { retainedGroup ->
            listOf("current_time_progress", "total_time_progress", "audio_badge_text", "player_lyrics", "player_queue",
                "media_route_button", "play_pause", "previous_rewind", "next_fast_forward").mapNotNull { find(retainedGroup, it) } +
                metadata(retainedGroup) + listOfNotNull(percentTarget(retainedGroup))
        }
        rows.retain(retained + listOfNotNull(gradients))
        transportStyle.apply(previous as? ImageView, play as? ImageView, next as? ImageView)
        overlay.visibility = if (visible) View.VISIBLE else View.INVISIBLE; placed = true
        if (visible && !running) { running = true; main.postDelayed(poll, 500) }
        else if (!visible) stopPolling()
    }
    private fun stopPolling() { running = false; main.removeCallbacks(poll) }
    private fun restore() {
        overlay.visibility = View.INVISIBLE; placed = false; volume.setPageVisible(false); stopPolling()
        hidden.keys.toList().forEach(::restoreSource); translations.keys.toList().forEach(::restoreTranslation)
        rows.close()
        transportStyle.restore()
        lyricsSource = null; queueSource = null
    }
    override fun close() {
        if (closed) return
        closed = true; restore(); volume.close(); audio.close(); root.removeView(overlay)
    }
}
