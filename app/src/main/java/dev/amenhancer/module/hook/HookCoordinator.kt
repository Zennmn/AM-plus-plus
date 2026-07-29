package dev.amenhancer.module.hook

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.FeatureHealth
import dev.amenhancer.module.model.FeatureState
import java.util.concurrent.atomic.AtomicBoolean

internal object HookCoordinator {
    private val installed = AtomicBoolean(false)

    fun install(application: Application, classLoader: ClassLoader, config: TargetConfigClient) {
        if (!installed.compareAndSet(false, true)) return

        val targetBuild = targetBuild(application)
        val symbols = IndexedTargetSymbolResolver(
            build = targetBuild,
            source = ApkTargetClassSource(application, classLoader),
        )
        val context = HookContext(application, classLoader, config, symbols, targetBuild.displayName)

        listOf(
            DualPaneFeature(),
            EditorialVideoFeature(),
            PhoneLiquidGlassFeature(),
            FutureLyricBlurFeature(),
        ).forEach { feature ->
            val result = runCatching { feature.install(context) }
                .getOrElse { error ->
                    ModernXposedRuntime.log("${feature.key} failed", error)
                    FeatureInstallResult.failed(error.shortMessage())
                }
            context.report(feature.key, result)
        }
    }

    private fun targetBuild(context: Context): TargetBuild = runCatching {
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
}

internal data class HookContext(
    val application: Application,
    val classLoader: ClassLoader,
    val config: TargetConfigClient,
    val symbols: TargetSymbolResolver,
    val targetVersion: String,
) {
    fun report(feature: String, result: FeatureInstallResult) {
        config.reportHealth(FeatureHealth(feature, result.state, result.message, targetVersion))
    }
}

private fun Throwable.shortMessage(): String = buildString {
    append(javaClass.simpleName.ifBlank { javaClass.name })
    message?.takeIf(String::isNotBlank)?.let { append(": ").append(it.take(180)) }
}

internal interface FeatureHook {
    val key: String
    fun install(context: HookContext): FeatureInstallResult
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
