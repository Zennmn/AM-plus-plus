package dev.amenhancer.module.hook

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicDpiOverridePolicyTest {
    @Test
    fun `zero follows system and only the bounded range is accepted`() {
        assertTrue(AppleMusicDpiOverridePolicy.isValidDpi(0))
        assertTrue(AppleMusicDpiOverridePolicy.isValidDpi(160))
        assertTrue(AppleMusicDpiOverridePolicy.isValidDpi(640))
        assertFalse(AppleMusicDpiOverridePolicy.isValidDpi(159))
        assertFalse(AppleMusicDpiOverridePolicy.isValidDpi(641))
        assertEquals(0, AppleMusicDpiOverridePolicy.normalizeDpi(-1))
        assertEquals(0, AppleMusicDpiOverridePolicy.normalizeDpi(999))
    }

    @Test
    fun `screen dimensions preserve physical size when density changes`() {
        assertEquals(1706, AppleMusicDpiOverridePolicy.scaleDp(1280, 320, 240))
        assertEquals(928, AppleMusicDpiOverridePolicy.scaleDp(696, 320, 240))
        assertEquals(960, AppleMusicDpiOverridePolicy.scaleDp(720, 320, 240))
        assertEquals(853, AppleMusicDpiOverridePolicy.scaleDp(1280, 320, 480))
        assertEquals(464, AppleMusicDpiOverridePolicy.scaleDp(696, 320, 480))
        assertEquals(0, AppleMusicDpiOverridePolicy.scaleDp(0, 320, 240))
    }

    @Test
    fun `screen layout recalculates size and long bits while preserving other flags`() {
        val original = Configuration.SCREENLAYOUT_LAYOUTDIR_RTL or
            Configuration.SCREENLAYOUT_ROUND_YES
        val result = AppleMusicDpiOverridePolicy.recomputeScreenLayout(
            original = original,
            widthDp = 1706,
            heightDp = 928,
        )

        assertEquals(
            Configuration.SCREENLAYOUT_LAYOUTDIR_RTL,
            result and Configuration.SCREENLAYOUT_LAYOUTDIR_MASK,
        )
        assertEquals(
            Configuration.SCREENLAYOUT_ROUND_YES,
            result and Configuration.SCREENLAYOUT_ROUND_MASK,
        )
        assertEquals(Configuration.SCREENLAYOUT_SIZE_XLARGE, result and Configuration.SCREENLAYOUT_SIZE_MASK)
        assertEquals(Configuration.SCREENLAYOUT_LONG_YES, result and Configuration.SCREENLAYOUT_LONG_MASK)
        assertTrue((result and AppleMusicDpiOverridePolicy.SCREENLAYOUT_COMPAT_NEEDED) != 0)
    }

    @Test
    fun `undefined dimensions leave the existing layout qualifier untouched`() {
        val original = Configuration.SCREENLAYOUT_SIZE_LARGE or
            Configuration.SCREENLAYOUT_LONG_NO or
            Configuration.SCREENLAYOUT_LAYOUTDIR_LTR
        assertEquals(
            original,
            AppleMusicDpiOverridePolicy.recomputeScreenLayout(original, 0, 0),
        )
    }

    @Test
    fun `very small displays keep the Android small non-long layout rule`() {
        val result = AppleMusicDpiOverridePolicy.recomputeScreenLayout(
            original = 0,
            widthDp = 320,
            heightDp = 460,
        )
        assertEquals(Configuration.SCREENLAYOUT_SIZE_SMALL, result and Configuration.SCREENLAYOUT_SIZE_MASK)
        assertEquals(Configuration.SCREENLAYOUT_LONG_NO, result and Configuration.SCREENLAYOUT_LONG_MASK)
        assertEquals(0, result and AppleMusicDpiOverridePolicy.SCREENLAYOUT_COMPAT_NEEDED)
    }
}
