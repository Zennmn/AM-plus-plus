package dev.amenhancer.module.hook

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.os.SystemClock
import dev.amenhancer.module.LibraryRefreshProtocol
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Calls Apple's own library poll.  This is intentionally separate from the
 * title hooks: a missing private MediaLibrary symbol only disables this
 * button, never the player or lyrics features.
 */
internal class AppleMusicLibraryRefreshTarget(
    private val application: Application,
    private val symbols: TargetSymbolResolver,
    private val classLoader: ClassLoader,
    private val targetLanguage: String = "",
    private val titleCache: CatalogTitleCache? = null,
    private val titleCacheProvider: CatalogTitleCacheProvider? = null,
    private val catalogLookup: CatalogEntityLookup? = null,
) : LibraryRefreshTarget {
    override fun install(): TargetCapabilityInstall {
        val libraryType = symbols.resolve(AppleMusicSymbols.MediaLibraryType).valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(
                symbols.resolve(AppleMusicSymbols.MediaLibraryType).summary,
            )
        val singletonResolution = symbols.resolve(AppleMusicSymbols.MediaLibrarySingletonMethod)
        val singleton = singletonResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(singletonResolution.summary)
        val updateResolution = symbols.resolve(AppleMusicSymbols.MediaLibraryUpdateMethod)
        val update = updateResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(updateResolution.summary)
        val readyResolution = symbols.resolve(AppleMusicSymbols.MediaLibraryReadyMethod)
        val ready = readyResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(readyResolution.summary)
        val pointerResolution = symbols.resolve(AppleMusicSymbols.MediaLibraryNativePointerMethod)
        val pointer = pointerResolution.valueOrNull()
        val nativeRefreshResolution = symbols.resolve(
            AppleMusicSymbols.MediaLibraryNativeCatalogRefreshMethod,
        )
        val nativeRefresh = nativeRefreshResolution.valueOrNull()

        val updateReason = update.parameterTypes.singleOrNull()
            ?.enumConstants
            ?.firstOrNull { (it as? Enum<*>)?.name == USER_INITIATED_POLL }
            ?: return TargetCapabilityInstall.Degraded(
                "MediaLibrary update reason UserInitiatedPoll was not found",
            )

        if (!runCatching {
                singleton.isAccessible = true
                update.isAccessible = true
                ready.isAccessible = true
                pointer?.isAccessible = true
                nativeRefresh?.isAccessible = true
                true
            }.getOrDefault(false)
        ) {
            return TargetCapabilityInstall.Degraded(
                "MediaLibrary refresh methods could not be made accessible",
            )
        }

        val responder = LibraryRefreshRequestResponder(
            application = application,
            singleton = singleton,
            update = update,
            ready = ready,
            nativePointer = pointer,
            nativeCatalogRefresh = nativeRefresh,
            catalogBackfill = AppleMusicCatalogBackfill(
                application = application,
                symbols = symbols,
                targetLanguage = targetLanguage,
                classLoader = classLoader,
                logger = ModernXposedRuntime::log,
                titleCache = titleCache,
                titleCacheProvider = titleCacheProvider,
                catalogLookup = catalogLookup,
            ),
            targetLanguage = targetLanguage,
            updateReason = updateReason,
            logger = ModernXposedRuntime::log,
        )
        if (!responder.register()) {
            return TargetCapabilityInstall.Degraded(
                "Library refresh request receiver could not be registered",
            )
        }
        return TargetCapabilityInstall.Active(
            "Apple Music library refresh installed for ${libraryType.name}; " +
                listOf(
                    singletonResolution.summary,
                    updateResolution.summary,
                    readyResolution.summary,
                    pointerResolution.valueOrNull()?.let { pointerResolution.summary },
                    nativeRefreshResolution.valueOrNull()?.let { nativeRefreshResolution.summary },
                )
                    .filterNotNull()
                    .joinToString("; "),
        )
    }

    private companion object {
        const val USER_INITIATED_POLL = "UserInitiatedPoll"
    }
}
internal fun interface LibraryRefreshTarget {
    fun install(): TargetCapabilityInstall
}

/**
 * AMTool-style refresh coordinator: a CAS guard rejects a second refresh while
 * one is running, a token-scoped cancel broadcast flips the task's cancel flag
 * and bumps its generation, and every in-flight batch compares its captured
 * generation before writing so late responses are discarded after a cancel.
 * A ready timeout is a completed refresh that skipped the backfill (AMTool
 * reports "等待资料库就绪超时，已跳过批量补查"), never a native refresh failure.
 */
