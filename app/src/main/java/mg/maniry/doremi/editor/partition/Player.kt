package mg.maniry.doremi.editor.partition

import android.arch.lifecycle.MutableLiveData
import android.content.Context
import android.media.MediaPlayer
import mg.maniry.doremi.editor.sfplayer.SfPlayer
import mg.maniry.doremi.editor.viewModels.EditorViewModel
import org.jetbrains.anko.doAsync


data class Player constructor(
    private val mainContext: Context,
    private val editorViewModel: EditorViewModel,
) {

    var isActive = true
    private val sfPlayer = SfPlayer(mainContext)
    private var isPlaying = false
    private var isReleased = false
    private var mediaPlayer = MediaPlayer()
    val playedVoices = MutableLiveData<MutableList<Boolean>>()

    init {
        playedVoices.value = editorViewModel.partitionData.voices.map { true }.toMutableList()
        mediaPlayer.setOnCompletionListener {
            mediaPlayer.reset()
            updateState(false)
        }
    }


    fun playSingleNote(pitch: Int) {
        sfPlayer.playSingleNote(pitch)
    }


    fun play() {
        playUsingSfPlayer()
    }

    private fun playUsingSfPlayer() {
        val notes = editorViewModel.buildNotes(playedVoices.value)
        sfPlayer.play(notes, editorViewModel.partitionData.tempo)
    }

    fun stop() {
        if (isPlaying) {
            editorViewModel.playerIsPlaying.value = true
            updateState(false)
            doAsync {
                mediaPlayer.run {
                    stop()
                    reset()
                }
            }
        }
    }


    fun toggleVoice(index: Int) {
        playedVoices.value?.run {
            while (size <= index) {
                add(false)
            }
            set(index, !get(index))
        }
        playedVoices.value = playedVoices.value
    }


    fun release() {
        sfPlayer.dispose()
        if (!isActive) {
            if (isPlaying) {
                mediaPlayer.stop()
            }
            mediaPlayer.release()
            isReleased = true
            updateState(false)
        }
    }


    private fun updateState(nextState: Boolean) {
        isPlaying = nextState
        editorViewModel.playerIsPlaying.value = nextState
    }
}
