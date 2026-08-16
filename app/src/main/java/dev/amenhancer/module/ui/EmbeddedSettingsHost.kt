package dev.amenhancer.module.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.amenhancer.module.LibraryRefreshProtocol
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.CatalogLanguagePolicy
import dev.amenhancer.module.config.EmbeddedConfigurationSession
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.hook.AmLyricsClient
import dev.amenhancer.module.hook.AmllTtmlClient
import dev.amenhancer.module.hook.HttpLyricTransport
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.module.hook.NeteaseLyricClient
import dev.amenhancer.module.lyrics.CustomLyricsDraft
import dev.amenhancer.module.lyrics.CustomLyricsMultiIdDraft
import dev.amenhancer.module.lyrics.CustomLyricsOnlineImportResult
import dev.amenhancer.module.lyrics.CustomLyricsOnlineImporter
import dev.amenhancer.module.lyrics.CustomLyricsRestorePolicy
import dev.amenhancer.module.lyrics.CustomLyricsSyncProgress
import dev.amenhancer.module.lyrics.CustomLyricsSyncResult
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal enum class EmbeddedHostActivityRole {
    Player,
    MainContent,
    Settings,
}

internal enum class EmbeddedSettingsPage {
    MAIN,
    CUSTOM_LYRICS,
}

private val EMBEDDED_FONT_MIME_TYPES = arrayOf(
    "font/ttf",
    "font/otf",
    "application/x-font-ttf",
    "application/x-font-opentype",
    "application/vnd.ms-opentype",
)

/** AMTool-compatible language choices shared by the standalone settings UI. */
private val EMBEDDED_CATALOG_LANGUAGE_TAGS = listOf(
    "zh-CN",
    "zh-TW",
    "ja-JP",
    "en-US",
    "tr-TR",
)

/** Stable, locale-aware signals for the fixed Apple Music settings surface. */
internal object EmbeddedSettingsTextPolicy {
    private val classNameMarkers = listOf(
        "settings",
        "setting",
        "preferences",
        "preference",
        "accountsettings",
    )
    private val titleMarkers = listOf(
        "settings",
        "preference",
        "设置",
        "通用",
    )

    fun isSettingsClassName(className: String): Boolean {
        val normalized = className.lowercase(Locale.ROOT)
        return classNameMarkers.any(normalized::contains)
    }

    fun isSettingsTitle(text: CharSequence?): Boolean {
        val normalized = text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normalized.isBlank()) return false
        return titleMarkers.any(normalized::contains)
    }

    fun containsSettingsTitle(root: View, ignoredTag: Any? = null): Boolean {
        val pending = ArrayDeque<View>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < MAX_VIEW_SCAN_NODES) {
            val view = pending.removeFirst()
            if (ignoredTag != null && view.tag == ignoredTag) continue
            if (view.visibility != View.VISIBLE || view.alpha <= 0f) continue
            if (view is TextView && isSettingsTitle(view.text)) return true
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    pending.addLast(view.getChildAt(index))
                }
            }
        }
        return false
    }

    private const val MAX_VIEW_SCAN_NODES = 1024
}

/** Matches the fixed PlayerActivity across subclasses and class-loader copies. */
internal class EmbeddedActivityMatcher(
    private val playerActivityClass: Class<*>? = null,
    private val playerActivityName: String = EmbeddedSettingsHost.PLAYER_ACTIVITY_NAME,
) {
    fun roleFor(activity: Activity): EmbeddedHostActivityRole? {
        if (activity.packageName != ModuleConstants.TARGET_PACKAGE) return null
        if (isMainContentActivity(activity)) return EmbeddedHostActivityRole.MainContent
        if (isPlayerActivity(activity)) return EmbeddedHostActivityRole.Player
        if (EmbeddedSettingsTextPolicy.isSettingsClassName(activity.javaClass.name)) {
            return EmbeddedHostActivityRole.Settings
        }
        val decor = activity.window?.decorView
            ?: activity.findViewById<View>(android.R.id.content)
            ?: return null
        return if (EmbeddedSettingsTextPolicy.containsSettingsTitle(decor)) {
            EmbeddedHostActivityRole.Settings
        } else {
            null
        }
    }

    fun isPlayerActivity(activity: Activity): Boolean {
        if (playerActivityClass?.isAssignableFrom(activity.javaClass) == true) return true
        var current: Class<*>? = activity.javaClass
        while (current != null) {
            if (current.name == playerActivityName) return true
            current = current.superclass
        }
        return false
    }

    fun isMainContentActivity(activity: Activity): Boolean {
        var current: Class<*>? = activity.javaClass
        while (current != null) {
            if (current.name == EmbeddedSettingsHost.MAIN_CONTENT_ACTIVITY_NAME) return true
            current = current.superclass
        }
        return false
    }
}

/** Pure lifecycle decisions used by the embedded host and its JVM tests. */
internal enum class EmbeddedSettingsLifecycleAction {
    Ignore,
    Inject,
    AlreadyInjected,
}

/**
 * Tracks only opaque activity identities. It deliberately does not retain
 * Activity instances, views, or dialogs.
 */
internal class EmbeddedSettingsLifecycleState(
    private val targetActivityName: String = EmbeddedSettingsHost.PLAYER_ACTIVITY_NAME,
) {
    private val injectedActivityIds = mutableSetOf<String>()

    fun onActivityResumed(
        activityId: String,
        className: String,
        role: EmbeddedHostActivityRole? = null,
    ): EmbeddedSettingsLifecycleAction {
        val resolvedRole = role ?: when {
            className == targetActivityName -> EmbeddedHostActivityRole.Player
            EmbeddedSettingsTextPolicy.isSettingsClassName(className) -> EmbeddedHostActivityRole.Settings
            else -> null
        }
        if (resolvedRole == null) return EmbeddedSettingsLifecycleAction.Ignore
        return if (injectedActivityIds.add(activityId)) {
            EmbeddedSettingsLifecycleAction.Inject
        } else {
            EmbeddedSettingsLifecycleAction.AlreadyInjected
        }
    }

    fun onActivityDestroyed(activityId: String): Boolean = injectedActivityIds.remove(activityId)

    fun clear() {
        injectedActivityIds.clear()
    }
}

internal enum class EmbeddedSafOperation {
    Font,
    Ttml,
    Backup,
    RestoreOverwrite,
    RestoreKeepExisting,
}

internal data class EmbeddedSafPending(
    val requestCode: Int,
    val operation: EmbeddedSafOperation,
)

internal object EmbeddedSafResult {
    const val RESULT_CANCELED = 0
    const val RESULT_OK = -1
}

internal sealed interface EmbeddedSafRoute {
    data object Ignored : EmbeddedSafRoute

    data class Canceled(
        val operation: EmbeddedSafOperation,
    ) : EmbeddedSafRoute

    data class Selected(
        val operation: EmbeddedSafOperation,
        val uri: String,
    ) : EmbeddedSafRoute
}

/**
 * Owns only the module's pending SAF request. A result for any other request
 * code is ignored and leaves the pending request untouched for the host.
 */
internal class EmbeddedSafResultRouter {
    private var pendingRequest: EmbeddedSafPending? = null

    fun begin(operation: EmbeddedSafOperation): Int {
        val requestCode = when (operation) {
            EmbeddedSafOperation.Font -> REQUEST_PICK_FONT
            EmbeddedSafOperation.Ttml -> REQUEST_PICK_TTML
            EmbeddedSafOperation.Backup -> REQUEST_CREATE_BACKUP
            EmbeddedSafOperation.RestoreOverwrite -> REQUEST_RESTORE_BACKUP
            EmbeddedSafOperation.RestoreKeepExisting -> REQUEST_RESTORE_BACKUP_KEEP
        }
        pendingRequest = EmbeddedSafPending(requestCode, operation)
        return requestCode
    }

    fun pending(): EmbeddedSafPending? = pendingRequest

    fun route(
        requestCode: Int,
        resultCode: Int,
        uri: String?,
    ): EmbeddedSafRoute {
        val pending = pendingRequest ?: return EmbeddedSafRoute.Ignored
        if (pending.requestCode != requestCode) return EmbeddedSafRoute.Ignored

        pendingRequest = null
        return if (resultCode == EmbeddedSafResult.RESULT_OK && !uri.isNullOrBlank()) {
            EmbeddedSafRoute.Selected(pending.operation, uri)
        } else {
            EmbeddedSafRoute.Canceled(pending.operation)
        }
    }

    companion object {
        const val REQUEST_PICK_FONT = 6511
        const val REQUEST_PICK_TTML = 6512
        const val REQUEST_CREATE_BACKUP = 6513
        const val REQUEST_RESTORE_BACKUP = 6514
        const val REQUEST_RESTORE_BACKUP_KEEP = 6515
        val OWN_REQUEST_CODES: Set<Int> = setOf(
            REQUEST_PICK_FONT,
            REQUEST_PICK_TTML,
            REQUEST_CREATE_BACKUP,
            REQUEST_RESTORE_BACKUP,
            REQUEST_RESTORE_BACKUP_KEEP,
        )
    }
}

/** Small facade so the host UI never depends on a particular storage backend. */
internal interface EmbeddedSettingsController {
    fun currentSettings(): ModuleSettings

    fun saveOrdinarySettings(settings: ModuleSettings): Boolean

    /**
     * Starts the host-process library refresh and reports its terminal result
     * on the caller's UI handler.  The default keeps lightweight test/fallback
     * controllers fail-open when the Apple Music refresh target is unavailable.
     */
    fun requestLibraryRefresh(onResult: (LibraryRefreshResult) -> Unit): Boolean = false

    /** Cancels the in-flight host refresh, if any. */
    fun cancelLibraryRefresh() = Unit

