package dev.amenhancer.module.hook.tabletmedia

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import dev.amenhancer.module.hook.ModernXposedRuntime
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** Selected media-plugin components, scoped to the host's native 1606 tablet session. */
internal class TabletPlayerComponents(
    private val controller: Any, private val root: ViewGroup, private val songHost: ViewGroup,
    private val rightHost: ViewGroup, private val songVisible: () -> Boolean,
    private val lyricsExpanded: () -> Boolean, private val lyricsClick: () -> Unit,
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
    private val modes by lazy { TabletPlaybackModes(controller) }
    private val outputIcon = IosDeviceOutputDrawable.Factory(TabletMediaAssets, root.resources).create()
    private val output = button(null).apply {
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
    private var languageSource: View? = null
    private val lyrics = button("ic_nowplaying_lyrics").apply {
        setOnClickListener { lyricsClick() }
        setOnLongClickListener { lyricsSource?.takeIf { it.isEnabled }?.performLongClick() == true }
    }
    private val queue = button("ic_nowplaying_queue").apply {
        setOnClickListener { queueSource?.takeIf { it.isEnabled }?.performClick() }
        setOnLongClickListener { queueSource?.takeIf { it.isEnabled }?.performLongClick() == true }
    }
    private val language = button("ic_nowplaying_translate").apply {
        val drawable = root.resources.getIdentifier("ic_nowplaying_translate", "drawable", root.context.packageName)
        if (drawable != 0) TabletTranslationGlyph.read(root.resources, drawable)?.let(::setImageDrawable)
        setOnClickListener { languageSource?.takeIf { it.isEnabled }?.performClick() }
        setOnLongClickListener { languageSource?.takeIf { it.isEnabled }?.performLongClick() == true }
    }
    private val shuffle = button("ic_nowplaying_shuffle").apply {
        contentDescription = "随机播放"; setOnClickListener { command { modes.toggleShuffle() } }
    }
    private val repeat = button("ic_nowplaying_repeat").apply {
        contentDescription = "循环播放"; setOnClickListener { command { modes.cycleRepeat() } }
    }
    private val volume = IosVolumeSliderView(root.context)
    private val views get() = listOf(output, caption, lyrics, queue, language, shuffle, repeat, volume)
    private val audio = SystemAudioOutput(root.context, root.context.getSystemService(AudioManager::class.java)) {
        root.postInvalidateOnAnimation()
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!running || closed) return
            refreshOutput(); refreshModes(); root.postInvalidateOnAnimation()
            main.postDelayed(this, 500)
        }
    }
    init {
        views.forEach { view -> view.visibility = View.INVISIBLE; overlay.addView(view, FrameLayout.LayoutParams(1, 1)) }
        overlay.visibility = View.INVISIBLE
        root.addView(overlay, ViewGroup.LayoutParams(-1, -1))
        refreshOutput()
    }
    private fun button(icon: String?) = ImageButton(root.context).apply {
        background = null; scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        icon?.let { name -> root.resources.getIdentifier(name, "drawable", root.context.packageName)
            .takeIf { it != 0 }?.let(::setImageResource) }
    }
    private fun command(action: () -> Unit) {
        runCatching(action).onFailure { ModernXposedRuntime.log("Tablet media command failed", it) }
        refreshModes(); root.invalidate()
    }
    private fun refreshModes() {
        val state = runCatching { modes.state() }.getOrElse {
            ModernXposedRuntime.log("Tablet playback modes unavailable", it); TabletPlaybackModes.State()
        }
        shuffle.isEnabled = state.canShuffle; repeat.isEnabled = state.canRepeat
        decorate(shuffle, state.shuffle == true); decorate(repeat, state.repeat != null && state.repeat != 0)
        val icon = if (state.repeat == 1) "ic_nowplaying_repeatone" else "ic_nowplaying_repeat"
        root.resources.getIdentifier(icon, "drawable", root.context.packageName).takeIf { it != 0 }?.let(repeat::setImageResource)
        repeat.contentDescription = if (state.repeat == 1) "单曲循环" else "循环播放"
        shuffle.alpha = if (!state.canShuffle) .3f else if (state.shuffle == true) 1f else .55f
        repeat.alpha = if (!state.canRepeat) .3f else if (state.repeat != 0) 1f else .55f
    }
    private fun decorate(view: ImageButton, selected: Boolean, always: Boolean = false) {
        if (view.isSelected == selected && (view.background != null) == (selected || always)) return
        view.isSelected = selected
        view.background = if (selected || always) GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(Color.argb(30, 255, 255, 255))
        } else null
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
    private fun controls(): ViewGroup? {
        val found = ArrayList<ViewGroup>()
        fun visit(view: View) {
            if (view is ViewGroup) {
                if (view.id == id("player_controls") && view.isShown && view.height > 0 &&
                    find(view, "play_pause")?.isShown == true) found += view
                else for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(songHost)
        return found.maxByOrNull(::alpha)?.takeIf { alpha(it) > .01f }
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
        if (!root.isShown || expansion < .95f) {
            overlay.visibility = View.INVISIBLE; volume.setPageVisible(false); stopPolling()
            if (expansion <= .001f || !root.isShown) restore()
            return
        }
        if (transitioning || expansion < .999f) {
            overlay.alpha = expansion
            overlay.visibility = if (placed) View.VISIBLE else View.INVISIBLE
            listOf(lyrics, queue, language, shuffle, repeat, output).forEach { it.isEnabled = false }
            volume.setPageVisible(false)
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
        footer.forEach { if (rows.footer(it, dp(44), dp(8))) changed = true }
        if (changed || group.isLayoutRequested) { overlay.visibility = View.INVISIBLE; return }
        val progress = listOf("seek_bar_controls", "live_radio_container").mapNotNull { find(group, it) }
            .firstOrNull { it.isShown && it.height > 0 } ?: return
        val layout = TabletComponentGeometry.rows(group.height, progress.height, root.resources.displayMetrics.density)
            ?: run {
                // Keep the prepared compact rows. Restoring them here would request
                // another layout that prepares them again on every short-window frame.
                overlay.visibility = View.INVISIBLE; placed = false; volume.setPageVisible(false); stopPolling()
                hidden.keys.toList().forEach(::restoreSource)
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
        val queueLeft = root.width - rightInset - dp(32) - size
        val lyricsLeft = queueLeft - dp(16) - size
        val pairFits = lyricsLeft >= column.first + group.width + dp(16)
        lyricsSource = nativeLyrics; queueSource = nativeQueue
        // Pane visibility is independent of a song's native lyric availability.
        lyrics.isEnabled = true; queue.isEnabled = nativeQueue.isEnabled; output.isEnabled = true
        lyrics.contentDescription = nativeLyrics.contentDescription; queue.contentDescription = nativeQueue.contentDescription
        decorate(lyrics, lyricsExpanded()); decorate(queue, !songVisible())
        if (pairFits) {
            place(lyrics, lyricsLeft, footerTop, size, size); place(queue, queueLeft, footerTop, size, size)
        } else {
            val a = location(nativeLyrics); val b = location(nativeQueue)
            place(lyrics, a.first, footerTop, size, size); place(queue, b.first, footerTop, size, size)
        }
        val centered = if (!lyricsExpanded() && songVisible())
            TabletComponentGeometry.centeredOffset((songHost.parent as View).width, songHost.left, songHost.width) ?: 0f else 0f
        val outputLeft = column.first - centered.roundToInt() + dp(10)
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
        volume.setPageVisible(volume.visibility == View.VISIBLE)
        val p = location(previous); val n = location(next)
        val slots = TabletComponentGeometry.modes(column.first, group.width, p.first, n.first + next.width, size, dp(4))
        if (slots != null) {
            val top = column.second + layout.transportTop + (layout.transportHeight - size) / 2
            place(shuffle, slots.shuffleLeft, top, size, size); place(repeat, slots.repeatLeft, top, size, size)
        } else { shuffle.visibility = View.INVISIBLE; repeat.visibility = View.INVISIBLE }

        val nativeLanguage = find(rightHost, "translations_button")?.takeIf { it.isShown && it.width > 0 }
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
        languageSource = nativeLanguage
        if (nativeLanguage != null) {
            language.isEnabled = nativeLanguage.isEnabled; language.contentDescription = nativeLanguage.contentDescription
            decorate(language, nativeLanguage.isSelected, always = true)
            place(language, edge - size, top, size, size); top += size + dp(12)
        } else language.visibility = View.INVISIBLE
        val gradients = find(rightHost, "recycler_view_gradients")
        if (gradients != null && rightHost.isShown) {
            val parentTop = location(gradients.parent as View).second
            rows.lyricViewport(gradients, if (vocal != null || nativeLanguage != null) (top - parentTop).coerceAtLeast(0) else 0,
                (root.height - footerTop + dp(8)).coerceAtLeast(0))
        }
        val active = listOfNotNull(nativeLyrics, nativeQueue, nativeOutput, nativeLanguage)
        hidden.keys.toList().filter { it !in active }.forEach(::restoreSource)
        active.forEach(::hideSource)
        translations.keys.toList().filter { it !== vocal && it !== limits }.forEach(::restoreTranslation)
        rows.retain(times + footer + listOf(play, previous, next) + listOfNotNull(gradients))
        overlay.visibility = View.VISIBLE; placed = true
        if (!running) { running = true; refreshModes(); main.postDelayed(poll, 500) }
    }
    private fun stopPolling() { running = false; main.removeCallbacks(poll) }
    private fun restore() {
        overlay.visibility = View.INVISIBLE; placed = false; volume.setPageVisible(false); stopPolling()
        hidden.keys.toList().forEach(::restoreSource); translations.keys.toList().forEach(::restoreTranslation)
        rows.close()
        lyricsSource = null; queueSource = null; languageSource = null
    }
    override fun close() {
        if (closed) return
        closed = true; restore(); volume.close(); audio.close(); root.removeView(overlay)
    }
}
