package mg.maniry.doremii.omr

import android.content.Context
import android.os.Handler
import android.os.Looper
import mg.maniry.doremii.DoremiApp
import mg.maniry.doremii.editor.partition.NotesParser
import mg.maniry.doremii.editor.partition.VoiceTimbre
import mg.maniry.doremii.editor.sfplayer.SfPlayer
import java.util.concurrent.Executors

class ScanPreviewPlayer(
    context: Context,
    private val onPlaying: (Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val sfPlayer = SfPlayer()
    private val parser = NotesParser()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "scan-preview").apply { isDaemon = true }
    }
    private var generation = 0

    init {
        sfPlayer.setOnCompletionListener {
            main.post { onPlaying(false) }
        }
    }

    fun play(voices: List<ParsedVoice>, signature: Int, key: Int, tempo: Int) {
        val sig = signature.coerceIn(2, 6)
        val speed = tempo.coerceIn(31, 399)
        val limit = sig * 4
        val notes = voices.map { it.cells.take(limit).toMutableList() }.toMutableList()
        if (notes.isEmpty() || notes.all { row -> row.all { it.isBlank() } }) {
            onPlaying(false)
            return
        }
        val ids = voices.map { it.id.ifBlank { "S" } }.toMutableList()
        val ticket = ++generation
        onPlaying(true)
        worker.execute {
            val started = try {
                if (ticket != generation) {
                    false
                } else {
                    val repository = DoremiApp.from(appContext).soundFonts
                    val font = repository.resolveSongFont(VoiceTimbre.PIANO_ID)
                    val patches = List(notes.size) {
                        repository.assignmentOnFont(font, VoiceTimbre.piano())
                    }
                    parser.apply {
                        this.key = key.coerceIn(0, 11)
                        this.tempo = speed
                        this.signature = sig
                        swing = false
                        changeEvents = mutableListOf()
                        start = 0
                        enableVelocity = false
                        loopsNumber = 0
                        voiceIds = ids
                        playedVoices = List(notes.size) { true }
                    }
                    val parsed = parser.parse(notes)
                    when {
                        parsed.isEmpty() || ticket != generation -> false
                        else -> {
                            val playing = sfPlayer.play(parsed, speed, patches)
                            if (ticket != generation) {
                                sfPlayer.stopPlaying()
                                false
                            } else {
                                playing
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                false
            }
            if (ticket == generation) main.post { onPlaying(started) }
        }
    }

    fun stop() {
        generation++
        onPlaying(false)
        worker.execute { sfPlayer.stopPlaying() }
    }

    fun release() {
        generation++
        worker.execute {
            sfPlayer.dispose()
            worker.shutdown()
        }
    }
}
