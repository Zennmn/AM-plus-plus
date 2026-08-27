package io.github.proify.lyricon.amprovider.xposed

import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Catalog transport has a deliberately small seam: host work is submitted to the main
 * executor, host values are copied there, and only immutable snapshots reach the CPU executor.
 */
class AppleCatalogResponseWorkDispatcherTest {

    @Test
    fun `host query and snapshot run on main while transformation runs in background`() {
        val main = ManualExecutor("main")
        val background = ManualExecutor("catalog-cpu")
        val dispatcher = AppleCatalogResponseWorkDispatcher(main, background)
        val stages = mutableListOf<String>()
        var result: Int? = null

        dispatcher.dispatchHostQuery(
            invokeHostOnMain = {
                stages += "invoke:${main.currentStage}"
                "host-value"
            },
            snapshotOnMain = { value ->
                stages += "snapshot:${main.currentStage}"
                value?.length
            },
            transformOffMain = { value ->
                stages += "transform:${background.currentStage}"
                value?.times(2)
            },
            publishOnMain = { value ->
                stages += "publish:${main.currentStage}"
                result = value
            },
            failOnMain = { error -> error.printStackTrace() },
        )

        assertEquals(emptyList<String>(), stages)
        main.runNext()
        assertEquals(listOf("invoke:main"), stages)
        main.runNext()
        assertEquals(listOf("invoke:main", "snapshot:main"), stages)
        background.runNext()
        assertEquals(listOf("invoke:main", "snapshot:main", "transform:catalog-cpu"), stages)
        main.runNext()

        assertEquals(
            listOf("invoke:main", "snapshot:main", "transform:catalog-cpu", "publish:main"),
            stages,
        )
        assertEquals(20, result)
    }

    @Test
    fun `host response is snapshotted inline when its callback is already on main`() {
        val main = ManualExecutor("main")
        val background = ManualExecutor("catalog-cpu")
        val dispatcher = AppleCatalogResponseWorkDispatcher(
            mainExecutor = main,
            backgroundExecutor = background,
            isMainThread = { main.currentStage == "main" },
        )
        val stages = mutableListOf<String>()
        val task = dispatcher.newTask<String, String, String>(
            snapshotOnMain = { value ->
                stages += "snapshot:${main.currentStage}"
                requireNotNull(value)
            },
            transformOffMain = { value ->
                stages += "transform:${background.currentStage}"
                requireNotNull(value)
            },
            publishOnMain = { stages += "publish:${main.currentStage}" },
            failOnMain = { error -> error.printStackTrace() },
        )

        main.execute { task.submit("host-value") }
        main.runNext()

        assertEquals(listOf("snapshot:main"), stages)
        background.runNext()
        main.runNext()
        assertEquals(
            listOf("snapshot:main", "transform:catalog-cpu", "publish:main"),
            stages,
        )
    }

    @Test
    fun `empty host response publishes empty result and never touches host off main`() {
        val main = ManualExecutor("main")
        val background = ManualExecutor("catalog-cpu")
        val dispatcher = AppleCatalogResponseWorkDispatcher(main, background)
        var transformed = "not-called"
        var published: String? = "not-called"

        dispatcher.dispatchResponse<String, String?, String?>(
            hostResponse = null,
            snapshotOnMain = { response ->
                assertNull(response)
                null
            },
            transformOffMain = { snapshot ->
                transformed = if (snapshot == null) "empty" else "unexpected"
                null
            },
            publishOnMain = { value -> published = value },
            failOnMain = { error -> error.printStackTrace() },
        )
        main.runAll()
        background.runAll()
        main.runAll()

        assertEquals("empty", transformed)
        assertNull(published)
    }

    @Test
    fun `host, snapshot, and transform errors fail open on main`() {
        val cases = listOf("host", "snapshot", "transform")
        cases.forEach { failureStage ->
            val main = ManualExecutor("main")
            val background = ManualExecutor("catalog-cpu")
            val dispatcher = AppleCatalogResponseWorkDispatcher(main, background)
            val failures = mutableListOf<Throwable>()
            var published = false
            dispatcher.dispatchHostQuery(
                invokeHostOnMain = {
                    if (failureStage == "host") error("host-failure")
                    "host"
                },
                snapshotOnMain = {
                    if (failureStage == "snapshot") error("snapshot-failure")
                    1
                },
                transformOffMain = {
                    if (failureStage == "transform") error("transform-failure")
                    2
                },
                publishOnMain = { published = true },
                failOnMain = { failures += it },
            )
            main.runAll()
            background.runAll()
            main.runAll()

            assertFalse("published for $failureStage", published)
            assertEquals("failure count for $failureStage", 1, failures.size)
            assertEquals("$failureStage-failure", failures.single().message)
        }
    }

    @Test
    fun `cancelled and repeated responses publish at most once`() {
        val main = ManualExecutor("main")
        val background = ManualExecutor("catalog-cpu")
        val dispatcher = AppleCatalogResponseWorkDispatcher(main, background)
        var published = 0
        val task = dispatcher.newTask<String, String, String?>(
            snapshotOnMain = { it },
            transformOffMain = { it },
            publishOnMain = { published++ },
            failOnMain = { error -> error.printStackTrace() },
        )

        assertTrue(task.submit("first"))
        assertFalse(task.submit("late"))
        assertTrue(task.cancel())
        main.runAll()
        background.runAll()
        main.runAll()
        assertEquals(0, published)

        val secondTask = dispatcher.newTask<String, String, String?>(
            snapshotOnMain = { it },
            transformOffMain = { it },
            publishOnMain = { published++ },
            failOnMain = { error -> error.printStackTrace() },
        )
        assertTrue(secondTask.submit("accepted"))
        main.runAll()
        background.runAll()
        main.runAll()
        assertEquals(1, published)
        assertFalse(secondTask.submit("late-again"))
    }

    @Test
    fun `cancelling before the main adapter runs skips host invocation`() {
        val main = ManualExecutor("main")
        val background = ManualExecutor("catalog-cpu")
        val dispatcher = AppleCatalogResponseWorkDispatcher(main, background)
        var invoked = false
        val task = dispatcher.dispatchHostQuery<String, String, String>(
            invokeHostOnMain = {
                invoked = true
                "unexpected"
            },
            snapshotOnMain = { it },
            transformOffMain = { it ?: "unexpected" },
            publishOnMain = {},
            failOnMain = { error -> error.printStackTrace() },
        )
        assertTrue(task.cancel())
        main.runAll()
        assertFalse(invoked)
    }

    private class ManualExecutor(private val label: String) : Executor {
        private val queue = ArrayDeque<Runnable>()
        var currentStage: String = "idle"
            private set

        override fun execute(command: Runnable) {
            queue.addLast(command)
        }

        fun runNext() {
            val command = queue.removeFirst()
            currentStage = label
            command.run()
            currentStage = "idle"
        }

        fun runAll() {
            while (queue.isNotEmpty()) runNext()
        }
    }
}
