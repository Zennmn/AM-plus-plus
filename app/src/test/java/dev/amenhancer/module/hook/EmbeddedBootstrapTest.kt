package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.ConfigurationReader
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedBootstrapTest {
    @Test
    fun `an empty package-ready process name is deferred to application verification`() {
        val bootstrap = EmbeddedBootstrap()

        assertTrue(
            bootstrap.prepare(
                packageName = ModuleConstants.TARGET_PACKAGE,
                processName = "",
                isFirstPackage = true,
            ),
        )
    }

    @Test
    fun `only the current host main process first package prepares once`() {
        val bootstrap = EmbeddedBootstrap()
        val packageName = "com.apple.android.music.clone"

        assertFalse(
            bootstrap.prepare(
                packageName = packageName,
                processName = "$packageName:service",
                isFirstPackage = true,
            ),
        )
        assertFalse(
            bootstrap.prepare(
                packageName = "other.package",
                processName = packageName,
                isFirstPackage = true,
            ),
        )
        assertFalse(
            bootstrap.prepare(
                packageName = packageName,
                processName = packageName,
                isFirstPackage = false,
            ),
        )
        assertTrue(
            bootstrap.prepare(
                packageName = packageName,
                processName = packageName,
                isFirstPackage = true,
            ),
        )
        assertFalse(
            bootstrap.prepare(
                packageName = packageName,
                processName = packageName,
                isFirstPackage = true,
            ),
        )
    }

    @Test
    fun `only supported exact builds bind one host reader`() {
        val bootstrap = EmbeddedBootstrap()
        val packageName = "com.apple.android.music.clone"
        val reader = reader(mapOf("dual_pane_enabled" to false))

        assertFalse(
            bootstrap.bind(
                TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1583L),
                reader,
            ),
        )
        assertTrue(
            bootstrap.prepare(
                packageName,
                packageName,
                true,
            ),
        )

        assertFalse(
            bootstrap.bind(
                TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
                reader,
            ),
        )
        assertTrue(
            bootstrap.bind(
                TargetBuild(packageName, "6.5.1", 1583L),
                reader,
            ),
        )
        assertEquals(false, bootstrap.reader.values()["dual_pane_enabled"])
        assertFalse(
            bootstrap.bind(
                TargetBuild(packageName, "6.5.1", 1583L),
                reader(emptyMap()),
            ),
        )
    }

    @Test
    fun `652 build is supported while neighboring versions remain rejected`() {
        val bootstrap = EmbeddedBootstrap()
        val reader = reader(mapOf("dual_pane_enabled" to true))

        assertTrue(
            bootstrap.prepare(
                ModuleConstants.TARGET_PACKAGE,
                ModuleConstants.TARGET_PACKAGE,
                true,
            ),
        )
        assertFalse(
            bootstrap.bind(
                TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.3", 1587L),
                reader,
            ),
        )
        assertTrue(
            bootstrap.bind(
                TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.2", 1586L),
                reader,
            ),
        )
    }

    @Test
    fun `classical 4_1 262 build is supported while neighboring versions are rejected`() {
        val bootstrap = EmbeddedBootstrap()
        val reader = reader(mapOf("dual_pane_enabled" to true))

        assertTrue(
            bootstrap.prepare(
                ModuleConstants.CLASSICAL_PACKAGE,
                ModuleConstants.CLASSICAL_PACKAGE,
                true,
            ),
        )
        assertFalse(
            bootstrap.bind(
                TargetBuild(ModuleConstants.CLASSICAL_PACKAGE, "4.1", 263L),
                reader,
            ),
        )
        assertTrue(
            bootstrap.bind(
                TargetBuild(ModuleConstants.CLASSICAL_PACKAGE, "4.1", 262L),
                reader,
            ),
        )
    }

    private fun reader(values: Map<String, Any>): ConfigurationReader =
        object : ConfigurationReader {
            override fun values(): Map<String, *> = values
            override fun openFile(name: String): InputStream? = null
        }
}
