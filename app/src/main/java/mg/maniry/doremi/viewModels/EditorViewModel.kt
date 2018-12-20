package mg.maniry.doremi.viewModels


import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import mg.maniry.doremi.partition.*
import mg.maniry.doremi.commonUtils.FileManager
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread
import java.io.File


class EditorViewModel : ViewModel() {
    private var prefs: SharedPreferences? = null
    private val parser = NotesParser()
    val partitionData = PartitionData()
    private val printer = HtmlExport(partitionData)
    private val updater = NotesUpdater(partitionData)
    val lyricsEditMode = MutableLiveData<Boolean>().apply { value = false }
    val octave = MutableLiveData<Int>().apply { value = 0 }
    var updatedCell = MutableLiveData<Cell>()
    var cursorPos = MutableLiveData<Cell>().apply { value = Cell() }
    var dialogPosition = 0
    val dialogOpen = MutableLiveData<Boolean>()
    val headerTvTrigger = MutableLiveData<Boolean>()
    val message = MutableLiveData<String>()
    var fileList = MutableLiveData<List<String>>()
    var prevCursorPos: Cell? = null
    var playerCursorPosition = 0
    var enablePlayerVelocity = MutableLiveData<Boolean>().apply { value = true }
    var playerLoops = 0
    private var filename = ""
    private var history = EditionHistory()


    init {
        updater.moveCursor(0, 0)
        fileList.value = listOf()
    }


    fun start(preferences: SharedPreferences, intent: Intent?) {
        prefs = preferences
        with(intent) {
            if (this != null && action == Intent.ACTION_VIEW &&
                    data is Uri && data != null && data?.path != null) {
                val path = FileManager.moveIntoDoremiDir(path = data?.path) ?: ""

                if (path == "") {
                    loadRecentFile()
                    message.value = "Impossible d'ouvrir le fichier"

                } else {
                    loadFile(path) {
                        message.value = "Impossible d'ouvrir le fichier"
                        loadRecentFile()
                    }
                }
            } else {
                loadRecentFile()
            }
        }

        FileManager.importAllDoremiFiles { refreshFileList() }
    }


    fun addNote(n: String) {
        history.anticipate(cursorPos.value, partitionData)
        updater.add(addOctaveToNote(n, octave.value)).also {
            updatedCell.value = it
            if (it != null) {
                if (it.index != cursorPos.value?.index) {
                    prevCursorPos = cursorPos.value
                }
                cursorPos.value = it

                history.handle(it)
            }
        }
    }


    fun restoreHistory(isForward: Boolean) {
        history.restore(partitionData, isForward)?.also {
            updatedCell.value = it
            moveCursor(it.voice, it.index)
        }
    }


    fun changeOctave(increment: Boolean) {
        if (increment && octave.value!! < 2)
            octave.value = octave.value!! + 1
        else if (!increment && octave.value!! > -2)
            octave.value = octave.value!! - 1
    }


    fun moveCursor(voice: Int, index: Int) {
        if (cursorPos.value?.voice != voice || cursorPos.value?.index != index) {
            prevCursorPos = cursorPos.value
        }

        cursorPos.value = updater.moveCursor(voice, index)
    }


    fun openDialog(position: Int) {
        dialogPosition = position
        dialogOpen.value = true
    }


    fun deleteNotes() {
        history.anticipate(cursorPos.value, partitionData)
        updater.delete().also {
            updatedCell.value = it
            if (it != null && (cursorPos.value == null || it.index != cursorPos.value?.index)) {
                prevCursorPos = cursorPos.value
                cursorPos.value = it
                history.handle(it)
            }
        }
    }


    fun save() {
        if (filename == "")
            filename = partitionData.songInfo.filename

        if (partitionData.getMaxLength() > 4)
            FileManager.write(filename, partitionData.toString())
                    .also { if (it != "") message.value = it }

        refreshFileList()
    }


    fun updateSongInfo(index: String, value: String) {
        partitionData.songInfo.update(index, value)
        if (index == Labels.TITLE || index == Labels.SINGER) {
            FileManager.rename(filename, partitionData.songInfo.filename)
            refreshFileList()
            saveRecentFile(partitionData.songInfo.filename)
        }
    }


    fun refreshFileList() {
        doAsync {
            val list = FileManager.listFiles()
            uiThread { fileList.value = list }
        }
    }


    fun loadFile(path: String, onError: (e: String) -> Unit) {
        if (filename != path) {
            save()
            history.reset()

            with(FileManager.read(path)) {
                if (error == null) {
                    resetCursors()
                    partitionData.parseRawString(content)
                    this@EditorViewModel.filename = path
                } else {
                    message.value = "Impossible d'ouvrir: $path"
                    onError(error)
                }
            }

            saveRecentFile(path)
        }
    }


    private fun loadRecentFile() {
        prefs?.getString("recent", "").run {
            if (this != "" && this != null)
                loadFile(this) {}
        }
    }


    private fun saveRecentFile(filename: String) {
        this.filename = filename
        prefs?.edit()?.apply {
            putString("recent", filename)
            apply()
        }
    }


    fun reset() {
        save()
        resetCursors()
        history.reset()
        partitionData.reset()
        partitionData.signature.value = 4
        filename = partitionData.songInfo.filename
    }


    fun resetCursors() {
        playerCursorPosition = 0
        lyricsEditMode.value = false
        moveCursor(0, 0)
        updatedCell.value = null
    }


    fun resetDialog() {
        dialogOpen.value = false
        dialogPosition = 0
    }


    fun createTmpMidFile(file: File, playedVoices: MutableList<Boolean>?) {
        if (playerCursorPosition % partitionData.signature.value!! > 0)
            playerCursorPosition = 0

        parser.apply {
            key = partitionData.key.value ?: 0
            swing = partitionData.swing.value ?: false
            changeEvents = partitionData.changeEvents
            tempo = partitionData.tempo
            start = playerCursorPosition
            enableVelocity = enablePlayerVelocity.value ?: false
            loopsNumber = playerLoops
            signature = partitionData.signature.value ?: 4

            if (playedVoices != null)
                this.playedVoices = playedVoices
        }

        createMidiFile(CreateMidiParams(
                notes = parser.parse(partitionData.notes),
                tempo = partitionData.tempo,
                outFile = file,
                instruments = partitionData.instruments
        ))
    }


    fun print(callback: (String) -> Unit) {
        printer.print {
            callback(partitionData.songInfo.filename)
        }
    }
}