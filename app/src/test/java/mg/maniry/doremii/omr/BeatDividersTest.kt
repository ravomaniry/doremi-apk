package mg.maniry.doremii.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BeatDividersTest {
    @Test
    fun colonsPipesAndBarsOnTheSameColumnAreOneBeat() {
        val upper = line(40f, true)
        val lower = line(80f, true)
        val marks = listOf(
            bar(22f, 36f, 96f),
            mark(102f, 40f, DebugMarkKind.COLON),
            mark(102f, 80f, DebugMarkKind.PIPE),
            mark(182f, 40f, DebugMarkKind.PIPE),
            mark(182f, 80f, DebugMarkKind.COLON),
            mark(300f, 40f, DebugMarkKind.COLON),
        )
        val divided = divide(listOf(upper, lower), listOf(block(30f, 100f)), marks)

        assertXs(divided.beats, listOf(22f, 102f, 182f))
        assertTrue(divided.beats.all { it.top == 40f && it.bottom == 92f })
        assertTrue(divided.lines.all { it.musical })
    }

    @Test
    fun aMissedLineBetweenVoicesJoinsWhenDelimitersShareTheBeatColumns() {
        val upper = line(40f, true)
        val missed = line(76f, false)
        val lower = line(112f, true)
        val marks = listOf(
            mark(102f, 40f, DebugMarkKind.COLON),
            mark(182f, 40f, DebugMarkKind.COLON),
            mark(102f, 76f, DebugMarkKind.PIPE),
            mark(182f, 76f, DebugMarkKind.COLON),
            mark(102f, 112f, DebugMarkKind.PIPE),
            mark(182f, 112f, DebugMarkKind.PIPE),
        )
        val divided = divide(listOf(upper, missed, lower), listOf(block(30f, 140f)), marks)

        assertTrue(divided.lines[1].musical)
        assertXs(divided.beats, listOf(102f, 182f))
        assertTrue(divided.beats.all { it.top == 40f && it.bottom == 124f })
    }

    @Test
    fun onlyTheNearestLineAboveTheBoxIsCorrected() {
        val far = line(0f, false)
        val near = line(24f, false)
        val upper = line(40f, true)
        val lower = line(80f, true)
        val marks = listOf(
            mark(102f, 0f, DebugMarkKind.COLON),
            mark(182f, 0f, DebugMarkKind.PIPE),
            mark(102f, 40f, DebugMarkKind.COLON),
            mark(182f, 40f, DebugMarkKind.COLON),
            mark(102f, 80f, DebugMarkKind.PIPE),
            mark(182f, 80f, DebugMarkKind.PIPE),
        )
        val divided = divide(listOf(far, near, upper, lower), listOf(block(30f, 100f)), marks)

        assertFalse(divided.lines[0].musical)
        assertFalse(divided.lines[1].musical)
        assertXs(divided.beats, listOf(102f, 182f))
        assertTrue(divided.beats.all { it.top == 40f && it.bottom == 92f })
    }

    @Test
    fun theLineJustBelowTheBoxJoinsWhenItSharesBeatColumns() {
        val upper = line(40f, true)
        val lower = line(80f, true)
        val below = line(100f, false)
        val marks = listOf(
            mark(102f, 40f, DebugMarkKind.COLON),
            mark(182f, 40f, DebugMarkKind.COLON),
            mark(102f, 80f, DebugMarkKind.PIPE),
            mark(182f, 80f, DebugMarkKind.PIPE),
            mark(102f, 100f, DebugMarkKind.BAR),
            mark(182f, 100f, DebugMarkKind.COLON),
        )
        val divided = divide(listOf(upper, lower, below), listOf(block(30f, 96f)), marks)

        assertTrue(divided.lines[2].musical)
        assertXs(divided.beats, listOf(102f, 182f))
        assertTrue(divided.beats.all { it.top == 40f && it.bottom == 112f })
    }

    @Test
    fun delimitersOffTheBeatColumnsDoNotRecoverALine() {
        val above = line(24f, false)
        val upper = line(40f, true)
        val lower = line(80f, true)
        val marks = listOf(
            mark(40f, 24f, DebugMarkKind.COLON),
            mark(300f, 24f, DebugMarkKind.PIPE),
            mark(102f, 40f, DebugMarkKind.COLON),
            mark(182f, 40f, DebugMarkKind.COLON),
            mark(102f, 80f, DebugMarkKind.PIPE),
            mark(182f, 80f, DebugMarkKind.PIPE),
        )
        val divided = divide(listOf(above, upper, lower), listOf(block(30f, 100f)), marks)

        assertFalse(divided.lines[0].musical)
        assertTrue(divided.beats.all { it.top == 40f })
    }

    @Test
    fun oneSharedColumnDoesNotRecoverALine() {
        val above = line(24f, false)
        val upper = line(40f, true)
        val lower = line(80f, true)
        val marks = listOf(
            mark(102f, 24f, DebugMarkKind.COLON),
            mark(182f, 24f, DebugMarkKind.COLON),
            mark(102f, 40f, DebugMarkKind.COLON),
            mark(102f, 80f, DebugMarkKind.PIPE),
        )
        val divided = divide(listOf(above, upper, lower), listOf(block(30f, 100f)), marks)

        assertFalse(divided.lines[0].musical)
        assertEquals(1, divided.beats.size)
        assertEquals(102f, divided.beats.single().x, 0.1f)
    }

    @Test
    fun inkAtTheBeatColumnsRecoversALineWhoseMarksWereMissed() {
        val upper = line(40f, true)
        val missed = line(76f, false)
        val lower = line(112f, true)
        val marks = listOf(
            mark(102f, 40f, DebugMarkKind.COLON),
            mark(182f, 40f, DebugMarkKind.COLON),
            mark(102f, 112f, DebugMarkKind.PIPE),
            mark(182f, 112f, DebugMarkKind.PIPE),
        )
        val ink = DividerInk(400, 180)
        colon(ink, 101, 76)
        pipe(ink, 182, 76)
        val divided = BeatDividers.find(
            ink,
            listOf(upper, missed, lower),
            listOf(block(30f, 140f)),
            marks,
        )

        assertTrue(divided.lines[1].musical)
        assertXs(divided.beats, listOf(102f, 182f))
    }

    @Test
    fun aWideLetterOnABeatColumnDoesNotRecoverALine() {
        val upper = line(40f, true)
        val missed = line(76f, false)
        val lower = line(112f, true)
        val marks = listOf(
            mark(102f, 40f, DebugMarkKind.COLON),
            mark(182f, 40f, DebugMarkKind.COLON),
            mark(102f, 112f, DebugMarkKind.PIPE),
            mark(182f, 112f, DebugMarkKind.PIPE),
        )
        val ink = DividerInk(400, 180)
        letter(ink, 102, 76)
        letter(ink, 182, 76)
        val divided = BeatDividers.find(
            ink,
            listOf(upper, missed, lower),
            listOf(block(30f, 140f)),
            marks,
        )

        assertFalse(divided.lines[1].musical)
    }

    @Test
    fun draftBeatsStayOnTheDebugOverlay() {
        val beat = DebugMeasureLine(40f, 10f, 80f)
        val finished = MeasureDelimiters.finish(
            ScanDraft(
                boxes = emptyList(),
                notes = emptyList(),
                glyphs = emptyList(),
                windows = emptyList(),
                beats = listOf(beat),
            ),
            4,
        )
        assertEquals(listOf(beat), finished.parse.debug.beats)
    }

    private fun divide(
        lines: List<DebugLine>,
        blocks: List<DebugBlock>,
        marks: List<DebugMark>,
    ): DividedBeats {
        return BeatDividers.find(DividerInk(8, 8), lines, blocks, marks)
    }

    private fun assertXs(beats: List<DebugMeasureLine>, expected: List<Float>) {
        val actual = beats.map { it.x }.sorted()
        assertEquals(expected.size, actual.size)
        actual.zip(expected).forEach { (found, want) -> assertTrue(abs(found - want) < 0.1f) }
    }

    private fun line(top: Float, musical: Boolean): DebugLine {
        return DebugLine(40f, top, 400f, top + 12f, musical)
    }

    private fun block(top: Float, bottom: Float): DebugBlock {
        return DebugBlock(10f, top, 430f, bottom)
    }

    private fun mark(x: Float, top: Float, kind: DebugMarkKind): DebugMark {
        return DebugMark(x - 1.5f, top + 1f, x + 1.5f, top + 11f, kind)
    }

    private fun bar(x: Float, top: Float, bottom: Float): DebugMark {
        return DebugMark(x - 1.5f, top, x + 1.5f, bottom, DebugMarkKind.BAR)
    }

    private fun colon(ink: DividerInk, x: Int, top: Int) {
        for (y in top + 1 until top + 4) {
            for (dx in 0..2) ink.on(x + dx, y)
        }
        for (y in top + 7 until top + 10) {
            for (dx in 0..2) ink.on(x + dx, y)
        }
    }

    private fun pipe(ink: DividerInk, x: Int, top: Int) {
        for (y in top + 2 until top + 10) {
            ink.on(x, y)
            ink.on(x + 1, y)
        }
    }

    private fun letter(ink: DividerInk, x: Int, top: Int) {
        for (y in top until top + 12) {
            for (dx in -6..6) ink.on(x + dx, y)
        }
    }
}

private class DividerInk(override val width: Int, override val height: Int) : InkImage {
    private val dark = Array(height) { BooleanArray(width) }

    fun on(x: Int, y: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        dark[y][x] = true
    }

    override fun isDark(x: Int, y: Int): Boolean {
        return x in 0 until width && y in 0 until height && dark[y][x]
    }
}
