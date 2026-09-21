package dev.amenhancer.glass

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs

data class GlassTab(val id: Int, val title: String, val icon: Drawable?, val enabled: Boolean)

@Composable
fun GlassNavigation(
    tabs: List<GlassTab>,
    selectedId: Int,
    accent: Color,
    foreground: Color,
    backdrop: Backdrop,
    onSelect: (Int) -> Int,
) {
    // The reference drag animation normalizes by tabsCount - 1. Keep a one-tab host native.
    if (tabs.size < 2) return
    val index = GlassPolicy.selectedIndex(tabs.map { it.id }, selectedId) ?: return
    val confirmedIndex = rememberUpdatedState(index)
    val confirmedId = rememberUpdatedState(selectedId)
    val select = rememberUpdatedState(onSelect)
    var rejectedSelection by remember { mutableIntStateOf(0) }
    fun request(tab: GlassTab) {
        if (!tab.enabled || select.value(tab.id) != tab.id) rejectedSelection++
    }
    // A menu replacement must discard gestures whose indices refer to the old menu.
    key(tabs.map { it.id }, rejectedSelection) {
        val selection = remember { { confirmedIndex.value } }
        LiquidBottomTabs(
            selectedTabIndex = selection,
            onTabSelected = { i -> tabs.getOrNull(i)?.let { if (it.id != confirmedId.value) request(it) } },
            onSelectedTabClick = { i -> tabs.getOrNull(i)?.let(::request) },
            isTabEnabled = { i -> tabs.getOrNull(i)?.enabled == true },
            backdrop = backdrop,
            tabsCount = tabs.size,
            accentOverride = accent,
            panelHeight = GlassPolicy.NAV_HEIGHT_DP.dp,
        ) {
            tabs.forEach { tab ->
                LiquidBottomTab(
                    onClick = { request(tab) },
                    modifier = Modifier.semantics {
                        selected = tab.id == selectedId
                        contentDescription = tab.title
                    },
                ) {
                    Canvas(Modifier.size(24.dp)) {
                        tab.icon?.let { icon ->
                            val save = drawContext.canvas.nativeCanvas.save()
                            try {
                                icon.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                                icon.draw(drawContext.canvas.nativeCanvas)
                            } finally { drawContext.canvas.nativeCanvas.restoreToCount(save) }
                        }
                    }
                    BasicText(tab.title, style = TextStyle(color = foreground, fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
