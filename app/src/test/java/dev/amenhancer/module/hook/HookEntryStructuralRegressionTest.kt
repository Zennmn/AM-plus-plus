package dev.amenhancer.module.hook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HookEntryStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found")

    @Test
    fun `hooks the real host Application and keeps the base Application fallback`() {
        val source = projectFile("app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt")

        assertTrue(source.contains("param.applicationInfo.className"))
        assertTrue(source.contains("installApplicationBootstrap"))
        assertTrue(source.contains("targetClassLoader.loadClass(className)"))
        assertTrue(source.contains("type.getDeclaredMethod(\"onCreate\")"))
        assertTrue(source.contains("listOfNotNull(applicationClass, Application::class.java)"))
        assertTrue(source.contains("applicationHooksInstalled"))
    }

    @Test
    fun `verifies the actual process in Application before installing host UI`() {
        val source = projectFile("app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt")

        assertTrue(source.contains("isTargetMainProcess(application)"))
        assertTrue(source.contains("getDeclaredMethod(\"getProcessName\")"))
        assertTrue(source.contains("currentProcessName"))
        assertTrue(source.contains("initializationStarted"))
    }

    @Test
    fun `registers resources before Application onCreate and reuses the deferred config`() {
        val source = projectFile("app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt")
        val beforeHook = source.substringAfter("override fun beforeHookedMethod")
            .substringBefore("override fun afterHookedMethod")
        val afterHook = source.substringAfter("override fun afterHookedMethod")

        assertTrue(beforeHook.contains("val config = TargetConfigClient(bootstrap.reader)"))
        assertTrue(beforeHook.contains("FeatureInstallation.registerResources(config)"))
        assertTrue(beforeHook.contains("migrateRemoteConfiguration(storage)"))
        assertTrue(beforeHook.indexOf("migrateRemoteConfiguration(storage)") <
            beforeHook.indexOf("bootstrap.bind(build, session)"))
        assertTrue(beforeHook.indexOf("FeatureInstallation.registerResources(config)") <
            beforeHook.indexOf("embeddedConfig = config"))
        assertTrue(beforeHook.contains("resourcePreparationFailed"))
        assertTrue(beforeHook.contains("resourcePreparationStarted.compareAndSet(false, true)"))
        assertTrue(source.indexOf("override fun beforeHookedMethod") <
            source.indexOf("override fun afterHookedMethod"))
        assertTrue(source.contains(
            "installApplicationBootstrap(param.applicationInfo.className, targetClassLoader)",
        ))
        assertTrue(source.contains("FeatureInstallation.installEmbedded("))

        assertTrue(afterHook.contains("val config = embeddedConfig ?: return"))
        assertTrue(afterHook.contains("val session = embeddedSession ?: return"))
        assertTrue(!afterHook.contains("bootstrap.bind(build, session)"))
        assertTrue(!afterHook.contains("TargetConfigClient(bootstrap.reader)"))
    }

    @Test
    fun `resolves inherited no-arg onResume for the fixed settings fragment seam`() {
        open class PreferenceFragmentFixture {
            fun onResume() = Unit
            fun onCreateView(
                inflater: LayoutInflater,
                container: ViewGroup?,
                state: Bundle?,
            ): View? = null
            fun onViewCreated(view: View, state: Bundle?) = Unit
        }
        class SettingsFragmentFixture : PreferenceFragmentFixture()

        val method = EmbeddedSettingsFragmentMethodResolver.findOnResume(
            SettingsFragmentFixture::class.java,
        )

        assertEquals("onResume", method?.name)
        assertEquals(PreferenceFragmentFixture::class.java, method?.declaringClass)

        assertEquals(
            PreferenceFragmentFixture::class.java,
            EmbeddedSettingsFragmentMethodResolver.findOnCreateView(
                SettingsFragmentFixture::class.java,
            )?.declaringClass,
        )
        assertEquals(
            PreferenceFragmentFixture::class.java,
            EmbeddedSettingsFragmentMethodResolver.findOnViewCreated(
                SettingsFragmentFixture::class.java,
            )?.declaringClass,
        )
    }

    @Test
    fun `installs the early preference setup seam before lifecycle fallbacks`() {
        val source = projectFile("app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt")

        assertTrue(source.contains("findPreferenceSetup"))
        assertTrue(source.contains("preferenceSetupMethod"))
        assertTrue(source.contains("onSettingsPreferencesReady"))
        assertTrue(source.contains("preferenceSetupSignature"))
    }
}
