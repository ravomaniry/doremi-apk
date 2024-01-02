package mg.maniry.doremii.editor.partition

import leff.midi.*
import java.io.File
import java.io.IOException
import java.util.ArrayList


data class CreateMidiParams(
    val notes: List<Note>,
    val tempo: Int,
    val outFile: File,
    val instruments: MutableList<Int>? = mutableListOf(),
//    val voiceIds: MutableList<String>
)


fun createMidiFile(params: CreateMidiParams) {
    val noteTracks = mutableListOf(MidiTrack())
    val (notes, tempo, outFile, instruments) = params

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

    val programsIndexes = notes.mapIndexed { index, _ ->
        if (instruments != null && instruments.size > index) {
            instruments[index]
        } else {
            0
        }
    }

    noteTracks.forEachIndexed { v, track ->
        track.insertEvent(ProgramChange(1, v, InstrumentsList.list[programsIndexes[v]].program))
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
