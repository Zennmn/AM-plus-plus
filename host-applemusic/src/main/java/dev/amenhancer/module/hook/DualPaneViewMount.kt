package dev.amenhancer.module.hook

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.host.applemusic.R
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

internal object DualPaneShell {
    /** Invokes the profile-resolved Activity root method without naming its obfuscation. */
    fun activityRoot(activity: Activity, rootMethod: Method): View? = runCatching {
        rootMethod.apply { isAccessible = true }.invoke(activity) as? View
    }.onFailure {
        dualPaneDebug("PlayerActivity root lookup failed: $it")
    }.getOrNull()

    /**
     * Mirrors a compiled layout-land resource: mutate immediately after
     * fragment_player_main inflation, even if this pre-created view has not
     * attached to the window yet.
     */
    fun installImmediately(root: ViewGroup): DualPaneState? {
        if (!TabletModeQualifier.isEligible(root.context)) {
            dualPaneDebug("layout skipped: tablet-landscape predicate is false")
            return null
        }
        (root.getTag(R.id.am_enhancer_dual_pane_state) as? DualPaneState)?.let { return it }
        val state = runCatching { ConstraintLayoutPane.install(root) }
            .onFailure { dualPaneDebug("layout install failed: $it") }
            .getOrNull()
            ?: return null
        root.setTag(R.id.am_enhancer_dual_pane_state, state)
        dualPaneDebug(
            "layout installed synchronously root=" + System.identityHashCode(root) +
                " playerHost=" + state.playerHost.id +
                " lyricsHost=" + state.lyricsHost.id,
        )
        return state
    }
}

/**
 * Uses reflection deliberately: ConstraintLayout belongs to Apple Music's
 * class loader, and the module must not package or shadow its own copy.
 */
internal object ConstraintLayoutPane {
    private const val BOTTOM_NAVIGATION_ROOT_STACKED = "bottom_navigation_root_stacked"
    private const val BOTTOM_NAVIGATION_ROOT_FLAT = "bottom_navigation_root_flat"
    private const val BOTTOM_NAVIGATION_TABS = "bottom_navigation_tabs_frame"
    private const val BOTTOM_NAVIGATION = "bottom_navigation"
    private const val NAVIGATION_TABS_DIVIDER = "navigation_tabs_divider"
    private const val NAV_TABS_TOP_SHADOW = "nav_tabs_top_shadow"
    private const val NAVIGATION_TABS_HEIGHT = "navigation_tabs_height"
    private const val STACKED_TABS_VERTICAL_INSET_DP = 8
    private const val PLAYER_CONTAINER = "player_container"
    private const val PLAYER_SHEET_CONTAINER = "player_sheet_container"
    private const val PLAYER_CONTAINER_ELEVATION = "player_container_elevation"
    private const val ARTWORK_CONTAINER = "artwork_container"
    private const val METADATA_BARRIER_TOP = "metadata_barrier_top"
    private const val ARTWORK_LAYOUT_REAPPLY_MAX_PRE_DRAWS = 8
    private const val PLAYER_ROOT = "player_root"
    private const val PLAYER_FRAGMENTS_HOST = "player_fragments_host"
    private const val PARENT_ID = 0

    /** Reference 1606 phone bottom frame: copy native params and preserve the host class loader. */
    fun newBottomNavigationFrameParams(template: ViewGroup.LayoutParams, height: Int): ViewGroup.MarginLayoutParams =
        newLayoutParams(template, ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
            setMargins(0, 0, 0, 0)
            marginStart = 0; marginEnd = 0
            constrainFullWidth(this)
            setInt("topToTop", -1)
            setInt("topToBottom", -1)
            setInt("bottomToTop", -1)
            setInt("bottomToBottom", PARENT_ID)
        }
    private object BottomNavigationLandscapeInstalled
    /**
     * Apple Music 6.5.0 repackages ConstraintLayout. Its LayoutParams keeps
     * the public class name but R8 renames its instance fields. The mapping
     * below is recovered from ConstraintLayout$b.resolveLayoutDirection() and
     * the modified APK's layout-land XML, rather than guessed from field order.
     */
    private const val TARGET_650_LAYOUT_PARAMS = "androidx.constraintlayout.widget.ConstraintLayout\$b"
    private val TARGET_650_FIELD_NAMES = mapOf(
        "guideBegin" to "a",
        "guidePercent" to "c",
        "leftToLeft" to "h",
        "leftToRight" to "g",
        "rightToLeft" to "f",
        "rightToRight" to "e",
        "topToTop" to "i",
        "topToBottom" to "j",
        "bottomToTop" to "k",
        "bottomToBottom" to "l",
        "orientation" to "V",
        "endToStart" to "u",
        "endToEnd" to "v",
        "startToEnd" to "s",
        "startToStart" to "t",
        "dimensionRatio" to "G",
        "matchConstraintDefaultWidth" to "L",
        "matchConstraintMinWidth" to "N",
        "matchConstraintMaxWidth" to "P",
        "matchConstraintPercentHeight" to "S",
        "constrainedWidth" to "W",
    )

