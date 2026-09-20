/*
 * Derived from AndroidLiquidGlass / Backdrop 2.0.1
 * (https://github.com/Kyant0/AndroidLiquidGlass), commit
 * 65ab177e90e5c1d8c62e70cf7755841982da65f6, Apache License 2.0.
 * AM++ addition: not part of upstream Backdrop.
 * See backdrop/UPSTREAM.md and THIRD_PARTY_NOTICES.md.
 */

package com.kyant.backdrop.backdrops

import android.graphics.Matrix
import android.graphics.RenderNode
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop

/** AM++ Android View bridge. The source must NOT contain any consumer of this backdrop. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class ViewBackdrop(
    private val source: View,
    private val onFailure: (Throwable) -> Unit,
) : Backdrop, AutoCloseable, ViewTreeObserver.OnPreDrawListener {
    private val node = RenderNode("AM++ content backdrop")
    private val sourceToWindow = Matrix()
    private val targetToWindow = Matrix()
    private val windowToTarget = Matrix()
    private val previousMatrix = FloatArray(9)
    private val matrixValues = FloatArray(9)
    private val location = IntArray(2)
    private val rootOrigin = FloatArray(2)
    private var generation by mutableIntStateOf(0)
    private var observer: ViewTreeObserver? = null
    private var recording = false
    private var closed = false
    var ready: Boolean = false
        private set
    var recordings: Long = 0
        private set

    override val isCoordinatesDependent = true

    fun start() {
        check(!closed)
        if (observer != null) return
        observer = source.viewTreeObserver.also { it.addOnPreDrawListener(this) }
    }

    override fun onPreDraw(): Boolean {
        if (closed || recording || !source.isAttachedToWindow || source.width == 0 || source.height == 0) return true
        try {
            check(source.isHardwareAccelerated) { "Hardware accelerated window required" }
            updateSourceMatrix()
            sourceToWindow.getValues(matrixValues)
            val moved = !matrixValues.contentEquals(previousMatrix)
            if (!ready || source.isDirty || moved || node.width != source.width || node.height != source.height) {
                recording = true
                node.setPosition(0, 0, source.width, source.height)
                val canvas = node.beginRecording()
                try {
                    drawWindowBackground(canvas)
                    source.draw(canvas)
                } finally { node.endRecording(); recording = false }
                matrixValues.copyInto(previousMatrix)
                ready = true
                recordings++
                generation++
            }
        } catch (error: Throwable) {
            recording = false
            close()
            // Never mutate the hierarchy inside traversal.
            source.post { onFailure(error) }
            return false // Do not expose a discarded background before the host restores its UI.
        }
        return true
    }

    /** Transparent Compose scenes must include the window underneath them. Otherwise
     * blurred alpha blends over the still-sharp scene, defeating the blur. */
    private fun drawWindowBackground(canvas: android.graphics.Canvas) {
        val value = android.util.TypedValue()
        val resolved = source.context.theme.resolveAttribute(android.R.attr.colorBackground, value, true)
        val color = if (resolved && value.type in android.util.TypedValue.TYPE_FIRST_COLOR_INT..android.util.TypedValue.TYPE_LAST_COLOR_INT) value.data
            else if (source.resources.configuration.uiMode and 0x30 == 0x20) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        canvas.drawColor(color or 0xff000000.toInt())
        val background = source.rootView.background ?: return
        val windowToSource = Matrix()
        if (!sourceToWindow.invert(windowToSource)) return
        val count = canvas.save()
        try {
            canvas.concat(windowToSource)
            source.rootView.getLocationInWindow(location)
            canvas.translate(location[0].toFloat(), location[1].toFloat())
            background.draw(canvas)
        } finally { canvas.restoreToCount(count) }
    }

    private fun updateSourceMatrix() {
        sourceToWindow.reset()
        source.transformMatrixToGlobal(sourceToWindow)
        val rootMatrix = Matrix()
        source.rootView.transformMatrixToGlobal(rootMatrix)
        rootOrigin.fill(0f)
        rootMatrix.mapPoints(rootOrigin)
        source.rootView.getLocationInWindow(location)
        sourceToWindow.postTranslate(location[0] - rootOrigin[0], location[1] - rootOrigin[1])
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        @Suppress("UNUSED_VARIABLE") val frame = generation
        if (!ready || closed || coordinates == null || !coordinates.isAttached) return
        val origin = coordinates.localToWindow(Offset.Zero)
        val x = coordinates.localToWindow(Offset(1f, 0f)) - origin
        val y = coordinates.localToWindow(Offset(0f, 1f)) - origin
        targetToWindow.setValues(floatArrayOf(x.x, y.x, origin.x, x.y, y.y, origin.y, 0f, 0f, 1f))
        if (!targetToWindow.invert(windowToTarget)) return
        // localToWindow already includes the consumer's full layer transform. Applying the
        // LayerBackdrop inverse scale a second time would distort the sampled background.
        withTransform({}) {
            val canvas = drawContext.canvas.nativeCanvas
            check(canvas.isHardwareAccelerated) { "ViewBackdrop cannot render into a software canvas" }
            val save = canvas.save()
            try {
                canvas.concat(windowToTarget)
                canvas.concat(sourceToWindow)
                canvas.drawRenderNode(node)
            } finally { canvas.restoreToCount(save) }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer = null
        node.discardDisplayList()
        ready = false
    }
}
