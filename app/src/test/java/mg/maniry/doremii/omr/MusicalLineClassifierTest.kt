package mg.maniry.doremii.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicalLineClassifierTest {
    @Test
    fun matchingColonsAndPipesKeepBothLinesMusical() {
        val ink = ClassInk(480, 160)
        writeLine(ink, 80, 30)
        writeLine(ink, 80, 70)
        for (x in listOf(100, 160, 220, 280)) {
            colon(ink, x, 30)
            colon(ink, x, 70)
            pipe(ink, x + 24, 30)
            pipe(ink, x + 24, 70)
        }
        vertical(ink, 12, 24, 90, 3)

        val classified = classify(ink)

        assertEquals(1, classified.blocks.size)
        assertEquals(2, classified.lines.count { it.musical })
        assertTrue(classified.lines.all { it.musical })
    }

    @Test
    fun aLineWithoutBeatMarksStaysPlain() {
        val ink = ClassInk(480, 220)
        writeLine(ink, 80, 30)
        writeLine(ink, 80, 70)
        writeLine(ink, 80, 120)
        for (x in listOf(100, 160, 220, 280)) {
            colon(ink, x, 30)
            colon(ink, x, 70)
            pipe(ink, x + 24, 30)
            pipe(ink, x + 24, 70)
        }
        vertical(ink, 12, 24, 140, 3)

        val classified = classify(ink)

        assertEquals(1, classified.blocks.size)
        val musical = classified.lines.filter { it.musical }
        assertEquals(2, musical.size)
        assertTrue(musical.all { it.bottom < 110f })
        val plain = classified.lines.single { !it.musical }
        assertTrue(plain.top > 100f)
    }

    @Test
    fun aLineWithHalfTheColumnsIsNotMusical() {
        val ink = ClassInk(480, 220)
        writeLine(ink, 80, 30)
        writeLine(ink, 80, 70)
        writeLine(ink, 80, 120)
        for (x in listOf(100, 160, 220, 280)) {
            colon(ink, x, 30)
            colon(ink, x, 70)
            pipe(ink, x + 24, 30)
            pipe(ink, x + 24, 70)
        }
        colon(ink, 100, 120)
        colon(ink, 160, 120)
        pipe(ink, 124, 120)
        pipe(ink, 184, 120)
        vertical(ink, 12, 24, 140, 3)

        val classified = classify(ink)

        assertEquals(1, classified.blocks.size)
        assertEquals(2, classified.lines.count { it.musical })
        assertFalse(classified.lines.maxBy { it.top }.musical)
    }

    @Test
    fun aBoxWithNoSharedDelimitersIsDropped() {
        val ink = ClassInk(480, 160)
        writeLine(ink, 80, 30)
        writeLine(ink, 80, 70)
        val lines = TextLineDetector.detect(ink)
        val block = DebugBlock(
            lines.minOf { it.left },
            lines.minOf { it.top },
            lines.maxOf { it.right },
            lines.maxOf { it.bottom },
        )

        val classified = MusicalLineClassifier.classify(ink, lines, listOf(block))

        assertTrue(classified.blocks.isEmpty())
        assertEquals(lines.size, classified.lines.size)
        assertTrue(classified.lines.none { it.musical })
    }

    @Test
    fun oneLineWithColonsAndPipesIsMusical() {
        val ink = ClassInk(480, 120)
        writeLine(ink, 80, 36)
        for (x in listOf(100, 160, 220)) {
            colon(ink, x, 36)
            pipe(ink, x + 24, 36)
        }
        vertical(ink, 12, 28, 56, 3)

        val classified = classify(ink)

        assertEquals(1, classified.blocks.size)
        assertEquals(1, classified.lines.size)
        assertTrue(classified.lines.single().musical)
    }

    private fun classify(ink: ClassInk): ClassifiedLines {
        val lines = TextLineDetector.detect(ink)
        val blocks = TextBlockDetector.detect(ink, lines)
        return MusicalLineClassifier.classify(ink, lines, blocks)
    }

    private fun writeLine(ink: ClassInk, left: Int, top: Int) {
        for (x in listOf(left, left + 70, left + 180, left + 250)) {
            glyph(ink, x, top, 8, 12)
        }
    }

    private fun colon(ink: ClassInk, x: Int, top: Int) {
        glyph(ink, x, top + 1, 3, 3)
        glyph(ink, x, top + 7, 3, 3)
    }

    private fun pipe(ink: ClassInk, x: Int, top: Int) {
        vertical(ink, x, top + 2, top + 10, 2)
    }

    private fun glyph(ink: ClassInk, left: Int, top: Int, width: Int, height: Int) {
        for (y in top until top + height) {
            for (x in left until left + width) ink.on(x, y)
        }
    }

    private fun vertical(ink: ClassInk, x: Int, top: Int, bottom: Int, width: Int) {
        for (y in top until bottom) {
            for (dx in 0 until width) ink.on(x + dx, y)
        }
    }
}

private class ClassInk(override val width: Int, override val height: Int) : InkImage {
    private val dark = Array(height) { BooleanArray(width) }

    fun on(x: Int, y: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        dark[y][x] = true
    }

    override fun isDark(x: Int, y: Int): Boolean {
        return x in 0 until width && y in 0 until height && dark[y][x]
    }
}
