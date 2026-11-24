package mg.maniry.doremii.editor.partition

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NotesUpdaterMoveTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun shiftRightOverlapsAndClearsTheVacatedColumn() {
        val data = score(
            listOf("a", "b", "c", "d"),
            listOf("e", "f", "g", "h"),
            listOf("i", "j", "k", "l"),
            listOf("m", "n", "o", "p"),
        )

        val moved = NotesUpdater(data).move(ClipBoard(Cell(0, 1), Cell(1, 2)), 0, 1)

        assertNotNull(moved)
        assertEquals(listOf("a", "", "b", "c"), data.notes[0])
        assertEquals(listOf("e", "", "f", "g"), data.notes[1])
        assertEquals(listOf("i", "j", "k", "l"), data.notes[2])
        assertEquals(listOf("m", "n", "o", "p"), data.notes[3])
    }

    @Test
    fun shiftLeftClearsTheVacatedCell() {
        val data = score(
            listOf("a", "b", "c"),
            listOf("d", "e", "f"),
        )

        NotesUpdater(data).move(ClipBoard(Cell(0, 1), Cell(1, 1)), 0, -1)

        assertEquals(listOf("b", "", "c"), data.notes[0])
        assertEquals(listOf("e", "", "f"), data.notes[1])
    }

    @Test
    fun shiftUpOverwritesDestinationAndClearsSource() {
        val data = score(
            listOf("a", "b", "c"),
            listOf("d", "e", "f"),
            listOf("g", "h", "i"),
        )

        NotesUpdater(data).move(ClipBoard(Cell(1, 0), Cell(1, 1)), -1, 0)

        assertEquals(listOf("d", "e", "c"), data.notes[0])
        assertEquals(listOf("", "", "f"), data.notes[1])
        assertEquals(listOf("g", "h", "i"), data.notes[2])
    }

    @Test
    fun emptyCellsInsideTheScoreCountAsRoom() {
        val data = score(
            listOf("a", "b", "c", "d"),
            listOf("e", "f"),
        )

        val moved = NotesUpdater(data).move(ClipBoard(Cell(0, 3), Cell(0, 3)), 1, 0)

        assertNotNull(moved)
        assertEquals("", data.notes[0][3])
        assertEquals(listOf("e", "f", "", "d"), data.notes[1])
    }

    @Test
    fun refusesToLeaveTheScore() {
        val data = score(
            listOf("a", "b"),
            listOf("c", "d"),
        )
        val updater = NotesUpdater(data)

        val left = ClipBoard(Cell(0, 0), Cell(1, 0))
        assertFalse(updater.canMove(left, 0, -1))
        assertNull(updater.move(left, 0, -1))

        val top = ClipBoard(Cell(0, 0), Cell(0, 1))
        assertFalse(updater.canMove(top, -1, 0))
        assertNull(updater.move(top, -1, 0))

        val bottom = ClipBoard(Cell(1, 0), Cell(1, 1))
        assertFalse(updater.canMove(bottom, 1, 0))
        assertNull(updater.move(bottom, 1, 0))

        assertEquals(listOf("a", "b"), data.notes[0])
        assertEquals(listOf("c", "d"), data.notes[1])
    }

    @Test
    fun shiftRightPastTheEndAddsAColumn() {
        val data = score(
            listOf("a", "b"),
            listOf("c", "d"),
        )
        val updater = NotesUpdater(data)
        val selection = ClipBoard(Cell(0, 1), Cell(1, 1))

        assertTrue(updater.canMove(selection, 0, 1))
        val moved = updater.move(selection, 0, 1)

        assertNotNull(moved)
        assertEquals(listOf("a", "", "b"), data.notes[0])
        assertEquals(listOf("c", "", "d"), data.notes[1])
    }

    private fun score(vararg rows: List<String>): PartitionData {
        val data = PartitionData()
        data.voicesNum = rows.size
        data.notes = rows.map { it.toMutableList() }.toMutableList()
        return data
    }
}
