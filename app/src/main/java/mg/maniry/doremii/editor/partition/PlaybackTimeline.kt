package mg.maniry.doremii.editor.partition

import kotlin.math.max
import kotlin.math.min

/** One expanded cell, in playback order. Repeats visit the same score cell again. */
data class ScoreVisit(
    val sourceCell: Int,
    val startTick: Long,
    val durationTicks: Long,
)

data class PlaybackClock(
    val notes: List<Note>,
    val visits: List<ScoreVisit>,
    val tempo: Int,
    val signature: Int,
)

private const val TICKS_PER_BEAT = 480L

/** Same truncation as [mg.maniry.doremii.editor.sfplayer.SfPlayer] tick conversion. */
fun tickToPositionMs(tick: Long, tempo: Int): Long {
    if (tempo <= 0 || tick <= 0) return 0
    val microseconds = 1_000_000L * tick * 60 / (tempo * TICKS_PER_BEAT)
    return microseconds / 1000
}

fun positionMsToTick(positionMs: Long, tempo: Int): Long {
    if (tempo <= 0 || positionMs <= 0) return 0
    return positionMs * tempo * TICKS_PER_BEAT / 60_000
}

/** C of the lowest sounding octave through B of the highest. One middle octave when nothing sounds. */
fun pianoPitchRange(notes: Iterable<Note>): IntRange {
    var low: Int? = null
    var high: Int? = null
    for (note in notes) {
        if (note.pitch <= 0) continue
        low = if (low == null) note.pitch else min(low, note.pitch)
        high = if (high == null) note.pitch else max(high, note.pitch)
    }
    if (low == null || high == null) return 60..71
    return octaveFloor(low) * 12..octaveFloor(high) * 12 + 11
}

private fun octaveFloor(pitch: Int): Int {
    val quotient = pitch / 12
    return if (pitch < 0 && pitch % 12 != 0) quotient - 1 else quotient
}

fun visitIndexAt(visits: List<ScoreVisit>, tick: Long): Int {
    if (visits.isEmpty()) return -1
    var found = 0
    var low = 0
    var high = visits.size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (visits[mid].startTick <= tick) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found
}

fun visitIndexAtPosition(visits: List<ScoreVisit>, positionMs: Long, tempo: Int): Int {
    if (visits.isEmpty()) return -1
    var found = 0
    var low = 0
    var high = visits.size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (tickToPositionMs(visits[mid].startTick, tempo) <= positionMs) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found
}

fun measureAt(visits: List<ScoreVisit>, tick: Long, signature: Int): Int? {
    if (signature <= 0) return null
    val index = visitIndexAt(visits, tick)
    if (index < 0) return null
    return visits[index].sourceCell / signature
}

fun measureAtPosition(visits: List<ScoreVisit>, positionMs: Long, tempo: Int, signature: Int): Int? {
    if (signature <= 0) return null
    val index = visitIndexAtPosition(visits, positionMs, tempo)
    if (index < 0) return null
    return visits[index].sourceCell / signature
}

/** Start tick of the next bar in playback order. Stays put on the last bar. */
fun nextMeasureTick(visits: List<ScoreVisit>, tick: Long, signature: Int): Long {
    if (visits.isEmpty() || signature <= 0) return 0
    val index = visitIndexAt(visits, tick)
    val measure = visits[index].sourceCell / signature
    var cursor = index + 1
    while (cursor < visits.size && visits[cursor].sourceCell / signature == measure) {
        cursor++
    }
    return if (cursor < visits.size) visits[cursor].startTick else tick
}

/** Start tick of the previous bar in playback order. The first bar rewinds to its start. */
fun previousMeasureTick(visits: List<ScoreVisit>, tick: Long, signature: Int): Long {
    if (visits.isEmpty() || signature <= 0) return 0
    val index = visitIndexAt(visits, tick)
    val measure = visits[index].sourceCell / signature
    var cursor = index
    while (cursor > 0 && visits[cursor].sourceCell / signature == measure) {
        cursor--
    }
    if (visits[cursor].sourceCell / signature == measure) {
        return visits[cursor].startTick
    }
    val previous = visits[cursor].sourceCell / signature
    while (cursor > 0 && visits[cursor - 1].sourceCell / signature == previous) {
        cursor--
    }
    return visits[cursor].startTick
}

fun nextMeasurePositionMs(clock: PlaybackClock, positionMs: Long): Long {
    val index = visitIndexAtPosition(clock.visits, positionMs, clock.tempo)
    if (index < 0 || clock.signature <= 0) return 0
    val measure = clock.visits[index].sourceCell / clock.signature
    var cursor = index + 1
    while (cursor < clock.visits.size && clock.visits[cursor].sourceCell / clock.signature == measure) {
        cursor++
    }
    return if (cursor < clock.visits.size) {
        tickToPositionMs(clock.visits[cursor].startTick, clock.tempo)
    } else {
        positionMs
    }
}

fun previousMeasurePositionMs(clock: PlaybackClock, positionMs: Long): Long {
    val index = visitIndexAtPosition(clock.visits, positionMs, clock.tempo)
    if (index < 0 || clock.signature <= 0) return 0
    val tick = clock.visits[index].startTick
    return tickToPositionMs(previousMeasureTick(clock.visits, tick, clock.signature), clock.tempo)
}

fun soundingVoices(notes: List<Note>, positionMs: Long, tempo: Int): Map<Int, List<Int>> {
    if (tempo <= 0) return emptyMap()
    val byPitch = linkedMapOf<Int, MutableList<Int>>()
    for (note in notes) {
        if (note.pitch <= 0) continue
        val on = tickToPositionMs(note.tick, tempo)
        val off = tickToPositionMs(note.tick + note.duration, tempo)
        if (positionMs < on || positionMs >= off) continue
        val voices = byPitch.getOrPut(note.pitch) { mutableListOf() }
        if (note.channel !in voices) voices += note.channel
    }
    return byPitch
}