    fun currentSongDetails(): CurrentSongDetails? = null
    fun lyricsEntries(): List<CustomLyricsEntry> = emptyList()
    fun readLyrics(appleMusicId: Long): String? = null
    /** Reads and validates a SAF TTML document without persisting it. */
    fun readTtml(uri: Uri): String? = null
    fun saveLyrics(draft: CustomLyricsDraft, replacingAppleMusicId: Long? = null): EmbeddedActionResult =
        EmbeddedActionResult.Failed("歌词管理不可用")
    fun saveLyrics(
        draft: CustomLyricsMultiIdDraft,
        replacingAppleMusicIds: List<Long> = emptyList(),
    ): EmbeddedActionResult = EmbeddedActionResult.Failed("歌词管理不可用")
    fun setLyricsEnabled(appleMusicId: Long, enabled: Boolean): EmbeddedActionResult =
        EmbeddedActionResult.Failed("歌词管理不可用")
    fun setLyricsEnabled(appleMusicIds: List<Long>, enabled: Boolean): EmbeddedActionResult =
        EmbeddedActionResult.Failed("歌词管理不可用")
    fun deleteLyrics(appleMusicId: Long): EmbeddedActionResult =
        EmbeddedActionResult.Failed("歌词管理不可用")
    fun deleteLyrics(appleMusicIds: List<Long>): EmbeddedActionResult =
        EmbeddedActionResult.Failed("歌词管理不可用")
    fun importFont(uri: Uri): EmbeddedActionResult = EmbeddedActionResult.Failed("字体导入不可用")
    fun clearFont(): EmbeddedActionResult = EmbeddedActionResult.Failed("字体管理不可用")
    fun importTtml(
        uri: Uri,
        appleMusicId: Long,
        displayName: String,
        replacingAppleMusicId: Long? = null,
    ): EmbeddedActionResult = EmbeddedActionResult.Failed("歌词导入不可用")
    fun backupLyrics(uri: Uri): EmbeddedActionResult = EmbeddedActionResult.Failed("备份不可用")
    fun restoreLyrics(uri: Uri, policy: CustomLyricsRestorePolicy): EmbeddedActionResult =
        EmbeddedActionResult.Failed("恢复不可用")
    fun importOnlineLyrics(
        source: EmbeddedOnlineSource,
        appleMusicId: Long,
        neteaseSongId: Long?,
        displayName: String,
    ): EmbeddedActionResult = EmbeddedActionResult.Failed("在线导入不可用")

    fun syncFromGitHub(
        isCancelled: () -> Boolean = { false },
        onProgress: (CustomLyricsSyncProgress) -> Unit = {},
    ): CustomLyricsSyncResult = CustomLyricsSyncResult.Failed("GitHub 同步不可用")
}

internal class EmbeddedSessionSettingsController(
    private val session: EmbeddedConfigurationSession,
) : EmbeddedSettingsController {
    override fun currentSettings(): ModuleSettings = session.settings()

    override fun saveOrdinarySettings(settings: ModuleSettings): Boolean = session.saveSettings(settings)
}

internal fun interface EmbeddedSafSelectionHandler {
    fun onSelected(operation: EmbeddedSafOperation, uri: Uri)
}

/**
 * Embedded-only settings entry for Apple's PlayerActivity and settings surface.
 *
 * The host is registered through [install] by the embedded bootstrap seam.
 * It uses weak references for the current Activity and Dialog so registering
 * an Application callback cannot keep a destroyed host screen alive.
 */
