package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two device regressions captured on 2026-07-26.
 *
 * Apple Music owns the outer bottom-sheet lifecycle, while the modified APK
 * replaces two static landscape resources: bottom_navigation and
 * fragment_player_main. This is intentionally a source contract: the target
 * app's private view tree is not available to JVM tests.
 */
class DualPaneStructuralRegressionTest {
    private val source: String by lazy {
        sequenceOf(
            File("src/main/java/dev/amenhancer/module/hook/AppleMusicDualPaneTarget.kt"),
            File("app/src/main/java/dev/amenhancer/module/hook/AppleMusicDualPaneTarget.kt"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("AppleMusicDualPaneTarget.kt was not found from the unit-test working directory")
    }
    private val featureSource: String by lazy {
        sequenceOf(
            File("src/main/java/dev/amenhancer/module/hook/DualPaneFeature.kt"),
            File("app/src/main/java/dev/amenhancer/module/hook/DualPaneFeature.kt"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("DualPaneFeature.kt was not found from the unit-test working directory")
    }

    @Test
    fun `keeps target internals behind the dual pane capability seam`() {
        assertTrue(source.contains("internal class AppleMusicDualPaneTarget("))
        assertTrue(source.contains(") : DualPaneTarget"))
        assertTrue(featureSource.contains("context.target.dualPane.install().toFeatureInstallResult()"))
        assertFalse(featureSource.contains("AppleMusicSymbols"))
        assertFalse(featureSource.contains("TargetResolution"))
        assertFalse(featureSource.contains("Class<"))
        assertFalse(featureSource.contains("java.lang.reflect"))
        assertFalse(featureSource.contains("LayoutInflationRegistry"))
    }

    @Test
    fun `resolves every dual pane hook entry through target symbols`() {
        listOf(
            "PlayerControllerInitialize",
            "PlayerControllerCreateView",
            "PlayerControllerSelectPane",
            "PlayerActivityCreateStackedNavigationHolder",
            "StackedNavigationMenuOnMeasure",
            "LyricsFragmentOnResume",
            "LyricsChromeAnimate",
            "LyricsFragmentUpdateMetrics",
        ).forEach { symbol ->
            assertTrue(source.contains("AppleMusicSymbols.$symbol"))
        }
        listOf("w1", "onCreateView", "F1", "k1", "onMeasure", "onResume", "a2", "j2")
            .forEach { methodName ->
                assertFalse(source.contains("method.name == \"$methodName\""))
            }
    }

    @Test
    fun `does not reparent the player root into a linear layout`() {
        assertFalse(source.contains("parent.removeViewAt(index)"))
        assertFalse(source.contains("paneContainer.addView(root"))
        assertFalse(source.contains("LinearLayout(root.context)"))
        assertTrue(source.contains("\"player_root\""))
        assertTrue(source.contains("ViewGroup.LayoutParams.WRAP_CONTENT"))
        assertTrue(source.contains("setInt(\"endToStart\""))
    }

    @Test
    fun `does not hide bottom navigation while the player layout inflates`() {
        assertFalse(source.contains("state.chrome?.applyExpanded()"))
        assertFalse(source.contains("root.context.findActivity()?.let(::PlayerChrome)"))
        assertFalse(source.contains("navigation.visibility = View.GONE"))
    }

    @Test
    fun `mirrors the modified outer landscape bottom navigation resource`() {
        assertTrue(source.contains("\"bottom_navigation\""))
        assertTrue(source.contains("ConstraintLayoutPane.resolveBottomNavigationRoot(view)"))
        assertTrue(source.contains("BOTTOM_NAVIGATION_ROOT_STACKED"))
        assertTrue(source.contains("view.findViewById<ViewGroup>(candidateId)"))
        assertTrue(source.contains("installLandscapeBottomNavigation(root)"))
        assertTrue(source.contains("BOTTOM_NAVIGATION_TABS"))
        assertTrue(source.contains("PLAYER_CONTAINER"))
        assertTrue(source.contains("NAVIGATION_TABS_HEIGHT"))
        assertTrue(source.contains("constrainFullWidth(params)"))
        assertTrue(source.contains("params.bottomMargin = if (FlatLandscapeWindowPolicy.shouldReserveNavigationSpace(context))"))
        assertTrue(source.contains("tabsHeight"))
        assertTrue(source.contains("NAVIGATION_TABS_DIVIDER"))
        assertTrue(source.contains("clearLegacyWidthContract(params)"))
        assertTrue(source.contains("\"dimensionRatio\" to \"G\""))
        assertTrue(source.contains("\"constrainedWidth\" to \"W\""))
    }

    @Test
    fun `centers bottom navigation icons and labels like the modified tablet layout`() {
        assertTrue(source.contains("val params = bottomNavigation.layoutParams as? FrameLayout.LayoutParams"))
        assertTrue(source.contains("params.width = ViewGroup.LayoutParams.MATCH_PARENT"))
        assertTrue(source.contains("params.height = ViewGroup.LayoutParams.WRAP_CONTENT"))
        assertTrue(source.contains("params.gravity = Gravity.CENTER"))
    }

    @Test
    fun `reapplies bottom navigation params after target layout initialization`() {
        assertTrue(source.contains("bottomNavigation.post {"))
    }

    @Test
    fun `keeps the material navigation menu under its mobile XML contract`() {
        assertFalse(source.contains("configureTabsMenuGeometry"))
        assertFalse(source.contains("menuParams.height = ViewGroup.LayoutParams.MATCH_PARENT"))
    }

    @Test
    fun `feeds the direct Material menu the modified first-measure height`() {
        assertTrue(source.contains("AppleMusicSymbols.StackedNavigationMenuOnMeasure"))
        assertTrue(source.contains("navigationMenuResolution.valueOrNull()"))
        assertFalse(source.contains("\"Hd.b\""))
        assertTrue(source.contains("View.MeasureSpec.makeMeasureSpec("))
        assertTrue(source.contains("View.MeasureSpec.EXACTLY"))
        assertTrue(source.contains("navigation.id != bottomNavigationId"))
        assertTrue(source.contains("val navigation = menu.parent as? View ?: return"))
        assertFalse(source.contains("bottomNavigation.minimumHeight"))
        assertFalse(source.contains("ensureStackedNavigationMenuHeight"))
        assertFalse(source.contains("menu.minimumHeight"))
        assertFalse(source.contains("installStackedBottomNavigationMeasureHook"))
        assertFalse(source.contains("AMENH-NAV-"))
    }

    @Test
    fun `adds an eight dp vertical inset around the unchanged 56dp Material menu`() {
        assertTrue(source.contains("STACKED_TABS_VERTICAL_INSET_DP = 8"))
        assertTrue(source.contains("fun stackedTabsContainerHeight(context: Context, menuHeight: Int): Int"))
        assertTrue(source.contains("menuHeight + dp(context, STACKED_TABS_VERTICAL_INSET_DP * 2)"))
        assertTrue(source.contains("stackedTabsContainerHeight(root.context, menuHeight)"))
        assertFalse(source.contains("ConstraintLayoutPane.stackedTabsContainerHeight(activity, menuHeight)"))
    }

    @Test
    fun `lets the native stacked holder own mini player content geometry`() {
        assertFalse(source.contains("hookTabletMiniPlayerLayout"))
        assertFalse(source.contains("StackedMiniPlayerLayout"))
        assertFalse(source.contains("MINI_PLAYER_CONTENT"))
        assertFalse(source.contains("centerContentInParent"))
        assertFalse(source.contains("AMENH-MINI-PROBE"))
    }

    @Test
    fun `lets the native stacked holder own the collapsed player offset`() {
        assertTrue(source.contains("configurePlayerContainer(playerContainer, tabsHeight, root.context)"))
        assertTrue(source.contains("FlatLandscapeWindowPolicy.shouldReserveNavigationSpace(context)"))
        assertFalse(source.contains("restoreStackedNavigationLayout"))
    }

    @Test
    fun `gates flat navigation reservation to tablet landscape aspect ratio`() {
        assertTrue(source.contains("internal object FlatLandscapeWindowPolicy"))
        assertTrue(source.contains("TabletModeQualifier.isOfficialTablet(context)"))
        assertTrue(source.contains("configuration.orientation == Configuration.ORIENTATION_LANDSCAPE"))
        assertTrue(source.contains("height > 0"))
        assertTrue(source.contains("width.toFloat() / height.toFloat() >= 1.7f"))
    }

    @Test
    fun `detects flat player overlap beyond the eager window policy`() {
        assertTrue(source.contains("installFlatPlayerBoundarySync(root, playerContainer, tabsFrame, tabsHeight)"))
        assertFalse(source.contains("if (!FlatLandscapeWindowPolicy.shouldReserveNavigationSpace(root.context)) return"))
        assertTrue(source.contains("params.bottomMargin = if (FlatLandscapeWindowPolicy.shouldReserveNavigationSpace(context))"))
        assertTrue(source.contains("FlatPlayerBoundaryPolicy.decide"))
        assertTrue(source.contains("sheet.getLocationInWindow(sheetLocation)"))
        assertTrue(source.contains("tabsFrame.getLocationInWindow(tabsLocation)"))
        assertTrue(source.contains("reserveNavigationSpace = decision.reserveNavigationSpace"))
        assertTrue(source.contains("params.bottomMargin = desired"))
    }

    @Test
    fun `observes delayed native sheet offsets at the pre draw boundary`() {
        assertTrue(source.contains("ViewTreeObserver.OnPreDrawListener"))
        assertTrue(source.contains("sheet.viewTreeObserver.addOnPreDrawListener"))
        assertTrue(source.contains("removeOnPreDrawListener"))
        assertTrue(source.contains("sheet.addOnAttachStateChangeListener"))
        assertTrue(source.contains("installFlatPlayerBoundarySync(root, playerContainer, tabsFrame, tabsHeight)"))
        assertTrue(source.contains("val desiredTabsVisibility = if (decision.tabsVisible) View.VISIBLE else View.INVISIBLE"))
        assertTrue(source.contains("tabsFrame.visibility = desiredTabsVisibility"))
    }

    @Test
    fun `creates the right lyrics pane through the target controller factory`() {
        assertFalse(source.contains("lyricsClass.getDeclaredConstructor()"))
        assertTrue(source.contains("getChildFragmentManager"))
    }

    @Test
    fun `writes the split synchronously while the target layout inflates`() {
        assertTrue(source.contains("DualPaneShell.installImmediately(root)"))
        assertTrue(source.contains("installForControllerRoot(controllerInstance, param.result as? View, \"onCreateView\")"))
        assertFalse(source.contains("PendingDualPaneState"))
        val synchronousInstallSource = source.substringBefore("private fun installFlatPlayerBoundarySync")
        assertFalse(synchronousInstallSource.contains("addOnAttachStateChangeListener"))
        assertFalse(synchronousInstallSource.contains("onViewAttachedToWindow"))
        assertTrue(source.contains("[AMENH-2]"))
    }

    @Test
    fun `uses Apple Music runtime layout and transaction types instead of guessed APIs`() {
        assertTrue(source.contains("playerHost.layoutParams.javaClass"))
        assertFalse(source.contains("playerRoot.javaClass.name == \"androidx.constraintlayout.widget.ConstraintLayout\""))
        assertTrue(source.contains("Class.forName(\"androidx.constraintlayout.widget.Guideline\""))
        assertTrue(source.contains("\"androidx.fragment.app.a\""))
        assertTrue(source.contains("listOf(\"e\", \"replace\")"))
        assertTrue(source.contains("listOf(\"h\", \"commit\")"))
    }

    @Test
    fun `mirrors modified landscape lyrics chrome suppression`() {
        assertTrue(source.contains("installLandscapeLyricsChromeHook"))
        assertTrue(source.contains("AppleMusicSymbols.LyricsChromeAnimate"))
        assertTrue(source.contains("ModernXposedRuntime.callMethod(fragment, \"f2\") as? View"))
        assertTrue(source.contains("suppressed duplicate lyrics pane chrome"))
        assertTrue(source.contains("param.result = null"))
    }

    @Test
    fun `mirrors modified landscape lyrics metrics correction`() {
        assertTrue(source.contains("installLandscapeLyricsMetricsHook"))
        assertTrue(source.contains("AppleMusicSymbols.LyricsFragmentUpdateMetrics"))
        assertTrue(source.contains("alignSynchronizedLyricsHighlightAnchor"))
        assertTrue(source.contains("TabletLyricAnchorPolicy.highlightOffset"))
        assertTrue(source.contains("listOf(\"z0\", \"A0\")"))
        assertTrue(source.contains("lowerBoundary.getInt(bounds) - controlsHeight"))
        assertTrue(source.contains("ModernXposedRuntime.callMethod(recycler, \"S\")"))
        assertTrue(source.contains("corrected landscape lyrics metrics"))
    }

    @Test
    fun `maps the target's obfuscated ConstraintLayout params from the APK`() {
        assertTrue(source.contains("TARGET_650_LAYOUT_PARAMS"))
        assertTrue(source.contains("\"leftToLeft\" to \"h\""))
        assertTrue(source.contains("\"rightToRight\" to \"e\""))
        assertTrue(source.contains("\"startToStart\" to \"t\""))
        assertTrue(source.contains("\"endToStart\" to \"u\""))
        assertTrue(source.contains("\"guidePercent\" to \"c\""))
        assertTrue(source.contains("\"orientation\" to \"V\""))
        assertFalse(source.contains("getConstructor(Int::class.javaPrimitiveType!!"))
        assertTrue(source.contains("ConstraintLayout.LayoutParams copy constructor"))
    }

    @Test
    fun `uses the same BaseActivity root as the modified PlayerActivity`() {
        assertTrue(source.contains("ModernXposedRuntime.callMethod(activity, \"n0\")"))
        assertFalse(source.contains("installForExpandedActivityRoot"))
        assertFalse(source.contains("playerRootFromActivity"))
    }

    @Test
    fun `returns the official stacked holder before the flat holder can be constructed`() {
        assertTrue(source.contains("AppleMusicSymbols.PlayerActivityCreateStackedNavigationHolder"))
        assertTrue(source.contains("installNativeStackedNavigationHolderHook(activityResolution.valueOrNull())"))
        assertTrue(source.contains("nested.simpleName == \"StackedBottomNavigationHolder\""))
        assertTrue(source.contains("override fun beforeHookedMethod(param: MethodHookParam)"))
        assertTrue(source.contains("constructor.newInstance(activity, navigationRoot, behavior)"))
        assertTrue(source.contains("param.result = stackedHolder"))
        assertTrue(source.contains("if (!TabletModeQualifier.isEligible(activity)) return"))
    }

    @Test
    fun `contains no module-owned player transition animation`() {
        assertFalse(source.contains("installPhoneStylePlayerTransitionHook"))
        assertFalse(source.contains("FlatBottomNavigationHolder"))
        assertFalse(source.contains("internal class PlayerChrome"))
        assertFalse(source.contains("PHONE_STACKED_EXPONENT"))
        assertFalse(source.contains("MotionViews"))
        assertFalse(source.contains("preparePlayerForMotion"))
        assertFalse(source.contains("installFinalBottomSheetStateCallback"))
        assertFalse(source.contains("syncToBottomSheetState"))
        assertFalse(source.contains("it.name == \"j1\""))
        assertFalse(source.contains("it.name == \"h1\""))
        assertFalse(source.contains("it.name == \"q1\""))
        assertFalse(source.contains("it.name == \"v1\""))
    }
}
