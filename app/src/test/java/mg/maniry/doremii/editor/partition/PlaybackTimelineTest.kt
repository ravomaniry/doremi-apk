package mg.maniry.doremii.editor.partition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(0, cellAt(visits, jumped))
        assertEquals(visits[4].startTick, previousMeasureTick(visits, jumped, signature))
        assertEquals(visits[12].startTick, nextMeasureTick(visits, jumped, signature))

        val insideFirstRepeat = visits[6].startTick + 10
        assertEquals(6, cellAt(visits, insideFirstRepeat))
        assertEquals(visits[0].startTick, previousMeasureTick(visits, insideFirstRepeat, signature))
        assertEquals(visits[8].startTick, nextMeasureTick(visits, insideFirstRepeat, signature))
    }


    @Test
    fun cellAtPositionChangesOnTheBeat() {
        val visits = (0..7).map { ScoreVisit(it, it * 480L, 480) }
        val tempo = 120
        val secondBeat = tickToPositionMs(480, tempo)
        val thirdBeat = tickToPositionMs(960, tempo)

        assertNull(cellAt(emptyList(), 0))
        assertNull(cellAtPosition(emptyList(), 0, tempo))
        assertEquals(0, cellAt(visits, 0))
        assertEquals(0, cellAtPosition(visits, 0, tempo))
        assertEquals(1, cellAtPosition(visits, secondBeat, tempo))
        assertEquals(1, cellAtPosition(visits, thirdBeat - 1, tempo))
        assertEquals(2, cellAtPosition(visits, thirdBeat, tempo))
        val nextMeasure = tickToPositionMs(4 * 480L, tempo)
        assertEquals(4, cellAtPosition(visits, nextMeasure, tempo))
        assertEquals(1, cellAtPosition(visits, nextMeasure, tempo)!! / signature)
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
    fun playheadRestsOnTheBeatThenSnapsOntoTheNextColumn() {
        val tempo = 120
        val beat = tickToPositionMs(480, tempo)
        val visits = (0..7).map { ScoreVisit(it, it * 480L, 480) }

        val opening = playheadSpan(visits, beat / 2, tempo, signature)
        assertEquals(0, opening!!.cell)
        assertNull(opening.nextCell)
        assertEquals(0f, opening.fraction, 0.001f)

        // Ease-out: 60ms before the next column the marker is already most of the way across.
        val midSnap = playheadSpan(visits, beat - 60, tempo, signature)
        assertEquals(0, midSnap!!.cell)
        assertEquals(1, midSnap.nextCell)
        assertEquals(0.75f, midSnap.fraction, 0.02f)

        val onTheBeat = playheadSpan(visits, beat, tempo, signature)
        assertEquals(1, onTheBeat!!.cell)
        assertNull(onTheBeat.nextCell)
        assertEquals(0f, onTheBeat.fraction, 0.001f)

        val landed = playheadSpan(visits, beat + beat / 2, tempo, signature)
        assertEquals(1, landed!!.cell)
        assertNull(landed.nextCell)
        assertEquals(0f, landed.fraction, 0.001f)

        val beforeBar = playheadSpan(visits, beat * 4 - 60, tempo, signature)
        assertEquals(3, beforeBar!!.cell)
        assertNull(beforeBar.nextCell)

        val nextBar = playheadSpan(visits, beat * 4, tempo, signature)
        assertEquals(4, nextBar!!.cell)
        assertNull(nextBar.nextCell)

        val repeated = (listOf(0, 1, 2, 3) + listOf(0, 1, 2, 3))
            .mapIndexed { index, cell -> ScoreVisit(cell, index * 480L, 480) }
        val beforeRepeat = playheadSpan(repeated, beat * 4 - 60, tempo, signature)
        assertEquals(3, beforeRepeat!!.cell)
        assertNull(beforeRepeat.nextCell)
        val onRepeat = playheadSpan(repeated, beat * 4, tempo, signature)
        assertEquals(0, onRepeat!!.cell)
        assertNull(onRepeat.nextCell)

        val skipped = listOf(0, 2).mapIndexed { index, cell -> ScoreVisit(cell, index * 480L, 480) }
        val beforeSkip = playheadSpan(skipped, beat - 60, tempo, signature)
        assertEquals(0, beforeSkip!!.cell)
        assertNull(beforeSkip.nextCell)
        val notTheNextColumn = playheadSpan(skipped, beat, tempo, signature)
        assertEquals(2, notTheNextColumn!!.cell)
        assertNull(notTheNextColumn.nextCell)

        assertNull(playheadSpan(emptyList(), beat / 2, tempo, signature))
    }

    @Test
    fun playheadGlideUsesTheWholeBeatWhenItIsShorterThanTheSnap() {
        val tempo = 120
        val beat = tickToPositionMs(48, tempo)
        val visits = (0..3).map { ScoreVisit(it, it * 48L, 48) }

        val mid = playheadSpan(visits, beat / 2, tempo, signature)
        assertEquals(0, mid!!.cell)
        assertEquals(1, mid.nextCell)
        assertEquals(0.75f, mid.fraction, 0.02f)

        val landed = playheadSpan(visits, beat, tempo, signature)
        assertEquals(1, landed!!.cell)
        assertNull(landed.nextCell)
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
