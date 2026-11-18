package dev.amenhancer.module.hook

import android.os.Handler
import android.os.Looper
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.lyrics.AmllTtmlFormatConverter
import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import dev.amenhancer.module.lyrics.CustomLyricsFileReader
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsSources
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Owns the native parser seam and submits prepared lyrics through the host result contract. */
internal class AppleMusicCustomLyricsTarget(
    private val config: TargetConfigClient,
    private val symbols: TargetSymbolResolver,
    private val currentSong: CurrentSongIdentityCache,
    private val autoLyricsRuntime: AutoLyricsRuntime? = null,
    private val installMenu: ((HookRegistrationScope) -> TargetCapabilityInstall)? = null,
) : CustomLyricsTarget {
    private var installedResult: TargetCapabilityInstall? = null
    private val registration = HookRegistrationScope()
    private var installedParserResult: TargetCapabilityInstall? = null
    private val parserRegistration = HookRegistrationScope()
    @Volatile private var parserObserver: ((String, Any?) -> Unit)? = null

    /** Native input repair is independent of enabling user-managed lyric replacement. */
    @Synchronized fun installParserRepair(): TargetCapabilityInstall {
        installedParserResult?.let { return it }
        val result = runCatching {
            val resolution = symbols.resolve(AppleMusicSymbols.TtmlSongInfoFromTtml)
            val method = resolution.valueOrNull()
                ?: return@runCatching TargetCapabilityInstall.Degraded(resolution.summary)
            method.isAccessible = true
            check(ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val ttml = param.args.getOrNull(0) as? String ?: return
                    runCatching {
                        // Custom lyrics may still need this hook for observation while repair is off.
                        if (!config.settings().lyricEffectsOptimizationEnabled ||
                            !isSimplifiedLyricsLocale(Locale.getDefault())
                        ) return@runCatching
                        val repaired = AmllTtmlFormatConverter.repairSimplifiedReplacementBackgrounds(ttml)
                        if (repaired != ttml) {
                            param.extras["originalTtml"] = ttml
                            param.args[0] = repaired
                        }
                    }.onFailure { error ->
                        ModernXposedRuntime.log("native TTML background repair failed: $error")
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    // Observations retain the exact host input, not the repaired display document.
                    val original = param.extras["originalTtml"] as? String
                        ?: param.args.getOrNull(0) as? String ?: return
                    parserObserver?.invoke(original, param.result)
                }
            }, parserRegistration)) { "Native TTML parser hook unavailable" }
            parserRegistration.activate()
            TargetCapabilityInstall.Active("Native TTML parser hook installed")
        }.getOrElse { error ->
            TargetCapabilityInstall.Degraded("Native TTML parser repair unavailable: ${error.message}")
        }
        if (result !is TargetCapabilityInstall.Active) parserRegistration.close()
        installedParserResult = result
        return result
    }

    @Synchronized override fun install(): TargetCapabilityInstall {
        installedResult?.let { return it }
        return try {
            installOnce().also { result ->
                if (result is TargetCapabilityInstall.Active) registration.activate()
                else if (result.message.startsWith("Custom lyric ViewModel submission installed")) registration.activate()
                else registration.close()
                installedResult = result
            }
        } catch (error: Throwable) { registration.close(); throw error }
    }
    private fun hook(method: java.lang.reflect.Executable, callback: ModernMethodHook): Boolean =
        ModernXposedRuntime.hookMethod(method,callback,registration)
    private fun installOnce(): TargetCapabilityInstall {
        val installMethodResolution = symbols.resolve(AppleMusicSymbols.LyricsInstallMethod)
        val installMethod = installMethodResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(installMethodResolution.summary)
        if (!runCatching {
                installMethod.isAccessible = true
                true
            }.getOrDefault(false)
        ) {
            return TargetCapabilityInstall.Degraded(
                "PlayerLyricsViewFragment.I2 could not be made accessible; " +
                    installMethodResolution.summary,
            )
        }
        val ptrResolution = symbols.resolve(AppleMusicSymbols.SongInfoPtr)
        val ptrClass = ptrResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(ptrResolution.summary)
        val nativeResolution = symbols.resolve(AppleMusicSymbols.SongInfoNative)
        val nativeClass = nativeResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(nativeResolution.summary)
        val parserResolution = symbols.resolve(AppleMusicSymbols.TtmlParserNative)
        val parserClass = parserResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(parserResolution.summary)
        val parseMethodResolution = symbols.resolve(AppleMusicSymbols.TtmlSongInfoFromTtml)
        val parseMethod = parseMethodResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(parseMethodResolution.summary)
        val seam = CurrentItemIdentitySeam(symbols)
        seam.resolve(installMethod)?.let { diagnostic ->
            return TargetCapabilityInstall.Degraded(diagnostic)
        }
        val nativeParser = TtmlNativeParser.create(
            parserClass = parserClass,
            parseMethod = parseMethod,
            ptrClass = ptrClass,
            nativeClass = nativeClass,
        ) ?: return TargetCapabilityInstall.Degraded(
            "TTML native parser surface was unavailable; " +
                listOf(
                    parserResolution.summary,
                    parseMethodResolution.summary,
                    ptrResolution.summary,
                    nativeResolution.summary,
                ).joinToString("; "),
        )
        val mainHandler = Handler(Looper.getMainLooper())
        lateinit var resultBridge: LyricsResultSubmissionBridge
        val preparationExecutor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(8),
            { runnable -> Thread(runnable, "ampp-custom-lyrics").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
        val parser = OpaqueTtmlParser(
            native = nativeParser,
            executor = preparationExecutor,
            onSubmissionReady = { id ->
                mainHandler.post {
                    if (registration.isActive) resultBridge.onReplacementPublished(id)
                }
            },
            logger = ModernXposedRuntime::log,
        )
        registration.onClose { preparationExecutor.shutdownNow() }
        val timingObservations = TtmlTimingObservationRegistry()
        val fileReader = CustomLyricsFileReader { fileId ->
            config.openFile(fileId)?.let { input ->
                runCatching {
                    input.use(CustomLyricsFilePolicy::readBounded)
                }.getOrNull()
            }
        }
        var refreshSession: AutoLyricsRefreshSession? = null
        var llmSession: LlmLyricsReplacementSession? = null
        var autoSession: AutoLyricsReplacementSession? = null
        val playback = autoLyricsRuntime?.playback
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                (if (config.settings().customLyricsEnabled) config.customLyricsManifest().entries
                    .filter { it.source == CustomLyricsSources.MANUAL } else emptyList())
                    .associateBy(
                    CustomLyricsEntry::appleMusicId,
                )
            },
            readTtml = fileReader::read,
            parseTtml = parser::parse,
            isAlive = parser::isAlive,
            verifyPtr = parser::isValid,
            readAdamId = parser::adamIdOf,
            bindAdamId = parser::bindAdamId,
            onReplacementPublished = { appleMusicId ->
                mainHandler.post {
                    if (registration.isActive) {
                        resultBridge.onReplacementPublished(appleMusicId)
                    }
                }
            },
            executor = preparationExecutor,
            logger = ModernXposedRuntime::log,
        )
        refreshSession = autoLyricsRuntime?.let { runtime ->
            val refreshSong = runtime.refreshSong ?: return@let null
            val refreshExecutor = runtime.refreshExecutor ?: return@let null
            AutoLyricsRefreshSession(
                executor = refreshExecutor,
                refreshSong = refreshSong,
                isEnabled = { config.settings().let { it.customLyricsEnabled && it.automaticLyricsEnabled } },
                onUpdated = { entry, isCancelled ->
                    if (!isCancelled()) mainHandler.post {
                        if (registration.isActive && !isCancelled()) {
                            autoSession?.refreshCached(entry.appleMusicId, entry.source)
                        }
                    }
                },
                logger = ModernXposedRuntime::log,
            ).also { refresh -> registration.onClose {
                refresh.close()
                runtime.closeRefresh()
            } }
        }
        autoSession = autoLyricsRuntime?.let { runtime ->
            AutoLyricsReplacementSession(
                fetchCandidate = { appleMusicId, source ->
                    // The core checked this source cache; fetch only the provider it requested.
                    runtime.resolver.fetch(appleMusicId, listOf(source))?.let { candidate ->
                        val details = currentSong.current()
                            ?.takeIf { it.details.appleMusicId == appleMusicId }
                            ?.details
                        val displayName = listOfNotNull(
                            details?.title?.takeIf(String::isNotBlank),
                            details?.artist?.takeIf(String::isNotBlank),
                        ).joinToString(" - ").ifBlank { null }
                        candidate.copy(displayName = displayName)
                    }
                },
                cache = runtime.cache,
                parseTtml = parser::parse,
                isAlive = parser::isAlive,
                verifyPtr = parser::isValid,
                readAdamId = parser::adamIdOf,
                bindAdamId = parser::bindAdamId,
                onReplacementPublished = { appleMusicId ->
                    mainHandler.post {
                        if (registration.isActive) {
                            resultBridge.onReplacementPublished(appleMusicId)
                        }
                    }
                },
                isAllowed = { appleMusicId ->
                    config.settings().let { it.customLyricsEnabled && it.automaticLyricsEnabled } &&
                        !runtime.playback.isSuppressed(appleMusicId) &&
                        (playback?.manualEntry(appleMusicId)?.enabled != true) &&
                        (!session.isMapped(appleMusicId)) &&
                        playback?.preferredSource(appleMusicId) == null
                },
                executor = runtime.executor,
                logger = ModernXposedRuntime::log,
                onFreshCandidate = { id ->
                    mainHandler.post {
                        if (registration.isActive && currentSong.current()?.details?.appleMusicId == id &&
                            autoSession?.readyReplacementFor(id) != null
                        ) {
                            refreshSession?.onSongChanged(id, cacheReady = false)
                            refreshSession?.markDownloaded(id)
                        }
                    }
                },
                onLookupMiss = { id ->
                    mainHandler.post {
                        if (registration.isActive) resultBridge.onReplacementPublished(id)
                    }
                },
            )
        }
        llmSession = autoLyricsRuntime?.llm?.let { runtime ->
            LlmLyricsReplacementSession(
                runtime = runtime,
                parseTtml = parser::parse,
                isAlive = parser::isAlive,
                verifyPtr = parser::isValid,
                readAdamId = parser::adamIdOf,
                bindAdamId = parser::bindAdamId,
                isAllowed = { id ->
                    currentSong.current()?.details?.appleMusicId == id && config.settings().let {
                        it.llmLyricsTransliterationEnabled ||
                            (it.customLyricsEnabled && it.automaticLyricsEnabled && it.llmLyricsEnabled)
                    }
                },
                isTranslationAllowed = { id ->
                    playback?.isSuppressed(id) != true &&
                        (playback?.preferredSource(id) == CustomLyricsSources.AM_NATIVE ||
                            (playback?.manualEntry(id)?.enabled != true &&
                                !session.isMapped(id) &&
                                autoSession?.readyReplacementFor(id) == null))
                },
                onReplacementPublished = { id ->
                    mainHandler.post {
                        if (registration.isActive) resultBridge.onReplacementPublished(id)
                    }
                },
            ).also { translation ->
                translation.onSongChanged(currentSong.current()?.details?.appleMusicId)
                registration.onClose(translation::close)
            }
        }
        // Every selection, including a user choice and timing copy, uses this one result channel.
        val selectReplacement: (Long, Long, Any?) -> Any? = { id, nativeId, original ->
            val settings = config.settings()
            val automaticEnabled = settings.customLyricsEnabled && settings.automaticLyricsEnabled
            val manual = if (settings.customLyricsEnabled) session.replacementFor(id) else null
            val native = parser.wrap(original, id)
            val metadata = timingObservations.metadataOf(original)
            val nativeRaw = timingObservations.nativeTtmlOf(original)
            val manualRaw = manual?.let(parser::documentOf)
            val explicit = if (settings.customLyricsEnabled) playback?.select(id, native, nativeRaw, manual, manualRaw) else null
            val sources = automaticLyricsLookupSources(settings, native != null, metadata)
            val automatic = if (explicit == null && manual == null && automaticEnabled && playback?.preferredSource(id) == null) {
                autoSession?.configureSources(id, sources)
                if (sources.isNotEmpty()) autoSession?.replacementFor(id) else null
            } else null
            val candidate = autoSession?.candidateFor(id)
            val nativeSelected = explicit?.source == CustomLyricsSources.AM_NATIVE ||
                (explicit == null && manual == null && automatic == null && native != null &&
                    (!automaticEnabled || sources.isEmpty() || autoSession?.hasLookupMiss(id) == true))
            val base = explicit?.payload ?: manual ?: automatic ?: native
            val source = explicit?.source ?: when {
                manual != null -> CustomLyricsSources.MANUAL
                automatic != null -> candidate?.source ?: CustomLyricsSources.AUTO_CACHE
                else -> CustomLyricsSources.AM_NATIVE
            }
            val raw = when {
                explicit != null -> explicit.ttml
                manual != null || automatic != null -> base?.let(parser::documentOf)
                else -> nativeRaw
            }
            val sourceSelected = explicit != null || manual != null || automatic != null || nativeSelected
            if (base != null) playback?.onSelected(id, source, base, raw)
            refreshSession?.onSongChanged(id, cacheReady = sourceSelected && source in setOf(
                CustomLyricsSources.AMLL,
                CustomLyricsSources.AM_LYRICS,
                CustomLyricsSources.LUNABEAT,
            ))
            val translationEligible = nativeSelected && automaticEnabled &&
                playback?.isSuppressed(id) != true &&
                (explicit != null || (playback?.manualEntry(id)?.enabled != true &&
                    !session.isMapped(id))) &&
                metadata?.hasTranslation != true && (metadata == null || metadata.language == null || metadata.isForeign)
            val selectionPending = playback?.snapshot(id)?.pendingSource != null
            val enhanced = if (selectionPending) {
                llmSession?.pauseForSourceChange(id, raw)
            } else if (sourceSelected && base != null) {
                llmSession?.onSelected(id)
                val details = currentSong.current()?.takeIf { it.details.appleMusicId == id }?.details
                if (raw != null) llmSession?.replacementFor(id, raw, details?.title, details?.artist,
                    allowTranslation = translationEligible,
                    translationSkipReason = when {
                        !nativeSelected -> dev.amenhancer.module.lyrics.LlmLyricsPhase.NATIVE_ONLY
                        metadata?.hasTranslation == true -> dev.amenhancer.module.lyrics.LlmLyricsPhase.EXISTS
                        metadata?.language != null && !metadata.isForeign -> dev.amenhancer.module.lyrics.LlmLyricsPhase.NOT_NEEDED
                        else -> dev.amenhancer.module.lyrics.LlmLyricsPhase.UNSUPPORTED
                    })
                else { llmSession?.onNativeInputUnavailable(id); null }
            } else { llmSession?.onHigherPriorityResult(id); null }
            val display = enhanced ?: base
            val displayRaw = enhanced?.let(parser::documentOf) ?: raw
            val adjusted = if (display != null && settings.customLyricsEnabled) {
                playback?.withOffset(id, source, display, displayRaw) ?: display
            } else display
            parser.forSubmission(adjusted, id, nativeId)
        }
        resultBridge = LyricsResultSubmissionBridge.create(
            symbols = symbols,
            registration = registration,
            currentSong = currentSong,
            fragmentSongId = { seam.currentItemCanonicalIdOf(it, currentSong.current()) },
            nativeFragmentSongId = seam::currentItemAdamIdOf,
            itemSongId = { seam.canonicalAdamIdOf(it, currentSong.current()) },
            selectReplacement = selectReplacement,
            automaticLookupCanDeliver = { id ->
                autoSession?.let { it.hasPendingLookup(id) || it.readyReplacementFor(id) != null } == true
            },
            isAlive = parser::isAlive,
            isCurrentSubmission = parser::isCurrentSubmission,
            documentOf = parser::documentOf,
            logger = ModernXposedRuntime::log,
        ) ?: return TargetCapabilityInstall.Degraded("Lyric ViewModel result contract unavailable")
        registration.onClose(resultBridge::close)
        if (config.settings().customLyricsEnabled) installMenu?.invoke(registration)?.let {
            if (it !is TargetCapabilityInstall.Active) ModernXposedRuntime.log("Lyric menu unavailable: ${it.message}")
        }
        var observedManual: CustomLyricsEntry? = null
        var observedCacheGeneration = playback?.cacheGeneration()
        playback?.let { controls ->
            controls.onSongChanged(currentSong.current()?.details?.appleMusicId)
            registration.onClose(controls.bind(
                prepare = { id, ttml ->
                    parser.parse(ttml)?.takeIf { payload ->
                        parser.isValid(payload) && parser.bindAdamId(payload, id) && parser.adamIdOf(payload) == id
                    }
                },
                isAlive = parser::isAlive,
                onReady = { id ->
                    if (registration.isActive && currentSong.current()?.details?.appleMusicId == id) {
                        val nextGeneration = controls.cacheGeneration()
                        if (nextGeneration != observedCacheGeneration) {
                            observedCacheGeneration = nextGeneration
                            autoSession?.cancelPending(id)
                        }
                        val entry = controls.manualEntry(id)
                        if (entry != observedManual) {
                            observedManual = entry
                            controls.invalidateSource(id, CustomLyricsSources.MANUAL)
                            if (entry?.enabled == true) session.refreshEntry(entry)
                            else session.refreshMappings {
                                if (registration.isActive) resultBridge.onReplacementPublished(id)
                            }
                        }
                        resultBridge.onReplacementPublished(id)
                    }
                },
            )::close)
        }

        val parserHooked = installParserRepair() is TargetCapabilityInstall.Active
        if (parserHooked) {
            parserObserver = { ttml, pointer ->
                if (registration.isActive) {
                    runCatching {
                        val moduleParse = parser.isModuleParse()
                        val metadata = TtmlTimingPolicy.metadataOf(ttml)
                        val appleMusicId = pointer?.let(parser::adamIdOf)
                        timingObservations.record(pointer, metadata, appleMusicId.takeUnless { moduleParse },
                            nativeTtml = ttml.takeIf { !moduleParse && (autoLyricsRuntime?.llm != null || playback != null) })
                        if (
                            !moduleParse &&
                            appleMusicId != null &&
                            currentSong.current()?.details?.appleMusicId == appleMusicId &&
                            !session.isMapped(appleMusicId) && playback?.preferredSource(appleMusicId) == null
                        ) {
                            val sources = automaticLyricsLookupSources(config.settings(), true, metadata)
                            autoSession?.configureSources(appleMusicId, sources)
                            if (sources.isNotEmpty()) autoSession?.ensureRequested(appleMusicId)
                        }
                    }.onFailure { error ->
                        ModernXposedRuntime.log("custom lyrics TTML timing observation failed: $error")
                    }
                }
            }
            registration.onClose { parserObserver = null }
        }
        // Attach the exact host ViewModel and guard an empty native fallback.
        // Selection and recovery still publish through the upstream result channel.
        val hooked = hook(installMethod, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val fragment = param.thisObject ?: return
                val emptyInstall = param.args.isNotEmpty() && param.args[0] == null
                resultBridge.attach(fragment, publish = !emptyInstall)
                if (emptyInstall && resultBridge.suppressEmptyInstall(fragment)) param.result = null
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable == null) param.thisObject?.let { fragment ->
                    resultBridge.onInstalled(fragment, param.args.getOrNull(0))
                }
            }
        })
        if (!hooked) return TargetCapabilityInstall.Degraded("Lyric ViewModel owner hook unavailable")
        val availabilityResolution = symbols.resolve(AppleMusicSymbols.LyricsAvailabilityPredicate)
        val availabilityMethod = availabilityResolution.valueOrNull()
        val availabilityHooked = availabilityMethod != null && runCatching {
            availabilityMethod.isAccessible = true
            hook(availabilityMethod, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val nativeLyricsAvailable = param.result as? Boolean ?: return@runCatching
                        if (nativeLyricsAvailable) return@runCatching
                        val appleMusicId = seam.canonicalAdamIdOf(param.args.getOrNull(0), currentSong.current())
                            ?: return@runCatching
                        val sources = automaticLyricsLookupSources(config.settings(), false, null)
                        session.ensureRequested(appleMusicId)
                        autoSession?.configureSources(appleMusicId, sources)
                        autoSession?.ensureRequested(appleMusicId)
                        val replacementReady = session.replacementOrPrepareFor(appleMusicId) != null ||
                            autoSession?.replacementFor(appleMusicId) != null ||
                            playback?.hasReadyLyrics(appleMusicId) == true
                        // The first host check can gate both the item update and its lyrics request.
                        val cachedLyricsAvailable = !replacementReady && playback?.hasCachedLyrics(
                            appleMusicId,
                            sources.takeUnless { autoSession?.hasLookupMiss(appleMusicId) == true }.orEmpty(),
                        ) == true
                        if (
                            shouldExposeCustomLyrics(
                                nativeLyricsAvailable = nativeLyricsAvailable,
                                appleMusicId = appleMusicId,
                                replacementReady = replacementReady,
                                cachedLyricsAvailable = cachedLyricsAvailable,
                            ) || resultBridge.keepLyricsOpenForLookup(appleMusicId)
                        ) {
                            param.result = true
                        }
                    }.onFailure { error ->
                        ModernXposedRuntime.log("custom lyrics availability hook failed: $error")
                    }
                }
            })
        }.getOrDefault(false)
        session.start()
        val identitySubscription = currentSong.addListener { current ->
            val appleMusicId = current?.details?.appleMusicId
            if (observedManual?.appleMusicId != appleMusicId) observedManual = null
            appleMusicId?.let(session::ensureRequested)
            autoSession?.onSongChanged(appleMusicId)
            playback?.onSongChanged(appleMusicId)
            llmSession?.onSongChanged(appleMusicId)
            resultBridge.onSongChanged(appleMusicId)
            refreshSession?.onSongChanged(appleMusicId, cacheReady = false)
            appleMusicId?.let { id ->
                timingObservations.metadataOfAppleMusicId(id)
                    ?.takeIf { !session.isMapped(id) && playback?.preferredSource(id) == null }
                    ?.let { metadata ->
                        val sources = automaticLyricsLookupSources(config.settings(), true, metadata)
                        autoSession?.configureSources(id, sources)
                        if (sources.isNotEmpty()) autoSession?.ensureRequested(id)
                    }
            }
        }
        registration.onClose(identitySubscription::close)
        if (!availabilityHooked) {
            return TargetCapabilityInstall.Degraded(
                "Custom lyric ViewModel submission installed, but unavailable-lyrics entry could not be enabled; " +
                    availabilityResolution.summary,
            )
        }
        if (!parserHooked && config.settings().let { it.llmLyricsEnabled || it.llmLyricsTransliterationEnabled }) {
            return TargetCapabilityInstall.Degraded(
                "Custom lyric ViewModel submission installed, but LLM native TTML observation unavailable; " +
                    parseMethodResolution.summary,
            )
        }
        return TargetCapabilityInstall.Active(
            "Custom lyric ViewModel submission installed" +
                (if (autoSession != null) " with automatic Word-TTML fallback" else "") + "; " +
                listOf(
                    installMethodResolution.summary,
                    availabilityResolution.summary,
                    "delivery=ViewModel-result",
                    ptrResolution.summary,
                    nativeResolution.summary,
                    parserResolution.summary,
                    parseMethodResolution.summary,
                    "timingHooked=$parserHooked",
                    seam.fieldSummary.orEmpty(),
                ).joinToString("; "),
        )
    }

}

