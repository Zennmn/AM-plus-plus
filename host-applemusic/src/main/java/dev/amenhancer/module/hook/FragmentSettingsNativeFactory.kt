package dev.amenhancer.module.hook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.ui.EmbeddedHostActivityRole
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Native settings composition entry points for AppleMusicHostFactory's Fragment-family dispatch. */
internal object FragmentSettingsNativeFactory {
    private val runtimes = WeakHashMap<ClassLoader, FragmentSettingsRuntime>()

    private fun names(context: Context): FragmentSettingsContract {
        val build = targetBuild(context)
        val profile = checkNotNull(AppleMusicHostProfiles.find(build.packageName, build.versionName, build.versionCode))
        require(profile.productionEnabled && profile.family == "fragment-content") {
            "Unverified native settings build: ${build.displayName}"
        }
        return FragmentSettingsContract.from(profile.document.getJSONObject("settings"))
    }

    @Synchronized
    private fun runtime(loader: ClassLoader, names: FragmentSettingsContract): FragmentSettingsRuntime =
        runtimes.getOrPut(loader) { FragmentSettingsRuntime(loader, names) }

    fun activityMatcher(context: Context): SettingsActivityMatcher = FragmentSettingsActivityMatcher(names(context).mainActivity)

    fun viewBridge(context: Context, onOpen: (Activity) -> Unit): SettingsViewBridge {
        val names = names(context)
        val loader = context.classLoader
        runtime(loader, names).onOpen = onOpen
        return object : SettingsViewBridge {
            override val supportsViewFallback = false
            override fun supportsStartupPagePrewarm(activity: Activity): Boolean =
                FragmentStartupPagePrewarmTarget.supports(activity)
            override fun supportsGlassScrollCollapse(activity: Activity): Boolean {
                val build = targetBuild(activity)
                return android.os.Build.VERSION.SDK_INT >= 33 &&
                    !TabletModeQualifier.isOfficialTablet(activity) &&
                    AppleMusicHostProfiles.supportsGlassScrollCollapse(build.versionCode, build.versionName)
            }
            override fun fragmentView(fragment: Any): ViewGroup? = runCatching {
                fragment.javaClass.getMethod("getView").invoke(fragment) as? ViewGroup
            }.getOrNull()

            override fun injectNativeSettingsPreference(fragment: Any, activity: Activity): Boolean {
                if (activity.packageName != ModuleConstants.TARGET_PACKAGE) return false
                val current = runtime(fragment.javaClass.classLoader ?: loader, names)
                current.onOpen = onOpen
                return current.bind(fragment)
            }

            // Compose owns the list and invalidation; no RecyclerView child, drawer or view overlay is added.
            override fun findSettingsListOverlayContainer(root: View): ViewGroup? = null
            override fun findSettingsInsertionContainer(root: ViewGroup): ViewGroup? = null
            override fun refreshNativePreferenceAdapter(root: ViewGroup?) = Unit
        }
    }

    fun install(context: Context, loader: ClassLoader, observer: SettingsEntryObserver): TargetCapabilityInstall =
        runtime(loader, names(context)).install(observer).also {
            ModernXposedRuntime.log(it.message)
        }
}

/** MainActivity hosts the player and settings Fragments; it must never receive the old floating player button. */
internal class FragmentSettingsActivityMatcher(private val mainActivity: String) : SettingsActivityMatcher {
    override fun roleFor(activity: Activity): EmbeddedHostActivityRole? =
        if (activity.packageName == ModuleConstants.TARGET_PACKAGE && isMainContentActivity(activity)) {
            EmbeddedHostActivityRole.MainContent
        } else null

    override fun isPlayerActivity(activity: Activity): Boolean = false

    override fun isMainContentActivity(activity: Activity): Boolean = isMainClass(activity.javaClass)

    internal fun isMainClass(type: Class<*>): Boolean = generateSequence(type) { it.superclass }
        .any { it.name == mainActivity }
}

internal interface FragmentSettingsHookInstaller {
    fun install(
        method: Method,
        scope: HookRegistrationScope,
        before: (Any?, Array<Any?>) -> Unit = { _, _ -> },
        after: (Any?, Array<Any?>, Any?) -> Any? = { _, _, result -> result },
    )
}

private object ModernFragmentSettingsHookInstaller : FragmentSettingsHookInstaller {
    override fun install(
        method: Method,
        scope: HookRegistrationScope,
        before: (Any?, Array<Any?>) -> Unit,
        after: (Any?, Array<Any?>, Any?) -> Any?,
    ) {
        check(ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) = before(param.thisObject, param.args)
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val original = param.result
                val result = after(param.thisObject, param.args, original)
                if (result !== original) param.result = result
            }
        }, scope)) { "Native settings hook registration failed: ${method.toGenericString()}" }
    }
}

