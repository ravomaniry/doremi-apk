package mg.maniry.doremii.omr

import mg.maniry.doremii.editor.partition.PartitionData
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class MarkerKind { COLON, PIPE }

enum class CellInk { EMPTY, DASH, GLYPH }

data class CellKey(val left: Int, val top: Int, val right: Int, val bottom: Int)

data class GridCell(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val systemIndex: Int,
    val voiceIndex: Int,
    val measureIndex: Int,
    val beatInMeasure: Int,
    val ink: CellInk,
    val inferred: Boolean,
) {
    val key: CellKey get() = CellKey(left, top, right, bottom)
}

data class LabelRect(
    val systemIndex: Int,
    val voiceIndex: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val key: CellKey get() = CellKey(left, top, right, bottom)
}

data class VoiceSpan(
    val systemIndex: Int,
    val voiceIndex: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val markerXs: List<Float>,
)

data class RawBand(val top: Int, val bottom: Int)

data class RawMarker(
    val x: Float,
    val kind: MarkerKind,
    val hits: Int,
    val top: Float,
    val bottom: Float,
)

data class RawSystem(
    val bands: List<RawBand>,
    val tallLines: List<Float>,
    val markers: List<RawMarker>,
    val top: Int,
    val bottom: Int,
    val strokes: List<VerticalStroke> = emptyList(),
)

data class DetectedGrid(
    val systems: List<RawSystem>,
    val header: CellKey?,
    val signature: Int,
) {
    fun hasStructure(): Boolean {
        return systems.any { it.tallLines.size >= 2 || it.markers.isNotEmpty() }
    }

    fun layout(ink: InkImage, signature: Int): LaidOutScore {
        return SolfaGridDetector.layout(this, ink, signature)
    }
}

data class LaidOutScore(
    val voices: List<List<GridCell>>,
    val debug: ScanDebug,
    val header: CellKey?,
    val labels: List<LabelRect>,
    val spans: List<VoiceSpan>,
)

object SolfaGridDetector {
    private const val inset = 3f
    private const val sameXTolerance = 4f
    private const val heightMargin = 0.05f
    private const val musicalShareNumerator = 4
    private const val musicalShareDenominator = 5
    private const val leftCropStep = 2
    private const val solfaLetters = "drmfslt"
    private val solfaSyllables = setOf("di", "ri", "fi", "si", "ta")
    private val solfaPunctuation = setOf('-', ',', '\'', '\u2019', '\u2032', ':')

    fun detect(ink: InkImage, signature: Int): DetectedGrid {
        val lines = step1VerticalLines(ink)
        val rows = step2GroupRows(lines)
        val kept = step3MajorityLeftEdges(rows, ink.width)
        return step4BuildSystems(ink, kept, signature)
    }

    suspend fun scan(
        ink: InkImage,
        targetSignature: Int?,
        readWindow: suspend (DebugWindow) -> List<OcrLine>,
    ): ScanResult {
        val strokes = step1VerticalLines(ink)
        val pass = step5CropUntilMusical(strokes, ink.width, readWindow)
        val voices = step6MajorityVoices(pass.sure)
        val classified = pass.unsure.map { step6ClassifyUnsure(it, voices) }
        val kept = pass.sure + classified
        val opened = kept.map { it to openBox(it, strokes) }
        val draft = ScanDraft(
            boxes = opened.mapNotNull { it.second },
            notes = kept.flatMap { it.notes },
            glyphs = kept.flatMap { it.glyphs },
            windows = opened.mapNotNull { (read, box) -> if (box == null) null else read.window },
        )
        val finished = MeasureDelimiters.finish(draft, targetSignature)
        return ScanResult(finished.parse, finished.signature, draft)
    }

    private fun openBox(read: BoundsRead, strokes: List<VerticalStroke>): OpenBox? {
        if (read.window.label.contains("not")) return null
        val musical = read.notes.zip(read.lineSymbols)
            .filter { (note, _) -> note.musical }
            .sortedBy { (note, _) -> note.top }
        if (musical.isEmpty()) return null
        return MeasureDelimiters.open(
            voices = musical.map { it.second },
            strokes = strokes,
            left = read.window.left - inset,
            right = read.window.right + inset,
            top = read.window.top,
            bottom = read.window.bottom,
        )
    }

    private suspend fun readBounds(
        window: DebugWindow,
        bounds: DebugBounds,
        readWindow: suspend (DebugWindow) -> List<OcrLine>,
    ): BoundsRead {
        val lines = readWindow(window)
        val classified = step4ClassifyLines(lines, bounds)
        return BoundsRead(
            window = window,
            notes = classified.map { it.box },
            glyphs = step4Glyphs(lines, bounds),
            lineSymbols = classified.map { it.symbols },
        )
    }

    private suspend fun step5CropUntilMusical(
        strokes: List<VerticalStroke>,
        width: Int,
        readWindow: suspend (DebugWindow) -> List<OcrLine>,
    ): ColumnPass {
        var columns = columnsFrom(strokes, 0)
        var pass = readColumns(columns, readWindow)
        if (step5HasMusicalRows(pass)) return pass
        var crop = leftCropStep
        while (crop < width) {
            val next = columnsFrom(strokes, crop)
            if (next.left.isNotEmpty() && !sameStrokes(columns.left, next.left)) {
                columns = next
                pass = readColumns(columns, readWindow)
                if (step5HasMusicalRows(pass)) return pass
            }
            crop += leftCropStep
        }
        return pass
    }

