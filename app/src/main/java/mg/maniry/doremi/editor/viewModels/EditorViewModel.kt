package mg.maniry.doremi.editor.viewModels


import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import mg.maniry.doremi.editor.partition.*
import mg.maniry.doremi.commonUtils.FileManager
import mg.maniry.doremi.commonUtils.Values
import mg.maniry.doremi.editor.xlsxExport.ExcelExport
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread
import java.io.File


class EditorViewModel : ViewModel() {
    private var prefs: SharedPreferences? = null
    private val parser = NotesParser()
    val partitionData = PartitionData()
    private val printer = HtmlExport(partitionData)
    lateinit var xlsExport: ExcelExport
    private val updater = NotesUpdater(partitionData)
    val lyricsEditMode = MutableLiveData<Boolean>().apply { value = false }
    val octave = MutableLiveData<Int>().apply { value = 0 }
    private val _reRenderNotifier = MutableLiveData<Int>().apply { value = 0 }
    val reRenderNotifier: LiveData<Int> = _reRenderNotifier
    var cursorPos = MutableLiveData<Cell>().apply { value = Cell() }
    var dialogPosition = 0
    val dialogOpen = MutableLiveData<Boolean>()
    val headerTvTrigger = MutableLiveData<Boolean>()
    val message = MutableLiveData<String>()
    private var prevCursorPos: Cell? = null
    private var _playerCursorPosition = MutableLiveData<Int>().apply { value = 0 }
    val playerCursorPosition: LiveData<Int> get() = _playerCursorPosition
    var enablePlayerVelocity = MutableLiveData<Boolean>().apply { value = true }
    var playerLoops = 0
    private var filename = ""
    private var history = EditionHistory()
    var player: Player? = null
    val playerIsPlaying = MutableLiveData<Boolean>().apply { value = false }
    var selectMode = MutableLiveData<SelectMode>().apply { value = SelectMode.CURSOR }
    var clipBoard: ClipBoard? = null
    val instrument = MutableLiveData<String>().apply {
        value = FileManager.redInstrumentName()
    }


    init {
        updater.moveCursor(0, 0)
    }

    private fun notifyListeners() {
        _reRenderNotifier.value = _reRenderNotifier.value!! + 1
    }

    fun start(preferences: SharedPreferences, intent: Intent?) {
        prefs = preferences
        with(intent) {
            if (this != null) {
                val fileToOpen = getStringExtra("fileToOpen")
                if (fileToOpen != null) {
                    loadFile(fileToOpen)
                } else if (action == Intent.ACTION_VIEW && data is Uri && data != null && data?.path != null) {
                    val path = FileManager.moveIntoDoremiDir(path = data?.path) ?: ""

                    if (path == "") {
                        loadRecentFile()
                        message.value = Values.fileOpenError
                    } else {
                        loadFile(path)
                    }
                } else {
                    loadRecentFile()
                }
            } else {
                loadRecentFile()
            }
        }
    }


    fun addNote(n: String) {
        history.anticipate(cursorPos.value, partitionData)
        val noteWithOctave = addOctaveToNote(n, octave.value)
        val cell = updater.add(noteWithOctave)
        if (cell != null) {
            if (cell.index != cursorPos.value?.index) {
                prevCursorPos = cursorPos.value
            }
            cursorPos.value = cell
            history.handle(cell)
            playAddedNote(noteWithOctave, cell)
        }
        notifyListeners()
    }

    fun addMeasure() {
        partitionData.addMeasure()
        notifyListeners()
    }

    private fun playAddedNote(noteWithOctave: String, cell: Cell) {
        parser.key = partitionData.key.value ?: 0
        val pitch = parser.noteToPitch(noteWithOctave, partitionData.voices[cell.voice])
        if (pitch > 0) {
            player?.playSingleNote(pitch)
        }
    }


    fun restoreHistory(isForward: Boolean) {
        history.restore(partitionData, isForward)?.also { cells ->
            moveCursor(cells.first().voice, cells.first().index)
        }
        notifyListeners()
    }


    fun changeOctave(increment: Boolean) {
        if (increment && octave.value!! < 2) {
            octave.value = octave.value!! + 1
        } else if (!increment && octave.value!! > -2) {
            octave.value = octave.value!! - 1
        }
    }


    fun moveCursor(voice: Int, index: Int) {
        endClipboard(voice, index)
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
        if (selectMode.value == SelectMode.COPY && clipBoard != null) {
            val result = updater.massDelete(clipBoard!!)
            history.handleBulkOps(result.changes)
            selectMode.value = SelectMode.CURSOR
        } else {
            history.anticipate(cursorPos.value, partitionData)
            updater.delete().also {
                if (it != null && (cursorPos.value == null || it.index != cursorPos.value?.index)) {
                    prevCursorPos = cursorPos.value
                    cursorPos.value = it
                    history.handle(it)
                }
            }
        }
        notifyListeners()
    }


    fun save() {
        if (filename == "") {
            filename = partitionData.songInfo.filename
        }
        if (partitionData.getMaxLength() > 4) {
            FileManager.write(filename, partitionData.toString())
                .also { if (it != "") message.value = it }
        }
    }