internal class FragmentSettingsRuntime(
    private val loader: ClassLoader,
    private val names: FragmentSettingsContract,
    private val hooks: FragmentSettingsHookInstaller = ModernFragmentSettingsHookInstaller,
) {
    @Volatile var onOpen: ((Activity) -> Unit)? = null
    private val scope = HookRegistrationScope()
    private var sessions: FragmentSettingsSessions? = null
    private var fragmentClass: Class<*>? = null
    private var mainClass: Class<*>? = null
    private var getViewModel: Method? = null
    private var getActivity: Method? = null
    private var getView: Method? = null
    private var installedResult: TargetCapabilityInstall? = null

    @Synchronized
    fun install(observer: SettingsEntryObserver): TargetCapabilityInstall {
        installedResult?.let { return it }
        val result = runCatching {
            // Resolve the complete contract first. Partial registrations cannot run until activation.
            val fragment = loader.loadClass(names.fragmentClass)
            val main = loader.loadClass(names.mainActivity)
            val preferenceItems = names.preferenceItemsMethod(loader)
            val viewModel = fragment.getDeclaredMethod(names.viewModelMethod).apply {
                require(!Modifier.isStatic(modifiers) && returnType == preferenceItems.declaringClass)
                isAccessible = true
            }
            val activity = inherited(fragment, "getActivity").apply {
                require(Activity::class.java.isAssignableFrom(returnType))
            }
            val view = inherited(fragment, "getView").apply { require(View::class.java.isAssignableFrom(returnType)) }
            val composer = loader.loadClass(names.composerClass)
            // 1606-1609 expose compose(Composer); 1611 renamed it PageContent(Composer, int $changed).
            val compose = runCatching { fragment.getDeclaredMethod(names.composeMethod, composer) }
                .getOrElse { fragment.getDeclaredMethod(names.composeMethod, composer, Integer.TYPE) }.apply {
                require(!Modifier.isStatic(modifiers) && returnType == Void.TYPE)
                isAccessible = true
            }
            val onViewCreated = inherited(fragment, "onViewCreated", View::class.java, Bundle::class.java)
            val onResume = inherited(fragment, "onResume")
            val onDestroyView = inherited(fragment, "onDestroyView")
            // MainActivity inherits this from BaseActivity in 1606. Hook only the nearest declaration,
            // so super calls do not deliver a SAF result twice, and let the host handle its own results.
            val activityResult = inherited(main, "onActivityResult", Integer.TYPE, Integer.TYPE, Intent::class.java)
            listOf(onViewCreated, onResume, onDestroyView, activityResult).forEach {
                require(!Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE)
            }
            val models = FragmentSettingsModels.resolve(loader, names)
            fragmentClass = fragment
            mainClass = main
            getViewModel = viewModel
            getActivity = activity
            getView = view
            sessions = FragmentSettingsSessions(models, ::open)
            scope.onClose { sessions?.clear() }

            hooks.install(preferenceItems, scope, after = { receiver, _, original ->
                if (scope.isActive && receiver != null) {
                    runCatching { sessions?.transform(receiver, original) ?: original }.getOrDefault(original)
                } else original
            })
            hooks.install(compose, scope, before = { receiver, _ ->
                if (receiver != null) bind(receiver)
            })
            hooks.install(onViewCreated, scope, after = { receiver, args, original ->
                notifyFragment(receiver) { fragmentObject, host ->
                    observer.onSettingsFragmentViewCreated(fragmentObject, host, args.getOrNull(0) as? View)
                }
                original
            })
            hooks.install(onResume, scope, after = { receiver, _, original ->
                notifyFragment(receiver, observer::onSettingsFragmentResumed)
                original
            })
            hooks.install(onDestroyView, scope, before = { receiver, _ ->
                if (scope.isActive && receiver != null && fragment.isInstance(receiver)) sessions?.unbind(receiver)
            })
            hooks.install(activityResult, scope, after = { receiver, args, original ->
                if (scope.isActive && main.isInstance(receiver) && receiver is Activity &&
                    receiver.packageName == ModuleConstants.TARGET_PACKAGE) {
                    val requestCode = args.getOrNull(0) as? Int
                    val resultCode = args.getOrNull(1) as? Int
                    if (requestCode != null && resultCode != null) {
                        runCatching { observer.onActivityResult(receiver, requestCode, resultCode, args.getOrNull(2) as? Intent) }
                    }
                }
                original
            })
            scope.activate()
            TargetCapabilityInstall.Active("Installed native Compose AM++ settings row and MainActivity SAF bridge")
        }.getOrElse { error ->
            scope.close()
            TargetCapabilityInstall.Degraded(
                "Native Compose settings registration failed; callbacks remain dormant: " +
                    "${error.javaClass.simpleName}: ${error.message.orEmpty().take(180)}",
            )
        }
        installedResult = result
        return result
    }

    fun bind(fragment: Any): Boolean {
        if (!scope.isActive || fragmentClass?.isInstance(fragment) != true) return false
        return runCatching {
            val viewModel = getViewModel?.invoke(fragment) ?: return false
            sessions?.bind(fragment, viewModel) == true
        }.getOrDefault(false)
    }

    private fun notifyFragment(receiver: Any?, notify: (Any, Activity) -> Unit) {
        if (receiver == null || !bind(receiver)) return
        val activity = runCatching { getActivity?.invoke(receiver) as? Activity }.getOrNull() ?: return
        if (mainClass?.isInstance(activity) != true || activity.packageName != ModuleConstants.TARGET_PACKAGE) return
        runCatching { notify(receiver, activity) }
    }

    private fun open(fragment: Any) {
        if (!scope.isActive || getView?.invoke(fragment) == null) return
        val activity = getActivity?.invoke(fragment) as? Activity ?: return
        if (mainClass?.isInstance(activity) != true || activity.packageName != ModuleConstants.TARGET_PACKAGE ||
            activity.isFinishing || activity.isDestroyed) return
        // EmbeddedSettingsHost supplies its existing complete controller, dialog and SAF router here.
        onOpen?.invoke(activity)
    }

    companion object {
        internal fun inherited(type: Class<*>, name: String, vararg parameters: Class<*>): Method {
            for (owner in generateSequence(type) { it.superclass }) {
                val method = runCatching { owner.getDeclaredMethod(name, *parameters) }.getOrNull()
                if (method != null) return method.apply { isAccessible = true }
            }
            throw NoSuchMethodException("${type.name}#$name")
        }
    }
}
