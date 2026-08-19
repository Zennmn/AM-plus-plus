package dev.amenhancer.module.hook

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.R
import java.lang.reflect.Method
import kotlin.math.roundToInt

/** Pure gate for the narrow touch-interception bypass used by the flat root. */
internal object StaticCollapsedInterceptPolicy {
    fun shouldBypass(
        tabletEligible: Boolean,
        targetCoordinator: Boolean,
        targetChild: Boolean,
        eventInTargetRegion: Boolean,
    ): Boolean = tabletEligible && targetCoordinator && targetChild && eventInTargetRegion
}

/**
 * Keeps the native static-collapsed behavior everywhere except the transformed
 * flat bottom-navigation root's player lyrics/queue buttons. The surrounding
 * tabs frame and player sheet remain native, so the embedded pane buttons can
 * receive their taps while player gestures continue through the original
 * behavior everywhere else.
 */
internal object StaticCollapsedInterceptGuard {
    private const val TARGET_VERSION_NAME = "6.5.2"
    private const val TARGET_VERSION_CODE = 1590L
    private const val FLAT_ROOT = "bottom_navigation_root_flat"
    private const val TABS_FRAME = "bottom_navigation_tabs_frame"
    private const val PLAYER_CONTAINER = "player_container"
    private const val PLAYER_SHEET = "player_sheet_container"
    private const val PLAYER_LYRICS = "player_lyrics"
    private const val PLAYER_QUEUE = "player_queue"

    /** This obfuscated behavior contract was verified only on Apple Music 6.5.2 (1590). */
    fun isSupportedBuild(build: TargetBuild): Boolean =
        build.packageName == ModuleConstants.TARGET_PACKAGE &&
            build.versionName == TARGET_VERSION_NAME &&
            build.versionCode == TARGET_VERSION_CODE

    fun install(intercept: Method?): Boolean {
        intercept ?: return false
        return runCatching {
            ModernXposedRuntime.hookMethod(intercept, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val coordinator = param.args.getOrNull(0) as? ViewGroup ?: return
                    val child = param.args.getOrNull(1) as? View ?: return
                    val event = param.args.getOrNull(2) as? MotionEvent ?: return
                    val bypass = StaticCollapsedInterceptPolicy.shouldBypass(
                        tabletEligible = TabletModeQualifier.isEligible(coordinator.context),
                        targetCoordinator = child.parent === coordinator,
                        targetChild = isTransformedFlatRoot(child),
                        eventInTargetRegion = isInPlayerButtonRegion(child, event),
                    )
                    if (bypass) param.result = false
                }
            })
            true
        }.onFailure {
            ModernXposedRuntime.log("static-collapsed intercept guard failed", it)
        }.getOrDefault(false)
    }

    private fun isTransformedFlatRoot(child: View): Boolean {
        val resources = child.resources
        val flatRootId = resources.getIdentifier(FLAT_ROOT, "id", ModuleConstants.TARGET_PACKAGE)
        if (flatRootId == 0) return false
        val root = generateSequence(child) { it.parent as? View }
            .firstOrNull { it.id == flatRootId }
            ?: return false
        val tabsFrameId = resources.getIdentifier(TABS_FRAME, "id", ModuleConstants.TARGET_PACKAGE)
        val playerContainerId = resources.getIdentifier(PLAYER_CONTAINER, "id", ModuleConstants.TARGET_PACKAGE)
        val playerSheetId = resources.getIdentifier(PLAYER_SHEET, "id", ModuleConstants.TARGET_PACKAGE)
        if (tabsFrameId == 0 || playerContainerId == 0 || playerSheetId == 0) return false
        if (child.id !in setOf(flatRootId, playerContainerId, playerSheetId)) return false
        return root.getTag(R.id.am_enhancer_dual_pane_state) != null &&
            root.findViewById<View>(tabsFrameId) != null &&
            root.findViewById<View>(playerSheetId) != null
    }

    private fun isInPlayerButtonRegion(child: View, event: MotionEvent): Boolean {
        val resources = child.resources
        val flatRootId = resources.getIdentifier(FLAT_ROOT, "id", ModuleConstants.TARGET_PACKAGE)
        if (flatRootId == 0) return false
        val root = generateSequence(child) { it.parent as? View }
            .firstOrNull { it.id == flatRootId }
            ?: return false
        val buttonIds = listOf(PLAYER_LYRICS, PLAYER_QUEUE)
            .mapNotNull { name ->
                resources.getIdentifier(name, "id", ModuleConstants.TARGET_PACKAGE).takeIf { it != 0 }
            }
        val x = event.rawX.roundToInt()
        val y = event.rawY.roundToInt()
        return buttonIds.any { id ->
            val button = root.findViewById<View>(id) ?: return@any false
            val bounds = Rect()
            button.getGlobalVisibleRect(bounds) && bounds.contains(x, y)
        }
    }
}
