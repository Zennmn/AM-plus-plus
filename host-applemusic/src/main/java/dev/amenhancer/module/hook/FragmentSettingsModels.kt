package dev.amenhancer.module.hook

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import org.json.JSONObject

/** Exact profile contracts with the reviewed legacy signatures retained for existing builds. */
internal data class FragmentSettingsContract(
    val fragmentClass: String = "com.apple.android.music.settings2.fragment.SettingsFragment",
    val mainActivity: String = "com.apple.android.music.common.MainActivity",
    val viewModelClass: String = "com.apple.android.music.settings2.model.SettingsViewModel",
    val viewModelMethod: String = "z1",
    val preferenceItemsMethod: String = "getPreferenceItems",
    val dataCategoryMethod: String = "getDataCategory",
    val composeMethod: String = "r1",
    val composerClass: String = "z0.m",
    val categoryClass: String = "com.apple.android.music.settings2.model.b\$a",
    val actionClass: String = "com.apple.android.music.settings2.model.b\$b\$b",
    val artworkClass: String = "Ra.I",
    val callbackClass: String = "pi.a",
    val unitClass: String = "kotlin.Unit",
    val unitField: String = "a",
    val categoryItemsField: String = "c",
    val actionCallbackField: String = "j",
    val members: JSONObject? = null,
    val resolutionFailure: String? = null,
) {
    private fun profiledMethod(loader: ClassLoader, role: String): Method? = members?.let {
        require(it.getInt("contractVersion") == 1) { "Unsupported native settings member contract" }
        FragmentProfileMembers(loader, it, "settings.members",
            FragmentMemberRequirements(methods = setOf("preferenceItems", "compose"))).method(role)
    }

    fun preferenceItemsMethod(loader: ClassLoader): Method {
        check(resolutionFailure == null) { resolutionFailure.orEmpty() }
        profiledMethod(loader, "preferenceItems")?.let { method ->
            require(method.declaringClass == loader.loadClass(viewModelClass) &&
                method.name == preferenceItemsMethod && !Modifier.isStatic(method.modifiers) &&
                method.returnType == List::class.java)
            return method
        }
        val function0 = loader.loadClass(callbackClass)
        val function1 = loader.loadClass("pi.l")
        val function2 = loader.loadClass("pi.p")
        val bool = java.lang.Boolean.TYPE
        val parameters = arrayOf(
            bool, function1, function0, function0, function0, function0, function0, function0,
            function0, function1, function2, function1, function0, function1, function0, function0,
            function1, function1, String::class.java, bool, bool, bool, String::class.java,
            bool, bool, bool, bool, bool, bool,
        )
        return loader.loadClass(viewModelClass).getDeclaredMethod(preferenceItemsMethod, *parameters).apply {
            require(!Modifier.isStatic(modifiers) && returnType == List::class.java)
            isAccessible = true
        }
    }

    fun composeMethod(loader: ClassLoader): Method =
        (profiledMethod(loader, "compose") ?: loader.loadClass(fragmentClass)
            .getDeclaredMethod(composeMethod, loader.loadClass(composerClass))).apply {
            require(declaringClass == loader.loadClass(fragmentClass) && name == composeMethod &&
                !Modifier.isStatic(modifiers) && returnType == Void.TYPE)
            isAccessible = true
        }

    fun dataCategoryMethod(loader: ClassLoader): Method =
        loader.loadClass(viewModelClass).getDeclaredMethod(dataCategoryMethod, loader.loadClass(callbackClass)).apply {
            require(!Modifier.isStatic(modifiers) && returnType == loader.loadClass(categoryClass))
            isAccessible = true
        }

    /** Require the central profile to attest the exact native row builder, not merely a method name. */
    fun verifyDataCategoryProfile(loader: ClassLoader, profile: JSONObject, method: Method) {
        val contract = members?.optJSONObject("methods")?.optJSONObject("dataCategory")
            ?: profile.getJSONObject("indexed").getJSONObject("methodContracts").getJSONObject("settings-data-category-build")
        require(method.declaringClass == loader.loadClass(contract.getString("owner")))
        require(method.name == contract.getString("name"))
        require(method.returnType == loader.loadClass(contract.getString("returns")))
        require(Modifier.isStatic(method.modifiers) == contract.getBoolean("static"))
        val parameters = contract.getJSONArray("parameters")
        require(parameters.length() == 1 && method.parameterTypes.contentEquals(
            arrayOf(loader.loadClass(parameters.getString(0))),
        ))
    }

    companion object {
        /** Exact known variants only. Ambiguous or partial matches never select an arbitrary map. */
        fun select(loader: ClassLoader, profile: JSONObject): FragmentSettingsContract {
            val primary = from(profile.getJSONObject("settings"))
            val variants = profile.optJSONArray("settingsVariants") ?: return primary
            val candidates = listOf(primary) + (0 until variants.length()).map { from(variants.getJSONObject(it)) }
            val matching = candidates.filter { candidate -> runCatching {
                candidate.preferenceItemsMethod(loader)
                candidate.composeMethod(loader)
                candidate.dataCategoryMethod(loader)
                FragmentSettingsModels.resolve(loader, candidate)
            }.isSuccess }
            check(matching.size == 1) { "Native settings variant match count: ${matching.size}" }
            return matching.single()
        }
        fun from(names: JSONObject): FragmentSettingsContract {
            val defaults = FragmentSettingsContract()
            fun name(key: String, fallback: String) = names.optString(key, fallback).also {
                require(it.isNotBlank()) { "Missing native settings contract: $key" }
            }
            return FragmentSettingsContract(
                fragmentClass = name("fragmentClass", defaults.fragmentClass),
                mainActivity = name("mainActivity", defaults.mainActivity),
                viewModelClass = name("viewModelClass", defaults.viewModelClass),
                viewModelMethod = name("viewModelMethod", defaults.viewModelMethod),
                preferenceItemsMethod = name("preferenceItemsMethod", defaults.preferenceItemsMethod),
                dataCategoryMethod = name("dataCategoryMethod", defaults.dataCategoryMethod),
                composeMethod = name("composeMethod", defaults.composeMethod),
                composerClass = name("composerClass", defaults.composerClass),
                categoryClass = name("categoryClass", defaults.categoryClass),
                actionClass = name("actionClass", defaults.actionClass),
                artworkClass = name("artworkClass", defaults.artworkClass),
                callbackClass = name("callbackClass", defaults.callbackClass),
                unitClass = name("unitClass", defaults.unitClass),
                unitField = name("unitField", defaults.unitField),
                categoryItemsField = name("categoryItemsField", defaults.categoryItemsField),
                actionCallbackField = name("actionCallbackField", defaults.actionCallbackField),
                members = if (names.has("members")) names.getJSONObject("members") else null,
            )
        }
    }
}

