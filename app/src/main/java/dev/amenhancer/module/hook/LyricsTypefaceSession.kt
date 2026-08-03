package dev.amenhancer.module.hook

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.font.FontFilePolicy
import dev.amenhancer.module.font.FontLoadRetryPolicy
import dev.amenhancer.module.model.LyricsFontManifest
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The verified Apple Music layout names that may contain player lyric text. */
internal object LyricsTypefaceLayoutContract {
    const val INSTRUMENTAL_LAYOUT_NAME = "lyrics_line_instrumental"

    val layoutNames: List<String> = listOf(
        "lyrics_line",
        "lyrics_line_karaoke",
        "lyrics_line_static",
        "lyrics_translation_line_karaoke",
        "lyrics_word_karaoke",
        "lyrics_word_pronunciation",
        "lyrics_pronunciation_mixed_rtl_ltr",
        "lyrics_pronunciation_vertical_group",
        "lyrics_bg_translation_line_karaoke",
        "lyrics_word_karaoke_bg",
        "lyrics_word_pronunciation_bg",
        INSTRUMENTAL_LAYOUT_NAME,
    )
}

internal sealed interface LyricsTypefacePreparation {
    data object Ready : LyricsTypefacePreparation
    data object Loading : LyricsTypefacePreparation
    data class Failed(val message: String) : LyricsTypefacePreparation
    data object Disabled : LyricsTypefacePreparation
}

/**
 * Shares the lazy remote-file verification and Typeface instance across both
 * hook phases.
 *
 * Threading model:
 * - [registerResources] runs on the resource thread and only registers
 *   layout-inflation callbacks; it never touches the remote file.
 * - [prepare] runs on the main thread (Application.onCreate install). It
 *   decides enabled/disabled and then starts a one-shot background load on a
 *   single daemon thread; the up-to-16 MiB read, SHA-256 and Typeface parse
 *   never run on the main thread.
 * - The background load transitions [LyricsTypefaceLoadController] under its
 *   own lock and, on success, posts a main-thread re-application of every
 *   lyric root/recycler observed while loading.
 * - Apply paths run on the main thread; they read the controller's value
 *   under the same lock and no-op while it is not ready, which keeps the
 *   original font on any failure (fail-open).
 */
internal class LyricsTypefaceSession {
    private val lock = Any()
    private val loadController = LyricsTypefaceLoadController<Typeface>()
    private val observedRecyclers = Collections.synchronizedMap(WeakHashMap<ViewGroup, Boolean>())
    private val pendingRoots = WeakHashMap<View, Boolean>()
    private val observedRowRoots = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val delayedApplyGate = DelayedApplyGate()
    private val styleTypefaceCache = HashMap<Int, Typeface>()
    private val failedTypefaceStyles = HashSet<Int>()

    private var config: TargetConfigClient? = null
    private var resourcesRegistered = false

