package dev.amenhancer.module.ui

import dev.amenhancer.module.i18n.ModuleText

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.amenhancer.glass.GlassPolicy
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.lyrics.LlmLyricsClient
import dev.amenhancer.module.lyrics.LlmLyricsConfig
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

internal fun EmbeddedSettingsHost.showSettingsDialog(activity: Activity) {
        val currentDialog = dialogReference?.get()
        if (currentDialog?.isShowing == true) return

        val initialSettings = runCatching { controller.currentSettings() }.getOrElse {
            Toast.makeText(activity, localizedText(ModuleText.SETTINGS_READ_FAILED), Toast.LENGTH_SHORT).show()
            return
        }
        val draft = EmbeddedSettingsDraft(initialSettings, controller::saveOrdinarySettings)
        var page = EmbeddedSettingsPage.MAIN
        var dialogReady = false
        val pageScrollPositions = mutableMapOf<EmbeddedSettingsPage, Int>()
        var stopPageTransition: (() -> Unit)? = null
        var cancelPageReady: (() -> Unit)? = null
        var cancelPagePreparation: (() -> Unit)? = null
        var disposePage: (() -> Unit)? = null
        var pageTransitionActive = false
        var pendingNavigation: (() -> Unit)? = null
        var refreshAfterTransition = false
        val panelBounds = RectF()
        val panelClip = Path()
        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val panelShadow = EmbeddedPanelShadow(activity.resources.displayMetrics.density)
        var onViewportChanged: (() -> Unit)? = null
        var onPluginBoundsChanged: ((RectF) -> Unit)? = null
        var onTransitionFinished: (() -> Unit)? = null
        lateinit var dialog: AlertDialog
        val barHeight = dp(activity, 64)
        val barHorizontalInset = dp(activity, if (isEmbeddedPhone(activity)) 8 else 0)

        val pageHost = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            val hostInset = dp(activity, if (isEmbeddedPhone(activity)) 0 else 8)
            setPadding(hostInset, 0, hostInset, 0)
            setEmbeddedBackgroundColor(activity) { colors -> colors.pageBackground }
        }
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = barHeight
            setPadding(barHorizontalInset, 0, barHorizontalInset, 0)
        }
        val backButton = ImageView(activity).apply {
            setEmbeddedImageDrawable(activity,
                embeddedSvgDrawable(EmbeddedSvgIcon.Back, activity) ?: EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.BackArrow,
                    EmbeddedSettingsPalette.Light.primary,
                ),
            )
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = localizedText(ModuleText.BACK)
            isClickable = true
            isFocusable = true
            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
            bindEmbeddedClickFeedback(activity, cornerRadiusDp = 12)
        }
        val moduleIcon = ImageView(activity).apply {
            setEmbeddedImageDrawable(activity, EmbeddedAmppBrandDrawable(cornerRadiusPx = dp(activity, 12).toFloat()))
            contentDescription = "AM++"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(0, 0, 0, 0)
        }
        val pageTitle = TextView(activity).apply {
            textSize = embeddedTextSize(activity, 19f, 18f)
            setEmbeddedTextColor(activity) { colors -> colors.onSurface }
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(false)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(backButton, LinearLayout.LayoutParams(dp(activity, 44), barHeight))
        topBar.addView(moduleIcon, LinearLayout.LayoutParams(embeddedHeaderIconSize(activity), embeddedHeaderIconSize(activity)).apply {
            marginStart = dp(activity, if (isEmbeddedPhone(activity)) 0 else 8)
            marginEnd = dp(activity, 8)
        })
        topBar.addView(pageTitle)
        val pageAction = Button(activity).apply {
            text = localizedText(ModuleText.SETTINGS_DETAILS_ACTION)
            bindEmbeddedDialogActionTheme(activity)
            setOnClickListener {
                cancelPagePreparation?.invoke()
                cancelPagePreparation = null
                if (page == EmbeddedSettingsPage.PLUGINS) {
                    launchSafPicker(activity, EmbeddedSafOperation.PluginZip, "*/*",
                        arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                } else showEmbeddedPageDetails(activity, page)
            }
        }
        topBar.addView(pageAction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 48)))
        pageHost.addView(topBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            barHeight,
        ))
        val pageContent = FrameLayout(activity)
        pageHost.addView(pageContent, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        // Keep the close action inside our content tree.  AlertDialog's default
        // button panel adds theme-dependent padding that made the phone layout
        // look like it had an oversized blank footer.
        val closeBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setEmbeddedBackgroundColor(activity) { colors -> colors.pageBackground }
            setPadding(barHorizontalInset, 0, barHorizontalInset, 0)
        }
        val closeButton = Button(activity).apply {
            text = localizedText(ModuleText.CLOSE)
            bindEmbeddedDialogActionTheme(activity)
            contentDescription = localizedText(ModuleText.CLOSE_SETTINGS)
            setOnClickListener { dialog.dismiss() }
        }
        closeBar.addView(closeButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(activity, 48),
        ))
        pageHost.addView(closeBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            barHeight,
        ))

        val root = object : FrameLayout(activity) {
            private var viewportWidth = 0
            private var viewportHeight = 0

            override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean =
                pageTransitionActive || super.dispatchTouchEvent(event)

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val availableWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                    activity.resources.displayMetrics.widthPixels
                } else MeasureSpec.getSize(widthMeasureSpec)
                val availableHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                    activity.resources.displayMetrics.heightPixels
                } else MeasureSpec.getSize(heightMeasureSpec)
                val panelWidth = embeddedPanelWidth(activity, page, availableWidth)
                val panelMaxHeight = (availableHeight - dp(activity, 16)).coerceAtLeast(1)
                val exactWidth = MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY)
                val animatedHeight = pageHost.measuredHeight.takeIf {
                    pageTransitionActive && stopPageTransition != null &&
                        viewportWidth == availableWidth && viewportHeight == availableHeight
                }
                pageHost.measure(exactWidth, MeasureSpec.makeMeasureSpec(panelMaxHeight, MeasureSpec.EXACTLY))
                val scroll = pageContent.getChildAt(0) as? ScrollView
                val contentHeight = topBar.measuredHeight + (scroll?.getChildAt(0)?.measuredHeight ?: 0) +
                    (scroll?.paddingTop ?: 0) + (scroll?.paddingBottom ?: 0) +
                    (if (closeBar.visibility == View.VISIBLE) closeBar.measuredHeight else 0)
                // Every page follows its actual content height and scrolls only
                // when that content exceeds the available viewport.
                val panelHeight = (animatedHeight ?: contentHeight).coerceIn(1, panelMaxHeight)
                if (panelHeight != panelMaxHeight) {
                    pageHost.measure(exactWidth, MeasureSpec.makeMeasureSpec(panelHeight, MeasureSpec.EXACTLY))
                }
                for (index in 0 until childCount) {
                    val child = getChildAt(index)
                    if (child !== pageHost) {
                        val params = child.layoutParams
                        child.measure(MeasureSpec.makeMeasureSpec(params.width, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(params.height, MeasureSpec.EXACTLY))
                    }
                }
                setMeasuredDimension(availableWidth, availableHeight)
            }

            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                super.onLayout(changed, left, top, right, bottom)
                val viewportChanged = viewportWidth > 0 && viewportHeight > 0 &&
                    (viewportWidth != width || viewportHeight != height)
                viewportWidth = width
                viewportHeight = height
                if (viewportChanged && pageTransitionActive) onViewportChanged?.invoke()
                if (!pageTransitionActive || panelBounds.isEmpty) {
                    val targetBounds = RectF(pageHost.left.toFloat(), pageHost.top.toFloat(),
                        pageHost.right.toFloat(), pageHost.bottom.toFloat())
                    if (page == EmbeddedSettingsPage.PLUGINS && dialogReady && !viewportChanged &&
                        cancelPageReady == null && !panelBounds.isEmpty && panelBounds != targetBounds
                    ) {
                        onPluginBoundsChanged?.invoke(targetBounds)
                    } else panelBounds.set(targetBounds)
                }
            }

            override fun dispatchDraw(canvas: Canvas) {
                if (panelBounds.isEmpty) return
                val radius = dp(activity, 24).toFloat()
                panelShadow.draw(canvas, panelBounds, radius)
                panelClip.setEmbeddedG3Rect(panelBounds, radius)
                val save = canvas.save()
                canvas.clipPath(panelClip)
                canvas.drawPath(panelClip, panelPaint)
                super.dispatchDraw(canvas)
                canvas.restoreToCount(save)
            }
        }.apply {
            bindEmbeddedTheme(activity) { colors ->
                panelPaint.color = colors.pageBackground
                invalidate()
            }
            setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_UP && !panelBounds.contains(event.x, event.y)) {
                    dialog.cancel()
                }
                true
            }
            addView(pageHost, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ))
        }
        var outgoingPage: ImageView? = null
        fun clearOutgoingPage() {
            outgoingPage?.let { outgoing ->
                root.removeView(outgoing)
                outgoing.setImageDrawable(null)
            }
            outgoingPage = null
        }

        fun updateDraft(next: ModuleSettings) {
            if (!draft.update(next)) {
                Toast.makeText(activity, localizedText(ModuleText.SETTINGS_SAVE_FAILED), Toast.LENGTH_SHORT).show()
            }
        }

        fun syncBottomCloseButton() {
            closeBar.visibility = if (page == EmbeddedSettingsPage.MAIN) View.VISIBLE else View.GONE
        }

        fun renderPage(nextPage: EmbeddedSettingsPage = page) {
            cancelPagePreparation?.invoke()
            cancelPagePreparation = null
            val changingPage = nextPage != page
            if (pageTransitionActive) {
                if (changingPage) pendingNavigation = { renderPage(nextPage) }
                else refreshAfterTransition = true
                return
            }
            val customLyricsPage = nextPage == EmbeddedSettingsPage.CUSTOM_LYRICS
            val automaticLyricsPage = nextPage == EmbeddedSettingsPage.AUTOMATIC_LYRICS
            val llmLyricsPage = nextPage == EmbeddedSettingsPage.LLM_LYRICS
            val lyricsPage = customLyricsPage || automaticLyricsPage || llmLyricsPage
            val bottomInset = dp(activity, when {
                nextPage == EmbeddedSettingsPage.MAIN -> 0
                lyricsPage -> 8
                else -> 12
            })
            val scroll = ScrollView(activity).apply {
                isFillViewport = false
                clipChildren = !customLyricsPage
                clipToPadding = !customLyricsPage
                isVerticalScrollBarEnabled = false
                isVerticalFadingEdgeEnabled = true
                setFadingEdgeLength(dp(activity, 18))
                setEmbeddedBackgroundColor(activity) { colors -> colors.surface }
                val horizontalInset = when {
                    nextPage == EmbeddedSettingsPage.BOTTOM_BAR_ANIMATION -> 0
                    customLyricsPage -> 16
                    else -> 8
                }
                setPadding(
                    dp(activity, horizontalInset),
                    0,
                    dp(activity, horizontalInset),
                    0,
                )
            }
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = !customLyricsPage
                // Bottom-only padding also clips the side edges by default.
                // Let the lyrics ripple reach the ScrollView's side inset.
                clipToPadding = !customLyricsPage
                // Keep both fading edges at the viewport edges. A ScrollView's
                // own bottom padding leaves unfaded ink when clipToPadding=false.
                setPadding(0, 0, 0, bottomInset)
            }
            scroll.addView(content)
            var preparationCancelled = false
            var disposePreparedPage: (() -> Unit)? = null
            cancelPagePreparation = {
                preparationCancelled = true
                disposePreparedPage?.invoke()
                disposePreparedPage = null
            }

            fun showPreparedPage() {
                if (preparationCancelled || (dialogReady && !dialog.isShowing)) return
                cancelPagePreparation = null
                val previousScroll = pageContent.getChildAt(0) as? ScrollView
                previousScroll?.let { pageScrollPositions[page] = it.scrollY }
                cancelPageReady?.invoke()
                cancelPageReady = null
                stopPageTransition?.invoke()
                stopPageTransition = null
                clearOutgoingPage()
                val startBounds = RectF(panelBounds)
                val snapshot = if (dialogReady) pageHost.captureEmbeddedSettingsBackdrop() else null
                val outgoing = snapshot?.let { backdrop ->
                    ImageView(activity).apply {
                        setImageDrawable(backdrop)
                        scaleType = ImageView.ScaleType.FIT_XY
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }.also { view ->
                        root.addView(view, 0, FrameLayout.LayoutParams(
                            pageHost.width, pageHost.height, Gravity.CENTER))
                        outgoingPage = view
                    }
                }
                pageTransitionActive = outgoing != null
                pageHost.alpha = if (pageTransitionActive) 0f else 1f
                disposePage?.invoke()
                disposePage = disposePreparedPage
                disposePreparedPage = null
                page = nextPage
                pageContent.removeAllViews()
                pageContent.addView(scroll, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
                val childPage = page != EmbeddedSettingsPage.MAIN
                pageTitle.text = when (page) {
                    EmbeddedSettingsPage.MAIN -> "AM++"
                    EmbeddedSettingsPage.CUSTOM_LYRICS -> localizedText(ModuleText.CATEGORY_LYRICS_MANAGEMENT)
                    EmbeddedSettingsPage.AUTOMATIC_LYRICS -> localizedText(ModuleText.AUTO_LYRICS)
                    EmbeddedSettingsPage.LYRICS_EFFECTS -> localizedText(ModuleText.CATEGORY_LYRICS_EFFECTS)
                    EmbeddedSettingsPage.INTERFACE -> localizedText(ModuleText.CATEGORY_INTERFACE)
                    EmbeddedSettingsPage.TITLE_REGION -> localizedText(ModuleText.CATEGORY_TITLE_REGION)
                    EmbeddedSettingsPage.ADVANCED -> localizedText(ModuleText.ADVANCED_SETTINGS)
                    EmbeddedSettingsPage.PLUGINS -> localizedText(ModuleText.PLUGINS)
                    EmbeddedSettingsPage.LLM_LYRICS -> localizedText(ModuleText.LLM_LYRICS_FALLBACK)
                    EmbeddedSettingsPage.BOTTOM_BAR_ANIMATION -> localizedText(ModuleText.BOTTOM_BAR_ANIMATION_SETTINGS)
                }
                backButton.visibility = if (childPage) View.VISIBLE else View.GONE
                moduleIcon.visibility = if (childPage) View.GONE else View.VISIBLE
                pageAction.text = localizedText(if (page == EmbeddedSettingsPage.PLUGINS) ModuleText.IMPORT_ZIP else ModuleText.SETTINGS_DETAILS_ACTION)
                pageAction.isEnabled = page != EmbeddedSettingsPage.PLUGINS || plugins != null
                syncBottomCloseButton()
                refreshEmbeddedTheme(activity)
                val restoredScrollY = pageScrollPositions[page] ?: 0
                val ready = object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (pageContent.getChildAt(0) !== scroll) {
                            if (scroll.viewTreeObserver.isAlive) scroll.viewTreeObserver.removeOnPreDrawListener(this)
                            return true
                        }
                        if (scroll.viewTreeObserver.isAlive) scroll.viewTreeObserver.removeOnPreDrawListener(this)
                        if (pageContent.getChildAt(0) !== scroll) return true
                        cancelPageReady = null
                        if (!dialogReady || !dialog.isShowing) {
                            pageHost.alpha = 1f
                            pageTransitionActive = false
                            clearOutgoingPage()
                            return true
                        }
                        scroll.scrollTo(0, restoredScrollY)
                        val endBounds = RectF(pageHost.left.toFloat(), pageHost.top.toFloat(),
                            pageHost.right.toFloat(), pageHost.bottom.toFloat())
                        if (pageTransitionActive) {
                            val stop = pageHost.morphEmbeddedSettingsPage(outgoing, startBounds, endBounds, onBoundsChanged = { bounds ->
                                panelBounds.set(bounds)
                                root.invalidate()
                            }) {
                                onTransitionFinished?.invoke()
                            }
                            if (pageTransitionActive) stopPageTransition = stop
                        } else {
                            panelBounds.set(endBounds)
                            root.invalidate()
                        }
                        return true
                    }
                }
                scroll.viewTreeObserver.addOnPreDrawListener(ready)
                cancelPageReady = {
                    if (scroll.viewTreeObserver.isAlive) scroll.viewTreeObserver.removeOnPreDrawListener(ready)
                }
            }

            if (customLyricsPage) {
                renderEmbeddedCustomLyricsPage(
                    activity = activity,
                    parent = content,
                    settings = draft.settings,
                    song = controller.currentSongDetails(),
                    onSettingsChanged = ::updateDraft,
                    onOpenLlmLyrics = {
                        renderPage(EmbeddedSettingsPage.LLM_LYRICS)
                    },
                    onOpenAutomaticLyrics = {
                        renderPage(EmbeddedSettingsPage.AUTOMATIC_LYRICS)
                    },
                )
            } else if (automaticLyricsPage) {
                renderEmbeddedAutomaticLyricsPage(
                    activity = activity,
                    parent = content,
                    currentSettings = { draft.settings },
                    onSettingsChanged = ::updateDraft,
                )
            } else if (llmLyricsPage) {
                renderEmbeddedLlmLyricsPage(
                    activity = activity,
                    parent = content,
                    settings = draft.settings,
                    onSettingsChanged = ::updateDraft,
                )
            } else if (nextPage == EmbeddedSettingsPage.BOTTOM_BAR_ANIMATION) {
                renderEmbeddedAnimationSettingsPage(
                    activity = activity,
                    parent = content,
                    currentSettings = { draft.settings },
                    onSettingsChanged = ::updateDraft,
                )
            } else if (nextPage == EmbeddedSettingsPage.PLUGINS) {
                // Prepare off-screen. The current page stays interactive until
                // the first result supplies the one real transition height.
                disposePreparedPage = renderEmbeddedPluginManagementPage(activity, content, scroll,
                    onInitialContentReady = { root.post { showPreparedPage() } })
                return
            } else if (nextPage == EmbeddedSettingsPage.MAIN) {
                renderEmbeddedMainPage(
                    activity = activity,
                    parent = content,
                    settings = draft.settings,
                    lyricsCount = runCatching { controller.lyricsEntries().size }.getOrDefault(0),
                    onOpenPage = { renderPage(it) },
                )
            } else {
                renderEmbeddedCategoryPage(
                    activity = activity,
                    parent = content,
                    page = nextPage,
                    currentSettings = { draft.settings },
                    onSettingsChanged = ::updateDraft,
                    onCellularDataEntryChanged = { enabled ->
                        if (!draft.updateCellularDataEntry(enabled)) {
                            Toast.makeText(activity, localizedText(ModuleText.SETTINGS_SAVE_FAILED), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenAnimationSettings = {
                        renderPage(EmbeddedSettingsPage.BOTTOM_BAR_ANIMATION)
                    },
                    onChooseFont = {
                        launchSafPicker(
                            activity,
                            EmbeddedSafOperation.Font,
                            "*/*",
                            EMBEDDED_FONT_MIME_TYPES,
                        )
                    },
                    onClearFont = { runAsync(activity, controller::clearFont) },
                )
            }
            showPreparedPage()
        }

        onTransitionFinished = {
            stopPageTransition = null
            pageTransitionActive = false
            clearOutgoingPage()
            // Plugin refreshes may arrive during the short entrance motion. Remeasure
            // after it finishes so a changed content height gets its own morph.
            root.requestLayout()
            val nextNavigation = pendingNavigation
            pendingNavigation = null
            val needsRefresh = refreshAfterTransition
            refreshAfterTransition = false
            if (nextNavigation != null || needsRefresh) root.post {
                if (dialog.isShowing && pageHost.isAttachedToWindow) {
                    if (nextNavigation != null) nextNavigation()
                    else renderPage()
                }
            }
        }

        onPluginBoundsChanged = { targetBounds ->
            val startBounds = RectF(panelBounds)
            pageTransitionActive = true
            val stop = pageHost.morphEmbeddedSettingsPage(null, startBounds, targetBounds, onBoundsChanged = { bounds ->
                panelBounds.set(bounds)
                root.invalidate()
            }) { onTransitionFinished?.invoke() }
            if (pageTransitionActive) stopPageTransition = stop
        }

        onViewportChanged = {
            cancelPageReady?.invoke()
            cancelPageReady = null
            stopPageTransition?.invoke()
            stopPageTransition = null
            pageTransitionActive = false
            pageHost.alpha = 1f
            pageHost.translationX = 0f
            pageHost.translationY = 0f
            clearOutgoingPage()
            val nextNavigation = pendingNavigation
            pendingNavigation = null
            val needsRefresh = refreshAfterTransition
            refreshAfterTransition = false
            root.post {
                if (dialog.isShowing && pageHost.isAttachedToWindow) {
                    if (nextNavigation != null) nextNavigation()
                    else if (needsRefresh) renderPage()
                }
            }
        }

        fun navigateBack() {
            val parentPage = when (page) {
                EmbeddedSettingsPage.AUTOMATIC_LYRICS -> EmbeddedSettingsPage.CUSTOM_LYRICS
                EmbeddedSettingsPage.LLM_LYRICS -> EmbeddedSettingsPage.CUSTOM_LYRICS
                EmbeddedSettingsPage.BOTTOM_BAR_ANIMATION -> EmbeddedSettingsPage.INTERFACE
                EmbeddedSettingsPage.MAIN -> {
                    dialog.dismiss()
                    return
                }
                else -> EmbeddedSettingsPage.MAIN
            }
            renderPage(parentPage)
        }
        backButton.setOnClickListener { navigateBack() }
        val backCallback = if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.window.OnBackInvokedCallback { navigateBack() }
        } else null
        val themedContext = embeddedThemedContext(activity, dialog = true)
        dialog = object : AlertDialog(themedContext) {
            override fun onCreate(savedInstanceState: android.os.Bundle?) {
                super.onCreate(savedInstanceState)
                // Match the editor's stable transparent carrier. Page changes
                // only morph the visible panel; WindowManager never recenters it.
                setContentView(root, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                window?.apply {
                    clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                    setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                }
            }
        }
        configureEmbeddedDialogWindow(activity, dialog, themedContext, customPanel = true, animateStandardEntrance = false)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode != android.view.KeyEvent.KEYCODE_BACK || page == EmbeddedSettingsPage.MAIN) false
            else {
                if (event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled) navigateBack()
                true
            }
        }
        dialog.setOnShowListener {
            dialogReady = true
            syncBottomCloseButton()
            root.animateEmbeddedPanelEntrance()
            if (android.os.Build.VERSION.SDK_INT >= 33 && backCallback != null) {
                dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)
            }
        }
        val weakDialog = WeakReference<Dialog>(dialog)
        dialog.setOnDismissListener {
            cancelPagePreparation?.invoke()
            cancelPagePreparation = null
            cancelPageReady?.invoke()
            cancelPageReady = null
            pendingNavigation = null
            refreshAfterTransition = false
            stopPageTransition?.invoke()
            stopPageTransition = null
            pageTransitionActive = false
            clearOutgoingPage()
            disposePage?.invoke()
            disposePage = null
            if (android.os.Build.VERSION.SDK_INT >= 33 && backCallback != null) {
                dialog.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
            }
            if (dialogReference?.get() === weakDialog.get()) {
                dialogReference = null
                pageRefresh = null
            }
        }
        dialogReference = weakDialog
        pageRefresh = { renderPage() }
        renderPage()
        dialog.show()
    }