    fun resolveBottomNavigationRoot(view: View): ViewGroup? {
        val resources = view.resources
        val candidateIds = listOf(
            BOTTOM_NAVIGATION_ROOT_STACKED,
            BOTTOM_NAVIGATION_ROOT_FLAT,
        ).mapNotNull { name ->
            resources.getIdentifier(name, "id", ModuleConstants.TARGET_PACKAGE)
                .takeIf { it != 0 }
        }
        val suppliedRoot = view as? ViewGroup
        if (suppliedRoot != null && suppliedRoot.id in candidateIds) return suppliedRoot
        return candidateIds.asSequence()
            .mapNotNull { candidateId -> view.findViewById<ViewGroup>(candidateId) }
            .firstOrNull()
    }

    /** Reference1606: fix the native cover's constraint size before aligning its sheet frame. */
    fun configureArtworkContainer(artwork: View, sizePx: Int): Boolean {
        val params = constraintMarginParams(artwork, ARTWORK_CONTAINER)
        if (params.width == sizePx && params.height == sizePx && params.topMargin == 0 && params.bottomMargin == 0 &&
            constraintField(params.javaClass, "topToTop")?.getInt(params) == PARENT_ID &&
            constraintField(params.javaClass, "topToBottom")?.getInt(params) == -1 &&
            constraintField(params.javaClass, "dimensionRatio")?.get(params) == null) return false
        params.width = sizePx; params.height = sizePx; params.topMargin = 0; params.bottomMargin = 0
        params.setObject("dimensionRatio", null)
        params.setInt("topToTop", PARENT_ID); params.setInt("topToBottom", -1)
        artwork.layoutParams = params
        return true
    }

    /** 1606 native animation reads layout coordinates; centering must be an actual top margin. */
    fun configureNativeArtworkContainer(artwork: View, sizePx: Int, topMarginPx: Int): Boolean {
        val params = constraintMarginParams(artwork, ARTWORK_CONTAINER)
        if (params.width == sizePx && params.height == sizePx && params.topMargin == topMarginPx && params.bottomMargin == 0 &&
            constraintField(params.javaClass, "topToTop")?.getInt(params) == PARENT_ID &&
            constraintField(params.javaClass, "topToBottom")?.getInt(params) == -1 &&
            constraintField(params.javaClass, "bottomToTop")?.getInt(params) == -1 &&
            constraintField(params.javaClass, "bottomToBottom")?.getInt(params) == -1 &&
            constraintField(params.javaClass, "dimensionRatio")?.get(params) == null) return false
        params.width = sizePx; params.height = sizePx; params.topMargin = topMarginPx; params.bottomMargin = 0
        params.setObject("dimensionRatio", null)
        params.setInt("topToTop", PARENT_ID); params.setInt("topToBottom", -1)
        // A remaining bottom anchor would apply vertical bias on top of the explicit margin.
        params.setInt("bottomToTop", -1); params.setInt("bottomToBottom", -1)
        artwork.layoutParams = params
        return true
    }

    /** 1606 tablet song pane: a shorter control section with native metadata insets. */
    fun configureTabletSongLayout(host: View): (() -> Unit)? {
        val controls = host.findViewById<View>(targetId(host.resources, "player_controls")) ?: return null
        val params = constraintMarginParams(controls, "player_controls")
        val heightPercent = checkNotNull(constraintField(params.javaClass, "matchConstraintPercentHeight"))
        val nativeHeightPercent = heightPercent.getFloat(params)
        // Keep the original params: the host's generic constructor drops native constraints.
        heightPercent.setFloat(params, dev.amenhancer.module.hook.tabletmedia.TabletComponentGeometry.CONTROLS_HEIGHT_PERCENT)
        controls.layoutParams = params
        return {
            heightPercent.setFloat(params, nativeHeightPercent)
            controls.layoutParams = params
        }
    }