    private fun step5HasMusicalRows(pass: ColumnPass): Boolean {
        return (pass.sure + pass.unsure).any { read -> read.notes.any { it.musical } }
    }

    private fun columnsFrom(strokes: List<VerticalStroke>, crop: Int): ColumnGeometry {
        val visible = if (crop <= 0) strokes else strokes.filter { it.x >= crop }
        val left = step2SameXAsLeftmost(visible)
        val pairs = step3RightmostSameHeight(visible, left)
        val leftOnly = step3LeftLineOnly(visible, pairs)
        return ColumnGeometry(left, pairs, leftOnly, visible)
    }

    private suspend fun readColumns(
        columns: ColumnGeometry,
        readWindow: suspend (DebugWindow) -> List<OcrLine>,
    ): ColumnPass {
        val sure = mutableListOf<BoundsRead>()
        for (pair in columns.pairs) {
            val window = windowOf(pair) ?: continue
            sure += readBounds(window, DebugBounds.BOTH, readWindow)
        }
        val unsure = mutableListOf<BoundsRead>()
        for (stroke in columns.leftOnly) {
            val window = windowOfLeft(stroke, columns.visible, columns.pairs) ?: continue
            unsure += readBounds(window, DebugBounds.LEFT, readWindow)
        }
        return ColumnPass(columns.left, columns.pairs, columns.leftOnly, sure, unsure)
    }

    private fun sameStrokes(first: List<VerticalStroke>, second: List<VerticalStroke>): Boolean {
        if (first.size != second.size) return false
        return first.indices.all { first[it] === second[it] }
    }

    private fun step6MajorityVoices(reads: List<BoundsRead>): Int {
        if (reads.isEmpty()) return 0
        val counts = reads.map { it.notes.count { note -> note.musical } }
        return counts
            .groupingBy { it }
            .eachCount()
            .entries
            .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            ?.key
            ?: 0
    }

    private fun step6ClassifyUnsure(read: BoundsRead, voices: Int): BoundsRead {
        val score = read.notes.count { it.musical }
        val needed = voices - 1
        if (voices > 0 && score >= needed) {
            return read.copy(window = read.window.copy(bounds = DebugBounds.BOTH, label = ""))
        }
        val shown = if (voices <= 0) "$score/?" else "$score/$needed"
        return read.copy(window = read.window.copy(label = "$shown not"))
    }

    private fun step1VerticalLines(ink: InkImage): List<VerticalStroke> {
        return BarlineDetector.strokes(ink)
    }

    private fun step2SameXAsLeftmost(strokes: List<VerticalStroke>): List<VerticalStroke> {
        val origin = strokes.minOfOrNull { it.x } ?: return emptyList()
        return strokes.filter { abs(it.x - origin) <= sameXTolerance }
    }

    private fun step3RightmostSameHeight(
        strokes: List<VerticalStroke>,
        leftLines: List<VerticalStroke>,
    ): List<BoundPair> {
        return leftLines.mapNotNull { left ->
            val right = strokes
                .filter { it.x > left.x + sameXTolerance }
                .filter { sameRow(left, it) && heightMatches(left.height, it.height) }
                .maxByOrNull { it.x }
                ?: return@mapNotNull null
            BoundPair(left, right)
        }
    }

    private fun step3LeftLineOnly(
        strokes: List<VerticalStroke>,
        pairs: List<BoundPair>,
    ): List<VerticalStroke> {
        if (pairs.isEmpty()) return emptyList()
        val edges = strokes.filter { stroke ->
            alignedWithSureLeft(stroke, pairs) && pairs.none { sameSureLeft(it.left, stroke) }
        }
        val kept = mutableListOf<VerticalStroke>()
        for (stroke in edges.sortedBy { it.x }) {
            val duplicate = kept.any { other -> sameSureLeft(other, stroke) }
            if (!duplicate) kept += stroke
        }
        return kept.sortedBy { it.top }
    }

    private fun alignedWithSureLeft(stroke: VerticalStroke, pairs: List<BoundPair>): Boolean {
        return pairs.any { abs(it.left.x - stroke.x) <= sameXTolerance }
    }

    private fun sameSureLeft(sure: VerticalStroke, stroke: VerticalStroke): Boolean {
        return sure === stroke ||
            abs(sure.x - stroke.x) <= sameXTolerance &&
            sameRow(sure, stroke) &&
            heightMatches(sure.height, stroke.height)
    }

    private fun windowOf(pair: BoundPair): DebugWindow? {
        val left = pair.left.x + inset
        val right = pair.right.x - inset
        if (right - left < 8f) return null
        val top = min(pair.left.top, pair.right.top).toFloat()
        val bottom = max(pair.left.bottom, pair.right.bottom).toFloat()
        if (bottom - top < 8f) return null
        return DebugWindow(left, top, right, bottom, DebugBounds.BOTH)
    }

    private fun windowOfLeft(
        stroke: VerticalStroke,
        strokes: List<VerticalStroke>,
        pairs: List<BoundPair>,
    ): DebugWindow? {
        val rightX = strokes
            .filter { it !== stroke && it.x > stroke.x + sameXTolerance && sameRow(stroke, it) }
            .maxOfOrNull { it.x }
            ?: pairs.maxOfOrNull { it.right.x }
            ?: return null
        val left = stroke.x + inset
        val right = rightX - inset
        if (right - left < 8f) return null
        val top = stroke.top.toFloat()
        val bottom = stroke.bottom.toFloat()
        if (bottom - top < 8f) return null
        return DebugWindow(left, top, right, bottom, DebugBounds.LEFT)
    }

