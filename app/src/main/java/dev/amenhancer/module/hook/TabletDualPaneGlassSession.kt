package dev.amenhancer.module.hook

import android.app.Activity
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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

    // User sketch (2026-09-22): both capsules share one bottom row — the nav
    // pill on the left (65% of a two-thirds-wide row), the mini pill in the
    // right slot (30%), a small gap between them, and equal outer whitespace
    // ("留白长度一致"): row = 2W/3 centered, outer = W/6 per side.
    // nav slot = [W/6, 2W/5]; mini slot = [19W/30, W/6].
    override fun capsuleMarginsPx(frameWidth: Int, mini: Boolean): IntArray =
        if (mini) intArrayOf(frameWidth * 19 / 30, frameWidth / 6)
        else intArrayOf(frameWidth / 6, frameWidth * 2 / 5)

    // Keep the tablet fallback tap on the native mini content. The user confirmed
    // it expands correctly; the earlier apparent failure was device lag.
    override fun armMiniTap(content: View) {
        content.setOnClickListener { expandPlayer() }
    }

    // The collapsed sheet band spans the full width, so the native sheet Behavior treats
    // the empty side areas as its drag handle and sliding there expands the player. The
    // row gesture gate suppresses exactly those gestures at the Behavior touch entries, and
    // the same geometry tells the runtime which down events belong to the page below: the bar
    // root and the sheet are full-screen even where no capsule is drawn. Every value derives
    // from the very views that carry the glass surfaces, and a failure here only leaves the
    // native chain in place.
    private var publishedBandKey: String? = null
    private var rowAtRest = false
    private var rowBarRoot: View? = null
    private var rowSheetRoot: View? = null

    override fun updateRowGestureOwnership(frameWidth: Int, atRest: Boolean) {
        rowAtRest = atRest
        val published = runCatching {
            val container = find("player_container") as? ViewGroup ?: return@runCatching false
            if (!atRest || frameWidth <= 0 || container.height <= 0) return@runCatching false
            val sheet = find("player_sheet_container") ?: return@runCatching false
            val frame = navFrame ?: return@runCatching false
            if (!frame.isShown) return@runCatching false
            val visible = Rect().also { sheet.getGlobalVisibleRect(it) }
            val frameRect = Rect().also { frame.getGlobalVisibleRect(it) }
            val sheetRect = Rect().also { sheet.getGlobalVisibleRect(it) }
            if (visible.isEmpty || frameRect.isEmpty || sheetRect.isEmpty) return@runCatching false
            val band = RowBandRect(visible.left, visible.top, visible.right, visible.bottom)
            val navSlot = capsuleMarginsPx(frameWidth, mini = false)
            val miniSlot = capsuleMarginsPx(frameWidth, mini = true)
            // Each glass surface is laid out top-aligned inside its host with these slot
            // margins (nav in the tabs frame, mini in the sheet strip), so the handle is
            // exactly that surface's own rectangle; the drawn shape is a stadium inside it.
            val handles = buildList {
                add(
                    RowCapsuleRect(
                        frameRect.left + navSlot[0],
                        frameRect.top,
                        frameRect.left + frameRect.width() - navSlot[1],
                        frameRect.top + dp(GlassPolicy.NAV_HEIGHT_DP),
                    ),
                )
                if (miniVisible) {
                    add(
                        RowCapsuleRect(
                            sheetRect.left + miniSlot[0],
                            sheetRect.top,
                            sheetRect.left + sheetRect.width() - miniSlot[1],
                            sheetRect.top + dp(geometry.miniHeightDp),
                        ),
                    )
                }
            }
            val key = buildString {
                append(container.height)
                append('/').append(band.left).append('-').append(band.top)
                append('-').append(band.right).append('-').append(band.bottom)
                handles.forEach {
                    append('/').append(it.left).append('-').append(it.top)
                    append('-').append(it.right).append('-').append(it.bottom)
                }
            }
            if (key != publishedBandKey) {
                publishedBandKey = key
                rowBarRoot = find("bottom_navigation_root_flat")
                rowSheetRoot = sheet
                TabletRowGestureGate.publish(
                    TabletRowBand(handles, dp(ROW_HANDLE_SLOP_DP)),
                    band,
                    listOfNotNull(rowSheetRoot, container, rowBarRoot),
                )
            }
            true
        }.getOrDefault(false)
        if (!published) {
            publishedBandKey = null
            rowBarRoot = null
            rowSheetRoot = null
            TabletRowGestureGate.clear()
        }
    }

    // Only the capsule handles may keep a collapsed-row gesture in the bar. Every other down
    // event is handed to the page below, which then owns the whole stream natively (scroll,
    // momentum, taps) while the gate keeps the sheet Behavior out of that gesture.
    override fun managesTouchRoot(view: View): Boolean = view === rowBarRoot || view === rowSheetRoot

    override fun shouldDispatch(view: View, event: MotionEvent): Boolean {
        if (!activated || !rowAtRest || event.actionMasked != MotionEvent.ACTION_DOWN) return true
        return !TabletRowGestureGate.isRowWhitespace(event.rawX, event.rawY)
    }

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
        val target = (1f - exp(-20f * progress)) * extent
        // At the tail of this exponential curve, subpixel updates are visually inert but
        // still invalidate every backdrop consumer. Stop once the remaining error is < 0.5px.
        if (kotlin.math.abs(frame.translationY - target) >= 0.5f) {
            frame.translationY = target
            invalidateBackdropTargetPosition()
        }
    }

    override fun onSlide(progress: Float) {
        super.onSlide(progress)
        // The host has already moved the sheet at this callback. Move the glass
        // row now as well, before pre-draw captures the source and paints it.
        if (activated) driveNavFrameExit(progress.coerceIn(0f, 1f))
    }

    // The dual-pane boundary sync mutes its own writes while the glass owns the geometry.
    override fun onGlassOwnership(root: View?) {
        root?.let(TabletGlassChrome::markGlassActive)
    }

    override fun releaseGlassOwnership(root: View?) {
        publishedBandKey = null
        rowAtRest = false
        rowBarRoot = null
        rowSheetRoot = null
        TabletRowGestureGate.clear()
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