/** No module Kotlin function or Unit object crosses the host's class-loader boundary. */
internal class FragmentSettingsModels private constructor(
    private val loader: ClassLoader,
    private val categoryConstructor: Constructor<*>,
    private val actionConstructor: Constructor<*>,
    private val callbackClass: Class<*>,
    private val unit: Any,
    private val categoryItems: Field,
    private val actionCallback: Field,
) {
    fun createCategory(onOpen: () -> Unit): Any {
        val callback = Proxy.newProxyInstance(loader, arrayOf(callbackClass), EntryCallback(unit, onOpen))
        // This is the same optimized constructor and default mask as native navigation rows.
        val action = actionConstructor.newInstance("AM++", null, false, null, null, callback, 0x17a)
        return categoryConstructor.newInstance(null, listOf(action), null, 0x3b)
    }

    fun prependUnique(original: Any?, category: Any): Any? {
        val items = original as? List<*> ?: return original
        // 1606 returns an empty singleton while preferencesStateFlow is null.
        // Keep that not-ready result native; add the entry when settings are populated.
        if (items.isEmpty()) return original
        // Never mutate Apple's list.
        return ArrayList<Any?>(items.size + 1).apply {
            add(category)
            items.filterNot(::isModuleCategory).forEach(::add)
        }
    }

    internal fun isModuleCategory(value: Any?): Boolean {
        if (!categoryItems.declaringClass.isInstance(value)) return false
        val rows = categoryItems.get(value) as? List<*> ?: return false
        return rows.size == 1 && rows.single()?.let { row ->
            if (!actionCallback.declaringClass.isInstance(row)) return@let false
            val callback = actionCallback.get(row) ?: return@let false
            Proxy.isProxyClass(callback.javaClass) && Proxy.getInvocationHandler(callback) is EntryCallback
        } == true
    }

    private class EntryCallback(private val unit: Any, private val onOpen: () -> Unit) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when {
            method.name == "invoke" && method.parameterCount == 0 -> {
                // A UI failure must not escape into the host Compose click dispatcher.
                runCatching(onOpen)
                unit
            }
            method.name == "equals" && method.parameterCount == 1 -> proxy === args?.getOrNull(0)
            method.name == "hashCode" && method.parameterCount == 0 -> System.identityHashCode(proxy)
            method.name == "toString" && method.parameterCount == 0 -> "ampp_embedded_settings_preference"
            else -> throw UnsupportedOperationException(method.toGenericString())
        }
    }

    companion object {
        fun resolve(loader: ClassLoader, names: FragmentSettingsContract): FragmentSettingsModels {
            val category = loader.loadClass(names.categoryClass)
            val action = loader.loadClass(names.actionClass)
            val callback = loader.loadClass(names.callbackClass)
            require(callback.isInterface)
            require(callback.getMethod("invoke").returnType == Any::class.java)
            val unitClass = loader.loadClass(names.unitClass)
            val unitField = unitClass.getDeclaredField(names.unitField).apply { isAccessible = true }
            require(Modifier.isStatic(unitField.modifiers) && unitField.type == unitClass)
            val categoryItems = category.getDeclaredField(names.categoryItemsField).apply { isAccessible = true }
            val actionCallback = action.getDeclaredField(names.actionCallbackField).apply { isAccessible = true }
            require(categoryItems.type == List::class.java && actionCallback.type == callback)
            return FragmentSettingsModels(
                loader,
                category.getDeclaredConstructor(String::class.java, List::class.java, CharSequence::class.java, Integer.TYPE),
                action.getDeclaredConstructor(
                    String::class.java, String::class.java, java.lang.Boolean.TYPE, String::class.java,
                    loader.loadClass(names.artworkClass), callback, Integer.TYPE,
                ),
                callback, checkNotNull(unitField.get(null)), categoryItems, actionCallback,
            )
        }
    }
}

