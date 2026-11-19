package dev.amenhancer.module.hook

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import dev.amenhancer.host.classical.ClassicalHostProfile
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.font.FontFilePolicy
import dev.amenhancer.module.font.FontLoadRetryPolicy
import dev.amenhancer.module.model.FeatureHealth
import dev.amenhancer.module.model.FeatureState
import dev.amenhancer.module.model.LyricsFontManifest
import java.io.FileInputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Replaces Classical's Compose typography font resolution (nn.n) using the verified
 * user-selected font file from [LyricsFontManifest], with background verification and fail-open recovery.
 */
internal class ClassicalFontTarget(
    private val classLoader: ClassLoader,
    private val profile: ClassicalHostProfile,
) : LyricsTypefaceTarget, LyricsTypefaceResourceBinding {
    private val lock = Any()
    private val scope = HookRegistrationScope()
    private val loadedTypeface = AtomicReference<Typeface?>(null)
    private val observedResolvers = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val weightedFamilyCache = HashMap<Int, Any>()
    private val loadStarted = AtomicBoolean(false)
    private val backgroundExecutor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ampp-classical-font").apply { isDaemon = true }
        }
    }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var config: TargetConfigClient? = null

    @Volatile
    private var active = false

    @Volatile
    private var failureMessage: String? = null

    private var installedResult: TargetCapabilityInstall? = null

    override fun registerResources(config: TargetConfigClient) {
        synchronized(lock) { this.config = config }
    }

    @Synchronized
    override fun install(): TargetCapabilityInstall {
        installedResult?.let { return it }
        return runCatching { installOnce() }
            .getOrElse { error ->
                scope.close()
                TargetCapabilityInstall.Degraded("Classical font hook failed: ${error.javaClass.simpleName}: ${error.message}")
            }
            .also { installedResult = it }
    }

    private fun installOnce(): TargetCapabilityInstall {
        if (!profile.productionEnabled || !profile.capability("font")) {
            return TargetCapabilityInstall.Unsupported("Classical build has no verified font contract")
        }
        val activeConfig = config ?: return TargetCapabilityInstall.Degraded("Font configuration unavailable")
        val manifest = activeConfig.settings().fontManifest
        if (!manifest.enabled) {
            return TargetCapabilityInstall.Degraded("Font override disabled")
        }

        val fontContract = profile.document.getJSONObject("font")
        val resolverClass = classLoader.loadClass(fontContract.getString("resolverClass"))
        val resolveMethod = resolverClass.getDeclaredMethod(
            fontContract.getString("resolveMethod"),
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val weightMapField: Field = resolverClass.getDeclaredField(fontContract.getString("weightMapField"))
            .apply { isAccessible = true }
        val cacheMapField: Field = resolverClass.getDeclaredField(fontContract.getString("cacheMapField"))
            .apply { isAccessible = true }
        val weightClass = classLoader.loadClass(fontContract.getString("weightClass"))
        val weightIntField: Field = weightClass.getDeclaredField(fontContract.getString("weightIntField"))
            .apply { isAccessible = true }
        val loadedFamilyClass = classLoader.loadClass(fontContract.getString("loadedFontFamilyClass"))
        val wrapperClass = classLoader.loadClass(fontContract.getString("typefaceWrapperClass"))
        val wrapperCtor: Constructor<*> = wrapperClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Any::class.java)
            .apply { isAccessible = true }
        val loadedFamilyCtor: Constructor<*> = loadedFamilyClass.getDeclaredConstructor(wrapperClass)
            .apply { isAccessible = true }
        val wrapperMode = fontContract.getInt("typefaceWrapperMode")

        check(ModernXposedRuntime.hookMethod(resolveMethod, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!scope.isActive || !active) return
                val resolver = param.thisObject ?: return
                synchronized(observedResolvers) { observedResolvers[resolver] = true }
                val baseTypeface = loadedTypeface.get() ?: return
                val fontResId = param.args.getOrNull(0) as? Int ?: return
                val weight = resolveWeight(resolver, fontResId, weightMapField, weightIntField)
                val family = synchronized(weightedFamilyCache) {
                    weightedFamilyCache.getOrPut(weight) {
                        val weighted = Typeface.create(baseTypeface, weight, false)
                        val wrapper = wrapperCtor.newInstance(wrapperMode, weighted)
                        loadedFamilyCtor.newInstance(wrapper)
                    }
                }
                param.result = family
            }
        }, scope)) { "Classical font resolver hook failed" }

        scope.activate()
        active = true
        if (loadStarted.compareAndSet(false, true)) {
            startBackgroundLoad(activeConfig, manifest, cacheMapField)
        }
        return if (loadedTypeface.get() != null) {
            TargetCapabilityInstall.Active("Classical custom font loaded (${manifest.displayName})")
        } else {
            TargetCapabilityInstall.Degraded("Classical font hook active; loading ${manifest.displayName} in background")
        }
    }

    private fun resolveWeight(
        resolver: Any,
        fontResId: Int,
        weightMapField: Field,
        weightIntField: Field,
    ): Int = runCatching {
        val map = weightMapField.get(resolver) as? Map<*, *>
        val weightObj = map?.get(fontResId)
        if (weightObj != null) weightIntField.getInt(weightObj).coerceIn(1, 1000) else DEFAULT_WEIGHT
    }.getOrDefault(DEFAULT_WEIGHT)

    private fun startBackgroundLoad(
        client: TargetConfigClient,
        manifest: LyricsFontManifest,
        cacheMapField: Field,
    ) {
        backgroundExecutor.execute {
            var attempt = 0
            while (true) {
                val result = loadAndVerify(client, manifest)
                when (result) {
                    is LoadOutcome.Ready -> {
                        loadedTypeface.set(result.typeface)
                        synchronized(weightedFamilyCache) { weightedFamilyCache.clear() }
                        ModernXposedRuntime.log("Classical custom font loaded: ${manifest.displayName}")
                        mainHandler.post {
                            clearResolverCaches(cacheMapField)
                            config?.reportHealth(
                                FeatureHealth(
                                    ModuleConstants.FEATURE_LYRICS_TYPEFACE,
                                    FeatureState.ACTIVE,
                                    "自定义字体已应用：${manifest.displayName}",
                                    profile.versionName,
                                ),
                            )
                        }
                        return@execute
                    }
                    is LoadOutcome.Permanent -> {
                        failureMessage = result.message
                        reportFailure(result.message)
                        return@execute
                    }
                    is LoadOutcome.Transient -> {
                        if (!FontLoadRetryPolicy.shouldRetry(attempt, transient = true)) {
                            failureMessage = result.message
                            reportFailure(result.message)
                            return@execute
                        }
                        runCatching { Thread.sleep(FontLoadRetryPolicy.backoffMillis(attempt)) }
                        attempt++
                    }
                }
            }
        }
    }

    private fun clearResolverCaches(cacheMapField: Field) {
        val resolvers = synchronized(observedResolvers) { observedResolvers.keys.toList() }
        resolvers.forEach { resolver ->
            runCatching {
                (cacheMapField.get(resolver) as? MutableMap<*, *>)?.clear()
            }
        }
    }

    private fun reportFailure(message: String) {
        ModernXposedRuntime.log("Classical custom font disabled: $message")
        mainHandler.post {
            config?.reportHealth(
                FeatureHealth(
                    ModuleConstants.FEATURE_LYRICS_TYPEFACE,
                    FeatureState.FAILED,
                    "保留原字体：$message",
                    profile.versionName,
                ),
            )
        }
    }

    private fun loadAndVerify(client: TargetConfigClient, manifest: LyricsFontManifest): LoadOutcome {
        val pfd = client.openFileDescriptor(manifest.fileId)
            ?: return LoadOutcome.Transient("Font file descriptor unavailable")
        return try {
            val fd = pfd.fileDescriptor
            Os.lseek(fd, 0L, OsConstants.SEEK_SET)
            val bytes = FileInputStream(fd).readBytes()
            if (manifest.sizeBytes > 0 && bytes.size.toLong() != manifest.sizeBytes) {
                return LoadOutcome.Permanent("Font byte size mismatch")
            }
            if (manifest.sha256.isNotBlank() && !FontFilePolicy.sha256(bytes).equals(manifest.sha256, ignoreCase = true)) {
                return LoadOutcome.Permanent("Font checksum mismatch")
            }
            Os.lseek(fd, 0L, OsConstants.SEEK_SET)
            val parsed = runCatching { Typeface.Builder(fd).build() }.getOrNull()
                ?: return LoadOutcome.Permanent("Typeface.Builder could not parse font")
            LoadOutcome.Ready(parsed)
        } catch (error: Throwable) {
            LoadOutcome.Transient("Font read error: ${error.javaClass.simpleName}")
        } finally {
            runCatching { pfd.close() }
        }
    }

    private sealed interface LoadOutcome {
        data class Ready(val typeface: Typeface) : LoadOutcome
        data class Permanent(val message: String) : LoadOutcome
        data class Transient(val message: String) : LoadOutcome
    }

    private companion object {
        const val DEFAULT_WEIGHT = 400
    }
}