internal fun EmbeddedSettingsHost.renderEmbeddedMainPage(
    activity: Activity,
    parent: LinearLayout,
    settings: ModuleSettings,
    lyricsCount: Int,
    onOpenPage: (EmbeddedSettingsPage) -> Unit,
) {
    fun state(enabled: Boolean) = localizedText(if (enabled) ModuleText.STATE_ON else ModuleText.STATE_OFF)
    val font = runCatching { controller.currentSettings().fontManifest }.getOrDefault(settings.fontManifest)
    val fontName = if (font.enabled) font.displayName else localizedText(ModuleText.ORIGINAL_FONT)
    val dpi = if (settings.appleMusicDpiOverrideDpi == ModuleSettings.FOLLOW_SYSTEM_APPLE_MUSIC_DPI) {
        localizedText(ModuleText.SUMMARY_DPI_SYSTEM)
    } else settings.appleMusicDpiOverrideDpi.toString()
    val effectCount = listOf(settings.futureBlurEnabled, settings.cjkKaraokeAnimationEnabled,
        settings.lyricEffectsOptimizationEnabled).count { it }

    parent.addView(embeddedCard(activity, null) {
        setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4))
        fun entry(title: ModuleText, summary: String, icon: EmbeddedSvgIcon, onClick: () -> Unit) {
            if (childCount > 0) addView(embeddedSpacer(activity, 1))
            addView(embeddedNavigationRow(
                activity = activity,
                title = localizedText(title),
                summary = summary,
                icon = icon,
                summaryMaxLines = 1,
                onClick = onClick,
            ))
        }
        entry(ModuleText.CATEGORY_LYRICS_MANAGEMENT,
            localizedText(ModuleText.SUMMARY_LYRICS_MANAGEMENT, state(settings.customLyricsEnabled), lyricsCount),
            EmbeddedSvgIcon.CustomLyrics) { onOpenPage(EmbeddedSettingsPage.CUSTOM_LYRICS) }
        entry(ModuleText.CATEGORY_LYRICS_EFFECTS,
            localizedText(ModuleText.SUMMARY_LYRICS_EFFECTS, effectCount, fontName),
            EmbeddedSvgIcon.LyricEffects) { onOpenPage(EmbeddedSettingsPage.LYRICS_EFFECTS) }
        entry(ModuleText.CATEGORY_INTERFACE,
            localizedText(ModuleText.SUMMARY_INTERFACE, state(settings.dualPaneEnabled), state(settings.phoneLiquidGlassEnabled)),
            EmbeddedSvgIcon.TabletDualPane) { onOpenPage(EmbeddedSettingsPage.INTERFACE) }
        entry(ModuleText.CATEGORY_TITLE_REGION,
            localizedText(ModuleText.SUMMARY_TITLE_REGION, state(settings.titleCorrectionEnabled),
                localizedText(settings.titleCorrectionMode.displayText)),
            EmbeddedSvgIcon.TitleMode) { onOpenPage(EmbeddedSettingsPage.TITLE_REGION) }
        entry(ModuleText.ADVANCED_SETTINGS,
            if (nativeBridge.supportsStartupPagePrewarm(activity)) {
                localizedText(ModuleText.SUMMARY_ADVANCED, dpi, state(settings.startupPagePrewarmEnabled))
            } else "DPI · $dpi",
            EmbeddedSvgIcon.Settings) { onOpenPage(EmbeddedSettingsPage.ADVANCED) }
        entry(ModuleText.PLUGINS,
            localizedText(if (plugins == null) ModuleText.PLUGIN_RUNTIME_UNAVAILABLE else ModuleText.SUMMARY_PLUGINS),
            EmbeddedSvgIcon.Plugins) { onOpenPage(EmbeddedSettingsPage.PLUGINS) }
    }, matchWidthWrapContent())
}