    /**
     * Mirrors the modified layout-land/bottom_navigation.xml by converting the
     * stock flat resource tree into full-width tablet chrome. Apple Music's
     * native bottom-navigation holder owns its peek height and transitions.
     */
    fun installLandscapeBottomNavigation(
        root: ViewGroup,
        targetBuild: TargetBuild = TargetBuild.UNKNOWN,
    ) {
        if (!TabletModeQualifier.isEligible(root.context)) {
            dualPaneDebug("bottom navigation skipped: tablet-landscape predicate is false")
            return
        }
        if (root.getTag(R.id.am_enhancer_dual_pane_state) === BottomNavigationLandscapeInstalled) return

        runCatching {
            val resources = root.resources
            val rootId = targetId(resources, BOTTOM_NAVIGATION_ROOT_FLAT)
            if (root.id != rootId) {
                dualPaneDebug("bottom navigation skipped: unexpected root id=" + root.id)
                return
            }
            val menuHeight = resources.getDimensionPixelSize(targetId(resources, NAVIGATION_TABS_HEIGHT, "dimen"))
            val tabsHeight = stackedTabsContainerHeight(root.context, menuHeight)
            val tabsFrame = root.findViewById<FrameLayout>(targetId(resources, BOTTOM_NAVIGATION_TABS))
                ?: error("bottom_navigation_tabs_frame was absent from inflated layout")
            val playerContainer = root.findViewById<View>(targetId(resources, PLAYER_CONTAINER))
                ?: error("player_container was absent from inflated layout")
            val topShadow = root.findViewById<View>(targetId(resources, NAV_TABS_TOP_SHADOW))
                ?: error("nav_tabs_top_shadow was absent from inflated layout")
            val bottomNavigation = tabsFrame.findViewById<View>(targetId(resources, BOTTOM_NAVIGATION))
                ?: error("bottom_navigation was absent from tabs frame")

            configureTabsFrame(tabsFrame, tabsHeight, resources)
            configureTabsContent(bottomNavigation)
            // XC_LayoutInflated runs before the target finishes initializing its
            // material navigation child. Re-apply the modified XML's child
            // params on the next UI queue turn, after that initialization.
            bottomNavigation.post {
                configureTabsContent(bottomNavigation)
            }
            configureTabsTopShadow(topShadow)
            configurePlayerContainer(playerContainer, root.context)
            // Keep the accepted Phase-109 navigation inset on every target
            // build. The full tabs frame is owned by the native holder; using
            // it as a container translation pulls the mini-player behind the
            // navigation bar on 6.5.2.
            val navigationInset = (tabsHeight - menuHeight).coerceAtLeast(0)
            installFlatPlayerBoundarySync(
                root,
                playerContainer,
                tabsFrame,
                tabsHeight = tabsHeight,
                navigationInset = navigationInset,
            )
            installTabsDivider(tabsFrame, resources)

            root.setTag(R.id.am_enhancer_dual_pane_state, BottomNavigationLandscapeInstalled)
            root.requestLayout()
            dualPaneDebug(
                "bottom navigation landscape installed root=" + System.identityHashCode(root) +
                    " player=" + playerContainer.id +
                    " tabsHeight=" + tabsHeight,
            )
        }.onFailure {
            dualPaneDebug("bottom navigation landscape install failed: $it")
        }
    }

    /** Mirrors the modified lyrics-sheet top constraint without changing its parent tree. */
    fun anchorTopToParent(view: View, resourceName: String) {
        val params = constraintMarginParams(view, resourceName)
        params.setInt("topToTop", PARENT_ID)
        params.setInt("topToBottom", -1)
        view.layoutParams = params
        view.requestLayout()
    }

