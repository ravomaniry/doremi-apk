package mg.maniry.doremi.editor.partition

/**
 * Create all possible use cases notes
 * Parse it
 * Expect all possible values
 */

import org.junit.Assert.*
import org.junit.Test

class NotesParserTest {
    private val delay = 120L
    private val timeUnit = 480L
    private val release = 5
    private val velocityLetters = listOf("pp", "p", "mp", "mf", "f", "ff")
    private val velocityNumbers = listOf(78, 84, 90, 100, 110, 120)


    @Test
    fun basic() {
        val parser = NotesParser().apply {
            key = 2
            voiceIds = mutableListOf("S", "B", "T", "A")
            playedVoices = mutableListOf(true, true, true, true)
        }
        val notes = mutableListOf(
                mutableListOf("d", "d1"),
                mutableListOf("d", "d1"),
                mutableListOf("d", "-"),
                mutableListOf("d", "d1"))
        val parsed = parser.parse(notes)

        assertEquals(7, parsed.size)
        assertEquals(0, parsed[0].channel)
        assertEquals(0, parsed[1].channel)
        assertEquals(1, parsed[2].channel)
        assertEquals(2, parsed[4].channel)
        assertEquals(62, parsed[0].pitch)
        assertEquals(74, parsed[1].pitch)
        assertEquals(50, parsed[2].pitch)
        assertEquals(62, parsed[3].pitch)
        assertEquals(delay, parsed[0].tick)
        assertEquals(delay + timeUnit, parsed[1].tick)
        assertEquals(delay, parsed[2].tick)
        assertEquals(timeUnit - release, parsed[0].duration)
        assertEquals(110, parsed[0].velocity)
        assertEquals(98, parsed[2].velocity)
        assertEquals(101, parsed[4].velocity)
        assertEquals(104, parsed[5].velocity)
    }


    @Test
    fun multipleNotes() {
        val notes = mutableListOf(mutableListOf("d", "-.r,m", "s, s ., d1", "s.,m", "r.m.f"))
        val parser = NotesParser().apply {
            voiceIds = mutableListOf("S")
            playedVoices = mutableListOf(true)
        }
        val parsed = parser.parse(notes)

        assertEquals(11, parsed.size)
        assertEquals(delay, parsed[0].tick)
        assertEquals((delay + timeUnit * 1.5).toLong(), parsed[1].tick)
        assertEquals((delay + 1.75 * timeUnit).toLong(), parsed[2].tick)
        assertEquals(delay + 2 * timeUnit, parsed[3].tick)
        assertEquals((delay + 2.25 * timeUnit).toLong(), parsed[4].tick)
        assertEquals((delay + 2.75 * timeUnit).toLong(), parsed[5].tick)
        assertEquals(delay + 4 * timeUnit + timeUnit / 3, parsed[9].tick)
        assertEquals(delay + 4 * timeUnit + timeUnit * 2 / 3, parsed[10].tick)

        assertEquals((1.5 * timeUnit - release).toLong(), parsed[0].duration)
        assertEquals(timeUnit / 4 - release, parsed[1].duration)
        assertEquals(timeUnit / 4 - release, parsed[2].duration)
        assertEquals(timeUnit / 4 - release, parsed[3].duration)
        assertEquals(timeUnit / 4 - release, parsed[4].duration)
        assertEquals(timeUnit / 4 - release, parsed[5].duration)
        assertEquals(timeUnit / 2 - release, parsed[6].duration)
        assertEquals(timeUnit / 3 - release, parsed[8].duration)
        assertEquals(timeUnit / 3 - release, parsed[9].duration)
        assertEquals(timeUnit / 3 - release, parsed[10].duration)
    }


    @Test
    fun swing() {
        val notes = mutableListOf(mutableListOf("d.r", "m.,f"))
        val parser = NotesParser().apply {
            swing = true
            voiceIds = mutableListOf("S")
            playedVoices = mutableListOf(true)
        }
        val parsed = parser.parse(notes)

        assertEquals(4, parsed.size)
        assertEquals(timeUnit * 2 / 3 - release, parsed[0].duration)
        assertEquals(timeUnit * 1 / 3 - release, parsed[1].duration)
        assertEquals(timeUnit * 2 / 3 + delay, parsed[1].tick)
        assertEquals(timeUnit * 2 / 3 - release, parsed[2].duration)
        assertEquals(timeUnit * 1 / 3 - release, parsed[3].duration)
        assertEquals(timeUnit * 5 / 3 + delay, parsed[3].tick)
    }


