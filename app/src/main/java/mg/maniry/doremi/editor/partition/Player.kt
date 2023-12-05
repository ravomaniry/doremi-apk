package mg.maniry.doremi.editor.partition

import android.arch.lifecycle.MutableLiveData
import android.content.Context
import mg.maniry.doremi.editor.sfplayer.SfPlayer
import mg.maniry.doremi.editor.viewModels.EditorViewModel

data class Player constructor(
    private val mainContext: Context,
    private val editorViewModel: EditorViewModel,
) {
    var isActive = true
    private val sfPlayer = SfPlayer(mainContext)
    val playedVoices = MutableLiveData<MutableList<Boolean>>()

    init {
        playedVoices.value = editorViewModel.partitionData.voices.map { true }.toMutableList()
        sfPlayer.setOnCompletionListener {
            updateState(false)
        }
    }


    fun playSingleNote(pitch: Int) {
        sfPlayer.playSingleNote(pitch)
    }


    fun play() {
        updateState(true)
        val notes = editorViewModel.buildNotes(playedVoices.value)
        sfPlayer.play(notes, editorViewModel.partitionData.tempo)
    }

    fun stop() {
        updateState(false)
        sfPlayer.stop()
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
        sfPlayer.stop()
        if (!isActive) {
            updateState(false)
        }
    }


    private fun updateState(nextState: Boolean) {
        editorViewModel.playerIsPlaying.value = nextState
    }
}
