package mg.maniry.doremii.editor.partition

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import mg.maniry.doremii.commonUtils.Values

// Key names follow NotesParser's index order so a stored name does not shift if that list grows.
internal object SolfaJson {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val keyNames = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    private val changeToJson = mapOf(
        ChangeEvent.MOD to "modulation",
        ChangeEvent.TEMPO to "tempo",
        ChangeEvent.VELOCITY to "velocity",
        ChangeEvent.SIGN to "sign",
        ChangeEvent.DAL to "dal",
    )
    private val changeFromJson = changeToJson.entries.associate { (code, name) -> name to code }
    private val rootKeys = setOf("schema", "version", "info", "score", "voices", "changes", "lyrics")
    private val infoKeys = setOf("title", "author", "compositor", "releaseDate", "singer")
    private val scoreKeys = setOf("key", "signature", "tempo", "swing")
    private val voiceKeys = setOf("id", "timbre", "notes")
    private val timbreKeys = setOf("soundFont", "bank", "program", "legacy")
    private val changeKeys = setOf("at", "type", "value")

    fun write(data: PartitionData): String {
        val root = JsonObject()
        root.addProperty("schema", data.schema)
        root.addProperty("version", data.version)
        root.add("info", writeInfo(data.songInfo))
        root.add("score", writeScore(data))
        root.add("voices", writeVoices(data))
        root.add("changes", writeChanges(data.changeEvents))
        root.addProperty("lyrics", data.lyrics.value ?: "")
        writeExtras(root, data.documentExtras)
        return gson.toJson(root)
    }

    fun readInto(data: PartitionData, raw: String) {
        val root = parseObject(raw)
        val schema = schemaOf(root)
        val version = optionalString(root, "version", Values.solfaVersion)
        val info = readInfo(root.get("info"))
        val score = readScore(root.get("score"))
        val voices = readVoices(root.get("voices"))
        val changes = readChanges(root.get("changes"))
        val lyrics = optionalString(root, "lyrics", "")
        val documentExtras = extras(root, rootKeys)

        data.reset()
        data.schema = schema
        data.version = version
        data.documentExtras = documentExtras
        data.songInfo.title.value = info.title
        data.songInfo.author.value = info.author
        data.songInfo.compositor.value = info.compositor
        data.songInfo.releaseDate.value = info.releaseDate
        data.songInfo.singer.value = info.singer
        data.songInfo.extras = info.extras
        data.key.value = score.key
        data.tempo = score.tempo
        data.swing.value = score.swing
        data.scoreExtras = score.extras
        data.voicesNum = voices.size
        data.voices = voices.map { it.id }.toMutableList()
        data.notes = voices.map { it.notes.toMutableList() }.toMutableList()
        data.voiceExtras.clear()
        data.voiceExtras.addAll(voices.map { it.extras })
        data.timbres.value = voices.map { it.timbre }.toMutableList()
        data.changeEvents = changes.toMutableList()
        data.lyrics.value = lyrics
        data.signature.value = score.signature
    }

    fun displayName(raw: String): String? {
        return try {
            val info = parseObject(raw).get("info")
            if (info == null || info.isJsonNull) return ""
            if (!info.isJsonObject) return null
            val obj = info.asJsonObject
            val title = optionalString(obj, "title", "")
            val singer = optionalString(obj, "singer", "")
            title + if (singer.isEmpty()) "" else " - $singer"
        } catch (e: Exception) {
            null
        }
    }

    private fun writeInfo(info: SongInfo): JsonObject {
        val obj = JsonObject()
        obj.addProperty("title", info.title.value ?: "")
        obj.addProperty("author", info.author.value ?: "")
        obj.addProperty("compositor", info.compositor.value ?: "")
        obj.addProperty("releaseDate", info.releaseDate.value ?: "")
        obj.addProperty("singer", info.singer.value ?: "")
        writeExtras(obj, info.extras)
        return obj
    }

    private fun writeScore(data: PartitionData): JsonObject {
        val obj = JsonObject()
        val key = data.key.value ?: 0
        obj.addProperty("key", keyNames.getOrElse(key) { "C" })
        obj.addProperty("signature", data.signature.value ?: 4)
        obj.addProperty("tempo", data.tempo)
        obj.addProperty("swing", data.swing.value == true)
        writeExtras(obj, data.scoreExtras)
        return obj
    }

