package mg.maniry.doremii.omr

import mg.maniry.doremii.editor.partition.PartitionData
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class DelimiterKind { BEAT, MEASURE }

data class DelimiterColumn(val x: Float, val kind: DelimiterKind)

data class OpenBox(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    val voices: List<List<OcrSymbol>>,
    val columns: List<DelimiterColumn>,
    val tolerance: Float,
    val markers: List<DebugMeasureLine>,
)

data class ScanDraft(
    val boxes: List<OpenBox>,
    val notes: List<DebugNoteBox>,
    val glyphs: List<DebugGlyph>,
    val windows: List<DebugWindow>,
    val lines: List<DebugLine> = emptyList(),
    val blocks: List<DebugBlock> = emptyList(),
)

data class FinishedScan(
    val parse: SolfaParse,
    val signature: Int,
)

data class ScanResult(
    val parse: SolfaParse,
    val signature: Int,
    val draft: ScanDraft,
)

object MeasureDelimiters {
    private val colonChars = setOf(':', ';')
    private val pipeChars = setOf('|', '\u2502', '\u00A6', 'l', 'I', '1')

    fun open(
        voices: List<List<OcrSymbol>>,
        strokes: List<VerticalStroke>,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        marks: List<DelimiterColumn> = emptyList(),
    ): OpenBox {
        val tolerance = toleranceOf(voices.flatten())
        val columns = mutableListOf<DelimiterColumn>()
        val markerXs = mutableListOf<Float>()
        for (group in glyphGroups(voices, tolerance)) {
            val voicesHit = group.map { it.voice }.toSet()
            if (voicesHit.size < voices.size) continue
            val xs = group.map { it.symbol.centerX }.sorted()
            val x = xs[xs.size / 2]
            val chosen = voices.indices.map { voice ->
                group.filter { it.voice == voice }.minBy { abs(it.symbol.centerX - x) }
            }
            val colons = chosen.count { it.colon }
            val kind = if (colons * 2 >= chosen.size) {
                DelimiterKind.BEAT
            } else if (measureBar(strokesAt(x, strokes, tolerance, left, right, top, bottom), top, bottom, voices.size)) {
                DelimiterKind.MEASURE
            } else {
                DelimiterKind.BEAT
            }
            columns.add(DelimiterColumn(x, kind))
            addMarker(markerXs, x, tolerance)
        }
        for (group in strokeGroups(strokes, tolerance, left, right, top, bottom)) {
            val xs = group.map { it.x }.sorted()
            val x = xs[xs.size / 2]
            if (marks.any { abs(it.x - x) <= tolerance }) continue
            if (continuous(group, top, bottom)) addMarker(markerXs, x, tolerance)
            if (columns.any { abs(it.x - x) <= tolerance }) continue
            if (!measureBar(group, top, bottom, voices.size)) continue
            columns.add(DelimiterColumn(x, DelimiterKind.MEASURE))
        }
        for (mark in marks) {
            if (columns.any { abs(it.x - mark.x) <= tolerance }) continue
            columns.add(mark)
            addMarker(markerXs, mark.x, tolerance)
        }
        return OpenBox(
            left = left,
            right = right,
            top = top,
            bottom = bottom,
            voices = voices,
            columns = columns.sortedBy { it.x },
            tolerance = tolerance,
            markers = markerXs.sorted().map { DebugMeasureLine(it, top, bottom) },
        )
    }

    fun finish(draft: ScanDraft, target: Int?): FinishedScan {
        val signature = (target ?: majority(draft.boxes) ?: 4).coerceIn(2, 6)
        val closed = draft.boxes.map { closeBox(it, signature) }
        val voiceCount = closed.maxOfOrNull { it.voices.size } ?: 0
        if (voiceCount == 0) {
            return FinishedScan(SolfaParse(emptyList(), null, empty = true, debug = overlay(draft)), signature)
        }
        val texts = List(voiceCount) { mutableListOf<String>() }
        val inferred = List(voiceCount) { mutableListOf<Int>() }
        for (box in closed) {
            val width = box.voices.maxOfOrNull { it.size } ?: 0
            for (index in 0 until voiceCount) {
                val row = box.voices.getOrNull(index)
                if (row == null) {
                    repeat(width) { texts[index].add("") }
                } else {
                    for (cell in row) {
                        val at = texts[index].size
                        texts[index].add(cell.text)
                        if (cell.inferred && cell.text.isNotEmpty()) inferred[index].add(at)
                    }
                }
            }
        }
        val voices = texts.mapIndexed { index, cells ->
            ParsedVoice(
                id = PartitionData.voiceIds[index % PartitionData.voiceIds.size],
                cells = cells,
                inferredBeats = inferred[index],
            )
        }
        val empty = voices.isEmpty() || voices.all { voice -> voice.cells.all { it.isBlank() } }
        return FinishedScan(
            parse = SolfaParse(voices, null, empty, debug = overlay(draft)),
            signature = signature,
        )
    }

