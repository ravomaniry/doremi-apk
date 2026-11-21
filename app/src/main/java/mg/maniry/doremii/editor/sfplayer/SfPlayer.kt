package mg.maniry.doremii.editor.sfplayer

import android.os.Handler
import android.os.Looper
import android.util.Log
import mg.maniry.doremii.editor.partition.Note
import mg.maniry.doremii.soundfont.DiscoveredPreset

data class VoicePatch(
    val path: String,
    val bank: Int,
    val program: Int,
)

class SfPlayer {
    var onPresetsDiscovered: ((path: String, presets: List<DiscoveredPreset>) -> Unit)? = null
    private var completionListener: (() -> Unit)? = null
    private var engine = 0L
    private val discoveredPaths = mutableSetOf<String>()

    @Volatile
    private var sessionId = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    fun setOnCompletionListener(listener: () -> Unit) {
        completionListener = listener
    }

    fun play(notes: List<Note>, tempo: Int, voices: List<VoicePatch>): Boolean {
        val discovered = mutableListOf<Pair<String, List<DiscoveredPreset>>>()
        val delayMs: Long
        val sessionToStop: Int
        synchronized(this) {
            if (!ensureEngine() || !prepareVoices(voices, discovered)) {
                return false
            }
            sessionId++
            sessionToStop = sessionId
            val pitches = IntArray(notes.size)
            val velocities = IntArray(notes.size)
            val channels = IntArray(notes.size)
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
                channels[i] = note.channel.coerceIn(0, MAX_CHANNEL)
                if (offMs[i] > latestOffMs) {
                    latestOffMs = offMs[i]
                }
            }
            nativeReplaceScore(engine, pitches, velocities, channels, onMs, offMs)
            delayMs = latestOffMs + PREROLL_MS
        }
        publish(discovered)
        scheduleCompletion(sessionToStop, delayMs)
        return true
    }

    fun playSingleNote(pitch: Int, voice: VoicePatch, channel: Int) {
        val discovered = mutableListOf<Pair<String, List<DiscoveredPreset>>>()
        synchronized(this) {
            if (!ensureEngine()) return
            val id = load(voice.path, discovered)
            if (id < 0) return
            val midiChannel = channel.coerceIn(0, MAX_CHANNEL)
            if (!nativeSelectInstrument(engine, midiChannel, id, voice.bank, voice.program)) {
                nativeSelectInstrument(engine, midiChannel, id, 0, 0)
            }
            nativeAddNote(engine, midiChannel, pitch, SINGLE_NOTE_VELOCITY, SINGLE_NOTE_DURATION_MS)
        }
        publish(discovered)
    }

    fun stopPlaying() {
        synchronized(this) {
            sessionId++
            if (engine != 0L) {
                nativeStop(engine)
            }
        }
    }

    fun seekTo(positionMs: Long): Boolean {
        val delayMs: Long
        val sessionToStop: Int
        synchronized(this) {
            if (engine == 0L || !nativeSeek(engine, positionMs)) {
                return false
            }
            sessionId++
            sessionToStop = sessionId
            val remaining = (nativeDurationMs(engine) - nativePositionMs(engine)).coerceAtLeast(0L)
            delayMs = remaining + PREROLL_MS
        }
        scheduleCompletion(sessionToStop, delayMs)
        return true
    }

    fun positionMs(): Long {
        synchronized(this) {
            if (engine == 0L) return 0L
            return nativePositionMs(engine)
        }
    }

    fun durationMs(): Long {
        synchronized(this) {
            if (engine == 0L) return 0L
            return nativeDurationMs(engine)
        }
    }

    fun dispose() {
        val handle: Long
        synchronized(this) {
            sessionId++
            handle = engine
            engine = 0L
            discoveredPaths.clear()
        }
        if (handle != 0L) {
            nativeDestroy(handle)
        }
    }

    private fun publish(discovered: List<Pair<String, List<DiscoveredPreset>>>) {
        val listener = onPresetsDiscovered ?: return
        discovered.forEach { (path, presets) -> listener(path, presets) }
    }

    private fun prepareVoices(
        voices: List<VoicePatch>,
        discovered: MutableList<Pair<String, List<DiscoveredPreset>>>,
    ): Boolean {
        if (voices.isEmpty()) return false
        val ids = ArrayList<Int>(voices.size)
        voices.forEachIndexed { channel, voice ->
            val id = load(voice.path, discovered)
            if (id < 0) {
                Log.e(TAG, "Unable to load ${voice.path}")
                return false
            }
            val midiChannel = channel.coerceAtMost(MAX_CHANNEL)
            if (!nativeSelectInstrument(engine, midiChannel, id, voice.bank, voice.program)) {
                nativeSelectInstrument(engine, midiChannel, id, 0, 0)
            }
            ids += id
        }
        nativeReleaseUnused(engine, ids.distinct().toIntArray())
        return true
    }

    private fun load(
        path: String,
        discovered: MutableList<Pair<String, List<DiscoveredPreset>>>,
    ): Int {
        if (engine == 0L || path.isEmpty()) return -1
        val id = nativeLoadSoundFont(engine, path)
        if (id < 0 || path in discoveredPaths) return id
        val presets = nativePresets(engine, id)?.mapNotNull(DiscoveredPreset::parseRow).orEmpty()
        if (presets.isNotEmpty()) {
            discoveredPaths += path
            discovered += path to presets
        }
        return id
    }

    private fun ensureEngine(): Boolean {
        if (engine == 0L) {
            engine = nativeCreate()
        }
        return engine != 0L
    }

    private fun scheduleCompletion(sessionToStop: Int, delayMs: Long) {
        mainHandler.postDelayed({
            if (sessionToStop == sessionId) {
                completionListener?.invoke()
            }
        }, delayMs.coerceAtLeast(0L))
    }

    private fun tickToMicroseconds(tick: Long, tempo: Int): Long {
        return 1_000_000 * tick * 60 / (tempo * 480L)
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeLoadSoundFont(handle: Long, path: String): Int
    private external fun nativePresets(handle: Long, sfontId: Int): Array<String>?
    private external fun nativeSelectInstrument(
        handle: Long,
        channel: Int,
        sfontId: Int,
        bank: Int,
        program: Int,
    ): Boolean

    private external fun nativeReleaseUnused(handle: Long, keepIds: IntArray)
    private external fun nativeReplaceScore(
        handle: Long,
        pitches: IntArray,
        velocities: IntArray,
        channels: IntArray,
        onMs: LongArray,
        offMs: LongArray,
    )

    private external fun nativeAddNote(
        handle: Long,
        channel: Int,
        pitch: Int,
        velocity: Int,
        durationMs: Long,
    )

    private external fun nativeStop(handle: Long)
    private external fun nativeSeek(handle: Long, positionMs: Long): Boolean
    private external fun nativePositionMs(handle: Long): Long
    private external fun nativeDurationMs(handle: Long): Long

    companion object {
        private const val TAG = "SfPlayer"
        private const val PREROLL_MS = 100L
        private const val SINGLE_NOTE_VELOCITY = 100
        private const val SINGLE_NOTE_DURATION_MS = 500L
        private const val MAX_CHANNEL = 15

        init {
            System.loadLibrary("doremi_synth")
        }

        fun probe(path: String): List<DiscoveredPreset>? {
            val rows = nativeProbe(path) ?: return null
            return rows.mapNotNull(DiscoveredPreset::parseRow).takeIf { it.isNotEmpty() }
        }

        @JvmStatic
        private external fun nativeProbe(path: String): Array<String>?
    }
}
