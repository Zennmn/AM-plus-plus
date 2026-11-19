package dev.amenhancer.module.hook

import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class WindowBackdropPoolPolicyTest {
    private inline fun <reified T> allocateStub(): T {
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null) as Unsafe
        @Suppress("UNCHECKED_CAST")
        return unsafe.allocateInstance(T::class.java) as T
    }

    @Test
    fun `forbids self-capture when consumer equals or descends from backdrop source`() {
        assertFalse(BackdropSafetyPolicy.isSafeHierarchy(consumerIsSource = true, sourceContainsConsumer = false))
        assertFalse(BackdropSafetyPolicy.isSafeHierarchy(consumerIsSource = false, sourceContainsConsumer = true))
        assertTrue(BackdropSafetyPolicy.isSafeHierarchy(consumerIsSource = false, sourceContainsConsumer = false))
        assertFalse(BackdropSafetyPolicy.isSafeBackdropSource(null, null))
    }

    @Test
    fun `context popup menu anchor defaults to normal insertion and preserves popup config`() {
        val container = allocateStub<FrameLayout>()
        val source = allocateStub<View>()
        val defaultAnchor = GlassSurfaceAnchor(
            role = GlassSurfaceRole.CONTEXT_POPUP_MENU,
            container = container,
            backdropSource = source,
            cornerRadiusDp = 24f,
            forceRefreshOnPreDraw = true,
        )
        assertEquals(GlassSurfaceRole.CONTEXT_POPUP_MENU, defaultAnchor.role)
        assertEquals(24f, defaultAnchor.cornerRadiusDp, 0f)
        assertTrue(defaultAnchor.forceRefreshOnPreDraw)
        assertFalse(defaultAnchor.insertBehindViaZOrder)

        val zOrderedAnchor = defaultAnchor.copy(insertBehindViaZOrder = true)
        assertTrue(zOrderedAnchor.insertBehindViaZOrder)
    }
}
