package mg.maniry.doremii.omr

import mg.maniry.doremii.editor.partition.PartitionData
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ParsedVoice(
    val id: String,
    val cells: List<String>,
    val inferredBeats: List<Int>,
)

data class SolfaParse(
    val voices: List<ParsedVoice>,
    val keyIndex: Int?,
    val empty: Boolean,
)

object SolfaSpatialParser {
    private val keyNames = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    private val solfaLetters = "drmfslt"
    private val voiceLetters = setOf('S', 'A', 'T', 'B')
    private val barChars = setOf("|", "I", "l", "│", "¦")
    private val musicChars = buildSet<Char> {
        addAll("drmfsltab:.,-;|/\\i0123456789'`".toList())
        add('\u2019')
        add('\u2032')
    }

    fun parse(symbols: List<OcrSymbol>, signature: Int, ink: InkImage? = null): SolfaParse {
        val sig = signature.coerceIn(2, 6)
        val key = detectKey(symbols)
        if (symbols.isEmpty()) return SolfaParse(emptyList(), key, empty = true)
        val (bars, body) = separateTallBars(symbols.filterNot { isKeyToken(it.plain()) })
        val rows = clusterRows(explode(body)).filter { isMusicRow(it) }
        if (rows.isEmpty()) return SolfaParse(emptyList(), key, empty = true)

        val voices = mutableListOf<VoiceBuilder>()
        for (system in groupSystems(rows)) {
            appendSystem(voices, system.take(12), bars, sig, ink)
        }
        if (voices.isEmpty() || voices.all { it.cells.all { cell -> cell.text.isEmpty() } }) {
            return SolfaParse(emptyList(), key, empty = true)
        }
        val width = voices.maxOf { it.cells.size }
        val padded = ceil(width / sig.toFloat()).toInt().coerceAtLeast(1) * sig
        voices.forEach { voice ->
            while (voice.cells.size < padded) voice.cells.add(BeatCell("", false))
        }
        return SolfaParse(
            voices = voices.map { voice ->
                ParsedVoice(
                    id = voice.id,
                    cells = voice.cells.map { it.text },
                    inferredBeats = voice.cells.mapIndexedNotNull { index, cell ->
                        if (cell.inferred && cell.text.isNotEmpty()) index else null
                    },
                )
            },
            keyIndex = key,
            empty = false,
        )
    }

    fun musicRowCount(symbols: List<OcrSymbol>): Int {
        val (_, body) = separateTallBars(symbols)
        return clusterRows(explode(body)).count { isMusicRow(it) }
    }

    fun looksSideways(symbols: List<OcrSymbol>): Boolean {
        if (symbols.size < 6) return false
        val mx = symbols.sumOf { it.centerX.toDouble() } / symbols.size
        val my = symbols.sumOf { it.centerY.toDouble() } / symbols.size
        var vx = 0.0
        var vy = 0.0
        for (symbol in symbols) {
            val dx = symbol.centerX - mx
            val dy = symbol.centerY - my
            vx += dx * dx
            vy += dy * dy
        }
        return vy > vx * 1.3
    }

    fun explode(symbols: List<OcrSymbol>): List<OcrSymbol> {
        return symbols.flatMap { symbol ->
            val text = symbol.plain()
            if (text.length <= 1) {
                listOf(symbol.copy(text = text))
            } else {
                val slot = symbol.width / text.length
                text.mapIndexed { index, char ->
                    OcrSymbol(
                        text = char.toString(),
                        left = symbol.left + index * slot,
                        top = symbol.top,
                        right = symbol.left + (index + 1) * slot,
                        bottom = symbol.bottom,
                        confidence = symbol.confidence * 0.5f,
                    )
                }
            }
        }
    }