/** Explicit script wins over region; ambiguous Chinese locales are left unchanged. */
internal fun isSimplifiedLyricsLocale(locale: Locale): Boolean {
    if (!locale.language.equals("zh", ignoreCase = true)) return false
    return if (locale.script.isNotEmpty()) locale.script.equals("Hans", ignoreCase = true)
        else locale.country.equals("CN", ignoreCase = true) || locale.country.equals("SG", ignoreCase = true)
}

internal fun selectLyricsInjectionAdamId(
    original: Any?,
    fragmentAdamId: Long?,
    publishedAdamId: Long?,
): Long? = if (
    original == null &&
    publishedAdamId != null &&
    publishedAdamId > 0L &&
    publishedAdamId != fragmentAdamId
) {
    publishedAdamId
} else {
    fragmentAdamId
}

/**
 * Apple emits a null SongInfoPtr when a playback item has no native lyrics,
 * and a live SongInfoPtr otherwise. Both forms can be recorded by the
 * ready-late reapply ledger while their custom replacement is preparing; the
 * ledger applies the same current-item and lifecycle gates to both.
 */
internal fun acceptsLyricsInstallArguments(args: Array<Any?>, ptrClass: Class<*>): Boolean =
    args.isNotEmpty() && (args[0] == null || ptrClass.isInstance(args[0]))

/**
 * Automatic lookup is fail-open for a missing native pointer and opt-in only
 * when the parser seam proved that the original document is not Word-timed or
 * is a foreign Word-timed document without a translation track. An unobserved
 * live pointer is left untouched rather than guessing.
 */
internal fun shouldTryAutoLyrics(
    original: Any?,
    metadata: TtmlDocumentMetadata?,
): Boolean = original == null ||
    shouldTryAutoLyricsForMetadata(metadata)

internal fun shouldTryAutoLyricsForMetadata(metadata: TtmlDocumentMetadata?): Boolean =
    metadata?.timingMode == TtmlTimingMode.NON_WORD || metadata?.needsTranslationFallback == true

internal fun shouldPrepareAutomaticLyrics(
    manualReplacement: Any?,
    autoEligible: Boolean,
): Boolean = manualReplacement == null && autoEligible

internal fun shouldExposeCustomLyrics(
    nativeLyricsAvailable: Boolean,
    appleMusicId: Long?,
    replacementReady: Boolean,
    cachedLyricsAvailable: Boolean = false,
): Boolean = nativeLyricsAvailable ||
    (appleMusicId != null && appleMusicId > 0L && (replacementReady || cachedLyricsAvailable))
