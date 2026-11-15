package mg.maniry.doremii.omr

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import mg.maniry.doremii.editor.partition.PartitionData

class SolfaScanTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun clustersRowsAndDropsLyrics() {
        val music = listOf(sym("d", 10f, 10f), sym(":", 30f, 10f), sym("r", 50f, 12f))
        val lyric = "A-le-lu-ia".mapIndexed { index, char ->
            sym(char.toString(), index * 18f, 80f)
        }
        val hyphens = listOf(sym("-", 10f, 150f), sym("-", 30f, 152f), sym("-", 50f, 149f))
        val rows = SolfaSpatialParser.clusterRows(music + lyric + hyphens)
        assertEquals(3, rows.size)
        assertTrue(SolfaSpatialParser.isMusicRow(rows[0]))
        assertFalse(SolfaSpatialParser.isMusicRow(rows[1]))
        assertFalse(SolfaSpatialParser.isMusicRow(rows[2]))
    }

    @Test
    fun sharesDelimitersAcrossVoices() {
        val symbols = mutableListOf(bar(0f), bar(400f), sym("Do=G", 40f, -30f, w = 40f))
        symbols += voice("S", 22f, listOf(30f to "d", 120f to "r", 230f to "m", 330f to "f"), listOf(80f, 180f, 280f))
        symbols += voice("A", 58f, listOf(40f to "d", 90f to "r", 250f to "m", 340f to "f"), emptyList())
        val parsed = SolfaSpatialParser.parse(symbols, 4)
        assertEquals(7, parsed.keyIndex)
        assertEquals(listOf("S", "A"), parsed.voices.map { it.id })
        assertEquals(listOf("d", "r", "m", "f"), parsed.voices[0].cells)
        assertEquals(listOf("d", "r", "m", "f"), parsed.voices[1].cells)

        val three = mutableListOf(bar(0f, bottom = 80f), bar(300f, bottom = 80f))
        three += voice("S", 20f, listOf(40f to "d", 140f to "r", 240f to "m"), listOf(90f, 190f))
        three += voice("A", 52f, listOf(70f to "d", 95f to "r", 240f to "m"), emptyList())
        val triple = SolfaSpatialParser.parse(three, 3)
        assertEquals(listOf("d", "r", "m"), triple.voices[0].cells)
        assertEquals(listOf("d", "r", "m"), triple.voices[1].cells)
    }

    @Test
    fun notesWithoutDelimitersStayInsideTheBar() {
        val spread = SolfaSpatialParser.parse(
            listOf(bar(0f), bar(400f)) + notes(20f, listOf(50f to "d", 150f to "r", 250f to "m", 350f to "f")),
            4,
        ).voices.single().cells
        assertEquals(listOf("d", "r", "m", "f"), spread)

        val shared = SolfaSpatialParser.parse(
            listOf(bar(0f), bar(400f)) + notes(20f, listOf(40f to "d", 70f to "r")),
            4,
        ).voices.single().cells
        assertEquals(listOf("d.r", "", "", ""), shared)

        val six = SolfaSpatialParser.parse(
            listOf(bar(0f), bar(400f)) + notes(
                20f,
                listOf(40f to "d", 80f to "r", 140f to "m", 180f to "f", 260f to "s", 340f to "l"),
            ),
            4,
        ).voices.single().cells
        assertEquals(listOf("d.r", "m.f", "s", "l"), six)
    }

    @Test
    fun extraDelimitersDoNotOverflowTheNextMeasure() {
        val symbols = mutableListOf(bar(0f), bar(400f), bar(800f))
        val pitches = listOf(30f to "d", 90f to "r", 150f to "m", 210f to "f", 270f to "s", 330f to "l")
        symbols += notes(20f, pitches)
        pitches.zipWithNext { left, right ->
            symbols += sym(";", (left.first + right.first) / 2f, 20f)
        }
        symbols += sym("t", 450f, 20f)
        val cells = SolfaSpatialParser.parse(symbols, 4).voices.single().cells
        assertEquals(8, cells.size)
        assertEquals("t", cells[4])
        assertTrue(cells.take(4).any { it.contains('.') })
        assertFalse(cells.take(4).any { it.contains("t") })
        assertEquals(listOf("", "", ""), cells.drop(5))
    }

    @Test
    fun octaveMarkIsNotABaselineDot() {
        val symbols = mutableListOf(bar(0f), bar(400f))
        symbols += sym("d", 30f, 30f)
        symbols += sym("'", 42f, 8f, w = 8f, h = 8f)
        symbols += sym("r", 140f, 30f)
        symbols += sym(".", 156f, 30f, w = 6f, h = 6f)
        symbols += sym("m", 175f, 30f)
        symbols += sym("s", 260f, 30f)
        symbols += sym(",", 274f, 46f, w = 8f, h = 8f)
        val cells = SolfaSpatialParser.parse(symbols, 4).voices.single().cells
        assertEquals(listOf("d1", "r.m", "s-1", ""), cells)
    }

    @Test
    fun samplesGapsAndBarlinesOnABinaryImage() {
        val colon = MutableInk(12, 24)
        for (y in 3..6) colon.on(6, y)
        for (y in 14..17) colon.on(6, y)
        assertEquals(GapMark.COLON, GapSampler.sample(colon, 6, 0, 24, 12))

        val dot = MutableInk(12, 24)
        for (y in 10..12) dot.on(6, y)
        assertEquals(GapMark.DOT, GapSampler.sample(dot, 6, 0, 24, 11))

        val dash = MutableInk(16, 20)
        for (x in 2..12) dash.on(x, 10)
        assertEquals(GapMark.DASH, GapSampler.sample(dash, 7, 0, 20, 10))

        val bars = MutableInk(20, 40)
        for (y in 0 until 40) bars.on(4, y)
        for (y in 10..16) bars.on(12, y)
        val found = BarlineDetector.detect(bars, 0, 40)
        assertEquals(1, found.size)
        assertEquals(4f, found.single())
    }

    @Test
    fun cellMappingRoundTripsIntoJson() {
        assertEquals("d", SolfaCellMapper.toCell(listOf(MappedNote("d"))))
        assertEquals("d1", SolfaCellMapper.toCell(listOf(MappedNote("d", 1))))
        assertEquals("d-1", SolfaCellMapper.toCell(listOf(MappedNote("d", -1))))
        assertEquals("d.r", SolfaCellMapper.toCell(listOf(MappedNote("d"), MappedNote("r"))))
        assertEquals("", SolfaCellMapper.toCell(emptyList()))
        assertEquals(
            "d : r | m : d.r",
            PrintedSolfaFormatter.format(listOf("d", "r", "m", "d.r"), 4).text,
        )
        assertEquals(
            listOf("d", "r", "m", "f", "s", "l", "t", "d"),
            PrintedSolfaFormatter.parse("d : r | m : f | s : l | t : d", 4),
        )
        assertEquals("d : r : m", PrintedSolfaFormatter.format(listOf("d", "r", "m"), 3).text)
        assertEquals(
            listOf("d", "d1", "d-1", "d.r"),
            PrintedSolfaFormatter.parse(
                PrintedSolfaFormatter.format(listOf("d", "d1", "d-1", "d.r"), 4).text,
                4,
            ),
        )

        val data = ScanScoreBuilder.build(
            voices = listOf(
                ParsedVoice("S", listOf("d", "d1", "d-1", "d.r"), emptyList()),
                ParsedVoice("A", listOf("d", "", "s", "l"), emptyList()),
            ),
            signature = 4,
            keyIndex = 7,
            title = "scan",
        )
        val json = data.toJson()
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals(1, root.get("schema").asInt)
        assertEquals("G", root.getAsJsonObject("score").get("key").asString)
        assertEquals(4, root.getAsJsonObject("score").get("signature").asInt)
        assertEquals(120, root.getAsJsonObject("score").get("tempo").asInt)
        assertFalse(root.getAsJsonObject("score").get("swing").asBoolean)
        assertEquals("", root.get("lyrics").asString)
        val written = root.getAsJsonArray("voices")
        assertTrue(written[0].asJsonObject.get("notes").isJsonArray)
        assertEquals("d", written[0].asJsonObject.getAsJsonArray("notes")[0].asString)
        val again = PartitionData().apply { fromJson(json) }
        assertEquals(listOf("d", "d1", "d-1", "d.r"), again.notes[0])
        assertEquals(listOf("d", "", "s", "l"), again.notes[1])
        assertEquals("G", JsonParser.parseString(again.toJson()).asJsonObject.getAsJsonObject("score").get("key").asString)
        assertNull(again.lyrics.value?.takeIf { it.isNotEmpty() })
    }

    private fun voice(
        id: String,
        y: Float,
        pitches: List<Pair<Float, String>>,
        colons: List<Float>,
    ): List<OcrSymbol> {
        val labelX = -36f
        return listOf(sym(id, labelX, y)) +
            notes(y, pitches) +
            colons.map { sym(":", it, y, w = 8f) }
    }

    private fun notes(y: Float, pitches: List<Pair<Float, String>>): List<OcrSymbol> {
        return pitches.map { (x, name) -> sym(name, x, y) }
    }

    private fun sym(text: String, x: Float, y: Float, w: Float = 18f, h: Float = 20f) =
        OcrSymbol(text, x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f)

    private fun bar(x: Float, bottom: Float = 90f) =
        OcrSymbol("|", x - 2f, 0f, x + 2f, bottom)
}

private class MutableInk(override val width: Int, override val height: Int) : InkImage {
    private val dark = Array(height) { BooleanArray(width) }

    fun on(x: Int, y: Int) {
        if (x in 0 until width && y in 0 until height) dark[y][x] = true
    }

    override fun isDark(x: Int, y: Int): Boolean {
        return x in 0 until width && y in 0 until height && dark[y][x]
    }
}
