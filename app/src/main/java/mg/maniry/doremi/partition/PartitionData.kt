package mg.maniry.doremi.partition

import android.arch.lifecycle.MutableLiveData
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


    private fun createMissingCells(voice: Int, index: Int) {
        while (notes[voice].size <= index)
            notes[voice].add("")
    }


    fun updateChangeEvents(position: Int, events: MutableList<ChangeEvent>) {
        changeEvents = changeEvents
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
                when (_key) {
                    Labels.KEY -> key.value = _value.toInt()
                    Labels.TEMPO -> tempo = _value.toInt()
                    Labels.SWING -> swing.value = _value == "1"
                    Labels.CHANGES -> _value.split(',').forEach { str ->
                        ChangeEvent.fromString(str)?.run { changeEvents.add(this) }
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
            if (it._key == Labels.SIGNATURE)
                signature.value = it._value.toInt()
        }
    }


    override fun toString(): String {
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
        output += "${Labels.CHANGES}:${changeEvents.joinToString(",")} "

        notes.forEach { output += separator + it.joinToString(":") }

        output += separator + lyrics.value?.replace(';', ',')?.replace('\n', ';')

        return output
    }
}