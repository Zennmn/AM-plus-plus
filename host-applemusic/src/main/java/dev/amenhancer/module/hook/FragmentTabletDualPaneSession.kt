package dev.amenhancer.module.hook

import android.annotation.SuppressLint
import dev.amenhancer.module.hook.tabletmedia.TabletPlayerComponents
import dev.amenhancer.module.hook.tabletmedia.TabletLyricsPaneMotion
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import java.util.IdentityHashMap

/** Native fragments and media callbacks remain native; only their containers change. */
internal class FragmentTabletDualPaneSession(val controller: Any, val root: ViewGroup) : ViewTreeObserver.OnPreDrawListener {
    private val ids = HashMap<String, Int>()
    val player = find(root, "player_root") as ViewGroup
    private val songHost = find(player, "player_fragments_host") as ViewGroup
    private val originalParams = songHost.layoutParams
    private var originalIndex = player.indexOfChild(songHost)
    private val wrapper = object : FrameLayout(root.context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Restored lyric rows can bind during the first layout. Set their real
            // viewport before child measurement, not in the later onSizeChanged.
            layoutPanes(View.MeasureSpec.getSize(widthMeasureSpec))
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }.apply { clipChildren = false; clipToPadding = false }
    // Outside resource-ID space, stable across process recreation for native fragment saved state.
    @SuppressLint("ResourceType") // Stable runtime ID, deliberately outside the resource-ID range.
    private val right = FrameLayout(root.context).apply {
        id = 0x00a71606; clipChildren = false; clipToPadding = false; alpha = 0f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val loader = controller.javaClass.classLoader!!
    private val nativeLyricsAvailability = FragmentTabletLyricsAvailability.native(controller, root.context)
    private val stateClass = loader.loadClass("com.apple.android.music.player.fragment.PlayerMainFragment\$l")
    private val states = checkNotNull(stateClass.enumConstants).associateBy { (it as Enum<*>).name }
    private val fragmentAccessor = stateClass.getDeclaredMethod("a")
    private val tagAccessor = stateClass.getDeclaredMethod("e")
    private val manager = ModernXposedRuntime.callMethod(controller, "getChildFragmentManager")!!
    private val paneField = controller.javaClass.getDeclaredField("a").apply { isAccessible = true }
    private val switchingField = controller.javaClass.getDeclaredField("j").apply { isAccessible = true }
    private val transitionField = controller.javaClass.getDeclaredField("k").apply { isAccessible = true }
    private var pending = false
    private var closing = false
    private var destroyed = false
    private var contentAttached = false
    private var slide = 0f
    private var observer: ViewTreeObserver? = null
    private val hidden = IdentityHashMap<View, Int>()
    private val margins = IdentityHashMap<View, Int>()
    private var cover: View? = null
    private val nativeCallback = checkNotNull(controller.javaClass.getDeclaredField("c0").apply { isAccessible = true }.get(controller))
    private val nativeCoverGetter = resolveFragmentNativeCoverGetter(controller.javaClass)
    private val behaviorField = controller.javaClass.getDeclaredField("c").apply { isAccessible = true }
    private val behaviorState = behaviorField.type.getDeclaredField("p0").apply { isAccessible = true }
    private val expandedSheetTop = resolveFragmentExpandedSheetTop(behaviorField.type)
    private var artworkContainer: View? = null
    private var metadataBarrier: View? = null
    private var artworkParams: ViewGroup.LayoutParams? = null
    private var songLayoutRestore: (() -> Unit)? = null
    private var components: TabletPlayerComponents? = null
    private var componentsFailed = false
    private var lyricsExpanded = true
    private val paneMotion = TabletLyricsPaneMotion(wrapper, songHost, right) { root.postInvalidateOnAnimation() }
    private var nativeArtworkSize = 0
    private var artworkDirty = true
    private val artworkLayoutBounds = Rect()
    private val coordinatorLocation = IntArray(2)
    private val rootLocation = IntArray(2)
    private var rightRoot: View? = null
    private var chrome: List<View> = emptyList()
    private val artworkListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        artworkDirty = true
    }
    var installed = false
        private set

