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
    private var isPlaying = false
    private var isReleased = false
    private var mediaPlayer = MediaPlayer()
    val playedVoices = MutableLiveData<MutableList<Boolean>>()
    private val tmpMid = File(mainContext.filesDir, "tmp.mid")
            .apply { if (!exists()) createNewFile() }
            .apply { setReadable(true, false) }


    init {
        playedVoices.value = editorViewModel.partitionData.voices.map { true }.toMutableList()
        mediaPlayer.setOnCompletionListener {
            mediaPlayer.reset()
            updateState(false)
        }
    }


    private fun notify(msg: String) {
        Toast.makeText(mainContext, msg, Toast.LENGTH_SHORT).show()
    }


    fun play() {
        if (isReleased) {
            mediaPlayer = MediaPlayer()
            isReleased = false
            isActive = true
        }

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
                        notify(Values.playerErr)
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
        playedVoices.value?.run {
            while (size <= index) {
                add(false)
            }
            set(index, !get(index))
        }
        playedVoices.value = playedVoices.value
    }


    fun release() {
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