    fun install(root: ViewGroup): DualPaneState {
        val resources = root.resources
        val playerRootId = resources.getIdentifier(PLAYER_ROOT, "id", ModuleConstants.TARGET_PACKAGE)
        val playerHostId = resources.getIdentifier(PLAYER_FRAGMENTS_HOST, "id", ModuleConstants.TARGET_PACKAGE)
        require(playerRootId != 0 && playerHostId != 0) { "player root/host resource IDs were unavailable" }

        val playerRoot = root.findViewById<View>(playerRootId) as? ViewGroup
            ?: error("player_root was absent from inflated layout")
        val playerHost = playerRoot.findViewById<View>(playerHostId)
            ?: error("player_fragments_host was absent from player_root")
        val layoutParamsClass = playerHost.layoutParams.javaClass
        check(constraintField(layoutParamsClass, "startToStart") != null) {
            "player_fragments_host does not use ConstraintLayout params: " + layoutParamsClass.name
        }

        val classLoader = playerRoot.javaClass.classLoader ?: root.javaClass.classLoader
            ?: error("player_root class loader was unavailable")
        val guidelineClass = Class.forName("androidx.constraintlayout.widget.Guideline", false, classLoader)
        val guideline = guidelineClass.getConstructor(Context::class.java).newInstance(root.context) as? View
            ?: error("ConstraintLayout Guideline was not a View")
        val splitId = View.generateViewId()
        guideline.id = splitId
        playerRoot.addView(
            guideline,
            newLayoutParams(
                playerHost.layoutParams,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setInt("orientation", 1)
                setFloat("guidePercent", 0.5f)
            },
        )

        configureExistingPlayerHost(playerHost, splitId, layoutParamsClass)
        val lyricsHost = FrameLayout(root.context).apply {
            id = View.generateViewId()
            contentDescription = "AM++ lyrics pane"
        }
        playerRoot.addView(
            lyricsHost,
            newLayoutParams(playerHost.layoutParams, 0, 0).apply {
                setInt("leftToRight", splitId)
                setInt("rightToRight", PARENT_ID)
                setInt("startToEnd", splitId)
                setInt("endToEnd", PARENT_ID)
                setInt("topToTop", PARENT_ID)
                setInt("bottomToBottom", PARENT_ID)
                setHorizontalMargins(this, dp(root.context, 16))
            },
        )

        val divider = View(root.context).apply {
            id = View.generateViewId()
            visibility = View.INVISIBLE
        }
        playerRoot.addView(
            divider,
            newLayoutParams(playerHost.layoutParams, dp(root.context, 1), 0).apply {
                setInt("leftToLeft", splitId)
                setInt("rightToRight", splitId)
                setInt("startToStart", splitId)
                setInt("endToEnd", splitId)
                setInt("topToTop", PARENT_ID)
                setInt("bottomToBottom", PARENT_ID)
            },
        )

        val artworkLayoutReapply = installTabletArtworkLayout(playerRoot, playerHost)
        return DualPaneState(root, playerHost, lyricsHost, artworkLayoutReapply)
    }

