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
        assertTrue(build.contains("io.github.libxposed:service:102.0.0"))
        assertTrue(properties.contains("targetApiVersion=102"))
        assertTrue(entry.contains("dev.amenhancer.module.hook.HookEntry"))
        assertFalse(production.contains("de.robv.android.xposed"))
    }

    @Test
    fun `uses remote preferences and runtime layout inflation replacement`() {
        val application = projectFile("app/src/main/java/dev/amenhancer/module/ModuleApplication.kt")
        val target = projectFile("app/src/main/java/dev/amenhancer/module/config/TargetConfigClient.kt")
        val entry = projectFile("app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt")
        val layouts = projectFile("app/src/main/java/dev/amenhancer/module/hook/LayoutInflationRegistry.kt")

        assertTrue(application.contains("XposedServiceHelper.registerListener(this)"))
        assertTrue(application.contains("XposedService.PROP_CAP_REMOTE"))
        assertTrue(application.contains("service = service"))
        assertTrue(target.contains("ModuleSettingsSchema.decode(preferences.all)"))
        assertTrue(target.contains("openRemoteFile"))
        assertFalse(target.contains("contentResolver"))
        assertTrue(entry.contains("class HookEntry : XposedModule()"))
        assertTrue(entry.contains("openRemoteFile(name)"))
        assertTrue(entry.contains("frameworkProperties.and(PROP_CAP_REMOTE)"))
        assertTrue(layouts.contains("LayoutInflater::class.java.getDeclaredMethod"))
        assertTrue(layouts.contains("XmlPullParser::class.java"))
        assertTrue(layouts.contains("getResourceEntryName(resourceId)"))
        assertTrue(layouts.contains("inferLayoutName(inflated)"))
        assertTrue(layouts.contains("WeakHashMap<View, MutableSet<String>>()"))
    }
}
