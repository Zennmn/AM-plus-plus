package dev.amenhancer.module.hook

import android.util.Log
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.TargetConfigClient
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class HookEntry : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        ModernXposedRuntime.attach(this)
        log(
            Log.INFO,
            "AppleMusicEnhancer",
            "loaded in ${param.processName}; framework=$frameworkName API=$apiVersion",
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != ModuleConstants.TARGET_PACKAGE || !param.isFirstPackage) return
        ModernXposedRuntime.attach(this)
        if (apiVersion < 102 || frameworkProperties.and(PROP_CAP_REMOTE) == 0L) {
            ModernXposedRuntime.log(
                "framework API $apiVersion does not provide API 102 remote preferences; hooks remain disabled",
            )
            return
        }
        val config = TargetConfigClient(
            getRemotePreferences(ModuleConstants.REMOTE_PREFERENCES_GROUP),
            remoteFileOpener = { name -> openRemoteFile(name) },
        )

        FeatureInstallation.install(config, param.classLoader)
    }
}
