package mg.maniry.doremii.editor.partition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTimelineTest {
    private val signature = 4

    @Test
    fun seekFollowsPlaybackOrderAcrossAJump() {
        val visits = (listOf(0, 1, 2, 3, 4, 5, 6, 7) + listOf(0, 1, 2, 3, 4, 5, 6, 7))
            .mapIndexed { index, cell -> ScoreVisit(cell, index * 480L, 480) }

        val jumped = visits[8].startTick
        assertEquals(0, measureAt(visits, jumped, signature))
        assertEquals(visits[4].startTick, previousMeasureTick(visits, jumped, signature))
        assertEquals(visits[12].startTick, nextMeasureTick(visits, jumped, signature))

        val insideFirstRepeat = visits[6].startTick + 10
        assertEquals(visits[0].startTick, previousMeasureTick(visits, insideFirstRepeat, signature))
        assertEquals(visits[8].startTick, nextMeasureTick(visits, insideFirstRepeat, signature))
    }


    @Test
    fun firstAndLastMeasuresStayInsideTheTimeline() {
        val visits = (0..7).map { ScoreVisit(it, it * 480L, 480) }

        assertEquals(0L, previousMeasureTick(visits, 100, signature))
        assertEquals(visits.last().startTick, nextMeasureTick(visits, visits.last().startTick, signature))
        assertEquals(visits[0].startTick, previousMeasureTick(visits, visits[6].startTick, signature))
        assertEquals(visits[4].startTick, nextMeasureTick(visits, visits[2].startTick, signature))
    }


    @Test
    fun pianoRangeUsesWholeOctaves() {
        assertEquals(60..71, pianoPitchRange(listOf(note(60), note(64))))
        assertEquals(48..71, pianoPitchRange(listOf(note(59), note(60))))
        assertEquals(72..83, pianoPitchRange(listOf(note(72))))
        assertEquals(60..71, pianoPitchRange(listOf(note(0), note(-1))))
    }


    @Test
    fun keysStayLitUntilNoteOff() {
        val notes = listOf(Note(0, 60, 100, 120, 475), Note(1, 60, 90, 120, 475))
        val on = tickToPositionMs(120, 120)
        val off = tickToPositionMs(120 + 475, 120)
        assertEquals(mapOf(60 to listOf(0, 1)), soundingVoices(notes, on, 120))
        assertTrue(soundingVoices(notes, off - 1, 120).isNotEmpty())
        assertTrue(soundingVoices(notes, off, 120).isEmpty())
    }


    private fun note(pitch: Int) = Note(0, pitch, 100, 0, 10)
}
