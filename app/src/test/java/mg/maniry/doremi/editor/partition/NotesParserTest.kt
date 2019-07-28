package mg.maniry.doremi.editor.partition

/**
 * Create all possible use cases notes
 * Parse it
 * Expect all possible values
 */

import org.junit.Assert.*
import org.junit.Test

class NotesParserTest {
    private val parser = NotesParser()
    private val parsed: MutableList<Note>
    private val delay = 120L
    private val timeUnit = 480L

    init {
        parser.voicesNum = 4
        parser.voiceIds = mutableListOf("S", "B", "T", "A")
        parser.playedVoices = MutableList(parser.voicesNum) { true }
        val notes = mutableListOf(
                mutableListOf("d", "d1"),
                mutableListOf("d", "d1"),
                mutableListOf("d", ""),
                mutableListOf("d", "d1"))
        parsed = parser.parse(notes)
    }

    @Test
    fun sizeTest() {
        assertEquals(7, parsed.size)
    }


    @Test
    fun channelTests() {
        assertEquals(0, parsed[0].channel)
        assertEquals(0, parsed[1].channel)
        assertEquals(1, parsed[2].channel)
        assertEquals(2, parsed[4].channel)
    }

    @Test
    fun pitchesTests() {
        assertEquals(60, parsed[0].pitch)
        assertEquals(72, parsed[1].pitch)
        assertEquals(48, parsed[2].pitch)
        assertEquals(60, parsed[3].pitch)
    }

    @Test
    fun tickTests() {
        assertEquals(delay, parsed[0].tick)
        assertEquals(delay + timeUnit, parsed[1].tick)
        assertEquals(delay, parsed[2].tick)
    }


}
