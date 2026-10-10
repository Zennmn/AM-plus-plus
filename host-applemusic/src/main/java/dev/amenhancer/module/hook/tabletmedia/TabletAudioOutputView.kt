package dev.amenhancer.module.hook.tabletmedia

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One output action with a device glyph and an ellipsized route name. */
internal class TabletAudioOutputView(context: Context, private val icon: IosDeviceOutputDrawable) : TextView(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    init {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setTextColor(Color.argb(166, 255, 255, 255))
        textSize = 12f
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        compoundDrawablePadding = dp(12)
        setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
        setPadding(0, 0, dp(8), 0)
        isFocusable = true
        bind(AudioOutputState(null, OutputDeviceIcon.SYSTEM))
    }

    fun bind(state: AudioOutputState) {
        icon.kind = state.icon
        val name = state.name?.takeIf(String::isNotBlank) ?: when (state.icon) {
            OutputDeviceIcon.SYSTEM -> "本机"
            OutputDeviceIcon.HEADPHONES -> "耳机"
            OutputDeviceIcon.AIRPODS -> "AirPods"
            OutputDeviceIcon.SPEAKER -> "扬声器"
        }
        if (text.toString() != name) text = name
        contentDescription = "音频输出 · $name"
    }

    fun preferredWidth(): Int = maxOf(dp(44), ceil(paint.measureText(text.toString())).toInt() +
        icon.intrinsicWidth + compoundDrawablePadding + paddingLeft + paddingRight)
}
