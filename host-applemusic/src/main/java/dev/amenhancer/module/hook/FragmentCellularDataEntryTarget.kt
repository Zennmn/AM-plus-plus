package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleHookRegistrar
import java.lang.reflect.Modifier

/**
 * 1606 Compose has no SIM row gate: getDataCategory always builds the row, and its caller gates
 * the whole account section on login. Verify that exact seam; never override login or a boolean scan.
 */
internal class FragmentCellularDataEntryTarget(
    private val symbols: TargetSymbolResolver,
    private val build: TargetBuild,
    private val loader: ClassLoader,
    private val enabled: () -> Boolean,
    private val profileProvider: (TargetBuild) -> AppleMusicHostProfile? = { candidate ->
        AppleMusicHostProfiles.find(candidate.packageName, candidate.versionName, candidate.versionCode)
    },
    private val hooksFactory: () -> CellularDataEntryHookInstaller? = {
        ModernXposedRuntime.activeModule()?.let { module ->
            val registrar = AppleHookRegistrar(module)
            object : CellularDataEntryHookInstaller {
                override fun overrideResult(method: java.lang.reflect.Method, override: (Any?) -> Any?) {
                    registrar.withModule(ModuleConstants.FEATURE_CELLULAR_DATA_ENTRY) {
                        registrar.installResultOverrideHook(method) { _, original -> override(original) }
                    }
                }
                override fun withScope(method: java.lang.reflect.Method, enter: () -> Unit, exit: () -> Unit) =
                    error("1606 Compose cellular entry has no scoped SIM gate")
            }
        }
    },
) : CellularDataEntryTarget {
    private val ready = HookRegistrationScope()
    private var installedResult: TargetCapabilityInstall? = null

    @Synchronized
    override fun install(): TargetCapabilityInstall {
        installedResult?.let { return it }
        val result = installOnce()
        installedResult = result
        return result
    }

    private fun installOnce(): TargetCapabilityInstall {
        val profile = runCatching { profileProvider(build) }.getOrElse { error ->
            return TargetCapabilityInstall.Degraded(
                "Fragment cellular profile is unavailable: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(180)}",
            )
        }
        if (!qualified(build, profile)) return TargetCapabilityInstall.Unsupported(
            "Fragment cellular data entry requires a production Fragment profile with cellular capability; found ${build.displayName}",
        )
        return runCatching {
            val document = checkNotNull(profile).document
            val names = FragmentSettingsContract.select(loader, document)
            val preferenceItems = names.preferenceItemsMethod(loader)
            val dataCategory = names.dataCategoryMethod(loader)
            names.verifyDataCategoryProfile(loader, document, dataCategory)
            val resolution = symbols.resolve(AppleMusicSymbols.CellularAvailability)
            val availability = resolution.valueOrNull() ?: return TargetCapabilityInstall.Degraded(resolution.summary)
            require(!Modifier.isStatic(availability.modifiers) && availability.parameterCount == 0 &&
                availability.returnType == java.lang.Boolean.TYPE)
            val hooks = hooksFactory() ?: return TargetCapabilityInstall.Degraded(
                "Modern Xposed module was not attached for Fragment cellular data entry",
            )
            hooks.overrideResult(availability) { original ->
                if (ready.isActive && runCatching(enabled).getOrDefault(false)) true else original
            }
            ready.activate()
            TargetCapabilityInstall.Active(
                "Verified native Compose cellular row (account gate preserved): ${preferenceItems.toGenericString()}; " +
                    "installed availability override: ${availability.toGenericString()}",
            )
        }.getOrElse { error ->
            ready.close()
            TargetCapabilityInstall.Degraded(
                "Fragment cellular entry registration failed; callbacks remain dormant: " +
                    "${error.javaClass.simpleName}: ${error.message.orEmpty().take(180)}",
            )
        }
    }

    companion object {
        fun supports(build: TargetBuild): Boolean = qualified(
            build, AppleMusicHostProfiles.find(build.packageName, build.versionName, build.versionCode),
        )

        private fun qualified(build: TargetBuild, profile: AppleMusicHostProfile?): Boolean =
            profile != null && profile.versionName == build.versionName &&
                profile.versionCode == build.versionCode && profile.productionEnabled &&
                profile.family == "fragment-content" && profile.capability("cellular")
    }
}
