package dev.amenhancer.module.hook

import android.view.View

/** Single-writer arbitration between the dual-pane flat boundary sync and the tablet glass session. */
internal object TabletGlassChrome {
    private val activeRoots: MutableSet<View> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    @JvmStatic fun markGlassActive(root: View) { synchronized(activeRoots) { activeRoots.add(root) } }

    @JvmStatic fun clearGlassActive(root: View) { synchronized(activeRoots) { activeRoots.remove(root) } }

    @JvmStatic fun isGlassActive(root: View): Boolean =
        synchronized(activeRoots) { activeRoots.contains(root) }
}