    private fun step4ClassifyLines(lines: List<OcrLine>, bounds: DebugBounds): List<ClassifiedLine> {
        return horizontalLines(detectedTexts(lines)).mapNotNull { row ->
            val text = row.joinToString("") { it.plain() }
            if (text.isEmpty()) return@mapNotNull null
            val left = row.minOf { it.left }
            val top = row.minOf { it.top }
            val right = row.maxOf { it.right }
            val bottom = row.maxOf { it.bottom }
            if (right - left < 2f || bottom - top < 2f) return@mapNotNull null
            val score = scoreLine(text)
            ClassifiedLine(
                box = DebugNoteBox(
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    voiceIndex = 0,
                    voiceId = "",
                    musical = score.musical,
                    bounds = bounds,
                    label = score.label,
                ),
                symbols = row,
            )
        }
    }

    private fun detectedTexts(lines: List<OcrLine>): List<OcrSymbol> {
        val symbols = lines.flatMap { it.symbols }.filter { it.plain().isNotEmpty() }
        if (symbols.isNotEmpty()) return symbols
        return lines.mapNotNull { line ->
            val text = line.text.trim()
            if (text.isEmpty()) return@mapNotNull null
            OcrSymbol(text, line.left, line.top, line.right, line.bottom)
        }
    }

    private fun horizontalLines(symbols: List<OcrSymbol>): List<List<OcrSymbol>> {
        if (symbols.isEmpty()) return emptyList()
        val heights = symbols.map { it.height.coerceAtLeast(1f) }.sorted()
        val gap = heights[heights.size / 2] * 0.5f
        val rows = mutableListOf<MutableList<OcrSymbol>>()
        for (symbol in symbols.sortedBy { it.centerY }) {
            val row = rows.lastOrNull()
            val center = if (row == null) {
                Float.NEGATIVE_INFINITY
            } else {
                val centers = row.map { it.centerY }.sorted()
                centers[centers.size / 2]
            }
            if (row == null || symbol.centerY - center > gap) {
                rows.add(mutableListOf(symbol))
            } else {
                row.add(symbol)
            }
        }
        return rows.map { row -> row.sortedBy { it.centerX } }
    }

    private fun step4Glyphs(lines: List<OcrLine>, bounds: DebugBounds): List<DebugGlyph> {
        return lines.flatMap { line ->
            line.symbols.mapNotNull { symbol ->
                val text = symbol.text
                if (text.isBlank()) return@mapNotNull null
                DebugGlyph(text, symbol.left, symbol.top, symbol.right, symbol.bottom, bounds)
            }
        }
    }

    private data class LineScore(
        val hits: Int,
        val total: Int,
        val musical: Boolean,
        val label: String,
    )

    private fun scoreLine(text: String): LineScore {
        var index = 0
        var hits = 0
        var total = 0
        while (index < text.length) {
            val char = text[index]
            if (char.isWhitespace() || char == 'I') {
                index++
                continue
            }
            if (char in solfaPunctuation) {
                hits++
                total++
                index++
                continue
            }
            val next = text.getOrNull(index + 1)
            if (char.isLetter() && next != null && next.isLetter() && next != 'I') {
                val pair = "${char.lowercaseChar()}${next.lowercaseChar()}"
                if (pair in solfaSyllables) {
                    hits += 2
                    total += 2
                    index += 2
                    continue
                }
            }
            if (char.isLetter()) {
                total++
                if (char.lowercaseChar() in solfaLetters) hits++
            }
            index++
        }
        val musical = total > 0 && hits * musicalShareDenominator >= total * musicalShareNumerator
        val label = "$hits/$total ${if (musical) "musical" else "not"}"
        return LineScore(hits, total, musical, label)
    }

    private data class ClassifiedLine(val box: DebugNoteBox, val symbols: List<OcrSymbol>)

    private data class BoundsRead(
        val window: DebugWindow,
        val notes: List<DebugNoteBox>,
        val glyphs: List<DebugGlyph>,
        val lineSymbols: List<List<OcrSymbol>>,
    )

    private data class ColumnGeometry(
        val left: List<VerticalStroke>,
        val pairs: List<BoundPair>,
        val leftOnly: List<VerticalStroke>,
        val visible: List<VerticalStroke>,
    )

    private data class ColumnPass(
        val left: List<VerticalStroke>,
        val pairs: List<BoundPair>,
        val leftOnly: List<VerticalStroke>,
        val sure: List<BoundsRead>,
        val unsure: List<BoundsRead>,
    )

    private fun heightMatches(reference: Int, other: Int): Boolean {
        val base = reference.coerceAtLeast(1)
        return abs(other - base) <= base * heightMargin
    }

    private fun step2GroupRows(strokes: List<VerticalStroke>): List<List<VerticalStroke>> {
        return gridRows(strokes)
    }

    private fun step3MajorityLeftEdges(
        rows: List<List<VerticalStroke>>,
        width: Int,
    ): List<List<VerticalStroke>> {
        return keepMajorityLeftEdges(rows, width)
    }

    private fun step4BuildSystems(
        ink: InkImage,
        rows: List<List<VerticalStroke>>,
        signature: Int,
    ): DetectedGrid {
        val systems = rows.mapNotNull { buildSystem(ink, it) }
        val first = systems.firstOrNull()?.bands?.firstOrNull()
        val header = if (first != null && first.top >= 8) {
            CellKey(0, 0, ink.width, first.top)
        } else {
            null
        }
        return DetectedGrid(systems, header, signature.coerceIn(2, 6))
    }

