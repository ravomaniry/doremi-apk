package mg.maniry.doremi.partition


import java.io.File
import java.io.IOException
import java.util.ArrayList

import leff.midi.MidiFile
import leff.midi.MidiTrack
import leff.midi.Tempo
import leff.midi.TimeSignature


fun createMidiFile(notes: MutableList<Note>, bpm: Int, outFile: File) {
    val tempoTrack = MidiTrack()
    val noteTracks = mutableListOf(MidiTrack())

    val ts = TimeSignature()
    ts.setTimeSignature(4, 4, TimeSignature.DEFAULT_METER, TimeSignature.DEFAULT_DIVISION)

    val t = Tempo()
    t.bpm = bpm.toFloat()

    tempoTrack.insertEvent(ts)
    tempoTrack.insertEvent(t)

    // Add notes
    notes.forEach {
        if (it.pitch > 0) {
            while (noteTracks.size <= it.channel)
                noteTracks.add(MidiTrack())

            noteTracks[it.channel].insertNote(it)
        }
    }

    //Create a MidiFile
    val tracks = ArrayList<MidiTrack>()
    tracks.add(tempoTrack)
    noteTracks.forEach { tracks.add(it) }

    val midi = MidiFile(MidiFile.DEFAULT_RESOLUTION, tracks)

    // Write into getFileFromName
    try {
        midi.writeToFile(outFile)
    } catch (e: IOException) {
        System.err.println(e)
    }
}