private fun EmbeddedSettingsHost.renderEmbeddedCategoryPage(
    activity: Activity,
    parent: LinearLayout,
    page: EmbeddedSettingsPage,
    currentSettings: () -> ModuleSettings,
    onSettingsChanged: (ModuleSettings) -> Unit,
    onCellularDataEntryChanged: (Boolean) -> Unit,
    onOpenAnimationSettings: () -> Unit,
    onChooseFont: () -> Unit,
    onClearFont: () -> Unit,
) {
    val settings = currentSettings()
    fun LinearLayout.spaceBeforeRow() {
        if (childCount > 0) addView(embeddedSpacer(activity, 1))
    }
    fun LinearLayout.setting(title: ModuleText, summary: ModuleText, checked: Boolean, onChanged: (Boolean) -> Unit) {
        spaceBeforeRow()
        addView(embeddedSettingRow(activity, localizedText(title), localizedText(summary), checked, onChanged = onChanged))
    }

    parent.addView(embeddedCard(activity, null) {
        setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
        when (page) {
            EmbeddedSettingsPage.LYRICS_EFFECTS -> {
                setting(ModuleText.BIDIRECTIONAL_LYRIC_BLUR, ModuleText.BIDIRECTIONAL_LYRIC_BLUR_SUMMARY,
                    settings.futureBlurEnabled) {
                    onSettingsChanged(currentSettings().copy(futureBlurEnabled = it))
                }
                setting(ModuleText.CJK_KARAOKE_ANIMATION, ModuleText.CJK_KARAOKE_ANIMATION_SUMMARY,
                    settings.cjkKaraokeAnimationEnabled) {
                    onSettingsChanged(currentSettings().copy(cjkKaraokeAnimationEnabled = it))
                }
                setting(ModuleText.LYRIC_EFFECTS_OPTIMIZATION, ModuleText.LYRIC_EFFECTS_OPTIMIZATION_SUMMARY,
                    settings.lyricEffectsOptimizationEnabled) {
                    onSettingsChanged(currentSettings().copy(lyricEffectsOptimizationEnabled = it))
                }
                spaceBeforeRow()
                addView(embeddedBlurRadiusRow(activity, settings.lyricBlurRadiusOffsetPx) {
                    onSettingsChanged(currentSettings().copy(lyricBlurRadiusOffsetPx = it))
                })
            }
            EmbeddedSettingsPage.INTERFACE -> {
                setting(ModuleText.TABLET_DUAL_PANE, ModuleText.TABLET_DUAL_PANE_SUMMARY, settings.dualPaneEnabled) {
                    onSettingsChanged(currentSettings().copy(dualPaneEnabled = it))
                    pageRefresh?.invoke()
                }
                if (settings.dualPaneEnabled) {
                    setting(ModuleText.TABLET_DUAL_PANE_HIDE_STATUS_BAR, ModuleText.SHORT_DUAL_PANE_STATUS_SUMMARY,
                        settings.dualPaneHideStatusBarEnabled) {
                        onSettingsChanged(currentSettings().copy(dualPaneHideStatusBarEnabled = it))
                    }
                }
                setting(ModuleText.LIQUID_GLASS_NAVIGATION, ModuleText.SHORT_GLASS_SUMMARY, settings.phoneLiquidGlassEnabled) {
                    onSettingsChanged(currentSettings().copy(phoneLiquidGlassEnabled = it))
                    pageRefresh?.invoke()
                }
                if (settings.phoneLiquidGlassEnabled) {
                    if (nativeBridge.supportsGlassScrollCollapse(activity)) {
                        setting(ModuleText.BOTTOM_BAR_COLLAPSE, ModuleText.BOTTOM_BAR_COLLAPSE_SUMMARY,
                            settings.phoneLiquidGlassCollapseEnabled) {
                            onSettingsChanged(currentSettings().copy(phoneLiquidGlassCollapseEnabled = it))
                            pageRefresh?.invoke()
                        }
                        if (settings.phoneLiquidGlassCollapseEnabled) {
                            spaceBeforeRow()
                            addView(embeddedNavigationRow(activity,
                                localizedText(ModuleText.BOTTOM_BAR_ANIMATION_SETTINGS),
                                localizedText(ModuleText.BOTTOM_BAR_ANIMATION_SETTINGS_SUMMARY),
                                onClick = onOpenAnimationSettings))
                        }
                    }
                    addView(embeddedSpacer(activity, 12))
                    addView(embeddedGlassRangeRow(
                        activity = activity,
                        title = localizedText(ModuleText.BOTTOM_BAR_HEIGHT),
                        value = settings.phoneLiquidGlassBottomGapDp,
                        defaultValue = GlassPolicy.BOTTOM_DP,
                        rangeMin = ModuleSettings.MIN_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP,
                        rangeMax = ModuleSettings.MAX_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP,
                        suffix = localizedText(ModuleText.BOTTOM_BAR_HEIGHT_SUMMARY),
                    ) { onSettingsChanged(currentSettings().copy(phoneLiquidGlassBottomGapDp = it)) })
                    addView(embeddedSpacer(activity, 16))
                    addView(embeddedGlassRangeRow(
                        activity = activity,
                        title = localizedText(ModuleText.BOTTOM_BAR_BLUR),
                        value = settings.phoneLiquidGlassPanelBlurDp,
                        defaultValue = GlassPolicy.PANEL_BLUR_DP.toInt(),
                        rangeMin = ModuleSettings.MIN_PHONE_LIQUID_GLASS_PANEL_BLUR_DP,
                        rangeMax = ModuleSettings.MAX_PHONE_LIQUID_GLASS_PANEL_BLUR_DP,
                        suffix = localizedText(ModuleText.BOTTOM_BAR_BLUR_SUMMARY),
                    ) { onSettingsChanged(currentSettings().copy(phoneLiquidGlassPanelBlurDp = it)) })
                } else {
                    setting(ModuleText.TABLET_BOTTOM_BAR_FIX, ModuleText.TABLET_BOTTOM_BAR_FIX_SUMMARY,
                        settings.navigationCompensationEnabled) {
                        onSettingsChanged(currentSettings().copy(navigationCompensationEnabled = it))
                    }
                }
            }
            EmbeddedSettingsPage.TITLE_REGION -> {
                setting(ModuleText.TITLE_CORRECTION, ModuleText.TITLE_CORRECTION_SUMMARY, settings.titleCorrectionEnabled) {
                    onSettingsChanged(currentSettings().copy(titleCorrectionEnabled = it))
                }
                spaceBeforeRow()
                addView(embeddedNavigationRow(activity,
                    localizedText(ModuleText.TITLE_CORRECTION_MODE),
                    localizedText(ModuleText.VALUE_RESTART_REQUIRED, localizedText(settings.titleCorrectionMode.displayText)),
                ) {
                    showEmbeddedTitleCorrectionModePicker(activity) { mode ->
                        onSettingsChanged(currentSettings().copy(titleCorrectionMode = mode))
                        pageRefresh?.invoke()
                    }
                })
            }
            EmbeddedSettingsPage.ADVANCED -> {
                if (nativeBridge.supportsStartupPagePrewarm(activity)) {
                    setting(ModuleText.STARTUP_PAGE_PREWARM, ModuleText.STARTUP_PAGE_PREWARM_SUMMARY,
                        settings.startupPagePrewarmEnabled) {
                        onSettingsChanged(currentSettings().copy(startupPagePrewarmEnabled = it))
                    }
                }
                setting(ModuleText.CELLULAR_SETTINGS, ModuleText.CELLULAR_SETTINGS_SUMMARY,
                    settings.forceCellularDataEntryEnabled) {
                    onCellularDataEntryChanged(it)
                    pageRefresh?.invoke()
                }
                spaceBeforeRow()
                addView(embeddedDpiOverrideRow(activity, settings.appleMusicDpiOverrideDpi) {
                    onSettingsChanged(currentSettings().copy(appleMusicDpiOverrideDpi = it))
                    pageRefresh?.invoke()
                })
            }
            else -> Unit
        }
    }, matchWidthWrapContent())
    if (page == EmbeddedSettingsPage.LYRICS_EFFECTS) {
        parent.addView(embeddedSpacer(activity, 12))
        val font = runCatching { controller.currentSettings().fontManifest }.getOrDefault(settings.fontManifest)
        parent.addView(embeddedFontCard(activity, font, onChooseFont, onClearFont), matchWidthWrapContent())
    }
}

