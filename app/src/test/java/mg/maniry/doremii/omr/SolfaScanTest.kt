package mg.maniry.doremii.omr

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import mg.maniry.doremii.editor.partition.PartitionData

class SolfaScanTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun keepsStackedVoicesApartWhenBoxesOverlap() {
        val soprano = notes(20f, listOf(40f to "d", 140f to "r", 240f to "m", 340f to "f"))
        val alto = notes(34f, listOf(40f to "s", 140f to "l", 240f to "t", 340f to "d"))
        val rows = SolfaSpatialParser.clusterRows(soprano + alto)
        assertEquals(2, rows.size)

        val symbols = mutableListOf(bar(0f, bottom = 70f), bar(400f, bottom = 70f))
        symbols += soprano
        symbols += alto
        val parsed = SolfaSpatialParser.parse(symbols, 4)
        assertEquals(listOf("d", "r", "m", "f"), parsed.voices[0].cells)
        assertEquals(listOf("s", "l", "t", "d"), parsed.voices[1].cells)
    }

    @Test
    fun continuesTheSameFourVoicesOnTheNextSystem() {
        val symbols = mutableListOf<OcrSymbol>()
        symbols += system(0f, 0f)
        symbols += system(0f, 160f)
        val parsed = SolfaSpatialParser.parse(symbols, 4)
        assertEquals(listOf("S", "A", "T", "B"), parsed.voices.map { it.id })
        assertEquals(listOf("d", "r", "d", "r"), parsed.voices[0].cells.filter { it.isNotEmpty() })
        assertEquals(listOf("m", "f", "m", "f"), parsed.voices[1].cells.filter { it.isNotEmpty() })
    }

    @Test
    fun misreadColonIsABeatNotAnAccidental() {
        val symbols = mutableListOf(bar(0f), bar(400f))
        symbols += listOf(
            sym("d", 40f, 20f),
            sym("i", 90f, 20f),
            sym("r", 150f, 20f),
            sym("m", 250f, 20f),
            sym("f", 340f, 20f),
        )
        val cells = SolfaSpatialParser.parse(symbols, 4).voices.single().cells
        assertEquals(listOf("d", "r", "m", "f"), cells)
    }

    @Test
    fun commaBesideANoteIsTheLowerOctave() {
        val symbols = mutableListOf(bar(0f), bar(400f))
        symbols += sym("s", 40f, 30f)
        symbols += sym(",", 54f, 32f, w = 8f, h = 12f)
        symbols += sym("d", 160f, 30f)
        symbols += sym("r", 260f, 30f)
        symbols += sym("m", 350f, 30f)
        val cells = SolfaSpatialParser.parse(symbols, 4).voices.single().cells
        assertEquals("s-1", cells[0])
    }

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

        val gapped = MutableInk(12, 40)
        for (y in 0 until 18) gapped.on(4, y)
        for (y in 20 until 40) gapped.on(4, y)
        assertTrue(BarlineDetector.detect(gapped, 0, 40).isEmpty())

        val gray = MutableInk(12, 40)
        for (y in 0 until 40) gray.on(4, y, shade = 90)
        assertTrue(BarlineDetector.detect(gray, 0, 40).isEmpty())
    }

    @Test
    fun beatMarkersAreAlignedColonsPipesAndContinuousStrokes() {
        val voices = (0 until 4).map { index ->
            row(20f + index * 30f, listOf(40f to "d", 120f to ":", 200f to "|", 280f to "r"))
        }
        val box = MeasureDelimiters.open(voices, emptyList(), 0f, 400f, 0f, 140f)
        val xs = box.markers.map { it.x }.sorted()
        assertEquals(2, xs.size)
        assertTrue(abs(xs[0] - 120f) < 8f)
        assertTrue(abs(xs[1] - 200f) < 8f)
        assertTrue(box.markers.all { it.top == 0f && it.bottom == 140f })

        val lone = MeasureDelimiters.open(
            listOf(
                row(20f, listOf(80f to "l")),
                row(60f, listOf(80f to "d", 240f to "r")),
            ),
            emptyList(),
            0f,
            400f,
            0f,
            90f,
        )
        assertTrue(lone.markers.isEmpty())

        val tall = MeasureDelimiters.open(
            listOf(row(20f, listOf(40f to "d", 200f to "r"))),
            listOf(VerticalStroke(120f, 0, 100, 1f)),
            0f,
            280f,
            0f,
            100f,
        )
        assertEquals(1, tall.markers.size)
        assertTrue(abs(tall.markers.single().x - 120f) < 8f)
        assertEquals(0f, tall.markers.single().top)
        assertEquals(100f, tall.markers.single().bottom)

        val shortVoices = (0 until 4).map { index ->
            row(16f + index * 28f, listOf(40f to "d", 200f to "r"))
        }
        val short = MeasureDelimiters.open(
            shortVoices,
            listOf(VerticalStroke(120f, 10, 30, 1f)),
            0f,
            400f,
            0f,
            120f,
        )
        assertTrue(short.markers.none { abs(it.x - 120f) < 8f })

        val musical = DebugNoteBox(10f, 10f, 40f, 30f, 0, "S", musical = true, label = "4/4 musical")
        val lyric = DebugNoteBox(10f, 40f, 80f, 60f, 0, "", musical = false)
        val window = DebugWindow(8f, 0f, 392f, 140f)
        val shown = finish(
            box,
            4,
            notes = listOf(musical, lyric),
            glyphs = listOf(DebugGlyph("d", 10f, 10f, 20f, 30f)),
            windows = listOf(window),
        ).parse.debug
        assertEquals(listOf(musical), shown.notes)
        assertTrue(shown.measures.isEmpty())
        assertTrue(shown.glyphs.isEmpty())
        assertEquals(listOf(window), shown.windows)
        assertEquals(box.markers, shown.beats)
    }

    @Test
    fun alignedPipeLookalikesAreBeatDelimiters() {
        val voices = (0 until 4).map { index ->
            row(20f + index * 30f, listOf(40f to "d", 120f to "l", 200f to "r"))
        }
        val box = MeasureDelimiters.open(voices, emptyList(), 0f, 320f, 0f, 140f)
        val column = box.columns.single()
        assertEquals(DelimiterKind.BEAT, column.kind)
        assertTrue(abs(column.x - 120f) < 8f)
        val soprano = finish(box, 2).parse.voices[0].cells
        assertEquals(listOf("d", "r"), soprano)
    }

    @Test
    fun loneLIsANoteWhenTheOtherVoicesDoNotShareItsX() {
        val box = MeasureDelimiters.open(
            listOf(
                row(20f, listOf(80f to "l")),
                row(60f, listOf(80f to "d", 240f to "r")),
            ),
            emptyList(),
            0f,
            400f,
            0f,
            90f,
        )
        assertTrue(box.columns.none { abs(it.x - 80f) < 12f })
        val cells = finish(box, 4).parse.voices[0].cells
        assertEquals("l", cells.first { it.isNotEmpty() })
    }

    @Test
    fun singleTallStrokeIsAMeasureAndPerVoicePipesAreBeats() {
        val voices = (0 until 4).map { index ->
            row(16f + index * 28f, listOf(40f to "d", 160f to "|", 280f to "r"))
        }
        val shorts = (0 until 4).map { index ->
            val top = 8 + index * 28
            VerticalStroke(160f, top, top + 16, 1f)
        }
        val box = MeasureDelimiters.open(
            voices,
            shorts + VerticalStroke(320f, 0, 120, 1f),
            0f,
            400f,
            0f,
            120f,
        )
        assertEquals(DelimiterKind.BEAT, box.columns.single { abs(it.x - 160f) < 8f }.kind)
        assertEquals(DelimiterKind.MEASURE, box.columns.single { abs(it.x - 320f) < 8f }.kind)

        val alone = MeasureDelimiters.open(
            listOf(row(20f, listOf(40f to "d", 120f to "|", 200f to "r"))),
            listOf(VerticalStroke(120f, 10, 30, 1f)),
            0f,
            280f,
            0f,
            40f,
        )
        assertEquals(DelimiterKind.BEAT, alone.columns.single().kind)

        val six = (0 until 6).map { index ->
            row(16f + index * 20f, listOf(40f to "d", 120f to "|"))
        }
        val sixStrokes = (0 until 6).map { index ->
            val top = 8 + index * 20
            VerticalStroke(120f, top, top + 12, 1f)
        }
        val many = MeasureDelimiters.open(
            six,
            sixStrokes + VerticalStroke(200f, 0, 130, 1f),
            0f,
            280f,
            0f,
            130f,
        )
        assertEquals(DelimiterKind.BEAT, many.columns.single { abs(it.x - 120f) < 6f }.kind)
        assertEquals(DelimiterKind.MEASURE, many.columns.single { abs(it.x - 200f) < 6f }.kind)
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

    private fun system(x: Float, y: Float): List<OcrSymbol> {
        val pitches = listOf(40f to "d", 140f to "r")
        return listOf(bar(x, y, y + 90f), bar(x + 220f, y, y + 90f)) +
            voice("S", y + 16f, pitches.map { (at, name) -> (x + at) to name }, listOf(x + 90f)) +
            voice("A", y + 36f, pitches.map { (at, _) -> (x + at) to if (at < 100f) "m" else "f" }, emptyList()) +
            voice("T", y + 56f, pitches.map { (at, name) -> (x + at) to name }, emptyList()) +
            voice("B", y + 76f, pitches.map { (at, _) -> (x + at) to "s" }, emptyList())
    }

    @Test
    fun debugBoxesEachNoteAndMarksMeasureWalls() {
        val parsed = SolfaSpatialParser.parse(
            listOf(bar(0f), bar(400f)) + notes(20f, listOf(50f to "d", 150f to "r", 250f to "m", 350f to "f")),
            4,
        )
        val boxes = parsed.debug.notes.sortedBy { it.left }
        assertEquals(4, boxes.size)
        assertTrue(boxes.all { it.voiceId == "S" && it.voiceIndex == 0 })
        assertTrue(boxes.zipWithNext().all { (left, right) -> left.right <= right.left + 1f })
        assertEquals(2, parsed.debug.measures.size)
        assertTrue(parsed.debug.measures.zipWithNext().all { (left, right) -> right.x > left.x })

        val duet = SolfaSpatialParser.parse(
            listOf(bar(0f, bottom = 80f), bar(400f, bottom = 80f)) +
                voice("S", 22f, listOf(50f to "d", 150f to "r", 250f to "m", 350f to "f"), emptyList()) +
                voice("A", 58f, listOf(60f to "m", 160f to "f", 260f to "s", 340f to "l"), emptyList()),
            4,
        )
        assertEquals(setOf(0, 1), duet.debug.notes.map { it.voiceIndex }.toSet())
        assertEquals(setOf("S", "A"), duet.debug.notes.map { it.voiceId }.toSet())
        assertTrue(duet.debug.notes.count { it.voiceId == "S" } == 4)
        assertTrue(duet.debug.notes.count { it.voiceId == "A" } == 4)
    }

    @Test
    fun debugMarksBeatDivisionsFromSemicolonsColonsAndPipes() {
        val symbols = mutableListOf(bar(0f, bottom = 80f), bar(400f, bottom = 80f))
        symbols += voice(
            "S",
            22f,
            listOf(50f to "d", 150f to "r", 250f to "m", 350f to "f"),
            emptyList(),
        )
        symbols += sym(";", 100f, 22f, w = 8f)
        symbols += sym("|", 200f, 22f, w = 4f, h = 20f)
        symbols += sym(":", 300f, 22f, w = 8f)
        symbols += voice(
            "A",
            58f,
            listOf(50f to "m", 150f to "f", 250f to "s", 350f to "l"),
            listOf(100f, 300f),
        )
        val parsed = SolfaSpatialParser.parse(symbols, 4)
        assertEquals(listOf("d", "r", "m", "f"), parsed.voices[0].cells)
        assertEquals(2, parsed.debug.measures.size)
        val beats = parsed.debug.beats.map { it.x }.sorted()
        assertEquals(3, beats.size)
        assertTrue(beats.zip(listOf(100f, 200f, 300f)).all { (actual, expected) -> kotlin.math.abs(actual - expected) < 12f })
        assertTrue(parsed.debug.beats.all { it.top <= 22f && it.bottom >= 58f })

        val tallPipe = mutableListOf(bar(0f, bottom = 80f), bar(200f, bottom = 80f), bar(400f, bottom = 80f))
        tallPipe += notes(22f, listOf(50f to "d", 150f to "r", 250f to "m", 350f to "f"))
        val split = SolfaSpatialParser.parse(tallPipe, 4)
        assertEquals(2, split.debug.measures.size)
        assertEquals(1, split.debug.beats.size)
        assertTrue(kotlin.math.abs(split.debug.beats.single().x - 200f) < 12f)
        assertEquals(listOf("d", "r", "m", "f"), split.voices.single().cells)
    }

    @Test
    fun quarterTurnMapsParserSpaceBackOntoThePhoto() {
        val point = photoPoint(x = 30f, y = 8f, photoHeight = 40f, quarterTurn = true)
        assertEquals(8f, point.first)
        assertEquals(10f, point.second)
        val rect = photoRect(30f, 8f, 38f, 18f, photoHeight = 40f, quarterTurn = true)
        assertEquals(8f, rect.left)
        assertEquals(2f, rect.top)
        assertEquals(18f, rect.right)
        assertEquals(10f, rect.bottom)
        val same = photoRect(1f, 2f, 3f, 4f, photoHeight = 40f, quarterTurn = false)
        assertEquals(PhotoRect(1f, 2f, 3f, 4f), same)
    }

    @Test
    fun majoritySplitsALongSpanFillsAShortOneAndMergesHalves() {
        val fours = List(2) { index ->
            val origin = index * 1000f
            colonMeasure(origin, 400f, listOf(50f to "d", 150f to "r", 250f to "m", 350f to "f"), listOf(100f, 200f, 300f))
        }
        val longNotes = (0 until 8).map { beat -> (50f + beat * 100f) to "d" }
        val longCuts = (1 until 8).map { it * 100f }
        val longBox = colonMeasure(0f, 800f, longNotes, longCuts)
        val shortBox = colonMeasure(0f, 400f, listOf(40f to "s", 280f to "t"), listOf(200f))
        val finished = MeasureDelimiters.finish(ScanDraft(fours + longBox + shortBox, emptyList(), emptyList(), emptyList()), null)
        assertEquals(4, finished.signature)
        val soprano = finished.parse.voices[0]
        assertEquals(20, soprano.cells.size)
        assertEquals(listOf("d", "r", "m", "f", "d", "r", "m", "f"), soprano.cells.take(8))
        assertEquals(List(8) { "d" }, soprano.cells.subList(8, 16))
        assertEquals(listOf("s", "", "t", ""), soprano.cells.takeLast(4))
        assertEquals(listOf(16, 18), soprano.inferredBeats)
        assertTrue((0..15).none { it in soprano.inferredBeats })

        val halves = MeasureDelimiters.open(
            listOf(0f, 40f).map { y ->
                row(
                    y + 20f,
                    listOf(50f to "d", 100f to ":", 150f to "r", 250f to "m", 300f to ":", 350f to "f"),
                )
            },
            listOf(VerticalStroke(200f, 0, 80, 1f)),
            0f,
            400f,
            0f,
            80f,
        )
        assertEquals(DelimiterKind.MEASURE, halves.columns.single { abs(it.x - 200f) < 4f }.kind)
        val merged = MeasureDelimiters.finish(
            ScanDraft(fours + halves, emptyList(), emptyList(), emptyList()),
            null,
        )
        assertEquals(4, merged.signature)
        assertEquals(listOf("d", "r", "m", "f"), merged.parse.voices[0].cells.takeLast(4))
        assertTrue(merged.parse.voices[0].inferredBeats.none { it >= 8 })
    }

    @Test
    fun ocrTextMapsToSolfaCells() {
        assertEquals("", SolfaCellMapper.fromOcrText("hello"))
        assertEquals("-", SolfaCellMapper.fromOcrText("-"))
        assertEquals("d", SolfaCellMapper.fromOcrText("d"))
        assertEquals("d1", SolfaCellMapper.fromOcrText("d'"))
        assertEquals("d-1", SolfaCellMapper.fromOcrText("d,"))
        assertEquals("di", SolfaCellMapper.fromOcrText("di"))
        assertEquals("d.r", SolfaCellMapper.fromOcrText("d.r"))
        assertEquals("d-1.r", SolfaCellMapper.fromOcrText("d,r"))
        assertEquals("l", SolfaCellMapper.fromOcrText("l"))
        assertEquals("l-1.s-1", SolfaCellMapper.fromOcrText("l,s,"))
        assertEquals("t-1.t-1,r", SolfaCellMapper.fromOcrText("t,.t,r"))
        assertEquals("d.d,d", SolfaCellMapper.fromOcrText("d.dd"))
        assertEquals("d.d", SolfaCellMapper.fromOcrText("dd"))
        assertEquals("d.d.d", SolfaCellMapper.fromOcrText("ddd"))
        assertEquals("d,d.d,d", SolfaCellMapper.fromOcrText("dddd"))
        assertEquals("d,d.d,d.d,d", SolfaCellMapper.fromOcrText("dddddd"))
        assertEquals("d.d.d.d.d", SolfaCellMapper.fromOcrText("ddddd"))
        assertEquals("m.,f", SolfaCellMapper.fromOcrText("m.,f"))
        assertEquals("m.,f", SolfaCellMapper.fromOcrText("m;f"))
        assertEquals("f-1.s-1", SolfaCellMapper.fromOcrText("f,.s,"))
        assertEquals("d.", SolfaCellMapper.fromOcrText("d."))
        assertEquals(".d", SolfaCellMapper.fromOcrText(".d"))
        assertEquals("-.r", SolfaCellMapper.fromOcrText("-.r"))
        assertEquals("d2", SolfaCellMapper.fromOcrText("d''"))
        assertEquals("m-1,f.,", SolfaCellMapper.fromOcrText("m,f.,"))
        assertEquals("t-1.,t-1", SolfaCellMapper.fromOcrText("t,.,t,"))
        assertEquals(".s-1,s-1", SolfaCellMapper.fromOcrText(".s,s,"))
        assertEquals("s-1.", SolfaCellMapper.fromOcrText("s,."))
        assertEquals("-.,s-1", SolfaCellMapper.fromOcrText("-.,s,"))
        assertEquals("fi-1.fi-1,fi-1", SolfaCellMapper.fromOcrText("fi,.fi,fi,"))
    }

    @Test
    fun stackedSystemsStaySplitAcrossALyricGap() {
        val ink = MutableInk(430, 260)
        drawStaff(ink, 16, 78)
        block(ink, 70, 100, 18, 12)
        block(ink, 180, 100, 18, 12)
        block(ink, 280, 100, 18, 12)
        drawStaff(ink, 150, 212)
        assertNull(PageRectifier.fitQuad(ink))
        val systems = SolfaGridDetector.detect(ink, 4).systems
        assertEquals(2, systems.size)
        val upper = systems[0]
        val lower = systems[1]
        assertEquals(2, upper.tallLines.size)
        assertEquals(2, lower.tallLines.size)
        assertEquals(16, upper.top)
        assertEquals(78, upper.bottom)
        assertEquals(150, lower.top)
        assertEquals(212, lower.bottom)
        assertTrue(abs(upper.tallLines[0] - 24.5f) < 2f)
        assertTrue(abs(upper.tallLines[1] - 400.5f) < 2f)
        assertTrue(abs(lower.tallLines[0] - 24.5f) < 2f)
        assertTrue(abs(lower.tallLines[1] - 400.5f) < 2f)
        assertTrue(upper.bottom < 100)
        assertTrue(lower.top > 112)
    }

    @Test
    fun dropsRowsWhoseLeftLineMissesTheMajority() {
        val ink = MutableInk(430, 460)
        drawStaff(ink, 16, 78)
        drawStaff(ink, 110, 172)
        drawStaff(ink, 204, 266)
        vertical(ink, 200, 300, 348, 2)
        vertical(ink, 400, 300, 348, 2)
        block(ink, 240, 310, 16, 12)
        block(ink, 320, 310, 16, 12)
        vertical(ink, 24, 360, 460, 2)
        vertical(ink, 400, 360, 460, 2)
        block(ink, 80, 390, 16, 20)
        block(ink, 200, 390, 16, 20)
        val systems = SolfaGridDetector.detect(ink, 4).systems
        assertEquals(3, systems.size)
        assertEquals(listOf(16, 110, 204), systems.map { it.top })
        assertTrue(systems.all { abs(it.tallLines.first() - 24.5f) < 2f })
    }

    @Test
    fun convergingBarlinesWarpToVertical() {
        val ink = MutableInk(240, 160)
        stroke(ink, 70, 8, 28, 150)
        stroke(ink, 170, 8, 214, 150)
        val quad = PageRectifier.fitQuad(ink)
        assertNotNull(quad)
        assertTrue(abs(quad!!.topWidth - quad.bottomWidth) > 20f)
        val width = max(1, ((quad.topWidth + quad.bottomWidth) / 2f).roundToInt())
        val height = max(1, (quad.bottomLeft.y - quad.topLeft.y).roundToInt())
        val warped = PageRectifier.warp(ink, quad, width, height)
        assertTrue(longestRun(warped, 0 until warped.width / 4) > warped.height * 0.55f)
        assertTrue(longestRun(warped, warped.width * 3 / 4 until warped.width) > warped.height * 0.55f)
    }

    @Test
    fun rotationSearchUprightsTiltedBarlines() {
        val upright = MutableInk(140, 90)
        stroke(upright, 36, 6, 36, 84)
        stroke(upright, 104, 6, 104, 84)
        val tilted = PageRectifier.rotateInk(upright, 8f)
        val angle = PageRectifier.bestRotation(tilted)
        assertTrue("angle=$angle", abs(angle + 8f) <= 2f)
    }

    private fun drawStaff(ink: MutableInk, top: Int, bottom: Int) {
        val mid = top + (bottom - top) / 2
        block(ink, 70, top + 8, 12, 14)
        block(ink, 180, top + 8, 12, 14)
        block(ink, 300, top + 8, 12, 14)
        block(ink, 70, mid, 12, 14)
        block(ink, 180, mid, 12, 14)
        block(ink, 300, mid, 12, 14)
        vertical(ink, 24, top, bottom, 2)
        vertical(ink, 400, top, bottom, 2)
    }

    private fun finish(
        box: OpenBox,
        signature: Int?,
        notes: List<DebugNoteBox> = emptyList(),
        glyphs: List<DebugGlyph> = emptyList(),
        windows: List<DebugWindow> = emptyList(),
    ): FinishedScan {
        return MeasureDelimiters.finish(ScanDraft(listOf(box), notes, glyphs, windows), signature)
    }

    private fun colonMeasure(
        left: Float,
        width: Float,
        notes: List<Pair<Float, String>>,
        cuts: List<Float>,
    ): OpenBox {
        val voices = listOf(0f, 40f).map { y ->
            val glyphs = notes.map { (x, name) -> (left + x) to name } + cuts.map { left + it to ":" }
            row(y + 20f, glyphs)
        }
        return MeasureDelimiters.open(voices, emptyList(), left, left + width, 0f, 80f)
    }

    private fun row(y: Float, items: List<Pair<Float, String>>): List<OcrSymbol> {
        return items.map { (x, text) ->
            val width = if (text.length == 1 && text[0] in ":|lI1;") 8f else 18f
            sym(text, x, y, w = width)
        }
    }

    private fun block(ink: MutableInk, left: Int, top: Int, width: Int, height: Int) {
        for (y in top until top + height) {
            for (x in left until left + width) ink.on(x, y)
        }
    }

    private fun beatsOf(ink: MutableInk, lines: List<DebugNoteBox>): List<DelimiterColumn> {
        return BeatMarks.detect(
            ink,
            lines,
            BarlineDetector.strokes(ink),
            0f,
            ink.width.toFloat(),
            0f,
            ink.height.toFloat(),
        )
    }

    private fun noteLine(top: Float, bottom: Float): DebugNoteBox {
        return DebugNoteBox(0f, top, 220f, bottom, 0, "S", musical = true)
    }

    private fun drawBorders(ink: MutableInk) {
        vertical(ink, 2, 0, ink.height, 2)
        vertical(ink, ink.width - 4, 0, ink.height, 2)
    }

    private fun colon(ink: MutableInk, x: Int, top: Int, bottom: Int) {
        block(ink, x - 1, top + 2, 3, 4)
        block(ink, x - 1, top + (bottom - top) / 2 + 1, 3, 4)
    }

    private fun containedPipe(ink: MutableInk, x: Int, top: Int, bottom: Int) {
        vertical(ink, x - 1, top + 4, (top + 14).coerceAtMost(bottom - 2), 3)
    }

    private fun vertical(ink: MutableInk, x: Int, top: Int, bottom: Int, thickness: Int) {
        for (y in top until bottom) {
            for (dx in 0 until thickness) ink.on(x + dx, y)
        }
    }

    private fun stroke(ink: MutableInk, x0: Int, y0: Int, x1: Int, y1: Int) {
        val steps = max(abs(x1 - x0), abs(y1 - y0)).coerceAtLeast(1)
        for (step in 0..steps) {
            val x = x0 + (x1 - x0) * step / steps
            val y = y0 + (y1 - y0) * step / steps
            for (dy in -1..1) {
                for (dx in -1..1) ink.on(x + dx, y + dy)
            }
        }
    }

    private fun longestRun(ink: InkImage, columns: IntRange): Int {
        var best = 0
        for (x in columns) {
            var run = 0
            for (y in 0 until ink.height) {
                if (ink.isDark(x, y)) {
                    run++
                    if (run > best) best = run
                } else {
                    run = 0
                }
            }
        }
        return best
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

    private fun bar(x: Float, top: Float = 0f, bottom: Float = 90f) =
        OcrSymbol("|", x - 2f, top, x + 2f, bottom)

    @Test
    fun textRowsStaySplitWhenABarlineCrossesTheGap() {
        val projection = IntArray(80) { 4 }
        for (y in 12..28) projection[y] = 60
        for (y in 48..64) projection[y] = 55
        val bands = OcrBands.find(projection)
        assertEquals(2, bands.size)
        assertEquals(12, bands[0].first)
        assertEquals(28, bands[0].last)
        assertEquals(48, bands[1].first)
    }

    @Test
    fun octaveMarksJoinTheNoteLineBeneathThem() {
        val projection = IntArray(40) { 2 }
        for (y in 8..10) projection[y] = 14
        for (y in 13..28) projection[y] = 50
        val bands = OcrBands.find(projection)
        assertEquals(listOf(8..28), bands)
    }

    @Test
    fun aDashLineStaysSeparateFromTheVoiceBelow() {
        val projection = IntArray(50) { 2 }
        for (y in 8..12) projection[y] = 20
        for (y in 24..40) projection[y] = 50
        assertEquals(2, OcrBands.find(projection).size)
    }

    @Test
    fun recognizedGlyphsKeepSolfaMarksAndSplitRuns() {
        assertEquals("d:r|m'", OcrGlyphs.accept("d ： r │ m\u2032 c"))
        val split = OcrGlyphs.split(OcrSymbol("d:r", 0f, 0f, 30f, 10f))
        assertEquals(listOf("d", ":", "r"), split.map { it.text })
        assertEquals(0f, split.first().left)
        assertEquals(30f, split.last().right)
        assertTrue(OcrBands.resolutionDpi(20) < OcrBands.resolutionDpi(64))
    }

    @Test
    fun openCvBeatsNeedEveryVoiceAtTheSameX() {
        val lines = listOf(noteLine(10f, 30f), noteLine(40f, 60f), noteLine(70f, 90f))
        val colons = MutableInk(220, 120)
        drawBorders(colons)
        for (line in lines) colon(colons, 80, line.top.roundToInt(), line.bottom.roundToInt())
        val colonBeats = beatsOf(colons, lines)
        assertEquals(1, colonBeats.size)
        assertEquals(DelimiterKind.BEAT, colonBeats.single().kind)
        assertTrue(abs(colonBeats.single().x - 80f) < 4f)

        val shifted = MutableInk(220, 120)
        colon(shifted, 80, 10, 30)
        colon(shifted, 80, 40, 60)
        colon(shifted, 140, 70, 90)
        assertTrue(beatsOf(shifted, lines).isEmpty())

        val pipes = MutableInk(220, 120)
        for (line in lines) containedPipe(pipes, 80, line.top.roundToInt(), line.bottom.roundToInt())
        val pipeBeats = beatsOf(pipes, lines)
        assertEquals(listOf(DelimiterKind.BEAT), pipeBeats.map { it.kind })
        assertTrue(abs(pipeBeats.single().x - 80f) < 4f)

        val missingPipe = MutableInk(220, 120)
        containedPipe(missingPipe, 80, 10, 30)
        containedPipe(missingPipe, 80, 40, 60)
        assertTrue(beatsOf(missingPipe, lines).isEmpty())

        val full = MutableInk(220, 120)
        drawBorders(full)
        vertical(full, 100, 0, 120, 1)
        val fullBeats = beatsOf(full, lines)
        assertEquals(listOf(DelimiterKind.MEASURE), fullBeats.map { it.kind })
        assertTrue(abs(fullBeats.single().x - 100f) < 4f)

        val sharedLines = listOf(noteLine(16f, 36f), noteLine(48f, 68f), noteLine(80f, 100f))
        val shared = MutableInk(220, 130)
        vertical(shared, 90, 12, 72, 1)
        vertical(shared, 90, 48, 96, 1)
        val sharedBeats = beatsOf(shared, sharedLines)
        assertEquals(listOf(DelimiterKind.BEAT), sharedBeats.map { it.kind })
        assertTrue(abs(sharedBeats.single().x - 90f) < 4f)

        val stack = MutableInk(220, 120)
        for (line in lines) {
            vertical(stack, 80, line.top.roundToInt() + 3, line.bottom.roundToInt() - 3, 1)
        }
        assertEquals(listOf(DelimiterKind.BEAT), beatsOf(stack, lines).map { it.kind })

        val broken = MutableInk(220, 120)
        vertical(broken, 80, 13, 27, 1)
        vertical(broken, 80, 43, 57, 1)
        assertTrue(beatsOf(broken, lines).isEmpty())

        val several = MutableInk(220, 120)
        for (x in listOf(60, 120)) {
            for (line in lines) colon(several, x, line.top.roundToInt(), line.bottom.roundToInt())
        }
        containedPipe(several, 170, 10, 30)
        val severalBeats = beatsOf(several, lines)
        assertEquals(2, severalBeats.size)
        assertTrue(severalBeats.all { it.kind == DelimiterKind.BEAT })
        assertTrue(severalBeats.none { abs(it.x - 170f) < 8f })

        val voices = (0 until 3).map { index ->
            row(20f + index * 30f, listOf(40f to "d", 160f to "r"))
        }
        val opened = MeasureDelimiters.open(
            voices,
            BarlineDetector.strokes(shared),
            0f,
            220f,
            0f,
            130f,
            sharedBeats,
        )
        assertEquals(DelimiterKind.BEAT, opened.columns.single().kind)
        assertEquals(0f, opened.markers.single().top)
        assertEquals(130f, opened.markers.single().bottom)

        val withColon = (0 until 3).map { index ->
            row(20f + index * 30f, listOf(40f to "d", 80f to ":", 160f to "r"))
        }
        val merged = MeasureDelimiters.open(
            withColon,
            BarlineDetector.strokes(pipes),
            0f,
            220f,
            0f,
            120f,
            pipeBeats,
        )
        assertEquals(1, merged.columns.size)
        assertEquals(DelimiterKind.BEAT, merged.columns.single().kind)
    }
}

private class MutableInk(override val width: Int, override val height: Int) : InkImage {
    private val dark = Array(height) { BooleanArray(width) }
    private val levels = Array(height) { IntArray(width) { 255 } }

    fun on(x: Int, y: Int, shade: Int = 0) {
        if (x !in 0 until width || y !in 0 until height) return
        dark[y][x] = true
        levels[y][x] = shade
    }

    override fun isDark(x: Int, y: Int): Boolean {
        return x in 0 until width && y in 0 until height && dark[y][x]
    }

    override fun shade(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 255
        return levels[y][x]
    }
}
