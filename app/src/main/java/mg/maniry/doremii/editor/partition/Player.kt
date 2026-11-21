package mg.maniry.doremii.editor.partition

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import mg.maniry.doremii.DoremiApp
import mg.maniry.doremii.editor.sfplayer.SfPlayer
import mg.maniry.doremii.editor.sfplayer.VoicePatch
import mg.maniry.doremii.editor.viewModels.EditorViewModel
import java.util.concurrent.Executors

data class Player constructor(
    private val mainContext: Context,
    private val editorViewModel: EditorViewModel,
) {
    var isActive = true
    private val sfPlayer = SfPlayer()
    val mutedVoices = MutableLiveData(mutableListOf<Boolean>())
    val soloedVoices = MutableLiveData(mutableListOf<Boolean>())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sf-player").apply { isDaemon = true }
    }

    @Volatile
    private var generation = 0
    private var clock: PlaybackClock? = null
    private var paused = false
    private var loadEpoch = 0
    private var pendingLoads = 0
    private val showLoading = Runnable {
        if (pendingLoads > 0) {
            editorViewModel.playbackLoading.value = true
        }
    }

    init {
        sfPlayer.setOnCompletionListener {
            updateState(false)
            leaveSession()
        }
        sfPlayer.onPresetsDiscovered = { path, presets ->
            DoremiApp.from(mainContext).soundFonts.rememberPresets(path, presets)
        }
    }

    fun playSingleNote(pitch: Int) {
        val voice = editorViewModel.cursorPos.voice
        val flags = audibleFlags()
        if (voice !in flags.indices || !flags[voice]) return
        val patch = songPatches().getOrNull(voice) ?: return
        val ticket = generation
        val epoch = armLoading()
        worker.execute {
            if (ticket == generation) {
                sfPlayer.playSingleNote(pitch, patch, voice)
                if (ticket != generation) {
                    sfPlayer.stopPlaying()
                }
            }
            mainHandler.post { finishLoading(epoch) }
        }
    }


    fun playbackClock(): PlaybackClock? = clock

    fun isPlaybackPaused(): Boolean = paused

    fun play() {
        val notes = editorViewModel.buildNotes(audibleFlags())
        val tempo = editorViewModel.partitionData.tempo
        val signature = editorViewModel.partitionData.signature.value ?: 4
        val clock = PlaybackClock(notes, editorViewModel.playbackVisits(), tempo, signature)
        val patches = songPatches()
        val ticket = ++generation
        val epoch = armLoading()
        enterSession(clock)
        worker.execute {
            var started = false
            if (ticket == generation) {
                started = sfPlayer.play(notes, tempo, patches)
                if (ticket != generation) {
                    sfPlayer.stopPlaying()
                    started = false
                }
            }
            val playing = started
            mainHandler.post {
                finishLoading(epoch)
                if (ticket != generation) return@post
                if (playing) updateState(true) else {
                    updateState(false)
                    leaveSession()
                }
            }
        }
    }

    fun stop() {
        generation++
        cancelLoading()
        updateState(false)
        leaveSession()
        worker.execute { sfPlayer.stopPlaying() }
    }

    fun togglePause() {
        if (editorViewModel.playSession.value != true) return
        if (paused) resume() else pause()
    }

    fun seekToStart() {
        seekToPosition(0)
    }

    fun seekMeasure(direction: Int) {
        val current = clock ?: return
        if (editorViewModel.playSession.value != true) return
        val ticket = generation
        worker.execute {
            if (ticket != generation) return@execute
            val position = sfPlayer.positionMs()
            val target = if (direction < 0) {
                previousMeasurePositionMs(current, position)
            } else {
                nextMeasurePositionMs(current, position)
            }
            sfPlayer.seekTo(target)
        }
    }

    fun seekTo(positionMs: Long) {
        seekToPosition(positionMs)
    }

    fun seekBy(deltaMs: Long) {
        val ticket = generation
        worker.execute {
            if (ticket != generation) return@execute
            sfPlayer.seekTo(sfPlayer.positionMs() + deltaMs)
        }
    }

    fun positionMs(): Long = sfPlayer.positionMs()

    fun durationMs(): Long = sfPlayer.durationMs()


    fun toggleMute(index: Int) {
        mutedVoices.value = flipped(mutedVoices.value, index)
    }

    fun toggleSolo(index: Int) {
        soloedVoices.value = flipped(soloedVoices.value, index)
    }


    fun release() {
        generation++
        cancelLoading()
        if (!isActive) {
            updateState(false)
        }
        worker.execute { sfPlayer.dispose() }
    }


    private fun seekToPosition(positionMs: Long) {
        if (editorViewModel.playSession.value != true) return
        val ticket = generation
        worker.execute {
            if (ticket != generation) return@execute
            val moved = sfPlayer.seekTo(positionMs)
            if (moved && ticket != generation) {
                sfPlayer.stopPlaying()
            }
        }
    }

    private fun pause() {
        val ticket = generation
        worker.execute {
            if (ticket != generation) return@execute
            val held = sfPlayer.pause()
            mainHandler.post {
                if (ticket == generation && held) {
                    paused = true
                    updateState(false)
                }
            }
        }
    }

    private fun resume() {
        val ticket = generation
        worker.execute {
            if (ticket != generation) return@execute
            val running = sfPlayer.resume()
            mainHandler.post {
                if (ticket == generation && running) {
                    paused = false
                    updateState(true)
                }
            }
        }
    }

    private fun enterSession(clock: PlaybackClock) {
        this.clock = clock
        paused = false
        editorViewModel.playSession.value = true
    }

    private fun leaveSession() {
        if (editorViewModel.playSession.value != true && clock == null) return
        clock = null
        paused = false
        editorViewModel.playSession.value = false
    }

    private fun audibleFlags(): List<Boolean> {
        return audibleVoices(
            editorViewModel.partitionData.voicesNum,
            mutedVoices.value.orEmpty(),
            soloedVoices.value.orEmpty(),
        )
    }

    private fun songPatches(): List<VoicePatch> {
        val repository = DoremiApp.from(mainContext).soundFonts
        val timbres = editorViewModel.partitionData.timbres.value
        val font = repository.resolveSongFont(timbres?.firstOrNull()?.soundFontId)
        val count = editorViewModel.partitionData.voicesNum
        return List(count) { index ->
            repository.assignmentOnFont(font, timbres?.getOrNull(index) ?: VoiceTimbre.piano())
        }
    }

    private fun flipped(current: MutableList<Boolean>?, index: Int): MutableList<Boolean> {
        val next = current?.toMutableList() ?: mutableListOf()
        while (next.size <= index) next.add(false)
        next[index] = !next[index]
        return next
    }


    private fun updateState(nextState: Boolean) {
        editorViewModel.playerIsPlaying.value = nextState
    }

    private fun armLoading(): Int {
        val wasIdle = pendingLoads == 0
        pendingLoads++
        if (wasIdle) {
            mainHandler.removeCallbacks(showLoading)
            mainHandler.postDelayed(showLoading, LOADING_DELAY_MS)
        }
        return loadEpoch
    }

    private fun finishLoading(epoch: Int) {
        if (epoch != loadEpoch) return
        pendingLoads = (pendingLoads - 1).coerceAtLeast(0)
        if (pendingLoads == 0) {
            mainHandler.removeCallbacks(showLoading)
            editorViewModel.playbackLoading.value = false
        }
    }

    private fun cancelLoading() {
        loadEpoch++
        pendingLoads = 0
        mainHandler.removeCallbacks(showLoading)
        editorViewModel.playbackLoading.value = false
    }

    private companion object {
        const val LOADING_DELAY_MS = 300L
    }
}

internal fun audibleVoices(count: Int, muted: List<Boolean>, soloed: List<Boolean>): List<Boolean> {
    if (count <= 0) return emptyList()
    val anySolo = (0 until count).any { soloed.getOrElse(it) { false } }
    return List(count) { index ->
        val isMuted = muted.getOrElse(index) { false }
        val isSolo = soloed.getOrElse(index) { false }
        !isMuted && (!anySolo || isSolo)
    }
}
