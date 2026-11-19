package dev.amenhancer.module.hook

import android.graphics.Color as AndroidColor
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.amenhancer.glass.GlassHostView
import dev.amenhancer.glass.GlassTier
import dev.amenhancer.glass.LiquidGlassSurface
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.ModuleSettings
import java.util.Collections
import java.util.WeakHashMap

/**
 * Shared multi-surface Liquid Glass presenter for secondary Apple Music & Classical surfaces
 * (search pills, app bars, action buttons, dialogs, popups, card overlays).
 */
@RequiresApi(33)
internal object AmMultiSurfaceGlassRuntime : MultiSurfaceGlassObserver {
    private val attachments = Collections.synchronizedMap(WeakHashMap<ViewGroup, Attachment>())

    @Volatile
    private var config: TargetConfigClient? = null

    fun configure(client: TargetConfigClient) {
        config = client
    }

    override fun onSurfaceAttached(anchor: GlassSurfaceAnchor) {
        if (Build.VERSION.SDK_INT < 33) return
        val client = config ?: return
        val settings = client.settings()
        if (!settings.phoneLiquidGlassEnabled) return
        val container = anchor.container
        if (!container.isAttachedToWindow) return
        val customRenderer = anchor.sourceRenderer
        val safeRenderer: ((android.graphics.Canvas) -> Boolean)?
        if (customRenderer == null) {
            if (!BackdropSafetyPolicy.isSafeBackdropSource(container, anchor.backdropSource)) return
            safeRenderer = null
        } else {
            if (anchor.backdropSource === container) return
            safeRenderer = if (BackdropSafetyPolicy.isSafeBackdropSource(container, anchor.backdropSource)) {
                customRenderer
            } else {
                { canvas ->
                    customRenderer(canvas)
                    true
                }
            }
        }
        if (attachments[container] != null) return

        val lease = runCatching {
            WindowBackdropPool.acquire(
                source = anchor.backdropSource,
                minCaptureIntervalMs = anchor.minCaptureIntervalMs,
                sourceRenderer = safeRenderer,
                forceRefreshOnPreDraw = anchor.forceRefreshOnPreDraw,
            ) {
                container.post { detach(container) }
            }
        }.getOrElse { error ->
            ModernXposedRuntime.log("Backdrop lease failed for ${anchor.role}", error)
            return
        }

        val blurDp = ModuleSettings.normalizePhoneLiquidGlassPanelBlurDp(settings.phoneLiquidGlassPanelBlurDp)
        val tier = when (anchor.role) {
            GlassSurfaceRole.PLAYER_ACTION_BUTTON,
            GlassSurfaceRole.FLOATING_ACTION_BUTTON,
            GlassSurfaceRole.FLOATING_SEARCH_BAR -> GlassTier.Hero
            else -> GlassTier.Chrome
        }
        val tint = when (anchor.role) {
            GlassSurfaceRole.CARD_OVERLAY -> Color(AndroidColor.argb(64, 18, 18, 22))
            GlassSurfaceRole.FLOATING_ACTION_BUTTON -> Color.Transparent
            else -> Color.Unspecified
        }
        val bleedDp = if (anchor.role == GlassSurfaceRole.FLOATING_ACTION_BUTTON) 0 else 8
        val menuHeightThresholdPx = 64f * container.resources.displayMetrics.density
        val shapeProvider: () -> Shape = if (anchor.role == GlassSurfaceRole.FLOATING_ACTION_BUTTON) {
            {
                if (container.height > menuHeightThresholdPx) RoundedCornerShape(20.dp) else CircleShape
            }
        } else {
            { RoundedCornerShape(anchor.cornerRadiusDp.dp) }
        }
        val night = (container.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val isFab = anchor.role == GlassSurfaceRole.FLOATING_ACTION_BUTTON
        val effectiveBlur = if (isFab) (blurDp * 0.55f).coerceIn(1.5f, 3.5f).dp else blurDp.dp

        val ctx = runCatching { moduleGlassContext(container.context) }.getOrElse {
            lease.close()
            return
        }
        val glassView = GlassHostView(ctx, bleedDp = bleedDp, traceName = "AM++/${anchor.role.name}").apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            content {
                LiquidGlassSurface(
                    backdrop = lease.backdrop,
                    modifier = Modifier.fillMaxSize(),
                    tier = tier,
                    shape = shapeProvider,
                    panelBlur = effectiveBlur,
                    tint = tint,
                    interactive = false,
                    refractionScale = if (isFab) 1.45f else 1f,
                    lensDepthEffect = isFab,
                    lensChromaticAberration = isFab,
                    highlightAlphaOverride = if (isFab) (if (night) 0.82f else 0.92f) else null,
                ) {}
            }
        }

        val targetSibling = anchor.replaceBackgroundView?.takeIf { it.parent === container }
        val insertIndex = targetSibling?.let { container.indexOfChild(it).coerceAtLeast(0) } ?: 0
        val savedState = suppressNativeBackground(anchor)
        val savedExtras = anchor.extraSuppressViews.map { extra ->
            SavedTargetState(extra, background = extra.background, visibility = extra.visibility, alpha = extra.alpha).also {
                extra.background = null
            }
        }
        val layoutParams = if (targetSibling != null) {
            ViewGroup.LayoutParams(
                targetSibling.width.coerceAtLeast(1),
                targetSibling.height.coerceAtLeast(1),
            )
        } else {
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val syncTarget: View? = when {
            targetSibling != null -> targetSibling
            container !is FrameLayout -> container
            else -> null
        }
        val syncToTarget: () -> Unit = {
            if (targetSibling != null) {
                val targetVis = if (targetSibling.visibility == View.GONE) View.GONE else View.VISIBLE
                if (glassView.visibility != targetVis) glassView.visibility = targetVis
                alignToSibling(
                    glassView,
                    targetSibling.left,
                    targetSibling.top,
                    targetSibling.width,
                    targetSibling.height,
                    targetSibling.translationX,
                    targetSibling.translationY,
                    if (anchor.role == GlassSurfaceRole.CARD_OVERLAY) 1f else targetSibling.alpha,
                )
            } else if (syncTarget != null) {
                alignToSibling(
                    glassView,
                    0,
                    0,
                    container.width,
                    container.height,
                    0f,
                    0f,
                    1f,
                )
            }
        }
        val syncBounds = if (syncTarget != null) {
            View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncToTarget() }
        } else null
        val preDrawListener = if (syncTarget != null) {
            ViewTreeObserver.OnPreDrawListener {
                syncToTarget()
                true
            }
        } else null

        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = detach(container)
        }

