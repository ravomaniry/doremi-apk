package mg.maniry.doremii.editor.partition

import android.content.Context
import androidx.lifecycle.MutableLiveData
import mg.maniry.doremii.DoremiApp
import mg.maniry.doremii.editor.sfplayer.SfPlayer
import mg.maniry.doremii.editor.sfplayer.VoicePatch
import mg.maniry.doremii.editor.viewModels.EditorViewModel

data class Player constructor(
    private val mainContext: Context,
    private val editorViewModel: EditorViewModel,
) {
    var isActive = true
    private val sfPlayer = SfPlayer()
    val mutedVoices = MutableLiveData(mutableListOf<Boolean>())
    val soloedVoices = MutableLiveData(mutableListOf<Boolean>())

    init {
        sfPlayer.setOnCompletionListener {
            updateState(false)
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
        sfPlayer.playSingleNote(pitch, patch, voice)
    }


    fun play() {
        updateState(true)
        val notes = editorViewModel.buildNotes(audibleFlags())
        if (!sfPlayer.play(notes, editorViewModel.partitionData.tempo, songPatches())) {
            updateState(false)
        }
    }

    fun stop() {
        updateState(false)
        sfPlayer.stopPlaying()
    }


    fun toggleMute(index: Int) {
        mutedVoices.value = flipped(mutedVoices.value, index)
    }

    fun toggleSolo(index: Int) {
        soloedVoices.value = flipped(soloedVoices.value, index)
    }


    fun release() {
        sfPlayer.dispose()
        if (!isActive) {
            updateState(false)
        }
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
