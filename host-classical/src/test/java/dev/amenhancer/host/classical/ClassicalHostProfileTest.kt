package dev.amenhancer.host.classical

import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.hook.ClassicalChromeSnapshot
import dev.amenhancer.module.hook.ClassicalMiniPlayerSnapshot
import dev.amenhancer.module.hook.computeClassicalArtworkAlpha
import dev.amenhancer.module.hook.computeClassicalBackgroundAlpha
import dev.amenhancer.module.hook.computeClassicalPlayerExpansion
import dev.amenhancer.module.hook.flattenClassicalMenuGroups
import dev.amenhancer.module.hook.mapClassicalPlayerState
import dev.amenhancer.module.hook.resolveClassicalArtworkUrl
import dev.amenhancer.module.hook.shouldSuppressCollapsedClassicalLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicalHostProfileTest {
    @Test
    fun `matches exact classical 4_1 262 profile and rejects neighbors`() {
        assertTrue(ClassicalHostProfiles.isProductionBuild(ModuleConstants.CLASSICAL_PACKAGE, "4.1", 262L))
        assertTrue(ClassicalHostProfiles.supportsGlass(ModuleConstants.CLASSICAL_PACKAGE, "4.1", 262L))
        assertFalse(ClassicalHostProfiles.isProductionBuild(ModuleConstants.CLASSICAL_PACKAGE, "4.1", 263L))
        assertFalse(ClassicalHostProfiles.isProductionBuild(ModuleConstants.TARGET_PACKAGE, "4.1", 262L))
        assertNull(ClassicalHostProfiles.find(ModuleConstants.CLASSICAL_PACKAGE, "4.0", 250L))
    }

    @Test
    fun `profile 4_1 262 contains all verified non-empty default-package contracts`() {
        val profile = ClassicalHostProfiles.find(ModuleConstants.CLASSICAL_PACKAGE, "4.1", 262L)
        assertNotNull(profile)
        val doc = checkNotNull(profile).document
        listOf("activities", "tablet", "font", "listeningGuide", "chrome").forEach { section ->
            val obj = doc.getJSONObject(section)
            obj.keys().forEach { key ->
                val value = obj.get(key)
                if (value is String) {
                    assertTrue("$section.$key must not be blank", value.isNotBlank())
                    assertFalse("$section.$key must use runtime class name without defpackage prefix", value.startsWith("defpackage."))
                }
            }
        }
        val chrome = doc.getJSONObject("chrome")
        listOf(
            "hiddenStateClass",
            "alphaModifierClass",
            "alphaModifierMethod",
            "miniPlayerShadowMethod",
            "contentConstraintClass",
            "constraintAnchorHelperClass",
            "constraintAnchorLinkMethod",
            "playerTouchTargetClass",
            "playerTouchTargetMethod",
            "playerArtworkClass",
            "playerArtworkMethod",
            "playerControllerClass",
            "playerControllerEventMethod",
            "miniPlayerClickEventClass",
            "playerMotionContentClass",
            "playerStateResolverClass",
            "playerDragHandlerClass",
            "anchoredDraggableClass",
            "velocityTrackerClass",
            "velocityTrackerHolderClass",
            "velocityTracker1DClass",
            "composeStateClass",
            "floatStateClass",
            "floatStateGetMethod",
            "floatStateSetMethod",
            "graphicsLayerClass",
            "graphicsLayerMethod",
            "graphicsLayerScopeClass",
            "graphicsLayerAlphaMethod",
            "graphicsLayerClipMethod",
            "graphicsLayerShapeMethod",
            "roundedCornerShapeClass",
            "roundedCornerShapeMethod",
            "rectangleShapeHolderClass",
            "rectangleShapeField",
            "onGloballyPositionedClass",
            "onGloballyPositionedMethod",
            "layoutCoordinatesClass",
            "layoutCoordinatesAttachedMethod",
            "layoutCoordinatesLocalToRootMethod",
            "layoutCoordinatesSizeMethod",
            "playerBackgroundClass",
            "playerBackgroundMethod",
            "popupLayoutClass",
            "popupAnchorViewField",
            "popupPositionProviderField",
            "dropdownPositionProviderClass",
            "abstractComposeViewClass",
            "abstractComposeViewCheckAddMethod",
            "dropdownMenuContentClass",
            "dropdownMenuContentMethod",
            "surfaceModifierMethod",
            "backgroundElementClass",
            "modifierCompanionClass",
            "modifierCompanionField",
            "motionPlayerViewClass",
            "motionPlayerContentFrameField",
            "motionPlayerSurfaceViewField",
            "contextMenuGroupListClass",
            "contextMenuGroupListMethod",
        ).forEach { key ->
            assertTrue("chrome.$key must exist", chrome.has(key))
            assertTrue("chrome.$key must be non-blank", chrome.getString(key).isNotBlank())
        }
    }

    @Test
    fun `resolves classical artwork URL templates cleanly`() {
        assertNull(resolveClassicalArtworkUrl(null))
        assertNull(resolveClassicalArtworkUrl("   "))
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/200x200.jpg",
            resolveClassicalArtworkUrl("https://is1-ssl.mzstatic.com/image/thumb/Music/{w}x{h}{c}.{f}", 200),
        )
    }

    @Test
    fun `maps hidden minimized and expanded player states accurately`() {
        val base = ClassicalChromeSnapshot(
            selectedTabIndex = 0,
            tabs = emptyList(),
            playerExpanded = false,
            searchActive = false,
            miniPlayer = ClassicalMiniPlayerSnapshot("Work", "Composer", false, visible = true, null, null),
        )
        val hidden = mapClassicalPlayerState(base.copy(playerExpanded = true, playerExpansion = 1f), isHidden = true, isMinimized = false)
        assertFalse(hidden.playerExpanded)
        assertEquals(0f, hidden.playerExpansion, 0f)
        assertEquals(false, hidden.miniPlayer?.visible)

        val minimized = mapClassicalPlayerState(base.copy(playerExpanded = true, playerExpansion = 1f), isHidden = false, isMinimized = true)
        assertFalse(minimized.playerExpanded)
        assertEquals(0f, minimized.playerExpansion, 0f)
        assertEquals(true, minimized.miniPlayer?.visible)

        val expanded = mapClassicalPlayerState(base, isHidden = false, isMinimized = false)
        assertTrue(expanded.playerExpanded)
        assertEquals(1f, expanded.playerExpansion, 0f)
        assertEquals(true, expanded.miniPlayer?.visible)
    }

    @Test
    fun `computes continuous player expansion across motion states and handles non-finite inputs`() {
        assertEquals(0f, computeClassicalPlayerExpansion(startExpanded = false, endExpanded = false, motionProgress = 0.5f), 0f)
        assertEquals(1f, computeClassicalPlayerExpansion(startExpanded = true, endExpanded = true, motionProgress = 0.5f), 0f)
        assertEquals(0.35f, computeClassicalPlayerExpansion(startExpanded = false, endExpanded = true, motionProgress = 0.35f), 0.0001f)
        assertEquals(0.65f, computeClassicalPlayerExpansion(startExpanded = true, endExpanded = false, motionProgress = 0.35f), 0.0001f)
        assertEquals(0f, computeClassicalPlayerExpansion(startExpanded = false, endExpanded = true, motionProgress = Float.NaN), 0f)
        assertEquals(1f, computeClassicalPlayerExpansion(startExpanded = true, endExpanded = false, motionProgress = Float.NEGATIVE_INFINITY), 0f)
        assertTrue(shouldSuppressCollapsedClassicalLayer(0.02f))
        assertTrue(shouldSuppressCollapsedClassicalLayer(Float.NaN))
        assertFalse(shouldSuppressCollapsedClassicalLayer(0.021f))
        assertEquals(0f, computeClassicalBackgroundAlpha(0.35f), 0f)
        assertTrue(computeClassicalBackgroundAlpha(0.475f) in 0.45f..0.55f)
        assertEquals(1f, computeClassicalBackgroundAlpha(0.60f), 0.0001f)
        assertEquals(1f, computeClassicalBackgroundAlpha(1f), 0f)
        assertEquals(0f, computeClassicalArtworkAlpha(0.55f), 0f)
        assertTrue(computeClassicalArtworkAlpha(0.635f) in 0.45f..0.55f)
        assertEquals(1f, computeClassicalArtworkAlpha(0.72f), 0.0001f)
        assertEquals(1f, computeClassicalArtworkAlpha(1f), 0f)
    }

    @Test
    fun `flattens multi-group context menu lists to suppress group dividers`() {
        assertNull(flattenClassicalMenuGroups(emptyList<Any?>()))
        assertNull(flattenClassicalMenuGroups(listOf(listOf("add", "favorite"))))
        assertNull(flattenClassicalMenuGroups(listOf(listOf("add"), "not-a-list")))

        assertEquals(
            listOf(listOf("add", "favorite", "share", "addToPlaylist", "playNext")),
            flattenClassicalMenuGroups(
                listOf(
                    listOf("add", "favorite", "share"),
                    listOf("addToPlaylist", "playNext"),
                ),
            ),
        )
    }
}
