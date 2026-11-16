package mg.maniry.doremii.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLineDetectorTest {
    @Test
    fun findsEachTextLineAndIgnoresTheBarline() {
        val ink = LineInk(400, 180)
        val rows = listOf(20, 55, 100)
        for (top in rows) {
            for (x in listOf(16, 48, 90, 140, 200, 280, 340)) {
                block(ink, x, top, 8, 12)
            }
        }
        vertical(ink, 120, 8, 122, 3)
        ink.on(10, 150)
        ink.on(300, 8)
        ink.on(220, 170)

        val lines = TextLineDetector.detect(ink)
        assertEquals(3, lines.size)
        rows.forEachIndexed { index, top ->
            val line = lines[index]
            assertTrue(line.top <= top.toFloat())
            assertTrue(line.bottom >= (top + 12).toFloat())
            assertTrue(line.left <= 16f)
            assertTrue(line.right >= 348f)
            if (index > 0) assertTrue(line.top > lines[index - 1].bottom)
        }
    }

    @Test
    fun keepsWidelySpacedLettersOnOneLine() {
        val ink = LineInk(360, 80)
        for (x in listOf(12, 70, 140, 220, 300)) block(ink, x, 24, 10, 14)
        val lines = TextLineDetector.detect(ink)
        assertEquals(1, lines.size)
        assertTrue(lines.single().left <= 12f)
        assertTrue(lines.single().right >= 310f)
    }

    @Test
    fun keepsAHyphenRow() {
        val ink = LineInk(240, 60)
        block(ink, 20, 28, 160, 2)
        val lines = TextLineDetector.detect(ink)
        assertEquals(1, lines.size)
        assertTrue(lines.single().right - lines.single().left >= 150f)
    }

    private fun block(ink: LineInk, left: Int, top: Int, width: Int, height: Int) {
        for (y in top until top + height) {
            for (x in left until left + width) ink.on(x, y)
        }
    }

    private fun vertical(ink: LineInk, x: Int, top: Int, bottom: Int, width: Int) {
        for (y in top until bottom) {
            for (dx in 0 until width) ink.on(x + dx, y)
        }
    }
}

private class LineInk(override val width: Int, override val height: Int) : InkImage {
    private val dark = Array(height) { BooleanArray(width) }

    fun on(x: Int, y: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        dark[y][x] = true
    }

    override fun isDark(x: Int, y: Int): Boolean {
        return x in 0 until width && y in 0 until height && dark[y][x]
    }
}
