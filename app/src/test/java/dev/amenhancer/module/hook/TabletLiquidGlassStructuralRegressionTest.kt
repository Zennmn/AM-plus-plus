package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the tablet dual-pane + liquid-glass combination contract: the tablet session is
 * gated behind the dual-pane form and the shared toggle, the dual-pane boundary sync stays
 * muted while glass owns the collapsed geometry, and the configuration schema is untouched
 * (no new keys, no migration).
 */
class TabletLiquidGlassStructuralRegressionTest {
    private fun source(relativePath: String): String = sequenceOf(
        File("src/main/java/$relativePath"),
        File("app/src/main/java/$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    /** Collapses line wraps so multi-line expressions can be matched as written prose. */
    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ")

    @Test
    fun `builds the tablet glass session behind the eligibility and toggle gates`() {
        val runtime = source("dev/amenhancer/module/hook/PhoneGlassRuntime.kt")
        val phoneSession = source("dev/amenhancer/module/hook/PhoneGlassSession.kt")
        val tabletSession = source("dev/amenhancer/module/hook/TabletDualPaneGlassSession.kt")

        // The tablet session may be constructed from the runtime dispatch or the session
        // factory; wherever it is built, the form eligibility and the user toggle must
        // already have been decided.
        val constructionSites = listOf(runtime, phoneSession).map { normalized(it) }
            .map { text -> text.indexOf("TabletDualPaneGlassSession(") to text }
            .filter { (index, _) -> index >= 0 }
        assertTrue(
            "TabletDualPaneGlassSession must be constructed from PhoneGlassRuntime/PhoneGlassSession",
            constructionSites.isNotEmpty(),
        )
        constructionSites.forEach { (build, text) ->
            val gates = text.substring(maxOf(0, build - 600), build)
            assertTrue(gates.contains("TabletModeQualifier.isEligible"))
            assertTrue(gates.contains("phoneLiquidGlassEnabled"))
        }

        // The session itself re-checks the same predicate for activation/liveness.
        val tablet = normalized(tabletSession)
        assertTrue(tablet.contains("phoneLiquidGlassEnabled"))
        assertTrue(tablet.contains("TabletModeQualifier.isEligible"))
    }

    @Test
    fun `mutes the dual-pane boundary sync while glass owns the collapsed geometry`() {
        val dualPane = normalized(
            source("dev/amenhancer/module/hook/AppleMusicDualPaneTarget.kt"),
        )
        val guard = dualPane.indexOf("TabletGlassChrome.isGlassActive(root)")
        // Only the settled writes match these strings; the comparison reads use `!=`.
        val translationWrite = dualPane.indexOf("playerContainer.translationY = desiredTranslation")
        val visibilityWrite = dualPane.indexOf("tabsFrame.visibility = desiredTabsVisibility")
        assertTrue(guard >= 0)
        // Both sync() writes must sit behind the arbitration guard.
        assertTrue(translationWrite > guard)
        assertTrue(visibilityWrite > guard)
        // The hand-over releases a settled compensation lift/visibility so the
        // glass geometry starts from a clean state instead of inheriting it.
        assertTrue(dualPane.contains("playerContainer.translationY = 0f"))
    }

    @Test
    fun `lays the tablet capsules side by side in one centered row`() {
        val session = source("dev/amenhancer/module/hook/TabletDualPaneGlassSession.kt")
        val base = source("dev/amenhancer/module/hook/PhoneGlassSession.kt")
        // Nav pill left of one centered row, mini pill in the right slot, equal
        // outer whitespace of frameWidth/6 ("留白长度一致"): the tablet override
        // carves [W/6, 2W/5] for the nav and [19W/30, W/6] for the mini.
        assertTrue(session.contains("frameWidth * 19 / 30"))
        assertTrue(session.contains("frameWidth * 2 / 5"))
        // The native mini content and the morphing glass share the mini slot, and
        // the expand morph interpolates each edge from its own slot edge to zero.
        assertTrue(base.contains("capsuleMarginsPx"))
        assertTrue(base.contains("applySlot(miniContent, miniSlot)"))
    }

    @Test
    fun `hides the compensation row while the glass toggle is on`() {
        val settings = source("dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")
        // The compensation toggle is native-bar-only; showing it while glass
        // owns the geometry would read as a live switch that does nothing.
        assertTrue(settings.contains("if (!settings.phoneLiquidGlassEnabled)"))
        assertTrue(settings.contains("平板底栏补偿"))
    }

    @Test
    fun `suppresses the flat chrome seams under the tablet capsule`() {
        val session = source("dev/amenhancer/module/hook/TabletDualPaneGlassSession.kt")
        val base = source("dev/amenhancer/module/hook/PhoneGlassSession.kt")
        // The flat top-shadow strip and the stock column divider must be held
        // gone for the whole session, not only at activation: the host may
        // recolor/recreate them behind the floating capsule.
        assertTrue(session.contains("nav_tabs_top_shadow"))
        assertTrue(session.contains("suppressNativeChromeSeams"))
        assertTrue(base.contains("suppressNativeChromeSeams"))
    }

    @Test
    fun `keeps the glass configuration keys and schema version unchanged`() {
        val schema = source("dev/amenhancer/module/config/ModuleSettingsSchema.kt")
        val constants = source("dev/amenhancer/module/ModuleConstants.kt")

        // Exactly the three existing glass keys: the tablet form reuses the phone toggle,
        // gap and blur, so no new key exists and no migration was added.
        val glassKeys = Regex("\"(phone_liquid_glass[a-z_]*)\"").findAll(schema)
            .map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "phone_liquid_glass_enabled",
                "phone_liquid_glass_bottom_gap_dp",
                "phone_liquid_glass_panel_blur_dp",
            ),
            glassKeys,
        )
        assertTrue(constants.contains("const val CONFIG_SCHEMA_VERSION = 14"))
    }
}
