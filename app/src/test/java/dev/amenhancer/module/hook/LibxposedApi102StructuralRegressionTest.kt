package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibxposedApi102StructuralRegressionTest {
    private fun projectFile(path: String): String = sequenceOf(File(path), File("../$path"))
        .firstOrNull(File::isFile)?.readText()
        ?: error("$path was not found")

    @Test
    fun `targets libxposed api 102 without legacy xposed calls`() {
        val build = projectFile("app/build.gradle.kts")
        val properties = projectFile("app/src/main/resources/META-INF/xposed/module.prop")
        val entry = projectFile("app/src/main/resources/META-INF/xposed/java_init.list")
        val production = File("app/src/main/java").walkTopDown()
            .filter(File::isFile)
            .joinToString("\n") { it.readText() }

        assertTrue(build.contains("io.github.libxposed:api:102.0.0"))
        assertTrue(build.contains("compileOnly(\"io.github.libxposed:service:102.0.0\")"))
        assertTrue(properties.contains("targetApiVersion=102"))
        assertTrue(entry.contains("dev.amenhancer.module.hook.HookEntry"))
        assertFalse(production.contains("de.robv.android.xposed"))
    }

    @Test
    fun `uses host private embedded storage and runtime layout inflation replacement`() {
        val storage = projectFile("app/src/main/java/dev/amenhancer/module/config/HostPrivateEmbeddedStorage.kt")
        val target = projectFile("app/src/main/java/dev/amenhancer/module/config/TargetConfigClient.kt")
        val entry = projectFile("app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt")
        val layouts = projectFile("app/src/main/java/dev/amenhancer/module/hook/LayoutInflationRegistry.kt")

        assertTrue(storage.contains("ampp-embedded-settings"))
        assertTrue(storage.contains("ampp-embedded-files"))
        assertTrue(target.contains("ConfigurationReader"))
        assertTrue(target.contains("openFileDescriptor"))
        assertFalse(target.contains("contentResolver"))
        assertTrue(entry.contains("class HookEntry : XposedModule()"))
        assertTrue(entry.contains("EmbeddedConfigurationSession"))
        assertFalse(entry.contains("frameworkProperties.and(PROP_CAP_REMOTE)"))
        assertTrue(layouts.contains("LayoutInflater::class.java.getDeclaredMethod"))
        assertTrue(layouts.contains("XmlPullParser::class.java"))
        assertTrue(layouts.contains("getResourceEntryName(resourceId)"))
        assertTrue(layouts.contains("inferLayoutName(inflated)"))
        assertTrue(layouts.contains("WeakHashMap<View, MutableSet<String>>()"))
    }
}
