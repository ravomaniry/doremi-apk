package mg.maniry.doremii.editor.partition

import androidx.lifecycle.MutableLiveData
import com.google.gson.JsonObject
import mg.maniry.doremii.commonUtils.Values
import java.lang.Exception
import java.util.*
import kotlin.math.max


class PartitionData {
    private val infoLabels = with(Labels) { listOf(TITLE, AUTHOR, COMP, DATE, SINGER) }
    private val structureLabels = with(Labels) { listOf(KEY, SIGNATURE, TEMPO) }

    var voicesNum = 4
    var notes = MutableList(voicesNum) { mutableListOf<String>() }
    var signature = MutableLiveData<Int>().apply { value = 4 }
    var tempo = 120
    var key = MutableLiveData<Int>().apply { value = 0 }
    var swing = MutableLiveData<Boolean>().apply { value = false }
    val lyrics = MutableLiveData<String>().apply { value = "" }
    val songInfo = SongInfo()
    var changeEvents = mutableListOf<ChangeEvent>()
    val timbres =
        MutableLiveData<MutableList<VoiceTimbre>>().apply {
            value = MutableList(voicesNum) { VoiceTimbre.piano() }
        }
    lateinit var voices: MutableList<String>
    var version = Values.solfaVersion
    var schema = SCHEMA
    var documentExtras = JsonObject()
    var scoreExtras = JsonObject()
    val voiceExtras = mutableListOf<JsonObject>()

    companion object {
        const val SCHEMA = 1
        val voiceIds = arrayListOf("S", "A", "T", "B")
    }


    init {
        reset()
    }


    fun reset(fallback: VoiceTimbre = VoiceTimbre.piano()) {
        voices = mutableListOf()
        changeEvents = mutableListOf()
        voicesNum = 4
        notes = MutableList(voicesNum) { mutableListOf<String>() }
        timbres.value = MutableList(voicesNum) { fallback }
        completeVoiceIds()

        lyrics.value = ""
        version = Values.solfaVersion
        schema = SCHEMA
        documentExtras = JsonObject()
        scoreExtras = JsonObject()
        voiceExtras.clear()
        songInfo.apply {
            title.value = "doremi_${Date().time}"
            author.value = ""
            compositor.value = ""
            releaseDate.value = ""
            singer.value = ""
            extras = JsonObject()
        }
    }


    fun updateVoiceId(index: Int, voiceIdIndex: Int) {
        voices[index] = voiceIds[voiceIdIndex]
        signature.value = signature.value
    }


    fun updateNote(cell: Cell) {
        createMissingCells(cell.voice, cell.index)
        notes[cell.voice][cell.index] = cell.content
    }

    fun addMeasure() {
        createMissingCells(0, notes[0].size + signature.value!! - 1)
    }

    fun safelyGetNote(voiceIdIndex: Int, index: Int): String {
        return if (notes.size > voiceIdIndex && notes[voiceIdIndex].size > index) notes[voiceIdIndex][index] else ""
    }


    fun updateSignature(i: Int) {
        if (signature.value != i) {
            signature.value = i
        }
    }


    fun updateKey(k: Int) {
        key.value = k
    }


    fun toggleSwing() {
        swing.value = !(swing.value ?: false)
    }


    fun updateVoicesNum(n: Int, fallback: VoiceTimbre = VoiceTimbre.piano()) {
        voicesNum = n
        completeVoiceIds()
        createMissingCells(n - 1, 0)

        val next = timbres.value?.toMutableList() ?: mutableListOf()
        while (next.size > n) {
            next.removeAt(next.size - 1)
        }
        while (next.size < n) {
            next.add(fallback)
        }
        timbres.value = next
        signature.value = signature.value
    }


    private fun completeVoiceIds() {
        while (voices.size < voicesNum) {
            voices.add(voiceIds[voices.size % voiceIds.size])
        }
        while (voices.size > voicesNum) {
            voices.removeAt(voices.size - 1)
        }
    }


    fun getMaxLength() = notes.map { it.size }.reduce(::max)


    fun getCell(voice: Int, index: Int): Cell {
        createMissingCells(voice, index)
        return Cell(voice, index, notes[voice][index])
    }


    fun createMissingCells(voice: Int, index: Int) {
        while (notes.size <= voice) {
            notes.add(mutableListOf())
        }
        while (notes[voice].size <= index) {
            notes[voice].add("")
        }
    }


