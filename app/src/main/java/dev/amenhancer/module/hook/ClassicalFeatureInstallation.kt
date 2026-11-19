package dev.amenhancer.module.hook

import android.app.Application
import dev.amenhancer.module.config.TargetConfigClient

/** Separate installation module for Apple Music Classical so AM structural regression order stays untouched. */
internal object ClassicalFeatureInstallation {
    @Volatile
    private var typefaceBinding: LyricsTypefaceResourceBinding? = null

    fun registerResources(
        config: TargetConfigClient,
        application: Application,
        classLoader: ClassLoader,
    ) {
        val binding = typefaceBinding
            ?: ClassicalHostFactory.newTypefaceResources(application, classLoader)?.also { typefaceBinding = it }
        binding?.registerResources(config)
    }

    fun installEmbedded(
        config: TargetConfigClient,
        application: Application,
        targetClassLoader: ClassLoader,
    ) {
        val typeface = typefaceBinding
            ?: checkNotNull(ClassicalHostFactory.newTypefaceResources(application, targetClassLoader)).also {
                it.registerResources(config)
                typefaceBinding = it
            }
        val classicalModule = FeatureInstallationModule(
            plans = listOf(
                FeatureInstallationPlan(feature = DualPaneFeature()),
                FeatureInstallationPlan(feature = ClassicalGuideTranslationFeature()),
                FeatureInstallationPlan(feature = LyricsTypefaceFeature()),
                FeatureInstallationPlan(feature = ClassicalLiquidGlassFeature(application, targetClassLoader)),
                FeatureInstallationPlan(feature = AppleMusicDpiOverrideFeature()),
            ),
            installLayoutInflationHooks = {},
            reportHealth = { context, health -> context.config.reportHealth(health) },
            reportError = ModernXposedRuntime::log,
        )
        classicalModule.installNow(config) {
            HookContext(
                config = config,
                target = ClassicalHostFactory.classical(config, application, targetClassLoader, typeface),
            )
        }
    }
}
