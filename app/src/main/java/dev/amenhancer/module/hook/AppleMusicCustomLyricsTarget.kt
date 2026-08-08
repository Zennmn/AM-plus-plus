package dev.amenhancer.module.hook

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import dev.amenhancer.module.lyrics.CustomLyricsFileReader
import dev.amenhancer.module.model.CustomLyricsEntry
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Apple Music 6.5.0 adapter for user-managed, offline ID -> TTML mappings. */
internal class AppleMusicCustomLyricsTarget(
    private val config: TargetConfigClient,
    private val symbols: TargetSymbolResolver,
    private val currentSong: CurrentSongIdentityCache,
) : CustomLyricsTarget {
    override fun install(): TargetCapabilityInstall {
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
        val parser = TtmlNativeParser.create(
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
        val fileReader = CustomLyricsFileReader { fileId ->
            config.openRemoteFile(fileId)?.let { descriptor ->
                runCatching {
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use(
                        CustomLyricsFilePolicy::readBounded,
                    )
                }.getOrNull()
            }
        }
        val mainHandler = Handler(Looper.getMainLooper())
        lateinit var readyReapply: CustomLyricsReadyReapply
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                config.customLyricsManifest().entries.associateBy(
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
                mainHandler.post { readyReapply.onReplacementPublished(appleMusicId) }
            },
            executor = ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(1),
                { runnable -> Thread(runnable, "ampp-custom-lyrics").apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy(),
            ),
            logger = ModernXposedRuntime::log,
        )
        val fragmentUsable = fragmentIsAddedPredicate(installMethod.declaringClass)
        readyReapply = CustomLyricsReadyReapply(
            installMethod = installMethod,
            seam = seam,
            readyReplacementFor = session::readyReplacementFor,
            isFragmentUsable = fragmentUsable,
            currentSong = currentSong,
            logger = ModernXposedRuntime::log,
        )
        val itemUpdateContext = LyricsItemUpdateContext()
        val hooked = runCatching {
            ModernXposedRuntime.hookMethod(installMethod, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    itemUpdateContext.markAppleInvokedI2()
                    runCatching {
                        if (!acceptsLyricsInstallArguments(param.args, ptrClass)) return@runCatching
                        val original = param.args[0]
                        val fragmentAdamId = seam.currentItemAdamIdOf(param.thisObject)
                        val publishedCurrent = currentSong.current()
                        val publishedAdamId = publishedCurrent?.details?.appleMusicId
                        val adamId = selectLyricsInjectionAdamId(
                            original = original,
                            fragmentAdamId = fragmentAdamId,
                            publishedAdamId = publishedAdamId,
                        )
                        adamId ?: return@runCatching
                        val replacement = session.replacementFor(adamId)
                        val tracking = session.isTracking(adamId)
                        val needsRebind = original == null &&
                            publishedCurrent != null &&
                            publishedAdamId != null &&
                            publishedAdamId != fragmentAdamId
                        val canRebind = currentSong.canRebind(fragmentAdamId, publishedAdamId)
                        if (
                            needsRebind && tracking && canRebind &&
                            (fragmentAdamId == null || session.isMapped(adamId))
                        ) {
                            val rebound = param.thisObject?.let { fragment ->
                                seam.bindCurrentItemOf(fragment, publishedCurrent.item)
                            } == true
                            if (!rebound) return@runCatching
                        }
                        if (replacement == null) {
                            if (shouldRecordReadyLateMiss(original, replacement) && tracking) {
                                param.thisObject?.let { readyReapply.recordMiss(it, adamId) }
                            }
                        } else {
                            param.thisObject?.let { readyReapply.dismiss(it) }
                            if (replacement !== original) {
                                param.args[0] = replacement
                            }
                        }
                    }.onFailure { error ->
                        ModernXposedRuntime.log("custom lyrics I2 replacement hook failed: $error")
                    }
                }
            })
        }.isSuccess
        if (!hooked) {
            return TargetCapabilityInstall.Degraded(
                "PlayerLyricsViewFragment.I2 could not be hooked; ${installMethodResolution.summary}",
            )
        }
        val itemUpdateResolution = symbols.resolve(AppleMusicSymbols.LyricsItemUpdateMethod)
        val itemUpdateMethod = itemUpdateResolution.valueOrNull()
        if (itemUpdateMethod != null) {
            val coordinator = runCatching {
                LyricsItemUpdateCoordinator(
                    installMethod = installMethod,
                    flags = ItemUpdateFlags(itemUpdateMethod.parameterTypes[2]),
                    seam = seam,
                    readyReplacementFor = session::readyReplacementFor,
                    isTracking = session::isTracking,
                    isFragmentUsable = fragmentUsable,
                    readyReapply = readyReapply,
                    logger = ModernXposedRuntime::log,
                )
            }.getOrNull()
            if (coordinator != null) {
                val itemUpdateHooked = runCatching {
                    ModernXposedRuntime.hookMethod(itemUpdateMethod, object : ModernMethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            itemUpdateContext.enterO2()
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val fragment = param.thisObject
                                val appleInvokedI2 = itemUpdateContext.appleInvokedI2DuringO2()
                                val flagsHolder = param.args.getOrNull(2)
                                itemUpdateContext.reentering {
                                    runCatching {
                                        fragment?.let { currentFragment ->
                                            coordinator.onItemUpdate(
                                                fragment = currentFragment,
                                                flagsHolder = flagsHolder,
                                                appleInvokedI2 = appleInvokedI2,
                                            )
                                        }
                                    }.onFailure { error ->
                                        ModernXposedRuntime.log(
                                            "custom lyrics item update hook failed: $error",
                                        )
                                    }
                                }
                            } finally {
                                itemUpdateContext.exitO2()
                            }
                        }
                    })
                }.isSuccess
                if (!itemUpdateHooked) {
                    ModernXposedRuntime.log(
                        "PlayerLyricsViewFragment.o2 could not be hooked; " +
                            itemUpdateResolution.summary,
                    )
                }
            }
        }
        val availabilityResolution = symbols.resolve(AppleMusicSymbols.LyricsAvailabilityPredicate)
        val availabilityMethod = availabilityResolution.valueOrNull()
        val availabilityHooked = availabilityMethod != null && runCatching {
            availabilityMethod.isAccessible = true
            ModernXposedRuntime.hookMethod(availabilityMethod, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val nativeLyricsAvailable = param.result as? Boolean ?: return@runCatching
                        if (nativeLyricsAvailable) return@runCatching
                        val appleMusicId = seam.detailsOfItem(param.args.getOrNull(0))?.appleMusicId
                        appleMusicId?.let(session::ensureRequested)
                        val replacementReady = appleMusicId != null &&
                            session.replacementOrPrepareFor(appleMusicId) != null
                        if (
                            shouldExposeCustomLyrics(
                                nativeLyricsAvailable = nativeLyricsAvailable,
                                appleMusicId = appleMusicId,
                                replacementReady = replacementReady,
                            )
                        ) {
                            param.result = true
                        }
                    }.onFailure { error ->
                        ModernXposedRuntime.log("custom lyrics availability hook failed: $error")
                    }
                }
            })
        }.isSuccess
        session.start()
        currentSong.addListener { current ->
            current?.details?.appleMusicId?.let(session::ensureRequested)
        }
        if (!availabilityHooked) {
            return TargetCapabilityInstall.Degraded(
                "Custom lyric I2 replacement installed, but unavailable-lyrics entry could not be enabled; " +
                    availabilityResolution.summary,
            )
        }
        return TargetCapabilityInstall.Active(
            "Custom lyric ID mappings installed; " +
                listOf(
                    installMethodResolution.summary,
                    availabilityResolution.summary,
                    itemUpdateResolution.summary,
                    ptrResolution.summary,
                    nativeResolution.summary,
                    parserResolution.summary,
                    parseMethodResolution.summary,
                    seam.fieldSummary.orEmpty(),
                ).joinToString("; "),
        )
    }

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

internal fun shouldExposeCustomLyrics(
    nativeLyricsAvailable: Boolean,
    appleMusicId: Long?,
    replacementReady: Boolean,
): Boolean = nativeLyricsAvailable || (appleMusicId != null && appleMusicId > 0L && replacementReady)
