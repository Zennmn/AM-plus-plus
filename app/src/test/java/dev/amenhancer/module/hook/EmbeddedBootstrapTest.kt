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
    fun `only the target main process first package prepares once`() {
        val bootstrap = EmbeddedBootstrap()

        assertFalse(
            bootstrap.prepare(
                packageName = ModuleConstants.TARGET_PACKAGE,
                processName = "${ModuleConstants.TARGET_PACKAGE}:service",
                isFirstPackage = true,
            ),
        )
        assertFalse(
            bootstrap.prepare(
                packageName = "other.package",
                processName = "other.package",
                isFirstPackage = true,
            ),
        )
        assertFalse(
            bootstrap.prepare(
                packageName = ModuleConstants.TARGET_PACKAGE,
                processName = ModuleConstants.TARGET_PACKAGE,
                isFirstPackage = false,
            ),
        )
        assertTrue(
            bootstrap.prepare(
                packageName = ModuleConstants.TARGET_PACKAGE,
                processName = ModuleConstants.TARGET_PACKAGE,
                isFirstPackage = true,
            ),
        )
        assertFalse(
            bootstrap.prepare(
                packageName = ModuleConstants.TARGET_PACKAGE,
                processName = ModuleConstants.TARGET_PACKAGE,
                isFirstPackage = true,
            ),
        )
    }

    @Test
    fun `only exact 651 build binds one host reader`() {
        val bootstrap = EmbeddedBootstrap()
        val reader = reader(mapOf("dual_pane_enabled" to false))

        assertFalse(
            bootstrap.bind(
                TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1583L),
                reader,
            ),
        )
        assertTrue(
            bootstrap.prepare(
                ModuleConstants.TARGET_PACKAGE,
                ModuleConstants.TARGET_PACKAGE,
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
                TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1583L),
                reader,
            ),
        )
        assertEquals(false, bootstrap.reader.values()["dual_pane_enabled"])
        assertFalse(
            bootstrap.bind(
                TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1583L),
                reader(emptyMap()),
            ),
        )
    }

    private fun reader(values: Map<String, Any>): ConfigurationReader =
        object : ConfigurationReader {
            override fun values(): Map<String, *> = values
            override fun openFile(name: String): InputStream? = null
        }
}
