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
            runCatching {
                if (!feature.isEnabled(context)) {
                    context.report(feature.key, FeatureState.DISABLED, "Disabled in module settings")
                } else {
                    feature.install(context)
                }
            }.onFailure { error ->
                ModernXposedRuntime.log("${feature.key} failed", error)
                context.report(feature.key, FeatureState.FAILED, error.shortMessage())
            }
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
    fun report(feature: String, state: FeatureState, message: String) {
        config.reportHealth(FeatureHealth(feature, state, message, targetVersion))
    }
}

private fun Throwable.shortMessage(): String = buildString {
    append(javaClass.simpleName)
    message?.takeIf(String::isNotBlank)?.let { append(": ").append(it.take(180)) }
}

internal interface FeatureHook {
    val key: String
    fun isEnabled(context: HookContext): Boolean
    fun install(context: HookContext)
}