private class LibraryRefreshRequestResponder(
    private val application: Application,
    private val singleton: Method,
    private val update: Method,
    private val ready: Method,
    private val nativePointer: Method?,
    private val nativeCatalogRefresh: Method?,
    private val catalogBackfill: AppleMusicCatalogBackfill,
    private val targetLanguage: String,
    private val updateReason: Any,
    private val logger: (String) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "am++-library-refresh").apply { isDaemon = true }
    }
    /** Keeps callback-side readiness polling off both the host and wait thread. */
    private val finishExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "am++-library-refresh-finish").apply { isDaemon = true }
    }
    private val activeTask = AtomicReference<ActiveRefreshTask?>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                LibraryRefreshProtocol.REQUEST_ACTION -> handleRequest(intent)
                LibraryRefreshProtocol.CANCEL_ACTION -> handleCancel(intent)
            }
        }
    }

    private fun handleRequest(intent: Intent) {
        val token = intent.getStringExtra(LibraryRefreshProtocol.EXTRA_REQUEST_TOKEN)
            ?.takeIf(String::isNotBlank)
            ?: return
        val resultReceiver = intent.resultReceiver() ?: return
        val task = ActiveRefreshTask(token)
        if (!activeTask.compareAndSet(null, task)) {
            // AMTool rejects a second refresh while one is running.
            send(resultReceiver, token, RESULT_FAILED, "刷新和补查正在进行")
            return
        }
        executor.execute {
            try {
                task.run { result ->
                    send(resultReceiver, token, result.first, result.second)
                }
            } catch (error: Throwable) {
                logger("manual library refresh callback failed: $error")
                send(resultReceiver, token, RESULT_FAILED, "资料库刷新回调失败：${error.message.orEmpty()}")
            } finally {
                activeTask.compareAndSet(task, null)
            }
        }
    }

    private fun handleCancel(intent: Intent) {
        val token = intent.getStringExtra(LibraryRefreshProtocol.EXTRA_REQUEST_TOKEN)
            ?.takeIf(String::isNotBlank)
            ?: return
        val task = activeTask.get() ?: return
        if (task.token != token) return
        task.requestCancel()
    }

    private fun send(receiver: ResultReceiver, token: String, code: Int, message: String) {
        receiver.send(
            code,
            Bundle().apply {
                putString(LibraryRefreshProtocol.EXTRA_REQUEST_TOKEN, token)
                putString(LibraryRefreshProtocol.EXTRA_RESULT_MESSAGE, message)
            },
        )
    }

    private inner class ActiveRefreshTask(val token: String) {
        private val cancelled = AtomicBoolean(false)
        private val generation = AtomicLong(0L)

        fun requestCancel() {
            if (cancelled.compareAndSet(false, true)) {
                generation.incrementAndGet()
                logger("刷新资料库：已停止（等待协作点）")
            }
        }

        private fun cancellation() = object : CatalogRefreshCancellation {
            override fun isCancelled(): Boolean = cancelled.get()
            override fun generation(): Long = generation.get()
        }

        fun run(onResult: (Pair<Int, String>) -> Unit) {
            trigger(onResult)
        }

        private fun trigger(onResult: (Pair<Int, String>) -> Unit) = runCatching {
            val library = singleton.invoke(null)
                ?: return@runCatching onResult(RESULT_FAILED to "MediaLibrary 单例返回 null")
            val operation = update.invoke(library, updateReason)
                ?: return@runCatching onResult(RESULT_FAILED to "资料库刷新未返回异步操作")
            val completed = AtomicBoolean(false)
            val completion = CountDownLatch(1)
            val subscribed = subscribeCallbackOperation(
                operation,
                onSuccess = { args ->
                    finishExecutor.execute {
                        finishRefresh(
                            library,
                            completed,
                            completion,
                            onResult,
                            (args?.firstOrNull() as? Any)?.toString().orEmpty(),
                        )
                    }
                },
                onError = { args ->
                    finishExecutor.execute {
                        if (!cancelled.get() && completed.compareAndSet(false, true)) {
                            onResult(RESULT_FAILED to "资料库刷新失败：${args?.firstOrNull()}")
                        }
                        completion.countDown()
                    }
                },
                defaultValue = ::defaultValue,
            )
            if (!subscribed) {
                return@runCatching onResult(RESULT_FAILED to "资料库刷新完成回调不可用")
            }
            logger("手动刷新资料库：已触发 updateLibrary(UserInitiatedPoll)，等待完成")
            if (!completion.await(REFRESH_TIMEOUT_SECONDS, TimeUnit.SECONDS) &&
                completed.compareAndSet(false, true)
            ) {
                onResult(RESULT_FAILED to "资料库刷新超时（${REFRESH_TIMEOUT_SECONDS} 秒）")
            }
        }.getOrElse { error ->
            logger("manual library refresh failed: $error")
            onResult(RESULT_FAILED to "资料库刷新触发失败：${error.message.orEmpty()}")
        }

        private fun finishRefresh(
            library: Any,
            completed: AtomicBoolean,
            completion: CountDownLatch,
            onResult: (Pair<Int, String>) -> Unit,
            operationMessage: String,
        ) {
            if (completed.get()) return
            if (cancelled.get()) {
                if (completed.compareAndSet(false, true)) {
                    onResult(RESULT_CANCELLED to "已停止刷新资料库")
                }
                completion.countDown()
                return
            }
            logger("手动刷新资料库：原生刷新完成")
            runCatching {
                val pointerMethod = nativePointer
                val nativeRefreshMethod = nativeCatalogRefresh
                if (pointerMethod != null && nativeRefreshMethod != null) {
                    runCatching {
                        val pointer = pointerMethod.invoke(library)
                            ?: error("SVMediaLibraryPtr 返回 null")
                        val native = pointer.javaClass.methods.singleOrNull {
                            it.name == "get" && it.parameterCount == 0
                        }?.apply { isAccessible = true }?.invoke(pointer)
                            ?: error("SVMediaLibraryNative 返回 null")
                        nativeRefreshMethod.invoke(native)
                    }.onFailure { logger("native Catalog refresh degraded: $it") }
                }
            }
            if (targetLanguage.isBlank()) {
                if (completed.compareAndSet(false, true)) {
                    onResult(
                        RESULT_COMPLETED to
                            "资料库刷新完成；歌曲名显示修正未启用或目标语言不可用，已跳过批量补查" +
                            operationMessage.takeIf(String::isNotBlank)?.let { "（$it）" }.orEmpty(),
                    )
                }
                completion.countDown()
                return
            }
            if (cancelled.get()) {
                if (completed.compareAndSet(false, true)) {
                    onResult(RESULT_CANCELLED to "已停止刷新资料库")
                }
                completion.countDown()
                return
            }
            if (!awaitReady(library)) {
                if (cancelled.get()) {
                    if (completed.compareAndSet(false, true)) {
                        onResult(RESULT_CANCELLED to "已停止刷新资料库")
                    }
                } else {
                    // AMTool: ready timeout means the native refresh completed;
                    // skip the backfill and report completion, never a failure.
                    if (completed.compareAndSet(false, true)) {
                        onResult(
                            RESULT_COMPLETED to
                                "资料库刷新完成，但等待资料库就绪超时，已跳过批量补查" +
                                operationMessage.takeIf(String::isNotBlank)?.let { "（$it）" }.orEmpty(),
                        )
                    }
                }
                completion.countDown()
                return
            }
            val backfill = catalogBackfill.run(library, cancellation())
            val message = when {
                backfill.skipped -> "资料库刷新完成；未执行 Catalog 回填（歌曲名显示修正未启用）"
                backfill.error != null -> "资料库刷新完成，但 ${backfill.completionMessage()}"
                else -> backfill.completionMessage()
            }
            if (cancelled.get()) {
                if (completed.compareAndSet(false, true)) {
                    onResult(RESULT_CANCELLED to "已停止刷新资料库")
                }
            } else if (completed.compareAndSet(false, true)) {
                onResult(
                    RESULT_COMPLETED to message +
                        operationMessage.takeIf(String::isNotBlank)?.let { "（$it）" }.orEmpty(),
                )
            }
            completion.countDown()
        }

        /**
         * AMTool polls isReady every 200 ms for up to 30 s on a dedicated
         * daemon thread and cancels cooperatively between polls.
         */
        private fun awaitReady(library: Any): Boolean {
            val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MILLIS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (cancelled.get()) return false
                if (runCatching { ready.invoke(library) as? Boolean == true }.getOrDefault(false)) {
                    return true
                }
                SystemClock.sleep(READY_POLL_INTERVAL_MILLIS)
            }
            if (cancelled.get()) return false
            return runCatching { ready.invoke(library) as? Boolean == true }.getOrDefault(false)
        }

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Char::class.javaPrimitiveType -> '\u0000'
            else -> null
        }
    }

    @Suppress("unused")
    private fun shutdown() {
        executor.shutdownNow()
        finishExecutor.shutdownNow()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun register(): Boolean = runCatching {
        val filter = IntentFilter().apply {
            addAction(LibraryRefreshProtocol.REQUEST_ACTION)
            addAction(LibraryRefreshProtocol.CANCEL_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(
                receiver,
                filter,
                LibraryRefreshProtocol.REQUEST_PERMISSION,
                null,
                Context.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            application.registerReceiver(
                receiver,
                filter,
                LibraryRefreshProtocol.REQUEST_PERMISSION,
                null,
            )
        }
        true
    }.onFailure { error ->
        logger("library refresh request receiver failed: $error")
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun Intent.resultReceiver(): ResultReceiver? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(
                LibraryRefreshProtocol.EXTRA_RESULT_RECEIVER,
                ResultReceiver::class.java,
            )
        } else {
            getParcelableExtra(LibraryRefreshProtocol.EXTRA_RESULT_RECEIVER)
        }

    private companion object {
        const val RESULT_FAILED = LibraryRefreshProtocol.RESULT_FAILED
        const val RESULT_COMPLETED = LibraryRefreshProtocol.RESULT_COMPLETED
        const val RESULT_CANCELLED = LibraryRefreshProtocol.RESULT_CANCELLED
        const val READY_POLL_INTERVAL_MILLIS = 200L
        const val READY_TIMEOUT_MILLIS = 30_000L
        const val REFRESH_TIMEOUT_SECONDS = 300L
    }
}
