package mg.maniry.doremii.editor.partition

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VoiceTimbreTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun legacyInstrumentIndexesStayIntegersUntilEdited() {
        val data = PartitionData()
        data.parseRawString("INSTR:2, 7; VOICES:S,A;")

        val timbres = data.timbres.value!!
        assertEquals(2, timbres[0].legacyIndex)
        assertEquals(VoiceTimbre.PIANO_ID, timbres[0].soundFontId)
        assertEquals(7, timbres[1].legacyIndex)
        assertTrue(data.toString().contains("INSTR:2,7;"))

        val (bank, program) = timbres[0].midiBankAndProgram()
        assertEquals(0, bank)
        assertEquals(InstrumentsList.list[2].program, program)
    }

    @Test
    fun selectedTimbresRoundTrip() {
        val data = PartitionData()
        data.parseRawString("VOICES:S,A;")
        data.setVoiceTimbre(0, VoiceTimbre("piano", 0, 0))
        data.setVoiceTimbre(1, VoiceTimbre("sf-fluid", 128, 5))

        val again = PartitionData()
        again.parseRawString(data.toString())
        val timbres = again.timbres.value!!

        assertNull(timbres[0].legacyIndex)
        assertEquals("piano", timbres[0].soundFontId)
        assertEquals(0, timbres[0].bank)
        assertEquals(0, timbres[0].program)
        assertEquals("sf-fluid", timbres[1].soundFontId)
        assertEquals(128, timbres[1].bank)
        assertEquals(5, timbres[1].program)
        val (bank, program) = timbres[1].midiBankAndProgram()
        assertEquals(128, bank)
        assertEquals(5, program)
    }
}