    fun layout(grid: DetectedGrid, ink: InkImage, signature: Int): LaidOutScore {
        val sig = signature.coerceIn(2, 6)
        val voiceCount = min(12, grid.systems.maxOfOrNull { it.bands.size } ?: 0)
        if (voiceCount == 0) {
            return LaidOutScore(emptyList(), ScanDebug(), grid.header, emptyList(), emptyList())
        }
        val voices = List(voiceCount) { mutableListOf<GridCell>() }
        val measureLines = mutableListOf<DebugMeasureLine>()
        val beatLines = mutableListOf<DebugMeasureLine>()
        val notes = mutableListOf<DebugNoteBox>()
        val labels = mutableListOf<LabelRect>()
        val spans = mutableListOf<VoiceSpan>()
        var measureIndex = 0
        for ((systemIndex, system) in grid.systems.withIndex()) {
            val classified = BarlineDetector.classify(system.strokes, sig, system.markers.map { it.x })
            val strokeWalls = classified.borders.map { it.x }
            val walls: List<Float>
            val markers: List<RawMarker>
            val strokeCuts: List<Float>
            if (strokeWalls.size >= 2) {
                walls = strokeWalls.sorted()
                markers = system.markers
                strokeCuts = classified.delimiters.map { it.x }
            } else if (system.tallLines.size >= 2) {
                val (thinned, demoted) = thinWalls(system.tallLines, system.markers, sig)
                walls = thinned
                markers = withDemoted(system, demoted)
                strokeCuts = emptyList()
            } else {
                val outer = outerLines(system) ?: continue
                walls = outer
                markers = system.markers
                strokeCuts = emptyList()
            }
            if (walls.size < 2) continue
            val top = system.top.toFloat()
            val bottom = system.bottom.toFloat()
            for (x in walls) measureLines.add(DebugMeasureLine(x, top, bottom))
            val markerXs = (markers.map { it.x } + strokeCuts).distinct().sorted()
            val gutterRight = walls.first().roundToInt() - 2
            for (voiceIndex in system.bands.indices) {
                if (voiceIndex >= voiceCount) break
                val band = system.bands[voiceIndex]
                spans.add(
                    VoiceSpan(
                        systemIndex = systemIndex,
                        voiceIndex = voiceIndex,
                        left = walls.first().roundToInt(),
                        top = band.top,
                        right = walls.last().roundToInt(),
                        bottom = band.bottom,
                        markerXs = markerXs,
                    )
                )
                if (gutterRight >= 8) {
                    labels.add(LabelRect(systemIndex, voiceIndex, 0, band.top, gutterRight, band.bottom))
                }
            }
            for (measure in 0 until walls.lastIndex) {
                val left = walls[measure]
                val right = walls[measure + 1]
                val inside = markers.filter { it.x > left + 1.5f && it.x < right - 1.5f }
                val fromStrokes = strokeCuts.filter { it > left + 1.5f && it < right - 1.5f }.sorted()
                val chosen = when {
                    fromStrokes.size >= sig - 1 -> fromStrokes.take(sig - 1)
                    fromStrokes.isNotEmpty() && inside.isEmpty() -> fromStrokes.take(sig - 1)
                    else -> chooseCuts(inside, sig).map { it.x }
                }
                for (x in chosen) beatLines.add(DebugMeasureLine(x, top, bottom))
                val snapped = snap(left, right, sig, chosen)
                for (voiceIndex in 0 until voiceCount) {
                    val band = system.bands.getOrNull(voiceIndex)
                    for (beat in 0 until sig) {
                        val cell = cellFor(
                            ink = ink,
                            band = band,
                            edges = snapped.edges,
                            inferred = snapped.inferred,
                            beat = beat,
                            signature = sig,
                            systemIndex = systemIndex,
                            voiceIndex = voiceIndex,
                            measureIndex = measureIndex,
                            systemBottom = system.bottom,
                        )
                        voices[voiceIndex].add(cell)
                        if (cell.right - cell.left >= 2 && cell.bottom - cell.top >= 2) {
                            notes.add(
                                DebugNoteBox(
                                    left = cell.left.toFloat(),
                                    top = cell.top.toFloat(),
                                    right = cell.right.toFloat(),
                                    bottom = cell.bottom.toFloat(),
                                    voiceIndex = voiceIndex,
                                    voiceId = voiceId(voiceIndex),
                                )
                            )
                        }
                    }
                }
                measureIndex++
            }
        }
        val kept = voices.filter { it.isNotEmpty() }
        return LaidOutScore(
            voices = kept,
            debug = ScanDebug(notes, measureLines, beatLines),
            header = grid.header,
            labels = labels,
            spans = spans,
        )
    }

