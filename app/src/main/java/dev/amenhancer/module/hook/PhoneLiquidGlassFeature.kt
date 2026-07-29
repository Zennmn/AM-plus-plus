package dev.amenhancer.module.hook

import android.app.Activity
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.os.Build
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.TargetConfigClient
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import java.util.Collections
import java.util.WeakHashMap

/** WIP health adapter for the resource-time phone liquid-glass hooks. */
internal class PhoneLiquidGlassFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_PHONE_LIQUID_GLASS

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().phoneLiquidGlassEnabled) return FeatureInstallResult.disabled()
        return FeatureInstallResult.degraded(
            "WIP: resource hooks registered; a single-frame bottom-chrome flicker remains possible during cold start or player collapse",
        )
    }
}

internal object PhoneLiquidGlassResourceHook {
    fun install(config: TargetConfigClient) {
        LayoutInflationRegistry.register("bottom_navigation") { view ->
            val root = view as? ViewGroup ?: return@register
            if (!PhoneLiquidGlassQualifier.isEligible(root.context, config)) return@register
            PhoneLiquidGlassStyler.installBottomNavigation(root)
            installMiniPlayerWhenAvailable(root, config, attempt = 0)
        }
        LayoutInflationRegistry.register("mini_player") { view ->
            val root = view as? FrameLayout ?: return@register
            if (!PhoneLiquidGlassQualifier.isEligible(root.context, config)) return@register
            PhoneLiquidGlassStyler.installMiniPlayer(root)
        }
    }

    private fun installMiniPlayerWhenAvailable(
        navigationRoot: ViewGroup,
        config: TargetConfigClient,
        attempt: Int,
    ) {
        navigationRoot.post {
            if (!PhoneLiquidGlassQualifier.isEligible(navigationRoot.context, config)) return@post
            val miniPlayerId = navigationRoot.resources.getIdentifier(
                "mini_player",
                "id",
                ModuleConstants.TARGET_PACKAGE,
            )
            val miniPlayer = miniPlayerId.takeIf { it != 0 }
                ?.let { navigationRoot.findViewById<FrameLayout>(it) }
            if (miniPlayer != null) {
                PhoneLiquidGlassStyler.installMiniPlayer(miniPlayer)
                ModernXposedRuntime.log("phone liquid-glass mini player installed from navigation root")
            } else if (attempt < MINI_PLAYER_LOOKUP_RETRIES) {
                navigationRoot.postDelayed(
                    { installMiniPlayerWhenAvailable(navigationRoot, config, attempt + 1) },
                    MINI_PLAYER_LOOKUP_DELAY_MS,
                )
            }
        }
    }

    private const val MINI_PLAYER_LOOKUP_RETRIES = 15
    private const val MINI_PLAYER_LOOKUP_DELAY_MS = 80L
}

internal object PhoneLiquidGlassQualifier {
    fun isEligible(context: Context, config: TargetConfigClient): Boolean =
        !TabletModeQualifier.isOfficialTablet(context) &&
            config.settings().phoneLiquidGlassEnabled
}

private object PhoneLiquidGlassStyler {
    private const val BOTTOM_NAVIGATION = "bottom_navigation"
    private const val BOTTOM_NAVIGATION_TABS_FRAME = "bottom_navigation_tabs_frame"
    private const val BOTTOM_NAVIGATION_TABS_DIVIDER = "navigation_tabs_divider"
    private const val BOTTOM_NAVIGATION_ROOT_STACKED = "bottom_navigation_root_stacked"
    private const val MINI_PLAYER_CONTENT = "mini_player_content"
    private const val PLAYER_SHEET_CONTAINER = "player_sheet_container"
    private const val PLAYER_FRAGMENTS_HOST = "player_fragments_host"
    private const val PLAYER_BACKGROUND_LAYERS = "background_layers"
    private const val PLAYER_TOP_SHADOW = "player_top_shadow"
    private const val NAVIGATION_HOST_GROUP = "navigation_host_group"
    private const val COORDINATOR_LAYOUT = "coordinator_layout"
    private const val GLASS_BLUR_RADIUS = 10f
    private const val MINI_GLASS_HORIZONTAL_INSET_DP = 14
    private const val NAV_GLASS_HORIZONTAL_INSET_DP = 14
    private const val MINI_GLASS_TOP_INSET_DP = 3
    private const val MINI_GLASS_BOTTOM_INSET_DP = 5
    private const val NAV_GLASS_TOP_INSET_DP = 4
    private const val NAV_GLASS_BOTTOM_INSET_DP = 12
    private const val NAV_GLASS_HEIGHT_DP = 60
    private const val NAV_SELECTION_HEIGHT_DP = 56
    private const val GLASS_CORNER_RADIUS_DP = 28
    private const val NAV_SELECTION_HORIZONTAL_INSET_DP = 2
    private const val NAV_SELECTION_MOTION_DURATION_MS = 480L
    private const val INITIAL_PLAYER_PROTECTION_MS = 3_000L

