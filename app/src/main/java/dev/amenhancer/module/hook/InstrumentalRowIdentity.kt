package dev.amenhancer.module.hook

import android.view.View
import java.util.Collections
import java.util.WeakHashMap

/** Process-local semantic identity for inflated instrumental lyric rows. */
internal object InstrumentalRowIdentity {
    private val rows = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    fun mark(view: View) {
        rows[view] = true
    }

    fun matches(view: View): Boolean = rows.containsKey(view)
}