    fun clusterRows(symbols: List<OcrSymbol>): List<List<OcrSymbol>> {
        if (symbols.isEmpty()) return emptyList()
        val heights = symbols.map { it.height.coerceAtLeast(1f) }.sorted()
        val median = heights[heights.size / 2]
        val (marks, notes) = symbols.partition { isAttachableMark(it, median) }
        val base = if (notes.isEmpty()) symbols else notes
        val rows = clusterByGap(base, median).map { it.toMutableList() }
        if (notes.isNotEmpty()) {
            for (mark in marks) {
                val host = rows.minByOrNull { row ->
                    abs(mark.centerY - row.sumOf { it.centerY.toDouble() } / row.size)
                } ?: continue
                val center = host.sumOf { it.centerY.toDouble() } / host.size
                val height = (host.maxOf { it.bottom } - host.minOf { it.top }).coerceAtLeast(median)
                if (abs(mark.centerY - center) <= height * 1.35) host.add(mark)
            }
        }
        return rows.map { row -> row.sortedBy { it.centerX } }
    }

    fun isMusicRow(row: List<OcrSymbol>): Boolean {
        val chars = row.joinToString("") { it.plain() }.filter { !it.isWhitespace() }
        if (chars.isEmpty()) return false
        var music = 0
        var solfa = 0
        for (char in chars) {
            val lower = char.lowercaseChar()
            if (lower in musicChars || char == '\u2019' || char == '\u2032') music++
            if (lower in solfaLetters) solfa++
        }
        if (solfa == 0 || music.toFloat() / chars.length < 0.9f) return false
        if (chars.length == 1 && chars[0].uppercaseChar() in voiceLetters && row.size == 1) return false
        return true
    }

    fun detectKey(symbols: List<OcrSymbol>): Int? {
        val ordered = symbols.sortedWith(compareBy({ it.centerY }, { it.centerX }))
        val blob = ordered.joinToString(" ") { it.plain() }
        val match = Regex("(?i)(?:do|doh)\\s*=\\s*(C#|Eb|F#|Ab|Bb|[A-G])").find(blob)
        if (match != null) {
            canonicalKey(match.groupValues[1])?.let { return it }
        }
        for (symbol in ordered) {
            val text = symbol.plain()
            val index = keyNames.indexOf(text)
            if (index < 0) continue
            if (text.length == 1 && text[0] in "DF" && hasSolfaNeighbor(symbol, ordered)) continue
            return index
        }
        return null
    }

    private fun appendSystem(
        voices: MutableList<VoiceBuilder>,
        rows: List<List<OcrSymbol>>,
        bars: List<OcrSymbol>,
        signature: Int,
        ink: InkImage?,
    ) {
        if (rows.isEmpty()) return
        val top = rows.minOf { row -> row.minOf { it.top } }
        val bottom = rows.maxOf { row -> row.maxOf { it.bottom } }
        val imageBars = if (ink != null) {
            BarlineDetector.detect(ink, top.toInt(), (bottom + 1f).toInt())
        } else {
            emptyList()
        }
        val ocrBars = bars.filter { it.bottom >= top && it.top <= bottom }.map { it.centerX }
        val barXs = imageBars + ocrBars
        val events = rows.map { rowEvents(it, barXs) }
        events.forEachIndexed { index, event ->
            ensureVoice(voices, index, event.label)
        }
        val noteXs = events.flatMap { event -> event.notes.map { it.x } }
        val walls = measureWalls(barXs, noteXs, signature)
        if (walls.size < 2) return
        val before = voices.first().cells.size
        val measureCount = walls.size - 1
        for (measure in 0 until measureCount) {
            val left = walls[measure]
            val right = walls[measure + 1]
            val last = measure == measureCount - 1
            val slices = events.map { slice(it, left, right, last) }
            val matrix = assignMeasure(slices, left, right, signature, ink)
            matrix.forEachIndexed { index, beats ->
                voices[index].cells.addAll(beats)
            }
            for (index in matrix.size until voices.size) {
                repeat(signature) { voices[index].cells.add(BeatCell("", false)) }
            }
        }
        val expected = before + measureCount * signature
        voices.forEach { voice ->
            while (voice.cells.size < expected) voice.cells.add(BeatCell("", false))
        }
    }

