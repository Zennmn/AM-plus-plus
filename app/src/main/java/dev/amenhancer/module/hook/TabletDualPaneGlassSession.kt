package dev.amenhancer.module.hook

import android.app.Activity
import android.view.View
import androidx.annotation.RequiresApi
import dev.amenhancer.glass.GlassGeometry
import dev.amenhancer.glass.GlassPolicy
import dev.amenhancer.module.config.TargetConfigClient
import kotlin.math.exp

/**
 * Dual-pane (flat) tablet form of the liquid-glass session. The phone pipeline is reused
 * as-is; only the host-form seams differ: the flat root name, the flat native peek
 * baseline, the session-driven capsule exit, chrome ownership arbitration and the
 * tightened behavior lookup.
 */
@RequiresApi(33)
internal class TabletDualPaneGlassSession(
    activity: Activity,
    config: TargetConfigClient,
    failure: (Throwable) -> Unit,
) : PhoneGlassSession(activity, config, failure) {

    override val geometry: GlassGeometry get() = GlassGeometry.Tablet

    // The session lives only while the official tablet runs the dual-pane player;
    // portrait or dual-pane-off restores the native chrome through close().
    override fun sessionEligible(): Boolean =
        config.settings().phoneLiquidGlassEnabled && TabletModeQualifier.isEligible(activity)

    // Flat layout resolves bottom_navigation_root_flat; stacked stays the fallback.
    override fun resolveBottomNavigationRoot(): View? =
        find("bottom_navigation_root_flat") ?: find("bottom_navigation_root_stacked")

    // The flat holder reserves miniplayer_height only (no navigation_tabs_height).
    override fun nativePeekBaseline(): Int = bottomInset + dimen("miniplayer_height")

    // The flat holder never translates the tabs frame, so the capsule exit is driven here
    // with the phone StackedBottomNavigationHolder.c exp(-20t) curve over the whole glass
    // occupied height (navigation capsule + mini); slide back to 0 parks the capsule again.
    override fun driveNavFrameExit(progress: Float) {
        val frame = navFrame ?: return
        val extent = GlassPolicy.occupiedHeight(density, bottomInset, miniVisible, bottomGapDp, geometry)
        frame.translationY = (1f - exp(-20f * progress)) * extent
    }

    // The dual-pane boundary sync mutes its own writes while the glass owns the geometry.
    override fun onGlassOwnership(root: View?) {
        root?.let(TabletGlassChrome::markGlassActive)
    }

    override fun releaseGlassOwnership(root: View?) {
        root?.let(TabletGlassChrome::clearGlassActive)
    }

    // Flat-only chrome survives the dual-pane full-width transform as hairlines
    // across/over the floating capsule: nav_tabs_top_shadow is a dp gradient
    // strip riding the tabs frame top edge, and the stock column divider (1dp
    // separator_color, drawn above the tabs frame in z) keeps anchors to both
    // pre-transform columns and resolves to a stray vertical line mid-screen.
    // The glass capsule replaces both; the shared seam hook keeps them gone.
    override fun suppressNativeChromeSeams() {
        super.suppressNativeChromeSeams()
        val root = hostRoot ?: return
        for (name in listOf("nav_tabs_top_shadow", "divider")) {
            val id = resourceId(name, "id").takeIf { it != 0 } ?: continue
            hideSeam(root.findViewById(id))
        }
    }

    // The host field declares BottomSheetBehavior<FrameLayout> but runs
    // PlayerBottomSheetBehavior (and is the activity's only Behavior field). Prefer a
    // value whose runtime class names it; fall back to the phone declared-type scan.
    override fun findPlayerBehavior(): Any? {
        generateSequence(activity.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .mapNotNull { field -> runCatching { field.isAccessible = true; field.get(activity) }.getOrNull() }
            .firstOrNull { it.javaClass.name.contains("PlayerBottomSheetBehavior") }
            ?.let { return it }
        return super.findPlayerBehavior()
    }
}
