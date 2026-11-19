package mg.maniry.doremii.omr

import org.junit.Assert.assertTrue
import org.junit.Test

class HorizontalRulesTest {
    @Test
    fun removesRulesAtLeastTwoCharactersWideAndKeepsADash() {
        val ink = RuleInk(220, 48)
        block(ink, 12, 8, 10, 2)
        block(ink, 12, 30, 80, 2)
        block(ink, 40, 6, 3, 36)
        block(ink, 150, 8, 40, 16)
        val symbols = listOf(
            OcrSymbol("d", 12f, 8f, 22f, 24f, 0.9f),
            OcrSymbol("r", 28f, 8f, 38f, 24f, 0.9f),
        )

        val cleaned = HorizontalRules.strip(ink, symbols)

        assertTrue(cleaned.isDark(14, 8))
        assertTrue(cleaned.isDark(14, 9))
        assertTrue(!cleaned.isDark(20, 30))
        assertTrue(!cleaned.isDark(70, 31))
        assertTrue(cleaned.isDark(41, 20))
        assertTrue(cleaned.isDark(160, 16))
    }

    private fun block(ink: RuleInk, left: Int, top: Int, width: Int, height: Int) {
        for (y in top until top + height) {
            for (x in left until left + width) ink.on(x, y)
        }
    }
}

private class RuleInk(override val width: Int, override val height: Int) : InkImage {
    private val dark = Array(height) { BooleanArray(width) }

    fun on(x: Int, y: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        dark[y][x] = true
    }

    override fun isDark(x: Int, y: Int): Boolean {
        return x in 0 until width && y in 0 until height && dark[y][x]
    }
}