    private fun rowEvents(row: List<OcrSymbol>, barXs: List<Float>): RowEvents {
        val sorted = row.sortedBy { it.centerX }.toMutableList()
        var label: String? = null
        if (sorted.size >= 2) {
            val first = sorted.first().plain()
            if (first.length == 1 && first[0].uppercaseChar() in voiceLetters) {
                val gap = sorted[1].left - sorted[0].right
                val median = sorted.map { it.width }.sorted()[sorted.size / 2].coerceAtLeast(8f)
                val beforeBar = barXs.isNotEmpty() && sorted[0].centerX < barXs.min() - 1f
                if (gap > median * 0.45f || beforeBar) {
                    label = first.uppercase()
                    sorted.removeAt(0)
                }
            }
        }
        val medianWidth = sorted.map { it.width }.sorted().let { widths ->
            if (widths.isEmpty()) 12f else widths[widths.size / 2]
        }.coerceAtLeast(8f)
        val firstBar = barXs.minOrNull()
        val kept = sorted.filterNot { symbol ->
            val nearBar = barXs.any { abs(it - symbol.centerX) <= medianWidth * 0.45f }
            nearBar && (symbol.plain() in barChars || symbol.width < symbol.height * 0.45f)
        }.filterNot { symbol ->
            val text = symbol.plain()
            text.length == 1 && text[0].isDigit() && text != "1" &&
                firstBar != null && symbol.centerX < firstBar
        }
        return buildEvents(kept, label)
    }

    private fun buildEvents(symbols: List<OcrSymbol>, label: String?): RowEvents {
        val heads = symbols.filter { isSolfaLetter(it.plain()) || it.plain() == "-" }
        val consumed = mutableSetOf<OcrSymbol>()
        val notes = mutableListOf<PlacedNote>()
        val cuts = mutableListOf<PlacedCut>()
        for (head in heads) {
            if (head in consumed) continue
            consumed.add(head)
            if (head.plain() == "-") {
                notes.add(PlacedNote(head.centerX, head.left, head.right, "-", 0, head.uncertain))
                continue
            }
            val vowel = symbols.firstOrNull { mark ->
                mark !in consumed && isAccidentalVowel(mark.plain()) &&
                    mark.left >= head.centerX &&
                    mark.left - head.right < head.width.coerceAtLeast(8f) &&
                    abs(mark.centerY - head.centerY) <= head.height.coerceAtLeast(8f) * 0.5f
            }
            val syllable = SolfaCellMapper.syllable(head.plain(), vowel?.plain()) ?: continue
            if (vowel != null && syllable.length > 1) consumed.add(vowel)
            var octave = 0
            val marks = symbols.filter { mark ->
                mark !in consumed && isOctaveChar(mark.plain()) && belongsTo(mark, head, heads)
            }
            for (mark in marks) {
                consumed.add(mark)
                when (classifyMark(mark, head)) {
                    MarkRole.UP -> octave++
                    MarkRole.DOWN -> octave--
                    MarkRole.DOT -> cuts.add(PlacedCut(mark.centerX, CutKind.JOIN_DOT, mark.uncertain))
                    MarkRole.COMMA -> cuts.add(PlacedCut(mark.centerX, CutKind.JOIN_COMMA, mark.uncertain))
                }
            }
            notes.add(
                PlacedNote(
                    x = head.centerX,
                    left = head.left,
                    right = head.right,
                    syllable = syllable,
                    octave = octave.coerceIn(-3, 3),
                    uncertain = head.uncertain || marks.any { it.uncertain },
                )
            )
        }
        for (symbol in symbols) {
            if (symbol in consumed) continue
            val kind = delimiterKind(symbol.plain()) ?: continue
            if (kind == CutKind.JOIN_DOT || kind == CutKind.JOIN_COMMA) {
                if (notes.any { symbol.centerX in it.left..it.right }) continue
            }
            cuts.add(PlacedCut(symbol.centerX, kind, symbol.uncertain))
        }
        val letters = symbols.filter { isSolfaLetter(it.plain()) }
        val baseline = (if (letters.isEmpty()) symbols else letters)
            .map { it.centerY }
            .average()
            .toFloat()
        val top = symbols.minOfOrNull { it.top } ?: 0f
        val bottom = symbols.maxOfOrNull { it.bottom } ?: 0f
        return RowEvents(label, notes.sortedBy { it.x }, cuts.sortedBy { it.x }, top, bottom, baseline)
    }