    private val installed = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val blurTargets = WeakHashMap<Activity, BlurTarget>()

    fun installBottomNavigation(root: ViewGroup) {
        if (!installed.add(root)) return
        val tabsFrame = root.findViewById<View>(targetId(root.context, BOTTOM_NAVIGATION_TABS_FRAME))
            as? FrameLayout ?: return
        val navigation = root.findViewById<View>(targetId(root.context, BOTTOM_NAVIGATION))
            as? ViewGroup ?: return

        tabsFrame.background = null
        tabsFrame.clipChildren = false
        tabsFrame.clipToPadding = false
        clearNavigationContainerBackgrounds(navigation)
        val dividerId = targetId(root.context, BOTTOM_NAVIGATION_TABS_DIVIDER)
        if (dividerId != 0) root.findViewById<View>(dividerId)?.visibility = View.GONE

        addGlassLayer(tabsFrame, GlassSurface.BOTTOM_NAVIGATION)
        navigation.post { styleNavigationItems(tabsFrame, navigation) }
    }

    private fun clearNavigationContainerBackgrounds(view: ViewGroup) {
        view.background = null
        view.elevation = 0f
        for (index in 0 until view.childCount) {
            (view.getChildAt(index) as? ViewGroup)?.let(::clearNavigationContainerBackgrounds)
        }
    }

    fun installMiniPlayer(root: FrameLayout) {
        if (!installed.add(root)) return
        root.background = null
        root.clipChildren = false
        root.clipToPadding = false

        configurePlayerSheetWhenAttached(root, attempt = 0)

        addGlassLayer(root, GlassSurface.MINI_PLAYER)

        val content = root.findViewById<View>(targetId(root.context, MINI_PLAYER_CONTENT))
        val params = content?.layoutParams as? ViewGroup.MarginLayoutParams
        if (content != null && params != null) {
            val horizontalInset = dp(root.context, MINI_GLASS_HORIZONTAL_INSET_DP + 2)
            params.leftMargin = maxOf(params.leftMargin, horizontalInset)
            params.rightMargin = maxOf(params.rightMargin, horizontalInset)
            content.layoutParams = params
        }
    }