/** A view generation owns one stable row. Destroying it revokes all of its retained callbacks. */
internal class FragmentSettingsSessions(
    private val models: FragmentSettingsModels,
    private val onOpen: (Any) -> Unit,
) {
    private class Session(fragment: Any, viewModel: Any) {
        val fragment = WeakReference(fragment)
        val viewModel = WeakReference(viewModel)
        var active = true
        var category: Any? = null
    }
    private val fragments = WeakHashMap<Any, Session>()
    private val viewModels = WeakHashMap<Any, Session>()

    @Synchronized
    fun bind(fragment: Any, viewModel: Any): Boolean {
        val old = fragments[fragment]
        if (old?.active == true && old.viewModel.get() === viewModel) return true
        unbind(fragment)
        viewModels[viewModel]?.let { previous ->
            previous.active = false
            previous.fragment.get()?.let { if (fragments[it] === previous) fragments.remove(it) }
        }
        val session = Session(fragment, viewModel)
        session.category = models.createCategory {
            val current = synchronized(this) {
                session.fragment.get()?.takeIf { session.active && fragments[it] === session }
            }
            if (current != null) onOpen(current)
        }
        fragments[fragment] = session
        viewModels[viewModel] = session
        return true
    }

    @Synchronized
    fun unbind(fragment: Any) {
        val session = fragments.remove(fragment) ?: return
        session.active = false
        session.viewModel.get()?.let { if (viewModels[it] === session) viewModels.remove(it) }
    }

    @Synchronized
    fun transform(viewModel: Any, original: Any?): Any? {
        val session = viewModels[viewModel] ?: return original
        if (!session.active || session.fragment.get() == null) return original
        return models.prependUnique(original, checkNotNull(session.category))
    }

    @Synchronized
    fun clear() {
        fragments.values.forEach { it.active = false }
        fragments.clear()
        viewModels.clear()
    }
}
