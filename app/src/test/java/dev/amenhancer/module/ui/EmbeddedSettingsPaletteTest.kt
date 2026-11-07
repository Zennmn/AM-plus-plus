package dev.amenhancer.module.ui

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedSettingsPaletteTest {
    @Test
    fun `host night flag selects dark regardless of other configuration flags`() {
        assertSame(EmbeddedSettingsPalette.Dark, EmbeddedSettingsPalette.forUiMode(0x21))
        assertSame(EmbeddedSettingsPalette.Dark, EmbeddedSettingsPalette.forUiMode(0xA4))
        assertSame(EmbeddedSettingsPalette.Light, EmbeddedSettingsPalette.forUiMode(0x11))
        assertSame(EmbeddedSettingsPalette.Light, EmbeddedSettingsPalette.forUiMode(0))
    }

    @Test
    fun `native entry button text remains readable in both host themes`() {
        listOf(EmbeddedSettingsPalette.Light, EmbeddedSettingsPalette.Dark).forEach { colors ->
            assertTrue(contrast(colors.onPrimary, colors.primary) >= 4.5)
        }
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color ushr shift) and 255) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return channel(16) * 0.2126 + channel(8) * 0.7152 + channel(0) * 0.0722
    }

    private fun contrast(first: Int, second: Int): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }
}