    private fun addGlassLayer(container: FrameLayout, surface: GlassSurface) {
        if ((0 until container.childCount).any { container.getChildAt(it) is BlurView }) return
        val context = container.context
        val horizontalInset = dp(
            context,
            if (surface == GlassSurface.MINI_PLAYER) {
                MINI_GLASS_HORIZONTAL_INSET_DP
            } else {
                NAV_GLASS_HORIZONTAL_INSET_DP
            },
        )
        val topInset = dp(
            context,
            if (surface == GlassSurface.MINI_PLAYER) MINI_GLASS_TOP_INSET_DP else NAV_GLASS_TOP_INSET_DP,
        )
        val bottomInset = dp(
            context,
            if (surface == GlassSurface.MINI_PLAYER) MINI_GLASS_BOTTOM_INSET_DP else NAV_GLASS_BOTTOM_INSET_DP,
        )
        val blurView = BlurView(context).apply {
            isClickable = false
            isFocusable = false
            background = glassSurfaceDrawable(context, surface)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(
                        0,
                        0,
                        view.width,
                        view.height,
                        dp(context, GLASS_CORNER_RADIUS_DP).toFloat(),
                    )
                }
            }
            clipToOutline = true
            elevation = dp(context, 16).toFloat()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineAmbientShadowColor = Color.argb(120, 0, 0, 0)
                outlineSpotShadowColor = Color.argb(150, 0, 0, 0)
            }
        }
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (surface == GlassSurface.BOTTOM_NAVIGATION) {
                dp(context, NAV_GLASS_HEIGHT_DP)
            } else {
                ViewGroup.LayoutParams.MATCH_PARENT
            },
        ).apply {
            if (surface == GlassSurface.BOTTOM_NAVIGATION) {
                // Apple Music's tabs frame extends through the gesture inset and
                // beyond the visible window. A match-parent glass layer therefore
                // loses its lower corners off-screen. The reference bar is a
                // self-contained 52dp capsule above the gesture indicator.
                gravity = Gravity.TOP
                setMargins(horizontalInset, 0, horizontalInset, 0)
            } else {
                setMargins(horizontalInset, topInset, horizontalInset, bottomInset)
            }
        }
        container.addView(blurView, 0, params)
        for (index in 1 until container.childCount) {
            container.getChildAt(index).translationZ = dp(context, 17).toFloat()
        }
        configureBlurWhenAttached(blurView, attempt = 0)
    }

    private fun configureBlurWhenAttached(blurView: BlurView, attempt: Int) {
        blurView.post {
            val activity = blurView.context.findActivity() ?: return@post
            val target = wrapNavigationHostInBlurTarget(activity)
            if (target == null) {
                if (attempt < 8) {
                    blurView.postDelayed(
                        { configureBlurWhenAttached(blurView, attempt + 1) },
                        80L,
                    )
                }
                return@post
            }
            val overlay = if (isNightMode(blurView.context)) {
                Color.argb(20, 0, 0, 0)
            } else {
                Color.argb(20, 255, 255, 255)
            }
            // BlurView's optional noise bitmap is resolved through the host app's
            // Resources. In an injected LSPosed process that resource belongs to
            // the module and decodes as null, so keep RenderNode blur but disable
            // the library's noise pass.
            blurView.setupWith(target, 4f, false)
                // The decor background is opaque white. During the single
                // RenderNode re-recording frame at player collapse it would be
                // exposed by both glass surfaces as a synchronized white flash.
                // Clear transparently so the live page remains visible until
                // the new target snapshot is ready.
                .setFrameClearDrawable(ColorDrawable(Color.TRANSPARENT))
                .setBlurRadius(GLASS_BLUR_RADIUS)
                .setOverlayColor(overlay)
        }
    }

    private fun wrapNavigationHostInBlurTarget(activity: Activity): BlurTarget? {
        blurTargets[activity]?.takeIf(View::isAttachedToWindow)?.let { return it }
        configureTransparentGestureNavigation(activity)
        val root = activity.window.decorView as? ViewGroup ?: return null
        val hostId = targetId(activity, NAVIGATION_HOST_GROUP)
        if (hostId == 0) return null
        val coordinatorId = targetId(activity, COORDINATOR_LAYOUT)
        val outerHost = findNavigationHost(root, hostId, coordinatorId) ?: return null
        (outerHost.parent as? BlurTarget)?.let { existingTarget ->
            blurTargets[activity] = existingTarget
            return existingTarget
        }
        val parent = outerHost.parent as? ViewGroup ?: return null
        val index = parent.indexOfChild(outerHost).takeIf { it >= 0 } ?: return null

        return runCatching {
            outerHost.setPadding(
                outerHost.paddingLeft,
                outerHost.paddingTop,
                outerHost.paddingRight,
                0,
            )
            outerHost.clipChildren = false
            outerHost.clipToPadding = false
            val hostParams = outerHost.layoutParams
            (hostParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
            parent.removeViewAt(index)
            val target = BlurTarget(activity)
            parent.addView(target, index, hostParams)
            target.addView(
                outerHost,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            target.clipChildren = false
            target.clipToPadding = false
            target.viewTreeObserver.addOnGlobalLayoutListener {
                syncNavigationUnderlap(outerHost, target, hostId)
            }
            target.post { syncNavigationUnderlap(outerHost, target, hostId) }
            blurTargets[activity] = target
            target
        }.onFailure { error ->
            ModernXposedRuntime.log("navigation-host BlurTarget install failed", error)
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun configureTransparentGestureNavigation(activity: Activity) {
        val window = activity.window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun findNavigationHost(
        root: ViewGroup,
        hostId: Int,
        coordinatorId: Int,
    ): ViewGroup? {
        val candidates = mutableListOf<ViewGroup>()
        fun collect(view: View) {
            if (view.id == hostId && view is ViewGroup) candidates += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
        }
        collect(root)
        return candidates.firstOrNull { candidate ->
            coordinatorId != 0 && (candidate.parent as? View)?.id == coordinatorId
        } ?: candidates.maxByOrNull(View::getHeight)
    }

    private fun syncNavigationUnderlap(outerHost: ViewGroup, target: BlurTarget, hostId: Int) {
        (target.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            if (params.bottomMargin != 0) {
                params.bottomMargin = 0
                target.layoutParams = params
            }
        }
        if (target.paddingBottom != 0) {
            target.setPadding(target.paddingLeft, target.paddingTop, target.paddingRight, 0)
        }
        if (outerHost.paddingBottom != 0) {
            outerHost.setPadding(
                outerHost.paddingLeft,
                outerHost.paddingTop,
                outerHost.paddingRight,
                0,
            )
        }
        expandNavigationHostPath(outerHost, hostId)
    }

    private fun expandNavigationHostPath(view: View, hostId: Int): Boolean {
        var childContainsHost = false
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (expandNavigationHostPath(view.getChildAt(index), hostId)) {
                    childContainsHost = true
                }
            }
        }
        val containsHost = view.id == hostId || childContainsHost
        if (!containsHost) return false
        view.layoutParams?.let { params ->
            if (params.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                params.height != ViewGroup.LayoutParams.MATCH_PARENT
            ) {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
                view.layoutParams = params
            }
        }
        if (view is ViewGroup) {
            if (view.paddingBottom != 0) {
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, 0)
            }
            view.clipChildren = false
            view.clipToPadding = false
        }
        return true
    }

    private fun styleNavigationItems(tabsFrame: FrameLayout, navigation: ViewGroup) {
        val menu = (0 until navigation.childCount)
            .map(navigation::getChildAt)
            .filterIsInstance<ViewGroup>()
            .firstOrNull() ?: return
        val menuInset = dp(navigation.context, NAV_GLASS_HORIZONTAL_INSET_DP)
        menu.layoutParams = menu.layoutParams.apply {
            width = maxOf(1, navigation.width - menuInset * 2)
        }
        // BottomNavigationView centers a fixed-width menu child itself.
        // Adding the inset again would push the fifth item outside the glass.
        menu.translationX = 0f
        menu.requestLayout()
        val items = (0 until menu.childCount).map(menu::getChildAt)
        if (items.isEmpty()) return

        for (index in 0 until menu.childCount) {
            val item = menu.getChildAt(index)
            item.background = ColorDrawable(Color.TRANSPARENT)
            item.minimumHeight = dp(item.context, 48)
            item.stateListAnimator = navigationItemPressAnimator(item)
        }

        val indicator = LiquidSelectionIndicator(tabsFrame.context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            elevation = dp(context, 16).toFloat()
            translationZ = resources.displayMetrics.density * 0.5f
        }
        tabsFrame.addView(
            indicator,
            minOf(1, tabsFrame.childCount),
            FrameLayout.LayoutParams(
                1,
                dp(tabsFrame.context, NAV_SELECTION_HEIGHT_DP),
                Gravity.TOP or Gravity.START,
            ).apply { topMargin = dp(tabsFrame.context, 2) },
        )

        var selectedIndex = -1
        var selectedX = Float.NaN
        var selectedWidth = -1
        navigation.viewTreeObserver.addOnPreDrawListener {
            val nextIndex = items.indexOfFirst(::hasCheckedState)
                .takeIf { it >= 0 }
                ?: items.indexOfFirst { it.isSelected || it.isActivated }
            if (nextIndex >= 0) {
                val item = items[nextIndex]
                val frameLocation = IntArray(2)
                val itemLocation = IntArray(2)
                tabsFrame.getLocationInWindow(frameLocation)
                item.getLocationInWindow(itemLocation)
                val inset = dp(tabsFrame.context, NAV_SELECTION_HORIZONTAL_INSET_DP)
                val nextX = (itemLocation[0] - frameLocation[0] + inset).toFloat()
                val nextWidth = maxOf(1, item.width - inset * 2)
                val indexChanged = nextIndex != selectedIndex
                val geometryChanged = nextX != selectedX || nextWidth != selectedWidth
                if (indexChanged || geometryChanged) {
                    indicator.moveTo(
                        x = nextX,
                        width = nextWidth,
                        animated = selectedIndex >= 0 && indexChanged,
                    )
                    selectedIndex = nextIndex
                    selectedX = nextX
                    selectedWidth = nextWidth
                }
            }
            true
        }
    }

    private fun hasCheckedState(view: View): Boolean =
        view.drawableState.any { it == android.R.attr.state_checked }

    private fun glassSurfaceDrawable(context: Context, surface: GlassSurface): Drawable {
        val night = isNightMode(context)
        val colors = if (night) {
            intArrayOf(Color.argb(72, 255, 255, 255), Color.argb(32, 255, 255, 255))
        } else {
            // SurfingTile's runtime bar keeps the sampled content visible instead
            // of painting an opaque white card over it. The mini player remains
            // slightly brighter than the navigation surface, matching the target
            // Apple Music reference while sharing the same low-tint material.
            if (surface == GlassSurface.MINI_PLAYER) {
                intArrayOf(Color.argb(112, 255, 255, 255), Color.argb(66, 235, 239, 246))
            } else {
                intArrayOf(Color.argb(92, 255, 255, 255), Color.argb(54, 232, 236, 243))
            }
        }
        val outer = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = dp(context, GLASS_CORNER_RADIUS_DP).toFloat()
            setStroke(
                dp(context, 1),
                if (night) Color.argb(145, 255, 255, 255) else Color.argb(92, 128, 132, 140),
            )
        }
        val inner = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(context, GLASS_CORNER_RADIUS_DP - 1).toFloat()
            setStroke(
                dp(context, 1),
                if (night) Color.argb(54, 255, 255, 255) else Color.argb(150, 255, 255, 255),
            )
        }
        return LayerDrawable(arrayOf(outer, InsetDrawable(inner, dp(context, 1))))
    }

    private fun navigationItemPressAnimator(item: View): StateListAnimator = StateListAnimator().apply {
        val pressed = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(item, View.SCALE_X, 0.94f),
                ObjectAnimator.ofFloat(item, View.SCALE_Y, 0.94f),
            )
            duration = 80L
        }
        val released = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(item, View.SCALE_X, 1f),
                ObjectAnimator.ofFloat(item, View.SCALE_Y, 1f),
            )
            duration = 180L
        }
        addState(
            intArrayOf(android.R.attr.state_pressed),
            pressed,
        )
        addState(intArrayOf(), released)
    }

    /**
     * Shared selection lens reconstructed from the reference app's runtime
     * motion: a fast positional catch-up followed by a slower elastic settle
     * with a short chromatic refraction fringe. It sits below the untouched
     * Apple Music navigation items, so their click, reselect and accessibility
     * behavior remains native.
     */
    private class LiquidSelectionIndicator(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val rect = RectF()
        private val gradientMatrix = Matrix()
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = Color.argb(155, 255, 255, 255)
        }
        private val refractionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * density
        }
        private val positionInterpolator = PathInterpolator(0.16f, 0.92f, 0.22f, 1f)
        private var motionStrength = 0f
        private var motionAnimator: ValueAnimator? = null

        init {
            setWillNotDraw(false)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                }
            }
        }

        fun moveTo(x: Float, width: Int, animated: Boolean) {
            val params = layoutParams
            if (params.width != width) {
                params.width = width
                layoutParams = params
            }
            motionAnimator?.cancel()
            if (!animated || !isLaidOut) {
                translationX = x
                scaleX = 1f
                scaleY = 1f
                motionStrength = 0f
                invalidate()
                return
            }

            val startX = translationX
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = NAV_SELECTION_MOTION_DURATION_MS
                addUpdateListener { valueAnimator ->
                    val raw = valueAnimator.animatedValue as Float
                    val position = positionInterpolator.getInterpolation(raw)
                    val pulse = kotlin.math.sin(Math.PI * raw).toFloat()
                    translationX = startX + (x - startX) * position
                    scaleX = 1f + 0.17f * pulse
                    scaleY = 1f + 0.055f * pulse
                    motionStrength = pulse
                    invalidateOutline()
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (motionAnimator !== animation) return
                        translationX = x
                        scaleX = 1f
                        scaleY = 1f
                        motionStrength = 0f
                        invalidateOutline()
                        invalidate()
                        motionAnimator = null
                    }
                })
            }
            motionAnimator = animator
            animator.start()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = 1.25f * density
            rect.set(inset, inset, width - inset, height - inset)
            val radius = rect.height() / 2f
            val night = isNightMode(context)
            fillPaint.shader = LinearGradient(
                0f,
                rect.top,
                0f,
                rect.bottom,
                if (night) Color.argb(135, 255, 255, 255) else Color.argb(156, 255, 255, 255),
                if (night) Color.argb(72, 255, 255, 255) else Color.argb(112, 207, 212, 220),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, radius, radius, fillPaint)

            if (motionStrength > 0.01f) {
                val sweep = SweepGradient(
                    rect.centerX(),
                    rect.centerY(),
                    intArrayOf(
                        Color.TRANSPARENT,
                        Color.argb(230, 43, 214, 255),
                        Color.TRANSPARENT,
                        Color.argb(225, 255, 214, 66),
                        Color.TRANSPARENT,
                        Color.argb(220, 255, 72, 197),
                        Color.TRANSPARENT,
                    ),
                    null,
                )
                gradientMatrix.setRotate(36f + motionStrength * 75f, rect.centerX(), rect.centerY())
                sweep.setLocalMatrix(gradientMatrix)
                refractionPaint.shader = sweep
                refractionPaint.alpha = (235f * motionStrength).toInt()
                canvas.drawRoundRect(rect, radius, radius, refractionPaint)
                refractionPaint.shader = null
            }
            canvas.drawRoundRect(rect, radius, radius, rimPaint)
        }
    }

    private fun configurePlayerSheetWhenAttached(miniPlayer: View, attempt: Int) {
        miniPlayer.post {
            val sheetId = targetId(miniPlayer.context, PLAYER_SHEET_CONTAINER)
            val sheet = sheetId.takeIf { it != 0 }
                ?.let { miniPlayer.rootView.findViewById<View>(it) }
            if (sheet != null) {
                makePlayerSheetTransparentWhileCollapsed(miniPlayer, sheet)
            } else if (attempt < 8) {
                miniPlayer.postDelayed(
                    { configurePlayerSheetWhenAttached(miniPlayer, attempt + 1) },
                    80L,
                )
            }
        }
    }

    private fun makePlayerSheetTransparentWhileCollapsed(miniPlayer: View, sheet: View) {
        val transparent = ColorDrawable(Color.TRANSPARENT)
        // Apple Music oscillates the sheet between expanded and collapsed
        // geometry during cold start. Hiding the entire sheet and restoring it
        // on a timer produces a visible whole-page flash. Keep the page and mini
        // player drawable, but suppress only full-player visuals throughout the
        // bootstrap window and restore them on the first genuine user expansion.
        var awaitingInitialCollapse = miniPlayer.visibility == View.VISIBLE
        sheet.alpha = 1f
        val fullPlayerHostId = targetId(sheet.context, PLAYER_FRAGMENTS_HOST)
        val fullPlayerHost = fullPlayerHostId.takeIf { it != 0 }
            ?.let { sheet.rootView.findViewById<View>(it) }
        val savedFullPlayerAlpha = fullPlayerHost?.alpha ?: 1f
        var fullPlayerSuppressed = awaitingInitialCollapse
        if (fullPlayerSuppressed) fullPlayerHost?.alpha = 0f
        val protectionEndsAt = android.os.SystemClock.uptimeMillis() + INITIAL_PLAYER_PROTECTION_MS
        var previousSheetTop = sheet.top
        val stackedRootId = targetId(sheet.context, BOTTOM_NAVIGATION_ROOT_STACKED)
        val stackedRoot = stackedRootId.takeIf { it != 0 }
            ?.let { sheet.rootView.findViewById<View>(it) }
        val surfaces = mutableListOf<View>()
        var current: View? = miniPlayer
        while (current != null) {
            surfaces += current
            if (current === stackedRoot) break
            current = current.parent as? View
        }
        if (sheet !in surfaces) surfaces += sheet
        if (stackedRoot != null && stackedRoot !in surfaces) surfaces += stackedRoot
        val savedBackgrounds = WeakHashMap<View, Drawable?>().apply {
            surfaces.forEach { put(it, it.background) }
        }
        val decorativeIds = intArrayOf(
            targetId(sheet.context, PLAYER_BACKGROUND_LAYERS),
            targetId(sheet.context, PLAYER_TOP_SHADOW),
        ).filter { it != 0 }
        val savedVisibility = WeakHashMap<View, Int>()
        val savedAlpha = WeakHashMap<View, Float>()
        lateinit var sync: () -> Unit
        sync = sync@{
            val rootHeight = sheet.rootView.height
            if (rootHeight <= 0) return@sync
            val miniPlayerVisible = miniPlayer.visibility == View.VISIBLE
            val atCollapsedPosition = sheet.top > rootHeight / 2
            val movingTowardExpanded = sheet.top < previousSheetTop
            previousSheetTop = sheet.top
            if (awaitingInitialCollapse) {
                when {
                    !miniPlayerVisible -> {
                        awaitingInitialCollapse = false
                        fullPlayerHost?.alpha = savedFullPlayerAlpha
                        fullPlayerSuppressed = false
                    }
                    movingTowardExpanded &&
                        android.os.SystemClock.uptimeMillis() >= protectionEndsAt -> {
                        awaitingInitialCollapse = false
                        fullPlayerHost?.alpha = savedFullPlayerAlpha
                        fullPlayerSuppressed = false
                    }
                }
                if (awaitingInitialCollapse) fullPlayerHost?.alpha = 0f
            }
            if (!awaitingInitialCollapse && fullPlayerSuppressed &&
                (movingTowardExpanded || !miniPlayerVisible)
            ) {
                fullPlayerHost?.alpha = savedFullPlayerAlpha
                fullPlayerSuppressed = false
            }
            val collapsed = atCollapsedPosition && miniPlayerVisible
            val visuallyCollapsed = collapsed || awaitingInitialCollapse
            surfaces.forEach { surface ->
                if (visuallyCollapsed) {
                    if (surface.background !== transparent) {
                        savedBackgrounds[surface] = surface.background
                    }
                    surface.background = transparent
                } else if (surface.background === transparent) {
                    surface.background = savedBackgrounds[surface]
                }
            }
            decorativeIds.mapNotNull { sheet.rootView.findViewById<View>(it) }.forEach { layer ->
                if (!savedVisibility.containsKey(layer)) {
                    savedVisibility[layer] = layer.visibility
                    savedAlpha[layer] = layer.alpha
                }
                if (visuallyCollapsed) {
                    layer.visibility = View.INVISIBLE
                    layer.alpha = 0f
                } else {
                    layer.visibility = savedVisibility[layer] ?: View.VISIBLE
                    layer.alpha = savedAlpha[layer] ?: 1f
                }
            }
        }
        sheet.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sync() }
        sheet.viewTreeObserver.addOnPreDrawListener(
            ViewTreeObserver.OnPreDrawListener {
                sync()
                true
            },
        )
        sheet.post { sync() }
    }

    private fun targetId(context: Context, name: String): Int =
        context.resources.getIdentifier(name, "id", ModuleConstants.TARGET_PACKAGE)

    private fun isNightMode(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private enum class GlassSurface {
        BOTTOM_NAVIGATION,
        MINI_PLAYER,
    }
}
