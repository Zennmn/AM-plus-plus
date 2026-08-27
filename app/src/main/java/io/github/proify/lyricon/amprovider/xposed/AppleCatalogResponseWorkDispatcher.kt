package io.github.proify.lyricon.amprovider.xposed

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dispatches a Catalog response across the host/CPU boundary.
 *
 * Host objects are only touched on the main thread: directly when [isMainThread] is true, or via
 * [mainExecutor] otherwise. The CPU callback receives the immutable value returned by
 * [snapshotOnMain], never the host response itself. Both completion and failure are published on
 * [mainExecutor] so existing resolver callbacks retain their main-thread contract.
 */
internal class AppleCatalogResponseWorkDispatcher(
    private val mainExecutor: Executor,
    private val backgroundExecutor: Executor,
    /** Allows a host-thread response to be copied before its callback returns. */
    private val isMainThread: () -> Boolean = { false },
) {

    internal interface ResponseTask<in Host> {
        /** Accepts the first response; late/repeated host callbacks are ignored. */
        fun submit(hostResponse: Host?): Boolean

        /** Reports a synchronous host invocation failure through the normal completion seam. */
        fun fail(error: Throwable): Boolean

        /** Prevents extraction, transformation, and publication when the request times out. */
        fun cancel(): Boolean

        val isCancelled: Boolean
    }

    internal fun <Host, Snapshot, Result> newTask(
        snapshotOnMain: (Host?) -> Snapshot?,
        transformOffMain: (Snapshot?) -> Result,
        publishOnMain: (Result) -> Unit,
        failOnMain: (Throwable) -> Unit,
    ): ResponseTask<Host> {
        val accepted = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val published = AtomicBoolean(false)

        fun publishFailure(error: Throwable) {
            mainExecutor.execute {
                if (cancelled.get() || !published.compareAndSet(false, true)) return@execute
                failOnMain(error)
            }
        }

        fun snapshotAndTransform(hostResponse: Host?) {
            if (cancelled.get()) return
            val snapshot = try {
                snapshotOnMain(hostResponse)
            } catch (error: Throwable) {
                publishFailure(error)
                return
            }
            if (cancelled.get()) return
            try {
                backgroundExecutor.execute {
                    if (cancelled.get()) return@execute
                    val transformed = try {
                        transformOffMain(snapshot)
                    } catch (error: Throwable) {
                        publishFailure(error)
                        return@execute
                    }
                    mainExecutor.execute {
                        if (cancelled.get() || !published.compareAndSet(false, true)) {
                            return@execute
                        }
                        publishOnMain(transformed)
                    }
                }
            } catch (error: Throwable) {
                publishFailure(error)
            }
        }

        return object : ResponseTask<Host> {
            override fun submit(hostResponse: Host?): Boolean {
                if (cancelled.get() || !accepted.compareAndSet(false, true)) return false
                if (isMainThread()) {
                    snapshotAndTransform(hostResponse)
                } else {
                    try {
                        mainExecutor.execute { snapshotAndTransform(hostResponse) }
                    } catch (error: Throwable) {
                        publishFailure(error)
                    }
                }
                return true
            }

            override fun fail(error: Throwable): Boolean {
                if (cancelled.get() || !accepted.compareAndSet(false, true)) return false
                publishFailure(error)
                return true
            }

            override fun cancel(): Boolean {
                if (published.get()) return false
                return cancelled.compareAndSet(false, true)
            }

            override val isCancelled: Boolean
                get() = cancelled.get()
        }
    }

    /**
     * Starts a host query on the main executor and then processes its synchronous result.  The
     * resolver uses [newTask] for suspend/continuation requests; this helper is kept as a tested
     * adapter seam for host invocation and for non-suspending query contracts.
     */
    internal fun <Host, Snapshot, Result> dispatchHostQuery(
        invokeHostOnMain: () -> Host?,
        snapshotOnMain: (Host?) -> Snapshot?,
        transformOffMain: (Snapshot?) -> Result,
        publishOnMain: (Result) -> Unit,
        failOnMain: (Throwable) -> Unit,
    ): ResponseTask<Host> {
        val task = newTask(
            snapshotOnMain = snapshotOnMain,
            transformOffMain = transformOffMain,
            publishOnMain = publishOnMain,
            failOnMain = failOnMain,
        )
        mainExecutor.execute {
            if (task.isCancelled) return@execute
            try {
                task.submit(invokeHostOnMain())
            } catch (error: Throwable) {
                task.fail(error)
            }
        }
        return task
    }

    internal fun <Host, Snapshot, Result> dispatchResponse(
        hostResponse: Host?,
        snapshotOnMain: (Host?) -> Snapshot?,
        transformOffMain: (Snapshot?) -> Result,
        publishOnMain: (Result) -> Unit,
        failOnMain: (Throwable) -> Unit,
    ): ResponseTask<Host> = newTask(
        snapshotOnMain = snapshotOnMain,
        transformOffMain = transformOffMain,
        publishOnMain = publishOnMain,
        failOnMain = failOnMain,
    ).also { it.submit(hostResponse) }
}
