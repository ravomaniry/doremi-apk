package mg.maniry.doremi.editor.sfplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import cn.sherlock.com.sun.media.sound.SF2Soundbank
import cn.sherlock.com.sun.media.sound.SoftSynthesizer
import jp.kshoji.javax.sound.midi.Receiver
import jp.kshoji.javax.sound.midi.ShortMessage
import mg.maniry.doremi.editor.partition.Note
import org.jetbrains.anko.doAsync


class SfPlayer(
    private val mainContext: Context
) {
    private var completionListener: (() -> Unit)? = null
    private var synth: SoftSynthesizer? = null
    private val receiver: Receiver? get() = synth?.receiver
    private var instrumentName = "Piano"
    private var sessionId = 0

    private data class InstrumentConfig(val assetPath: String, val program: Int)

    fun setOnCompletionListener(listener: () -> Unit) {
        completionListener = listener
    }

    fun prepare(instrument: String) {
        ensureInitialization(instrument)
    }

    fun play(notes: List<Note>, tempo: Int, instrument: String) {
        ensureInitialization(instrument)
        var timeout = 0L
        synth?.run {
            val t0 = microsecondPosition + 1_000_000
            for (note in notes) {
                val relativeNoteOnMs = tickToMicroseconds(note.tick, tempo)
                val durationInMs = tickToMicroseconds(note.duration, tempo)
                val noteOnTst = t0 + relativeNoteOnMs
                val noteOffTst = noteOnTst + durationInMs
                timeout = (relativeNoteOnMs + durationInMs) / 1000
                receiver.send(ShortMessage().apply {
                    setMessage(ShortMessage.NOTE_ON, 0, note.pitch, note.velocity)
                }, noteOnTst)
                receiver.send(ShortMessage().apply {
                    setMessage(ShortMessage.NOTE_OFF, 0, note.pitch, note.velocity)
                }, noteOffTst)
            }
        }
        scheduleCompletionHandlerCall(timeout)
    }

    fun playSingleNote(pitch: Int) {
        ensureInitialization(instrumentName)
        val velocity = 100
        receiver?.send(ShortMessage().apply {
            setMessage(ShortMessage.NOTE_ON, 0, pitch, velocity)
        }, -1)
        val noteOffTimestamp = synth!!.microsecondPosition + 500_000
        receiver?.send(ShortMessage().apply {
            setMessage(ShortMessage.NOTE_OFF, 0, pitch, velocity)
        }, noteOffTimestamp)
    }

    private fun scheduleCompletionHandlerCall(timeout: Long) {
        sessionId++
        val sessionToStop = sessionId
        Handler(Looper.getMainLooper()).postDelayed(
            {
                if (sessionToStop == sessionId) {
                    completionListener?.invoke()
                    println("Completion handler called")
                } else {
                    println("Outdated session so we don't stop the player")
                }
            }, timeout
        )
    }

    private fun ensureInitialization(instrument: String) {
        if (instrument !== this.instrumentName) {
            instrumentName = instrument
            dispose()
        }
        if (synth == null) {
            val instrumentConfig = parseInstrumentName()
            val sf = SF2Soundbank(mainContext.assets.open(instrumentConfig.assetPath))
            val synth = SoftSynthesizer()
            synth.open()
            synth.loadAllInstruments(sf)
            synth.channels[0].programChange(instrumentConfig.program)
            this.synth = synth
        }
    }

    private fun parseInstrumentName(): InstrumentConfig {
        val colonIndex = instrumentName.indexOf(':')
        val fileName =
            if (colonIndex > 0) instrumentName.subSequence(0, colonIndex) else instrumentName
        return InstrumentConfig(
            "soundfonts/$fileName.sf2",
            if (colonIndex > 0) instrumentName.substring(colonIndex + 1).toInt() else 0
        )
    }

    private fun tickToMicroseconds(tick: Long, tempo: Int): Long {
        // 480L is the tick value of 1 bar
        return 1_000_000 * tick * 60 / (tempo * 480L)
    }

    fun stopPlaying() {
        dispose()
        doAsync {
            ensureInitialization(instrumentName)
        }
    }

    fun dispose() {
        synth?.close()
        synth = null
    }
}