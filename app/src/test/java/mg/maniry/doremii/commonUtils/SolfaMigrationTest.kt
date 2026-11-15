package mg.maniry.doremii.commonUtils

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.gson.JsonParser
import mg.maniry.doremii.editor.partition.PartitionData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

class SolfaMigrationTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var dir: java.io.File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("solfa").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun convertsDrmAndDeletesIt() {
        val drm = java.io.File(dir, "Ho Anao.drm")
        drm.writeText(
            "VERSION:1; P_SWING:0; CHANGES:4_SI_Fin; INSTR:13; VOICES:S; " +
                "P_TITLE:Ho Anao; P_SINGER:Mozart; P_KEY:10; P_SIGNATURE:4; P_TEMPO:148; " +
                "__!!__d:r:m:"
        )
        java.io.File(dir, "notes.html").writeText("<p>keep</p>")
        java.io.File(dir, "crash.drm.tmp").writeText("partial")

        FileManager.migrateDirectory(dir)

        val json = java.io.File(dir, "Ho Anao.doremi.json")
        assertTrue(json.exists())
        assertFalse(drm.exists())
        assertFalse(java.io.File(dir, "Ho Anao.doremi.json.tmp").exists())
        assertEquals("<p>keep</p>", java.io.File(dir, "notes.html").readText())
        assertEquals("partial", java.io.File(dir, "crash.drm.tmp").readText())

        val data = PartitionData().apply { fromJson(json.readText()) }
        assertEquals("Ho Anao", data.songInfo.title.value)
        assertEquals("Mozart", data.songInfo.singer.value)
        assertEquals(10, data.key.value)
        assertEquals(148, data.tempo)
        assertEquals("d", data.notes[0][0])
        assertEquals("r", data.notes[0][1])
        assertEquals("m", data.notes[0][2])
        assertEquals(5, data.notes[0].size)
        assertEquals(13, data.timbres.value!![0].legacyIndex)
        assertEquals("Fin", data.changeEvents[0].value)
    }

    @Test
    fun joinsLinesTheWayTheLegacyReaderDid() {
        java.io.File(dir, "Split.DRM").writeText(
            "P_KEY:0; VOICES:S; P_TEMPO:100; P_SIGNATURE:4; __!!__d:r\nm:"
        )

        FileManager.migrateDirectory(dir)

        val json = java.io.File(dir, "Split.doremi.json")
        assertTrue(json.exists())
        assertFalse(java.io.File(dir, "Split.DRM").exists())
        val data = PartitionData().apply { fromJson(json.readText()) }
        assertEquals(listOf("d", "rm"), data.notes[0])
        assertEquals(100, data.tempo)
    }

    @Test
    fun parseFailureLeavesTheDrmInPlace() {
        val drm = java.io.File(dir, "Broken.drm")
        drm.writeText("P_KEY:nope; VOICES:S; __!!__d:")

        FileManager.migrateDirectory(dir)

        assertTrue(drm.exists())
        assertEquals("P_KEY:nope; VOICES:S; __!!__d:", drm.readText())
        assertTrue(dir.listFiles { file -> file.name.endsWith(".doremi.json") }!!.isEmpty())
        assertTrue(dir.listFiles { file -> file.name.endsWith(".tmp") }!!.isEmpty())
    }

    @Test
    fun existingJsonIsNotOverwritten() {
        val existing = java.io.File(dir, "Song.doremi.json")
        existing.writeText("""{"schema":1,"keep":true}""")
        java.io.File(dir, "Song.drm").writeText(
            "P_KEY:0; VOICES:S; P_TITLE:Other; P_TEMPO:80; P_SIGNATURE:4; __!!__d:"
        )

        FileManager.migrateDirectory(dir)

        assertEquals("""{"schema":1,"keep":true}""", existing.readText())
        assertFalse(java.io.File(dir, "Song.drm").exists())
        val sibling = java.io.File(dir, "Song (1).doremi.json")
        assertTrue(sibling.exists())
        val parsed = JsonParser.parseString(sibling.readText()).asJsonObject
        assertEquals("Other", parsed.getAsJsonObject("info").get("title").asString)
        assertEquals(1, parsed.get("schema").asInt)
    }
}