    private fun majority(boxes: List<OpenBox>): Int? {
        val counts = boxes.flatMap { initialSpans(it).map { span -> span.count } }
        if (counts.isEmpty()) return null
        val best = counts
            .groupingBy { it }
            .eachCount()
            .entries
            .maxWith(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
        return best.key.coerceIn(2, 6)
    }

    private fun closeBox(box: OpenBox, target: Int): ClosedBox {
        val spans = fillShort(mergeShort(splitWide(initialSpans(box), target), target), target)
        val voices = box.voices.map { symbols ->
            spans.flatMap { span -> cellsFor(symbols, span, box) }
        }
        return ClosedBox(voices)
    }

    private fun cellsFor(symbols: List<OcrSymbol>, span: Span, box: OpenBox): List<PlacedCell> {
        val cuts = span.cuts
        val edges = listOf(span.left) + cuts.map { it.x } + listOf(span.right)
        return (0 until edges.lastIndex).map { beat ->
            val text = cellText(symbols, edges[beat], edges[beat + 1], box)
            val leftInferred = beat > 0 && cuts[beat - 1].inferred
            val rightInferred = beat < cuts.size && cuts[beat].inferred
            PlacedCell(text, leftInferred || rightInferred)
        }
    }

    private fun splitWide(spans: List<Span>, target: Int): List<Span> {
        return spans.flatMap { explode(it, target) }
    }

    private fun explode(span: Span, target: Int): List<Span> {
        if (span.count <= target || span.beatXs.isEmpty()) return listOf(span)
        val ideal = span.left + (span.right - span.left) * target / span.count.toFloat()
        val index = span.beatXs.indices.minBy { abs(span.beatXs[it] - ideal) }
        val cut = span.beatXs[index]
        if (cut <= span.left + 1f || cut >= span.right - 1f) return listOf(span)
        val left = Span(span.left, cut, span.beatXs.take(index))
        val right = Span(cut, span.right, span.beatXs.drop(index + 1))
        return explode(left, target) + explode(right, target)
    }

    private fun mergeShort(spans: List<Span>, target: Int): List<Span> {
        if (spans.size < 2) return spans
        val merged = mutableListOf<Span>()
        var index = 0
        while (index < spans.size) {
            val here = spans[index]
            val next = spans.getOrNull(index + 1)
            if (next != null && here.count != target && here.count + next.count == target) {
                merged.add(Span(here.left, next.right, here.beatXs + here.right + next.beatXs))
                index += 2
            } else {
                merged.add(here)
                index += 1
            }
        }
        return merged
    }

    private fun fillShort(spans: List<Span>, target: Int): List<Span> {
        return spans.map { span ->
            if (span.count == target) {
                span.copy(cuts = span.beatXs.map { PlacedCut(it, false) })
            } else {
                span.copy(cuts = placeCuts(span, target))
            }
        }
    }

    private fun placeCuts(span: Span, target: Int): List<PlacedCut> {
        val need = target - 1
        if (need <= 0) return emptyList()
        val width = (span.right - span.left).coerceAtLeast(1f)
        val limit = width / target * 0.45f
        val used = mutableSetOf<Int>()
        return (1 until target).map { slot ->
            val ideal = span.left + width * slot / target
            val index = span.beatXs.indices
                .filter { it !in used }
                .minByOrNull { abs(span.beatXs[it] - ideal) }
            if (index != null && abs(span.beatXs[index] - ideal) <= limit) {
                used.add(index)
                PlacedCut(span.beatXs[index], false)
            } else {
                PlacedCut(ideal, true)
            }
        }
    }

    private fun initialSpans(box: OpenBox): List<Span> {
        if (box.right - box.left < 2f) return emptyList()
        val walls = (listOf(box.left, box.right) + box.columns.filter { it.kind == DelimiterKind.MEASURE }.map { it.x })
            .filter { it >= box.left - 0.5f && it <= box.right + 0.5f }
            .sorted()
            .fold(mutableListOf<Float>()) { kept, x ->
                if (kept.isEmpty() || x - kept.last() > 1.5f) kept.add(x)
                kept
            }
        if (walls.size < 2) return emptyList()
        val beats = box.columns.filter { it.kind == DelimiterKind.BEAT }.map { it.x }
        return walls.zipWithNext { left, right ->
            Span(left, right, beats.filter { it > left + 1.5f && it < right - 1.5f })
        }
    }

    private fun cellText(symbols: List<OcrSymbol>, left: Float, right: Float, box: OpenBox): String {
        val kept = symbols
            .filter { it.centerX > left && it.centerX < right && !delimiterGlyph(it, box) }
            .sortedBy { it.centerX }
        return SolfaCellMapper.fromOcrText(kept.joinToString("") { it.plain() })
    }

    private fun delimiterGlyph(symbol: OcrSymbol, box: OpenBox): Boolean {
        val text = symbol.plain()
        if (!isColon(text) && !isPipe(text)) return false
        return box.columns.any { abs(it.x - symbol.centerX) <= box.tolerance }
    }

    private fun glyphGroups(voices: List<List<OcrSymbol>>, tolerance: Float): List<List<GlyphHit>> {
        val hits = voices.flatMapIndexed { voice, symbols ->
            symbols.mapNotNull { symbol ->
                val text = symbol.plain()
                when {
                    isColon(text) -> GlyphHit(voice, symbol, colon = true)
                    isPipe(text) -> GlyphHit(voice, symbol, colon = false)
                    else -> null
                }
            }
        }.sortedBy { it.symbol.centerX }
        if (hits.isEmpty()) return emptyList()
        val groups = mutableListOf(mutableListOf(hits.first()))
        for (hit in hits.drop(1)) {
            val previous = groups.last().last().symbol.centerX
            if (hit.symbol.centerX - previous <= tolerance) groups.last().add(hit) else groups.add(mutableListOf(hit))
        }
        return groups
    }

    private fun strokesAt(
        x: Float,
        strokes: List<VerticalStroke>,
        tolerance: Float,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
    ): List<VerticalStroke> {
        return strokeGroups(strokes, tolerance, left, right, top, bottom)
            .firstOrNull { group ->
                val xs = group.map { it.x }.sorted()
                abs(xs[xs.size / 2] - x) <= tolerance
            }
            .orEmpty()
    }

    private fun strokeGroups(
        strokes: List<VerticalStroke>,
        tolerance: Float,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
    ): List<List<VerticalStroke>> {
        val margin = max(tolerance, 8f)
        val internal = strokes
            .filter { stroke ->
                stroke.x > left + margin && stroke.x < right - margin && overlaps(stroke, top, bottom)
            }
            .sortedBy { it.x }
        if (internal.isEmpty()) return emptyList()
        val groups = mutableListOf(mutableListOf(internal.first()))
        for (stroke in internal.drop(1)) {
            if (stroke.x - groups.last().last().x <= tolerance) groups.last().add(stroke) else groups.add(mutableListOf(stroke))
        }
        return groups
    }

    private fun overlaps(stroke: VerticalStroke, top: Float, bottom: Float): Boolean {
        return min(stroke.bottom.toFloat(), bottom) - max(stroke.top.toFloat(), top) > 4f
    }

    private fun continuous(group: List<VerticalStroke>, top: Float, bottom: Float): Boolean {
        if (group.isEmpty()) return false
        val height = (bottom - top).coerceAtLeast(1f)
        return group.any { stroke ->
            val overlap = min(stroke.bottom.toFloat(), bottom) - max(stroke.top.toFloat(), top)
            overlap >= height * 0.7f
        }
    }

    private fun addMarker(markers: MutableList<Float>, x: Float, tolerance: Float) {
        if (markers.none { abs(it - x) <= tolerance }) markers.add(x)
    }

    private fun measureBar(
        group: List<VerticalStroke>,
        top: Float,
        bottom: Float,
        voiceCount: Int,
    ): Boolean {
        if (group.isEmpty()) return false
        if (continuous(group, top, bottom)) return true
        return voiceCount > 1 && group.size < voiceCount
    }

    private fun toleranceOf(symbols: List<OcrSymbol>): Float {
        val widths = symbols.map { it.width }.filter { it >= 1f }.sorted()
        if (widths.isEmpty()) return 12f
        return widths[widths.size / 2].coerceIn(6f, 24f)
    }

    private fun isColon(text: String): Boolean {
        return text.length == 1 && text[0] in colonChars
    }

    private fun isPipe(text: String): Boolean {
        return text.length == 1 && text[0] in pipeChars
    }

    private fun overlay(draft: ScanDraft): ScanDebug {
        return ScanDebug(
            notes = draft.notes.filter { it.musical },
            beats = draft.boxes.flatMap { it.markers },
            windows = draft.windows,
            lines = draft.lines,
            blocks = draft.blocks,
        )
    }

    private data class GlyphHit(val voice: Int, val symbol: OcrSymbol, val colon: Boolean)

    private data class PlacedCut(val x: Float, val inferred: Boolean)

    private data class PlacedCell(val text: String, val inferred: Boolean)

    private data class Span(
        val left: Float,
        val right: Float,
        val beatXs: List<Float>,
        val cuts: List<PlacedCut> = emptyList(),
    ) {
        val count: Int get() = beatXs.size + 1
    }

    private data class ClosedBox(
        val voices: List<List<PlacedCell>>,
    )
}
