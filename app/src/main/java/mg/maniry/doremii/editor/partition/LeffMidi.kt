package mg.maniry.doremii.editor.partition

import leff.midi.*
import java.io.File
import java.io.IOException
import java.util.ArrayList


data class CreateMidiParams(
    val notes: List<Note>,
    val tempo: Int,
    val outFile: File,
    val timbres: MutableList<VoiceTimbre>? = mutableListOf(),
)


fun createMidiFile(params: CreateMidiParams) {
    val noteTracks = mutableListOf(MidiTrack())
    val (notes, tempo, outFile, timbres) = params

    // Add notes
    notes.forEach {
        if (it.pitch > 0) {
            while (noteTracks.size <= it.channel) {
                noteTracks.add(MidiTrack())
            }

            noteTracks[it.channel].insertNote(it)
        }
    }

    //Create a MidiFile
    val tracks = ArrayList<MidiTrack>()
    val ts = TimeSignature().apply {
        setTimeSignature(4, 4, TimeSignature.DEFAULT_METER, TimeSignature.DEFAULT_DIVISION)
    }
    val tempoEvent = Tempo().apply {
        bpm = tempo.toFloat()
    }

    tracks.add(MidiTrack().apply {
        insertEvent(ts)
        insertEvent(tempoEvent)
    })

    noteTracks.forEachIndexed { channel, track ->
        val (bank, program) = timbres?.getOrNull(channel)?.midiBankAndProgram() ?: (0 to 0)
        if (bank != 0) {
            track.insertEvent(Controller(0, channel, 0, (bank shr 7) and 0x7f))
            track.insertEvent(Controller(0, channel, 32, bank and 0x7f))
        }
        track.insertEvent(ProgramChange(0, channel, program))
        tracks.add(track)
    }

    val midi = MidiFile(MidiFile.DEFAULT_RESOLUTION, tracks)

    // Write into getFileFromName
    try {
        midi.writeToFile(outFile)
    } catch (e: IOException) {
        System.err.println(e)
    }
}