    private fun writeVoices(data: PartitionData): JsonArray {
        val voices = JsonArray()
        data.voices.forEachIndexed { index, id ->
            val voice = JsonObject()
            voice.addProperty("id", id)
            val timbre = data.timbres.value?.getOrNull(index) ?: VoiceTimbre.piano()
            voice.add("timbre", writeTimbre(timbre))
            val notes = JsonArray()
            data.notes.getOrNull(index).orEmpty().forEach { notes.add(it) }
            voice.add("notes", notes)
            writeExtras(voice, data.voiceExtras.getOrElse(index) { JsonObject() })
            voices.add(voice)
        }
        return voices
    }

    private fun writeTimbre(timbre: VoiceTimbre): JsonObject {
        val obj = JsonObject()
        val legacy = timbre.legacyIndex
        if (legacy != null) {
            obj.addProperty("legacy", legacy)
        } else {
            obj.addProperty("soundFont", timbre.soundFontId)
            obj.addProperty("bank", timbre.bank)
            obj.addProperty("program", timbre.program)
        }
        writeExtras(obj, timbre.extras)
        return obj
    }

    private fun writeChanges(events: List<ChangeEvent>): JsonArray {
        val changes = JsonArray()
        events.forEach { event ->
            val obj = JsonObject()
            obj.addProperty("at", event.position)
            obj.addProperty("type", changeToJson[event.type] ?: event.type)
            obj.addProperty("value", event.value)
            writeExtras(obj, event.extras)
            changes.add(obj)
        }
        return changes
    }

    private fun readInfo(element: JsonElement?): InfoFields {
        if (element == null || element.isJsonNull) return InfoFields()
        val obj = element.asObject("info")
        return InfoFields(
            title = optionalString(obj, "title", ""),
            author = optionalString(obj, "author", ""),
            compositor = optionalString(obj, "compositor", ""),
            releaseDate = optionalString(obj, "releaseDate", ""),
            singer = optionalString(obj, "singer", ""),
            extras = extras(obj, infoKeys),
        )
    }

    private fun readScore(element: JsonElement?): ScoreFields {
        if (element == null || element.isJsonNull) return ScoreFields()
        val obj = element.asObject("score")
        val keyName = if (!obj.has("key") || obj.get("key").isJsonNull) {
            "C"
        } else {
            optionalString(obj, "key", "C")
        }
        val keyIndex = keyNames.indexOf(keyName).let { if (it < 0) 0 else it }
        return ScoreFields(
            key = keyIndex,
            signature = optionalInt(obj, "signature", 4),
            tempo = optionalInt(obj, "tempo", 120),
            swing = optionalBoolean(obj, "swing", false),
            extras = extras(obj, scoreKeys),
        )
    }

    private fun readVoices(element: JsonElement?): List<VoiceFields> {
        if (element == null || element.isJsonNull || !element.isJsonArray) {
            throw IllegalArgumentException("Missing voices")
        }
        return element.asJsonArray.mapIndexed { index, item ->
            val voice = item.asObject("voices")
            val notesElement = voice.get("notes")
            if (notesElement != null && !notesElement.isJsonNull && !notesElement.isJsonArray) {
                throw IllegalArgumentException("Invalid voices")
            }
            val notes = if (notesElement == null || notesElement.isJsonNull) {
                emptyList()
            } else {
                notesElement.asJsonArray.map { note ->
                    if (!note.isJsonPrimitive || !note.asJsonPrimitive.isString) {
                        throw IllegalArgumentException("Invalid voices")
                    }
                    note.asString
                }
            }
            val id = if (!voice.has("id") || voice.get("id").isJsonNull) {
                PartitionData.voiceIds[index % PartitionData.voiceIds.size]
            } else {
                optionalString(voice, "id", PartitionData.voiceIds[index % PartitionData.voiceIds.size])
            }
            VoiceFields(
                id = id,
                timbre = readTimbre(voice.get("timbre")),
                notes = notes,
                extras = extras(voice, voiceKeys),
            )
        }
    }

    private fun readTimbre(element: JsonElement?): VoiceTimbre {
        if (element == null || element.isJsonNull) return VoiceTimbre.piano()
        val obj = element.asObject("timbre")
        val bag = extras(obj, timbreKeys)
        if (obj.has("legacy") && !obj.get("legacy").isJsonNull) {
            return VoiceTimbre(
                soundFontId = VoiceTimbre.PIANO_ID,
                bank = 0,
                program = 0,
                legacyIndex = requiredInt(obj, "legacy", "timbre"),
                extras = bag,
            )
        }
        if (!obj.has("soundFont") || obj.get("soundFont").isJsonNull) {
            return VoiceTimbre.piano().copy(extras = bag)
        }
        return VoiceTimbre(
            soundFontId = optionalString(obj, "soundFont", VoiceTimbre.PIANO_ID),
            bank = optionalInt(obj, "bank", 0),
            program = optionalInt(obj, "program", 0),
            extras = bag,
        )
    }

