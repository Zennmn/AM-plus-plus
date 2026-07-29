package dev.amenhancer.module.hook

import android.content.res.Resources
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.amenhancer.module.ModuleConstants
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import kotlin.math.abs

internal object TabletLyricTypography {
    private val states = WeakHashMap<ViewGroup, TypographyState>()
    private val eligibilityByResources = WeakHashMap<Resources, Pair<Int, Boolean>>()

    fun applyToInflatedLayout(root: View) {
        if (!isTargetTabletLandscape(root)) return
        applyPrimaryLyricTextSize(root, resolvePrimaryLyricTextIds(root))
    }

    fun attach(fragment: Any) {
        val recycler = runCatching {
            ModernXposedRuntime.callMethod(fragment, "getRecyclerView") as? ViewGroup
        }.getOrNull() ?: return
        if (!isTargetTabletLandscape(recycler)) return
        val state = synchronized(states) {
            states.getOrPut(recycler) { TypographyState(recycler) }
        }
        state.installIfNeeded()
        state.scheduleApply()
    }

    private class TypographyState(recycler: ViewGroup) {
        private val recyclerRef = WeakReference(recycler)
        private val primaryLyricTextIds = resolvePrimaryLyricTextIds(recycler)
        private var installed = false

        @Suppress("unused")
        private var childAttachListener: Any? = null

        fun installIfNeeded() {
            if (installed) return
            installed = true
            val recycler = recyclerRef.get() ?: return
            installChildAttachObserver(recycler)
        }

        fun scheduleApply() {
            val recycler = recyclerRef.get() ?: return
            recycler.post(::applyRows)
            recycler.postDelayed(::applyRows, 120L)
            recycler.postDelayed(::applyRows, 320L)
        }

        private fun applyRows() {
            val recycler = recyclerRef.get() ?: return
            if (!recycler.isAttachedToWindow) return
            repeat(recycler.childCount) { index ->
                applyToLyricRow(recycler.getChildAt(index))
            }
        }

        private fun applyToLyricRow(row: View) {
            applyPrimaryLyricTextSize(row, primaryLyricTextIds)
            applyLyricRowSpacing(row)
        }

        private fun installChildAttachObserver(recycler: ViewGroup) {
            val listenerClass = runCatching {
                Class.forName(
                    "androidx.recyclerview.widget.RecyclerView\$OnChildAttachStateChangeListener",
                    false,
                    recycler.javaClass.classLoader,
                )
            }.getOrNull() ?: return
            val handler = InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "onChildViewAttachedToWindow" -> {
                        (args?.firstOrNull() as? View)?.let(::applyToLyricRow)
                        recycler.postDelayed(::applyRows, 80L)
                        null
                    }
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "AppleMusicEnhancerTabletLyricTypographyListener"
                    else -> null
                }
            }
            val listener = runCatching {
                Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass), handler)
            }.getOrNull() ?: return
            runCatching {
                recycler.javaClass
                    .getMethod("addOnChildAttachStateChangeListener", listenerClass)
                    .invoke(recycler, listener)
            }.onSuccess {
                childAttachListener = listener
            }
        }
    }

    private fun isTargetTabletLandscape(view: View): Boolean {
        val resources = view.resources
        val orientation = resources.configuration.orientation
        return synchronized(eligibilityByResources) {
            eligibilityByResources[resources]?.takeIf { it.first == orientation }?.second
                ?: TabletModeQualifier.isEligible(view.context).also { eligible ->
                    eligibilityByResources[resources] = orientation to eligible
                }
        }
    }

    private fun resolvePrimaryLyricTextIds(view: View): Set<Int> = listOf(
        "song_lyrics_line",
        "song_lyrics_word",
    ).mapTo(mutableSetOf()) { name ->
        view.resources.getIdentifier(name, "id", ModuleConstants.TARGET_PACKAGE)
    }.filterTo(mutableSetOf()) { id -> id != 0 && id != View.NO_ID }

    private fun applyPrimaryLyricTextSize(root: View, primaryLyricTextIds: Set<Int>) {
        if (primaryLyricTextIds.isEmpty()) return
        val metrics = root.resources.displayMetrics
        val targetSizeSp = TabletLyricVisualPolicy.textSizeSp(
            viewportWidthPx = metrics.widthPixels.toFloat(),
            viewportHeightPx = metrics.heightPixels.toFloat(),
            scaledDensity = metrics.density * root.resources.configuration.fontScale,
        )
        val targetSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            targetSizeSp,
            metrics,
        )
        fun visit(view: View) {
            if (view is TextView && view.id in primaryLyricTextIds && abs(view.textSize - targetSizePx) > 0.5f) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, targetSizeSp)
            }
            (view as? ViewGroup)?.let { parent ->
                repeat(parent.childCount) { childIndex -> visit(parent.getChildAt(childIndex)) }
            }
        }
        visit(root)
    }

    private fun applyLyricRowSpacing(row: View) {
        if (row.getTag(dev.amenhancer.module.R.id.am_enhancer_lyric_spacing_applied) == true) return
        val params = row.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val spacingPx = (
            TabletLyricVisualPolicy.ITEM_SPACING_EXTRA_DP * row.resources.displayMetrics.density + 0.5f
            ).toInt()
        params.bottomMargin += spacingPx
        row.layoutParams = params
        row.setTag(dev.amenhancer.module.R.id.am_enhancer_lyric_spacing_applied, true)
    }
}
