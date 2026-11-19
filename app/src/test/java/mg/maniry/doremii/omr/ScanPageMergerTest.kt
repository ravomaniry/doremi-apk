package mg.maniry.doremii.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPageMergerTest {
    @Test
    fun pagesAppendByVoiceAndPadToTheFirstSignature() {
        val merged = ScanPageMerger.merge(
            pages = listOf(
                parse(voices("S" to listOf("d", "r"), "A" to listOf("m")), key = 2),
                parse(voices("S" to listOf("f"), "A" to listOf("s", "l"))),
            ),
            signatures = listOf(4, 3),
        )

        assertEquals(listOf("S", "A"), merged.voices.map { it.id })
        assertEquals(listOf("d", "r", "", "", "f", "", "", ""), merged.voices[0].cells)
        assertEquals(listOf("m", "", "", "", "s", "l", "", ""), merged.voices[1].cells)
        assertEquals(2, merged.keyIndex)
    }

    @Test
    fun aVoiceThatStartsLaterIsAlignedWithEarlierPages() {
        val merged = ScanPageMerger.merge(
            pages = listOf(
                parse(voices("S" to listOf("d", "r"))),
                parse(voices("T" to listOf("f"), "S" to listOf("m"))),
            ),
            signatures = listOf(4, 4),
        )

        assertEquals(listOf("S", "T"), merged.voices.map { it.id })
        assertEquals(listOf("d", "r", "", "", "m", "", "", ""), merged.voices[0].cells)
        assertEquals(listOf("", "", "", "", "f", "", "", ""), merged.voices[1].cells)
    }

    @Test
    fun inferredBeatsKeepTheirPlaceAfterEarlierPages() {
        val merged = ScanPageMerger.merge(
            pages = listOf(
                parse(voices("S" to listOf("d"))),
                parse(listOf(ParsedVoice("S", listOf("r", "m"), listOf(1)))),
            ),
            signatures = listOf(2, 2),
        )

        assertEquals(listOf(3), merged.voices.single().inferredBeats)
    }

    @Test
    fun anEmptyPageDoesNotOpenAMeasure() {
        val merged = ScanPageMerger.merge(
            pages = listOf(
                parse(voices("S" to listOf("d", "r"))),
                SolfaParse(emptyList(), null, empty = true),
                parse(voices("S" to listOf("m"))),
            ),
            signatures = listOf(4, 4, 4),
        )

        assertEquals(listOf("d", "r", "", "", "m", "", "", ""), merged.voices.single().cells)
    }

    @Test
    fun nothingRecognizedStaysEmpty() {
        val merged = ScanPageMerger.merge(
            pages = listOf(SolfaParse(emptyList(), null, empty = true)),
            signatures = listOf(4),
        )

        assertTrue(merged.empty)
        assertTrue(merged.voices.isEmpty())
    }

    private fun parse(voices: List<ParsedVoice>, key: Int? = null) =
        SolfaParse(voices, key, empty = false)

    private fun voices(vararg parts: Pair<String, List<String>>) =
        parts.map { (id, cells) -> ParsedVoice(id, cells, emptyList()) }
}