private fun EmbeddedSettingsHost.showEmbeddedPageDetails(activity: Activity, page: EmbeddedSettingsPage) {
    val entries = when (page) {
        EmbeddedSettingsPage.MAIN -> emptyList()
        EmbeddedSettingsPage.CUSTOM_LYRICS -> listOf(
            ModuleText.CUSTOM_LYRICS_REPLACEMENT to ModuleText.CUSTOM_LYRICS_REPLACEMENT_SUMMARY,
            ModuleText.AUTO_LYRICS to ModuleText.AUTO_LYRICS_SUMMARY,
            ModuleText.LLM_LYRICS_FALLBACK to ModuleText.SHORT_LLM_LYRICS_SUMMARY,
        )
        EmbeddedSettingsPage.AUTOMATIC_LYRICS -> listOf(
            ModuleText.AUTO_LYRICS_ENABLED to ModuleText.AUTO_LYRICS_SUMMARY,
            ModuleText.AUTO_LYRICS_PREFER_NATIVE to ModuleText.AUTO_LYRICS_PREFER_NATIVE_SUMMARY,
            ModuleText.AUTO_LYRICS_SOURCE_ORDER to
                if (controller.currentSettings().automaticLyricsPreferNative) {
                    ModuleText.AUTO_LYRICS_SOURCE_ORDER_NATIVE_HIDDEN
                } else ModuleText.AUTO_LYRICS_SOURCE_ORDER_ALL,
        )
        EmbeddedSettingsPage.LYRICS_EFFECTS -> listOf(
            ModuleText.BIDIRECTIONAL_LYRIC_BLUR to ModuleText.BIDIRECTIONAL_LYRIC_BLUR_SUMMARY,
            ModuleText.CJK_KARAOKE_ANIMATION to ModuleText.CJK_KARAOKE_ANIMATION_SUMMARY,
            ModuleText.LYRIC_EFFECTS_OPTIMIZATION to ModuleText.LYRIC_EFFECTS_OPTIMIZATION_SUMMARY,
            ModuleText.LYRICS_FONT to ModuleText.LYRICS_FONT_IMPORT_SUMMARY,
        )
        EmbeddedSettingsPage.INTERFACE -> listOf(
            ModuleText.TABLET_DUAL_PANE to ModuleText.TABLET_DUAL_PANE_SUMMARY,
            ModuleText.TABLET_DUAL_PANE_HIDE_STATUS_BAR to ModuleText.TABLET_DUAL_PANE_HIDE_STATUS_BAR_SUMMARY,
            ModuleText.LIQUID_GLASS_NAVIGATION to ModuleText.LIQUID_GLASS_NAVIGATION_SUMMARY,
            ModuleText.TABLET_BOTTOM_BAR_FIX to ModuleText.TABLET_BOTTOM_BAR_FIX_SUMMARY,
            ModuleText.BOTTOM_BAR_COLLAPSE to ModuleText.BOTTOM_BAR_COLLAPSE_SUMMARY,
            ModuleText.BOTTOM_BAR_ANIMATION_SETTINGS to ModuleText.BOTTOM_BAR_ANIMATION_SETTINGS_SUMMARY,
        )
        EmbeddedSettingsPage.TITLE_REGION -> listOf(
            ModuleText.TITLE_CORRECTION to ModuleText.TITLE_CORRECTION_SUMMARY,
        )
        EmbeddedSettingsPage.ADVANCED -> listOf(
            ModuleText.STARTUP_PAGE_PREWARM to ModuleText.STARTUP_PAGE_PREWARM_SUMMARY,
            ModuleText.CELLULAR_SETTINGS to ModuleText.CELLULAR_SETTINGS_SUMMARY,
            ModuleText.MUSIC_DPI to ModuleText.MUSIC_DPI_NOTICE,
        )
        EmbeddedSettingsPage.PLUGINS -> listOf(ModuleText.PLUGINS to ModuleText.PLUGIN_RESTART_NOTICE)
        EmbeddedSettingsPage.LLM_LYRICS -> listOf(
            ModuleText.LLM_LYRICS_ENABLED to ModuleText.LLM_LYRICS_ENABLED_SUMMARY,
            ModuleText.LLM_LYRICS_BASE_URL to ModuleText.LLM_LYRICS_BASE_URL_SUMMARY,
            ModuleText.LLM_LYRICS_API_KEY to ModuleText.LLM_LYRICS_API_KEY_SUMMARY,
            ModuleText.LLM_LYRICS_MODEL to ModuleText.LLM_LYRICS_MODEL_SUMMARY,
            ModuleText.LLM_LYRICS_THINKING to ModuleText.LLM_LYRICS_THINKING_SUMMARY,
        )
        EmbeddedSettingsPage.BOTTOM_BAR_ANIMATION -> listOf(
            ModuleText.BOTTOM_BAR_COLLAPSE_REBOUND to ModuleText.BOTTOM_BAR_COLLAPSE_REBOUND_SUMMARY,
            ModuleText.BOTTOM_BAR_MINI_REBOUND to ModuleText.BOTTOM_BAR_MINI_REBOUND_SUMMARY,
            ModuleText.BOTTOM_BAR_MINI_SINK_BOUNCE to ModuleText.BOTTOM_BAR_MINI_SINK_BOUNCE_SUMMARY,
            ModuleText.BOTTOM_BAR_EXPAND_REBOUND to ModuleText.BOTTOM_BAR_EXPAND_REBOUND_SUMMARY,
        )
    }
    val message = if (page == EmbeddedSettingsPage.MAIN) {
        listOf(ModuleText.PRIVATE_STORAGE_NOTICE, ModuleText.RESTART_NOTICE,
            ModuleText.LSPOSED_HELP_SCOPE, ModuleText.LSPOSED_HELP_RESTART).joinToString("\n\n") { localizedText(it) }
    } else {
        entries.joinToString("\n\n") { (title, summary) -> "${localizedText(title)}\n${localizedText(summary)}" } +
            "\n\n" + localizedText(ModuleText.RESTART_NOTICE)
    }
    embeddedDialogBuilder(activity)
        .setTitle(localizedText(if (page == EmbeddedSettingsPage.MAIN) ModuleText.SETTINGS_HELP else ModuleText.SETTINGS_DETAILS))
        .setMessage(message)
        .setPositiveButton(localizedText(ModuleText.GOT_IT), null)
        .show()
}


