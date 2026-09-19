package dev.amenhancer.glass.lab

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Canvas as NativeCanvas
import android.graphics.Color as NativeColor
import android.graphics.Paint
import android.os.Bundle
import android.view.Choreographer
import android.view.View
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.ViewBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import com.kyant.backdrop.catalog.components.LiquidButton
import dev.amenhancer.glass.GlassHostView
import dev.amenhancer.glass.NativeButtonInput
import dev.amenhancer.glass.NativeLiquidButton

/** Same scene/renderer/components on both sides; only the background bridge changes. */
class GlassLabActivity : Activity(), Choreographer.FrameCallback {
    private lateinit var stage: FrameLayout
    private lateinit var status: TextView
    private var nativeBackground: View? = null
    private var backdrop: ViewBackdrop? = null
    private var bridgeMode = false
    private var nativeMiniMode = false
    private var dark by mutableStateOf(false)
    private var phase by mutableFloatStateOf(0f)
    private var selected by mutableIntStateOf(0)
    private var running = false
    private var lastTime = 0L
    private var frameCount = 0
    private var slowFrames = 0

    fun setScenario(bridge: Boolean, night: Boolean, offset: Float = 0f) {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        bridgeMode = bridge; nativeMiniMode = false; dark = night; phase = offset; selected = 0
        mount()
    }
    fun stageForCapture(): View = stage

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { setPadding(12, 12, 12, 12); textSize = 13f }
        root.addView(status)
        val toolbar = LinearLayout(this)
        fun button(title: String, action: () -> Unit) {
            toolbar.addView(Button(this).apply { text = title; setOnClickListener { action() } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        button("A / B") { bridgeMode = !bridgeMode; nativeMiniMode = false; phase = 0f; mount() }
        button("深 / 浅") { dark = !dark; nativeBackground?.invalidate(); stage.findViewWithTag<TextView>("native-mini-text")?.setTextColor(if (dark) NativeColor.WHITE else NativeColor.BLACK) }
        button("滚动 / 停止") { running = !running; lastTime = 0L; if (running) Choreographer.getInstance().postFrameCallback(this) }
        button("原生前景") { nativeMiniMode = !nativeMiniMode; if (nativeMiniMode) bridgeMode = true; mount() }
        root.addView(toolbar)
        stage = FrameLayout(this).apply { clipChildren = false }
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        mount()
    }

    private fun mount() {
        backdrop?.close()
        backdrop = null
        stage.removeAllViews()
        status.text = if (bridgeMode) "B：Android View → RenderNode → Backdrop" else "A：原始 Compose LayerBackdrop"
        val overlay = GlassHostView(this)
        if (bridgeMode) {
            val background = object : View(this) {
                override fun onDraw(canvas: NativeCanvas) { Pattern.draw(canvas, width.toFloat(), height.toFloat(), resources.displayMetrics.density, phase, dark) }
            }
            nativeBackground = background
            stage.addView(background, FrameLayout.LayoutParams(-1, -1))
            val bg = ViewBackdrop(background) { error -> runOnUiThread { status.text = "FAILED: ${error.message}" } }
            backdrop = bg
            background.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { bg.start(); v.removeOnAttachStateChangeListener(this) }
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
            if (background.isAttachedToWindow) bg.start()
            overlay.content { SceneTheme { Box(Modifier.fillMaxSize()) { Components(bg) } } }
        } else {
            nativeBackground = null
            overlay.content {
                SceneTheme {
                    val bg = rememberLayerBackdrop()
                    Box(Modifier.fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize().layerBackdrop(bg)) {
                            Pattern.draw(drawContext.canvas.nativeCanvas, size.width, size.height, density, phase, dark)
                        }
                        Components(bg)
                    }
                }
            }
        }
        stage.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        if (bridgeMode && nativeMiniMode) mountNativeMini(requireNotNull(backdrop))
    }

    private fun mountNativeMini(bg: Backdrop) {
        val input = NativeButtonInput()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (density * value).toInt()
        val root = object : FrameLayout(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                input.event(event.actionMasked, event.x, event.y)
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            clipChildren = false; clipToPadding = false
            setOnClickListener { status.text = "原生点击仍有效；材质与形变使用 NativeLiquidButton" }
        }
        val foreground = TextView(this).apply {
            tag = "native-mini-text"
            text = "♫   Liquid Glass • Native View"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (dark) NativeColor.WHITE else NativeColor.BLACK)
        }
        val material = GlassHostView(this)
        material.content { SceneTheme {
            NativeLiquidButton(bg, input) { sx, sy, x, y ->
                foreground.scaleX = sx; foreground.scaleY = sy; foreground.translationX = x; foreground.translationY = y
            }
        } }
        root.addView(material, FrameLayout.LayoutParams(-1, -1))
        root.addView(foreground, FrameLayout.LayoutParams(-1, -1))
        stage.addView(root, FrameLayout.LayoutParams(-1, dp(64), Gravity.BOTTOM).apply {
            leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(80)
        })
    }

