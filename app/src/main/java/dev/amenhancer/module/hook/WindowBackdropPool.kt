package dev.amenhancer.module.hook

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.view.View
import androidx.annotation.RequiresApi
import com.kyant.backdrop.backdrops.ViewBackdrop
import dev.amenhancer.glass.GlassHostView
import java.util.WeakHashMap

/** Pure backdrop hierarchy guard; prevents self-capture feedback loops across windows and View trees. */
internal object BackdropSafetyPolicy {
    fun isSafeHierarchy(consumerIsSource: Boolean, sourceContainsConsumer: Boolean): Boolean =
        !consumerIsSource && !sourceContainsConsumer

    fun isSafeBackdropSource(consumer: View?, source: View?): Boolean {
        if (consumer == null || source == null) return false
        if (consumer === source) return isSafeHierarchy(consumerIsSource = true, sourceContainsConsumer = false)
        var current: View? = consumer.parent as? View
        while (current != null) {
            if (current === source) return isSafeHierarchy(consumerIsSource = false, sourceContainsConsumer = true)
            current = current.parent as? View
        }
        return isSafeHierarchy(consumerIsSource = false, sourceContainsConsumer = false)
    }
}

/** Builds a resource-isolated context for module Compose glass views inside host processes. */
internal fun moduleGlassContext(base: Context): Context {
    val info = checkNotNull(ModernXposedRuntime.activeModule()).moduleApplicationInfo
    val resources = base.packageManager.getResourcesForApplication(info)
    @Suppress("DEPRECATION")
    val isolated = Resources(resources.assets, base.resources.displayMetrics, Configuration(base.resources.configuration))
    val theme = isolated.newTheme().apply {
        applyStyle(info.theme.takeIf { it != 0 } ?: android.R.style.Theme_Material_Light_NoActionBar, true)
    }
    return object : ContextWrapper(base) {
        override fun getResources() = isolated
        override fun getAssets() = isolated.assets
        override fun getClassLoader() = GlassHostView::class.java.classLoader!!
        override fun getTheme() = theme
    }
}

/**
 * Shares one [ViewBackdrop] per source [View] in a window via [ViewBackdrop.acquireConsumer],
 * tearing down the RenderNode capture automatically once the final lease closes.
 */
@RequiresApi(33)
internal object WindowBackdropPool {
    private class Entry(val backdrop: ViewBackdrop) {
        var leases = 0
    }

    private val entries = WeakHashMap<View, Entry>()

    class Lease internal constructor(
        val backdrop: ViewBackdrop,
        private val onClose: () -> Unit,
    ) : AutoCloseable {
        private var closed = false
        override fun close() {
            if (closed) return
            closed = true
            onClose()
        }
    }

    @Synchronized
    fun acquire(
        source: View,
        minCaptureIntervalMs: Long = 16L,
        sourceRenderer: ((android.graphics.Canvas) -> Boolean)? = null,
        forceRefreshOnPreDraw: Boolean = false,
        onFailure: (Throwable) -> Unit = {},
    ): Lease {
        val entry = entries.getOrPut(source) {
            val backdrop = ViewBackdrop(
                source = source,
                onFailure = { error ->
                    evictIfSame(source)
                    onFailure(error)
                },
                sourceRenderer = sourceRenderer,
                minCaptureIntervalMs = minCaptureIntervalMs,
                forceRefreshOnPreDraw = forceRefreshOnPreDraw,
            ).apply { start() }
            Entry(backdrop)
        }
        entry.leases++
        val token = entry.backdrop.acquireConsumer()
        return Lease(entry.backdrop) {
            runCatching { token.close() }
            release(source, entry)
        }
    }

    @Synchronized
    private fun release(source: View, entry: Entry) {
        if (entries[source] !== entry) return
        entry.leases = (entry.leases - 1).coerceAtLeast(0)
        if (entry.leases == 0) {
            entries.remove(source)
            runCatching { entry.backdrop.close() }
        }
    }

    @Synchronized
    private fun evictIfSame(source: View) {
        entries.remove(source)?.let { runCatching { it.backdrop.close() } }
    }
}
