package dev.amenhancer.module.hook

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.util.DisplayMetrics
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.model.ModuleSettings
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure arithmetic used by the per-process density override.  Android's
 * resource manager expects the dp qualifiers to describe the physical display
 * after a density change, so the dimensions are scaled in the opposite
 * direction of the requested density.
 */
internal object AppleMusicDpiOverridePolicy {
    const val FOLLOW_SYSTEM_DPI = ModuleSettings.FOLLOW_SYSTEM_APPLE_MUSIC_DPI
    const val MIN_DPI = ModuleSettings.MIN_APPLE_MUSIC_DPI
    const val MAX_DPI = ModuleSettings.MAX_APPLE_MUSIC_DPI
    // Hidden in the public Android SDK; kept equal to Configuration's
    // SCREENLAYOUT_COMPAT_NEEDED bit used by ResourcesImpl.
    const val SCREENLAYOUT_COMPAT_NEEDED = 0x10000000

    fun isValidDpi(value: Int): Boolean =
        value == FOLLOW_SYSTEM_DPI || value in MIN_DPI..MAX_DPI

    fun normalizeDpi(value: Int): Int = value.takeIf(::isValidDpi) ?: FOLLOW_SYSTEM_DPI

    fun scaleDp(value: Int, sourceDpi: Int, targetDpi: Int): Int {
        if (value <= 0 || sourceDpi <= 0 || targetDpi <= 0) return value
        return (value.toLong() * sourceDpi / targetDpi)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    /**
     * Recomputes only the size/long/compat bits.  Layout direction, roundness,
     * and any future screen-layout bits are preserved from the host config.
     */
    fun recomputeScreenLayout(original: Int, widthDp: Int, heightDp: Int): Int {
        if (widthDp <= 0 || heightDp <= 0) return original

        val longSizeDp = maxOf(widthDp, heightDp)
        val shortSizeDp = minOf(widthDp, heightDp)
        val dynamicMask = Configuration.SCREENLAYOUT_SIZE_MASK or
            Configuration.SCREENLAYOUT_LONG_MASK or
            SCREENLAYOUT_COMPAT_NEEDED
        val preserved = original and dynamicMask.inv()

        val size = when {
            longSizeDp < 470 -> Configuration.SCREENLAYOUT_SIZE_SMALL
            longSizeDp >= 960 && shortSizeDp >= 720 -> Configuration.SCREENLAYOUT_SIZE_XLARGE
            longSizeDp >= 640 && shortSizeDp >= 480 -> Configuration.SCREENLAYOUT_SIZE_LARGE
            else -> Configuration.SCREENLAYOUT_SIZE_NORMAL
        }
        // Configuration.reduceScreenLayout() treats very small displays as
        // non-long and does not mark them compat-needed, regardless of aspect
        // ratio. Keep that special case before applying the normal heuristic.
        val isLong = longSizeDp >= 470 &&
            (longSizeDp * 3) / 5 >= shortSizeDp - 1
        val compatNeeded = longSizeDp >= 470 && (shortSizeDp > 321 || longSizeDp > 570)

        val longFlag = if (isLong) {
            Configuration.SCREENLAYOUT_LONG_YES
        } else {
            Configuration.SCREENLAYOUT_LONG_NO
        }
        val compatFlag = if (compatNeeded) SCREENLAYOUT_COMPAT_NEEDED else 0
        return preserved or size or longFlag or compatFlag
    }

    /**
     * Applies a fixed density to mutable Android snapshots.  The caller owns
     * the snapshots, so this method never mutates a live Resources object.
     */
    fun apply(
        configuration: Configuration,
        metrics: DisplayMetrics,
        targetDpi: Int,
    ): Boolean {
        if (!isValidDpi(targetDpi) || targetDpi == FOLLOW_SYSTEM_DPI) return false

        val sourceDpi = configuration.densityDpi
            .takeIf { it > 0 }
            ?: metrics.densityDpi.takeIf { it > 0 }
            ?: DisplayMetrics.DENSITY_DEFAULT
        val nextWidthDp = scaleDp(configuration.screenWidthDp, sourceDpi, targetDpi)
        val nextHeightDp = scaleDp(configuration.screenHeightDp, sourceDpi, targetDpi)
        val nextSmallestWidthDp = scaleDp(
            configuration.smallestScreenWidthDp,
            sourceDpi,
            targetDpi,
        )
        val nextScreenLayout = recomputeScreenLayout(
            original = configuration.screenLayout,
            widthDp = nextWidthDp,
            heightDp = nextHeightDp,
        )
        val nextDensity = targetDpi / DisplayMetrics.DENSITY_DEFAULT.toFloat()
        val fontScale = configuration.fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f

        val changed = configuration.densityDpi != targetDpi ||
            configuration.screenWidthDp != nextWidthDp ||
            configuration.screenHeightDp != nextHeightDp ||
            configuration.smallestScreenWidthDp != nextSmallestWidthDp ||
            configuration.screenLayout != nextScreenLayout ||
            metrics.densityDpi != targetDpi ||
            metrics.density != nextDensity

        configuration.densityDpi = targetDpi
        configuration.screenWidthDp = nextWidthDp
        configuration.screenHeightDp = nextHeightDp
        configuration.smallestScreenWidthDp = nextSmallestWidthDp
        configuration.screenLayout = nextScreenLayout
        metrics.densityDpi = targetDpi
        metrics.density = nextDensity
        metrics.scaledDensity = nextDensity * fontScale
        return changed
    }
}

internal enum class AppleMusicDpiOverrideState {
    DISABLED,
    ACTIVE,
    DEGRADED,
}

internal data class AppleMusicDpiOverrideStatus(
    val state: AppleMusicDpiOverrideState,
    val targetDpi: Int,
    val message: String,
) {
    fun asFeatureResult(): FeatureInstallResult = when (state) {
        AppleMusicDpiOverrideState.DISABLED -> FeatureInstallResult.disabled(message)
        AppleMusicDpiOverrideState.ACTIVE -> FeatureInstallResult.active(message)
        AppleMusicDpiOverrideState.DEGRADED -> FeatureInstallResult.degraded(message)
    }
}

/**
 * Installs the target-process-only density override at the earliest existing
 * seam (Application.onCreate before-hook).  The runtime deliberately freezes
 * the selected DPI for the process; a settings write therefore takes effect
 * only after a complete Apple Music restart.
 */
internal object AppleMusicDpiOverrideRuntime : Application.ActivityLifecycleCallbacks,
    ComponentCallbacks {
    private val installationAttempted = AtomicBoolean(false)
    private val resourceHookInstalled = AtomicBoolean(false)
    // Hooks registered through libxposed cannot be reliably removed.  Keep a
    // separate activation gate so a partially failed install becomes inert.
    private val runtimeEnabled = AtomicBoolean(false)
    private val activityCallbacksRegistered = AtomicBoolean(false)
    private val componentCallbacksRegistered = AtomicBoolean(false)
    private val trackedResources = Collections.synchronizedMap(WeakHashMap<Resources, Boolean>())
    private val reentrancy = ThreadLocal<Boolean>()

    @Volatile
    private var targetDpi: Int = AppleMusicDpiOverridePolicy.FOLLOW_SYSTEM_DPI

    @Volatile
    private var applicationReference: WeakReference<Application>? = null

    @Volatile
    var status: AppleMusicDpiOverrideStatus = AppleMusicDpiOverrideStatus(
        state = AppleMusicDpiOverrideState.DISABLED,
        targetDpi = AppleMusicDpiOverridePolicy.FOLLOW_SYSTEM_DPI,
        message = "未启用 Apple Music DPI 覆盖",
    )
        private set

    fun install(application: Application, configuredDpi: Int): AppleMusicDpiOverrideStatus {
        if (!installationAttempted.compareAndSet(false, true)) return status

        val normalized = AppleMusicDpiOverridePolicy.normalizeDpi(configuredDpi)
        runtimeEnabled.set(false)
        targetDpi = normalized
        applicationReference = WeakReference(application)

        if (configuredDpi != normalized) {
            status = AppleMusicDpiOverrideStatus(
                state = AppleMusicDpiOverrideState.DEGRADED,
                targetDpi = AppleMusicDpiOverridePolicy.FOLLOW_SYSTEM_DPI,
                message = "DPI 配置无效，已按跟随系统处理（允许 0 或 160–640）",
            )
            ModernXposedRuntime.log("invalid Apple Music DPI=$configuredDpi; fail-open")
            return status
        }
        if (normalized == AppleMusicDpiOverridePolicy.FOLLOW_SYSTEM_DPI) {
            status = AppleMusicDpiOverrideStatus(
                state = AppleMusicDpiOverrideState.DISABLED,
                targetDpi = normalized,
                message = "跟随系统 DPI",
            )
            return status
        }

        return runCatching {
            installResourceHook()
            application.registerActivityLifecycleCallbacks(this)
            activityCallbacksRegistered.set(true)
            application.registerComponentCallbacks(this)
            componentCallbacksRegistered.set(true)
            if (!applyToResources(application.resources, allowInactive = true)) {
                error("initial Apple Music DPI resource update failed")
            }
            // Do not expose the hooks to later callbacks until every
            // registration and the initial resource update has succeeded.
            runtimeEnabled.set(true)
            status = AppleMusicDpiOverrideStatus(
                state = AppleMusicDpiOverrideState.ACTIVE,
                targetDpi = normalized,
                message = "已启用 ${normalized} dpi（完全重启后重新读取）",
            )
            ModernXposedRuntime.log("Apple Music DPI override active: $normalized")
            status
        }.getOrElse { error ->
            deactivateAfterInstallFailure(application)
            status = AppleMusicDpiOverrideStatus(
                state = AppleMusicDpiOverrideState.DEGRADED,
                targetDpi = AppleMusicDpiOverridePolicy.FOLLOW_SYSTEM_DPI,
                message = "DPI 覆盖安装失败，已停用覆盖",
            )
            ModernXposedRuntime.log("Apple Music DPI override failed open", error)
            status
        }
    }

    /**
     * Make all hooks/callbacks inert after an install failure.  Modern Xposed
     * does not expose an unhook handle here, so deactivation must be explicit
     * and happen before the failure status is published.
     */
    private fun deactivateAfterInstallFailure(application: Application) {
        runtimeEnabled.set(false)
        targetDpi = AppleMusicDpiOverridePolicy.FOLLOW_SYSTEM_DPI
        applicationReference = null
        synchronized(trackedResources) {
            trackedResources.clear()
        }

        if (componentCallbacksRegistered.compareAndSet(true, false)) {
            runCatching { application.unregisterComponentCallbacks(this) }
                .onFailure { error ->
                    ModernXposedRuntime.log(
                        "Apple Music DPI component callback cleanup failed",
                        error,
                    )
                }
        }
        if (activityCallbacksRegistered.compareAndSet(true, false)) {
            runCatching { application.unregisterActivityLifecycleCallbacks(this) }
                .onFailure { error ->
                    ModernXposedRuntime.log(
                        "Apple Music DPI activity callback cleanup failed",
                        error,
                    )
                }
        }
    }

    private fun installResourceHook() {
        if (!resourceHookInstalled.compareAndSet(false, true)) return
        val updateConfiguration = Resources::class.java.getDeclaredMethod(
            "updateConfiguration",
            Configuration::class.java,
            DisplayMetrics::class.java,
        )
        ModernXposedRuntime.hookMethod(updateConfiguration, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!runtimeEnabled.get() || reentrancy.get() == true) return
                val resources = param.thisObject as? Resources ?: return
                if (resources === Resources.getSystem()) return
                transformUpdateArguments(param, resources)
            }
        })

        // This base hook covers API 26–28, where ActivityLifecycleCallbacks
        // does not expose the pre-created callback used on newer Android.
        val activityOnCreate = Activity::class.java.getDeclaredMethod(
            "onCreate",
            Bundle::class.java,
        )
        ModernXposedRuntime.hookMethod(activityOnCreate, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.application?.packageName != ModuleConstants.TARGET_PACKAGE) return
                applyToResources(activity.resources)
            }
        })

        // ResourcesManager updates an existing Activity's ResourcesImpl
        // directly for split-screen/display overrides, then dispatches this
        // callback.  That path bypasses Resources.updateConfiguration and the
        // Application ComponentCallbacks registered below, so reapply here
        // before the host Activity callback runs.
        val activityOnConfigurationChanged = Activity::class.java.getDeclaredMethod(
            "onConfigurationChanged",
            Configuration::class.java,
        )
        ModernXposedRuntime.hookMethod(
            activityOnConfigurationChanged,
            object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!runtimeEnabled.get()) return
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.application?.packageName != ModuleConstants.TARGET_PACKAGE) return
                    applyToResources(activity.resources)
                }
            },
        )
    }

    private fun transformUpdateArguments(param: ModernMethodHook.MethodHookParam, resources: Resources) {
        if (!runtimeEnabled.get()) return
        val sourceConfiguration = runCatching {
            (param.args.getOrNull(0) as? Configuration)?.let(::Configuration)
                ?: Configuration(resources.configuration)
        }.getOrNull() ?: return
        val sourceMetrics = runCatching {
            (param.args.getOrNull(1) as? DisplayMetrics)?.let {
                DisplayMetrics().also { copy -> copy.setTo(it) }
            } ?: DisplayMetrics().also { it.setTo(resources.displayMetrics) }
        }.getOrNull() ?: return
        if (!AppleMusicDpiOverridePolicy.apply(sourceConfiguration, sourceMetrics, targetDpi)) return
        param.args[0] = sourceConfiguration
        param.args[1] = sourceMetrics
    }

    private fun applyToResources(resources: Resources, allowInactive: Boolean = false): Boolean {
        if ((!allowInactive && !runtimeEnabled.get()) ||
            targetDpi == AppleMusicDpiOverridePolicy.FOLLOW_SYSTEM_DPI ||
            resources === Resources.getSystem() ||
            reentrancy.get() == true
        ) {
            return false
        }
        trackedResources[resources] = true
        val configuration = runCatching { Configuration(resources.configuration) }.getOrNull()
            ?: return false
        val metrics = runCatching {
            DisplayMetrics().also { it.setTo(resources.displayMetrics) }
        }.getOrNull() ?: return false
        if (!AppleMusicDpiOverridePolicy.apply(configuration, metrics, targetDpi)) return true

        reentrancy.set(true)
        try {
            @Suppress("DEPRECATION")
            resources.updateConfiguration(configuration, metrics)
            return true
        } catch (error: Throwable) {
            ModernXposedRuntime.log("Apple Music DPI resource update failed open", error)
            return false
        } finally {
            reentrancy.remove()
        }
    }

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (runtimeEnabled.get() &&
            activity.application?.packageName == ModuleConstants.TARGET_PACKAGE
        ) {
            applyToResources(activity.resources)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (runtimeEnabled.get() &&
            activity.application?.packageName == ModuleConstants.TARGET_PACKAGE
        ) {
            applyToResources(activity.resources)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (!runtimeEnabled.get()) return
        val application = applicationReference?.get() ?: return
        applyToResources(application.resources)
        val resources = synchronized(trackedResources) { trackedResources.keys.toList() }
        resources.forEach(::applyToResources)
    }

    override fun onLowMemory() = Unit

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

internal class AppleMusicDpiOverrideFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_APPLE_MUSIC_DPI

    override fun install(context: HookContext): FeatureInstallResult =
        AppleMusicDpiOverrideRuntime.status.asFeatureResult()
}
