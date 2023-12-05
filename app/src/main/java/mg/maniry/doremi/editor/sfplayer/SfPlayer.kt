package mg.maniry.doremi.editor.sfplayer

import android.content.Context
import cn.sherlock.com.sun.media.sound.SF2Soundbank
import cn.sherlock.com.sun.media.sound.SoftSynthesizer
import jp.kshoji.javax.sound.midi.Receiver
import jp.kshoji.javax.sound.midi.ShortMessage
import mg.maniry.doremi.editor.partition.Note


class SfPlayer(
    private val mainContext: Context
) {
    private var synth: SoftSynthesizer? = null
    private val receiver: Receiver? get() = synth?.receiver
    private val soundfontName = "SmallTimGM6mb.sf2"

    init {
        ensureInitialization()
    }

    fun play(notes: List<Note>, tempo: Int) {
        ensureInitialization()
        synth?.run {
            val t0 = microsecondPosition + 1_000_000
            for (note in notes) {
                val noteOnTst = t0 + tickToMicroseconds(note.tick, tempo)
                val noteOffTst = noteOnTst + tickToMicroseconds(note.duration, tempo)
                receiver.send(ShortMessage().apply {
                    setMessage(ShortMessage.NOTE_ON, 0, note.pitch, note.velocity)
                }, noteOnTst)
                receiver.send(ShortMessage().apply {
                    setMessage(ShortMessage.NOTE_OFF, 0, note.pitch, note.velocity)
                }, noteOffTst)
            }
        }
    }

    fun playSingleNote(pitch: Int) {
        ensureInitialization()
        val velocity = 100
        receiver?.send(ShortMessage().apply {
            setMessage(ShortMessage.NOTE_ON, 0, pitch, velocity)
        }, -1)
        val noteOffTimestamp = synth!!.microsecondPosition + 500_000
        receiver?.send(ShortMessage().apply {
            setMessage(ShortMessage.NOTE_OFF, 0, pitch, velocity)
        }, noteOffTimestamp)
    }

    private fun ensureInitialization() {
        if (synth == null) {
            val sf = SF2Soundbank(mainContext.assets.open("soundfonts/$soundfontName"))
            val synth = SoftSynthesizer()
            synth.open()
            synth.loadAllInstruments(sf)
            synth.channels[0].programChange(0)
            this.synth = synth
        }
    }

    private fun tickToMicroseconds(tick: Long, tempo: Int): Long {
        // 480L is the tick value of 1 bar
        return 1_000_000 * tick * 60 / (tempo * 480L)
    }

    fun stop() {
        synth?.close()
        synth = null
    }

    fun dispose() {
        synth?.close()
    }
}