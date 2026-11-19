package dev.amenhancer.module.hook

import dev.amenhancer.host.classical.ClassicalHostProfile
import dev.amenhancer.module.lyrics.ClassicalTranslationCache
import dev.amenhancer.module.lyrics.ClassicalTranslationPolicy
import dev.amenhancer.module.lyrics.LlmLyricsClient
import dev.amenhancer.module.lyrics.LlmLyricsConfig
import dev.amenhancer.module.model.ModuleSettings
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Translates Classical's synchronized Listening Guide segments and Editorial Notes asynchronously
 * via the shared OpenAI-compatible client, presenting bilingual English + Simplified Chinese text.
 */
internal class ClassicalGuideTranslatorTarget(
    private val classLoader: ClassLoader,
    private val profile: ClassicalHostProfile,
    private val settingsProvider: () -> ModuleSettings,
) : ClassicalGuideTranslationTarget {
    private val scope = HookRegistrationScope()
    private val client = LlmLyricsClient()
    private val cache = ClassicalTranslationCache(maxEntries = 128)
    private val inFlightNotes = Collections.synchronizedSet(mutableSetOf<String>())
    private val latestGuideTrackId = AtomicReference<String?>(null)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ampp-classical-translate").apply { isDaemon = true }
    }
    private var installedResult: TargetCapabilityInstall? = null

    @Synchronized
    override fun install(): TargetCapabilityInstall {
        installedResult?.let { return it }
        return runCatching { installOnce() }
            .getOrElse { error ->
                scope.close()
                TargetCapabilityInstall.Degraded(
                    "Classical guide translation failed: ${error.javaClass.simpleName}: ${error.message}",
                )
            }
            .also { installedResult = it }
    }

    private fun installOnce(): TargetCapabilityInstall {
        if (!profile.productionEnabled || !profile.capability("guideTranslation")) {
            return TargetCapabilityInstall.Unsupported("Classical build has no verified guide translation contract")
        }
        val contract = profile.document.getJSONObject("listeningGuide")
        val stateFlowClass = classLoader.loadClass(contract.getString("stateFlowClass"))
        val emitMethod: Method = stateFlowClass.getDeclaredMethod(
            contract.getString("stateFlowEmitMethod"),
            Any::class.java,
            Any::class.java,
        ).apply { isAccessible = true }
        val getValueMethod: Method = stateFlowClass.getMethod("getValue")

        val contentClass = classLoader.loadClass(contract.getString("contentStateClass"))
        val trackIdField: Field = contentClass.getDeclaredField(contract.getString("contentTrackIdField"))
            .apply { isAccessible = true }
        val guideField: Field = contentClass.getDeclaredField(contract.getString("contentGuideField"))
            .apply { isAccessible = true }
        val contentCtor: Constructor<*> = contentClass.getDeclaredConstructor(
            String::class.java,
            classLoader.loadClass(contract.getString("modelClass")),
        ).apply { isAccessible = true }

        val guideClass = classLoader.loadClass(contract.getString("modelClass"))
        val getSegments: Method = guideClass.getDeclaredMethod("getSegments")
        val getAttribution: Method = guideClass.getDeclaredMethod("getAttribution")
        val guideCtor: Constructor<*> = guideClass.getDeclaredConstructor(List::class.java, String::class.java)

        val segmentClass = classLoader.loadClass(contract.getString("segmentClass"))
        val getCaption: Method = segmentClass.getDeclaredMethod("getCaption")
        val getStartMs: Method = segmentClass.getDeclaredMethod("getStartTimeMs")
        val getEndMs: Method = segmentClass.getDeclaredMethod("getEndTimeMs")
        val segmentCtor: Constructor<*> = segmentClass.getDeclaredConstructor(
            String::class.java,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )

        val editorialNotesClass = classLoader.loadClass(contract.getString("editorialNotesClass"))
        val getStandard: Method = editorialNotesClass.getDeclaredMethod("getStandard")

        check(ModernXposedRuntime.hookMethod(emitMethod, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!scope.isActive || param.throwable != null) return
                val payload = param.args.getOrNull(1) ?: return
                if (!contentClass.isInstance(payload)) return
                val flow = param.thisObject ?: return
                val settings = settingsProvider()
                if (!settings.llmLyricsEnabled) return
                val llmConfig = LlmLyricsConfig.fromSettings(settings)
                if (!llmConfig.isConfigured) return
                val trackId = trackIdField.get(payload) as? String ?: return
                val guide = guideField.get(payload) ?: return
                val segments = (getSegments.invoke(guide) as? List<*>)?.filterNotNull().orEmpty()
                if (segments.isEmpty()) return
                val captions = segments.map { getCaption.invoke(it) as? String ?: "" }
                if (captions.none { ClassicalTranslationPolicy.needsTranslation(it) && !it.contains('\n') }) return

                val starts = segments.map { (getStartMs.invoke(it) as Long) }
                val ends = segments.map { (getEndMs.invoke(it) as Long) }
                val attribution = getAttribution.invoke(guide) as? String ?: ""
                latestGuideTrackId.set(trackId)

                executor.execute {
                    if (latestGuideTrackId.get() != trackId) return@execute
                    translateGuide(
                        flow = flow,
                        trackId = trackId,
                        attribution = attribution,
                        captions = captions,
                        starts = starts,
                        ends = ends,
                        llmConfig = llmConfig,
                        emitMethod = emitMethod,
                        getValueMethod = getValueMethod,
                        contentClass = contentClass,
                        trackIdField = trackIdField,
                        segmentCtor = segmentCtor,
                        guideCtor = guideCtor,
                        contentCtor = contentCtor,
                    )
                }
            }
        }, scope)) { "Classical ListeningGuide StateFlow hook failed" }

        check(ModernXposedRuntime.hookMethod(getStandard, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!scope.isActive || param.throwable != null) return
                val original = param.result as? String ?: return
                val settings = settingsProvider()
                if (!settings.llmLyricsEnabled) return
                val llmConfig = LlmLyricsConfig.fromSettings(settings)
                if (!llmConfig.isConfigured) return
                if (!ClassicalTranslationPolicy.needsTranslation(original) || original.contains('\n')) return

                val configKey = configCacheKey(llmConfig)
                val cached = cache.get(configKey, original)
                if (cached != null) {
                    param.result = ClassicalTranslationPolicy.formatBilingual(original, cached)
                    return
                }
                val dedupKey = "$configKey:${original.hashCode()}:${original.length}"
                if (!inFlightNotes.add(dedupKey)) return
                executor.execute {
                    try {
                        val response = client.translateProse(
                            config = llmConfig,
                            entries = mapOf("N0" to original),
                            contextTitle = "Editorial Notes",
                        ) ?: return@execute
                        response["N0"]?.let { cache.put(configKey, original, it) }
                    } finally {
                        inFlightNotes.remove(dedupKey)
                    }
                }
            }
        }, scope)) { "Classical EditorialNotes.getStandard hook failed" }

        scope.activate()
        return TargetCapabilityInstall.Active("Installed Classical ListeningGuide and EditorialNotes bilingual AI translator")
    }

    private fun translateGuide(
        flow: Any,
        trackId: String,
        attribution: String,
        captions: List<String>,
        starts: List<Long>,
        ends: List<Long>,
        llmConfig: LlmLyricsConfig,
        emitMethod: Method,
        getValueMethod: Method,
        contentClass: Class<*>,
        trackIdField: Field,
        segmentCtor: Constructor<*>,
        guideCtor: Constructor<*>,
        contentCtor: Constructor<*>,
    ) {
        val configKey = configCacheKey(llmConfig)
        val pending = linkedMapOf<String, String>()
        val resolved = HashMap<Int, String>(captions.size)
        captions.forEachIndexed { index, caption ->
            if (!ClassicalTranslationPolicy.needsTranslation(caption) || caption.contains('\n')) return@forEachIndexed
            val cached = cache.get(configKey, caption)
            if (cached != null) {
                resolved[index] = cached
            } else {
                pending["S$index"] = caption
            }
        }
        if (pending.isNotEmpty()) {
            val response = client.translateProse(
                config = llmConfig,
                entries = pending,
                contextTitle = "Listening Guide $trackId",
                isCancelled = { latestGuideTrackId.get() != trackId },
            ) ?: return
            pending.forEach { (id, orig) ->
                val index = id.removePrefix("S").toIntOrNull() ?: return@forEach
                val trans = response[id] ?: return@forEach
                cache.put(configKey, orig, trans)
                resolved[index] = trans
            }
        }
        if (latestGuideTrackId.get() != trackId || resolved.isEmpty()) return
        val currentValue = runCatching { getValueMethod.invoke(flow) }.getOrNull() ?: return
        if (!contentClass.isInstance(currentValue) || trackIdField.get(currentValue) != trackId) return

        val rebuiltSegments = captions.indices.map { index ->
            val orig = captions[index]
            val finalCaption = resolved[index]?.let { ClassicalTranslationPolicy.formatBilingual(orig, it) } ?: orig
            segmentCtor.newInstance(finalCaption, starts[index], ends[index])
        }
        val rebuiltGuide = guideCtor.newInstance(rebuiltSegments, attribution)
        val rebuiltContent = contentCtor.newInstance(trackId, rebuiltGuide)
        runCatching { emitMethod.invoke(flow, null, rebuiltContent) }
            .onFailure { ModernXposedRuntime.log("Classical guide emit failed", it) }
    }

    private fun configCacheKey(config: LlmLyricsConfig): String =
        "${config.endpoint}|${config.normalizedModel}|${config.effectiveThinkingEnabled}|${config.effectiveThinkingEffort.storageValue}"
}
