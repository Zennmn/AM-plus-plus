package dev.amenhancer.module.hook

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.EmbeddedConfigurationSession
import dev.amenhancer.module.config.HostPrivateEmbeddedStorage
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.ui.EmbeddedRuntimeSettingsController
import dev.amenhancer.module.ui.EmbeddedSettingsHost
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

internal object EmbeddedSettingsFragmentMethodResolver {
    fun findOnResume(type: Class<*>): Method? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredMethod("onResume") }
                .getOrNull()
                ?.let { return it }
            current = current.superclass
        }
        return runCatching { type.getMethod("onResume") }.getOrNull()
    }

    fun findOnCreateView(type: Class<*>): Method? = findInherited(
        type,
        "onCreateView",
        LayoutInflater::class.java,
        ViewGroup::class.java,
        Bundle::class.java,
    )

    fun findOnViewCreated(type: Class<*>): Method? = findInherited(
        type,
        "onViewCreated",
        View::class.java,
        Bundle::class.java,
    )

    private fun findInherited(
        type: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>,
    ): Method? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredMethod(name, *parameterTypes) }
                .getOrNull()
                ?.let { return it }
            current = current.superclass
        }
        return runCatching { type.getMethod(name, *parameterTypes) }.getOrNull()
    }
}

class HookEntry : XposedModule() {
    private val bootstrap = EmbeddedBootstrap()
    private val resultBridgeInstalled = AtomicBoolean(false)
    private val settingsFragmentHookInstalled = AtomicBoolean(false)
    private val applicationHooksInstalled = AtomicBoolean(false)
    private val initializationStarted = AtomicBoolean(false)

    @Volatile
    private var settingsHost: EmbeddedSettingsHost? = null

    @Volatile
    private var processName: String = ""

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        ModernXposedRuntime.attach(this)
        processName = param.processName
        log(
            Log.INFO,
            "AppleMusicEnhancer",
            "loaded in ${param.processName}; framework=$frameworkName API=$apiVersion",
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (apiVersion < 102) {
            ModernXposedRuntime.log("framework API $apiVersion is below the embedded API 102 minimum")
            return
        }
        if (!bootstrap.prepare(param.packageName, processName, param.isFirstPackage)) return
        ModernXposedRuntime.attach(this)
        val targetClassLoader = param.classLoader
        installApplicationBootstrap(param.applicationInfo.className, targetClassLoader)
    }