        val attachment = Attachment(
            container = container,
            glassView = glassView,
            lease = lease,
            savedTarget = savedState,
            savedExtras = savedExtras,
            syncTarget = syncTarget,
            syncListener = syncBounds,
            preDrawListener = preDrawListener,
            preDrawObserverTarget = if (preDrawListener != null) container else null,
            attachListener = attachListener,
        )
        attachments[container] = attachment

        runCatching {
            if (container is FrameLayout && targetSibling == null) {
                container.addView(
                    glassView,
                    insertIndex.coerceAtMost(container.childCount),
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
            } else {
                container.addView(glassView, insertIndex.coerceAtMost(container.childCount), layoutParams)
                syncToTarget()
                if (syncTarget != null && syncBounds != null) {
                    syncTarget.addOnLayoutChangeListener(syncBounds)
                }
                if (preDrawListener != null) {
                    container.viewTreeObserver.addOnPreDrawListener(preDrawListener)
                }
            }
            container.addOnAttachStateChangeListener(attachListener)
        }.onFailure { error ->
            ModernXposedRuntime.log("Attach multi-surface glass failed for ${anchor.role}", error)
            detach(container)
        }
    }

    private fun alignToSibling(
        view: View,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        translationX: Float,
        translationY: Float,
        alpha: Float,
    ) {
        val w = width.coerceAtLeast(0)
        val h = height.coerceAtLeast(0)
        if (w <= 0 || h <= 0) return
        val right = left + w
        val bottom = top + h
        val lp = view.layoutParams
        if (lp != null && (lp.width != w || lp.height != h)) {
            lp.width = w
            lp.height = h
            view.layoutParams = lp
        }
        if (view.width != w || view.height != h ||
            view.left != left || view.top != top || view.right != right || view.bottom != bottom
        ) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
            )
            view.layout(left, top, right, bottom)
        }
        if (view.translationX != translationX) view.translationX = translationX
        if (view.translationY != translationY) view.translationY = translationY
        val clampedAlpha = if (alpha.isFinite()) alpha.coerceIn(0f, 1f) else 1f
        if (view.alpha != clampedAlpha) view.alpha = clampedAlpha
    }

    private fun suppressNativeBackground(anchor: GlassSurfaceAnchor): SavedTargetState? {
        val target = anchor.replaceBackgroundView ?: return null
        return when (anchor.role) {
            GlassSurfaceRole.CARD_OVERLAY -> {
                val prevAlpha = target.alpha
                target.alpha = 0.28f
                SavedTargetState(target, background = target.background, visibility = target.visibility, alpha = prevAlpha, restoreAlphaOnly = true)
            }
            GlassSurfaceRole.FLOATING_SEARCH_BAR -> {
                val state = SavedTargetState(target, background = target.background, visibility = target.visibility, alpha = target.alpha)
                if (target === anchor.container) {
                    target.background = null
                } else {
                    target.visibility = View.INVISIBLE
                }
                state
            }
            GlassSurfaceRole.PLAYER_ACTION_BUTTON,
            GlassSurfaceRole.CONTEXT_POPUP_MENU,
            GlassSurfaceRole.ACTION_SHEET_DIALOG -> {
                val state = SavedTargetState(target, background = target.background, visibility = target.visibility, alpha = target.alpha)
                target.background = null
                state
            }
            GlassSurfaceRole.FLOATING_ACTION_BUTTON,
            GlassSurfaceRole.TOP_APP_BAR -> null
        }
    }

    private fun detach(container: ViewGroup) {
        val attachment = attachments.remove(container) ?: return
        runCatching { attachment.container.removeOnAttachStateChangeListener(attachment.attachListener) }
        attachment.syncListener?.let { listener ->
            runCatching { attachment.syncTarget?.removeOnLayoutChangeListener(listener) }
        }
        attachment.preDrawListener?.let { listener ->
            runCatching {
                attachment.preDrawObserverTarget?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
            }
        }
        runCatching { (attachment.glassView.parent as? ViewGroup)?.removeView(attachment.glassView) }
        attachment.savedExtras.forEach { it.restore() }
        attachment.savedTarget?.restore()
        attachment.lease.close()
    }

    private class SavedTargetState(
        val view: View,
        val background: Drawable?,
        val visibility: Int,
        val alpha: Float,
        val restoreAlphaOnly: Boolean = false,
    ) {
        fun restore() {
            runCatching {
                if (restoreAlphaOnly) {
                    view.alpha = alpha
                } else {
                    view.background = background
                    view.visibility = visibility
                    view.alpha = alpha
                }
            }
        }
    }

    private class Attachment(
        val container: ViewGroup,
        val glassView: GlassHostView,
        val lease: WindowBackdropPool.Lease,
        val savedTarget: SavedTargetState?,
        val savedExtras: List<SavedTargetState>,
        val syncTarget: View?,
        val syncListener: View.OnLayoutChangeListener?,
        val preDrawListener: ViewTreeObserver.OnPreDrawListener?,
        val preDrawObserverTarget: View?,
        val attachListener: View.OnAttachStateChangeListener,
    )
}