internal fun EmbeddedSettingsHost.renderEmbeddedCustomLyricsPage(
        activity: Activity,
        parent: LinearLayout,
        settings: ModuleSettings,
        song: CurrentSongDetails?,
        onSettingsChanged: (ModuleSettings) -> Unit,
        onOpenLlmLyrics: () -> Unit,
        onOpenAutomaticLyrics: () -> Unit,
    ) {
        val entries = runCatching { controller.lyricsEntries() }.getOrDefault(emptyList())
        customLyricsListState.update(entries, customLyricsSearchQuery)

        parent.addView(embeddedSettingRow(
            activity,
            localizedText(ModuleText.CUSTOM_LYRICS_REPLACEMENT),
            localizedText(ModuleText.CUSTOM_LYRICS_REPLACEMENT_SUMMARY),
            settings.customLyricsEnabled,
            compactWidePadding = true,
        ) {
            onSettingsChanged(settings.copy(customLyricsEnabled = it))
            // Re-render so the dependent automatic-lyrics switch changes its
            // enabled state without leaving the custom-lyrics page.
            pageRefresh?.invoke()
        })
        parent.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
        parent.addView(embeddedNavigationRow(
            activity = activity,
            title = localizedText(ModuleText.AUTO_LYRICS),
            summary = localizedText(ModuleText.SHORT_AUTO_LYRICS_SUMMARY),
            compactWidePadding = true,
            chevronTint = EmbeddedSettingsPalette.Light.accent,
            onClick = onOpenAutomaticLyrics,
        ))
        parent.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
        parent.addView(embeddedNavigationRow(
            activity = activity,
            title = localizedText(ModuleText.LLM_LYRICS_FALLBACK),
            summary = localizedText(ModuleText.SHORT_LLM_LYRICS_SUMMARY),
            compactWidePadding = true,
            chevronTint = EmbeddedSettingsPalette.Light.accent,
            onClick = onOpenLlmLyrics,
        ))
        parent.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))

        val lyricsContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false

            addView(
                embeddedCompactLyricsActionBar(
                    activity = activity,
                    onAdd = { showLyricsEditor(activity, null as CustomLyricsUiGroup?, song) },
                    onTtml = {
                        launchSafPicker(
                            activity,
                            EmbeddedSafOperation.Ttml,
                            "application/xml",
                            arrayOf("application/ttml+xml", "application/xml", "text/xml", "text/plain"),
                        )
                    },
                    onUpdate = { updateEmbeddedLyrics(activity) },
                    onBackup = { anchor -> showEmbeddedBackupRestoreMenu(activity, anchor) },
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(activity, 76),
                ).apply {
                    bottomMargin = dp(activity, if (isEmbeddedPhone(activity)) 12 else 14)
                },
            )

            val search = EditText(activity).apply {

                bindEmbeddedInputTheme(activity)
                hint = localizedText(ModuleText.SEARCH_LYRICS)
                textSize = 14f
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                showSoftInputOnFocus = true
                isSingleLine = true
                includeFontPadding = false
                setText(customLyricsSearchQuery)
                setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
                setEmbeddedTextColor(activity) { colors -> colors.onSurface }
                setEmbeddedHintColor(activity) { colors -> colors.muted }
                background = null
            }
            addView(FrameLayout(activity).apply {
                bindEmbeddedTheme(activity) { colors ->
                    background = EmbeddedG3Drawable().apply {
                        setColor(colors.raised)
                        setStroke(dp(activity, 1), colors.outline)
                        cornerRadius = dp(activity, 12).toFloat()
                    }
                }
                addView(ImageView(activity).apply {
                    setEmbeddedImageDrawable(activity,
                        EmbeddedGlyphDrawable(
                            EmbeddedGlyphKind.Search,
                            EmbeddedSettingsPalette.Light.onSurfaceVariant,
                        ),
                    )
                    contentDescription = null
                    setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 8), dp(activity, 12))
                }, FrameLayout.LayoutParams(embeddedSearchFieldHeight(activity), embeddedSearchFieldHeight(activity)))
                search.setPadding(embeddedSearchFieldHeight(activity), 0, dp(activity, 12), 0)
                addView(search, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    embeddedSearchFieldHeight(activity),
                ))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                embeddedSearchFieldHeight(activity),
            ).apply {
                topMargin = dp(activity, 4)
                bottomMargin = dp(activity, if (isEmbeddedPhone(activity)) 8 else 16)
            })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(activity, 6))
                addView(TextView(activity).apply {
                    text = localizedText(ModuleText.CONFIGURED)
                    textSize = embeddedTextSize(activity, 14f, 13f)
                    setEmbeddedTextColor(activity) { colors -> colors.text }
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }, LinearLayout.LayoutParams(0, dp(activity, 28), 1f))
                addView(TextView(activity).apply {
                    text = localizedText(ModuleText.SONG_COUNT, entries.size)
                    textSize = embeddedTextSize(activity, 13f, 13f)
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    setEmbeddedTextColor(activity) { colors -> colors.secondary }
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 28)))
            }, matchWidthWrapContent())
            val entriesRegion = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
            }
            addView(entriesRegion, matchWidthWrapContent())

            fun renderEntries() {
                entriesRegion.removeAllViews()
                val state = customLyricsListState
                if (state.totalCount == 0) {
                    entriesRegion.addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(activity, 16), dp(activity, 42), dp(activity, 16), dp(activity, 48))
                        addView(ImageView(activity).apply {
                            setEmbeddedImageDrawable(activity,
                                if (entries.isEmpty()) embeddedSvgDrawable(EmbeddedSvgIcon.CustomLyrics, activity)
                                else EmbeddedGlyphDrawable(
                                    EmbeddedGlyphKind.DocumentSearch,
                                    EmbeddedSettingsPalette.Light.disabledText,
                                ),
                            )
                            contentDescription = null
                            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
                        }, LinearLayout.LayoutParams(dp(activity, 56), dp(activity, 56)))
                        addView(TextView(activity).apply {
                            text = if (entries.isEmpty()) localizedText(ModuleText.NO_CUSTOM_LYRICS) else localizedText(ModuleText.NO_SEARCH_RESULTS)
                            textSize = 15f
                            gravity = Gravity.CENTER
                            setEmbeddedTextColor(activity) { colors -> colors.onSurfaceVariant }
                            setSingleLine(false)
                            setPadding(0, dp(activity, 8), 0, 0)
                        }, matchWidthWrapContent())
                        addView(TextView(activity).apply {
                            text = if (entries.isEmpty()) localizedText(ModuleText.ADD_LYRICS_EMPTY_HINT) else localizedText(ModuleText.SEARCH_LYRICS_EMPTY_HINT)
                            textSize = 12.5f
                            gravity = Gravity.CENTER
                            setEmbeddedTextColor(activity) { colors -> colors.mutedText }
                            setSingleLine(false)
                            setPadding(0, dp(activity, 4), 0, 0)
                        }, matchWidthWrapContent())
                    }, matchWidthWrapContent())
                    return
                }
                val visibleGroups = state.visibleGroups
                visibleGroups.forEachIndexed { index, group ->
                    entriesRegion.addView(embeddedCustomLyricsEntryRow(activity, group, song))
                    if (index < visibleGroups.lastIndex) entriesRegion.addView(View(activity).apply {
                        alpha = 0.35f
                        setEmbeddedBackgroundColor(activity) { colors -> colors.outline }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1)).apply {
                        marginStart = dp(activity, if (isEmbeddedPhone(activity)) 12 else 8)
                        marginEnd = dp(activity, 8)
                    })
                }
                entriesRegion.addView(TextView(activity).apply {
                    text = localizedText(ModuleText.LYRICS_VISIBLE_COUNT, state.visibleCount, state.totalCount)
                    textSize = 13f
                    setEmbeddedTextColor(activity) { colors -> colors.onSurfaceVariant }
                    setPadding(0, dp(activity, 8), 0, dp(activity, 12))
                })
                if (state.hasMore) {
                    entriesRegion.addView(LinearLayout(activity).apply {
                        orientation = embeddedActionOrientation(activity)
                        setPadding(0, 0, 0, dp(activity, 12))
                        addView(embeddedActionButton(activity, localizedText(ModuleText.LOAD_MORE)) {
                            customLyricsListState.loadMore()
                            renderEntries()
                        }, embeddedActionButtonParams(activity))
                    }, matchWidthWrapContent())
                }
            }

            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    customLyricsSearchQuery = s?.toString().orEmpty()
                    customLyricsListState.setQuery(customLyricsSearchQuery)
                    renderEntries()
                }
            })
            renderEntries()
        }
        parent.addView(lyricsContent)
    }