    /**
     * Keep Apple's native cover size, but center it in the vertical interval
     * from the left pane top to Apple's metadata barrier (the title row).
     */
    private fun installTabletArtworkLayout(
        playerRoot: ViewGroup,
        playerHost: View,
    ): (() -> Unit)? {
        val artworkId = playerRoot.resources.getIdentifier(
            ARTWORK_CONTAINER,
            "id",
            ModuleConstants.TARGET_PACKAGE,
        )
        if (artworkId == 0) return null
        val barrierId = playerRoot.resources.getIdentifier(
            METADATA_BARRIER_TOP,
            "id",
            ModuleConstants.TARGET_PACKAGE,
        )
        if (barrierId == 0) return null
        val nativeSizeByArtwork = WeakHashMap<View, Int>()
        fun apply(): Boolean {
            if (!TabletModeQualifier.isEligible(playerRoot.context)) return true
            val artwork = playerHost.findViewById<View>(artworkId) ?: return false
            val barrier = playerHost.findViewById<View>(barrierId) ?: return false
            val parent = artwork.parent as? ViewGroup ?: return false
            if (parent.width <= 0 || parent.height <= 0 || artwork.width <= 0) return false
            val nativeSizePx = nativeSizeByArtwork[artwork]
                ?: artwork.width.takeIf { it > 0 }?.also { nativeSizeByArtwork[artwork] = it }
                ?: return false
            val hostLocation = IntArray(2)
            val windowRootLocation = IntArray(2)
            val artworkLocation = IntArray(2)
            val barrierLocation = IntArray(2)
            playerHost.getLocationInWindow(hostLocation)
            playerRoot.rootView.getLocationInWindow(windowRootLocation)
            artwork.getLocationInWindow(artworkLocation)
            barrier.getLocationInWindow(barrierLocation)
            val statusBarInsetTopPx = playerRoot.rootWindowInsets?.systemWindowInsetTop
                ?: playerRoot.resources.getIdentifier("status_bar_height", "dimen", "android")
                    .takeIf { it != 0 }
                    ?.let(playerRoot.resources::getDimensionPixelSize)
                ?: 0
            val intervalTopPx = maxOf(
                hostLocation[1],
                windowRootLocation[1] + statusBarInsetTopPx,
            )
            val availableHeightPx = (barrierLocation[1] - intervalTopPx).toFloat()
            val layout = TabletArtworkLayoutPolicy.resolve(
                availableHeightPx = availableHeightPx,
                nativeSizePx = nativeSizePx.toFloat(),
            ) ?: return false
            val params = artwork.layoutParams as? ViewGroup.MarginLayoutParams ?: return true
            if (constraintField(params.javaClass, "startToStart") == null) return true
            val sizePx = (layout.sizePx + 0.5f).toInt().coerceAtLeast(1)
            val desiredArtworkTopPx = intervalTopPx + layout.edgeGapPx
            val artworkDeltaPx = desiredArtworkTopPx - artworkLocation[1]
            if (kotlin.math.abs(artworkDeltaPx) > 0.5f) {
                artwork.translationY += artworkDeltaPx
            }
            val alreadyApplied =
                params.width == sizePx &&
                    params.height == sizePx &&
                    params.topMargin == 0 &&
                    params.bottomMargin == 0 &&
                    constraintField(params.javaClass, "topToTop")?.getInt(params) == PARENT_ID &&
                    constraintField(params.javaClass, "topToBottom")?.getInt(params) == -1
            if (alreadyApplied) return true
            params.width = sizePx
            params.height = sizePx
            params.topMargin = 0
            params.bottomMargin = 0
            params.setObject("dimensionRatio", null)
            params.setInt("topToTop", PARENT_ID)
            params.setInt("topToBottom", -1)
            artwork.layoutParams = params
            artwork.requestLayout()
            return true
        }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply() }
        playerRoot.addOnLayoutChangeListener(listener)
        (playerHost.parent as? View)
            ?.takeUnless { it === playerRoot }
            ?.addOnLayoutChangeListener(listener)
        playerHost.addOnLayoutChangeListener(listener)
        playerRoot.post { apply() }
        playerHost.post { apply() }
        fun scheduleArtworkLayoutReapplyAfterMeasure() {
            var attemptsRemaining = ARTWORK_LAYOUT_REAPPLY_MAX_PRE_DRAWS
            var observedTree: ViewTreeObserver? = null
            var attachListener: View.OnAttachStateChangeListener? = null
            lateinit var preDrawListener: ViewTreeObserver.OnPreDrawListener

            fun removePreDrawListener() {
                observedTree?.takeIf(ViewTreeObserver::isAlive)
                    ?.removeOnPreDrawListener(preDrawListener)
                observedTree = null
            }

            fun finish() {
                removePreDrawListener()
                attachListener?.let { listener ->
                    playerHost.removeOnAttachStateChangeListener(listener)
                }
                attachListener = null
            }

            fun registerPreDraw() {
                if (!playerHost.isAttachedToWindow) return
                val tree = playerHost.viewTreeObserver
                if (!tree.isAlive) {
                    playerHost.post { registerPreDraw() }
                    return
                }
                removePreDrawListener()
                observedTree = tree
                playerHost.viewTreeObserver.addOnPreDrawListener(preDrawListener)
            }

            preDrawListener = ViewTreeObserver.OnPreDrawListener {
                val applied = apply()
                attemptsRemaining -= 1
                if (applied || attemptsRemaining <= 0 || !playerHost.isAttachedToWindow) {
                    finish()
                } else {
                    // A failed pre-draw may not itself cause another traversal
                    // (for example while FragmentManager is still adding the
                    // child). Keep the bounded retry observable to ViewRoot.
                    playerHost.requestLayout()
                }
                true
            }

            val newAttachListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    registerPreDraw()
                }

