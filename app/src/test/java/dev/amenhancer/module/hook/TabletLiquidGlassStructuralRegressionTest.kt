package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the tablet dual-pane + liquid-glass combination contract: the tablet session is
 * gated behind the dual-pane form and the shared toggle, the dual-pane boundary sync stays
 * muted while glass owns the collapsed geometry, and the tablet form continues to reuse
 * the existing glass keys under the current configuration schema.
 */
class TabletLiquidGlassStructuralRegressionTest {
    private fun source(relativePath: String): String = sequenceOf(
        File("src/main/java/$relativePath"),
        File("core/src/main/kotlin/$relativePath"),
        File("../core/src/main/kotlin/$relativePath"),
        File("../core/src/main/kotlin/$relativePath"),
        File("host-api/src/main/java/$relativePath"),
        File("../host-api/src/main/java/$relativePath"),
        File("../host-api/src/main/java/$relativePath"),
        File("hook-runtime/src/main/java/$relativePath"),
        File("../hook-runtime/src/main/java/$relativePath"),
        File("../hook-runtime/src/main/java/$relativePath"),
        File("host-applemusic/src/main/java/$relativePath"),
        File("../host-applemusic/src/main/java/$relativePath"),
        File("../host-applemusic/src/main/java/$relativePath"),
        File("app/src/main/java/$relativePath"),
        File("../app/src/main/java/$relativePath"),
        File("../host-applemusic/src/main/java/$relativePath"),
        File("../host-api/src/main/java/$relativePath"),
        File("../core/src/main/kotlin/$relativePath"),
        File("../hook-runtime/src/main/java/$relativePath"),

    ).firstOrNull(File::isFile)?.readRefactorComponent()
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
            val gates = normalized(runtime).substringAfter("private fun desiredSessionType")
                .substringBefore("private fun fail")
            assertTrue(runtime.contains("val desired = desiredSessionType(activity, config)"))
            assertTrue(text.substring(maxOf(0, build - 600), build).contains("if (desired == TabletDualPaneGlassSession::class.java)"))
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
        // outer whitespace of frameWidth/6 ("留白长度一致): the tablet override
        // carves [W/6, 2W/5] for the nav and [19W/30, W/6] for the mini.
        assertTrue(session.contains("frameWidth * 19 / 30"))
        assertTrue(session.contains("frameWidth * 2 / 5"))
        // The native mini content and the morphing glass share the mini slot, and
        // the expand morph interpolates each edge from its own slot edge to zero.
        // Shared layout starts from the form-owned slot before applying any supported collapse.
        assertTrue(base.contains("val expandedMiniSlot = capsuleMarginsPx(frame.width, mini = true)"))
        assertTrue(base.contains("barCollapse.miniMargins(expandedMiniSlot,"))
        assertTrue(base.contains("applySlot(miniContent, miniSlot)"))
    }

    @Test
    fun `owns the row band touch chain`() {
        val session = source("dev/amenhancer/module/hook/TabletDualPaneGlassSession.kt")
        val base = source("dev/amenhancer/module/hook/PhoneGlassSession.kt")
        val runtime = source("dev/amenhancer/module/hook/PhoneGlassRuntime.kt")
        // Hide native tabs while glass owns the menu, but restore them if the
        // visible menu cannot be mapped. Blank-area gestures pass through.
        assertTrue(base.contains("hideSeam(nav)"))
        assertTrue(base.contains("restoreInteraction(nav)"))
        assertTrue(session.contains("!glassMenuReady"))
        assertTrue(session.contains("TabletGlassLayoutPolicy.containsEither"))
        assertTrue(runtime.contains("it.shouldPassThroughTouch(view,event)"))
        assertTrue(runtime.contains("it.shouldBypassPlayerIntercept(event)"))
        // Apple binds both native click and long-click to mini_player_touch_panel.
        assertTrue(!session.contains("setOnClickListener"))
        assertTrue(!base.contains("getMethod(\"setState\""))
    }

    @Test
    fun `hides the compensation row while the glass toggle is on`() {
        val settings = source("dev/amenhancer/module/ui/EmbeddedSettingsPages.kt")
        // The compensation toggle is native-bar-only; showing it while glass
        // owns the geometry would read as a live switch that does nothing.
        val glassGateAt = settings.indexOf("if (settings.phoneLiquidGlassEnabled)")
        val disabledBranchAt = settings.indexOf("} else {", glassGateAt)
        val compensationAt = settings.indexOf("ModuleText.TABLET_BOTTOM_BAR_FIX", disabledBranchAt)
        assertTrue(
            "the tablet compensation row must remain in the glass-disabled branch",
            glassGateAt in 1 until disabledBranchAt && disabledBranchAt in 1 until compensationAt,
        )
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
    fun `keeps existing glass configuration keys across schema upgrades`() {
        val schema = source("dev/amenhancer/module/config/ModuleSettingsSchema.kt")

        // Keep the serialized glass keys, including the inactive tablet-collapse compatibility key.
        // Tablet forms use the glass toggle, gap and blur but never opt into scroll collapse.
        val glassKeys = Regex("\"(phone_liquid_glass[a-z_]*)\"").findAll(schema)
            .map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "phone_liquid_glass_enabled",
                "phone_liquid_glass_bottom_gap_dp",
                "phone_liquid_glass_panel_blur_dp",
                "phone_liquid_glass_collapse_enabled",
                "phone_liquid_glass_tablet_collapse_enabled",
                "phone_liquid_glass_collapse_rebound_percent",
                "phone_liquid_glass_expand_rebound_percent",
                "phone_liquid_glass_mini_rebound_percent",
                "phone_liquid_glass_mini_sink_bounce_dp",
            ),
            glassKeys,
        )
        // Schema 18 is the compatibility baseline; later settings may advance it without changing glass keys.
        assertTrue(ModuleConstants.CONFIG_SCHEMA_VERSION >= 18)
    }
}
