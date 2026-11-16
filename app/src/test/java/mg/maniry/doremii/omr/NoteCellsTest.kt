package mg.maniry.doremii.omr

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteCellsTest {
    @Test
    fun sharedGapsGiveEveryVoiceTheSameCells() {
        val systems = NoteCells.grid(
            listOf(voice(20f, 400f), voice(50f, 390f)),
            listOf(block(10f, 80f)),
            listOf(column(110f), column(210f), column(310f)),
        )

        assertEquals(1, systems.size)
        assertEquals(2, systems[0].size)
        assertEquals(listOf(10f, 110f, 210f, 310f), systems[0][0].map { it.left })
        assertEquals(listOf(110f, 210f, 310f, 400f), systems[0][0].map { it.right })
        assertEquals(systems[0][0].map { it.left to it.right }, systems[0][1].map { it.left to it.right })
        assertEquals(20f, systems[0][0][0].top)
        assertEquals(50f, systems[0][1][0].top)
    }

    @Test
    fun aThinTrailingMarginIsNotACell() {
        val systems = NoteCells.grid(
            listOf(voice(20f, 320f), voice(50f, 320f)),
            listOf(block(10f, 80f)),
            listOf(column(110f), column(210f), column(310f)),
        )

        assertEquals(listOf(10f, 110f, 210f), systems[0][0].map { it.left })
        assertEquals(310f, systems[0][0].last().right)
    }

    @Test
    fun aBlankReadInsertsAnEmptyCell() {
        val systems = NoteCells.grid(
            listOf(voice(20f, 220f), voice(50f, 220f)),
            listOf(block(10f, 80f)),
            listOf(column(110f)),
        )
        val voices = NoteCells.read(systems) { cell ->
            when {
                cell.left < 50f && cell.top < 40f -> "d"
                cell.left < 50f -> "qq"
                else -> ""
            }
        }

        assertEquals(listOf("d", ""), voices[0].cells)
        assertEquals(listOf("", ""), voices[1].cells)
        assertEquals(listOf("S", "A"), voices.map { it.id })
    }

    @Test
    fun aShorterSystemPadsTheMissingVoices() {
        val first = NoteCells.grid(
            listOf(voice(20f, 220f), voice(50f, 220f)),
            listOf(block(10f, 80f)),
            listOf(column(110f)),
        )
        val second = NoteCells.grid(
            listOf(voice(120f, 340f)),
            listOf(block(100f, 150f)),
            listOf(column(180f), column(260f)),
        )
        val voices = NoteCells.read(first + second) { "" }

        assertEquals(2, voices.size)
        assertEquals(5, voices[0].cells.size)
        assertEquals(5, voices[1].cells.size)
        assertEquals(List(5) { "" }, voices[1].cells)
    }

    private fun voice(top: Float, right: Float): DebugLine {
        return DebugLine(10f, top, right, top + 12f, musical = true)
    }

    private fun block(top: Float, bottom: Float): DebugBlock {
        return DebugBlock(0f, top, 430f, bottom)
    }

    private fun column(x: Float): DebugMeasureLine {
        return DebugMeasureLine(x, 0f, 400f)
    }
}