    private fun slice(event: RowEvents, left: Float, right: Float, last: Boolean): RowSlice {
        fun inside(x: Float) = x >= left && (if (last) x <= right else x < right)
        return RowSlice(
            notes = event.notes.filter { inside(it.x) }.toMutableList(),
            cuts = event.cuts.filter { it.x > left && it.x < right }.toMutableList(),
            top = event.top,
            bottom = event.bottom,
            baseline = event.baseline,
        )
    }

    private fun assignMeasure(
        slices: List<RowSlice>,
        left: Float,
        right: Float,
        signature: Int,
        ink: InkImage?,
    ): List<List<BeatCell>> {
        val width = right - left
        if (width < 1f) return slices.map { List(signature) { BeatCell("", false) } }
        val strong = slices.flatMap { slice ->
            slice.cuts.filter { it.kind == CutKind.BEAT || it.kind == CutKind.PIPE }
        }
        val tolerance = max(6f, width / signature * 0.35f)
        var shared = cluster1D(strong.map { it.x }, tolerance)
        if (shared.size < signature - 1) {
            val commas = slices.flatMap { slice ->
                slice.cuts.filter { it.kind == CutKind.JOIN_COMMA }.map { it.x }
            }
            shared = cluster1D(shared + commas, tolerance)
        }
        if (ink != null && shared.size < signature - 1) {
            shared = recoverGaps(slices, left, right, signature, ink, shared)
        }
        val boundaries = snapBoundaries(left, right, signature, shared)
        return slices.map { cellsForSlice(it, boundaries, signature) }
    }

    private fun recoverGaps(
        slices: List<RowSlice>,
        left: Float,
        right: Float,
        signature: Int,
        ink: InkImage,
        shared: List<Float>,
    ): List<Float> {
        val found = shared.toMutableList()
        val quarter = (right - left) / signature
        for (slice in slices) {
            val notes = slice.notes.sortedBy { it.x }
            if (notes.size < 2) continue
            val median = notes.map { it.right - it.left }.sorted().let { it[it.size / 2] }.coerceAtLeast(8f)
            for (index in 0 until notes.lastIndex) {
                if (found.size >= signature - 1) break
                val gapStart = notes[index].right
                val gapEnd = notes[index + 1].left
                if (gapEnd - gapStart < median * 0.45f) continue
                val x = (gapStart + gapEnd) / 2f
                if (x <= left || x >= right) continue
                if (slice.notes.any { x in it.left..it.right }) continue
                val sample = GapSampler.sample(
                    ink,
                    x.roundToInt(),
                    slice.top.roundToInt(),
                    slice.bottom.roundToInt().coerceAtLeast(slice.top.roundToInt() + 4),
                    slice.baseline.roundToInt(),
                ) ?: continue
                val nearSibling = found.any { abs(it - x) <= quarter * 0.4f } ||
                    slices.any { other ->
                        other.cuts.any { cut ->
                            (cut.kind == CutKind.BEAT || cut.kind == CutKind.PIPE) &&
                                abs(cut.x - x) <= quarter * 0.4f
                        }
                    }
                when {
                    sample == GapMark.DASH -> slice.notes.add(
                        PlacedNote(x, x, x, "-", 0, uncertain = true)
                    )
                    nearSibling || sample == GapMark.COLON -> found.add(x)
                    else -> slice.cuts.add(PlacedCut(x, CutKind.JOIN_DOT, inferred = true))
                }
            }
        }
        return cluster1D(found, max(6f, quarter * 0.35f))
    }

    private fun snapBoundaries(left: Float, right: Float, signature: Int, cuts: List<Float>): List<Float> {
        val edges = MutableList(signature + 1) { index ->
            left + (right - left) * index / signature
        }
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
        return edges
    }

    private fun cellsForSlice(slice: RowSlice, boundaries: List<Float>, signature: Int): List<BeatCell> {
        val buckets = List(signature) { mutableListOf<PlacedNote>() }
        for (note in slice.notes) {
            var index = 0
            while (index < signature - 1 && note.x >= boundaries[index + 1]) index++
            buckets[index.coerceIn(0, signature - 1)].add(note)
        }
        return buckets.map { beatCell(it, slice.cuts) }
    }

