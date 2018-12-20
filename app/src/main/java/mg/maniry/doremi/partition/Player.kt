package mg.maniry.doremi.partition

import android.arch.lifecycle.MutableLiveData
import android.content.Context
import android.media.MediaPlayer
import android.widget.Toast
import mg.maniry.doremi.viewModels.EditorViewModel
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread
import java.io.File
import java.lang.Exception


class Player constructor(
        private val mainContext: Context,
        private val editorViewModel: EditorViewModel) {

    private var isPlaying = false
    private val mediaPlayer = MediaPlayer()
    val playedVoices = MutableLiveData<MutableList<Boolean>>()
    private val tmpMid = File(mainContext.filesDir, "tmp.mid")
            .apply { if (!exists()) createNewFile() }
            .apply { setReadable(true, false) }


    init {
        playedVoices.value = mutableListOf(true, true, true, true)
        mediaPlayer.setOnCompletionListener {
            mediaPlayer.reset()
            isPlaying = false
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
                    isPlaying = true
                } catch (e: Exception) {
                    uiThread {
                        Toast.makeText(mainContext, "Impossible de lire le son. :(", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }


    fun stop() {
        if (isPlaying) {
            isPlaying = false
            doAsync {
                mediaPlayer.apply { stop(); reset() }
            }
        }
    }


    fun toggleVoice(index: Int) {
        val list = playedVoices.value!!
        list[index] = !list[index]
        playedVoices.value = list
    }
}