    private fun saved() = ModernXposedRuntime.callMethod(manager, "Q") == true
    private fun fragment(name: String): Any? = ModernXposedRuntime.callMethod(manager, "E", tag(name))
    private fun tag(name: String) = tagAccessor.invoke(states.getValue(name)) as String
    private fun transaction(): Any = loader.loadClass("androidx.fragment.app.a")
        .getDeclaredConstructor(loader.loadClass("androidx.fragment.app.E")).newInstance(manager)

    fun prepare() {
        if (destroyed || installed) return
        originalIndex = player.indexOfChild(songHost)
        check(originalIndex >= 0)
        player.removeView(songHost)
        player.addView(wrapper, originalIndex, originalParams)
        wrapper.addView(songHost, FrameLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT))
        wrapper.addView(right, FrameLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT))
        installed = true
        slide = currentSlide()
        layoutPanes(player.width)
    }

    fun install() {
        if (destroyed || contentAttached || pending || closing || saved()) return
        prepare()
        contentAttached = true
        observer = root.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        songHost.addOnLayoutChangeListener(artworkListener)
        prepareComponents(songHost)
        forceSong()
        val tx = transaction()
        val currentSong = fragment("SONG")
        ModernXposedRuntime.callMethod(tx, "e", songHost.id, currentSong ?: fragmentAccessor.invoke(states.getValue("SONG")), tag("SONG"))
        currentSong?.let { ModernXposedRuntime.callMethod(tx, "p", it) }
        // Initial native lyrics/queue may already occupy the left host if enabled after startup.
        // Discard the former right-hand queue on saved-state restoration from v4-v8.
        ModernXposedRuntime.callMethod(manager, "E", tag("LYRICS") + FragmentDualPanePolicy.RIGHT_TAG_SUFFIX)?.let {
            ModernXposedRuntime.callMethod(tx, "m", it)
        }
        val restoredLyrics = fragment("LYRICS")?.takeIf { ModernXposedRuntime.callMethod(it, "getId") == right.id }
        fragment("QUEUE")?.let { ModernXposedRuntime.callMethod(tx, "m", it) }
        if (restoredLyrics == null) {
            fragment("LYRICS")?.let { ModernXposedRuntime.callMethod(tx, "m", it) }
            val lyrics = fragmentAccessor.invoke(states.getValue("LYRICS"))!!
            ModernXposedRuntime.callMethod(tx, "e", right.id, lyrics, tag("LYRICS"))
        }
        commit(tx) { ModernXposedRuntime.callMethod(controller, "r1") }
        root.invalidate()
        ModernXposedRuntime.log("7.0 tablet dual pane: native song/lyrics hosts attached")
    }

    private fun forceSong() {
        val song = states.getValue("SONG")
        paneField.set(controller, song)
        val live = checkNotNull(controller.javaClass.getDeclaredField("Z").apply { isAccessible = true }.get(controller))
        ModernXposedRuntime.callMethod(live, "setValue", song)
        ModernXposedRuntime.callMethod(controller, "p1", false)
    }

    private fun currentSlide(): Float {
        FragmentTabletDualPaneCoordinator.progress(controller)?.let { return it }
        val behavior = behaviorField.get(controller) ?: return 0f
        return if (behaviorState.getInt(behavior) == 3) 1f else 0f
    }

    private fun layoutPanes(width: Int) {
        if (!installed || width <= 0) return
        val dualPane = FragmentTabletDualPaneCoordinator.eligible(root.context)
        val half = if (dualPane) width / 2 else width
        val leftGap = (48 * root.resources.displayMetrics.density).toInt()
        val rightGap = (16 * root.resources.displayMetrics.density).toInt()
        setPaneLayout(songHost, (half - 2 * leftGap).coerceAtLeast(1), leftGap, -1)
        // Android restores the saved right Fragment before the queued removal commits.
        // Keep a real viewport during that hand-off instead of collapsing it to 1px.
        setPaneLayout(right, FragmentDualPanePolicy.rightPaneWidth(width, dualPane, rightGap), rightGap, Gravity.RIGHT)
        artworkDirty = true
    }

    private fun setPaneLayout(view: View, width: Int, gap: Int, gravity: Int) {
        val current = view.layoutParams as? FrameLayout.LayoutParams
        if (current?.width == width && current.height == ViewGroup.LayoutParams.MATCH_PARENT &&
            current.leftMargin == gap && current.rightMargin == gap && current.gravity == gravity) return
        view.layoutParams = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, gravity).apply {
            leftMargin = gap; rightMargin = gap
        }
    }

    private fun commit(tx: Any, action: () -> Unit) {
        pending = true
        try {
            ModernXposedRuntime.callMethod(tx, "f", Runnable {
                pending = false
                if (!destroyed) runCatching(action).onFailure { FragmentTabletDualPaneCoordinator.fail(controller, it) }
                if (!destroyed && !FragmentTabletDualPaneCoordinator.eligible(root.context)) root.post { restore() }
            })
            ModernXposedRuntime.callMethod(tx, "h", false)
        } catch (error: Throwable) { pending = false; closing = false; throw error }
    }

    override fun onPreDraw(): Boolean {
        if (destroyed || !installed || closing) return true
        try {
            if (!FragmentTabletDualPaneCoordinator.eligible(root.context)) {
                root.post { restore() }
                return true
            }
            // A completed rebound can omit the last slide(1); trust the native settled state.
            if (behaviorField.get(controller)?.let { behaviorState.getInt(it) == 3 } == true) slide = 1f
            // Native lyrics chrome can be re-shown by asynchronous media updates.
            val child = if (right.childCount > 0) right.getChildAt(0) else null
            if (rightRoot !== child) {
                rightRoot = child
                chrome = listOf("current_player_item", "controls", "controls_tap_target").mapNotNull { find(right, it) }
            }
            for (view in chrome) view.let {
                hidden.putIfAbsent(it, it.visibility)
                if (it.visibility != View.GONE) it.visibility = View.GONE
            }
            if (right.childCount > 0) right.getChildAt(0).let { child ->
                (child.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                    margins.putIfAbsent(child, params.topMargin)
                    if (params.topMargin != 0) { params.topMargin = 0; child.layoutParams = params }
                }
            }
            applyTransition()
            wrapper.z = songHost.z
            if (artworkDirty && (slide <= .001f || slide >= .999f)) styleArtwork()
            applyPanePresentation()
            if (!componentsFailed) runCatching {
                ensureComponents()
                components?.update(slide, switchingField.getBoolean(controller) || transitionField.getBoolean(controller))
            }.onFailure { error ->
                runCatching { components?.close() }; components = null; componentsFailed = true
                lyricsExpanded = true; applyPanePresentation()
                ModernXposedRuntime.log("Tablet media components failed; retaining native dual pane", error)
            }
        } catch (error: Throwable) { FragmentTabletDualPaneCoordinator.fail(controller, error) }
        return true
    }

    fun onSlide(progress: Float) {
        if (!installed || closing || !progress.isFinite()) return
        slide = progress.coerceIn(0f, 1f)
        applyTransition()
        applyPanePresentation()
    }

    /** Same current SONG/QUEUE cover that the native animator selects; measurement only. */
    fun nativeCoverReady(): Boolean {
        val artwork = nativeCoverGetter.invoke(null, controller) as? View ?: return false
        return artwork.isAttachedToWindow && artwork.width > 0 && artwork.height > 0
    }

    private fun trackArtworkLayout(): View? {
        val artwork = nativeCoverGetter.invoke(null, controller) as? View ?: return null
        if (cover !== artwork) {
            if (artworkContainer !== artwork.parent) {
                songLayoutRestore?.invoke(); songLayoutRestore = null
                artworkContainer?.removeOnLayoutChangeListener(artworkListener)
                (artworkContainer?.parent as? View)?.removeOnLayoutChangeListener(artworkListener)
                metadataBarrier?.removeOnLayoutChangeListener(artworkListener)
                artworkContainer = null; metadataBarrier = null; artworkParams = null; nativeArtworkSize = 0
            }
            cover = artwork
            artworkDirty = true
        }
        return artwork
    }

    private fun applyTransition() {
        right.alpha = FragmentPlayerSurfaceMotion.tabletFrame(slide).expansion
        right.importantForAccessibility = if (slide >= .6f) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun toggleLyrics() {
        if (components?.lyricsAvailable() == false) return
        if ((paneField.get(controller) as? Enum<*>)?.name == "SONG") lyricsExpanded = !lyricsExpanded
        else {
            lyricsExpanded = true
            ModernXposedRuntime.callMethod(controller, "s1", states.getValue("SONG"), null)
        }
        applyPanePresentation(animate = true)
        root.invalidate()
    }

    private fun applyPanePresentation(animate: Boolean = false) {
        val song = (paneField.get(controller) as? Enum<*>)?.name == "SONG"
        val safe = song && slide >= .95f &&
            !switchingField.getBoolean(controller) && !transitionField.getBoolean(controller)
        paneMotion.apply(!lyricsExpanded, safe, FragmentPlayerSurfaceMotion.tabletFrame(slide).expansion, animate,
            available = components?.lyricsAvailable() != false)
    }

    private fun ensureComponents() {
        if (components == null) components = TabletPlayerComponents(root, songHost, right,
            { (paneField.get(controller) as? Enum<*>)?.name == "SONG" },
            { lyricsExpanded && components?.lyricsAvailable() != false }, ::toggleLyrics,
            { paneMotion.horizontalOffset }, { nativeLyricsAvailability.available() })
    }

    fun prepareComponents(pane: View) {
        if (!installed || componentsFailed) return
        runCatching { ensureComponents(); components?.prepare(pane) }.onFailure {
            runCatching { components?.close() }; components = null; componentsFailed = true
            ModernXposedRuntime.log("Tablet component preparation failed", it)
        }
    }

    fun prepareNativeTransition() { paneMotion.close() }

    private fun styleArtwork() {
        if (!canTransformSong()) return
        trackArtworkLayout() ?: return
        val artwork = artworkContainer ?: find(songHost, "artwork_container")?.also { view ->
            artworkContainer = view
            artworkParams = view.layoutParams.javaClass.getConstructor(ViewGroup.LayoutParams::class.java).newInstance(view.layoutParams) as ViewGroup.LayoutParams
            view.addOnLayoutChangeListener(artworkListener)
            (view.parent as? View)?.addOnLayoutChangeListener(artworkListener)
        } ?: return
        val barrier = metadataBarrier ?: find(songHost, "metadata_barrier_top")?.also {
            metadataBarrier = it; it.addOnLayoutChangeListener(artworkListener)
        } ?: return
        if (artwork.width <= 0 || songHost.height <= 0) return
        if (nativeArtworkSize == 0) nativeArtworkSize = artwork.width
        if (songLayoutRestore == null) {
            songLayoutRestore = ConstraintLayoutPane.configureTabletSongLayout(songHost)
            // Let the metadata barrier settle before calculating the cover's new center.
            return
        }
        val artworkParent = artwork.parent as? View ?: return
        val sheet = root.parent as? ViewGroup ?: return
        val coordinator = sheet.parent as? View ?: return
        val behavior = behaviorField.get(controller) ?: return
        // Match native i.d's player-root layout space, independent of sheet position.
        val hostTop = layoutTop(player, songHost)
        val parentTop = layoutTop(player, artworkParent)
        val metadataTop = layoutTop(player, barrier)
        coordinator.getLocationInWindow(coordinatorLocation)
        root.rootView.getLocationInWindow(rootLocation)
        @Suppress("DEPRECATION")
        val topInset = if (android.os.Build.VERSION.SDK_INT >= 30)
            root.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.statusBars())?.top ?: 0
        else root.rootWindowInsets?.systemWindowInsetTop ?: 0
        // Insets belong to the expanded viewport even while the hidden player is below
        // the screen. Reading its live window Y here drops the inset while collapsed.
        val expandedPlayerTop = coordinatorLocation[1] + (expandedSheetTop.invoke(behavior) as Int) + layoutTop(sheet, player)
        val localInset = (rootLocation[1] + topInset - expandedPlayerTop).coerceAtLeast(0)
        val topMargin = TabletArtworkLayoutPolicy.nativeTopMarginInPlayer(
            hostTop.toFloat(), metadataTop.toFloat(), parentTop.toFloat(), nativeArtworkSize.toFloat(), localInset.toFloat()) ?: return
        // Native i.d uses layout rectangles, which exclude ancestor translations. Put the
        // resting cover position in its constraints so the native mini endpoint includes it.
        val changed = ConstraintLayoutPane.configureNativeArtworkContainer(artwork, nativeArtworkSize, topMargin)
        artworkDirty = changed
        if (changed) root.postInvalidateOnAnimation()
    }

    private fun layoutTop(ancestor: ViewGroup, view: View): Int {
        view.getDrawingRect(artworkLayoutBounds)
        ancestor.offsetDescendantRectToMyCoords(view, artworkLayoutBounds)
        return artworkLayoutBounds.top
    }

    private fun resetArtwork() {
        ModernXposedRuntime.callMethod(nativeCallback, "e")
        ModernXposedRuntime.callMethod(nativeCallback, "d", slide)
    }

    private fun canTransformSong(): Boolean = FragmentTabletArtworkPolicy.canTransformSong(
        (paneField.get(controller) as? Enum<*>)?.name,
        switchingField.getBoolean(controller), transitionField.getBoolean(controller))

    fun restore() {
        if (!installed || closing || destroyed || pending || saved()) return
        closing = true
        val tx = transaction()
        for (name in listOf("LYRICS", "QUEUE")) fragment(name)?.takeIf {
            ModernXposedRuntime.callMethod(it, "getId") == right.id
        }?.let { ModernXposedRuntime.callMethod(tx, "m", it) }
        commit(tx) {
            stopObserver()
            restoreDecorations()
            val index = player.indexOfChild(wrapper).coerceAtLeast(0)
            wrapper.removeView(songHost)
            wrapper.removeView(right)
            player.removeView(wrapper)
            player.addView(songHost, index.coerceAtMost(player.childCount), originalParams)
            installed = false; closing = false
            // Keep the native left state (including QUEUE) consistent with its attached fragment.
            contentAttached = false
            ModernXposedRuntime.callMethod(controller, "r1")
            root.invalidate()
            root.post {
                if (!destroyed) {
                    resetArtwork()
                    FragmentTabletDualPaneCoordinator.onRestored(controller)
                }
            }
        }
    }

    private fun restoreDecorations() {
        components?.close(); components = null
        componentsFailed = false
        paneMotion.close()
        right.visibility = View.VISIBLE
        songLayoutRestore?.invoke(); songLayoutRestore = null
        hidden.forEach { (view, visibility) -> view.visibility = visibility }; hidden.clear()
        margins.forEach { (view, top) -> (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.topMargin = top; view.layoutParams = it } }; margins.clear()
        artworkContainer?.let { view -> artworkParams?.let { view.layoutParams = it } }
        cover = null; artworkContainer = null; metadataBarrier = null
        artworkParams = null; nativeArtworkSize = 0; artworkDirty = true; rightRoot = null; chrome = emptyList()
    }

    private fun stopObserver() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
        songHost.removeOnLayoutChangeListener(artworkListener)
        artworkContainer?.removeOnLayoutChangeListener(artworkListener)
        (artworkContainer?.parent as? View)?.removeOnLayoutChangeListener(artworkListener)
        metadataBarrier?.removeOnLayoutChangeListener(artworkListener)
    }
    fun destroy() {
        destroyed = true; stopObserver()
        components?.close(); components = null
        paneMotion.close()
        songLayoutRestore?.invoke(); songLayoutRestore = null
        hidden.clear(); margins.clear(); cover = null
    }
    private fun find(parent: View, name: String): View? = ids.getOrPut(name) { parent.resources.getIdentifier(name, "id", dev.amenhancer.module.ModuleConstants.TARGET_PACKAGE) }
        .takeIf { it != 0 }?.let { parent.findViewById(it) }
}
