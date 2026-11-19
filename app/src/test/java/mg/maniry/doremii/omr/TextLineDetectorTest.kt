package mg.maniry.doremii.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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
    fun characterHeightsJoinSlicesOfOneLine() {
        val ink = LineInk(360, 120)
        writeSlices(ink, listOf(20, 45, 70))
        assertTrue(TextLineDetector.detect(ink).size > 1)

        val lines = TextLineDetector.detect(ink, letters(20f, 75f))
        assertEquals(1, lines.size)
        assertTrue(lines.single().top <= 20f)
        assertTrue(lines.single().bottom >= 75f)
        assertTrue(lines.single().left <= 20f)
        assertTrue(lines.single().right >= 280f)
    }

    @Test
    fun characterHeightsKeepSeparateLines() {
        val ink = LineInk(360, 280)
        writeSlices(ink, listOf(20, 45, 70))
        writeSlices(ink, listOf(150, 175, 200))
        val symbols = letters(20f, 75f) + letters(150f, 205f)
        val lines = TextLineDetector.detect(ink, symbols)
        assertEquals(2, lines.size)
        assertTrue(lines[0].bottom < lines[1].top)
        assertTrue(lines[0].bottom - lines[0].top >= 50f)
        assertTrue(lines[1].bottom - lines[1].top >= 50f)
    }

    @Test
    fun punctuationDoesNotSetTheLineHeight() {
        val ink = LineInk(360, 120)
        writeSlices(ink, listOf(20, 45, 70))
        val dots = listOf(20, 70, 130, 200, 270).map { x ->
            OcrSymbol(".", x.toFloat(), 20f, (x + 3).toFloat(), 25f, 0.9f)
        }
        assertTrue(TextLineDetector.detect(ink, dots).size > 1)
    }

    @Test
    fun thinAscendersStayInsideALineOfShortLetters() {
        val ink = LineInk(420, 180)
        val bodyTop = 48
        val bodyHeight = 16
        for (x in listOf(16, 48, 80, 112, 220, 270, 330)) block(ink, x, bodyTop, 12, bodyHeight)
        vertical(ink, 150, 32, bodyTop + bodyHeight, 2)
        vertical(ink, 184, 32, bodyTop + bodyHeight, 2)
        for (x in listOf(16, 80, 150, 220, 300)) block(ink, x, 120, 12, bodyHeight)

        val lines = TextLineDetector.detect(ink)
        assertEquals(2, lines.size)
        assertTrue(lines[0].top <= 32f)
        assertTrue(lines[0].bottom >= (bodyTop + bodyHeight).toFloat())
        assertTrue(lines[0].bottom < lines[1].top)
        assertTrue(lines[1].top >= 110f)
    }

    @Test
    fun tallLetterBoxesRaiseALineThatStartsWithShortLetters() {
        val ink = LineInk(420, 180)
        val bodyTop = 48
        val bodyHeight = 16
        val bodyBottom = bodyTop + bodyHeight
        for (x in listOf(16, 48, 80, 112, 220, 270, 330)) block(ink, x, bodyTop, 12, bodyHeight)
        for (x in listOf(16, 80, 150, 220, 300)) block(ink, x, 120, 12, bodyHeight)
        val shorts = listOf(16, 48, 80, 112, 220, 270, 330).map { x ->
            OcrSymbol("m", x.toFloat(), bodyTop.toFloat(), (x + 12).toFloat(), bodyBottom.toFloat(), 0.9f)
        }
        val talls = listOf(150 to "f", 184 to "l").map { (x, name) ->
            OcrSymbol(name, x.toFloat(), 32f, (x + 10).toFloat(), bodyBottom.toFloat(), 0.9f)
        }
        val below = listOf(16, 80, 150, 220, 300).map { x ->
            OcrSymbol("s", x.toFloat(), 120f, (x + 12).toFloat(), 136f, 0.9f)
        }

        val lines = TextLineDetector.detect(ink, shorts + talls + below)
        assertEquals(2, lines.size)
        assertTrue(lines[0].top <= 32f)
        assertTrue(lines[0].bottom >= bodyBottom.toFloat())
        assertTrue(lines[0].bottom < lines[1].top)
        assertClose(lines[0].bottom - lines[0].top, 32f)
        assertClose(lines[1].bottom - lines[1].top, 32f)
    }

    @Test
    fun longUnderlineBetweenRowsStaysTwoLines() {
        val ink = LineInk(360, 140)
        val xs = listOf(20, 40, 60, 90, 120, 160, 200, 240)
        for (x in xs) {
            block(ink, x, 40, 10, 12)
            block(ink, x, 66, 10, 12)
        }
        block(ink, 16, 58, 240, 2)
        val symbols = xs.flatMap { x ->
            listOf(
                OcrSymbol("d", x.toFloat(), 16f, (x + 10).toFloat(), 52f, 0.9f),
                OcrSymbol("l", x.toFloat(), 66f, (x + 10).toFloat(), 102f, 0.9f),
            )
        }

        assertEquals(1, TextLineDetector.detect(ink, symbols).size)
        val cleaned = HorizontalRules.strip(ink, symbols)
        assertTrue(!cleaned.isDark(40, 58))
        val lines = TextLineDetector.detect(cleaned, symbols)
        assertEquals(2, lines.size)
        assertTrue(lines[0].bottom <= 58f)
        assertTrue(lines[1].top >= 60f)
        assertTrue(lines[0].bottom < lines[1].top)
        assertClose(lines[0].bottom - lines[0].top, 36f)
        assertClose(lines[1].bottom - lines[1].top, 36f)
    }

    @Test
    fun keepsAHyphenRow() {
        val ink = LineInk(240, 60)
        block(ink, 20, 28, 160, 2)
        val lines = TextLineDetector.detect(ink)
        assertEquals(1, lines.size)
        assertTrue(lines.single().right - lines.single().left >= 150f)
    }

    private fun assertClose(actual: Float, expected: Float) {
        assertTrue("expected $actual within 8 of $expected", abs(actual - expected) <= 8f)
    }

    private fun writeSlices(ink: LineInk, tops: List<Int>) {
        for (x in listOf(20, 70, 130, 200, 270)) {
            for (top in tops) block(ink, x, top, 10, 5)
        }
    }

    private fun letters(top: Float, bottom: Float): List<OcrSymbol> {
        return listOf(20, 70, 130, 200, 270).map { x ->
            OcrSymbol("d", x.toFloat(), top, (x + 10).toFloat(), bottom, 0.9f)
        }
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