    private fun cellFor(
        ink: InkImage,
        band: RawBand?,
        edges: List<Float>,
        inferred: Set<Int>,
        beat: Int,
        signature: Int,
        systemIndex: Int,
        voiceIndex: Int,
        measureIndex: Int,
        systemBottom: Int,
    ): GridCell {
        var left = (edges[beat] + inset).roundToInt()
        var right = (edges[beat + 1] - inset).roundToInt()
        if (right - left < 2) {
            val mid = ((edges[beat] + edges[beat + 1]) / 2f).roundToInt()
            left = mid
            right = mid + 2
        }
        val guessed = (beat > 0 && beat in inferred) || (beat < signature - 1 && beat + 1 in inferred)
        if (band == null) {
            return GridCell(
                left, systemBottom, right, systemBottom,
                systemIndex, voiceIndex, measureIndex, beat, CellInk.EMPTY, guessed,
            )
        }
        return GridCell(
            left = left,
            top = band.top,
            right = right,
            bottom = band.bottom,
            systemIndex = systemIndex,
            voiceIndex = voiceIndex,
            measureIndex = measureIndex,
            beatInMeasure = beat,
            ink = classify(ink, left, band.top, right, band.bottom),
            inferred = guessed,
        )
    }

    private fun classify(ink: InkImage, left: Int, top: Int, right: Int, bottom: Int): CellInk {
        val l = left.coerceIn(0, ink.width)
        val t = top.coerceIn(0, ink.height)
        val r = right.coerceIn(l, ink.width)
        val b = bottom.coerceIn(t, ink.height)
        if (r - l < 2 || b - t < 2) return CellInk.EMPTY
        var minX = r
        var maxX = l
        var minY = b
        var maxY = t
        var count = 0
        for (y in t until b) {
            for (x in l until r) {
                if (!ink.isDark(x, y)) continue
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (count < 6) return CellInk.EMPTY
        val width = maxX - minX + 1
        val height = maxY - minY + 1
        val cellHeight = b - t
        if (width >= height * 3 && height <= cellHeight * 0.45f) return CellInk.DASH
        return CellInk.GLYPH
    }

    private fun findBands(ink: InkImage, top: Int, bottom: Int): List<RawBand> {
        val y0 = top.coerceIn(0, ink.height)
        val y1 = bottom.coerceIn(y0, ink.height)
        if (ink.width < 8 || y1 - y0 < 8) return emptyList()
        val first = mergeHoles(runs(projection(ink, null, y0, y1), threshold(ink.width)))
        if (first.isEmpty()) return emptyList()
        val masked = mergeHoles(runs(projection(ink, barMask(ink, first, y0, y1), y0, y1), threshold(ink.width)))
        return if (masked.isEmpty()) first else masked
    }

    private fun threshold(width: Int): Int = max(3, width / 80)

    private fun projection(ink: InkImage, mask: BooleanArray?, top: Int, bottom: Int): IntArray {
        val counts = IntArray(ink.height)
        for (y in top until bottom) {
            var count = 0
            for (x in 0 until ink.width) {
                if (mask != null && mask[x]) continue
                if (ink.isDark(x, y)) count++
            }
            counts[y] = count
        }
        return counts
    }

    private fun runs(counts: IntArray, minCount: Int): List<RawBand> {
        val found = mutableListOf<RawBand>()
        var y = 0
        while (y < counts.size) {
            if (counts[y] < minCount) {
                y++
                continue
            }
            val start = y
            while (y < counts.size && counts[y] >= minCount) y++
            if (y - start >= 3) found.add(RawBand(start, y))
        }
        return found
    }

    private fun mergeHoles(bands: List<RawBand>): List<RawBand> {
        if (bands.isEmpty()) return bands
        val merged = mutableListOf(bands.first())
        for (band in bands.drop(1)) {
            val gap = band.top - merged.last().bottom
            if (gap <= 3) {
                val last = merged.removeAt(merged.lastIndex)
                merged.add(RawBand(last.top, band.bottom))
            } else {
                merged.add(band)
            }
        }
        return merged
    }

    private fun barMask(ink: InkImage, bands: List<RawBand>, top: Int, bottom: Int): BooleanArray {
        val mask = BooleanArray(ink.width)
        val heights = bands.map { it.bottom - it.top }.sorted()
        val median = heights[heights.size / 2].coerceAtLeast(1)
        val limit = max(median + 6, (median * 2.4f).toInt())
        for (x in 0 until ink.width) {
            if (longestRun(ink, x, top, bottom) >= limit) mask[x] = true
        }
        return mask
    }

    private fun longestRun(ink: InkImage, x: Int, top: Int, bottom: Int): Int {
        var best = 0
        var run = 0
        for (y in top until bottom) {
            if (ink.isDark(x, y)) {
                run++
                if (run > best) best = run
            } else {
                run = 0
            }
        }
        return best
    }

    private fun gridRows(strokes: List<VerticalStroke>): List<List<VerticalStroke>> {
        val remaining = strokes.sortedByDescending { it.height }.toMutableList()
        val candidates = mutableListOf<List<VerticalStroke>>()
        while (remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            val group = mutableListOf(seed)
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()
                if (group.all { sameRow(it, other) && heightsMatch(it.height, other.height) }) {
                    group.add(other)
                    iterator.remove()
                }
            }
            if (group.size >= 2) candidates.add(group.sortedBy { it.x })
        }
        return candidates
            .filter { cluster -> candidates.none { other -> other !== cluster && covers(other, cluster) } }
            .sortedBy { it.minOf { stroke -> stroke.top } }
    }

    private fun keepMajorityLeftEdges(
        rows: List<List<VerticalStroke>>,
        width: Int,
    ): List<List<VerticalStroke>> {
        if (rows.size < 2) return rows
        val tolerance = max(12f, width * 0.05f)
        val clusters = clusterByLeftX(rows, tolerance)
        val largest = clusters.maxOf { it.size }
        val aligned = clusters.first { it.size == largest }
        val kept = if (aligned.size < 2) {
            aligned
        } else {
            val heights = aligned.map { it.first().height }.sorted()
            val median = heights[heights.size / 2].coerceAtLeast(1)
            aligned.filter { abs(it.first().height - median) * 5 <= median * 2 }
        }
        return rows.filter { row -> kept.any { it === row } }
    }

    private fun clusterByLeftX(
        rows: List<List<VerticalStroke>>,
        tolerance: Float,
    ): List<List<List<VerticalStroke>>> {
        val sorted = rows.sortedBy { it.first().x }
        val clusters = mutableListOf(mutableListOf(sorted.first()))
        for (row in sorted.drop(1)) {
            val origin = clusters.last().first().first().x
            if (row.first().x - origin > tolerance) clusters.add(mutableListOf(row))
            else clusters.last().add(row)
        }
        return clusters
    }

    private fun sameRow(a: VerticalStroke, b: VerticalStroke): Boolean {
        val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (overlap <= 0) return false
        val shorter = min(a.height, b.height).coerceAtLeast(1)
        return overlap * 5 >= shorter * 4
    }

    private fun heightsMatch(a: Int, b: Int): Boolean {
        val larger = max(a, b).coerceAtLeast(1)
        return abs(a - b) * 10 <= larger
    }

    private fun covers(outer: List<VerticalStroke>, inner: List<VerticalStroke>): Boolean {
        val outerTop = outer.minOf { it.top }
        val outerBottom = outer.maxOf { it.bottom }
        val innerTop = inner.minOf { it.top }
        val innerBottom = inner.maxOf { it.bottom }
        val innerSpan = (innerBottom - innerTop).coerceAtLeast(1)
        val outerSpan = (outerBottom - outerTop).coerceAtLeast(1)
        if (innerSpan * 20 >= outerSpan * 17) return false
        val overlap = min(innerBottom, outerBottom) - max(innerTop, outerTop)
        return overlap * 5 >= innerSpan * 4
    }

    private fun buildSystem(ink: InkImage, strokes: List<VerticalStroke>): RawSystem? {
        if (strokes.size < 2) return null
        val top = strokes.minOf { it.top }
        val bottom = strokes.maxOf { it.bottom }
        val bands = findBands(ink, top, bottom)
        if (bands.isEmpty()) return null
        val hits = mutableListOf<BandHit>()
        for ((index, band) in bands.withIndex()) {
            hits.addAll(markersInBand(ink, band, index))
        }
        val clustered = clusterHits(hits, max(8f, ink.width * 0.025f))
        val columns = acceptColumns(clustered, bands.size)
        val kept = if (columns.isEmpty()) {
            bands
        } else {
            val sharing = bands.filterIndexed { index, _ -> columns.any { index in it.bands } }
            if (sharing.isEmpty()) bands else sharing
        }
        val music = kept.take(12)
        if (music.isEmpty()) return null
        val tall = strokes.map { it.x }.sorted()
        val musicIds = music.map { bands.indexOf(it) }.toSet()
        val recounted = columns.map { column ->
            column.copy(bands = column.bands.intersect(musicIds))
        }.filter { it.bands.size >= max(1, music.size / 2 + if (music.size == 1) 0 else 1) }
        val markers = if (recounted.isNotEmpty()) {
            recounted
        } else if (tall.size >= 2) {
            emptyList()
        } else {
            return null
        }
        if (tall.size < 2 && markers.isEmpty()) return null
        val rawMarkers = markers.map { column ->
            RawMarker(
                x = column.x,
                kind = column.kind,
                hits = column.bands.size,
                top = top.toFloat(),
                bottom = bottom.toFloat(),
            )
        }
        return RawSystem(music, tall, rawMarkers, top, bottom, strokes.sortedBy { it.x })
    }

    private fun acceptColumns(columns: List<HitColumn>, bandCount: Int): List<HitColumn> {
        val needed = if (bandCount <= 1) 1 else bandCount / 2 + 1
        return columns.filter { it.bands.size >= needed }
    }

    private fun markersInBand(ink: InkImage, band: RawBand, bandIndex: Int): List<BandHit> {
        val height = band.bottom - band.top
        if (height < 4) return emptyList()
        val blobs = components(ink, band.top, band.bottom)
        val hits = mutableListOf<BandHit>()
        val dots = blobs.filter { isColonDot(it, height) }.sortedBy { it.minY }
        val used = BooleanArray(dots.size)
        for (index in dots.indices) {
            if (used[index]) continue
            var match = -1
            for (other in index + 1 until dots.size) {
                if (used[other]) continue
                if (isColon(dots[index], dots[other], height)) {
                    match = other
                    break
                }
            }
            if (match < 0) continue
            used[index] = true
            used[match] = true
            val x = (dots[index].centerX + dots[match].centerX) / 2f
            hits.add(BandHit(x, MarkerKind.COLON, bandIndex, band.top, band.bottom))
        }
        for (blob in blobs) {
            if (!isPipe(blob, height) || leavesBand(ink, blob.centerX, band)) continue
            hits.add(BandHit(blob.centerX, MarkerKind.PIPE, bandIndex, band.top, band.bottom))
        }
        return hits
    }

    private fun leavesBand(ink: InkImage, x: Float, band: RawBand): Boolean {
        val start = (x - 1f).roundToInt()
        val end = (x + 1f).roundToInt()
        for (column in start..end) {
            if (column !in 0 until ink.width) continue
            if (outsideRun(ink, column, band.top, -1) >= 3) return true
            if (outsideRun(ink, column, band.bottom, 1) >= 3) return true
        }
        return false
    }

    private fun outsideRun(ink: InkImage, x: Int, start: Int, step: Int): Int {
        var count = 0
        var y = start
        while (count < 6 && y in 0 until ink.height && ink.isDark(x, y)) {
            count++
            y += step
        }
        return count
    }

    private fun isPipe(blob: Blob, bandHeight: Int): Boolean {
        if (blob.count < 4) return false
        val hairline = max(3, (bandHeight * 0.22f).roundToInt())
        if (blob.height < bandHeight * 0.45f) return false
        if (blob.width > hairline) return false
        if (blob.height < blob.width * 3) return false
        return true
    }

    private fun isColonDot(blob: Blob, bandHeight: Int): Boolean {
        if (blob.count < 3) return false
        if (blob.width > bandHeight * 0.7f) return false
        if (blob.height > bandHeight * 0.62f) return false
        if (blob.width > blob.height * 2.8f) return false
        if (isPipe(blob, bandHeight)) return false
        return true
    }

    private fun isColon(upper: Blob, lower: Blob, bandHeight: Int): Boolean {
        if (lower.minY <= upper.maxY) return false
        val gap = lower.minY - upper.maxY - 1
        if (gap < 1 || gap > bandHeight * 0.65f) return false
        val span = lower.maxY - upper.minY + 1
        if (span < bandHeight * 0.4f) return false
        val align = max(upper.width, lower.width).toFloat().coerceAtLeast(4f)
        if (abs(upper.centerX - lower.centerX) > align) return false
        return true
    }

    private fun components(ink: InkImage, top: Int, bottom: Int): List<Blob> {
        val height = bottom - top
        if (height <= 0 || ink.width <= 0) return emptyList()
        val seen = BooleanArray(ink.width * height)
        val stack = IntArray(seen.size)
        val blobs = mutableListOf<Blob>()
        fun slot(x: Int, y: Int) = (y - top) * ink.width + x
        for (y in top until bottom) {
            for (x in 0 until ink.width) {
                val origin = slot(x, y)
                if (seen[origin] || !ink.isDark(x, y)) continue
                var size = 0
                seen[origin] = true
                stack[size++] = origin
                val blob = Blob()
                while (size > 0) {
                    val packed = stack[--size]
                    val px = packed % ink.width
                    val py = top + packed / ink.width
                    blob.add(px, py)
                    size = push(ink, seen, stack, top, bottom, px - 1, py, size)
                    size = push(ink, seen, stack, top, bottom, px + 1, py, size)
                    size = push(ink, seen, stack, top, bottom, px, py - 1, size)
                    size = push(ink, seen, stack, top, bottom, px, py + 1, size)
                }
                if (blob.count >= 3) blobs.add(blob)
            }
        }
        return blobs
    }

    private fun push(
        ink: InkImage,
        seen: BooleanArray,
        stack: IntArray,
        top: Int,
        bottom: Int,
        x: Int,
        y: Int,
        size: Int,
    ): Int {
        if (x !in 0 until ink.width || y !in top until bottom || !ink.isDark(x, y)) return size
        val index = (y - top) * ink.width + x
        if (seen[index]) return size
        seen[index] = true
        stack[size] = index
        return size + 1
    }

    private fun clusterHits(hits: List<BandHit>, tolerance: Float): List<HitColumn> {
        if (hits.isEmpty()) return emptyList()
        val sorted = hits.sortedBy { it.x }
        val groups = mutableListOf(mutableListOf(sorted.first()))
        for (hit in sorted.drop(1)) {
            if (hit.x - groups.last().last().x > tolerance) groups.add(mutableListOf(hit))
            else groups.last().add(hit)
        }
        return groups.mapNotNull { group ->
            val bands = group.map { it.band }.toSet()
            if (bands.isEmpty()) return@mapNotNull null
            val xs = group.map { it.x }.sorted()
            val pipes = group.count { it.kind == MarkerKind.PIPE }
            val kind = if (pipes * 2 >= group.size) MarkerKind.PIPE else MarkerKind.COLON
            HitColumn(xs[xs.size / 2], kind, bands)
        }
    }

    private fun outerLines(system: RawSystem): List<Float>? {
        val xs = system.markers.map { it.x }.sorted()
        if (xs.size < 2 && system.tallLines.isEmpty()) return null
        val leftInk = system.markers.minOfOrNull { it.x } ?: system.tallLines.firstOrNull() ?: return null
        val rightInk = system.markers.maxOfOrNull { it.x } ?: system.tallLines.lastOrNull() ?: return null
        val pad = max(16f, (rightInk - leftInk) * 0.35f)
        return listOf(leftInk - pad, rightInk + pad)
    }

    private fun thinWalls(
        lines: List<Float>,
        markers: List<RawMarker>,
        signature: Int,
    ): Pair<List<Float>, List<Float>> {
        if (lines.size <= 2) return lines to emptyList()
        val counts = lines.zipWithNext { start, end ->
            markers.count { it.x > start + 2f && it.x < end - 2f }
        }
        val median = counts.sorted()[counts.size / 2]
        if (median >= 1 || lines.size <= signature) return lines to emptyList()
        val kept = mutableListOf<Float>()
        var index = 0
        while (index < lines.size) {
            kept.add(lines[index])
            index += signature
        }
        if (abs(kept.last() - lines.last()) > 1.5f) kept.add(lines.last())
        val demoted = lines.filter { line -> kept.none { abs(it - line) <= 1.5f } }
        return kept to demoted
    }

    private fun withDemoted(system: RawSystem, demoted: List<Float>): List<RawMarker> {
        val all = system.markers.toMutableList()
        for (x in demoted) {
            if (all.any { abs(it.x - x) <= 8f }) continue
            all.add(
                RawMarker(
                    x = x,
                    kind = MarkerKind.PIPE,
                    hits = system.bands.size,
                    top = system.top.toFloat(),
                    bottom = system.bottom.toFloat(),
                )
            )
        }
        return all.sortedBy { it.x }
    }

    private fun chooseCuts(markers: List<RawMarker>, signature: Int): List<RawMarker> {
        if (markers.size <= signature - 1) return markers.sortedBy { it.x }
        return markers
            .sortedWith(compareByDescending<RawMarker> { it.hits }.thenBy { it.x })
            .take(signature - 1)
            .sortedBy { it.x }
    }

    private fun snap(left: Float, right: Float, signature: Int, cuts: List<Float>): SnappedEdges {
        val edges = MutableList(signature + 1) { index ->
            left + (right - left) * index / signature
        }
        val inferred = (1 until signature).toMutableSet()
        val used = mutableSetOf<Int>()
        val limit = (right - left) / signature * 0.5f
        for (slot in 1 until signature) {
            val ideal = edges[slot]
            val index = cuts.indices
                .filter { it !in used }
                .minByOrNull { abs(cuts[it] - ideal) }
                ?: continue
            if (abs(cuts[index] - ideal) <= limit) {
                used.add(index)
                edges[slot] = cuts[index]
                inferred.remove(slot)
            }
        }
        for (index in 1 until edges.lastIndex) {
            if (edges[index] <= edges[index - 1]) edges[index] = edges[index - 1] + 0.5f
            if (edges[index] >= edges[index + 1]) {
                edges[index] = (edges[index - 1] + edges[index + 1]) / 2f
            }
        }
        edges[0] = left
        edges[edges.lastIndex] = right
        return SnappedEdges(edges, inferred)
    }

    private fun voiceId(index: Int): String {
        return PartitionData.voiceIds[index % PartitionData.voiceIds.size]
    }
}

object GridScore {
    fun fromLayout(
        layout: LaidOutScore,
        cellText: Map<CellKey, String>,
        regionText: Map<CellKey, String>,
    ): SolfaParse {
        val key = keyIndex(layout, regionText)
        if (layout.voices.isEmpty()) return SolfaParse(emptyList(), key, empty = true, debug = layout.debug)
        val ids = MutableList(layout.voices.size) { index ->
            PartitionData.voiceIds[index % PartitionData.voiceIds.size]
        }
        for (label in layout.labels) {
            if (label.voiceIndex !in ids.indices) continue
            val text = regionText[label.key] ?: continue
            val id = Regex("[SATB]").find(text.uppercase())?.value ?: continue
            ids[label.voiceIndex] = id
        }
        val notes = mutableListOf<DebugNoteBox>()
        val voices = layout.voices.mapIndexed { voiceIndex, cells ->
            val texts = cells.map { cell ->
                when (cell.ink) {
                    CellInk.EMPTY -> ""
                    CellInk.DASH -> "-"
                    CellInk.GLYPH -> cellText[cell.key] ?: ""
                }
            }
            cells.forEach { cell ->
                if (cell.right - cell.left < 2 || cell.bottom - cell.top < 2) return@forEach
                notes.add(
                    DebugNoteBox(
                        left = cell.left.toFloat(),
                        top = cell.top.toFloat(),
                        right = cell.right.toFloat(),
                        bottom = cell.bottom.toFloat(),
                        voiceIndex = voiceIndex,
                        voiceId = ids[voiceIndex],
                    )
                )
            }
            ParsedVoice(
                id = ids[voiceIndex],
                cells = texts,
                inferredBeats = cells.mapIndexedNotNull { index, cell ->
                    if (cell.inferred && texts[index].isNotEmpty()) index else null
                },
            )
        }
        return SolfaParse(
            voices = voices,
            keyIndex = key,
            empty = false,
            debug = layout.debug.copy(notes = notes),
        )
    }

    private fun keyIndex(layout: LaidOutScore, regionText: Map<CellKey, String>): Int? {
        val header = layout.header ?: return null
        val text = regionText[header]?.takeIf { it.isNotBlank() } ?: return null
        return SolfaSpatialParser.detectKey(listOf(OcrSymbol(text, 0f, 0f, 24f, 12f)))
    }
}

private data class BoundPair(val left: VerticalStroke, val right: VerticalStroke)

private data class BandHit(
    val x: Float,
    val kind: MarkerKind,
    val band: Int,
    val top: Int,
    val bottom: Int,
)

private data class HitColumn(val x: Float, val kind: MarkerKind, val bands: Set<Int>)

private data class SnappedEdges(val edges: List<Float>, val inferred: Set<Int>)

private class Blob {
    var minX: Int = Int.MAX_VALUE
    var maxX: Int = 0
    var minY: Int = Int.MAX_VALUE
    var maxY: Int = 0
    var count: Int = 0

    fun add(x: Int, y: Int) {
        count++
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
    }

    val width: Int get() = maxX - minX + 1
    val height: Int get() = maxY - minY + 1
    val centerX: Float get() = (minX + maxX) / 2f
}