    private fun beatCell(notes: List<PlacedNote>, cuts: List<PlacedCut>): BeatCell {
        val sorted = notes.sortedBy { it.x }
        if (sorted.isEmpty()) return BeatCell("", false)
        val pitched = sorted.filter { it.syllable != "-" }
        if (pitched.isEmpty()) return BeatCell("-", sorted.any { it.uncertain })
        val joins = mutableListOf<Char>()
        var inferred = pitched.any { it.uncertain }
        for (index in 0 until pitched.lastIndex) {
            val between = cuts.filter { cut ->
                cut.x in pitched[index].x..pitched[index + 1].x &&
                    (cut.kind == CutKind.JOIN_DOT || cut.kind == CutKind.JOIN_COMMA)
            }
            joins.add(if (between.any { it.kind == CutKind.JOIN_COMMA }) ',' else '.')
            if (between.any { it.inferred }) inferred = true
        }
        val text = SolfaCellMapper.toCell(
            pitched.map { MappedNote(it.syllable, it.octave) },
            joins,
        )
        return BeatCell(text, inferred)
    }

    private fun measureWalls(barXs: List<Float>, noteXs: List<Float>, signature: Int): List<Float> {
        if (noteXs.isEmpty()) return emptyList()
        val spanLeft = noteXs.min()
        val spanRight = noteXs.max()
        if (spanRight - spanLeft < 1f) return listOf(spanLeft - 16f, spanRight + 16f)
        val lines = cluster1D(barXs, max(4f, (spanRight - spanLeft) * 0.02f))
        val walls = if (lines.size >= 2) thinToMeasures(lines, noteXs, signature) else {
            val positions = cluster1D(noteXs, max(8f, (spanRight - spanLeft) / 20f))
            val measures = max(1, ceil(positions.size / signature.toFloat()).toInt())
            val pad = max(8f, (positions.last() - positions.first()) / positions.size * 0.5f)
            val start = positions.first() - pad
            val end = positions.last() + pad
            List(measures + 1) { index -> start + (end - start) * index / measures }
        }
        return extendWalls(walls, noteXs)
    }

    private fun thinToMeasures(lines: List<Float>, noteXs: List<Float>, signature: Int): List<Float> {
        if (lines.size <= 2) return lines
        val counts = lines.zipWithNext { start, end ->
            val tolerance = max(8f, (end - start) / (signature * 3f))
            cluster1D(noteXs.filter { it >= start && it < end }, tolerance).size
        }
        val median = counts.sorted()[counts.size / 2]
        if (median >= signature * 0.75f) return lines
        val step = max(1, (signature.toFloat() / median.coerceAtLeast(1)).roundToInt())
        if (step <= 1) return lines
        val kept = mutableListOf<Float>()
        var index = 0
        while (index < lines.size) {
            kept.add(lines[index])
            index += step
        }
        if (kept.last() != lines.last()) kept.add(lines.last())
        return kept
    }

    private fun extendWalls(walls: List<Float>, noteXs: List<Float>): List<Float> {
        if (walls.size < 2) {
            val left = noteXs.min()
            val right = noteXs.max()
            return listOf(left - 8f, right + 8f)
        }
        val out = walls.toMutableList()
        if (noteXs.any { it < out.first() - 4f }) out.add(0, noteXs.min() - 8f)
        if (noteXs.any { it > out.last() + 4f }) out.add(noteXs.max() + 8f)
        val cleaned = mutableListOf(out.first())
        for (x in out.drop(1)) {
            if (x - cleaned.last() > 8f) cleaned.add(x)
        }
        return if (cleaned.size >= 2) cleaned else listOf(noteXs.min() - 8f, noteXs.max() + 8f)
    }

    private fun ensureVoice(voices: MutableList<VoiceBuilder>, index: Int, label: String?) {
        while (voices.size <= index) {
            val slot = voices.size
            val backfill = voices.firstOrNull()?.cells?.size ?: 0
            val created = VoiceBuilder(PartitionData.voiceIds[slot % PartitionData.voiceIds.size])
            repeat(backfill) { created.cells.add(BeatCell("", false)) }
            voices.add(created)
        }
        val voice = voices[index]
        if (!voice.labelLocked && label != null) {
            voice.id = label
            voice.labelLocked = true
        }
    }

