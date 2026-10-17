package dev.amenhancer.module.hook

import dev.amenhancer.module.i18n.ModuleText

import android.app.Application
import android.util.Log
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.EmbeddedConfigurationMigration
import dev.amenhancer.module.config.EmbeddedConfigurationMigrationResult
import dev.amenhancer.module.config.EmbeddedConfigurationSession
import dev.amenhancer.module.config.HostPrivateEmbeddedStorage
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.settings.EmbeddedRuntimeSettingsController
import dev.amenhancer.module.ui.EmbeddedSettingsHost
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.service.XposedService
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

class HookEntry : XposedModule() {
    private val bootstrap = EmbeddedBootstrap()
    private val applicationHooksInstalled = AtomicBoolean(false)
    private val resourcePreparationStarted = AtomicBoolean(false)
    private val resourcePreparationFailed = AtomicBoolean(false)
    private val initializationStarted = AtomicBoolean(false)

    @Volatile
    private var settingsHost: EmbeddedSettingsHost? = null

    @Volatile
    private var embeddedConfig: TargetConfigClient? = null

    @Volatile
    private var embeddedSession: EmbeddedConfigurationSession? = null

    @Volatile
    private var processName: String = ""

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        ModernXposedRuntime.attach(this)
        val moduleInfo = runCatching { getModuleApplicationInfo() }.getOrNull()
        dev.amenhancer.host.applemusic.AppleMusicHostProfiles.initializeModulePaths(
            listOfNotNull(moduleInfo?.sourceDir, moduleInfo?.publicSourceDir) + moduleInfo?.splitSourceDirs.orEmpty(),
        )
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
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val application = param.thisObject as? Application ?: return
                        if (application.packageName != ModuleConstants.TARGET_PACKAGE) return
                        if (!isTargetMainProcess(application)) return
                        val build = targetBuild(application)
                        if (!bootstrap.supports(build)) {
                            ModernXposedRuntime.log(
                                "embedded build ${build.displayName} is has no verified production profile",
                            )
                            return
                        }
                        if (embeddedConfig != null) return
                        if (resourcePreparationFailed.get()) return
                        if (!resourcePreparationStarted.compareAndSet(false, true)) return
                        try {
                            val storage = HostPrivateEmbeddedStorage(application)
                            val migrationDeferred = migrateRemoteConfiguration(storage) is
                                EmbeddedConfigurationMigrationResult.Failed
                            if (migrationDeferred) {
                                // Keep the host runtime fail-open when the legacy
                                // source is temporarily unavailable.  The session
                                // remains read-only so settings cannot mask an
                                // in-progress migration; a fresh process retries it.
                                ModernXposedRuntime.log(
                                    "embedded configuration migration deferred; continuing read-only",
                                )
                            }
                            val session = EmbeddedConfigurationSession(
                                storage = storage,
                                writable = !migrationDeferred,
                            )
                            if (!bootstrap.bind(build, session)) {
                                resourcePreparationStarted.set(false)
                                return
                            }
                            val config = TargetConfigClient(bootstrap.reader) { health ->
                                ModernXposedRuntime.log("${health.feature}: ${health.state} - ${health.message} [${health.targetVersion}]")
                            }
                            AppleMusicHostFactory.installDensity(
                                application = application,
                                configuredDpi = config.settings().appleMusicDpiOverrideDpi,
                            )
                            runCatching { FeatureInstallation.registerResources(config) }
                                .getOrElse { error ->
                                    resourcePreparationFailed.set(true)
                                    throw error
                                }
                            // Publish only after resources are fully registered.
                            // If a callback throws, the current process stays
                            // fail-open and a fresh process can retry cleanly.
                            embeddedSession = session
                            embeddedConfig = config
                        } catch (error: Throwable) {
                            resourcePreparationStarted.set(false)
                            throw error
                        }
                    }.onFailure { error ->
                        ModernXposedRuntime.log("embedded resource preparation failed open: $error")
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val application = param.thisObject as? Application ?: return
                        if (application.packageName != ModuleConstants.TARGET_PACKAGE) return
                        if (!isTargetMainProcess(application)) return
                        val config = embeddedConfig ?: return
                        if (!initializationStarted.compareAndSet(false, true)) return
                        val session = embeddedSession ?: return
                        GlassDiagLog.bindFile(application.filesDir)
                        GlassDiagLog.i("application ready; ${application.packageName} sdk=${android.os.Build.VERSION.SDK_INT}")
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
                            activityMatcher = AppleMusicHostFactory.settingsActivityMatcher(application, playerActivityClass),
                            nativeBridgeFactory = { onOpen -> AppleMusicHostFactory.settingsViewBridge(application, onOpen) },
                        )
                        settingsHost = host
                        AppleMusicHostFactory.installSettingsEntry(application, targetClassLoader, host)
                        runCatching {
                            val plugins = dev.amenhancer.plugin.runtime.PluginManager(application, targetClassLoader)
                            host.plugins = plugins
                            plugins.start()
                        }.onFailure { ModernXposedRuntime.log("plugin runtime unavailable", it) }
                    }.onFailure { error ->
                        ModernXposedRuntime.log("embedded initialization failed open: $error")
                    }
                }
            })
        }
    }

    private fun migrateRemoteConfiguration(
        storage: HostPrivateEmbeddedStorage,
    ): EmbeddedConfigurationMigrationResult? {
        if (EmbeddedConfigurationMigration.destinationAlreadyInitialized(storage)) return null
        if (frameworkProperties.and(XposedService.PROP_CAP_REMOTE) == 0L) return null
        val remotePreferences = runCatching {
            getRemotePreferences(ModuleConstants.REMOTE_PREFERENCES_GROUP)
        }.getOrElse { error ->
            ModernXposedRuntime.log("embedded remote configuration unavailable; migration deferred", error)
            return EmbeddedConfigurationMigrationResult.Failed(ModuleText.REMOTE_CONFIG_UNREADABLE.text())
        }

        val result = runCatching {
            EmbeddedConfigurationMigration.migrate(
                remotePreferences = remotePreferences,
                remoteFileOpener = { name -> openRemoteFile(name) },
                destination = storage,
            )
        }.getOrElse { error ->
            ModernXposedRuntime.log("embedded configuration migration failed open", error)
            EmbeddedConfigurationMigrationResult.Failed("迁移调用失败: ${error.javaClass.simpleName}")
        }
        when (result) {
            is EmbeddedConfigurationMigrationResult.Failed ->
                ModernXposedRuntime.log(
                    "embedded configuration migration incomplete: ${result.message}",
                )
            is EmbeddedConfigurationMigrationResult.Migrated ->
                ModernXposedRuntime.log(
                    "embedded configuration migrated ${result.copiedFileIds.size} file(s)",
                )
            else -> Unit
        }
        return result
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

}