    fun updateSongInfo(index: String, value: String) {
        partitionData.songInfo.update(index, value)
        if (index == Labels.TITLE || index == Labels.SINGER) {
            FileManager.rename(filename, partitionData.songInfo.filename)
            saveRecentFile(partitionData.songInfo.filename)
        }
    }


    fun updateVoicesNum(n: Int) {
        if (cursorPos.value != null && cursorPos.value!!.voice >= n) {
            moveCursor(0, 0)
        }
        partitionData.updateVoicesNum(n)
    }

    fun onPlayerCursorPosChanged(index: Int) {
        _playerCursorPosition.value = index
    }


    private fun loadFile(path: String, onError: ((e: String) -> Unit)? = null) {
        if (filename != path) {
            save()
            history.reset()

            with(FileManager.read(path)) {
                if (error == null) {
                    resetCursors()
                    partitionData.parseRawString(content)
                    this@EditorViewModel.filename = path
                } else {
                    message.value = "${Values.fileOpenError}: $path"
                    if (onError == null) {
                        loadRecentFile()
                    } else {
                        onError(error)
                    }
                }
            }

            saveRecentFile(path)
        }
    }


    private fun loadRecentFile() {
        prefs?.getString("recent", "").run {
            if (this != "" && this != null) {
                loadFile(this) {}
            }
        }
    }


    private fun saveRecentFile(filename: String) {
        this.filename = filename
        prefs?.edit()?.apply {
            putString("recent", filename)
            apply()
        }
    }

    fun onInstrumentChanged(newValue: String) {
        instrument.value = newValue
        FileManager.saveInstrumentName(newValue)
    }


    fun createNew() {
        save()
        resetCursors()
        history.reset()
        partitionData.reset()
        partitionData.signature.value = 4
        filename = partitionData.songInfo.filename
    }


    fun resetCursors() {
        _playerCursorPosition.value = 0
        lyricsEditMode.value = false
        moveCursor(0, 0)
        notifyListeners()
    }


    fun resetDialog() {
        dialogOpen.value = false
        dialogPosition = 0
    }


    fun createTmpMidFile(file: File, playedVoices: List<Boolean>?) {
        val notes = buildNotes(playedVoices)
        createMidiFile(
            CreateMidiParams(
                notes = notes,
                tempo = partitionData.tempo,
                outFile = file,
                instruments = partitionData.instruments.value,
            )
        )
    }

    fun buildNotes(playedVoices: List<Boolean>?): List<Note> {
        if (playerCursorPosition.value!! % partitionData.signature.value!! > 0) {
            _playerCursorPosition.value = 0
        }
        parser.apply {
            key = partitionData.key.value ?: 0
            swing = partitionData.swing.value ?: false
            changeEvents = partitionData.changeEvents
            tempo = partitionData.tempo
            start = playerCursorPosition.value!!
            enableVelocity = enablePlayerVelocity.value ?: false
            loopsNumber = playerLoops
            signature = partitionData.signature.value ?: 4
            voiceIds = partitionData.voices
            if (playedVoices != null) {
                this.playedVoices = playedVoices
            }
        }
        return parser.parse(partitionData.notes)
    }


    fun exportMidiFile() {
        doAsync {
            createTmpMidFile(
                File(FileManager.createExportFilePath(partitionData.songInfo.filename, ".mid")),
                partitionData.voices.map { true }.toMutableList()
            )

            uiThread {
                message.value = "${Values.done}:\nmidi/${partitionData.songInfo.filename}.mid"
            }
        }
    }


    fun print() {
        printer.print {
            message.value = when (it) {
                Values.saved -> "${Values.done}:\nexport/${partitionData.songInfo.filename}.html"
                else -> it
            }
            doAsync {
                xlsExport.export(partitionData)
            }
        }
    }


    fun releasePlayer() {
        player?.run {
            isActive = false
            release()
        }
    }


    fun cancelPlayerRelease() {
        player?.apply {
            isActive = true
        }
    }


    fun toggleSelectMode() {
        if (selectMode.value == SelectMode.CURSOR) {
            initClipBoard()
        } else {
            message.value = Values.clipboardSaved
            selectMode.value = SelectMode.CURSOR
        }
    }


    private fun initClipBoard() {
        cursorPos.value?.run {
            clipBoard = ClipBoard(this, this)
        }
        selectMode.value = SelectMode.COPY
    }


    private fun endClipboard(voice: Int, index: Int) {
        if (selectMode.value == SelectMode.COPY) {
            if (clipBoard != null) {
                clipBoard = clipBoard!!.copy(end = Cell(voice, index)).apply {
                    reorder()
                }
            }
        }
    }


    fun paste() {
        if (clipBoard == null || cursorPos.value == null) {
            message.value = Values.emptyClipboard
        } else {
            val pasteResult = updater.paste(clipBoard!!, cursorPos.value!!)
            history.handleBulkOps(pasteResult.changes)
        }
        notifyListeners()
    }
}
