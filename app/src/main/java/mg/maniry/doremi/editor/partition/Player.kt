package mg.maniry.doremi.editor.partition

import android.arch.lifecycle.MutableLiveData
import android.content.Context
import android.media.MediaPlayer
import android.widget.Toast
import mg.maniry.doremi.commonUtils.Values
import mg.maniry.doremi.editor.viewModels.EditorViewModel
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread
import java.io.File
import java.lang.Exception


data class Player constructor(
        private val mainContext: Context,
        private val editorViewModel: EditorViewModel) {

    var isActive = true
    var isPlaying = false
    private val mediaPlayer = MediaPlayer()
    val playedVoices = MutableLiveData<MutableList<Boolean>>()
    private val tmpMid = File(mainContext.filesDir, "tmp.mid")
            .apply { if (!exists()) createNewFile() }
            .apply { setReadable(true, false) }


    init {
        playedVoices.value = mutableListOf(true, true, true, true)
        mediaPlayer.setOnCompletionListener {
            mediaPlayer.reset()
            updateState(false)
        }
    }


    fun play() {
        if (!isPlaying) {
            doAsync {
                try {
                    editorViewModel.createTmpMidFile(tmpMid, playedVoices.value)
                    mediaPlayer.apply {
                        setDataSource(tmpMid.absolutePath)
                        prepare()
                        start()
                    }
                    uiThread {
                        updateState(true)
                        editorViewModel.message.value = Values.playing
                    }
                } catch (e: Exception) {
                    uiThread {
                        Toast.makeText(mainContext, Values.playerErr, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
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
        val list = playedVoices.value!!
        list[index] = !list[index]
        playedVoices.value = list
    }


    fun release() {
        mediaPlayer.stop()
        mediaPlayer.release()
    }


    private fun updateState(nextState: Boolean) {
        isPlaying = nextState
        editorViewModel.playerIsPlaying.value = nextState
    }
}
