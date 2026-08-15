package dev.amenhancer.module.hook

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.FeatureHealth
import dev.amenhancer.module.model.FeatureState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Complete feature-installation module used by the libxposed entry point.
 *
 * Resource-time registration and application-time feature installation are
 * deliberately kept behind this single seam. Callers do not need to know the
 * required order, lifecycle split, failure isolation, or health reporting.
 */
internal object FeatureInstallation {
    private val module by lazy(::productionFeatureInstallationModule)

    fun install(
        config: TargetConfigClient,
        targetClassLoader: ClassLoader,
    ) {
        module.install(config, targetClassLoader)
    }
}
internal class FeatureInstallationModule(
    private val plans: List<FeatureInstallationPlan>,
    private val installLayoutInflationHooks: () -> Unit,
    private val registerApplicationCreated: (
        TargetConfigClient,
        ClassLoader,
        (() -> HookContext) -> Unit,
    ) -> Unit,
    private val reportHealth: (HookContext, FeatureHealth) -> Unit,
    private val reportError: (String, Throwable) -> Unit,
) {
    @Volatile
    private var activeSession: FeatureInstallationSession? = null

    fun install(
        config: TargetConfigClient,
        targetClassLoader: ClassLoader,
    ): FeatureInstallationSession = synchronized(this) {
        activeSession?.let { return@synchronized it }

        plans.forEach { plan -> plan.registerResources(config) }
        installLayoutInflationHooks()

        val session = FeatureInstallationSession(
            features = plans.map(FeatureInstallationPlan::feature),
            reportHealth = reportHealth,
            reportError = reportError,
        )
        registerApplicationCreated(config, targetClassLoader, session::install)
        activeSession = session
        session
    }
}

internal data class FeatureInstallationPlan(
    val feature: FeatureHook,
    val registerResources: (TargetConfigClient) -> Unit = {},
)

internal enum class FeatureInstallationPhase {
    RESOURCES_REGISTERED,
    FEATURES_INSTALLING,
    COMPLETE,
}

internal data class FeatureInstallationSnapshot(
    val phase: FeatureInstallationPhase,
    val health: List<FeatureHealth>,
)

internal class FeatureInstallationSession(
    private val features: List<FeatureHook>,
    private val reportHealth: (HookContext, FeatureHealth) -> Unit,
    private val reportError: (String, Throwable) -> Unit,
) {
    private val installed = AtomicBoolean(false)
    private val snapshot = AtomicReference(
        FeatureInstallationSnapshot(
            phase = FeatureInstallationPhase.RESOURCES_REGISTERED,
            health = emptyList(),
        ),
    )

    fun snapshot(): FeatureInstallationSnapshot = snapshot.get()

    fun install(contextFactory: () -> HookContext) {
        if (!installed.compareAndSet(false, true)) return
        val context = contextFactory()
        val installedHealth = mutableListOf<FeatureHealth>()
        snapshot.set(
            FeatureInstallationSnapshot(
                phase = FeatureInstallationPhase.FEATURES_INSTALLING,
                health = emptyList(),
            ),
        )

        features.forEach { feature ->
            val result = feature.installSafely(context, reportError)
            val health = FeatureHealth(
                feature = feature.key,
                state = result.state,
                message = result.message,
                targetVersion = context.target.identity,
            )
            reportHealth(context, health)
            installedHealth += health
            snapshot.set(
                FeatureInstallationSnapshot(
                    phase = FeatureInstallationPhase.FEATURES_INSTALLING,
                    health = installedHealth.toList(),
                ),
            )
        }

        snapshot.set(
            FeatureInstallationSnapshot(
                phase = FeatureInstallationPhase.COMPLETE,
                health = installedHealth.toList(),
            ),
        )
    }
}

internal data class HookContext(
    val config: TargetConfigClient,
    val target: TargetAdaptation,
)

internal interface FeatureHook {
    val key: String
    fun install(context: HookContext): FeatureInstallResult
}

