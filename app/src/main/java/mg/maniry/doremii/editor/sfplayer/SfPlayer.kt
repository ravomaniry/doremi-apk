package mg.maniry.doremii.editor.sfplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import mg.maniry.doremii.editor.partition.Note
import java.io.File

class SfPlayer(
    private val mainContext: Context
) {
    private var completionListener: (() -> Unit)? = null
    private var instrumentName = "Piano"
    private var loadedAssetPath: String? = null
    private var engine = 0L

    @Volatile
    private var sessionId = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    private data class InstrumentConfig(val assetPath: String, val program: Int)

    fun setOnCompletionListener(listener: () -> Unit) {
        completionListener = listener
    }

    fun prepare(instrument: String) {
        ensureReady(instrument)
    }

    fun play(notes: List<Note>, tempo: Int, instrument: String) {
        val delayMs: Long
        val sessionToStop: Int
        synchronized(this) {
            if (!ensureReady(instrument)) {
                return
            }
            sessionId++
            sessionToStop = sessionId
            val pitches = IntArray(notes.size)
            val velocities = IntArray(notes.size)
            val onMs = LongArray(notes.size)
            val offMs = LongArray(notes.size)
            var latestOffMs = 0L
            for (i in notes.indices) {
                val note = notes[i]
                val noteOnUs = tickToMicroseconds(note.tick, tempo)
                val noteOffUs = noteOnUs + tickToMicroseconds(note.duration, tempo)
                onMs[i] = noteOnUs / 1000
                offMs[i] = noteOffUs / 1000
                pitches[i] = note.pitch
                velocities[i] = note.velocity
                if (offMs[i] > latestOffMs) {
                    latestOffMs = offMs[i]
                }
            }
            nativeReplaceScore(engine, pitches, velocities, onMs, offMs)
            delayMs = latestOffMs + PREROLL_MS
        }
        scheduleCompletion(sessionToStop, delayMs)
    }

    fun playSingleNote(pitch: Int) {
        synchronized(this) {
            if (!ensureReady(instrumentName)) {
                return
            }
            nativeAddNote(engine, pitch, SINGLE_NOTE_VELOCITY, SINGLE_NOTE_DURATION_MS)
        }
    }

    fun stopPlaying() {
        synchronized(this) {
            sessionId++
            if (engine != 0L) {
                nativeStop(engine)
            }
        }
    }

    fun dispose() {
        val handle: Long
        synchronized(this) {
            sessionId++
            handle = engine
            engine = 0L
            loadedAssetPath = null
        }
        if (handle != 0L) {
            nativeDestroy(handle)
        }
    }

    private fun scheduleCompletion(sessionToStop: Int, delayMs: Long) {
        mainHandler.postDelayed({
            if (sessionToStop == sessionId) {
                completionListener?.invoke()
            }
        }, delayMs.coerceAtLeast(0L))
    }

    private fun ensureReady(instrument: String): Boolean {
        synchronized(this) {
            if (engine == 0L) {
                engine = nativeCreate()
            }
            if (engine == 0L) {
                return false
            }
            val config = parseInstrumentName(instrument)
            if (loadedAssetPath != config.assetPath) {
                val file = cachedSoundFont(config.assetPath)
                if (!nativeLoadSoundFont(engine, file.absolutePath)) {
                    Log.e(TAG, "Unable to load ${config.assetPath}")
                    return false
                }
                loadedAssetPath = config.assetPath
            }
            // The file is unchanged for Strings:0 versus Strings:1, but the preset still changes.
            nativeProgramChange(engine, config.program)
            instrumentName = instrument
            return true
        }
    }

    private fun cachedSoundFont(assetPath: String): File {
        val file = File(mainContext.cacheDir, assetPath)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            mainContext.assets.open(assetPath).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file
    }

    private fun parseInstrumentName(instrument: String): InstrumentConfig {
        val colonIndex = instrument.indexOf(':')
        val fileName = if (colonIndex > 0) instrument.substring(0, colonIndex) else instrument
        val program = if (colonIndex > 0) instrument.substring(colonIndex + 1).toInt() else 0
        return InstrumentConfig("soundfonts/$fileName.sf2", program)
    }

    private fun tickToMicroseconds(tick: Long, tempo: Int): Long {
        // 480 ticks is one beat.
        return 1_000_000 * tick * 60 / (tempo * 480L)
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeLoadSoundFont(handle: Long, path: String): Boolean
    private external fun nativeProgramChange(handle: Long, program: Int)
    private external fun nativeReplaceScore(
        handle: Long,
        pitches: IntArray,
        velocities: IntArray,
        onMs: LongArray,
        offMs: LongArray,
    )

    private external fun nativeAddNote(handle: Long, pitch: Int, velocity: Int, durationMs: Long)
    private external fun nativeStop(handle: Long)

    companion object {
        private const val TAG = "SfPlayer"
        private const val PREROLL_MS = 100L
        private const val SINGLE_NOTE_VELOCITY = 100
        private const val SINGLE_NOTE_DURATION_MS = 500L

        init {
            System.loadLibrary("doremi_synth")
        }
    }
}