    fun updateChangeEvents(position: Int, events: MutableList<ChangeEvent>) {
        changeEvents =
            changeEvents.asSequence().filter { it.position != position }.toMutableList().apply {
                addAll(events)
                sortBy(ChangeEvent::position)
            }
    }


    fun setVoiceTimbre(voice: Int, timbre: VoiceTimbre) {
        val next = timbres.value?.toMutableList() ?: mutableListOf()
        while (next.size <= voice) {
            next.add(VoiceTimbre.piano())
        }
        next[voice] = timbre
        timbres.value = next
    }

    fun replaceTimbres(next: List<VoiceTimbre>) {
        timbres.value = next.toMutableList()
    }


    fun parseRawString(raw: String) {
        reset()

        val mainParts = raw.split("__!!__").toMutableList().also {
            while (it.size < 6) it.add("")
        }

        val info = mainParts[0].split(";").map { KeyValue(it.trim().split(':')) }
        songInfo.parseFile(info)

        info.forEach {
            with(it) {
                when (mKey) {
                    Labels.KEY -> key.value = mValue.toInt()
                    Labels.TEMPO -> tempo = mValue.toInt()
                    Labels.SWING -> swing.value = mValue == "1"
                    Labels.VERSION -> version = mValue
                    Labels.VOICES -> {
                        val ids = mValue.split(",")
                        voices = ids.toMutableList()
                        voicesNum = ids.size
                    }

                    Labels.CHANGES -> mValue.split(',').forEach { str ->
                        ChangeEvent.fromString(str)?.run { changeEvents.add(this) }
                    }

                    Labels.INSTR -> mValue.split(",").forEachIndexed { i, instr ->
                        setVoiceTimbre(i, VoiceTimbre.parseToken(instr))
                    }
                }
            }
        }

        for (index in 0 until voicesNum) {
            if (mainParts.size > index + 2) {
                createMissingCells(index, 0)
                notes[index] = mainParts[index + 1].split(':').toMutableList()
            }
        }

        if (mainParts.size > voicesNum + 1) {
            lyrics.value = mainParts[voicesNum + 1].replace(';', '\n')
        }

        val loadedTimbres = timbres.value?.toMutableList() ?: mutableListOf()
        while (loadedTimbres.size < voicesNum) loadedTimbres.add(VoiceTimbre.piano())
        while (loadedTimbres.size > voicesNum) loadedTimbres.removeAt(loadedTimbres.size - 1)
        timbres.value = loadedTimbres

        // ReRender
        info.forEach {
            if (it.mKey == Labels.SIGNATURE) {
                signature.value = try {
                    it.mValue.toInt()
                } catch (e: Exception) {
                    4
                }
            }
        }
    }


    override fun toString(): String {
        trim()

        var output = ""
        val separator = "__!!__"

        output += "${Labels.VERSION}:$version; "
        output += "${Labels.SWING}:${if (swing.value == true) 1 else 0}; "
        output += "${Labels.CHANGES}:${changeEvents.joinToString(",")}; "
        output += "${Labels.INSTR}:${timbres.value?.joinToString(",") { it.serialize() }}; "
        output += "${Labels.VOICES}:${voices.joinToString(",")}; "

        with(songInfo) {
            listOf(
                title.value, author.value, compositor.value, releaseDate.value, singer.value
            ).forEachIndexed { i, value ->
                output += "${infoLabels[i]}:${
                    value?.replace(';', ' ')?.replace(':', ' ')?.replace(separator, "_") ?: " "
                }; "
            }
        }

        listOf(key.value, signature.value, tempo).forEachIndexed { i, value ->
            output += "${structureLabels[i]}:$value; "
        }

        voices.forEachIndexed { i, _ ->
            output += separator + notes[i].joinToString(":")
        }

        output += separator + lyrics.value?.replace(';', ',')?.replace('\n', ';')

        return output
    }


    fun toJson(): String {
        trim()
        alignVoiceExtras()
        return SolfaJson.write(this)
    }


    fun fromJson(raw: String) {
        SolfaJson.readInto(this, raw)
    }


    private fun alignVoiceExtras() {
        while (voiceExtras.size < voices.size) voiceExtras.add(JsonObject())
        while (voiceExtras.size > voices.size) voiceExtras.removeAt(voiceExtras.lastIndex)
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