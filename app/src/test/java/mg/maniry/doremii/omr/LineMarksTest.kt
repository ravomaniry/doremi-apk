package mg.maniry.doremii.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineMarksTest {
    @Test
    fun marksColonsPipesAndTheBarThatCrossesTheLines() {
        val ink = MarkInk(420, 160)
        writeLine(ink, 80, 30)
        writeLine(ink, 80, 78)
        colon(ink, 110, 30)
        colon(ink, 110, 78)
        pipe(ink, 134, 30)
        pipe(ink, 134, 78)
        vertical(ink, 16, 20, 110, 3)

        val lines = listOf(
            DebugLine(76f, 30f, 170f, 42f),
            DebugLine(76f, 78f, 170f, 90f),
        )
        val blocks = listOf(DebugBlock(12f, 18f, 180f, 116f))
        val marks = LineMarks.find(ink, lines, blocks)

        assertEquals(2, marks.count { it.kind == DebugMarkKind.COLON })
        assertEquals(2, marks.count { it.kind == DebugMarkKind.PIPE })
        assertEquals(1, marks.count { it.kind == DebugMarkKind.BAR })
        val bar = marks.single { it.kind == DebugMarkKind.BAR }
        assertTrue(bar.left < 30f)
        assertTrue(bar.bottom - bar.top > 50f)
        assertTrue(marks.none { it.kind != DebugMarkKind.BAR && it.left < 40f })
        assertTrue(marks.filter { it.kind == DebugMarkKind.COLON }.all { it.left in 100f..120f })
        assertTrue(marks.filter { it.kind == DebugMarkKind.PIPE }.all { it.left in 124f..146f })
    }

    @Test
    fun lettersAloneAreNotDelimiters() {
        val ink = MarkInk(220, 80)
        glyph(ink, 20, 20, 10, 14)
        glyph(ink, 50, 20, 10, 14)
        glyph(ink, 90, 20, 10, 14)
        val marks = LineMarks.find(ink, listOf(DebugLine(18f, 18f, 110f, 36f)), emptyList())
        assertTrue(marks.isEmpty())
    }

    @Test
    fun detectedLinesKeepTheSameDelimiters() {
        val ink = MarkInk(480, 160)
        writeLine(ink, 80, 30)
        writeLine(ink, 80, 78)
        for (x in listOf(110, 200)) {
            colon(ink, x, 30)
            colon(ink, x, 78)
            pipe(ink, x + 24, 30)
            pipe(ink, x + 24, 78)
        }
        vertical(ink, 16, 22, 112, 3)

        val lines = TextLineDetector.detect(ink)
        val blocks = TextBlockDetector.detect(ink, lines)
        val marks = LineMarks.find(ink, lines, blocks)

        assertTrue(lines.size >= 2)
        assertEquals(4, marks.count { it.kind == DebugMarkKind.COLON })
        assertEquals(4, marks.count { it.kind == DebugMarkKind.PIPE })
        assertEquals(1, marks.count { it.kind == DebugMarkKind.BAR })
    }

    @Test
    fun colonAboveUnderlineIsKept() {
        val ink = MarkInk(280, 80)
        colon(ink, 70, 20)
        underline(ink, 40, 120, 33, 2)
        colon(ink, 180, 20)
        underline(ink, 150, 240, 30, 2)
        val lines = listOf(DebugLine(30f, 20f, 250f, 44f))
        val marks = LineMarks.find(ink, lines, emptyList())

        assertEquals(2, marks.count { it.kind == DebugMarkKind.COLON })
        assertTrue(marks.none { it.kind == DebugMarkKind.PIPE || it.kind == DebugMarkKind.BAR })
        val colons = marks.filter { it.kind == DebugMarkKind.COLON }.map { it.left }.sorted()
        assertTrue(colons[0] in 66f..78f)
        assertTrue(colons[1] in 176f..188f)
    }

    @Test
    fun fatLettersAreNotBarsOrPipes() {
        val ink = MarkInk(240, 110)
        letterD(ink, 40, 24)
        letterS(ink, 80, 26)
        letterF(ink, 120, 24)
        vertical(ink, 8, 10, 100, 2)
        vertical(ink, 180, 36, 62, 2)
        val lines = listOf(DebugLine(20f, 30f, 210f, 70f))
        val marks = LineMarks.find(ink, lines, emptyList())

        assertEquals(1, marks.count { it.kind == DebugMarkKind.BAR })
        assertEquals(1, marks.count { it.kind == DebugMarkKind.PIPE })
        assertTrue(marks.none { it.kind == DebugMarkKind.COLON })
        val bar = marks.single { it.kind == DebugMarkKind.BAR }
        assertTrue(bar.left < 20f)
        val pipe = marks.single { it.kind == DebugMarkKind.PIPE }
        assertTrue(pipe.left in 170f..190f)
    }

    private fun writeLine(ink: MarkInk, left: Int, top: Int) {
        for (x in listOf(left, left + 70)) glyph(ink, x, top, 8, 12)
    }

    private fun colon(ink: MarkInk, x: Int, top: Int) {
        glyph(ink, x, top + 1, 3, 3)
        glyph(ink, x, top + 7, 3, 3)
    }

    private fun pipe(ink: MarkInk, x: Int, top: Int) {
        vertical(ink, x, top + 2, top + 10, 2)
    }

    private fun glyph(ink: MarkInk, left: Int, top: Int, width: Int, height: Int) {
        for (y in top until top + height) {
            for (x in left until left + width) ink.on(x, y)
        }
    }

    private fun vertical(ink: MarkInk, x: Int, top: Int, bottom: Int, width: Int) {
        for (y in top until bottom) {
            for (dx in 0 until width) ink.on(x + dx, y)
        }
    }

    private fun underline(ink: MarkInk, left: Int, right: Int, top: Int, thickness: Int) {
        glyph(ink, left, top, right - left, thickness)
    }

    private fun letterD(ink: MarkInk, left: Int, top: Int) {
        glyph(ink, left, top + 12, 10, 20)
        glyph(ink, left + 10, top, 3, 50)
    }

    private fun letterS(ink: MarkInk, left: Int, top: Int) {
        glyph(ink, left, top, 12, 8)
        glyph(ink, left, top + 14, 12, 8)
        glyph(ink, left, top + 28, 12, 10)
    }

    private fun letterF(ink: MarkInk, left: Int, top: Int) {
        glyph(ink, left + 10, top, 3, 50)
        glyph(ink, left, top + 18, 13, 3)
        glyph(ink, left, top + 2, 4, 8)
    }
}

private class MarkInk(override val width: Int, override val height: Int) : InkImage {
    private val dark = Array(height) { BooleanArray(width) }

    fun on(x: Int, y: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        dark[y][x] = true
    }

    override fun isDark(x: Int, y: Int): Boolean {
        return x in 0 until width && y in 0 until height && dark[y][x]
    }
}