    @Composable
    private fun SceneTheme(content: @Composable () -> Unit) {
        val configuration = Configuration(LocalConfiguration.current).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        CompositionLocalProvider(LocalConfiguration provides configuration, content = content)
    }

    @Composable
    private fun BoxScope.Components(bg: Backdrop) {
        val fg = if (dark) Color.White else Color.Black
        Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (nativeMiniMode) Spacer(Modifier.height(64.dp)) else LiquidButton(onClick = {}, backdrop = bg, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                BasicText("♫", style = TextStyle(color = fg, fontSize = 28.sp))
                BasicText("Liquid Glass • Reference", style = TextStyle(color = fg, fontSize = 14.sp))
            }
            LiquidBottomTabs({ selected }, { selected = it }, bg, 5) {
                listOf("首页", "新发现", "广播", "资料库", "搜索").forEachIndexed { index, title ->
                    LiquidBottomTab({ selected = index }) {
                        BasicText(listOf("⌂", "▧", "◎", "♫", "⌕")[index], style = TextStyle(fg, 24.sp))
                        BasicText(title, style = TextStyle(fg, 12.sp))
                    }
                }
            }
        }
    }

    override fun doFrame(time: Long) {
        if (!running) return
        if (lastTime != 0L) {
            val delta = (time - lastTime) / 1_000_000_000f
            phase += delta * 48f
            frameCount++
            val budget = 1f / (display?.refreshRate ?: 60f)
            if (delta > budget * 1.5f) slowFrames++
        }
        lastTime = time
        nativeBackground?.invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onPause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        android.util.Log.i("AMGlassLab", "frames=$frameCount slowFrames=$slowFrames recordings=${backdrop?.recordings}")
        super.onPause()
    }
    override fun onDestroy() { backdrop?.close(); super.onDestroy() }
}

private object Pattern {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun draw(canvas: NativeCanvas, width: Float, height: Float, density: Float, phase: Float, dark: Boolean) {
        canvas.drawColor(if (dark) 0xff121212.toInt() else 0xfffafafa.toInt())
        val cell = 40f * density
        val offset = phase * density % (cell * 2)
        var y = -cell * 2 + offset
        var row = 0
        while (y < height) {
            var x = 0f
            var col = 0
            while (x < width) {
                paint.color = if ((row + col) % 2 == 0) { if (dark) 0xff33476c.toInt() else 0xffcddbf0.toInt() } else { if (dark) 0xff553d50.toInt() else 0xfff6c9d5.toInt() }
                canvas.drawRect(x, y, x + cell, y + cell, paint)
                x += cell; col++
            }
            paint.color = if (dark) NativeColor.WHITE else NativeColor.BLACK
            paint.textSize = 13f * density
            canvas.drawText("GLASS  0123456789  ───  实时背景", 8f * density, y + cell / 2, paint)
            y += cell; row++
        }
        paint.color = 0xff23ad75.toInt()
        canvas.drawRect(width * .48f, 0f, width * .48f + 3f * density, height, paint)
    }
}
