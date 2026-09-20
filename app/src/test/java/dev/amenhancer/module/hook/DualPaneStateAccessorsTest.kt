package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 6.5.3 (1599) host renamed the dual-pane state enum's fragment accessor from f() to e()
 * (com.apple.android.music.player.fragment.v0$n), which silently failed the right-pane lyrics
 * attachment on tablets. These tests pin the shape contract that keeps the attachment working on
 * both verified builds, the fail-closed behaviour for an ambiguous future shape, and the fact that
 * the target no longer looks the accessors or the state LiveData up by a 6.5.2 member name.
 */
class DualPaneStateAccessorsTest {
    private val source: String by lazy {
        sequenceOf(
            File("src/main/java/dev/amenhancer/module/hook/AppleMusicDualPaneTarget.kt"),
            File("app/src/main/java/dev/amenhancer/module/hook/AppleMusicDualPaneTarget.kt"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("AppleMusicDualPaneTarget.kt was not found from the unit-test working directory")
    }

    @Test
    fun `resolves the 6_5_2 state accessors by shape`() {
        val stateClass = stateEnum("t0")

        assertEquals("f", DualPaneStateAccessors.fragment(stateClass)?.name)
        assertEquals("g", DualPaneStateAccessors.tag(stateClass)?.name)
    }

    @Test
    fun `resolves the 6_5_3 state accessors after the fragment accessor rename`() {
        val stateClass = stateEnum("v0")

        assertEquals("e", DualPaneStateAccessors.fragment(stateClass)?.name)
        assertEquals("g", DualPaneStateAccessors.tag(stateClass)?.name)
    }

    @Test
    fun `fails closed when two fragment accessors share the same shape`() {
        assertNull(DualPaneStateAccessors.fragment(stateEnum("x0")))
    }

    @Test
    fun `never looks the pane state accessors up by member name`() {
        assertTrue(source.contains("DualPaneStateAccessors.fragment(playerStateClass)"))
        assertTrue(source.contains("DualPaneStateAccessors.tag(playerStateClass)"))
        assertFalse(source.contains("callMethod(song, \"f\")"))
        assertFalse(source.contains("callMethod(lyrics, \"f\")"))
        assertFalse(source.contains("callMethod(song, \"g\")"))
        assertFalse(source.contains("callMethod(lyrics, \"g\")"))
    }

    @Test
    fun `locates the controller state LiveData by type instead of by its 6_5_2 field name`() {
        assertTrue(source.contains("findFieldByType(controller.javaClass)"))
        assertFalse(source.contains("findField(controller.javaClass, \"S\")"))
    }

    private fun stateEnum(controllerName: String): Class<*> =
        Class.forName("com.apple.android.music.player.fragment.$controllerName\$n")
}