internal fun FeatureHook.installSafely(
    context: HookContext,
    reportError: (String, Throwable) -> Unit = ModernXposedRuntime::log,
): FeatureInstallResult = runCatching { install(context) }
    .getOrElse { error ->
        reportError("$key failed", error)
        FeatureInstallResult.failed(error.shortMessage())
    }

internal class FeatureInstallResult private constructor(
    val state: FeatureState,
    val message: String,
) {
    init {
        require(message.isNotBlank()) { "Feature install diagnostic must not be blank" }
    }

    companion object {
        fun active(message: String): FeatureInstallResult =
            FeatureInstallResult(FeatureState.ACTIVE, message)

        fun disabled(message: String = "Disabled in module settings"): FeatureInstallResult =
            FeatureInstallResult(FeatureState.DISABLED, message)

        fun unsupported(message: String): FeatureInstallResult =
            FeatureInstallResult(FeatureState.UNSUPPORTED, message)

        fun degraded(message: String): FeatureInstallResult =
            FeatureInstallResult(FeatureState.DEGRADED, message)

        fun failed(message: String): FeatureInstallResult =
            FeatureInstallResult(FeatureState.FAILED, message)
    }
}

internal fun targetBuild(context: Context): TargetBuild = runCatching {
    val packageInfo = context.packageManager.getPackageInfo(ModuleConstants.TARGET_PACKAGE, 0)
    val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
    }
    TargetBuild(
        packageName = ModuleConstants.TARGET_PACKAGE,
        versionName = packageInfo.versionName.orEmpty(),
        versionCode = versionCode,
    )
}.getOrDefault(TargetBuild.UNKNOWN)

private fun productionFeatureInstallationModule(): FeatureInstallationModule {
    // The same session is used by resource callbacks registered before
    // Application.onCreate and by lifecycle hooks installed afterwards.
    // It owns the one lazy remote-file open and Typeface build.
    val lyricsTypefaceSession = LyricsTypefaceSession()
    return FeatureInstallationModule(
        plans = listOf(
            FeatureInstallationPlan(
                feature = DualPaneFeature(),
                registerResources = { DualPaneResourceHook.install() },
            ),
            FeatureInstallationPlan(feature = EditorialVideoFeature()),
            FeatureInstallationPlan(
                feature = PhoneLiquidGlassFeature(),
                registerResources = PhoneLiquidGlassResourceHook::install,
            ),
            FeatureInstallationPlan(
                feature = FutureLyricBlurFeature(),
                registerResources = { LyricCreditsRowResourceHook.install() },
            ),
            FeatureInstallationPlan(
                feature = LyricsTypefaceFeature(),
                registerResources = lyricsTypefaceSession::registerResources,
            ),
            FeatureInstallationPlan(feature = CurrentSongIdentityFeature()),
            FeatureInstallationPlan(feature = LibraryRefreshFeature()),
            FeatureInstallationPlan(feature = TitleCorrectionFeature()),
            FeatureInstallationPlan(feature = CatalogLanguageFeature()),
            FeatureInstallationPlan(feature = CustomLyricsFeature()),
        ),
        installLayoutInflationHooks = LayoutInflationRegistry::install,
        registerApplicationCreated = { config, targetClassLoader, onCreated ->
            val onCreate = Application::class.java.getDeclaredMethod("onCreate")
            ModernXposedRuntime.hookMethod(onCreate, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val application = param.thisObject as Application
                    onCreated {
                        HookContext(
                            config = config,
                            target = TargetAdaptation.appleMusic(
                                config = config,
                                application = application,
                                classLoader = targetClassLoader,
                                lyricsTypefaceSession = lyricsTypefaceSession,
                            ),
                        )
                    }
                }
            })
        },
        reportHealth = { context, health -> context.config.reportHealth(health) },
        reportError = ModernXposedRuntime::log,
    )
}

private fun Throwable.shortMessage(): String = buildString {
    append(javaClass.simpleName.ifBlank { javaClass.name })
    message?.takeIf(String::isNotBlank)?.let { append(": ").append(it.take(180)) }
}
