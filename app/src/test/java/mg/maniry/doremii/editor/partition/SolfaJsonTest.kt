package mg.maniry.doremii.editor.partition

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class SolfaJsonTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun sampleScoreRoundTrips() {
        val original = sampleScore()
        val json = original.toJson()
        val again = PartitionData().apply { fromJson(json) }
        val parsed = JsonParser.parseString(json).asJsonObject

        assertEquals(1, parsed.get("schema").asInt)
        assertEquals("C", parsed.getAsJsonObject("score").get("key").asString)
        assertEquals(true, parsed.getAsJsonObject("score").get("swing").asBoolean)
        assertEquals("velocity", parsed.getAsJsonArray("changes")[0].asJsonObject.get("type").asString)
        assertEquals("modulation", parsed.getAsJsonArray("changes")[1].asJsonObject.get("type").asString)
        assertEquals("tempo", parsed.getAsJsonArray("changes")[2].asJsonObject.get("type").asString)
        assertFalse(json.contains("\"MD\""))

        assertEquals(original.songInfo.title.value, again.songInfo.title.value)
        assertEquals(original.songInfo.singer.value, again.songInfo.singer.value)
        assertEquals(original.songInfo.author.value, again.songInfo.author.value)
        assertEquals(original.songInfo.compositor.value, again.songInfo.compositor.value)
        assertEquals(original.songInfo.releaseDate.value, again.songInfo.releaseDate.value)
        assertEquals(original.signature.value, again.signature.value)
        assertEquals(original.tempo, again.tempo)
        assertEquals(original.key.value, again.key.value)
        assertEquals(original.swing.value, again.swing.value)
        assertEquals(original.lyrics.value, again.lyrics.value)
        assertEquals(original.notes, again.notes)
        assertEquals(original.voices, again.voices)
        assertEquals(3, again.changeEvents.size)
        assertEquals(ChangeEvent.VELOCITY, again.changeEvents[0].type)
        assertEquals("mf", again.changeEvents[0].value)
        assertEquals(ChangeEvent.MOD, again.changeEvents[1].type)
        assertEquals("G", again.changeEvents[1].value)
        assertEquals(ChangeEvent.TEMPO, again.changeEvents[2].type)
        assertEquals("80", again.changeEvents[2].value)
        assertEquals("Line one\n\nLine <two>", again.lyrics.value)
    }

    @Test
    fun punctuationInTitleAndLyricsSurvives() {
        val data = PartitionData()
        data.songInfo.title.value = "A:B;C__!!__D"
        data.songInfo.singer.value = "N:ame"
        data.lyrics.value = "a;b\nc,d"
        data.notes[0] = mutableListOf("d", "r")

        val again = PartitionData().apply { fromJson(data.toJson()) }

        assertEquals("A:B;C__!!__D", again.songInfo.title.value)
        assertEquals("N:ame", again.songInfo.singer.value)
        assertEquals("a;b\nc,d", again.lyrics.value)
        assertEquals("A:B;C__!!__D - N:ame", SolfaJson.displayName(data.toJson()))
    }

    @Test
    fun legacyAndSoundFontTimbresRoundTrip() {
        val json = """
            {
              "schema": 1,
              "voices": [
                {
                  "id": "S",
                  "timbre": { "legacy": 13, "soundFont": "other", "bank": 2, "program": 3 },
                  "notes": ["d"]
                },
                {
                  "id": "A",
                  "timbre": { "soundFont": "sf-fluid", "bank": 128, "program": 5, "gain": 2 },
                  "notes": ["s", ""]
                }
              ]
            }
        """.trimIndent()

        val data = PartitionData().apply { fromJson(json) }
        val timbres = data.timbres.value!!
        assertEquals(13, timbres[0].legacyIndex)
        assertEquals(VoiceTimbre.PIANO_ID, timbres[0].soundFontId)
        assertNull(timbres[1].legacyIndex)
        assertEquals("sf-fluid", timbres[1].soundFontId)
        assertEquals(128, timbres[1].bank)
        assertEquals(5, timbres[1].program)

        val again = JsonParser.parseString(data.toJson()).asJsonObject
        val written = again.getAsJsonArray("voices")
        val first = written[0].asJsonObject.getAsJsonObject("timbre")
        val second = written[1].asJsonObject.getAsJsonObject("timbre")
        assertEquals(13, first.get("legacy").asInt)
        assertFalse(first.has("soundFont"))
        assertEquals("sf-fluid", second.get("soundFont").asString)
        assertEquals(2, second.get("gain").asInt)
        assertEquals(1, written[1].asJsonObject.getAsJsonArray("notes").size())
        assertEquals("s", written[1].asJsonObject.getAsJsonArray("notes")[0].asString)
    }

    @Test
    fun unknownKeysAndChangeTypesAreWrittenBack() {
        val json = """
            {
              "schema": 4,
              "future": { "n": 1 },
              "info": { "title": "T", "singer": "S", "subtitle": "x" },
              "score": { "key": "Bb", "signature": 3, "tempo": 90, "swing": false, "feel": "lift" },
              "voices": [
                {
                  "id": "T",
                  "mute": false,
                  "timbre": { "soundFont": "piano", "bank": 0, "program": 0 },
                  "notes": ["t", "d.r"]
                }
              ],
              "changes": [
                { "at": 3, "type": "color", "value": "red", "lane": true },
                { "at": 1, "type": "sign", "value": "Fin" }
              ],
              "lyrics": "line"
            }
        """.trimIndent()

        val data = PartitionData().apply { fromJson(json) }
        assertEquals(4, data.schema)
        assertEquals(10, data.key.value)
        assertEquals(3, data.signature.value)
        assertEquals("color", data.changeEvents[0].type)
        assertEquals(ChangeEvent.SIGN, data.changeEvents[1].type)

        val again = JsonParser.parseString(data.toJson()).asJsonObject
        assertEquals(4, again.get("schema").asInt)
        assertEquals(1, again.getAsJsonObject("future").get("n").asInt)
        assertEquals("x", again.getAsJsonObject("info").get("subtitle").asString)
        assertEquals("lift", again.getAsJsonObject("score").get("feel").asString)
        assertEquals(false, again.getAsJsonArray("voices")[0].asJsonObject.get("mute").asBoolean)
        val change = again.getAsJsonArray("changes")[0].asJsonObject
        assertEquals("color", change.get("type").asString)
        assertEquals(true, change.get("lane").asBoolean)
        assertEquals("T - S", SolfaJson.displayName(data.toJson()))
    }

    @Test
    fun missingSchemaOrBadVoicesAreRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            PartitionData().fromJson("""{"voices":[{"notes":["d"]}]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PartitionData().fromJson("""{"schema":1,"voices":"S"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PartitionData().fromJson("""{"schema":1,"voices":[{"notes":[1]}]}""")
        }
    }

    @Test
    fun defaultsFillMissingScoreAndVoiceIds() {
        val data = PartitionData().apply {
            fromJson("""{"schema":2,"voices":[{"notes":["d"]},{"notes":["r"]}]}""")
        }
        assertEquals(2, data.schema)
        assertEquals(0, data.key.value)
        assertEquals(4, data.signature.value)
        assertEquals(120, data.tempo)
        assertEquals(false, data.swing.value)
        assertEquals("", data.lyrics.value)
        assertEquals(listOf("S", "A"), data.voices)
        assertEquals(VoiceTimbre.PIANO_ID, data.timbres.value!![0].soundFontId)
        assertNull(data.timbres.value!![0].legacyIndex)
    }

    @Test
    fun trimKeepsCellsThroughTheLastChange() {
        val data = PartitionData()
        data.voices = mutableListOf("S")
        data.notes = mutableListOf(mutableListOf("d", "", "", "", "", "", ""))
        data.changeEvents.add(ChangeEvent(5, ChangeEvent.SIGN, "Fin"))

        val again = PartitionData().apply { fromJson(data.toJson()) }

        assertEquals(listOf("d", "", "", "", "", ""), again.notes[0])
        assertEquals(5, again.changeEvents[0].position)
        assertEquals(ChangeEvent.SIGN, again.changeEvents[0].type)
        assertEquals("Fin", again.changeEvents[0].value)
    }

    @Test
    fun drmTextMatchesJson() {
        val raw = """
            VERSION:2; P_SWING:1; CHANGES:0_MD_Bb,4_MV_90,4_SI_Fin,8_DL_DC,2_VEL_mf; INSTR:13,piano@1.2; VOICES:S,A; P_TITLE:Ho Anao; P_AUT:A; P_COMP:C; P_DATE:2020; P_SINGER:Mozart; P_KEY:10; P_SIGNATURE:3; P_TEMPO:148; __!!__d:r:m.f:__!!__s:l:__!!__line one;line two
        """.trimIndent()
        val original = PartitionData().apply { parseRawString(raw) }
        val again = PartitionData().apply { fromJson(original.toJson()) }

        assertEquals("2", again.version)
        assertEquals(10, again.key.value)
        assertEquals(3, again.signature.value)
        assertEquals(148, again.tempo)
        assertEquals(true, again.swing.value)
        assertEquals("Ho Anao", again.songInfo.title.value)
        assertEquals("Mozart", again.songInfo.singer.value)
        assertEquals("A", again.songInfo.author.value)
        assertEquals("line one\nline two", again.lyrics.value)
        assertEquals("d", again.notes[0][0])
        assertEquals("r", again.notes[0][1])
        assertEquals("m.f", again.notes[0][2])
        assertEquals("s", again.notes[1][0])
        assertEquals("l", again.notes[1][1])
        assertEquals(9, again.notes[0].size)
        assertEquals(13, again.timbres.value!![0].legacyIndex)
        assertEquals("piano", again.timbres.value!![1].soundFontId)
        assertEquals(1, again.timbres.value!![1].bank)
        assertEquals(2, again.timbres.value!![1].program)
        assertEquals(ChangeEvent.MOD, again.changeEvents[0].type)
        assertEquals("Bb", again.changeEvents[0].value)
        assertEquals(ChangeEvent.TEMPO, again.changeEvents[1].type)
        assertEquals("90", again.changeEvents[1].value)
        assertEquals(ChangeEvent.SIGN, again.changeEvents[2].type)
        assertEquals("Fin", again.changeEvents[2].value)
        assertEquals(ChangeEvent.DAL, again.changeEvents[3].type)
        assertEquals("DC", again.changeEvents[3].value)
        assertEquals(ChangeEvent.VELOCITY, again.changeEvents[4].type)
        assertEquals("mf", again.changeEvents[4].value)
        assertEquals(original.notes.take(original.voices.size), again.notes)
    }

    @Test
    fun demoFileAgreesWithJson() {
        val demo = listOf(
            File("src/main/assets/demo/Joy to the world.drm"),
            File("app/src/main/assets/demo/Joy to the world.drm"),
        ).first { it.exists() }
        val original = PartitionData().apply { parseRawString(demo.readText()) }
        val json = original.toJson()
        val again = PartitionData().apply { fromJson(json) }
        val parsed = JsonParser.parseString(json).asJsonObject

        assertEquals("D", parsed.getAsJsonObject("score").get("key").asString)
        assertEquals("Joy to the world", again.songInfo.title.value)
        assertEquals(2, again.key.value)
        assertEquals(100, again.tempo)
        assertEquals(4, again.signature.value)
        assertEquals(false, again.swing.value)
        assertEquals("", again.lyrics.value)
        assertEquals(4, again.voices.size)
        again.timbres.value!!.forEach { assertEquals(0, it.legacyIndex) }
        assertEquals(original.notes, again.notes)
        assertEquals(original.changeEvents.size, again.changeEvents.size)
    }
}