                override fun onViewDetachedFromWindow(view: View) {
                    removePreDrawListener()
                }
            }
            attachListener = newAttachListener
            playerHost.addOnAttachStateChangeListener(newAttachListener)
            if (playerHost.isAttachedToWindow) {
                registerPreDraw()
            }
        }
        return ::scheduleArtworkLayoutReapplyAfterMeasure
    }

    private fun configureTabsFrame(
        tabsFrame: FrameLayout,
        tabsHeight: Int,
        resources: android.content.res.Resources,
    ) {
        val params = constraintMarginParams(tabsFrame, BOTTOM_NAVIGATION_TABS)
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = tabsHeight
        clearLegacyWidthContract(params)
        constrainFullWidth(params)
        params.setInt("bottomToBottom", PARENT_ID)
        params.setInt("bottomToTop", -1)
        tabsFrame.layoutParams = params
        val elevationId = resources.getIdentifier(
            PLAYER_CONTAINER_ELEVATION,
            "dimen",
            ModuleConstants.TARGET_PACKAGE,
        )
        if (elevationId != 0) tabsFrame.elevation = resources.getDimension(elevationId)
        tabsFrame.requestLayout()
    }

    private fun configureTabsContent(bottomNavigation: View) {
        val params = bottomNavigation.layoutParams as? FrameLayout.LayoutParams
            ?: error("bottom_navigation does not use FrameLayout.LayoutParams")
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT
        params.gravity = Gravity.CENTER
        bottomNavigation.layoutParams = params
        bottomNavigation.requestLayout()
    }

    private fun configureTabsTopShadow(topShadow: View) {
        val params = constraintMarginParams(topShadow, NAV_TABS_TOP_SHADOW)
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        constrainFullWidth(params)
        topShadow.layoutParams = params
        topShadow.requestLayout()
    }

    private fun configurePlayerContainer(
        playerContainer: View,
        context: Context,
    ) {
        val params = constraintMarginParams(playerContainer, PLAYER_CONTAINER)
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        // Phase 109: compensation is visual only. Keeping margin 0 preserves
        // the full-screen sheet geometry, so the native holder's slide
        // animation targets never change and no black strip appears under
        // the player.
        params.bottomMargin = 0
        clearLegacyWidthContract(params)
        constrainFullWidth(params)
        params.setInt("topToTop", PARENT_ID)
        params.setInt("topToBottom", -1)
        params.setInt("bottomToBottom", PARENT_ID)
        params.setInt("bottomToTop", -1)
        playerContainer.layoutParams = params
        playerContainer.requestLayout()
    }

    private fun installFlatPlayerBoundarySync(
        root: ViewGroup,
        playerContainer: View,
        tabsFrame: View,
        tabsHeight: Int,
        navigationInset: Int,
    ) {
        if (!FlatLandscapeWindowPolicy.shouldInstallBoundarySync(root.context)) return
        val sheetId = targetId(root.resources, PLAYER_SHEET_CONTAINER)
        val sheet = sheetId.takeIf { it != 0 }?.let { root.findViewById<View>(it) }
        if (sheet == null) {
            dualPaneDebug(
                "flat boundary sync skipped: no player_sheet_container (id=" + sheetId +
                    ") under root=" + root.javaClass.name,
            )
            return
        }
        var reserveNavigationSpace =
            FlatLandscapeWindowPolicy.shouldReserveNavigationSpace(root.context)
        val rootLocation = IntArray(2)
        val sheetLocation = IntArray(2)
        val tabsLocation = IntArray(2)
        fun sync() {
            // Geometry arbitration: while the liquid-glass session is active
            // its rewritten peek height is the single source of collapsed
            // geometry, so this controller must stay silent — writing
            // translationY/tabs visibility on top of the glass peek would
            // double-lift the mini player out of the capsule position and
            // expose a black strip. The listener and the reserveNavigationSpace
            // latch semantics are preserved; once glass clears (switch off,
            // predicate false, fail-closed recovery) the compare-then-write
            // mechanism below re-asserts the settled values byte-identically.
            if (TabletGlassChrome.isGlassActive(root)) {
                // Hand-over release: settle the compensation's own writes once
                // so glass starts from clean geometry. A stuck lift would keep
                // the mini capsule too high; a stuck INVISIBLE tabs frame would
                // hide the glass surfaces entirely.
                if (playerContainer.translationY != 0f) playerContainer.translationY = 0f
                if (tabsFrame.visibility != View.VISIBLE) tabsFrame.visibility = View.VISIBLE
                return
            }
            val rootHeight = root.height
            if (rootHeight <= 0) return
            root.getLocationInWindow(rootLocation)
            sheet.getLocationInWindow(sheetLocation)
            tabsFrame.getLocationInWindow(tabsLocation)
            val rootTop = rootLocation[1]
            // Measure layout geometry, not translated geometry: the window
            // measurement includes this module's own translationY on the
            // outer player container, so exclude it to keep the decision
            // independent of the compensation it produces.
            val sheetTop = FlatPlayerBoundaryPolicy.sheetTopRelativeToRoot(
                sheetWindowTop = sheetLocation[1],
                rootWindowTop = rootTop,
                containerTranslationY = playerContainer.translationY,
            )
            val decision = FlatPlayerBoundaryPolicy.decide(
                rootHeight = rootHeight,
                sheetTop = sheetTop,
                sheetBottom = sheetTop + sheet.height,
                tabsTop = tabsLocation[1] - rootTop,
                tabsHeight = tabsHeight,
                navigationInset = navigationInset,
                wasNavigationSpaceReserved = reserveNavigationSpace,
            )
            reserveNavigationSpace = decision.reserveNavigationSpace
            val desiredTranslation = decision.translationY.toFloat()
            val translationChanged = playerContainer.translationY != desiredTranslation
            val desiredTabsVisibility = if (decision.tabsVisible) View.VISIBLE else View.INVISIBLE
            val tabsVisibilityChanged = tabsFrame.visibility != desiredTabsVisibility
            if (!translationChanged && !tabsVisibilityChanged) return
            // Phase 109 review: always compare against the live view property.
            // If another animation or the holder rewrote the translation,
            // re-assert the settled expectation instead of trusting a cached
            // local value. Visual only: translationY never relayouts the tree
            // and the sheet view itself is never written.
            if (translationChanged) {
                playerContainer.translationY = desiredTranslation
            }
            if (tabsVisibilityChanged) tabsFrame.visibility = desiredTabsVisibility
        }
        var observedTree: ViewTreeObserver? = null
        val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            sync()
            true
        }
        fun removePreDrawListener() {
            observedTree?.takeIf(ViewTreeObserver::isAlive)
                ?.removeOnPreDrawListener(preDrawListener)
            observedTree = null
        }
        fun addPreDrawListener() {
            val tree = sheet.viewTreeObserver
            if (!tree.isAlive || tree === observedTree) return
            removePreDrawListener()
            sheet.viewTreeObserver.addOnPreDrawListener(preDrawListener)
            observedTree = tree
        }
        sheet.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                addPreDrawListener()
            }

            override fun onViewDetachedFromWindow(view: View) {
                removePreDrawListener()
            }
        })
        if (sheet.isAttachedToWindow) addPreDrawListener()
        root.post {
            if (sheet.isAttachedToWindow) addPreDrawListener()
            sync()
        }
    }

    private fun installTabsDivider(tabsFrame: FrameLayout, resources: android.content.res.Resources) {
        val dividerId = targetId(resources, NAVIGATION_TABS_DIVIDER)
        if (tabsFrame.findViewById<View>(dividerId) != null) return
        val separatorId = resources.getIdentifier("separator_color", "color", ModuleConstants.TARGET_PACKAGE)
        val divider = View(tabsFrame.context).apply {
            id = dividerId
            if (separatorId != 0) setBackgroundColor(resources.getColor(separatorId, tabsFrame.context.theme))
        }
        tabsFrame.addView(
            divider,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(tabsFrame.context, 1),
                Gravity.TOP,
            ),
        )
    }

    private fun constrainFullWidth(params: ViewGroup.MarginLayoutParams) {
        params.setInt("leftToLeft", PARENT_ID)
        params.setInt("leftToRight", -1)
        params.setInt("rightToLeft", -1)
        params.setInt("rightToRight", PARENT_ID)
        params.setInt("startToStart", PARENT_ID)
        params.setInt("startToEnd", -1)
        params.setInt("endToStart", -1)
        params.setInt("endToEnd", PARENT_ID)
    }

    /**
     * The official tablet layout uses match-constraint width plus a ratio,
     * min-width and constrained-width flag to form the narrow right rail.
     * The modified landscape XML omits all of them, so reset the same fields
     * before changing the width to MATCH_PARENT.
     */
    private fun clearLegacyWidthContract(params: ViewGroup.MarginLayoutParams) {
        params.setObject("dimensionRatio", null)
        params.setInt("matchConstraintDefaultWidth", 0)
        params.setInt("matchConstraintMinWidth", 0)
        params.setInt("matchConstraintMaxWidth", 0)
        params.setBoolean("constrainedWidth", false)
    }

    private fun constraintMarginParams(view: View, resourceName: String): ViewGroup.MarginLayoutParams {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams
            ?: error("$resourceName does not use MarginLayoutParams")
        check(constraintField(params.javaClass, "startToStart") != null) {
            "$resourceName does not use ConstraintLayout params: " + params.javaClass.name
        }
        return params
    }

    private fun targetId(
        resources: android.content.res.Resources,
        name: String,
        type: String = "id",
    ): Int = resources.getIdentifier(name, type, ModuleConstants.TARGET_PACKAGE)
        .takeIf { it != 0 }
        ?: error("$type/$name resource ID was unavailable")

    private fun configureExistingPlayerHost(
        playerHost: View,
        splitId: Int,
        layoutParamsClass: Class<*>,
    ) {
        val params = playerHost.layoutParams as? ViewGroup.MarginLayoutParams
            ?: error("player_fragments_host does not use MarginLayoutParams")
        check(layoutParamsClass.isInstance(params))
        params.width = 0
        params.height = 0
        params.setInt("leftToLeft", PARENT_ID)
        params.setInt("rightToLeft", splitId)
        params.setInt("startToStart", PARENT_ID)
        params.setInt("endToStart", splitId)
        params.setInt("leftToRight", -1)
        params.setInt("rightToRight", -1)
        params.setInt("startToEnd", -1)
        params.setInt("endToEnd", -1)
        params.setInt("topToTop", PARENT_ID)
        params.setInt("bottomToBottom", PARENT_ID)
        setHorizontalMargins(params, dp(playerHost.context, 48))
        playerHost.layoutParams = params
    }

    /**
     * The target's repackaged LayoutParams exposes only the
     * LayoutParams-copy constructor. Copy the real host's params just like
     * LayoutInflater does, then overwrite the dimensions and constraints.
     */
    private fun newLayoutParams(template: ViewGroup.LayoutParams, width: Int, height: Int): ViewGroup.MarginLayoutParams {
        val layoutParamsClass = template.javaClass
        val constructor = layoutParamsClass.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterTypes.singleOrNull()?.isAssignableFrom(template.javaClass) == true
        } ?: error("ConstraintLayout.LayoutParams copy constructor was not found: " + layoutParamsClass.name)
        constructor.isAccessible = true
        return (constructor.newInstance(template) as? ViewGroup.MarginLayoutParams
            ?: error("ConstraintLayout.LayoutParams copy constructor returned an incompatible type")).apply {
            this.width = width
            this.height = height
        }
    }

    private fun setHorizontalMargins(params: ViewGroup.MarginLayoutParams, margin: Int) {
        params.leftMargin = margin
        params.rightMargin = margin
        params.marginStart = margin
        params.marginEnd = margin
    }

    private fun ViewGroup.LayoutParams.setInt(name: String, value: Int) {
        constraintField(javaClass, name)?.setInt(this, value)
            ?: error("ConstraintLayout.LayoutParams.$name was not found on " + javaClass.name)
    }

    private fun ViewGroup.LayoutParams.setFloat(name: String, value: Float) {
        constraintField(javaClass, name)?.setFloat(this, value)
            ?: error("ConstraintLayout.LayoutParams.$name was not found on " + javaClass.name)
    }

    private fun ViewGroup.LayoutParams.setBoolean(name: String, value: Boolean) {
        constraintField(javaClass, name)?.setBoolean(this, value)
            ?: error("ConstraintLayout.LayoutParams.$name was not found on " + javaClass.name)
    }

    private fun ViewGroup.LayoutParams.setObject(name: String, value: Any?) {
        constraintField(javaClass, name)?.set(this, value)
            ?: error("ConstraintLayout.LayoutParams.$name was not found on " + javaClass.name)
    }

    private fun constraintField(type: Class<*>, semanticName: String): Field? {
        dualPaneField(type, semanticName)?.let { return it }
        val obfuscatedName = TARGET_650_FIELD_NAMES[semanticName]
            ?.takeIf { type.name == TARGET_650_LAYOUT_PARAMS }
            ?: return null
        return dualPaneField(type, obfuscatedName)
    }

    /**
     * Keep the modified APK's 56dp Material menu unchanged, but give it a
     * centered 8dp breathing space above and below on this tablet layout.
     */
    fun stackedTabsContainerHeight(context: Context, menuHeight: Int): Int =
        menuHeight + dp(context, STACKED_TABS_VERTICAL_INSET_DP * 2)


    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}