    private fun clusterByGap(symbols: List<OcrSymbol>, median: Float): List<MutableList<OcrSymbol>> {
        val threshold = median * 0.5f
        val rows = mutableListOf<MutableList<OcrSymbol>>()
        var rowBottom = Float.NEGATIVE_INFINITY
        for (symbol in symbols.sortedBy { it.top }) {
            if (rows.isEmpty() || symbol.top - rowBottom > threshold) {
                rows.add(mutableListOf(symbol))
                rowBottom = symbol.bottom
            } else {
                rows.last().add(symbol)
                rowBottom = max(rowBottom, symbol.bottom)
            }
        }
        return rows
    }

    private fun groupSystems(rows: List<List<OcrSymbol>>): List<List<List<OcrSymbol>>> {
        if (rows.isEmpty()) return emptyList()
        val boxes = rows.map { RowBox(it) }
        val median = boxes.map { it.height }.sorted()[boxes.size / 2].coerceAtLeast(1f)
        val systems = mutableListOf(mutableListOf(rows.first()))
        for (index in 1 until rows.size) {
            val gap = boxes[index].top - boxes[index - 1].bottom
            val same = gap <= median * 1.5f && horizontalOverlap(boxes[index - 1], boxes[index]) >= 0.4f
            if (same) systems.last().add(rows[index]) else systems.add(mutableListOf(rows[index]))
        }
        return systems
    }

    private fun horizontalOverlap(a: RowBox, b: RowBox): Float {
        val overlap = min(a.right, b.right) - max(a.left, b.left)
        if (overlap <= 0f) return 0f
        val width = min(a.width, b.width).coerceAtLeast(1f)
        return overlap / width
    }

    private fun separateTallBars(symbols: List<OcrSymbol>): Pair<List<OcrSymbol>, List<OcrSymbol>> {
        if (symbols.isEmpty()) return emptyList<OcrSymbol>() to emptyList()
        val ordinary = symbols.filter { it.plain() !in barChars || it.width > it.height * 0.45f }
        val basis = if (ordinary.isEmpty()) symbols else ordinary
        val median = basis.map { it.height.coerceAtLeast(1f) }.sorted()[basis.size / 2]
        val bars = symbols.filter { isTallBar(it, median) }
        val barSet = bars.toSet()
        return bars to symbols.filter { it !in barSet }
    }

    private fun isTallBar(symbol: OcrSymbol, median: Float): Boolean {
        val text = symbol.plain()
        if (text !in barChars) return false
        if (symbol.height < median * 1.7f) return false
        if (symbol.width > symbol.height * 0.45f) return false
        return true
    }

    private fun isAttachableMark(symbol: OcrSymbol, median: Float): Boolean {
        if (symbol.height >= median * 0.65f) return false
        val text = symbol.plain()
        if (text.length != 1) return false
        return isOctaveChar(text) || text in setOf(":", ";", "/", "\\", "|", "i", "a", "-")
    }

    private fun isSolfaLetter(text: String): Boolean {
        return text.length == 1 && text[0].lowercaseChar() in solfaLetters
    }

    private fun isAccidentalVowel(text: String): Boolean {
        return text.equals("i", true) || text.equals("a", true)
    }

    private fun isOctaveChar(text: String): Boolean {
        return text.length == 1 && text[0] in "'’′`.,1"
    }

    private fun delimiterKind(text: String): CutKind? {
        return when (text) {
            ":", ";", "/", "\\" -> CutKind.BEAT
            "|", "I", "│", "¦" -> CutKind.PIPE
            "." -> CutKind.JOIN_DOT
            "," -> CutKind.JOIN_COMMA
            else -> null
        }
    }