private fun EmbeddedSettingsHost.renderEmbeddedAutomaticLyricsPage(
    activity: Activity,
    parent: LinearLayout,
    currentSettings: () -> ModuleSettings,
    onSettingsChanged: (ModuleSettings) -> Unit,
) {
    val settings = currentSettings()
    val sourceOrder = ModuleSettings.normalizeAutomaticLyricsSourceOrder(settings.automaticLyricsSourceOrder)
    val nativeIndex = sourceOrder.indexOf(CustomLyricsSources.AM_NATIVE)
    val visibleSources = if (settings.automaticLyricsPreferNative) {
        sourceOrder.filterNot { it == CustomLyricsSources.AM_NATIVE }
    } else {
        sourceOrder
    }.toMutableList()

    fun sourceOrderWithNative(sources: List<String>): List<String> {
        val nextSources = sources.toMutableList()
        if (settings.automaticLyricsPreferNative) {
            // Native already has precedence in this mode; its hidden rank must not block a visible source.
            val maximumNativeIndex = nextSources.indexOf(CustomLyricsSources.LUNABEAT)
                .takeIf { it >= 0 } ?: nextSources.size
            nextSources.add(nativeIndex.coerceIn(0, maximumNativeIndex), CustomLyricsSources.AM_NATIVE)
        }
        return nextSources
    }

    fun saveSourceOrder() {
        onSettingsChanged(currentSettings().copy(
            automaticLyricsSourceOrder = ModuleSettings.normalizeAutomaticLyricsSourceOrder(
                sourceOrderWithNative(visibleSources),
            ),
        ))
    }

    val settingsGroup = embeddedCard(activity, null) {
        setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
    }
    parent.addView(settingsGroup, matchWidthWrapContent())
    settingsGroup.addView(embeddedSettingRow(
        activity,
        localizedText(ModuleText.AUTO_LYRICS_ENABLED),
        localizedText(ModuleText.AUTO_LYRICS_SUMMARY),
        settings.automaticLyricsEnabled,
        enabled = settings.customLyricsEnabled,
        compactWidePadding = true,
    ) {
        onSettingsChanged(currentSettings().copy(automaticLyricsEnabled = it))
        pageRefresh?.invoke()
    })
    settingsGroup.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
    settingsGroup.addView(embeddedSettingRow(
        activity,
        localizedText(ModuleText.AUTO_LYRICS_PREFER_NATIVE),
        localizedText(ModuleText.AUTO_LYRICS_PREFER_NATIVE_SUMMARY),
        settings.automaticLyricsPreferNative,
        compactWidePadding = true,
    ) {
        onSettingsChanged(currentSettings().copy(
            automaticLyricsPreferNative = it,
        ))
        pageRefresh?.invoke()
    })

    parent.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
    val sourceContentStart = dp(activity, if (isEmbeddedPhone(activity)) 12 else 8)
    val sourceContentEnd = dp(activity, 8)
    val sourceGroup = embeddedCard(activity, null) {
        setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPaddingRelative(sourceContentStart, 0, sourceContentEnd, 0)
            addView(embeddedSectionLabel(activity, localizedText(ModuleText.AUTO_LYRICS_SOURCE_ORDER)),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(embeddedActionButton(activity, localizedText(ModuleText.RESET)) {
                onSettingsChanged(currentSettings().copy(
                    automaticLyricsSourceOrder = ModuleSettings.DEFAULT_AUTOMATIC_LYRICS_SOURCE_ORDER,
                ))
                pageRefresh?.invoke()
            }.apply {
                minHeight = dp(activity, 44)
                minimumHeight = dp(activity, 44)
                setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 44)).apply {
                marginStart = dp(activity, 8)
            })
        }, matchWidthWrapContent())
        addView(TextView(activity).apply {
            text = localizedText(
                if (settings.automaticLyricsPreferNative) {
                    ModuleText.AUTO_LYRICS_SOURCE_ORDER_NATIVE_HIDDEN
                } else {
                    ModuleText.AUTO_LYRICS_SOURCE_ORDER_ALL
                },
            ) + "\n" + localizedText(ModuleText.DRAG_TO_REORDER) +
                if (settings.automaticLyricsPreferNative) "" else "\n" + localizedText(ModuleText.LUNABEAT_AFTER_NATIVE)
            textSize = 12f
            setEmbeddedTextColor(activity) { colors -> colors.secondary }
            setSingleLine(false)
            setPaddingRelative(sourceContentStart, 0, sourceContentEnd, dp(activity, 8))
        }, matchWidthWrapContent())
    }
    parent.addView(sourceGroup, matchWidthWrapContent())
    lateinit var sourceAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder>
    lateinit var dragHelper: ItemTouchHelper
    var dragStartOrder: List<String>? = null
    var refreshAfterDrop = false

    fun canMoveSource(from: Int, to: Int): Boolean {
        if (from !in visibleSources.indices || to !in visibleSources.indices || from == to) return false
        val nextSources = visibleSources.toMutableList().apply { add(to, removeAt(from)) }
        val fullOrder = sourceOrderWithNative(nextSources)
        // Hidden native can move ahead of Lunabeat without changing the visible order.
        return ModuleSettings.normalizeAutomaticLyricsSourceOrder(fullOrder) == fullOrder
    }

    fun moveSource(from: Int, to: Int): Boolean {
        if (!canMoveSource(from, to)) return false
        visibleSources.add(to, visibleSources.removeAt(from))
        sourceAdapter.notifyItemMoved(from, to)
        return true
    }

    // AndroidX reads its own dimensions and IDs; the host Activity's resources
    // belong to Apple Music, not to the module that owns this RecyclerView.
    val moduleInfo = checkNotNull(dev.amenhancer.module.hook.ModernXposedRuntime.activeModule())
        .moduleApplicationInfo
    val moduleResources = activity.packageManager.getResourcesForApplication(moduleInfo)
    @Suppress("DEPRECATION")
    val listResources = Resources(moduleResources.assets, activity.resources.displayMetrics,
        Configuration(activity.resources.configuration))
    val listTheme = listResources.newTheme().apply {
        applyStyle(moduleInfo.theme.takeIf { it != 0 } ?: android.R.style.Theme_Material_Light_NoActionBar, true)
    }
    val listContext = object : ContextWrapper(activity) {
        override fun getResources(): Resources = listResources
        override fun getAssets() = listResources.assets
        override fun getTheme(): Resources.Theme = listTheme
        override fun getClassLoader(): ClassLoader = RecyclerView::class.java.classLoader!!
    }
    val sourceList = object : RecyclerView(listContext) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            val initialOrder = dragStartOrder
            if (initialOrder != null &&
                (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
            ) {
                dragStartOrder = null
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    visibleSources.clear()
                    visibleSources.addAll(initialOrder)
                    sourceAdapter.notifyDataSetChanged()
                } else if (visibleSources != initialOrder) {
                    // Persist before drop animation or a following control can change this page.
                    refreshAfterDrop = true
                    saveSourceOrder()
                }
            }
            val handled = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                this.parent?.requestDisallowInterceptTouchEvent(false)
            }
            return handled
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            dragHelper.attachToRecyclerView(this)
        }

        override fun onDetachedFromWindow() {
            dragStartOrder?.let { initialOrder ->
                visibleSources.clear()
                visibleSources.addAll(initialOrder)
                sourceAdapter.notifyDataSetChanged()
            }
            dragStartOrder = null
            refreshAfterDrop = false
            dragHelper.attachToRecyclerView(null)
            super.onDetachedFromWindow()
        }
    }.apply {
        layoutManager = LinearLayoutManager(activity)
        isNestedScrollingEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        setPadding(0, dp(activity, 4), 0, 0)
        setHasFixedSize(true)
    }

    sourceAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init { setHasStableIds(true) }

        override fun getItemCount(): Int = visibleSources.size

        override fun getItemId(position: Int): Long =
            ModuleSettings.DEFAULT_AUTOMATIC_LYRICS_SOURCE_ORDER.indexOf(visibleSources[position]).toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(activity, 56)
                setPaddingRelative(sourceContentStart, dp(activity, 4), sourceContentEnd, dp(activity, 4))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(activity, 6) }
                bindEmbeddedTheme(activity) { colors ->
                    background = EmbeddedG3Drawable().apply {
                        setColor(colors.raised)
                        cornerRadius = dp(activity, 12).toFloat()
                    }
                }
                addView(TextView(activity).apply {
                    textSize = 14f
                    setEmbeddedTextColor(activity) { colors -> colors.text }
                    setSingleLine(false)
                    maxLines = 2
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageView(activity).apply {
                    setEmbeddedImageDrawable(activity, EmbeddedSettingsSvgAssets.glyphDrawable(
                        EmbeddedSvgIcon.DragHandle, EmbeddedSettingsPalette.Light.secondary,
                    ))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
                    isFocusable = true
                    tooltipText = localizedText(ModuleText.DRAG_TO_REORDER)
                }, LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)))
            }
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = holder.itemView as LinearLayout
            val label = row.getChildAt(0) as TextView
            val handle = row.getChildAt(1) as ImageView
            val sourceName = embeddedCustomLyricsSourceName(visibleSources[position])
            label.text = sourceName
            handle.contentDescription = localizedText(ModuleText.DRAG_SOURCE, sourceName)
            handle.setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                    holder.bindingAdapterPosition != RecyclerView.NO_POSITION
                ) {
                    sourceList.stopScroll()
                    sourceList.parent?.requestDisallowInterceptTouchEvent(true)
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    dragHelper.startDrag(holder)
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    view.performClick()
                }
                true
            }
            handle.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    val index = holder.bindingAdapterPosition
                    if (canMoveSource(index, index - 1)) info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
                        localizedText(ModuleText.MOVE_UP),
                    ))
                    if (canMoveSource(index, index + 1)) {
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
                            localizedText(ModuleText.MOVE_DOWN),
                        ))
                    }
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    val offset = when (action) {
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id -> -1
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id -> 1
                        else -> return super.performAccessibilityAction(host, action, args)
                    }
                    if (dragStartOrder != null || sourceList.isComputingLayout) return false
                    val index = holder.bindingAdapterPosition
                    if (!moveSource(index, index + offset)) return false
                    saveSourceOrder()
                    pageRefresh?.invoke()
                    return true
                }
            }
        }
    }
    sourceList.adapter = sourceAdapter
    dragHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun isLongPressDragEnabled(): Boolean = false
        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun canDropOver(list: RecyclerView, holder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean =
            canMoveSource(holder.bindingAdapterPosition, target.bindingAdapterPosition)

        override fun onMove(list: RecyclerView, holder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean =
            moveSource(holder.bindingAdapterPosition, target.bindingAdapterPosition)

        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(holder, actionState)
            if (holder != null && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                dragStartOrder = visibleSources.toList()
                refreshAfterDrop = false
                holder.itemView.elevation = dp(activity, 8).toFloat()
                holder.itemView.alpha = 0.96f
            }
        }

        override fun clearView(list: RecyclerView, holder: RecyclerView.ViewHolder) {
            super.clearView(list, holder)
            holder.itemView.elevation = 0f
            holder.itemView.alpha = 1f
            if (refreshAfterDrop) {
                refreshAfterDrop = false
                list.post { if (list.isAttachedToWindow) pageRefresh?.invoke() }
            }
        }
    })
    sourceGroup.addView(sourceList, matchWidthWrapContent())
}


