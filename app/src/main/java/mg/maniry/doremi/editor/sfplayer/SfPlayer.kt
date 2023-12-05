package mg.maniry.doremi.editor.sfplayer

import android.content.Context
import cn.sherlock.com.sun.media.sound.SF2Soundbank
import cn.sherlock.com.sun.media.sound.SoftSynthesizer
import jp.kshoji.javax.sound.midi.Receiver
import jp.kshoji.javax.sound.midi.ShortMessage


class SfPlayer(
    mainContext: Context
) {
    private var synth: SoftSynthesizer? = null
    private var receiver: Receiver? = null

    init {
        val sf = SF2Soundbank(mainContext.assets.open("soundfonts/SmallTimGM6mb.sf2"))
        val synth = SoftSynthesizer()
        synth.open()
        synth.loadAllInstruments(sf)
        synth.channels[0].programChange(0)
        synth.channels[1].programChange(1)
        receiver = synth.receiver
        this.synth = synth
    }

    fun playSingleNote(pitch: Int) {
        val velocity = 100
        receiver?.send(ShortMessage().apply {
            setMessage(ShortMessage.NOTE_ON, 0, pitch, velocity)
        }, -1)
        val noteOffTimestamp = synth!!.microsecondPosition + 500_000
        receiver?.send(ShortMessage().apply {
            setMessage(ShortMessage.NOTE_OFF, 0, pitch, velocity)
        }, noteOffTimestamp)
    }

    fun dispose() {
        synth?.close()
    }
}