internal class EmbeddedSettingsHost private constructor(
    private val application: Application,
    private val controller: EmbeddedSettingsController,
    private val safRouter: EmbeddedSafResultRouter,
    private val selectionHandler: EmbeddedSafSelectionHandler,
    private val activityMatcher: EmbeddedActivityMatcher,
) : Application.ActivityLifecycleCallbacks {
    private val lifecycleState = EmbeddedSettingsLifecycleState()
    private var activityReference: WeakReference<Activity>? = null
    private var dialogReference: WeakReference<Dialog>? = null
    private var pageRefresh: (() -> Unit)? = null
    private var libraryRefreshDialog: AlertDialog? = null
    private val customLyricsListState = CustomLyricsListState()
    private var customLyricsSearchQuery = ""
    private var pendingTtmlImport: ((String) -> Unit)? = null
    private var buttonReference: WeakReference<View>? = null
    private var settingsOptionReference: WeakReference<View>? = null
    private var activeActivityId: String? = null
    private var activeActivityRole: EmbeddedHostActivityRole? = null
    private val nativePreferenceActivityIds = mutableSetOf<String>()
    private var nativePreferenceFragmentReference: WeakReference<Any>? = null
    private var observedMainContentActivity: WeakReference<Activity>? = null
    private var observedMainContentDecor: WeakReference<View>? = null
    private var mainContentLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ampp-embedded-settings").apply { isDaemon = true }
    }

    @Volatile
    private var registered = true

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (registered && activityMatcher.isMainContentActivity(activity)) {
            installMainContentLayoutObserver(activity)
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        if (!registered) return
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
    fun onActivityResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ): Boolean {
        if (activityReference?.get() !== activity || activeActivityRole == null) return false
        return when (val route = safRouter.route(requestCode, resultCode, data?.dataString)) {
            EmbeddedSafRoute.Ignored -> false
            is EmbeddedSafRoute.Canceled -> {
                if (route.operation == EmbeddedSafOperation.Ttml) pendingTtmlImport = null
                currentActivity()?.let { activity ->
                    Toast.makeText(activity, "未选择文件", Toast.LENGTH_SHORT).show()
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
     * Exact 6.5.1 seam: SettingsFragment is an AndroidX PreferenceFragment
     * hosted by MainContentActivity. This keeps the option inside Apple's
     * native settings list; the View row remains a fallback for future host
     * layouts or when a repacker changes the Preference implementation.
     */
    fun onSettingsPreferencesReady(fragment: Any, activity: Activity) {
        if (!registered || activity.packageName != ModuleConstants.TARGET_PACKAGE) return
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

    fun onSettingsFragmentResumed(fragment: Any, activity: Activity) {
        if (!registered || activity.packageName != ModuleConstants.TARGET_PACKAGE) return
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
    fun onSettingsFragmentViewCreated(fragment: Any, activity: Activity, view: View?) {
        if (!registered || activity.packageName != ModuleConstants.TARGET_PACKAGE) return
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

    private fun scheduleSettingsOptionFallback(
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

    private fun scheduleNativePreferenceRefresh(activity: Activity, root: ViewGroup?) {
        mainHandler.postDelayed({
            if (registered && activityReference?.get() === activity) {
                refreshNativePreferenceAdapter(root)
            }
        }, NATIVE_PREFERENCE_FALLBACK_DELAY_MS)
    }

    private fun refreshNativePreferenceAdapter(root: ViewGroup?) {
        val recycler = root?.let(::findRecyclerView) ?: return
        runCatching {
            val adapter = recycler.javaClass.getMethod("getAdapter").invoke(recycler) ?: return
            adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter)
        }
    }

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
    private fun installMainContentLayoutObserver(activity: Activity) {
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

    private fun removeMainContentLayoutObserver() {
        val decor = observedMainContentDecor?.get()
        val listener = mainContentLayoutListener
        if (decor != null && listener != null) {
            runCatching { decor.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
        }
        observedMainContentActivity = null
        observedMainContentDecor = null
        mainContentLayoutListener = null
    }

    private fun onMainContentLayout(activity: Activity) {
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

    private fun injectButtonIfNeeded(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val existing: View? = content.findViewWithTag<View>(FLOATING_BUTTON_TAG)
        if (existing != null) {
            buttonReference = WeakReference<View>(existing)
            return
        }

        val density = activity.resources.displayMetrics.density
        val button = Button(activity).apply {
            tag = FLOATING_BUTTON_TAG
            text = "AM"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            contentDescription = "打开 AM++ 设置"
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(42, 93, 170))
            }
            elevation = 4f * density
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

    private fun injectSettingsOptionIfNeeded(activity: Activity, preferredRoot: ViewGroup? = null) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val decor = activity.window?.decorView as? ViewGroup
        if (content == null && decor == null) return
        val existing = listOfNotNull(content, decor)
            .distinct()
            .asSequence()
            .mapNotNull { candidate -> candidate.findViewWithTag<View>(SETTINGS_OPTION_TAG) }
            .firstOrNull()
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
            contentDescription = "打开 AM++ 模块设置"
            setOnClickListener { showSettingsDialog(activity) }
            addView(TextView(activity).apply {
                text = "AM++ 模块设置"
                textSize = 16f
                setTextColor(Color.DKGRAY)
            }, matchWidthWrapContent())
            addView(TextView(activity).apply {
                text = "字体、歌词与模块功能"
                textSize = 13f
                setTextColor(Color.GRAY)
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

    private fun fragmentView(fragment: Any): ViewGroup? = runCatching {
        ModernXposedRuntime.callMethod(fragment, "getView") as? ViewGroup
    }.getOrNull()

    private fun findSettingsListOverlayContainer(root: View): ViewGroup? {
        val pending = ArrayDeque<View>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 1024) {
            val view = pending.removeFirst()
            if (view is ViewGroup && isRecyclerView(view)) {
                return view.parent as? ViewGroup
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    pending.addLast(view.getChildAt(index))
                }
            }
        }
        return null
    }

    private fun findRecyclerView(root: View): ViewGroup? {
        val pending = ArrayDeque<View>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 1024) {
            val view = pending.removeFirst()
            if (view is ViewGroup && isRecyclerView(view)) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    pending.addLast(view.getChildAt(index))
                }
            }
        }
        return null
    }

    private fun injectNativeSettingsPreference(fragment: Any, activity: Activity): Boolean {
        val classLoader = fragment.javaClass.classLoader ?: activity.javaClass.classLoader ?: return false
        val preferenceClass = Class.forName(
            "androidx.preference.Preference",
            false,
            classLoader,
        )
        val key = NATIVE_SETTINGS_PREFERENCE_KEY
        val existing = runCatching {
            // AndroidX 6.5.1 maps PreferenceFragmentCompat.findPreference()
            // to t0(String); s0 is a PreferenceGroup field, not a lookup
            // method on the Fragment.
            ModernXposedRuntime.callMethod(fragment, "t0", key)
        }.getOrNull()
        if (existing != null) {
            return hasNativePreferenceClick(preferenceClass, existing)
        }

        val preference = preferenceClass
            .getConstructor(Context::class.java)
            .newInstance(activity)
        val keyWasSet = runCatching {
            preferenceClass.getDeclaredField("x").apply { isAccessible = true }.set(preference, key)
            true
        }.getOrDefault(false)
        if (!keyWasSet) {
            return false
        }
        // On the verified 6.5.1 AndroidX build, K is setTitle and J is
        // setSummary (J rejects a SummaryProvider, which distinguishes them).
        preferenceClass.getDeclaredMethod("K", CharSequence::class.java)
            .apply { isAccessible = true }
            .invoke(preference, "AM++ 模块设置")
        preferenceClass.getDeclaredMethod("J", CharSequence::class.java)
            .apply { isAccessible = true }
            .invoke(preference, "字体、歌词与模块功能")
        if (!installNativePreferenceClick(preferenceClass, preference, activity)) return false

        val screen = findNativePreferenceScreen(fragment) ?: return false
        // AndroidX 6.5.1 maps PreferenceGroup.P() to addPreference(); S()
        // is the corresponding remove path.  Calling S() makes the
        // injection look successful while immediately removing the row.
        ModernXposedRuntime.callMethod(screen, "P", preference)
        return true
    }

    private fun hasNativePreferenceClick(preferenceClass: Class<*>, preference: Any): Boolean =
        runCatching {
            findNativePreferenceClickField(preferenceClass)
                ?.apply { isAccessible = true }
                ?.get(preference) != null
        }.getOrDefault(false)

    private fun installNativePreferenceClick(
        preferenceClass: Class<*>,
        preference: Any,
        activity: Activity,
    ): Boolean {
        val clickField = findNativePreferenceClickField(preferenceClass) ?: return false
        val clickInterface = clickField.type
        return runCatching {
            val listener = Proxy.newProxyInstance(
                clickInterface.classLoader,
                arrayOf(clickInterface),
                InvocationHandler { _, method, _ ->
                    if (method.returnType == Boolean::class.javaPrimitiveType) {
                        mainHandler.post { showSettingsDialog(activity) }
                        true
                    } else {
                        null
                    }
                },
            )
            clickField.apply { isAccessible = true }.set(preference, listener)
            true
        }.getOrDefault(false)
    }

    private fun findNativePreferenceClickField(preferenceClass: Class<*>): java.lang.reflect.Field? {
        val clickInterface = preferenceClass.declaredClasses.firstOrNull { nested ->
            nested.isInterface && nested.declaredMethods.any { method ->
                method.parameterTypes.contentEquals(arrayOf(preferenceClass)) &&
                    method.returnType == Boolean::class.javaPrimitiveType
            }
        } ?: return null
        return preferenceClass.declaredFields.firstOrNull { field ->
            field.type == clickInterface
        }
    }

    private fun findNativePreferenceScreen(fragment: Any): Any? {
        var fragmentType: Class<*>? = fragment.javaClass
        while (fragmentType != null) {
            val manager = fragmentType.declaredFields.asSequence()
                .mapNotNull { field ->
                    runCatching {
                        field.apply { isAccessible = true }.get(fragment)
                    }.getOrNull()
                }
                .firstOrNull { candidate ->
                    candidate.javaClass.declaredFields.any { field ->
                        field.type.name == "androidx.preference.PreferenceScreen"
                    }
                }
            if (manager != null) {
                var managerType: Class<*>? = manager.javaClass
                while (managerType != null) {
                    val screen = managerType.declaredFields.asSequence()
                        .filter { it.type.name == "androidx.preference.PreferenceScreen" }
                        .mapNotNull { field ->
                            runCatching {
                                field.apply { isAccessible = true }.get(manager)
                            }.getOrNull()
                        }
                        .firstOrNull()
                    if (screen != null) return screen
                    managerType = managerType.superclass
                }
            }
            fragmentType = fragmentType.superclass
        }
        return null
    }

    private fun findSettingsInsertionContainer(root: ViewGroup): ViewGroup? {
        if (isRecyclerView(root)) return null
        if (isScrollView(root)) {
            val child = root.getChildAt(0) as? ViewGroup ?: return null
            return findSettingsInsertionContainer(child) ?: child
        }
        if (root is LinearLayout) return root
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index) as? ViewGroup ?: continue
            findSettingsInsertionContainer(child)?.let { return it }
        }
        return null
    }

    private fun isScrollView(view: ViewGroup): Boolean =
        view is ScrollView || view.javaClass.name.endsWith("NestedScrollView")

    private fun isRecyclerView(view: ViewGroup): Boolean =
        view.javaClass.name.contains("RecyclerView")

    private fun showSettingsDialog(activity: Activity) {
        val currentDialog = dialogReference?.get()
        if (currentDialog?.isShowing == true) return

        var draft = runCatching { controller.currentSettings() }.getOrElse {
            Toast.makeText(activity, "无法读取 AM++ 设置", Toast.LENGTH_SHORT).show()
            return
        }
        var page = EmbeddedSettingsPage.MAIN
        lateinit var dialog: AlertDialog

        val pageHost = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 8), 0, dp(activity, 8), 0)
        }
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, 56)
        }
        val backButton = TextView(activity).apply {
            text = "‹"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(42, 93, 170))
            contentDescription = "返回"
            isClickable = true
            isFocusable = true
            setPadding(0, 0, dp(activity, 8), 0)
        }
        val pageTitle = TextView(activity).apply {
            textSize = 20f
            setTextColor(Color.rgb(28, 29, 34))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val saveButton = TextView(activity).apply {
            text = "保存"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(42, 93, 170))
            isClickable = true
            isFocusable = true
            setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 8), dp(activity, 8))
            contentDescription = "保存 AM++ 设置"
        }
        topBar.addView(backButton, LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 52)))
        topBar.addView(pageTitle)
        topBar.addView(saveButton, LinearLayout.LayoutParams(dp(activity, 64), dp(activity, 52)))
        pageHost.addView(topBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 56),
        ))
        val pageContent = FrameLayout(activity)
        pageHost.addView(pageContent, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        fun saveDraft(close: Boolean) {
            if (controller.saveOrdinarySettings(draft)) {
                Toast.makeText(activity, "已保存；需要重启的设置请重开 Apple Music。", Toast.LENGTH_LONG).show()
                if (close) dialog.dismiss()
            } else {
                Toast.makeText(activity, "保存 AM++ 设置失败", Toast.LENGTH_SHORT).show()
            }
        }

        fun updateDraft(next: ModuleSettings) {
            draft = next
            if (!controller.saveOrdinarySettings(next)) {
                Toast.makeText(activity, "保存 AM++ 设置失败", Toast.LENGTH_SHORT).show()
            }
        }

        fun renderPage() {
            pageContent.removeAllViews()
            val scroll = ScrollView(activity).apply {
                isFillViewport = true
                setPadding(dp(activity, 12), dp(activity, 4), dp(activity, 12), dp(activity, 12))
            }
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            scroll.addView(content)
            pageContent.addView(scroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))

            val customLyricsPage = page == EmbeddedSettingsPage.CUSTOM_LYRICS
            pageTitle.text = if (customLyricsPage) "自定义歌词" else "AM++"
            backButton.visibility = if (customLyricsPage) View.VISIBLE else View.INVISIBLE
            val song = controller.currentSongDetails()
            if (customLyricsPage) {
                renderEmbeddedCustomLyricsPage(
                    activity = activity,
                    parent = content,
                    settings = draft,
                    song = song,
                    onSettingsChanged = ::updateDraft,
                )
            } else {
                renderEmbeddedMainPage(
                    activity = activity,
                    parent = content,
                    settings = draft,
                    song = song,
                    lyricsCount = runCatching { controller.lyricsEntries().size }.getOrDefault(0),
                    onSettingsChanged = ::updateDraft,
                    onOpenCustomLyrics = {
                        page = EmbeddedSettingsPage.CUSTOM_LYRICS
                        renderPage()
                    },
                    onRefreshLibrary = { requestLibraryRefresh(activity) },
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
        }

        backButton.setOnClickListener {
            if (page == EmbeddedSettingsPage.CUSTOM_LYRICS) {
                page = EmbeddedSettingsPage.MAIN
                renderPage()
            } else {
                dialog.dismiss()
            }
        }
        saveButton.setOnClickListener { saveDraft(close = true) }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = (activity.resources.displayMetrics.heightPixels * 0.72f).toInt()
            addView(pageHost, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
        }
        dialog = AlertDialog.Builder(activity)
            .setView(root)
            .setNegativeButton("关闭", null)
            .create()
        val weakDialog = WeakReference<Dialog>(dialog)
        dialog.setOnDismissListener {
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

    private fun renderEmbeddedMainPage(
        activity: Activity,
        parent: LinearLayout,
        settings: ModuleSettings,
        song: CurrentSongDetails?,
        lyricsCount: Int,
        onSettingsChanged: (ModuleSettings) -> Unit,
        onOpenCustomLyrics: () -> Unit,
        onRefreshLibrary: () -> Unit,
        onChooseFont: () -> Unit,
        onClearFont: () -> Unit,
    ) {
        parent.addView(embeddedStatusCard(activity, song))
        parent.addView(embeddedSpacer(activity, 16))
        parent.addView(embeddedCard(activity, "功能") {
            addView(embeddedSettingRow(
                activity,
                "平板双栏播放器",
                "仅在 Apple Music 判定为平板且横屏时启用",
                settings.dualPaneEnabled,
            ) { onSettingsChanged(settings.copy(dualPaneEnabled = it)) })
            addView(embeddedDivider(activity))
            addView(embeddedSettingRow(
                activity,
                "平板底栏补偿",
                "如果底栏显示异常开启该选项",
                settings.navigationCompensationEnabled,
            ) { onSettingsChanged(settings.copy(navigationCompensationEnabled = it)) })
            addView(embeddedDivider(activity))
            addView(embeddedSettingRow(
                activity,
                "平板禁用动态视频",
                "平板横屏时禁用 Editorial Video",
                settings.disableEditorialVideoOnTablet,
            ) { onSettingsChanged(settings.copy(disableEditorialVideoOnTablet = it)) })
            addView(embeddedDivider(activity))
            addView(embeddedSettingRow(
                activity,
                "手机液态玻璃底栏",
                "仅手机启用 · 更改后需强制停止并重开 Apple Music",
                settings.phoneLiquidGlassEnabled,
                badge = "WIP",
            ) { onSettingsChanged(settings.copy(phoneLiquidGlassEnabled = it)) })
            addView(embeddedDivider(activity))
            addView(embeddedSettingRow(
                activity,
                "双向歌词模糊",
                "手动滚动停止 1 秒后恢复",
                settings.futureBlurEnabled,
            ) { onSettingsChanged(settings.copy(futureBlurEnabled = it)) })
            addView(embeddedDivider(activity))
            addView(embeddedBlurRadiusRow(activity, settings.lyricBlurRadiusOffsetPx) {
                onSettingsChanged(settings.copy(lyricBlurRadiusOffsetPx = it))
            })
            addView(embeddedDivider(activity))
            addView(embeddedSettingRow(
                activity,
                "歌曲名显示修正",
                "将部分 Catalog 请求改为目标语言并回填标题 · 修改后重开 Apple Music",
                settings.titleCorrectionEnabled,
            ) { onSettingsChanged(settings.copy(titleCorrectionEnabled = it)) })
            addView(embeddedDivider(activity))
            addView(embeddedNavigationRow(
                activity,
                "目标语言",
                CatalogLanguagePolicy.displayName(settings.titleCorrectionTargetLanguage),
            ) {
                showEmbeddedTargetLanguagePicker(
                    activity = activity,
                    current = settings.titleCorrectionTargetLanguage,
                ) { target ->
                    onSettingsChanged(settings.copy(titleCorrectionTargetLanguage = target))
                    // Re-render only after a picker selection so the summary
                    // reflects the persisted canonical tag immediately.
                    pageRefresh?.invoke()
                }
            })
            addView(embeddedDivider(activity))
            addView(embeddedNavigationRow(
                activity,
                "刷新资料库",
                "触发 Apple Music 原生同步并补查歌曲名",
                onRefreshLibrary,
            ))
            addView(embeddedDivider(activity))
            addView(embeddedNavigationRow(
                activity,
                "自定义歌词",
                if (lyricsCount == 0) "添加和管理 Apple Music ID 歌词映射" else "已配置 $lyricsCount 首歌词",
                onOpenCustomLyrics,
            ))
        })
        parent.addView(embeddedSpacer(activity, 20))
        parent.addView(embeddedFontCard(
            activity = activity,
            manifest = settings.fontManifest,
            onChooseFont = onChooseFont,
            onClearFont = onClearFont,
        ))
        parent.addView(embeddedSpacer(activity, 20))
        parent.addView(embeddedSectionLabel(activity, "应用"))
        parent.addView(embeddedInfoCard(
            activity,
            "配置保存在 Apple Music 私有目录，不依赖模块 Activity。\n嵌入版没有独立启动器 Activity，因此不显示“隐藏启动器图标”开关。",
        ))
        parent.addView(embeddedSpacer(activity, 16))
        parent.addView(embeddedSectionLabel(activity, "帮助"))
        parent.addView(embeddedInfoCard(
            activity,
            "LSPosed 配置提示\n字体和标记“需重启”的设置，需要完全重开 Apple Music 后生效。",
            onClick = { showEmbeddedHelp(activity) },
        ))
    }

    private fun renderEmbeddedCustomLyricsPage(
        activity: Activity,
        parent: LinearLayout,
        settings: ModuleSettings,
        song: CurrentSongDetails?,
        onSettingsChanged: (ModuleSettings) -> Unit,
    ) {
        val entries = runCatching { controller.lyricsEntries() }.getOrDefault(emptyList())
        customLyricsListState.update(entries, customLyricsSearchQuery)

        parent.addView(embeddedCard(activity, null) {
            addView(embeddedSettingRow(
                activity,
                "自定义歌词替换",
                "按 Apple Music ID 注入 · 更改后重开 Apple Music 生效",
                settings.customLyricsEnabled,
            ) { onSettingsChanged(settings.copy(customLyricsEnabled = it)) })
        })
        parent.addView(embeddedSpacer(activity, 20))

        val lyricsCard = embeddedCard(activity, "自定义歌词") {
            addView(TextView(activity).apply {
                text = when {
                    entries.isEmpty() -> "按 Apple Music ID 手动添加 TTML；不会在播放时联网识歌"
                    else -> "已配置 ${entries.size} 首；更改后重开 Apple Music 生效"
                }
                textSize = 13.5f
                setTextColor(Color.GRAY)
                setPadding(dp(activity, 16), dp(activity, 4), dp(activity, 16), dp(activity, 12))
            }, matchWidthWrapContent())

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(activity, 12), 0, dp(activity, 12), dp(activity, 8))
                addView(embeddedActionButton(activity, "添加歌词") {
                    showLyricsEditor(activity, null as CustomLyricsUiGroup?, song)
                }, LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
            }, matchWidthWrapContent())

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(activity, 12), 0, dp(activity, 12), dp(activity, 12))
                addView(embeddedActionButton(activity, "备份歌词") {
                    launchSafPicker(activity, EmbeddedSafOperation.Backup, "application/zip")
                }, LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
                addView(embeddedActionSpacer(activity))
                addView(embeddedActionButton(activity, "恢复备份") {
                    launchSafPicker(
                        activity,
                        EmbeddedSafOperation.RestoreOverwrite,
                        "*/*",
                        arrayOf(
                            "application/zip",
                            "application/x-zip-compressed",
                            "application/octet-stream",
                        ),
                    )
                }, LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
            }, matchWidthWrapContent())

            addView(embeddedActionButton(activity, "同步 GitHub 源") {
                syncEmbeddedGitHub(activity)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 48),
            ).apply {
                marginStart = dp(activity, 12)
                marginEnd = dp(activity, 12)
                bottomMargin = dp(activity, 12)
            })

            val search = EditText(activity).apply {
                hint = "搜索名称或 Apple Music ID"
                textSize = 14f
                isSingleLine = true
                setText(customLyricsSearchQuery)
                setPadding(dp(activity, 14), 0, dp(activity, 14), 0)
                setTextColor(Color.DKGRAY)
                setHintTextColor(Color.GRAY)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(248, 248, 250))
                    cornerRadius = dp(activity, 12).toFloat()
                }
            }
            addView(search, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 44),
            ).apply {
                marginStart = dp(activity, 16)
                marginEnd = dp(activity, 16)
                bottomMargin = dp(activity, 6)
            })
            val entriesRegion = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(entriesRegion, matchWidthWrapContent())

            fun renderEntries() {
                entriesRegion.removeAllViews()
                val state = customLyricsListState
                if (state.totalCount == 0) {
                    entriesRegion.addView(TextView(activity).apply {
                        text = if (entries.isEmpty()) "暂无自定义歌词" else "没有匹配的歌词"
                        textSize = 13.5f
                        setTextColor(Color.GRAY)
                        setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 12))
                    })
                    return
                }
                val visibleGroups = state.visibleGroups
                visibleGroups.forEachIndexed { index, group ->
                    entriesRegion.addView(embeddedCustomLyricsEntryRow(activity, group, song))
                    if (index < visibleGroups.lastIndex) entriesRegion.addView(embeddedDivider(activity))
                }
                entriesRegion.addView(TextView(activity).apply {
                    text = "已显示 ${state.visibleCount} / 共 ${state.totalCount} 首"
                    textSize = 13f
                    setTextColor(Color.GRAY)
                    setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 12))
                })
                if (state.hasMore) {
                    entriesRegion.addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(dp(activity, 12), 0, dp(activity, 12), dp(activity, 12))
                        addView(embeddedActionButton(activity, "加载更多") {
                            customLyricsListState.loadMore()
                            renderEntries()
                        }, LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
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
        parent.addView(lyricsCard)
    }

    private fun embeddedCustomLyricsEntryRow(
        activity: Activity,
        group: CustomLyricsUiGroup,
        song: CurrentSongDetails?,
    ): View = LinearLayout(activity).apply {
        val entry = group.primary
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 12), dp(activity, 12))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = entry.displayName.ifBlank { entry.appleMusicId.toString() }
                    textSize = 16f
                    setTextColor(Color.DKGRAY)
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.DEFAULT,
                        android.graphics.Typeface.BOLD,
                    )
                }, matchWidthWrapContent())
                addView(TextView(activity).apply {
                    text = "主 ID：${entry.appleMusicId} · 共 ${group.entries.size} 个 ID · " +
                        embeddedCustomLyricsSourceName(entry.source)
                    textSize = 13f
                    setTextColor(Color.GRAY)
                    setPadding(0, dp(activity, 3), 0, 0)
                }, matchWidthWrapContent())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Switch(activity).apply {
                isChecked = group.allEnabled
                contentDescription = "${entry.displayName} 自定义歌词开关"
                setOnCheckedChangeListener { _, checked ->
                    runAsync(activity) { controller.setLyricsEnabled(group.appleMusicIds, checked) }
                }
            }, LinearLayout.LayoutParams(dp(activity, 64), dp(activity, 48)))
        }, matchWidthWrapContent())
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(activity, 8), 0, 0)
            addView(embeddedActionButton(activity, "编辑") {
                showLyricsEditor(activity, group, song)
            }, LinearLayout.LayoutParams(0, dp(activity, 44), 1f))
            addView(embeddedActionSpacer(activity))
            addView(embeddedActionButton(activity, "删除") {
                AlertDialog.Builder(activity)
                    .setMessage(
                        "删除“${entry.displayName.ifBlank { entry.appleMusicId.toString() }}”及其 " +
                            "${group.entries.size} 个 Apple Music ID 的 TTML 映射？",
                    )
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
                        runAsync(activity) { controller.deleteLyrics(group.appleMusicIds) }
                    }
                    .show()
            }, LinearLayout.LayoutParams(0, dp(activity, 44), 1f))
        }, matchWidthWrapContent())
    }

    private fun embeddedCustomLyricsSourceName(source: String): String = when (source) {
        CustomLyricsSources.AMLL -> "AMLL"
        CustomLyricsSources.NETEASE -> "网易云 YRC"
        CustomLyricsSources.AM_LYRICS -> "AM-Lyrics 仓库"
        else -> "手动 TTML"
    }

    private fun syncEmbeddedGitHub(activity: Activity) {
        val cancelled = AtomicBoolean(false)
        val progress = TextView(activity).apply {
            text = "正在读取 GitHub 索引…"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 24), dp(activity, 8))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("同步 GitHub 源")
            .setView(progress)
            .setNegativeButton("取消") { _, _ -> cancelled.set(true) }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { cancelled.set(true) }
        dialog.show()
        worker.execute {
            val result = runCatching {
                controller.syncFromGitHub(
                    isCancelled = cancelled::get,
                    onProgress = { update ->
                        mainHandler.post {
                            if (dialog.isShowing) {
                                progress.text =
                                    "正在同步 ${update.processedEntries}/${update.totalEntries} 条 GitHub 歌词…"
                            }
                        }
                    },
                )
            }.getOrElse { error ->
                CustomLyricsSyncResult.Failed(
                    "同步 GitHub 源失败：${error.message.orEmpty()}",
                )
            }
            mainHandler.post {
                if (dialog.isShowing) dialog.dismiss()
                val current = currentActivity() ?: return@post
                when (result) {
                    is CustomLyricsSyncResult.Synced -> Toast.makeText(
                        current,
                        "GitHub 同步完成：新增 ${result.importedIds} 个 ID，覆盖 " +
                            "${result.overwrittenIds} 个 ID，保留 ${result.preservedIds} 个本地 ID",
                        Toast.LENGTH_LONG,
                    ).show()
                    CustomLyricsSyncResult.Cancelled -> Toast.makeText(
                        current,
                        "GitHub 同步已取消",
                        Toast.LENGTH_SHORT,
                    ).show()
                    is CustomLyricsSyncResult.Failed -> Toast.makeText(
                        current,
                        result.message,
                        Toast.LENGTH_LONG,
                    ).show()
                }
                if (result is CustomLyricsSyncResult.Synced) pageRefresh?.invoke()
            }
        }
    }

    /**
     * Requests the refresh through the controller's in-process protocol.  The
     * native target owns all reflection, waiting and Catalog backfill work;
     * this host only keeps a cancellable progress surface on the UI thread.
     */
    private fun requestLibraryRefresh(activity: Activity) {
        if (libraryRefreshDialog?.isShowing == true) return

        val completed = AtomicBoolean(false)
        val progress = TextView(activity).apply {
            text = "正在触发 Apple Music 资料库同步…"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(dp(activity, 24), dp(activity, 12), dp(activity, 24), dp(activity, 12))
        }
        lateinit var dialog: AlertDialog
        fun finish(result: LibraryRefreshResult) {
            if (!completed.compareAndSet(false, true)) return
            if (libraryRefreshDialog === dialog) {
                dialog.dismiss()
            }
            val current = currentActivity() ?: return
            val message = result.message.orEmpty().ifBlank {
                when (result.resultCode) {
                    LibraryRefreshProtocol.RESULT_COMPLETED -> "资料库刷新完成"
                    LibraryRefreshProtocol.RESULT_CANCELLED -> "已停止刷新资料库"
                    LibraryRefreshProtocol.RESULT_UNAVAILABLE -> "资料库刷新不可用"
                    else -> "资料库刷新失败"
                }
            }
            Toast.makeText(
                current,
                message,
                if (result.resultCode == LibraryRefreshProtocol.RESULT_COMPLETED) {
                    Toast.LENGTH_LONG
                } else {
                    Toast.LENGTH_SHORT
                },
            ).show()
        }

        dialog = AlertDialog.Builder(activity)
            .setTitle("刷新资料库")
            .setView(progress)
            .setNegativeButton("取消") { _, _ ->
                completed.set(true)
                controller.cancelLibraryRefresh()
            }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener {
            if (completed.compareAndSet(false, true)) controller.cancelLibraryRefresh()
        }
        dialog.setOnDismissListener {
            if (libraryRefreshDialog === dialog) libraryRefreshDialog = null
            if (completed.compareAndSet(false, true)) controller.cancelLibraryRefresh()
        }
        libraryRefreshDialog = dialog

        val requested = runCatching {
            controller.requestLibraryRefresh(::finish)
        }.getOrElse { error ->
            finish(
                LibraryRefreshResult(
                    LibraryRefreshProtocol.RESULT_FAILED,
                    "无法向 Apple Music 发送刷新请求：${error.message.orEmpty()}",
                ),
            )
            false
        }
        if (!requested) {
            if (!completed.get()) {
                completed.set(true)
                if (libraryRefreshDialog === dialog) dialog.dismiss()
                Toast.makeText(activity, "刷新和补查正在进行或不可用", Toast.LENGTH_SHORT).show()
            }
            return
        }
        // A sendBroadcast failure can synchronously complete the requester;
        // do not show a stale progress dialog after that callback has arrived.
        if (!completed.get() && !dialog.isShowing) dialog.show()
    }

    private fun embeddedStatusCard(activity: Activity, song: CurrentSongDetails?): View =
        embeddedCard(activity, null) {
            addView(TextView(activity).apply {
                text = song?.let {
                    "当前歌曲：${it.title.orEmpty().ifBlank { "未知标题" }}\nApple Music ID：${it.appleMusicId}"
                } ?: "当前歌曲：尚未捕获（播放一首歌后重试）"
                textSize = 14f
                setTextColor(Color.DKGRAY)
                setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14))
            }, matchWidthWrapContent())
        }

    private fun embeddedFontCard(
        activity: Activity,
        manifest: dev.amenhancer.module.model.LyricsFontManifest,
        onChooseFont: () -> Unit,
        onClearFont: () -> Unit,
    ): View = embeddedCard(activity, "歌词字体") {
        addView(TextView(activity).apply {
            text = if (manifest.enabled) manifest.displayName else "原字体"
            textSize = 17f
            setTextColor(Color.DKGRAY)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(activity, 16), dp(activity, 4), dp(activity, 16), 0)
        }, matchWidthWrapContent())
        addView(TextView(activity).apply {
            text = if (manifest.enabled) {
                "仅覆盖播放器歌词 · 重开 Apple Music 后生效"
            } else {
                "导入 TTF/OTF · 重开 Apple Music 后生效"
            }
            textSize = 13.5f
            setTextColor(Color.GRAY)
            setPadding(dp(activity, 16), dp(activity, 4), dp(activity, 16), dp(activity, 12))
        }, matchWidthWrapContent())
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(activity, 12), 0, dp(activity, 12), dp(activity, 12))
            addView(embeddedActionButton(activity, "选择字体", onClick = onChooseFont),
                LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
            addView(embeddedActionSpacer(activity))
            addView(embeddedActionButton(activity, "恢复原字体", manifest.enabled, onClearFont),
                LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
        }
        addView(actions, matchWidthWrapContent())
    }

    private fun embeddedCard(
        activity: Activity,
        title: String?,
        content: LinearLayout.() -> Unit,
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(dp(activity, 1), Color.rgb(232, 232, 236))
            cornerRadius = dp(activity, 18).toFloat()
        }
        elevation = dp(activity, 1).toFloat()
        title?.let { section -> addView(embeddedSectionLabel(activity, section)) }
        content()
    }

    private fun embeddedSectionLabel(activity: Activity, text: String): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 8))
        }

    private fun embeddedInfoCard(
        activity: Activity,
        text: String,
        onClick: (() -> Unit)? = null,
    ): View = embeddedCard(activity, null) {
            addView(TextView(activity).apply {
                this.text = text
                textSize = 13.5f
                setTextColor(Color.GRAY)
                setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12))
            }, matchWidthWrapContent())
            onClick?.let { click ->
                isClickable = true
                isFocusable = true
                setOnClickListener { click() }
            }
        }

    private fun showEmbeddedHelp(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("LSPosed 配置提示")
            .setMessage(
                "在 LSPosed 中启用 AM++，并仅选择 Apple Music（com.apple.android.music）作为作用域。" +
                    "修改功能后，请强制停止并重新打开 Apple Music。",
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun embeddedSettingRow(
        activity: Activity,
        title: String,
        summary: String,
        checked: Boolean,
        badge: String? = null,
        onChanged: (Boolean) -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(activity, 76)
        setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 12), dp(activity, 8))
        val labels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(activity).apply {
                    text = title
                    textSize = 16f
                    setTextColor(Color.DKGRAY)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                badge?.let { badgeText ->
                    addView(embeddedBadge(activity, badgeText))
                }
            }, matchWidthWrapContent())
            addView(TextView(activity).apply {
                text = summary
                textSize = 12.5f
                setTextColor(Color.GRAY)
                setPadding(0, dp(activity, 3), 0, 0)
            }, matchWidthWrapContent())
        }
        addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = Switch(activity).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        }
        addView(toggle, LinearLayout.LayoutParams(dp(activity, 52), dp(activity, 48)))
        setOnClickListener { toggle.isChecked = !toggle.isChecked }
    }

    private fun embeddedBadge(activity: Activity, text: String): View = TextView(activity).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(55, 90, 180))
        setPadding(dp(activity, 8), dp(activity, 3), dp(activity, 8), dp(activity, 3))
        background = GradientDrawable().apply {
            setColor(Color.rgb(232, 238, 255))
            cornerRadius = dp(activity, 99).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(activity, 8) }
    }

    private fun embeddedBlurRadiusRow(
        activity: Activity,
        value: Int,
        onChanged: (Int) -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 8))
        val title = "歌词模糊半径偏移"
        val label = TextView(activity).apply {
            text = "$title：${value}px"
            textSize = 15f
            setTextColor(Color.DKGRAY)
        }
        addView(label, matchWidthWrapContent())
        addView(SeekBar(activity).apply {
            max = ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX -
                ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX
            progress = value - ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val next = (progress + ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX).coerceIn(
                        ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX,
                        ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX,
                    )
                    label.text = "$title：${next}px"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (seekBar != null) {
                        onChanged(
                            (seekBar.progress + ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX)
                                .coerceIn(
                                    ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX,
                                    ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX,
                                ),
                        )
                    }
                }
            })
        }, matchWidthWrapContent())
    }

    private fun showEmbeddedTargetLanguagePicker(
        activity: Activity,
        current: String,
        onSelected: (String) -> Unit,
    ) {
        val normalizedCurrent = CatalogLanguagePolicy.normalize(current)
        val tags = EMBEDDED_CATALOG_LANGUAGE_TAGS
        val labels = tags.map { CatalogLanguagePolicy.displayName(it) }.toTypedArray()
        val selected = tags.indexOf(normalizedCurrent)
        AlertDialog.Builder(activity)
            .setTitle("目标语言")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                tags.getOrNull(which)?.let { tag ->
                    onSelected(tag)
                    dialog.dismiss()
                }
            }
            .setNeutralButton("自定义") { _, _ ->
                showEmbeddedTargetLanguageEditor(activity, normalizedCurrent, onSelected)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEmbeddedTargetLanguageEditor(
        activity: Activity,
        current: String,
        onSelected: (String) -> Unit,
    ) {
        val input = EditText(activity).apply {
            hint = "例如 tr-TR"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(current)
            setSelectAllOnFocus(true)
            setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 24), 0)
        }
        AlertDialog.Builder(activity)
            .setTitle("自定义目标语言")
            .setMessage("请输入 BCP-47 语言标签（例如 zh-CN）；空值或非法值无法保存")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val raw = input.text?.toString().orEmpty()
                if (!CatalogLanguagePolicy.isValid(raw)) {
                    Toast.makeText(activity, "目标语言格式无效，例如 tr-TR", Toast.LENGTH_SHORT).show()
                } else {
                    onSelected(CatalogLanguagePolicy.normalize(raw))
                }
            }
            .show()
    }

    private fun embeddedNavigationRow(
        activity: Activity,
        title: String,
        summary: String,
        onClick: () -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(activity, 80)
        isClickable = true
        isFocusable = true
        contentDescription = title
        setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 14), dp(activity, 10))
        setOnClickListener { onClick() }
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = title
                textSize = 16f
                setTextColor(Color.DKGRAY)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, matchWidthWrapContent())
            addView(TextView(activity).apply {
                text = summary
                textSize = 12.5f
                setTextColor(Color.GRAY)
                setPadding(0, dp(activity, 3), 0, 0)
            }, matchWidthWrapContent())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(activity).apply {
            text = "›"
            textSize = 28f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 40)))
    }

    private fun embeddedActionButton(
        activity: Activity,
        label: String,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): Button = Button(activity).apply {
        text = label
        isAllCaps = false
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.55f
        minHeight = dp(activity, 42)
        setTextColor(if (enabled) Color.rgb(42, 93, 170) else Color.GRAY)
        background = GradientDrawable().apply {
            setColor(if (enabled) Color.rgb(232, 238, 255) else Color.rgb(242, 242, 244))
            cornerRadius = dp(activity, 12).toFloat()
        }
        setOnClickListener { if (enabled) onClick() }
    }

    private fun embeddedActionSpacer(activity: Activity): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(dp(activity, 8), dp(activity, 1))
    }

    private fun embeddedSpacer(activity: Activity, height: Int): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(activity, height))
    }

    private fun embeddedDivider(activity: Activity): View = View(activity).apply {
        setBackgroundColor(Color.rgb(238, 238, 241))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 1),
        ).apply {
            marginStart = dp(activity, 16)
            marginEnd = dp(activity, 16)
        }
    }

    private fun showLegacySettingsDialog(activity: Activity) {
        val currentDialog = dialogReference?.get()
        if (currentDialog?.isShowing == true) return

        val settings = runCatching { controller.currentSettings() }.getOrElse {
            Toast.makeText(activity, "无法读取 AM++ 设置", Toast.LENGTH_SHORT).show()
            return
        }
        val content = ScrollView(activity).apply {
            isFillViewport = true
            setPadding(dp(activity, 20), dp(activity, 4), dp(activity, 20), dp(activity, 4))
        }
        val fields = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(fields)

        val song = controller.currentSongDetails()
        fields.addView(TextView(activity).apply {
            text = song?.let {
                "当前歌曲：${it.title.orEmpty().ifBlank { "未知标题" }}\nApple Music ID：${it.appleMusicId}"
            } ?: "当前歌曲：尚未捕获（播放一首歌后重试）"
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(activity, 8), 0, dp(activity, 8))
        }, matchWidthWrapContent())

        val dualPane = addSwitch(fields, activity, "双栏界面（需重启）", settings.dualPaneEnabled)
        val disableEditorialVideo = addSwitch(
            fields,
            activity,
            "平板隐藏编辑视频（需重启）",
            settings.disableEditorialVideoOnTablet,
        )
        val futureBlur = addSwitch(fields, activity, "歌词未来模糊（需重启）", settings.futureBlurEnabled)
        val navigationCompensation = addSwitch(
            fields,
            activity,
            "导航栏补偿（需重启）",
            settings.navigationCompensationEnabled,
        )
        val customLyrics = addSwitch(fields, activity, "启用自定义歌词", settings.customLyricsEnabled)

        val blurValue = TextView(activity).apply {
            text = "歌词模糊偏移：${settings.lyricBlurRadiusOffsetPx}px"
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(activity, 10), 0, 0)
        }
        fields.addView(blurValue, matchWidthWrapContent())
        val blurSeekBar = SeekBar(activity).apply {
            max = ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX -
                ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX
            progress = settings.lyricBlurRadiusOffsetPx - ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    blurValue.text = "歌词模糊偏移：${progress + ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX}px"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        fields.addView(blurSeekBar, matchWidthWrapContent())

        val fontName = settings.fontManifest.displayName.ifBlank { "未选择字体" }
        addFileButton(fields, activity, "字体：$fontName", EmbeddedSafOperation.Font, "font/*")
        if (settings.fontManifest.enabled) {
            Button(activity).apply {
                text = "清除自定义字体"
                setOnClickListener { runAsync(activity, controller::clearFont) }
                fields.addView(this, matchWidthWrapContent())
            }
        }
        addFileButton(fields, activity, "为当前歌曲选择 TTML", EmbeddedSafOperation.Ttml, "text/*")
        addLyricsManagement(fields, activity, song)
        addFileButton(fields, activity, "导出歌词 ZIP", EmbeddedSafOperation.Backup, "application/zip")
        addFileButton(
            fields,
            activity,
            "恢复歌词 ZIP（覆盖冲突）",
            EmbeddedSafOperation.RestoreOverwrite,
            "application/zip",
        )
        addFileButton(
            fields,
            activity,
            "恢复歌词 ZIP（保留现有）",
            EmbeddedSafOperation.RestoreKeepExisting,
            "application/zip",
        )

        val restartHint = TextView(activity).apply {
            text = "提示：标记“需重启”的设置，以及字体变更，需要重启 Apple Music 后完全生效。"
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(activity, 16), 0, dp(activity, 8))
        }
        fields.addView(restartHint, matchWidthWrapContent())

        val dialog = AlertDialog.Builder(activity)
            .setTitle("AM++ 设置")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val updated = settings.copy(
                    dualPaneEnabled = dualPane.isChecked,
                    disableEditorialVideoOnTablet = disableEditorialVideo.isChecked,
                    futureBlurEnabled = futureBlur.isChecked,
                    navigationCompensationEnabled = navigationCompensation.isChecked,
                    lyricBlurRadiusOffsetPx = (
                        blurSeekBar.progress + ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX
                        ).coerceIn(
                        ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX,
                        ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX,
                    ),
                    customLyricsEnabled = customLyrics.isChecked,
                )
                if (controller.saveOrdinarySettings(updated)) {
                    Toast.makeText(
                        activity,
                        "已保存；标记“需重启”的设置需要重启 Apple Music。",
                        Toast.LENGTH_LONG,
                    ).show()
                    dialog.dismiss()
                } else {
                    Toast.makeText(activity, "保存 AM++ 设置失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val weakDialog = WeakReference<Dialog>(dialog)
        dialog.setOnDismissListener {
            if (dialogReference?.get() === weakDialog.get()) dialogReference = null
        }
        dialogReference = weakDialog
        dialog.show()
    }

    private fun addSwitch(
        parent: LinearLayout,
        activity: Activity,
        label: String,
        checked: Boolean,
    ): Switch = Switch(activity).apply {
        text = label
        isChecked = checked
        setPadding(0, dp(activity, 5), 0, dp(activity, 5))
        parent.addView(this, matchWidthWrapContent())
    }

    private fun addFileButton(
        parent: LinearLayout,
        activity: Activity,
        label: String,
        operation: EmbeddedSafOperation,
        mimeType: String,
    ) {
        Button(activity).apply {
            text = label
            setOnClickListener { launchSafPicker(activity, operation, mimeType) }
            parent.addView(this, matchWidthWrapContent())
        }
    }

    private fun addLyricsManagement(
        parent: LinearLayout,
        activity: Activity,
        song: CurrentSongDetails?,
    ) {
        parent.addView(TextView(activity).apply {
            text = "自定义歌词管理"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, dp(activity, 18), 0, dp(activity, 6))
        }, matchWidthWrapContent())

        Button(activity).apply {
            text = "手动新增歌词"
            setOnClickListener { showLyricsEditor(activity, null as CustomLyricsUiGroup?, song) }
            parent.addView(this, matchWidthWrapContent())
        }
        if (song != null) {
            val onlineRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(
                "AMLL" to EmbeddedOnlineSource.AMLL,
                "AM Lyrics" to EmbeddedOnlineSource.AM_LYRICS,
                "网易云" to EmbeddedOnlineSource.NETEASE,
            ).forEach { (label, source) ->
                onlineRow.addView(Button(activity).apply {
                    text = label
                    setOnClickListener {
                        if (source == EmbeddedOnlineSource.NETEASE) {
                            promptNeteaseImport(activity, song)
                        } else {
                            runAsync(activity) {
                                controller.importOnlineLyrics(
                                    source,
                                    song.appleMusicId,
                                    null,
                                    song.title.orEmpty().ifBlank { song.appleMusicId.toString() },
                                )
                            }
                        }
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            parent.addView(onlineRow, matchWidthWrapContent())
        }

        val entries = runCatching(controller::lyricsEntries).getOrDefault(emptyList())
        if (entries.isEmpty()) {
            parent.addView(TextView(activity).apply {
                text = "暂无自定义歌词"
                setTextColor(Color.GRAY)
            }, matchWidthWrapContent())
        }
        entries.forEach { entry ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(activity, 6), 0, dp(activity, 6))
            }
            row.addView(TextView(activity).apply {
                text = "${entry.displayName.ifBlank { entry.appleMusicId.toString() }}  ·  ${entry.appleMusicId}"
                setTextColor(Color.DKGRAY)
            }, matchWidthWrapContent())
            val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(Button(activity).apply {
                text = "编辑"
                setOnClickListener { showLyricsEditor(activity, entry, song) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            actions.addView(Button(activity).apply {
                text = if (entry.enabled) "停用" else "启用"
                setOnClickListener {
                    runAsync(activity) { controller.setLyricsEnabled(entry.appleMusicId, !entry.enabled) }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            actions.addView(Button(activity).apply {
                text = "删除"
                setOnClickListener {
                    AlertDialog.Builder(activity)
                        .setMessage("删除 ${entry.displayName.ifBlank { entry.appleMusicId.toString() }}？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除") { _, _ ->
                            runAsync(activity) { controller.deleteLyrics(entry.appleMusicId) }
                        }
                        .show()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(actions, matchWidthWrapContent())
            parent.addView(row, matchWidthWrapContent())
        }
    }

    private fun showLyricsEditor(
        activity: Activity,
        group: CustomLyricsUiGroup?,
        song: CurrentSongDetails?,
    ) {
        val entry = group?.primary
        var source = entry?.source ?: CustomLyricsSources.MANUAL
        val fields = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), 0, dp(activity, 20), 0)
        }
        val idInput = EditText(activity).apply {
            hint = "Apple Music ID（可用逗号分隔多个 ID）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(
                group?.appleMusicIds?.let(CustomLyricsIdParser::format)
                    ?: song?.appleMusicId?.toString().orEmpty(),
            )
        }
        val nameInput = EditText(activity).apply {
            hint = "显示名称"
            setText(entry?.displayName ?: song?.title.orEmpty())
        }
        val neteaseIdInput = EditText(activity).apply {
            hint = "网易云歌曲 ID（仅网易云导入时需要）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val ttmlInput = EditText(activity).apply {
            hint = "TTML 内容"
            minLines = 8
            gravity = Gravity.TOP
            setText(entry?.let { controller.readLyrics(it.appleMusicId) }.orEmpty())
        }
        val sourceLabel = TextView(activity).apply {
            setTextColor(Color.GRAY)
            setPadding(0, dp(activity, 8), 0, dp(activity, 8))
        }
        fun updateSourceLabel() {
            sourceLabel.text = "当前来源：${embeddedLyricsSourceName(source)}"
        }
        fun importOnline(sourceToImport: EmbeddedOnlineSource) {
            importEmbeddedOnlineLyrics(
                activity = activity,
                source = sourceToImport,
                appleMusicIdInput = idInput,
                neteaseSongIdInput = neteaseIdInput,
                displayNameInput = nameInput,
                ttmlInput = ttmlInput,
            ) { importedSource ->
                source = importedSource
                updateSourceLabel()
            }
        }
        updateSourceLabel()
        fields.addView(idInput, matchWidthWrapContent())
        fields.addView(nameInput, matchWidthWrapContent())
        fields.addView(neteaseIdInput, matchWidthWrapContent())
        fields.addView(sourceLabel, matchWidthWrapContent())
        fields.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(embeddedActionButton(activity, "导入 TTML") {
                pendingTtmlImport = { imported ->
                    ttmlInput.setText(imported)
                    source = CustomLyricsSources.MANUAL
                    updateSourceLabel()
                }
                launchSafPicker(activity, EmbeddedSafOperation.Ttml, "application/xml", arrayOf(
                    "application/ttml+xml",
                    "application/xml",
                    "text/xml",
                    "text/plain",
                ))
            }, LinearLayout.LayoutParams(0, dp(activity, 44), 1f))
            addView(embeddedActionSpacer(activity))
            addView(embeddedActionButton(activity, "获取 ID") {
                requestCurrentSongId(activity, idInput, nameInput)
            }, LinearLayout.LayoutParams(0, dp(activity, 44), 1f))
        }, matchWidthWrapContent())
        fields.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(embeddedActionButton(activity, "从 AMLL 导入") {
                importOnline(EmbeddedOnlineSource.AMLL)
            }, LinearLayout.LayoutParams(0, dp(activity, 44), 1f))
            addView(embeddedActionSpacer(activity))
            addView(embeddedActionButton(activity, "从网易云导入") {
                importOnline(EmbeddedOnlineSource.NETEASE)
            }, LinearLayout.LayoutParams(0, dp(activity, 44), 1f))
            addView(embeddedActionSpacer(activity))
            addView(embeddedActionButton(activity, "从 GitHub 导入") {
                importOnline(EmbeddedOnlineSource.AM_LYRICS)
            }, LinearLayout.LayoutParams(0, dp(activity, 44), 1f))
        }, matchWidthWrapContent())
        fields.addView(ttmlInput, matchWidthWrapContent())
        AlertDialog.Builder(activity)
            .setTitle(if (group == null) "新增歌词" else "编辑歌词")
            .setView(fields)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val ids = CustomLyricsIdParser.parse(idInput.text.toString())
                if (ids == null) {
                    idInput.error = "请输入一个或多个正整数 Apple Music ID（用逗号分隔）"
                } else if (ttmlInput.text.toString().isBlank()) {
                    ttmlInput.error = "请输入或导入 TTML"
                } else {
                    runAsync(activity) {
                        saveMany(
                            CustomLyricsMultiIdDraft(
                                appleMusicIds = ids,
                                displayName = nameInput.text.toString(),
                                ttml = ttmlInput.text.toString(),
                                source = source,
                                enabled = entry?.enabled ?: true,
                            ),
                            group?.appleMusicIds.orEmpty(),
                        )
                    }
                }
            }
            .create().also { dialog ->
                dialog.setOnDismissListener {
                    if (pendingTtmlImport != null) pendingTtmlImport = null
                }
                dialog.show()
            }
    }

    /** Compatibility overload used by the legacy embedded dialog path. */
    private fun showLyricsEditor(
        activity: Activity,
        entry: CustomLyricsEntry?,
        song: CurrentSongDetails?,
    ) = showLyricsEditor(
        activity,
        entry?.let { CustomLyricsUiGroup(listOf(it)) },
        song,
    )

    private fun requestCurrentSongId(
        activity: Activity,
        appleMusicId: EditText,
        displayName: EditText,
    ) {
        val currentSong = controller.currentSongDetails()
        if (currentSong == null) {
            Toast.makeText(
                activity,
                "未获取到当前歌曲信息，请先在 Apple Music 播放一首歌",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        appleMusicId.setText(currentSong.appleMusicId.toString())
        appleMusicId.setSelection(appleMusicId.length())
        listOfNotNull(
            currentSong.title?.takeIf(String::isNotBlank),
            currentSong.artist?.takeIf(String::isNotBlank),
        ).joinToString(" - ").takeIf(String::isNotBlank)?.let { value ->
            displayName.setText(value)
            displayName.setSelection(displayName.length())
        }
        Toast.makeText(activity, "已获取当前歌曲信息", Toast.LENGTH_SHORT).show()
    }

    /** Keeps the editor's multi-ID operation explicit at the host boundary. */
    private fun saveMany(
        draft: CustomLyricsMultiIdDraft,
        replacingAppleMusicIds: List<Long>,
    ): EmbeddedActionResult = controller.saveLyrics(draft, replacingAppleMusicIds)

    private fun importEmbeddedOnlineLyrics(
        activity: Activity,
        source: EmbeddedOnlineSource,
        appleMusicIdInput: EditText,
        neteaseSongIdInput: EditText,
        displayNameInput: EditText,
        ttmlInput: EditText,
        onImported: (String) -> Unit,
    ) {
        val appleMusicId = appleMusicIdInput.text.toString().toLongOrNull()
        val neteaseSongId = neteaseSongIdInput.text.toString().toLongOrNull()
        when (source) {
            EmbeddedOnlineSource.AMLL,
            EmbeddedOnlineSource.AM_LYRICS,
            -> if (appleMusicId == null || appleMusicId <= 0L) {
                appleMusicIdInput.error = "请输入正整数 Apple Music ID"
                return
            }
            EmbeddedOnlineSource.NETEASE -> if (neteaseSongId == null || neteaseSongId <= 0L) {
                neteaseSongIdInput.error = "请输入正整数网易云歌曲 ID"
                return
            }
        }

        val displayName = displayNameInput.text.toString()
        Toast.makeText(activity, "正在获取歌词…", Toast.LENGTH_SHORT).show()
        worker.execute {
            val result = runCatching {
                val importer = embeddedOnlineLyricsImporter()
                when (source) {
                    EmbeddedOnlineSource.AMLL -> importer.importAmll(requireNotNull(appleMusicId))
                    EmbeddedOnlineSource.AM_LYRICS -> importer.importAmLyrics(requireNotNull(appleMusicId))
                    EmbeddedOnlineSource.NETEASE -> importer.importNetease(
                        requireNotNull(neteaseSongId),
                        displayName,
                    )
                }
            }.getOrElse {
                CustomLyricsOnlineImportResult.Failed(
                    it.message.orEmpty().ifBlank { "在线导入失败" },
                )
            }
            mainHandler.post {
                if (currentActivity() !== activity) return@post
                when (result) {
                    is CustomLyricsOnlineImportResult.Imported -> {
                        ttmlInput.setText(result.ttml)
                        onImported(result.source)
                        val reformatNote = if (result.reformatted) "，已自动转为 Apple Music 格式" else ""
                        Toast.makeText(
                            activity,
                            "已导入 ${embeddedLyricsSourceName(result.source)} 歌词$reformatNote，请确认后保存",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    is CustomLyricsOnlineImportResult.Failed -> {
                        Toast.makeText(activity, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun embeddedOnlineLyricsImporter(): CustomLyricsOnlineImporter = CustomLyricsOnlineImporter(
        fetchAmll = AmllTtmlClient(HttpLyricTransport())::fetch,
        fetchAmLyrics = AmLyricsClient(HttpLyricTransport())::fetch,
        fetchNeteaseYrc = NeteaseLyricClient(HttpLyricTransport())::fetchYrc,
    )

    private fun embeddedLyricsSourceName(source: String): String = when (source) {
        CustomLyricsSources.AMLL -> "AMLL"
        CustomLyricsSources.NETEASE -> "网易云 YRC"
        CustomLyricsSources.AM_LYRICS -> "AM-Lyrics 仓库"
        else -> "手动 TTML"
    }

    private fun promptNeteaseImport(activity: Activity, song: CurrentSongDetails) {
        val input = EditText(activity).apply {
            hint = "网易云歌曲 ID"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(activity)
            .setTitle("从网易云导入")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("导入") { _, _ ->
                val neteaseId = input.text.toString().toLongOrNull()
                runAsync(activity) {
                    controller.importOnlineLyrics(
                        EmbeddedOnlineSource.NETEASE,
                        song.appleMusicId,
                        neteaseId,
                        song.title.orEmpty().ifBlank { song.appleMusicId.toString() },
                    )
                }
            }
            .show()
    }

    private fun launchSafPicker(
        activity: Activity,
        operation: EmbeddedSafOperation,
        mimeType: String,
        extraMimeTypes: Array<String>? = null,
    ) {
        val requestCode = safRouter.begin(operation)
        val intent = Intent(
            if (operation == EmbeddedSafOperation.Backup) {
                Intent.ACTION_CREATE_DOCUMENT
            } else {
                Intent.ACTION_OPEN_DOCUMENT
            },
        )
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeType)
        extraMimeTypes?.let { intent.putExtra(Intent.EXTRA_MIME_TYPES, it) }
        if (operation == EmbeddedSafOperation.Backup) {
            intent.putExtra(Intent.EXTRA_TITLE, "AMPP-lyrics-backup.zip")
        }
        runCatching { activity.startActivityForResult(intent, requestCode) }
            .onFailure {
                safRouter.route(requestCode, EmbeddedSafResult.RESULT_CANCELED, null)
                Toast.makeText(activity, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
            }
    }

    private fun handleSafSelection(operation: EmbeddedSafOperation, uri: Uri) {
        val activity = currentActivity() ?: return
        when (operation) {
            EmbeddedSafOperation.Font -> runAsync(activity) { controller.importFont(uri) }
            EmbeddedSafOperation.Ttml -> {
                val editorImport = pendingTtmlImport
                pendingTtmlImport = null
                if (editorImport != null) {
                    worker.execute {
                        val imported = controller.readTtml(uri)
                        mainHandler.post {
                            val current = currentActivity() ?: return@post
                            if (imported == null) {
                                Toast.makeText(
                                    current,
                                    "所选文件不是有效且不超过 512 KiB 的 TTML",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                editorImport(imported)
                                Toast.makeText(current, "TTML 已导入，请确认后保存", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    val song = controller.currentSongDetails()
                    if (song == null) {
                        Toast.makeText(activity, "尚未捕获当前歌曲", Toast.LENGTH_SHORT).show()
                    } else {
                        runAsync(activity) {
                            val replacing = controller.lyricsEntries()
                                .firstOrNull { it.appleMusicId == song.appleMusicId }
                                ?.appleMusicId
                            controller.importTtml(
                                uri,
                                song.appleMusicId,
                                song.title.orEmpty().ifBlank { song.appleMusicId.toString() },
                                replacing,
                            )
                        }
                    }
                }
            }
            EmbeddedSafOperation.Backup -> runAsync(activity) { controller.backupLyrics(uri) }
            EmbeddedSafOperation.RestoreOverwrite -> confirmEmbeddedRestore(activity, uri)
            EmbeddedSafOperation.RestoreKeepExisting -> runAsync(activity) {
                controller.restoreLyrics(uri, CustomLyricsRestorePolicy.KEEP_EXISTING)
            }
        }
    }

    private fun confirmEmbeddedRestore(activity: Activity, uri: Uri) {
        AlertDialog.Builder(activity)
            .setTitle("恢复歌词备份")
            .setMessage("覆盖：冲突歌词使用备份版本；不覆盖：冲突歌词保留当前版本。")
            .setNegativeButton("取消", null)
            .setNeutralButton("不覆盖") { _, _ ->
                runAsync(activity) {
                    controller.restoreLyrics(uri, CustomLyricsRestorePolicy.KEEP_EXISTING)
                }
            }
            .setPositiveButton("覆盖") { _, _ ->
                runAsync(activity) {
                    controller.restoreLyrics(uri, CustomLyricsRestorePolicy.OVERWRITE)
                }
            }
            .show()
    }

    private fun runAsync(activity: Activity, action: () -> EmbeddedActionResult) {
        Toast.makeText(activity, "处理中…", Toast.LENGTH_SHORT).show()
        worker.execute {
            val result = runCatching(action).getOrElse {
                EmbeddedActionResult.Failed(it.message.orEmpty().ifBlank { "操作失败" })
            }
            mainHandler.post {
                val current = currentActivity() ?: return@post
                val message = when (result) {
                    is EmbeddedActionResult.Done -> result.message
                    is EmbeddedActionResult.Failed -> result.message
                }
                Toast.makeText(
                    current,
                    message,
                    if (result is EmbeddedActionResult.Done) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                ).show()
                if (result is EmbeddedActionResult.Done) {
                    pageRefresh?.invoke() ?: dismissDialog()
                }
            }
        }
    }

    private fun removeOverlay(activity: Activity?) {
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

    private fun removeSettingsOption(activity: Activity?) {
        val option = settingsOptionReference?.get()
        if (option != null && (activity == null || belongsToActivity(option, activity))) {
            (option.parent as? ViewGroup)?.removeView(option)
            settingsOptionReference = null
        } else if (option?.parent == null) {
            settingsOptionReference = null
        }
        if (activity != null) {
            findTaggedView(activity, SETTINGS_OPTION_TAG)?.let { tagged ->
                (tagged.parent as? ViewGroup)?.removeView(tagged)
                if (settingsOptionReference?.get() === tagged) settingsOptionReference = null
            }
        }
    }

    private fun removeInjectedViews(activity: Activity?) {
        removeOverlay(activity)
        removeSettingsOption(activity)
    }

    private fun dismissDialog() {
        // A settings host teardown must stop any in-flight native refresh
        // before releasing the progress dialog and its callback closure.
        controller.cancelLibraryRefresh()
        pendingTtmlImport = null
        libraryRefreshDialog?.dismiss()
        libraryRefreshDialog = null
        dialogReference?.get()?.dismiss()
        dialogReference = null
        pageRefresh = null
    }

    private fun currentActivity(): Activity? = activityReference?.get()

    private fun findTaggedView(activity: Activity, tag: String): View? {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val decor = activity.window?.decorView as? ViewGroup
        return listOfNotNull(content, decor)
            .distinct()
            .asSequence()
            .mapNotNull { root -> root.findViewWithTag<View>(tag) }
            .firstOrNull()
    }

    private fun belongsToActivity(view: View, activity: Activity): Boolean {
        val decor = activity.window?.decorView ?: return false
        var current: View? = view
        while (current != null) {
            if (current === decor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun activityKey(activity: Activity): String =
        Integer.toHexString(System.identityHashCode(activity))

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun matchWidthWrapContent(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

    companion object {
        const val PLAYER_ACTIVITY_NAME = "com.apple.android.music.common.activity.PlayerActivity"
        const val MAIN_CONTENT_ACTIVITY_NAME = "com.apple.android.music.common.MainContentActivity"
        const val FLOATING_BUTTON_TAG = "ampp_embedded_settings_button"
        const val SETTINGS_OPTION_TAG = "ampp_embedded_settings_option"
        const val NATIVE_SETTINGS_PREFERENCE_KEY = "ampp_embedded_settings_preference"
        private const val NATIVE_PREFERENCE_FALLBACK_DELAY_MS = 220L

        fun install(
            application: Application,
            controller: EmbeddedSettingsController,
            safRouter: EmbeddedSafResultRouter = EmbeddedSafResultRouter(),
            selectionHandler: EmbeddedSafSelectionHandler = EmbeddedSafSelectionHandler { _, _ -> },
            playerActivityClass: Class<*>? = null,
        ): EmbeddedSettingsHost = EmbeddedSettingsHost(
            application = application,
            controller = controller,
            safRouter = safRouter,
            selectionHandler = selectionHandler,
            activityMatcher = EmbeddedActivityMatcher(playerActivityClass),
        ).also(application::registerActivityLifecycleCallbacks)
    }
}