private fun EmbeddedSettingsHost.renderEmbeddedLlmLyricsPage(
    activity: Activity,
    parent: LinearLayout,
    settings: ModuleSettings,
    onSettingsChanged: (ModuleSettings) -> Unit,
) {
    val llmFallbackActive = settings.customLyricsEnabled && settings.automaticLyricsEnabled
    val settingsGroup = embeddedCard(activity, null) {
        setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
    }
    parent.addView(settingsGroup, matchWidthWrapContent())
    settingsGroup.addView(embeddedSettingRow(
        activity,
        localizedText(ModuleText.LLM_LYRICS_ENABLED),
        localizedText(
            if (llmFallbackActive) ModuleText.LLM_LYRICS_ENABLED_SUMMARY
            else ModuleText.LLM_LYRICS_REQUIRES_AUTO,
        ),
        settings.llmLyricsEnabled,
        enabled = llmFallbackActive,
        compactWidePadding = true,
    ) {
        onSettingsChanged(settings.copy(llmLyricsEnabled = it))
        pageRefresh?.invoke()
    })
    settingsGroup.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
    settingsGroup.addView(embeddedTextValueRow(
        activity = activity,
        title = localizedText(ModuleText.LLM_LYRICS_BASE_URL),
        value = settings.llmLyricsBaseUrl,
        emptySummary = localizedText(ModuleText.LLM_LYRICS_NOT_CONFIGURED),
        hint = localizedText(ModuleText.LLM_LYRICS_INPUT_HINT_BASE_URL),
        maxLength = ModuleSettings.MAX_LLM_LYRICS_BASE_URL_LENGTH,
        notice = localizedText(ModuleText.LLM_LYRICS_BASE_URL_SUMMARY),
    ) { value ->
        onSettingsChanged(settings.copy(llmLyricsBaseUrl = value))
        pageRefresh?.invoke()
    })
    settingsGroup.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
    settingsGroup.addView(embeddedTextValueRow(
        activity = activity,
        title = localizedText(ModuleText.LLM_LYRICS_API_KEY),
        value = settings.llmLyricsApiKey,
        emptySummary = localizedText(ModuleText.LLM_LYRICS_NOT_CONFIGURED),
        hint = localizedText(ModuleText.LLM_LYRICS_INPUT_HINT_API_KEY),
        maxLength = ModuleSettings.MAX_LLM_LYRICS_API_KEY_LENGTH,
        notice = localizedText(ModuleText.LLM_LYRICS_API_KEY_SUMMARY),
        password = true,
        masked = true,
    ) { value ->
        onSettingsChanged(settings.copy(llmLyricsApiKey = value))
        pageRefresh?.invoke()
    })
    settingsGroup.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
    settingsGroup.addView(embeddedTextValueRow(
        activity = activity,
        title = localizedText(ModuleText.LLM_LYRICS_MODEL),
        value = settings.llmLyricsModel,
        emptySummary = localizedText(ModuleText.LLM_LYRICS_NOT_CONFIGURED),
        hint = localizedText(ModuleText.LLM_LYRICS_INPUT_HINT_MODEL),
        maxLength = ModuleSettings.MAX_LLM_LYRICS_MODEL_LENGTH,
        notice = localizedText(ModuleText.LLM_LYRICS_MODEL_SUMMARY),
    ) { value ->
        onSettingsChanged(settings.copy(llmLyricsModel = value))
        pageRefresh?.invoke()
    })
    settingsGroup.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
    settingsGroup.addView(embeddedSettingRow(
        activity,
        localizedText(ModuleText.LLM_LYRICS_THINKING),
        localizedText(ModuleText.LLM_LYRICS_THINKING_SUMMARY),
        settings.llmLyricsThinkingEnabled,
        compactWidePadding = true,
    ) {
        onSettingsChanged(settings.copy(llmLyricsThinkingEnabled = it))
        pageRefresh?.invoke()
    })
    settingsGroup.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
    // Page edits replace this config snapshot; detaching the old page cancels its check.
    addLlmConnectionTestButton(activity, settingsGroup, LlmLyricsConfig(
        baseUrl = settings.llmLyricsBaseUrl,
        apiKey = settings.llmLyricsApiKey,
        model = settings.llmLyricsModel,
        thinkingEnabled = settings.llmLyricsThinkingEnabled,
    ))
}