    private fun belongsTo(mark: OcrSymbol, note: OcrSymbol, heads: List<OcrSymbol>): Boolean {
        if (mark.centerX < note.left - 2f) return false
        val reach = note.width.coerceAtLeast(8f)
        if (mark.left - note.right > reach * 1.4f) return false
        if (abs(mark.centerY - note.centerY) > note.height.coerceAtLeast(8f) * 1.4f) return false
        val nearest = heads.filter { isSolfaLetter(it.plain()) }.minByOrNull { head ->
            abs(head.centerX - mark.centerX) + abs(head.centerY - mark.centerY)
        }
        return nearest == note
    }

    private fun classifyMark(mark: OcrSymbol, note: OcrSymbol): MarkRole {
        val text = mark.plain()
        val height = note.height.coerceAtLeast(1f)
        val above = mark.centerY < note.top + height * 0.35f
        val below = mark.centerY > note.bottom - height * 0.35f
        if (text[0] in "'’′`1") {
            return if (mark.centerY <= note.centerY) MarkRole.UP else MarkRole.DOWN
        }
        if (above) return MarkRole.UP
        if (below) return MarkRole.DOWN
        return if (text == ",") MarkRole.COMMA else MarkRole.DOT
    }

    private fun isKeyToken(text: String): Boolean {
        if (text.equals("do", true) || text.equals("doh", true) || text == "=") return true
        val compact = text.replace(" ", "")
        if (Regex("(?i)^(?:do|doh)=(?:C#|Eb|F#|Ab|Bb|[A-G])$").matches(compact)) return true
        if (text.length == 1 && text[0] in "DF") return false
        return keyNames.any { it == text }
    }

    private fun hasSolfaNeighbor(symbol: OcrSymbol, symbols: List<OcrSymbol>): Boolean {
        return symbols.any { other ->
            other != symbol && isSolfaLetter(other.plain()) &&
                abs(other.centerY - symbol.centerY) <= symbol.height.coerceAtLeast(8f) &&
                abs(other.centerX - symbol.centerX) <= symbol.width.coerceAtLeast(8f) * 4f
        }
    }

    private fun canonicalKey(raw: String): Int? {
        val name = when (raw.uppercase()) {
            "C#" -> "C#"
            "EB" -> "Eb"
            "F#" -> "F#"
            "AB" -> "Ab"
            "BB" -> "Bb"
            else -> raw.uppercase()
        }
        return keyNames.indexOf(name).takeIf { it >= 0 }
    }

    private fun cluster1D(values: List<Float>, tolerance: Float): List<Float> {
        if (values.isEmpty()) return emptyList()
        val sorted = values.sorted()
        val groups = mutableListOf(mutableListOf(sorted.first()))
        for (value in sorted.drop(1)) {
            if (value - groups.last().last() > tolerance) groups.add(mutableListOf(value))
            else groups.last().add(value)
        }
        return groups.map { group -> group.sorted()[group.size / 2] }
    }
}

private data class BeatCell(val text: String, val inferred: Boolean)

private class VoiceBuilder(var id: String) {
    val cells = mutableListOf<BeatCell>()
    var labelLocked = false
}

private data class PlacedNote(
    val x: Float,
    val left: Float,
    val right: Float,
    val syllable: String,
    val octave: Int,
    val uncertain: Boolean,
)

private data class PlacedCut(val x: Float, val kind: CutKind, val inferred: Boolean)

private enum class CutKind { BEAT, PIPE, JOIN_DOT, JOIN_COMMA }

private enum class MarkRole { UP, DOWN, DOT, COMMA }

private data class RowEvents(
    val label: String?,
    val notes: List<PlacedNote>,
    val cuts: List<PlacedCut>,
    val top: Float,
    val bottom: Float,
    val baseline: Float,
)

private data class RowSlice(
    val notes: MutableList<PlacedNote>,
    val cuts: MutableList<PlacedCut>,
    val top: Float,
    val bottom: Float,
    val baseline: Float,
)

private data class RowBox(val symbols: List<OcrSymbol>) {
    val top = symbols.minOf { it.top }
    val bottom = symbols.maxOf { it.bottom }
    val left = symbols.minOf { it.left }
    val right = symbols.maxOf { it.right }
    val height = (bottom - top).coerceAtLeast(1f)
    val width = (right - left).coerceAtLeast(1f)
}
