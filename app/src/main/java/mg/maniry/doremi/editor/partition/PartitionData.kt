package mg.maniry.doremi.editor.partition

import android.arch.lifecycle.MutableLiveData
import java.lang.Exception
import java.util.*
import kotlin.math.max


class PartitionData {
    private val infoLabels = with(Labels) { listOf(TITLE, AUTHOR, COMP, DATE, SINGER) }
    private val structureLabels = with(Labels) { listOf(KEY, SIGNATURE, TEMPO) }

    var notes = Array(4) { mutableListOf<String>() }
    var signature = MutableLiveData<Int>().apply { value = 4 }
    var tempo = 120
    var key = MutableLiveData<Int>().apply { value = 0 }
    var swing = MutableLiveData<Boolean>().apply { value = false }
    val lyrics = MutableLiveData<String>().apply { value = "" }
    val songInfo = SongInfo()
    var changeEvents = mutableListOf<ChangeEvent>()
    val instruments = Array(4) { MutableLiveData<Int>().apply { value = 0 } }


    init {
        reset()
    }


    fun reset() {
        changeEvents = mutableListOf()
        notes = Array(4) { mutableListOf<String>() }

        lyrics.value = ""
        songInfo.apply {
            title.value = "doremi_${Date().time}"
            author.value = ""
            compositor.value = ""
            releaseDate.value = ""
            singer.value = ""
        }
    }


    fun updateNote(cell: Cell) {
        createMissingCells(cell.voice, cell.index)
        notes[cell.voice][cell.index] = cell.content
    }


    fun updateSignature(i: Int) {
        if (signature.value != i)
            signature.value = i
    }


    fun updateKey(k: Int) {
        key.value = k
    }


    fun toggleSwing() {
        swing.value = !(swing.value ?: false)
    }


    fun getMaxLength() = notes.map { it.size }.reduce(::max)


    fun getCell(voice: Int, index: Int): Cell {
        createMissingCells(voice, index)
        return Cell(voice, index, notes[voice][index])
    }


    fun createMissingCells(voice: Int, index: Int) {
        while (notes[voice].size <= index) {
            notes[voice].add("")
        }
    }


    fun updateChangeEvents(position: Int, events: MutableList<ChangeEvent>) {
        changeEvents = changeEvents
                .asSequence()
                .filter { it.position != position }
                .toMutableList()
                .apply {
                    addAll(events)
                    sortBy(ChangeEvent::position)
                }
    }


    fun parseRawString(raw: String) {
        reset()

        val mainParts = raw.split("__!!__").toMutableList().also {
            while (it.size < 6) it.add("")
        }

        val info = mainParts[0]
                .split(";")
                .map { KeyValue(it.trim().split(':')) }
        songInfo.parseFile(info)

        info.forEach {
            with(it) {
                when (mKey) {
                    Labels.KEY -> key.value = mValue.toInt()
                    Labels.TEMPO -> tempo = mValue.toInt()
                    Labels.SWING -> swing.value = mValue == "1"
                    Labels.CHANGES -> mValue.split(',').forEach { str ->
                        ChangeEvent.fromString(str)?.run { changeEvents.add(this) }
                    }
                    Labels.INSTR -> mValue.split(",").forEachIndexed { i, instr ->
                        if (i < 4) {
                            instruments[i].value = try {
                                instr.trim().toInt()
                            } catch (e: Exception) {
                                0
                            }
                        }
                    }
                }
            }
        }

        mainParts.slice(1 until 5).forEachIndexed { index, str ->
            notes[index] = str.split(':').toMutableList()
        }

        lyrics.value = mainParts[5].replace(';', '\n')

        // ReRender
        info.forEach {
            if (it.mKey == Labels.SIGNATURE)
                signature.value = it.mValue.toInt()
        }
    }


    override fun toString(): String {
        trim()

        var output = ""
        val separator = "__!!__"

        with(songInfo) {
            listOf(title.value, author.value, compositor.value, releaseDate.value, singer.value).forEachIndexed { i, value ->
                output += "${infoLabels[i]}:${value?.replace(';', ' ')?.replace(':', ' ')?.replace(separator, "_")
                        ?: " "}; "
            }
        }

        listOf(key.value, signature.value, tempo).forEachIndexed { i, value ->
            output += "${structureLabels[i]}:$value; "
        }

        output += "${Labels.SWING}:${if (swing.value == true) 1 else 0}; "
        output += "${Labels.CHANGES}:${changeEvents.joinToString(",")}; "
        output += "${Labels.INSTR}:${instruments.joinToString { (it.value ?: 0).toString() }} "

        notes.forEach { output += separator + it.joinToString(":") }

        output += separator + lyrics.value?.replace(';', ',')?.replace('\n', ';')

        return output
    }


    private fun trim() {
        val changeEventMax = when {
            changeEvents.isEmpty() -> 0
            else -> changeEvents.asSequence().map { it.position }.reduce(::max) + 1
        }

        notes.forEach {
            if (it.size > changeEventMax) {
                while (it.size > changeEventMax && it.size > 0 && it[it.size - 1].trim() == "") {
                    it.removeAt(it.size - 1)
                }
            } else {
                while (it.size < changeEventMax) {
                    it.add("")
                }
            }
        }
    }
}