internal fun EmbeddedSettingsHost.embeddedCustomLyricsEntryRow(
        activity: Activity,
        group: CustomLyricsUiGroup,
        song: CurrentSongDetails?,
    ): View = LinearLayout(activity).apply {
        val entry = group.primary
        val contentInset = dp(activity, if (isEmbeddedPhone(activity)) 12 else 8)
        val moreIconSize = dp(activity, 28)
        val moreIconEndSpace = EmbeddedSettingsSvgAssets.endInset(EmbeddedSvgIcon.MoreVertical, moreIconSize)
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        // Match the visible dots' end inset to the text's start inset.
        setPaddingRelative(
            contentInset,
            dp(activity, 10),
            0,
            dp(activity, 10),
        )
        minimumHeight = dp(activity, 68)
        val artist = song
            ?.takeIf { it.appleMusicId == entry.appleMusicId }
            ?.artist
            ?.takeIf(String::isNotBlank)
        val secondary = buildString {
            artist?.let {
                append(it)
                append(" · ")
            }
            append("AM ID: ")
            append(entry.appleMusicId)
            if (group.entries.size > 1) {
                append(localizedText(ModuleText.LYRICS_ID_COUNT, group.entries.size))
            }
        }
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = entry.displayName.ifBlank { entry.appleMusicId.toString() }
                    textSize = 16f
                    setEmbeddedTextColor(activity) { colors -> colors.onSurface }
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setSingleLine(false)
                    maxLines = 2
                }, matchWidthWrapContent())
                addView(TextView(activity).apply {
                    text = secondary
                    textSize = 12f
                    setEmbeddedTextColor(activity) { colors -> colors.onSurfaceVariant }
                    setPadding(0, dp(activity, 4), 0, 0)
                    setSingleLine(false)
                    maxLines = 3
                }, matchWidthWrapContent())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(activity, 4)
            })
            addView(Switch(activity).apply {
                isChecked = group.allEnabled
                contentDescription = localizedText(ModuleText.LYRICS_TOGGLE_ACCESSIBILITY, entry.displayName)
                bindEmbeddedSwitchTheme(activity)
                setOnCheckedChangeListener { _, checked ->
                    runAsync(activity) { controller.setLyricsEnabled(group.appleMusicIds, checked) }
                }
            }, LinearLayout.LayoutParams(dp(activity, if (isEmbeddedPhone(activity)) 56 else 48), dp(activity, 44)))
            addView(object : FrameLayout(activity) {
                override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                    super.onLayout(changed, left, top, right, bottom)
                    val icon = getChildAt(0) ?: return
                    foreground?.setHotspotBounds(icon.left, 0, icon.right, height)
                    drawableHotspotChanged(icon.left + icon.width / 2f, height / 2f)
                }

                override fun drawableHotspotChanged(x: Float, y: Float) {
                    val icon = getChildAt(0)
                    super.drawableHotspotChanged(
                        icon?.let { it.left + it.width / 2f } ?: x,
                        icon?.let { it.top + it.height / 2f } ?: y,
                    )
                }
            }.apply {
                contentDescription = localizedText(ModuleText.MORE_LYRICS_ACTIONS)
                isClickable = true
                isFocusable = true
                clipChildren = false
                addView(ImageView(activity).apply {
                    setEmbeddedImageDrawable(activity,
                        EmbeddedGlyphDrawable(
                            EmbeddedGlyphKind.MoreVertical,
                            EmbeddedSettingsPalette.Light.onSurfaceVariant,
                        ),
                    )
                    scaleType = ImageView.ScaleType.CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(moreIconSize, moreIconSize, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                    marginEnd = contentInset - kotlin.math.round(moreIconEndSpace).toInt()
                })
                // A bounded mask cuts off the ripple near the content edge.
                stateListAnimator = null
                val ripple = RippleDrawable(ColorStateList.valueOf(Color.TRANSPARENT), null, null).apply {
                    radius = dp(activity, 18)
                }
                foreground = ripple
                bindEmbeddedTheme(activity) { colors ->
                    // Recolour the existing ripple without cancelling its release animation.
                    ripple.setColor(ColorStateList.valueOf(if (colors.isDark) 0x24FFFFFF else 0x14000000))
                }
                setOnClickListener { showEmbeddedLyricsOverflowMenu(activity, group, song, this) }
            }, LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)))
        }, matchWidthWrapContent())
    }


internal fun EmbeddedSettingsHost.confirmEmbeddedLyricsDelete(activity: Activity, group: CustomLyricsUiGroup) {
        val entry = group.primary
        embeddedDialogBuilder(activity)
            .setMessage(
                localizedText(ModuleText.DELETE_LYRICS_CONFIRM,
                    entry.displayName.ifBlank { entry.appleMusicId.toString() }, group.entries.size),
            )
            .setNegativeButton(localizedText(ModuleText.CANCEL), null)
            .setPositiveButton(localizedText(ModuleText.DELETE)) { _, _ ->
                runAsync(activity) { controller.deleteLyrics(group.appleMusicIds) }
            }
            .show()
    }


internal fun EmbeddedSettingsHost.showEmbeddedLyricsOverflowMenu(
        activity: Activity,
        group: CustomLyricsUiGroup,
        song: CurrentSongDetails?,
        anchor: View,
    ) {
        showEmbeddedActionMenu(activity, anchor, listOf(
            localizedText(ModuleText.EDIT) to { showLyricsEditor(activity, group, song) },
            localizedText(ModuleText.DELETE) to { confirmEmbeddedLyricsDelete(activity, group) },
            localizedText(ModuleText.COPY_MUSIC_ID) to {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "Apple Music ID",
                        group.appleMusicIds.joinToString(","),
                    ),
                )
                Toast.makeText(activity, localizedText(ModuleText.MUSIC_ID_COPIED), Toast.LENGTH_SHORT).show()
            },
        ), destructiveIndex = 1)
    }


internal fun EmbeddedSettingsHost.embeddedCustomLyricsSourceName(source: String): String = when (source) {
        CustomLyricsSources.AUTO_CACHE -> localizedText(ModuleText.AUTO_CACHE)
        CustomLyricsSources.AMLL -> "AMLL"
        CustomLyricsSources.AM_LYRICS -> localizedText(ModuleText.AM_LYRICS_REPOSITORY)
        CustomLyricsSources.LUNABEAT -> "Lunabeat"
        CustomLyricsSources.AM_NATIVE -> localizedText(ModuleText.AM_NATIVE)
        else -> localizedText(ModuleText.MANUAL_TTML)
    }

private fun EmbeddedSettingsHost.addLlmConnectionTestButton(
    activity: Activity,
    parent: LinearLayout,
    config: LlmLyricsConfig,
) {
    val cancelled = AtomicBoolean(false)
    val running = AtomicBoolean(false)
    val hostReference = WeakReference(this)
    val activityReference = WeakReference(activity)
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    val idleLabel = localizedText(ModuleText.LLM_LYRICS_TEST_CONNECTION)
    lateinit var button: android.widget.Button
    button = embeddedActionButton(activity, idleLabel) {
        if (cancelled.get() || !running.compareAndSet(false, true)) return@embeddedActionButton
        button.isEnabled = false
        button.alpha = 0.55f
        button.text = localizedText(ModuleText.LLM_LYRICS_TEST_RUNNING)
        val buttonReference = WeakReference(button)
        Thread({
            val result = runCatching {
                LlmLyricsClient().testConnection(config) { cancelled.get() }
            }.getOrElse { LlmLyricsClient.ConnectionResult("transport") }
            main.post {
                running.set(false)
                if (cancelled.get()) return@post
                val target = buttonReference.get()?.takeIf { it.isAttachedToWindow } ?: return@post
                val owner = activityReference.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return@post
                val host = hostReference.get() ?: return@post
                if (host.dialogReference?.get()?.isShowing != true) return@post
                target.isEnabled = true
                target.alpha = 1f
                target.text = idleLabel
                if (result.failureReason != "cancelled") {
                    host.embeddedDialogBuilder(owner)
                        .setTitle(idleLabel)
                        .setMessage(host.llmConnectionResultText(result))
                        .setPositiveButton(host.localizedText(ModuleText.CLOSE), null)
                        .show()
                }
            }
        }, "ampp-llm-connection-test").apply { isDaemon = true }.start()
    }
    button.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            cancelled.set(true)
        }
    })
    parent.addView(button, matchWidthWrapContent())
}

private fun EmbeddedSettingsHost.llmConnectionResultText(result: LlmLyricsClient.ConnectionResult): String =
    when (result.failureReason) {
        null -> localizedText(ModuleText.LLM_LYRICS_TEST_SUCCESS)
        "invalid_endpoint" -> localizedText(ModuleText.LLM_LYRICS_TEST_INVALID_ENDPOINT)
        "missing_model" -> localizedText(ModuleText.LLM_LYRICS_TEST_MISSING_MODEL)
        "http_status" -> when (result.httpStatus) {
            401, 403 -> localizedText(ModuleText.LLM_LYRICS_TEST_AUTH_FAILED, result.httpStatus)
            404 -> localizedText(ModuleText.LLM_LYRICS_TEST_NOT_FOUND)
            429 -> localizedText(ModuleText.LLM_LYRICS_TEST_RATE_LIMITED)
            else -> localizedText(ModuleText.LLM_LYRICS_TEST_HTTP_FAILED, result.httpStatus)
        }
        "timeout" -> localizedText(ModuleText.LLM_LYRICS_TEST_TIMEOUT)
        "dns" -> localizedText(ModuleText.LLM_LYRICS_TEST_DNS_FAILED)
        "tls" -> localizedText(ModuleText.LLM_LYRICS_TEST_TLS_FAILED)
        "connect", "network", "connection_unavailable" -> localizedText(ModuleText.LLM_LYRICS_TEST_NETWORK_FAILED)
        "invalid_response", "response_limit" -> localizedText(ModuleText.LLM_LYRICS_TEST_INVALID_RESPONSE)
        else -> localizedText(ModuleText.LLM_LYRICS_TEST_FAILED)
    }

