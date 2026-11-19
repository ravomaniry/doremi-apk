package mg.maniry.doremii.editor.viewModels


import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mg.maniry.doremii.editor.partition.*
import mg.maniry.doremii.commonUtils.FileManager
import mg.maniry.doremii.commonUtils.PlaybackSettings
import mg.maniry.doremii.commonUtils.Values
import mg.maniry.doremii.DoremiApp
import mg.maniry.doremii.editor.xlsxExport.ExcelExport
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
    private var _cursorPos = Cell()
    val cursorPos: Cell get() = _cursorPos
    var dialogPosition = 0
    private val _dialogOpen = MutableLiveData<Boolean>()
    val dialogOpen: LiveData<Boolean> get() = _dialogOpen
    val message = MutableLiveData<String>()
    private var prevCursorPos: Cell? = null
    private var _measureToStartPlayer = 0
    val measureToStartPlayer: Int get() = _measureToStartPlayer
    var enablePlayerVelocity = MutableLiveData<Boolean>().apply { value = true }
    var playerLoops = 0
    private var filename = ""
    private var history = EditionHistory()
    var player: Player? = null
    private var launched = false
    val playerIsPlaying = MutableLiveData<Boolean>().apply { value = false }
    val playbackLoading = MutableLiveData<Boolean>().apply { value = false }
    private var _selectMode = SelectMode.CURSOR
    val selectMode: SelectMode get() = _selectMode
    private val _selectModeState = MutableLiveData(SelectMode.CURSOR)
    val selectModeState: LiveData<SelectMode> = _selectModeState
    var clipBoard: ClipBoard? = null


    init {
        updater.moveCursor(0, 0)
    }

    private fun notifyListeners() {
        _reRenderNotifier.value = _reRenderNotifier.value!! + 1
    }

    fun start(preferences: SharedPreferences, intent: Intent?) {
        prefs = preferences
        if (intent?.getBooleanExtra("createNew", false) == true) {
            if (!launched) {
                launched = true
                createNew()
            }
            return
        }
        launched = true
        with(intent) {
            if (this != null) {
                val fileToOpen = getStringExtra("fileToOpen")
                if (fileToOpen != null) {
                    loadFile(fileToOpen)
                } else if (action == Intent.ACTION_VIEW && data is Uri) {
                    val opened = data?.let { uri ->
                        if (uri.scheme == "content") {
                            FileManager.importContentUri(uri)
                        } else {
                            FileManager.moveIntoDoremiDir(path = uri.path)
                        }
                    } ?: ""
                    val path = opened

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
        history.anticipate(cursorPos, partitionData)
        val noteWithOctave = addOctaveToNote(n, octave.value)
        val cell = updater.add(noteWithOctave)
        if (cell != null) {
            if (cell.index != cursorPos.index) {
                prevCursorPos = cursorPos
            }
            _cursorPos = cell
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
        if (cursorPos.voice != voice || cursorPos.index != index) {
            prevCursorPos = cursorPos
        }
        _cursorPos = updater.moveCursor(voice, index)
        notifyListeners()
    }


    fun openDialog(position: Int) {
        dialogPosition = position
        _dialogOpen.value = true
    }

    fun updateChangeEvents(position: Int, events: MutableList<ChangeEvent>) {
        partitionData.updateChangeEvents(position, events)
        notifyListeners()
    }

    fun deleteNotes() {
        if (selectMode == SelectMode.COPY && clipBoard != null) {
            val result = updater.massDelete(clipBoard!!)
            history.handleBulkOps(result.changes)
            setSelectMode(SelectMode.CURSOR)
        } else {
            history.anticipate(cursorPos, partitionData)
            updater.delete().also {
                if (it != null && (it.index != cursorPos.index)) {
                    prevCursorPos = cursorPos
                    _cursorPos = it
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
            FileManager.write(filename, partitionData.toJson())
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


    private fun defaultTimbre(): VoiceTimbre {
        return DoremiApp.instance?.soundFonts?.defaultTimbre() ?: VoiceTimbre.piano()
    }

    private fun voiceFallback(): VoiceTimbre {
        val repository = DoremiApp.instance?.soundFonts ?: return defaultTimbre()
        val current = partitionData.timbres.value?.firstOrNull()
        val font = repository.resolveSongFont(current?.soundFontId) ?: return current ?: defaultTimbre()
        val preset = font.presets.firstOrNull()
        return VoiceTimbre(font.id, preset?.bank ?: 0, preset?.program ?: 0)
    }

    fun updateVoicesNum(n: Int) {
        if (cursorPos.voice >= n) {
            moveCursor(0, 0)
        }
        partitionData.updateVoicesNum(n, voiceFallback())
        notifyListeners()
    }

    fun onPlayerCursorPosChanged(index: Int) {
        _measureToStartPlayer = index
        notifyListeners()
    }


    private fun loadFile(path: String, onError: ((e: String) -> Unit)? = null) {
        if (filename != path) {
            save()
            history.reset()

            with(FileManager.read(path)) {
                if (error == null) {
                    try {
                        if (FileManager.isJsonSolfa(path)) {
                            partitionData.fromJson(content)
                        } else {
                            partitionData.parseRawString(content)
                        }
                        resetCursors()
                        this@EditorViewModel.filename = path
                    } catch (e: Exception) {
                        message.value = "${Values.fileOpenError}: $path"
                        if (onError == null) {
                            loadRecentFile()
                        } else {
                            onError(Values.fileOpenError)
                        }
                    }
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

    fun applyPlaybackSettings(context: Context) {
        playerLoops = PlaybackSettings.loops(context)
        enablePlayerVelocity.value = PlaybackSettings.velocity(context)
    }


    fun createNew() {
        save()
        resetCursors()
        history.reset()
        partitionData.reset(defaultTimbre())
        partitionData.signature.value = 4
        filename = partitionData.songInfo.filename
    }


    fun resetCursors() {
        _measureToStartPlayer = 0
        lyricsEditMode.value = false
        moveCursor(0, 0)
        notifyListeners()
    }


    fun resetDialog() {
        _dialogOpen.value = false
        dialogPosition = 0
    }


    private fun createTmpMidFile(file: File, playedVoices: List<Boolean>?) {
        val notes = buildNotes(playedVoices)
        createMidiFile(
            CreateMidiParams(
                notes = notes,
                tempo = partitionData.tempo,
                outFile = file,
                timbres = partitionData.timbres.value,
            )
        )
    }

    fun buildNotes(playedVoices: List<Boolean>?): List<Note> {
        parser.apply {
            key = partitionData.key.value ?: 0
            swing = partitionData.swing.value ?: false
            changeEvents = partitionData.changeEvents
            tempo = partitionData.tempo
            start = measureToStartPlayer * partitionData.signature.value!!
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
        viewModelScope.launch {
            val name = partitionData.songInfo.filename
            val result = withContext(Dispatchers.IO) {
                val cache = FileManager.newCacheFile(name, ".mid")
                createTmpMidFile(cache, partitionData.voices.map { true }.toMutableList())
                if (!cache.exists() || cache.length() == 0L) {
                    Values.saveError
                } else {
                    FileManager.writeDownload("$name.mid", "audio/midi", cache.readBytes()).also {
                        cache.delete()
                    }
                }
            }
            message.value = if (result == Values.saved) {
                "${Values.done}:\nDownloads/$name.mid"
            } else {
                result
            }
        }
    }


    fun print() {
        viewModelScope.launch {
            val name = partitionData.songInfo.filename
            val result = withContext(Dispatchers.IO) { printer.export() }
            message.value = when (result) {
                Values.saved -> "${Values.done}:\nDownloads/$name.html"
                else -> result
            }
            withContext(Dispatchers.IO) { xlsExport.export(partitionData) }
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
        if (selectMode == SelectMode.CURSOR) {
            initClipBoard()
        } else {
            message.value = Values.clipboardSaved
            setSelectMode(SelectMode.CURSOR)
        }
        notifyListeners()
    }


    private fun initClipBoard() {
        clipBoard = ClipBoard(cursorPos, cursorPos)
        setSelectMode(SelectMode.COPY)
    }


    private fun setSelectMode(mode: SelectMode) {
        _selectMode = mode
        _selectModeState.value = mode
    }


    private fun endClipboard(voice: Int, index: Int) {
        if (selectMode == SelectMode.COPY) {
            if (clipBoard != null) {
                clipBoard = clipBoard!!.copy(end = Cell(voice, index)).apply {
                    reorder()
                }
            }
        }
        notifyListeners()
    }


    fun paste() {
        if (clipBoard == null) {
            message.value = Values.emptyClipboard
        } else {
            val pasteResult = updater.paste(clipBoard!!, cursorPos)
            history.handleBulkOps(pasteResult.changes)
        }
        notifyListeners()
    }
}
