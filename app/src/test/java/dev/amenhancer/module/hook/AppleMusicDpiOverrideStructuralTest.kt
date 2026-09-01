package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicDpiOverrideStructuralTest {
    private fun source(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found")

    @Test
    fun `configuration is installed before resource callbacks and host Application body`() {
        val hookEntry = source("app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt")
        val before = hookEntry.substringAfter("override fun beforeHookedMethod")
            .substringBefore("override fun afterHookedMethod")
        assertTrue(before.contains("AppleMusicDpiOverrideRuntime.install("))
        assertTrue(before.indexOf("AppleMusicDpiOverrideRuntime.install(") <
            before.indexOf("FeatureInstallation.registerResources(config)"))

        val runtime = source("app/src/main/java/dev/amenhancer/module/hook/AppleMusicDpiOverride.kt")
        assertTrue(runtime.contains("Resources::class.java.getDeclaredMethod"))
        assertTrue(runtime.contains("registerActivityLifecycleCallbacks(this)"))
        assertTrue(runtime.contains("registerComponentCallbacks(this)"))
        assertTrue(runtime.contains("activityOnConfigurationChanged"))
        assertTrue(runtime.contains("Activity::class.java.getDeclaredMethod"))
        assertTrue(runtime.contains("onActivityPreCreated"))
        assertTrue(runtime.contains("resources === Resources.getSystem()"))
    }

    @Test
    fun `failed installation deactivates already registered seams`() {
        val runtime = source("app/src/main/java/dev/amenhancer/module/hook/AppleMusicDpiOverride.kt")

        assertTrue(runtime.contains("runtimeEnabled"))
        assertTrue(runtime.contains("if (!runtimeEnabled.get()) return"))
        assertTrue(runtime.contains("deactivateAfterInstallFailure(application)"))
        assertTrue(runtime.contains("targetDpi = AppleMusicDpiOverridePolicy.FOLLOW_SYSTEM_DPI"))
        assertTrue(runtime.contains("unregisterActivityLifecycleCallbacks(this)"))
        assertTrue(runtime.contains("unregisterComponentCallbacks(this)"))
        assertTrue(runtime.contains("message = \"DPI 覆盖安装失败，已停用覆盖\""))
    }

    @Test
    fun `settings contract exposes the fixed DPI value and schema key`() {
        val model = source("app/src/main/java/dev/amenhancer/module/model/ModuleModels.kt")
        val schema = source("app/src/main/java/dev/amenhancer/module/config/ModuleSettingsSchema.kt")
        val ui = source("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")
        val installation = source("app/src/main/java/dev/amenhancer/module/hook/FeatureInstallation.kt")

        assertTrue(model.contains("appleMusicDpiOverrideDpi"))
        assertTrue(schema.contains("apple_music_dpi_override_dpi"))
        assertTrue(ui.contains("Apple Music 内部 DPI"))
        assertTrue(installation.contains("AppleMusicDpiOverrideFeature()"))
    }
}
