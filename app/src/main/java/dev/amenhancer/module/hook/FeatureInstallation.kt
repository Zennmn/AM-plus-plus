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
    private val lyricsTypefaceSession by lazy(AppleMusicHostFactory::newTypefaceResources)
    private val module by lazy { productionFeatureInstallationModule(lyricsTypefaceSession) }

    /**
     * Registers resource and layout callbacks without requiring an Application
     * instance. Embedded callers invoke this from the Application onCreate
     * before-hook so LayoutInflater hooks are live before the host body runs.
     */
    fun registerResources(config: TargetConfigClient) {
        module.registerResources(config)
    }

    fun install(
        config: TargetConfigClient,
        targetClassLoader: ClassLoader,
    ) {
        module.install(config, targetClassLoader)
    }

    fun installEmbedded(
        config: TargetConfigClient,
        application: Application,
        targetClassLoader: ClassLoader,
        currentSong: CurrentSongIdentityCache,
    ) {
        module.installNow(config) {
            HookContext(
                config = config,
                target = assembleAppleMusicTarget(
                    config = config,
                    application = application,
                    classLoader = targetClassLoader,
                    lyricsTypefaceSession = lyricsTypefaceSession,
                    currentSong = currentSong,
                ),
            )
        }
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
    /** Resource callbacks are an at-most-once stage, including failed attempts. */
    private var resourceRegistrationAttempted = false
    private var resourceRegistrationFailure: Throwable? = null

    fun registerResources(config: TargetConfigClient) = synchronized(this) {
        registerResourcesIfNeeded(config)
    }

    fun install(
        config: TargetConfigClient,
        targetClassLoader: ClassLoader,
    ): FeatureInstallationSession = synchronized(this) {
        activeSession?.let { return@synchronized it }

        registerResourcesIfNeeded(config)
        val session = newSession()
        registerApplicationCreated(config, targetClassLoader, session::install)
        activeSession = session
        session
    }

    fun installNow(
        config: TargetConfigClient,
        contextFactory: () -> HookContext,
    ): FeatureInstallationSession = synchronized(this) {
        activeSession?.let { return@synchronized it }

        registerResourcesIfNeeded(config)
        val session = newSession()
        session.install(contextFactory)
        activeSession = session
        session
    }

    private fun registerResourcesIfNeeded(config: TargetConfigClient) {
        resourceRegistrationFailure?.let { throw it }
        if (resourceRegistrationAttempted) return
        resourceRegistrationAttempted = true
        try {
            plans.forEach { plan -> plan.registerResources(config) }
            installLayoutInflationHooks()
        } catch (error: Throwable) {
            resourceRegistrationFailure = error
            throw error
        }
    }

    private fun newSession(): FeatureInstallationSession = FeatureInstallationSession(
        features = plans.map(FeatureInstallationPlan::feature),
        reportHealth = reportHealth,
        reportError = reportError,
    )
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
        if (installed.get()) return
        val context = contextFactory()
        if (!installed.compareAndSet(false, true)) return
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


private fun productionFeatureInstallationModule(
    lyricsTypefaceSession: LyricsTypefaceResourceBinding,
): FeatureInstallationModule {
    // The same session is used by resource callbacks registered before
    // Application.onCreate and by lifecycle hooks installed afterwards.
    // It owns the one lazy remote-file open and Typeface build.
    return FeatureInstallationModule(
        plans = listOf(
            FeatureInstallationPlan(feature = PlayerRecoveryFeature()),
            FeatureInstallationPlan(
                feature = DualPaneFeature(),
                registerResources = { AppleMusicHostFactory.registerDualPaneResources() },
            ),
            FeatureInstallationPlan(feature = EditorialVideoFeature()),
            FeatureInstallationPlan(feature = CellularDataEntryFeature()),
            FeatureInstallationPlan(feature = StartupPagePrewarmFeature()),
            FeatureInstallationPlan(
                feature = PhoneLiquidGlassFeature(),
                registerResources = PhoneLiquidGlassResourceHook::install,
            ),
            FeatureInstallationPlan(
                feature = FutureLyricBlurFeature(),
                registerResources = { AppleMusicHostFactory.registerLyricAuxiliaryResources() },
            ),
            FeatureInstallationPlan(feature = CjkKaraokeAnimationFeature()),
            FeatureInstallationPlan(feature = LyricWrapSweepFeature()),
            FeatureInstallationPlan(feature = LyricBackgroundHighlightFeature()),
            FeatureInstallationPlan(
                feature = LyricsTypefaceFeature(),
                registerResources = lyricsTypefaceSession::registerResources,
            ),
            FeatureInstallationPlan(feature = CurrentSongIdentityFeature()),
            FeatureInstallationPlan(feature = TitleCorrectionFeature()),
            FeatureInstallationPlan(feature = LyricsParserFeature()),
            FeatureInstallationPlan(feature = CustomLyricsFeature()),
            // The actual resource hook is installed during the Application
            // before-hook; this plan records its final health alongside the
            // regular post-Application features.
            FeatureInstallationPlan(feature = AppleMusicDpiOverrideFeature()),
        ),
        installLayoutInflationHooks = AppleMusicHostFactory::installLayoutCallbacks,
        registerApplicationCreated = { config, targetClassLoader, onCreated ->
            val onCreate = Application::class.java.getDeclaredMethod("onCreate")
            ModernXposedRuntime.hookMethod(onCreate, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val application = param.thisObject as Application
                    onCreated {
                        HookContext(
                            config = config,
                            target = assembleAppleMusicTarget(
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
