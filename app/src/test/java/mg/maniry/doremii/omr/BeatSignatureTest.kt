package mg.maniry.doremii.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeatSignatureTest {
    @Test
    fun twoColonArraysBetweenBarsDetectThree() {
        val lines = listOf(line(20f), line(50f))
        val block = listOf(block())
        val beats = listOf(
            column(20f),
            column(80f),
            column(140f),
            column(200f),
        )
        val marks = listOf(
            bar(20f),
            colon(80f, 20f),
            colon(80f, 50f),
            colon(140f, 20f),
            colon(140f, 50f),
            bar(200f),
        )

        assertEquals(3, BeatSignature.detect(lines, block, marks, beats))
    }

    @Test
    fun oneColonArrayBetweenPipesDetectsFour() {
        val lines = listOf(line(20f), line(50f))
        val block = listOf(block())
        val beats = listOf(
            column(20f),
            column(70f),
            column(120f),
            column(170f),
            column(220f),
        )
        val marks = listOf(
            pipe(20f, 20f),
            pipe(20f, 50f),
            colon(70f, 20f),
            colon(70f, 50f),
            pipe(120f, 20f),
            pipe(120f, 50f),
            colon(170f, 20f),
            colon(170f, 50f),
            pipe(220f, 20f),
            pipe(220f, 50f),
        )

        assertEquals(4, BeatSignature.detect(lines, block, marks, beats))
    }

    @Test
    fun aTieLeavesTheSignatureUnset() {
        val lines = listOf(line(20f), line(50f))
        val block = listOf(block())
        val beats = listOf(
            column(10f),
            column(50f),
            column(90f),
            column(140f),
            column(180f),
            column(230f),
        )
        val marks = listOf(
            bar(10f),
            colon(50f, 20f),
            colon(50f, 50f),
            colon(90f, 20f),
            colon(90f, 50f),
            bar(140f),
            colon(180f, 20f),
            colon(180f, 50f),
            bar(230f),
        )

        assertNull(BeatSignature.detect(lines, block, marks, beats))
    }

    @Test
    fun leadingColonsAndAThreeColonSpanLeaveTheFourFourVote() {
        val lines = listOf(line(20f), line(50f))
        val block = listOf(block())
        val beats = listOf(
            column(20f),
            column(60f),
            column(100f),
            column(140f),
            column(180f),
            column(240f),
            column(300f),
        )
        val marks = listOf(
            colon(20f, 20f),
            colon(20f, 50f),
            bar(60f),
            colon(100f, 20f),
            colon(100f, 50f),
            colon(140f, 20f),
            colon(140f, 50f),
            colon(180f, 20f),
            colon(180f, 50f),
            bar(240f),
            colon(300f, 20f),
            colon(300f, 50f),
            bar(360f),
        )
        val withClosing = beats + column(360f)

        assertEquals(4, BeatSignature.detect(lines, block, marks, withClosing))
    }

    private fun line(top: Float): DebugLine {
        return DebugLine(10f, top, 400f, top + 12f, musical = true)
    }

    private fun block(): DebugBlock {
        return DebugBlock(0f, 10f, 420f, 80f)
    }

    private fun column(x: Float): DebugMeasureLine {
        return DebugMeasureLine(x, 18f, 64f)
    }

    private fun colon(x: Float, top: Float): DebugMark {
        return DebugMark(x - 1.5f, top + 1f, x + 1.5f, top + 11f, DebugMarkKind.COLON)
    }

    private fun pipe(x: Float, top: Float): DebugMark {
        return DebugMark(x - 1f, top + 1f, x + 1f, top + 11f, DebugMarkKind.PIPE)
    }

    private fun bar(x: Float): DebugMark {
        return DebugMark(x - 1.5f, 16f, x + 1.5f, 66f, DebugMarkKind.BAR)
    }
}