    private fun readChanges(element: JsonElement?): List<ChangeEvent> {
        if (element == null || element.isJsonNull) return emptyList()
        if (!element.isJsonArray) throw IllegalArgumentException("Invalid changes")
        return element.asJsonArray.map { item ->
            val obj = item.asObject("changes")
            if (!obj.has("at") || obj.get("at").isJsonNull) {
                throw IllegalArgumentException("Invalid changes")
            }
            if (!obj.has("type") || obj.get("type").isJsonNull) {
                throw IllegalArgumentException("Invalid changes")
            }
            val jsonType = optionalString(obj, "type", "")
            ChangeEvent(
                position = requiredInt(obj, "at", "changes"),
                type = changeFromJson[jsonType] ?: jsonType,
                value = if (!obj.has("value") || obj.get("value").isJsonNull) {
                    ""
                } else {
                    optionalString(obj, "value", "")
                },
                extras = extras(obj, changeKeys),
            )
        }
    }

    private fun parseObject(raw: String): JsonObject {
        val parsed = try {
            JsonParser.parseString(raw)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid solfa JSON")
        }
        if (parsed == null || !parsed.isJsonObject) throw IllegalArgumentException("Invalid solfa JSON")
        return parsed.asJsonObject
    }

    private fun schemaOf(root: JsonObject): Int {
        if (!root.has("schema") || root.get("schema").isJsonNull) {
            throw IllegalArgumentException("Missing schema")
        }
        val schema = wholeInt(root.get("schema")) ?: throw IllegalArgumentException("Missing schema")
        if (schema < 1) throw IllegalArgumentException("Missing schema")
        return schema
    }

    private fun optionalString(obj: JsonObject, key: String, default: String): String {
        if (!obj.has(key) || obj.get(key).isJsonNull) return default
        val element = obj.get(key)
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw IllegalArgumentException("Invalid $key")
        }
        return element.asString
    }

    private fun optionalInt(obj: JsonObject, key: String, default: Int): Int {
        if (!obj.has(key) || obj.get(key).isJsonNull) return default
        return wholeInt(obj.get(key)) ?: throw IllegalArgumentException("Invalid $key")
    }

    private fun requiredInt(obj: JsonObject, key: String, label: String): Int {
        if (!obj.has(key) || obj.get(key).isJsonNull) throw IllegalArgumentException("Invalid $label")
        return wholeInt(obj.get(key)) ?: throw IllegalArgumentException("Invalid $label")
    }

    private fun optionalBoolean(obj: JsonObject, key: String, default: Boolean): Boolean {
        if (!obj.has(key) || obj.get(key).isJsonNull) return default
        val element = obj.get(key)
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) {
            throw IllegalArgumentException("Invalid $key")
        }
        return element.asBoolean
    }

    private fun wholeInt(element: JsonElement): Int? {
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        val number = element.asDouble
        if (number.isNaN() || number.isInfinite()) return null
        if (number % 1.0 != 0.0) return null
        if (number > Int.MAX_VALUE || number < Int.MIN_VALUE) return null
        return number.toInt()
    }

    private fun extras(obj: JsonObject, known: Set<String>): JsonObject {
        val bag = JsonObject()
        for ((key, value) in obj.entrySet()) {
            if (key !in known) bag.add(key, value.deepCopy())
        }
        return bag
    }

    private fun writeExtras(obj: JsonObject, bag: JsonObject) {
        for ((key, value) in bag.entrySet()) {
            if (!obj.has(key)) obj.add(key, value.deepCopy())
        }
    }

    private fun JsonElement.asObject(label: String): JsonObject {
        if (!isJsonObject) throw IllegalArgumentException("Invalid $label")
        return asJsonObject
    }

    private data class InfoFields(
        val title: String = "",
        val author: String = "",
        val compositor: String = "",
        val releaseDate: String = "",
        val singer: String = "",
        val extras: JsonObject = JsonObject(),
    )

    private data class ScoreFields(
        val key: Int = 0,
        val signature: Int = 4,
        val tempo: Int = 120,
        val swing: Boolean = false,
        val extras: JsonObject = JsonObject(),
    )

    private data class VoiceFields(
        val id: String,
        val timbre: VoiceTimbre,
        val notes: List<String>,
        val extras: JsonObject,
    )
}