    @Test
    fun modulations() {
        val notes = mutableListOf(mutableListOf("d", "d", "d"))
        val modulations = mutableListOf(
                ChangeEvent(1, ChangeEvent.MOD, "D"),
                ChangeEvent(2, ChangeEvent.MOD, "F#"))
        val parser = NotesParser().apply {
            voiceIds = mutableListOf("S")
            playedVoices = mutableListOf(true)
            changeEvents = modulations
        }
        val parsed = parser.parse(notes)

        assertEquals(60, parsed[0].pitch)
        assertEquals(62, parsed[1].pitch)
        assertEquals(66, parsed[2].pitch)
    }


    @Test
    fun tempoChange() {
        val notes = mutableListOf(mutableListOf("d", "d", "d"))
        val tempoChanges = mutableListOf(
                ChangeEvent(1, ChangeEvent.TEMPO, "60"),
                ChangeEvent(2, ChangeEvent.TEMPO, "120"))
        val parser = NotesParser().apply {
            tempo = 120
            voiceIds = mutableListOf("S")
            playedVoices = mutableListOf(true)
            changeEvents = tempoChanges
        }
        val parsed = parser.parse(notes)
        assertEquals(delay + timeUnit, parsed[1].tick)
        assertEquals(delay + timeUnit * 3, parsed[2].tick)
        assertEquals(timeUnit * 2 - release, parsed[1].duration)
        assertEquals(timeUnit - release, parsed[2].duration)
    }


    @Test
    fun velocityChange() {
        val notes = mutableListOf(mutableListOf("d", "d", "d", "d", "d", "d"))
        val changes = mutableListOf(
                ChangeEvent(0, ChangeEvent.VELOCITY, velocityLetters[0]),
                ChangeEvent(1, ChangeEvent.VELOCITY, velocityLetters[1]),
                ChangeEvent(2, ChangeEvent.VELOCITY, velocityLetters[2]),
                ChangeEvent(3, ChangeEvent.VELOCITY, velocityLetters[3]),
                ChangeEvent(4, ChangeEvent.VELOCITY, velocityLetters[4]),
                ChangeEvent(5, ChangeEvent.VELOCITY, velocityLetters[5]))
        val parser = NotesParser().apply {
            voiceIds = mutableListOf("S")
            playedVoices = mutableListOf(true)
            changeEvents = changes
        }
        val parsed = parser.parse(notes)
        assertEquals(velocityNumbers[0], parsed[0].velocity)
        assertEquals(velocityNumbers[1], parsed[1].velocity)
        assertEquals(velocityNumbers[2], parsed[2].velocity)
        assertEquals(velocityNumbers[3], parsed[3].velocity)
        assertEquals(velocityNumbers[4], parsed[4].velocity)
        assertEquals(velocityNumbers[5], parsed[5].velocity)
    }


    @Test
    fun repetitions() {
        val notes = mutableListOf(mutableListOf("d", "r", "m", "f", "s", "l", "t", "d1"))
        val changes = mutableListOf(
                ChangeEvent(2, ChangeEvent.SIGN, "$1"),
                ChangeEvent(4, ChangeEvent.SIGN, "$2"),
                ChangeEvent(3, ChangeEvent.MOD, "C#"),
                ChangeEvent(5, ChangeEvent.SIGN, "Fin"),
                ChangeEvent(1, ChangeEvent.DAL, "DC"),
                ChangeEvent(5, ChangeEvent.DAL, "D$1"),
                ChangeEvent(6, ChangeEvent.TEMPO, "60"),
                ChangeEvent(7, ChangeEvent.DAL, "D$2"))
        val parser = NotesParser().apply {
            voiceIds = mutableListOf("S")
            playedVoices = mutableListOf(true)
            changeEvents = changes
        }
        val parsed = parser.parse(notes)
        assertEquals(4 + 8 + 2 + 2, parsed.size)
        assertEquals(60, parsed[2].pitch)
        assertEquals(delay + 2 * timeUnit, parsed[2].tick)
        assertEquals(64, parsed[4].pitch)
        assertEquals(66, parsed[5].pitch)
        assertEquals(64, parsed[8].pitch)
        assertEquals(delay + 4 * timeUnit, parsed[4].tick)
        assertEquals(70, parsed.last().pitch)
        assertEquals(delay + (15 + 2) * timeUnit, parsed.last().tick)
    }

}