    @Volatile
    private var active = false

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val backgroundExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ampp-lyrics-font-load").apply { isDaemon = true }
        }
    }

    fun registerResources(config: TargetConfigClient) {
        synchronized(lock) {
            this.config = config
            if (resourcesRegistered) return
            resourcesRegistered = true
        }
        LyricsTypefaceLayoutContract.layoutNames.forEach { layoutName ->
            LayoutInflationRegistry.register(layoutName) { root ->
                if (layoutName == LyricsTypefaceLayoutContract.INSTRUMENTAL_LAYOUT_NAME) {
                    InstrumentalRowIdentity.mark(root)
                }
                applyToLyricsLayout(root)
                installRowLayoutChangeObserver(root)
            }
        }
    }

    /**
     * Returns immediately: the remote file is read, verified and parsed on a
     * background thread. The target capability can install while [Loading].
     */
    fun prepare(): LyricsTypefacePreparation = synchronized(lock) {
        when (loadController.phase()) {
            LyricsTypefaceLoadController.Phase.READY -> LyricsTypefacePreparation.Ready
            LyricsTypefaceLoadController.Phase.LOADING -> LyricsTypefacePreparation.Loading
            LyricsTypefaceLoadController.Phase.FAILED -> LyricsTypefacePreparation.Failed(
                loadController.failureMessage().orEmpty(),
            )
            LyricsTypefaceLoadController.Phase.IDLE -> {
                val activeConfig = config
                    ?: return@synchronized LyricsTypefacePreparation.Failed(
                        "Lyrics font configuration was unavailable",
                    )
                val manifest = activeConfig.settings().fontManifest
                if (!manifest.enabled) return@synchronized LyricsTypefacePreparation.Disabled
                loadController.start()
                startBackgroundLoad(activeConfig, manifest)
                LyricsTypefacePreparation.Loading
            }
        }
    }

    /** Marks the feature installed; applies stay gated on a ready load. */
    fun activate() {
        active = true
    }

    fun attachToFragment(fragment: Any, recyclerClass: Class<*>) {
        if (!active) return
        val recycler = runCatching {
            ModernXposedRuntime.callMethod(fragment, "getRecyclerView") as? ViewGroup
        }.getOrNull() ?: return
        if (!recyclerClass.isInstance(recycler)) return
        applyToLyricsLayout(recycler)
        installChildAttachObserver(recycler)
        mainHandler.post { applyToLyricsLayout(recycler) }
    }

    private fun startBackgroundLoad(config: TargetConfigClient, manifest: LyricsFontManifest) {
        backgroundExecutor.execute {
            var attempt = 0
            while (true) {
                val outcome = runCatching { loadOnce(config, manifest) }
                    .getOrElse { error ->
                        LyricsFontLoadOutcome.Transient("Unexpected font load failure: $error")
                    }
                when (outcome) {
                    is LyricsFontLoadOutcome.Ready -> {
                        loadController.succeed(outcome.importedBase)
                        ModernXposedRuntime.log(
                            "Lyrics font ${manifest.displayName} loaded in attempt ${attempt + 1}",
                        )
                        scheduleReapplyPendingRoots()
                        return@execute
                    }
                    is LyricsFontLoadOutcome.Permanent -> {
                        finishFailed(outcome.message)
                        return@execute
                    }
                    is LyricsFontLoadOutcome.Transient -> {
                        if (!FontLoadRetryPolicy.shouldRetry(attempt, transient = true)) {
                            finishFailed(outcome.message)
                            return@execute
                        }
                        Thread.sleep(FontLoadRetryPolicy.backoffMillis(attempt))
                        attempt += 1
                    }
                }
            }
        }
    }

    private fun finishFailed(message: String) {
        loadController.fail(message)
        // Fail-open: the original font stays in place; the diagnostic is logged.
        ModernXposedRuntime.log("Lyrics font left at the original typeface: $message")
    }

    private fun loadOnce(
        config: TargetConfigClient,
        manifest: LyricsFontManifest,
    ): LyricsFontLoadOutcome {
        val descriptor = config.openRemoteFile(manifest.fileId)
            ?: return LyricsFontLoadOutcome.Transient("Remote font file could not be opened")
        return try {
            when (val result = verify(descriptor, manifest)) {
                is VerifyResult.Permanent -> return LyricsFontLoadOutcome.Permanent(result.message)
                is VerifyResult.Unreadable -> return LyricsFontLoadOutcome.Transient(result.message)
                VerifyResult.Ok -> Unit
            }
            try {
                Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_SET)
            } catch (_: Throwable) {
                return LyricsFontLoadOutcome.Transient("Remote font file could not be rewound")
            }
            val importedBase = try {
                Typeface.Builder(descriptor.fileDescriptor).build()
            } catch (_: Throwable) {
                return LyricsFontLoadOutcome.Permanent(
                    "Android Typeface.Builder could not parse the remote font",
                )
            }
            if (importedBase == null) {
                return LyricsFontLoadOutcome.Permanent(
                    "Android Typeface.Builder returned no typeface",
                )
            }
            LyricsFontLoadOutcome.Ready(importedBase)
        } finally {
            runCatching { descriptor.close() }
        }
    }

    /** Re-applies every lyric root observed while the load was still running. */
    private fun scheduleReapplyPendingRoots() {
        val roots = synchronized(lock) {
            val list = pendingRoots.keys.toList()
            pendingRoots.clear()
            list
        }
        if (roots.isEmpty()) return
        mainHandler.post {
            roots.forEach(::applyToLyricsLayout)
        }
    }

    private sealed interface LyricsFontLoadOutcome {
        data class Ready(val importedBase: Typeface) : LyricsFontLoadOutcome
        data class Permanent(val message: String) : LyricsFontLoadOutcome
        data class Transient(val message: String) : LyricsFontLoadOutcome
    }

    private sealed interface VerifyResult {
        data object Ok : VerifyResult
        data class Permanent(val message: String) : VerifyResult
        data class Unreadable(val message: String) : VerifyResult
    }

    private fun verify(
        descriptor: ParcelFileDescriptor,
        manifest: LyricsFontManifest,
    ): VerifyResult {
        val duplicate = try {
            ParcelFileDescriptor.dup(descriptor.fileDescriptor)
        } catch (_: Throwable) {
            return VerifyResult.Unreadable(
                "Remote font file could not be duplicated for verification",
            )
        }

        return try {
            ParcelFileDescriptor.AutoCloseInputStream(duplicate).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val header = ByteArray(4)
                var headerSize = 0
                var size = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (headerSize < header.size) {
                        val copied = minOf(count, header.size - headerSize)
                        buffer.copyInto(header, headerSize, 0, copied)
                        headerSize += copied
                    }
                    size += count
                    if (size > FontFilePolicy.MAX_FONT_SIZE_BYTES) {
                        return@use VerifyResult.Permanent("Remote font exceeds 16 MiB")
                    }
                    digest.update(buffer, 0, count)
                }
                when {
                    !FontFilePolicy.hasSupportedSfntMagic(header) ->
                        VerifyResult.Permanent("Remote font has an unsupported SFNT signature")
                    size != manifest.sizeBytes ->
                        VerifyResult.Permanent("Remote font size did not match its manifest")
                    hex(digest.digest()) != manifest.sha256.lowercase() ->
                        VerifyResult.Permanent("Remote font hash did not match its manifest")
                    else -> VerifyResult.Ok
                }
            }
        } catch (_: Throwable) {
            VerifyResult.Unreadable("Remote font could not be read for verification")
        }
    }

    private fun applyToLyricsLayout(root: View) {
        synchronized(lock) {
            val importedBase = loadController.readyValue()
            if (importedBase != null) {
                applyTypefaceTree(root, importedBase)
                return
            }
            // The load is still in flight: remember the root so the completed
            // load can re-apply it on the main thread.
            if (active && loadController.phase() == LyricsTypefaceLoadController.Phase.LOADING) {
                pendingRoots[root] = true
            }
        }
    }

    private fun applyTypefaceTree(root: View, importedBase: Typeface) {
        val textViews = mutableListOf<TextView>()
        collectTextViews(root, textViews)
        val replacements = ArrayList<Pair<TextView, Typeface>>(textViews.size)
        textViews.forEach { view ->
            val originalStyle = view.typeface?.style ?: Typeface.NORMAL
            val replacement = styleTypeface(importedBase, originalStyle) ?: return@forEach
            // Already the target instance: skip the redundant assignment.
            if (view.typeface === replacement) return@forEach
            replacements += view to replacement
        }
        replacements.forEach { (view, replacement) -> view.typeface = replacement }
    }

    /** One Typeface per style for the imported base; failures skip only that view. */
    private fun styleTypeface(importedBase: Typeface, style: Int): Typeface? = synchronized(lock) {
        styleTypefaceCache[style]?.let { return@synchronized it }
        if (failedTypefaceStyles.contains(style)) return@synchronized null
        val created = runCatching { Typeface.create(importedBase, style) }.getOrNull()
        if (created == null) {
            failedTypefaceStyles += style
            null
        } else {
            styleTypefaceCache[style] = created
            created
        }
    }

    private fun collectTextViews(view: View, target: MutableList<TextView>) {
        if (view is TextView) target += view
        (view as? ViewGroup)?.let { group ->
            repeat(group.childCount) { index -> collectTextViews(group.getChildAt(index), target) }
        }
    }

    private fun installChildAttachObserver(recycler: ViewGroup) {
        synchronized(observedRecyclers) {
            if (observedRecyclers.containsKey(recycler)) return
            observedRecyclers[recycler] = true
        }
        val listenerClass = runCatching {
            Class.forName(
                "androidx.recyclerview.widget.RecyclerView\$OnChildAttachStateChangeListener",
                false,
                recycler.javaClass.classLoader,
            )
        }.getOrNull() ?: return
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "onChildViewAttachedToWindow" -> {
                    (args?.firstOrNull() as? View)?.let(::applyToLyricsLayout)
                    scheduleDelayedApply(recycler)
                    null
                }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "AppleMusicEnhancerLyricsTypefaceListener"
                else -> null
            }
        }
        val listener = runCatching {
            Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass), handler)
        }.getOrNull() ?: return
        runCatching {
            recycler.javaClass
                .getMethod("addOnChildAttachStateChangeListener", listenerClass)
                .invoke(recycler, listener)
        }
    }

    /**
     * At most one delayed whole-recycler re-apply per recycler at any time:
     * further child attaches merge into the pending runnable instead of
     * stacking more. Runs on the main handler so a detached recycler can
     * never stall the gate.
     */
    private fun scheduleDelayedApply(recycler: ViewGroup) {
        if (!delayedApplyGate.tryAcquire(recycler)) return
        mainHandler.postDelayed({
            delayedApplyGate.release(recycler)
            applyToLyricsLayout(recycler)
        }, DELAYED_REAPPLY_MILLIS)
    }

    /**
     * Covers in-place rebinds that reuse a lyrics row without inflating it:
     * a rebind that changes text metrics triggers a layout pass, which
     * re-applies the row (merged through the gate). This deliberately does
     * not hook the TextView typeface setter globally.
     */
    private fun installRowLayoutChangeObserver(row: View) {
        synchronized(observedRowRoots) {
            if (observedRowRoots.containsKey(row)) return
            observedRowRoots[row] = true
        }
        row.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (!delayedApplyGate.tryAcquire(view)) return@addOnLayoutChangeListener
            mainHandler.post {
                delayedApplyGate.release(view)
                applyToLyricsLayout(view)
            }
        }
    }

    private fun hex(bytes: ByteArray): String = bytes
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val DELAYED_REAPPLY_MILLIS = 80L
    }
}