    private fun installApplicationBootstrap(
        applicationClassName: String?,
        targetClassLoader: ClassLoader,
    ) {
        if (!applicationHooksInstalled.compareAndSet(false, true)) return
        val applicationClass = applicationClassName
            ?.takeIf(String::isNotBlank)
            ?.let { className ->
                runCatching { targetClassLoader.loadClass(className) }
                    .getOrNull()
                    ?.takeIf { Application::class.java.isAssignableFrom(it) }
            }
        val methods = linkedSetOf<Method>()
        listOfNotNull(applicationClass, Application::class.java).forEach { type ->
            runCatching { type.getDeclaredMethod("onCreate") }
                .getOrNull()
                ?.let(methods::add)
        }
        methods.forEach { onCreate ->
            ModernXposedRuntime.hookMethod(onCreate, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching {
                    val application = param.thisObject as? Application ?: return
                    if (application.packageName != ModuleConstants.TARGET_PACKAGE) return
                    if (!isTargetMainProcess(application)) return
                    val build = targetBuild(application)
                    if (!bootstrap.supports(build)) {
                        ModernXposedRuntime.log(
                            "embedded build ${build.displayName} is unsupported; expected 6.5.1 (1583)",
                        )
                        return
                    }
                    val session = EmbeddedConfigurationSession(HostPrivateEmbeddedStorage(application))
                    if (!bootstrap.bind(build, session)) return
                    if (!initializationStarted.compareAndSet(false, true)) return
                    val config = TargetConfigClient(bootstrap.reader)
                    val currentSong = CurrentSongIdentityCache()
                    runCatching {
                        FeatureInstallation.installEmbedded(
                            config,
                            application,
                            targetClassLoader,
                            currentSong,
                        )
                    }.onFailure { error ->
                        ModernXposedRuntime.log("embedded feature installation failed open", error)
                    }
                    val playerActivityClass = runCatching {
                        targetClassLoader.loadClass(EmbeddedSettingsHost.PLAYER_ACTIVITY_NAME)
                    }.getOrNull()
                    val host = EmbeddedSettingsHost.install(
                        application,
                        EmbeddedRuntimeSettingsController(
                            application,
                            session,
                            currentSong = { currentSong.current()?.details },
                        ),
                        playerActivityClass = playerActivityClass,
                    )
                    settingsHost = host
                    installActivityResultBridge(targetClassLoader, host)
                    installSettingsFragmentHook(targetClassLoader, host)
                }.onFailure { error ->
                    ModernXposedRuntime.log("embedded initialization failed open: $error")
                }
            }
            })
        }
    }

    private fun installSettingsFragmentHook(
        targetClassLoader: ClassLoader,
        host: EmbeddedSettingsHost,
    ) {
        if (settingsFragmentHookInstalled.get()) return
        val settingsFragmentClass = runCatching {
            targetClassLoader.loadClass(SETTINGS_FRAGMENT_NAME)
        }.getOrNull() ?: return
        val methods = listOfNotNull(
            EmbeddedSettingsFragmentMethodResolver.findOnResume(settingsFragmentClass),
            EmbeddedSettingsFragmentMethodResolver.findOnCreateView(settingsFragmentClass),
            EmbeddedSettingsFragmentMethodResolver.findOnViewCreated(settingsFragmentClass),
        ).distinctBy(Method::toGenericString)
        if (methods.isEmpty()) return
        if (!settingsFragmentHookInstalled.compareAndSet(false, true)) return
        var hooked = false
        methods.forEach { method ->
            val installed = runCatching {
                ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val fragment = param.thisObject ?: return
                        if (!settingsFragmentClass.isInstance(fragment)) return
                        val activity = runCatching {
                            ModernXposedRuntime.callMethod(fragment, "getActivity") as? Activity
                        }.getOrNull() ?: return
                        when (method.name) {
                            "onCreateView" -> host.onSettingsFragmentViewCreated(
                                fragment,
                                activity,
                                param.result as? View,
                            )
                            "onViewCreated" -> host.onSettingsFragmentViewCreated(
                                fragment,
                                activity,
                                param.args.getOrNull(0) as? View,
                            )
                            else -> host.onSettingsFragmentResumed(fragment, activity)
                        }
                    }
                })
            }.onFailure { error ->
                ModernXposedRuntime.log("settings fragment hook failed for ${method.name}: $error")
            }.getOrDefault(false)
            hooked = hooked || installed
        }
        if (!hooked) settingsFragmentHookInstalled.set(false)
    }

    private fun isTargetMainProcess(application: Application): Boolean =
        currentProcessName(application) == ModuleConstants.TARGET_PACKAGE

    private fun currentProcessName(application: Application): String? {
        val applicationProcessName = runCatching {
            Application::class.java.getDeclaredMethod("getProcessName")
                .invoke(null) as? String
        }.getOrNull()
        val activityThreadProcessName = runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentProcessName")
                .invoke(null) as? String
        }.getOrNull()
        return applicationProcessName?.takeIf(String::isNotBlank)
            ?: activityThreadProcessName?.takeIf(String::isNotBlank)
            ?: application.applicationInfo.processName?.takeIf(String::isNotBlank)
            ?: application.packageName
    }

    private fun installActivityResultBridge(
        targetClassLoader: ClassLoader,
        host: EmbeddedSettingsHost,
    ) {
        if (!resultBridgeInstalled.compareAndSet(false, true)) return
        val signature = arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
        val methods = buildList {
            runCatching {
                targetClassLoader.loadClass(PLAYER_ACTIVITY_NAME).getDeclaredMethod(
                    "onActivityResult",
                    *signature,
                )
            }.getOrNull()?.let(::add)
            runCatching {
                Activity::class.java.getDeclaredMethod("onActivityResult", *signature)
            }.getOrNull()?.let(::add)
        }.distinctBy(Method::toGenericString)
        methods.forEach { method ->
            ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val requestCode = param.args.getOrNull(0) as? Int ?: return
                    val resultCode = param.args.getOrNull(1) as? Int ?: return
                    val data = param.args.getOrNull(2) as? Intent
                    val activity = param.thisObject as? Activity ?: return
                    // Observe only. Never modify result/throwable or short-circuit Apple Music.
                    host.onActivityResult(activity, requestCode, resultCode, data)
                }
            })
        }
    }

    private companion object {
        const val PLAYER_ACTIVITY_NAME = "com.apple.android.music.common.activity.PlayerActivity"
        const val SETTINGS_FRAGMENT_NAME = "com.apple.android.music.settings.fragment.SettingsFragment"
    }
}
