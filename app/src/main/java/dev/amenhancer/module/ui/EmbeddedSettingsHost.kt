package dev.amenhancer.module.ui

import dev.amenhancer.module.i18n.ModuleText

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.concurrent.Executors

internal class EmbeddedSettingsHost private constructor(
    internal val application: Application,
    internal val controller: EmbeddedSettingsController,
    internal val safRouter: EmbeddedSafResultRouter,
    internal val selectionHandler: EmbeddedSafSelectionHandler,
    internal val activityMatcher: dev.amenhancer.module.hook.SettingsActivityMatcher,
    nativeBridgeFactory: ((Activity)->Unit)->dev.amenhancer.module.hook.SettingsViewBridge,
) : Application.ActivityLifecycleCallbacks, dev.amenhancer.module.hook.SettingsEntryObserver {
    internal val nativeBridge = nativeBridgeFactory(::showSettingsDialog)
    internal val lifecycleState = EmbeddedSettingsLifecycleState()
    internal var activityReference: WeakReference<Activity>? = null
    internal var dialogReference: WeakReference<Dialog>? = null
    internal var pageRefresh: (() -> Unit)? = null
    internal var plugins: dev.amenhancer.plugin.runtime.PluginManager? = null
    internal val auxiliaryDialogs = mutableStateListOf<EmbeddedMiuixDialogEntry>()
    internal var auxiliaryDialogSequence = 0L
    internal val customLyricsListState = CustomLyricsListState()
    internal var customLyricsSearchQuery = ""
    internal var pendingTtmlImport: ((String?) -> Unit)? = null
    internal var pendingSafDialog: WeakReference<Dialog>? = null
    internal var buttonReference: WeakReference<View>? = null
    internal var settingsOptionReference: WeakReference<View>? = null
    internal var activeActivityId: String? = null
    internal var activeActivityRole: EmbeddedHostActivityRole? = null
    internal val nativePreferenceActivityIds = mutableSetOf<String>()
    internal var nativePreferenceFragmentReference: WeakReference<Any>? = null
    internal var observedMainContentActivity: WeakReference<Activity>? = null
    internal var observedMainContentDecor: WeakReference<View>? = null
    internal var mainContentLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    internal val mainHandler = Handler(Looper.getMainLooper())
    internal val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ampp-embedded-settings").apply { isDaemon = true }
    }

    @Volatile
    internal var registered = true

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (registered && activityMatcher.isMainContentActivity(activity)) {
            installMainContentLayoutObserver(activity)
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        if (!registered) return
        refreshEmbeddedTheme(activity)
        val role = activityMatcher.roleFor(activity) ?: return
        val action = lifecycleState.onActivityResumed(
            activityId = activityKey(activity),
            className = activity.javaClass.name,
            role = role,
        )
        if (action == EmbeddedSettingsLifecycleAction.Ignore) return

        val previousActivity = activityReference?.get()
        if (previousActivity !== activity) {
            removeInjectedViews(previousActivity)
            dismissDialog()
        }
        activityReference = WeakReference(activity)
        activeActivityId = activityKey(activity)
        activeActivityRole = role
        when (role) {
            EmbeddedHostActivityRole.Player -> injectButtonIfNeeded(activity)
            EmbeddedHostActivityRole.MainContent -> installMainContentLayoutObserver(activity)
            EmbeddedHostActivityRole.Settings -> injectSettingsOptionIfNeeded(activity)
        }
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        val destroyedActivityId = activityKey(activity)
        val wasCurrent = activeActivityId == destroyedActivityId
        lifecycleState.onActivityDestroyed(destroyedActivityId)
        if (nativePreferenceActivityIds.remove(destroyedActivityId)) {
            nativePreferenceFragmentReference = null
        }
        if (observedMainContentActivity?.get() === activity) {
            removeMainContentLayoutObserver()
        }
        removeInjectedViews(activity)
        if (!wasCurrent) return
        dismissDialog()
        activityReference = null
        activeActivityId = null
        activeActivityRole = null
    }

    /**
     * Call from the embedding Activity result seam. Returning false means the
     * result belongs to the host and must continue through its normal path.
     */
    override fun onActivityResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ): Boolean {
        if (activityReference?.get() !== activity || activeActivityRole == null) return false
        val route = safRouter.route(requestCode, resultCode, data?.dataString)
        if (route == EmbeddedSafRoute.Ignored) return false
        val owner = pendingSafDialog?.get()
        pendingSafDialog = null
        val activeOwner = activeSettingsDialog(activity)
        if (owner == null || owner !== activeOwner) return true
        return when (route) {
            EmbeddedSafRoute.Ignored -> false
            is EmbeddedSafRoute.Canceled -> {
                if (route.operation == EmbeddedSafOperation.EditorTtml) pendingTtmlImport = null
                currentActivity()?.let { activity ->
                    Toast.makeText(activity, localizedText(ModuleText.NO_FILE_SELECTED), Toast.LENGTH_SHORT).show()
                }
                true
            }
            is EmbeddedSafRoute.Selected -> {
                selectionHandler.onSelected(route.operation, Uri.parse(route.uri))
                handleSafSelection(route.operation, Uri.parse(route.uri))
                true
            }
        }
    }

    /**
     * Verified 6.5.1/6.5.2 seam: SettingsFragment is an AndroidX
     * PreferenceFragment hosted by MainContentActivity. This keeps the option
     * inside Apple's native settings list; the View row remains a fallback
     * for future host layouts or when a repacker changes the Preference
     * implementation.
     */
    override fun onSettingsPreferencesReady(fragment: Any, activity: Activity) {
        if (!registered || activity.packageName != application.packageName) return
        refreshEmbeddedTheme(activity)
        val activityId = activityKey(activity)
        val previousActivity = activityReference?.get()
        if (previousActivity !== activity) removeInjectedViews(previousActivity)
        activityReference = WeakReference(activity)
        activeActivityId = activityId
        activeActivityRole = EmbeddedHostActivityRole.Settings
        if (activityMatcher.isMainContentActivity(activity)) {
            installMainContentLayoutObserver(activity)
        }

        val nativePreferenceAdded = runCatching {
            injectNativeSettingsPreference(fragment, activity)
        }.getOrDefault(false)
        if (nativePreferenceAdded) {
            nativePreferenceActivityIds.add(activityId)
            nativePreferenceFragmentReference = WeakReference(fragment)
            removeSettingsOption(activity)
            // The setPreferences seam can run after PreferenceFragmentCompat
            // has already attached its RecyclerView adapter.  Always give
            // that adapter a late refresh so a newly-added native row is
            // reflected in the visible list.
            scheduleNativePreferenceRefresh(activity, fragmentView(fragment))
        } else {
            nativePreferenceActivityIds.remove(activityId)
            nativePreferenceFragmentReference = null
            scheduleSettingsOptionFallback(activity, fragmentView(fragment))
        }
    }

    override fun onSettingsFragmentResumed(fragment: Any, activity: Activity) {
        if (!registered || activity.packageName != application.packageName) return
        refreshEmbeddedTheme(activity)
        val activityId = activityKey(activity)
        lifecycleState.onActivityResumed(
            activityId = activityId,
            className = activity.javaClass.name,
            role = EmbeddedHostActivityRole.Settings,
        )
        val previousActivity = activityReference?.get()
        if (previousActivity !== activity) removeInjectedViews(previousActivity)
        activityReference = WeakReference(activity)
        activeActivityId = activityId
        activeActivityRole = EmbeddedHostActivityRole.Settings
        if (activityMatcher.isMainContentActivity(activity)) {
            installMainContentLayoutObserver(activity)
        }
        removeOverlay(activity)

        val nativePreferenceAdded = runCatching {
            injectNativeSettingsPreference(fragment, activity)
        }.getOrDefault(false)
        if (nativePreferenceAdded) {
            nativePreferenceActivityIds.add(activityId)
            nativePreferenceFragmentReference = WeakReference(fragment)
            removeSettingsOption(activity)
            // Refresh even when the early seam succeeded: on 6.5.1 the
            // adapter may already have been attached when r1() is invoked.
            scheduleNativePreferenceRefresh(activity, fragmentView(fragment))
        } else {
            nativePreferenceActivityIds.remove(activityId)
            nativePreferenceFragmentReference = null
            scheduleSettingsOptionFallback(activity, fragmentView(fragment))
        }
    }

    /**
     * The fixed settings Fragment can rebuild its view without resuming the
     * host Activity. Try the native Preference before the adapter is attached;
     * if that seam is not ready yet, keep a visible list-container fallback.
     */
    override fun onSettingsFragmentViewCreated(fragment: Any, activity: Activity, view: View?) {
        if (!registered || activity.packageName != application.packageName) return
        refreshEmbeddedTheme(activity)
        val activityId = activityKey(activity)
        lifecycleState.onActivityResumed(
            activityId = activityId,
            className = activity.javaClass.name,
            role = EmbeddedHostActivityRole.Settings,
        )
        val previousActivity = activityReference?.get()
        if (previousActivity !== activity) removeInjectedViews(previousActivity)
        activityReference = WeakReference(activity)
        activeActivityId = activityId
        activeActivityRole = EmbeddedHostActivityRole.Settings
        if (activityMatcher.isMainContentActivity(activity)) {
            installMainContentLayoutObserver(activity)
        }
        nativePreferenceActivityIds.remove(activityId)
        nativePreferenceFragmentReference = null
        removeOverlay(activity)
        val nativePreferenceAdded = runCatching {
            injectNativeSettingsPreference(fragment, activity)
        }.getOrDefault(false)
        if (nativePreferenceAdded) {
            nativePreferenceActivityIds.add(activityId)
            nativePreferenceFragmentReference = WeakReference(fragment)
            removeSettingsOption(activity)
            scheduleNativePreferenceRefresh(activity, view as? ViewGroup)
        } else {
            scheduleSettingsOptionFallback(activity, view as? ViewGroup)
        }
        view?.post {
            if (registered && activityReference?.get() === activity) {
                if (
                    nativePreferenceActivityIds.contains(activityId) &&
                    nativePreferenceFragmentReference?.get() != null
                ) {
                    removeSettingsOption(activity)
                }
            }
        }
    }

    internal fun scheduleSettingsOptionFallback(
        activity: Activity,
        preferredRoot: ViewGroup?,
    ) {
        mainHandler.postDelayed({
            if (!registered || activityReference?.get() !== activity) return@postDelayed
            if (!nativePreferenceActivityIds.contains(activityKey(activity))) {
                nativePreferenceActivityIds.remove(activityKey(activity))
                nativePreferenceFragmentReference = null
                injectSettingsOptionIfNeeded(activity, preferredRoot)
            }
        }, NATIVE_PREFERENCE_FALLBACK_DELAY_MS)
    }

    internal fun scheduleNativePreferenceRefresh(activity: Activity, root: ViewGroup?) {
        mainHandler.postDelayed({
            if (registered && activityReference?.get() === activity) {
                refreshNativePreferenceAdapter(root)
            }
        }, NATIVE_PREFERENCE_FALLBACK_DELAY_MS)
    }

    internal fun refreshNativePreferenceAdapter(root: ViewGroup?) = nativeBridge.refreshNativePreferenceAdapter(root)

    fun uninstall() {
        if (!registered) return
        registered = false
        application.unregisterActivityLifecycleCallbacks(this)
        removeMainContentLayoutObserver()
        removeInjectedViews(activityReference?.get())
        dismissDialog()
        activityReference = null
        activeActivityId = null
        activeActivityRole = null
        nativePreferenceActivityIds.clear()
        lifecycleState.clear()
        worker.shutdownNow()
    }

    /**
     * MainContentActivity keeps the same Activity while its settings Fragment
     * is swapped in. Observe decor changes so the View fallback does not rely
     * on a second Activity resume callback.
     */
    internal fun installMainContentLayoutObserver(activity: Activity) {
        if (!nativeBridge.supportsViewFallback) return
        val decor = activity.window?.decorView ?: return
        if (observedMainContentActivity?.get() === activity && mainContentLayoutListener != null) {
            onMainContentLayout(activity)
            return
        }

        removeMainContentLayoutObserver()
        val activityReference = WeakReference(activity)
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            activityReference.get()?.let(::onMainContentLayout)
        }
        runCatching { decor.viewTreeObserver.addOnGlobalLayoutListener(listener) }
            .onFailure { return }
        observedMainContentActivity = WeakReference(activity)
        observedMainContentDecor = WeakReference(decor)
        mainContentLayoutListener = listener
        decor.post { activityReference.get()?.let(::onMainContentLayout) }
    }

    internal fun removeMainContentLayoutObserver() {
        val decor = observedMainContentDecor?.get()
        val listener = mainContentLayoutListener
        if (decor != null && listener != null) {
            runCatching { decor.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
        }
        observedMainContentActivity = null
        observedMainContentDecor = null
        mainContentLayoutListener = null
    }

    internal fun onMainContentLayout(activity: Activity) {
        if (!nativeBridge.supportsViewFallback) return
        if (!registered || activityReference?.get() !== activity) return
        val decor = activity.window?.decorView ?: return
        val activityId = activityKey(activity)
        if (!EmbeddedSettingsTextPolicy.containsSettingsTitle(decor, SETTINGS_OPTION_TAG)) {
            nativePreferenceActivityIds.remove(activityId)
            nativePreferenceFragmentReference = null
            if (activeActivityRole == EmbeddedHostActivityRole.Settings) {
                activeActivityRole = EmbeddedHostActivityRole.MainContent
                removeSettingsOption(activity)
                dismissDialog()
            }
            return
        }

        activeActivityRole = EmbeddedHostActivityRole.Settings
        removeOverlay(activity)
        if (
            nativePreferenceActivityIds.contains(activityId) &&
            nativePreferenceFragmentReference?.get() != null
        ) {
            removeSettingsOption(activity)
        } else {
            nativePreferenceActivityIds.remove(activityId)
            nativePreferenceFragmentReference = null
            injectSettingsOptionIfNeeded(activity)
        }
    }

    internal fun injectButtonIfNeeded(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val existing: View? = content.findViewWithTag<View>(FLOATING_BUTTON_TAG)
        if (existing != null) {
            buttonReference = WeakReference<View>(existing)
            return
        }

        val density = activity.resources.displayMetrics.density
        val button = Button(activity).apply {
            backgroundTintList = null
            tag = FLOATING_BUTTON_TAG
            text = "AM"
            textSize = 12f
            isAllCaps = false
            setEmbeddedTextColor(activity) { it.onPrimary }
            contentDescription = localizedText(ModuleText.OPEN_SETTINGS)
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            bindEmbeddedTheme(activity) { colors ->
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colors.primary)
                }
            }
            elevation = 4f * density
            bindEmbeddedClickFeedback(activity, cornerRadiusDp = 28)
            setOnClickListener { showSettingsDialog(activity) }
        }
        val size = (56f * density).toInt()
        val margin = (16f * density).toInt()
        val layoutParams = if (content is FrameLayout) {
            FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                setMargins(margin, margin, margin, margin)
            }
        } else {
            ViewGroup.LayoutParams(size, size)
        }
        content.addView(button, layoutParams)
        buttonReference = WeakReference<View>(button)
    }

    internal fun injectSettingsOptionIfNeeded(activity: Activity, preferredRoot: ViewGroup? = null) {
        if (!nativeBridge.supportsViewFallback) { removeSettingsOption(activity); return }
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val decor = activity.window?.decorView as? ViewGroup
        if (content == null && decor == null) return
        val existing = deduplicateTaggedSettingsOptions(activity)
        if (existing != null) {
            settingsOptionReference = WeakReference(existing)
            return
        }

        val option = LinearLayout(activity).apply {
            tag = SETTINGS_OPTION_TAG
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = dp(activity, 64)
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 12))
            setBackgroundColor(Color.TRANSPARENT)
            bindEmbeddedClickFeedback(activity, cornerRadiusDp = 12)
            contentDescription = localizedText(ModuleText.OPEN_MODULE_SETTINGS)
            setOnClickListener { showSettingsDialog(activity) }
            addView(TextView(activity).apply {
                text = localizedText(ModuleText.MODULE_SETTINGS)
                textSize = 16f
                setEmbeddedTextColor(activity) { colors -> colors.onSurface }
                setSingleLine(false)
            }, matchWidthWrapContent())
            addView(TextView(activity).apply {
                text = localizedText(ModuleText.MODULE_SETTINGS_SUMMARY)
                textSize = 13f
                setEmbeddedTextColor(activity) { colors -> colors.onSurfaceVariant }
                setSingleLine(false)
            }, matchWidthWrapContent())
        }

        val container = preferredRoot?.let(::findSettingsListOverlayContainer)
            ?: decor?.let(::findSettingsListOverlayContainer)
            ?: content?.let(::findSettingsInsertionContainer)
        if (container != null) {
            val layoutParams: ViewGroup.LayoutParams = if (container is FrameLayout) {
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.BOTTOM
                    setMargins(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8))
                }
            } else {
                matchWidthWrapContent()
            }
            runCatching { container.addView(option, layoutParams) }
                .onSuccess {
                    settingsOptionReference = WeakReference<View>(option)
                }
            return
        }

        // A RecyclerView cannot accept arbitrary children. Keep a visible,
        // non-invasive fallback in the host content frame for such layouts.
        val fallbackRoot = when {
            content is FrameLayout -> content
            decor is FrameLayout -> decor
            else -> null
        }
        if (fallbackRoot != null) {
            val layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM
                setMargins(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
            }
            runCatching { fallbackRoot.addView(option, layoutParams) }
                .onSuccess {
                    settingsOptionReference = WeakReference<View>(option)
                }
        }
    }

    /**
     * Fallback rows can be requested by more than one fragment lifecycle
     * callback. Walk the host tree once, keep the first row in visual order,
     * and remove every later copy before another row is created.
     */
    internal fun deduplicateTaggedSettingsOptions(activity: Activity): View? {
        val matches = findTaggedViews(activity, SETTINGS_OPTION_TAG)
        val keeper = matches.firstOrNull()
        matches.drop(1).forEach { duplicate ->
            (duplicate.parent as? ViewGroup)?.removeView(duplicate)
        }
        if (keeper != null) settingsOptionReference = WeakReference(keeper)
        return keeper
    }

    internal fun fragmentView(fragment: Any): ViewGroup? = nativeBridge.fragmentView(fragment)
    internal fun findSettingsListOverlayContainer(root: View): ViewGroup? = nativeBridge.findSettingsListOverlayContainer(root)
    internal fun injectNativeSettingsPreference(fragment: Any, activity: Activity): Boolean = nativeBridge.injectNativeSettingsPreference(fragment,activity)
    internal fun findSettingsInsertionContainer(root: ViewGroup): ViewGroup? = nativeBridge.findSettingsInsertionContainer(root)

    internal fun removeOverlay(activity: Activity?) {
        val button = buttonReference?.get()
        if (button != null && (activity == null || belongsToActivity(button, activity))) {
            (button.parent as? ViewGroup)?.removeView(button)
            buttonReference = null
        } else if (button?.parent == null) {
            buttonReference = null
        }
        if (activity != null) {
            findTaggedView(activity, FLOATING_BUTTON_TAG)?.let { tagged ->
                (tagged.parent as? ViewGroup)?.removeView(tagged)
                if (buttonReference?.get() === tagged) buttonReference = null
            }
        }
    }

    internal fun removeSettingsOption(activity: Activity?) {
        val option = settingsOptionReference?.get()
        if (option != null && (activity == null || belongsToActivity(option, activity))) {
            (option.parent as? ViewGroup)?.removeView(option)
            settingsOptionReference = null
        } else if (option?.parent == null) {
            settingsOptionReference = null
        }
        if (activity != null) {
            findTaggedViews(activity, SETTINGS_OPTION_TAG).forEach { tagged ->
                (tagged.parent as? ViewGroup)?.removeView(tagged)
                if (settingsOptionReference?.get() === tagged) settingsOptionReference = null
            }
        }
    }

    internal fun removeInjectedViews(activity: Activity?) {
        removeOverlay(activity)
        removeSettingsOption(activity)
    }

    internal fun dismissDialog() {
        val dialog = dialogReference?.get()
        dialogReference = null
        pageRefresh = null
        dismissAuxiliarySettingsDialogs()
        dialog?.dismiss()
    }

    internal fun dismissAuxiliarySettingsDialogs() {
        pendingTtmlImport = null
        pendingSafDialog = null
        auxiliaryDialogs.toList().asReversed().forEach { entry ->
            requestEmbeddedMiuixDialogDismiss(entry)
            releaseEmbeddedMiuixDialog(entry)
        }
    }

    internal fun activeSettingsDialog(activity: Activity): Dialog? {
        if (!registered || activity.isFinishing || activity.isDestroyed) return null
        if (activityReference?.get() !== activity) return null
        return dialogReference?.get()?.takeIf {
            it.isShowing && it.ownerActivity === activity
        }
    }

    internal fun requestEmbeddedMiuixDialogDismiss(entry: EmbeddedMiuixDialogEntry) {
        if (entry.released || entry.closeRequested) return
        entry.closeRequested = true
        entry.visible.value = false
        if (!entry.contentShown) releaseEmbeddedMiuixDialog(entry)
    }

    internal fun markEmbeddedMiuixDialogContentShown(entry: EmbeddedMiuixDialogEntry) {
        if (!entry.released) entry.contentShown = true
    }

    internal fun releaseEmbeddedMiuixDialog(entry: EmbeddedMiuixDialogEntry) {
        if (entry.released) return
        entry.released = true
        entry.closeRequested = true
        entry.visible.value = false
        auxiliaryDialogs.removeAll { it === entry }
        entry.onDismiss?.invoke()
    }

    internal fun currentActivity(): Activity? = activityReference?.get()

    internal fun findTaggedView(activity: Activity, tag: String): View? {
        return findTaggedViews(activity, tag).firstOrNull()
    }

    internal fun findTaggedViews(activity: Activity, tag: String): List<View> {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val decor = activity.window?.decorView as? ViewGroup
        val pending = ArrayDeque<View>()
        // decorView contains android.R.id.content on normal Activities. Use
        // one root only so the same tagged row is never visited twice.
        (content?.let(::listOf) ?: listOfNotNull(decor)).forEach(pending::addLast)
        val matches = ArrayList<View>()
        var visited = 0
        while (pending.isNotEmpty() && visited++ < MAX_TAGGED_VIEW_SCAN) {
            val view = pending.removeFirst()
            if (view.tag == tag) matches += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    pending.addLast(view.getChildAt(index))
                }
            }
        }
        return matches
    }

    internal fun belongsToActivity(view: View, activity: Activity): Boolean {
        val decor = activity.window?.decorView ?: return false
        var current: View? = view
        while (current != null) {
            if (current === decor) return true
            current = current.parent as? View
        }
        return false
    }

    /** Draws user-supplied SVG paths without consulting Apple Music's package visibility. */
    internal fun activityKey(activity: Activity): String =
        Integer.toHexString(System.identityHashCode(activity))

    companion object {
        const val PLAYER_ACTIVITY_NAME = "com.apple.android.music.common.activity.PlayerActivity"
        const val FLOATING_BUTTON_TAG = "ampp_embedded_settings_button"
        const val SETTINGS_OPTION_TAG = "ampp_embedded_settings_option"
        internal const val NATIVE_PREFERENCE_FALLBACK_DELAY_MS = 220L
        internal const val MAX_TAGGED_VIEW_SCAN = 4096

        fun install(
            application: Application,
            controller: EmbeddedSettingsController,
            safRouter: EmbeddedSafResultRouter = EmbeddedSafResultRouter(),
            selectionHandler: EmbeddedSafSelectionHandler = EmbeddedSafSelectionHandler { _, _ -> },
            activityMatcher: dev.amenhancer.module.hook.SettingsActivityMatcher,
            nativeBridgeFactory: ((Activity)->Unit)->dev.amenhancer.module.hook.SettingsViewBridge,
        ): EmbeddedSettingsHost = EmbeddedSettingsHost(
            application = application,
            controller = controller,
            safRouter = safRouter,
            selectionHandler = selectionHandler,
            activityMatcher = activityMatcher,
            nativeBridgeFactory = nativeBridgeFactory,
        ).also(application::registerActivityLifecycleCallbacks)
    }
}
