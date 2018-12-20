package mg.maniry.doremi.partition

import leff.midi.ProgramChange

class InstrumentsList {
    companion object {
        class Instrument(val name: String, val program: Int)


        val list = listOf(
                Instrument("Piano Grand", ProgramChange.MidiProgram.ACOUSTIC_GRAND_PIANO.programNumber()),
                Instrument("Piano El.Grand", ProgramChange.MidiProgram.ELECTRIC_GRAND_PIANO.programNumber()),
                Instrument("Piano Bright", ProgramChange.MidiProgram.BRIGHT_ACOUSTIC_PIANO.programNumber()),
                Instrument("Piano El.1", ProgramChange.MidiProgram.ELECTRIC_PIANO_1.programNumber()),
                Instrument("Piano El.2", ProgramChange.MidiProgram.ELECTRIC_PIANO_2.programNumber()),
                Instrument("Piano Honkytonk", ProgramChange.MidiProgram.HONKYTONK_PIANO.programNumber()),
                Instrument("Organ Church", ProgramChange.MidiProgram.CHURCH_ORGAN.programNumber()),
                Instrument("Organ Perc.", ProgramChange.MidiProgram.PERCUSSIVE_ORGAN.programNumber()),
                Instrument("Organ Reed", ProgramChange.MidiProgram.REED_ORGAN.programNumber()),
                Instrument("Organ Drawbar", ProgramChange.MidiProgram.DRAWBAR_ORGAN.programNumber()),
                Instrument("Str Ens 1", ProgramChange.MidiProgram.STRING_ENSEMBLE_1.programNumber()),
                Instrument("Str Ens 2", ProgramChange.MidiProgram.STRING_ENSEMBLE_2.programNumber()),
                Instrument("Str Tremolo", ProgramChange.MidiProgram.TREMOLO_STRINGS.programNumber()),
                Instrument("Str Violin", ProgramChange.MidiProgram.VIOLIN.programNumber()),
                Instrument("Str Viola", ProgramChange.MidiProgram.VIOLA.programNumber()),
                Instrument("Str Cello", ProgramChange.MidiProgram.CELLO.programNumber()),
                Instrument("Str Contrabass", ProgramChange.MidiProgram.CONTRABASS.programNumber()),
                Instrument("Blown bottle", ProgramChange.MidiProgram.BLOWN_BOTTLE.programNumber()),
                Instrument("Clarinet", ProgramChange.MidiProgram.CLARINET.programNumber()),
                Instrument("Brass Section", ProgramChange.MidiProgram.BRASS_SECTION.programNumber()),
                Instrument("Sax Soprano", ProgramChange.MidiProgram.SOPRANO_SAX.programNumber()),
                Instrument("Sax Alto", ProgramChange.MidiProgram.ALTO_SAX.programNumber()),
                Instrument("Sax Tenor", ProgramChange.MidiProgram.TENOR_SAX.programNumber()),
                Instrument("Sax Baritone", ProgramChange.MidiProgram.BARITONE_SAX.programNumber()),
                Instrument("Trumpet", ProgramChange.MidiProgram.TRUMPET.programNumber()),
                Instrument("Trombone", ProgramChange.MidiProgram.TROMBONE.programNumber()),
                Instrument("French horn", ProgramChange.MidiProgram.FRENCH_HORN.programNumber()),
                Instrument("Basson", ProgramChange.MidiProgram.BASSOON.programNumber()),
                Instrument("Guitar Steel", ProgramChange.MidiProgram.ACOUSTIC_GUITAR_STEEL.programNumber()),
                Instrument("Guitar Nylon", ProgramChange.MidiProgram.ACOUSTIC_GUITAR_STEEL.programNumber()),
                Instrument("Harpsichord", ProgramChange.MidiProgram.HARPSICHORD.programNumber()),
                Instrument("Choir", ProgramChange.MidiProgram.PAD_4_CHOIR.programNumber()),
                Instrument("Bass Ac", ProgramChange.MidiProgram.ACOUSTIC_BASS.programNumber()),
                Instrument("Bass Finger", ProgramChange.MidiProgram.ELECTRIC_BASS_FINGER.programNumber()),
                Instrument("Bass Pick", ProgramChange.MidiProgram.ELECTRIC_BASS_PICK.programNumber())
        )
    }
}
