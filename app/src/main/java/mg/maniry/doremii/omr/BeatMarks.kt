package mg.maniry.doremii.omr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object BeatMarks {
    private const val edgeMargin = 8f
    private const val clusterTolerance = 12f

    fun detect(
        ink: InkImage,
        lines: List<DebugNoteBox>,
        strokes: List<VerticalStroke>,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
    ): List<DelimiterColumn> {
        val musical = lines.filter { it.musical }.sortedBy { it.top }
        if (musical.isEmpty()) return emptyList()
        val marks = mutableListOf<Mark>()
        musical.forEachIndexed { index, line ->
            marks += marksInLine(ink, line, index, left, right)
        }
        marks += strokeMarks(strokes, musical, left, right, top, bottom)
        return columns(marks, musical.size)
    }

    internal fun glyphXs(ink: InkImage, top: Float, bottom: Float, left: Float, right: Float): List<Float> {
        val y0 = top.roundToInt().coerceIn(0, ink.height)
        val y1 = bottom.roundToInt().coerceIn(0, ink.height)
        val height = y1 - y0
        if (height < 4) return emptyList()
        val blobs = OpenCvImages.blobs(ink, y0, y1).filter { blob ->
            val x = (blob.minX + blob.maxX) / 2f
            x > left && x < right
        }
        val marks = mutableListOf<Float>()
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
            marks.add((centerX(dots[index]) + centerX(dots[match])) / 2f)
        }
        for (blob in blobs) {
            if (!isPipe(blob, height) || leavesLine(ink, centerX(blob), y0, y1)) continue
            marks.add(centerX(blob))
        }
        return marks
    }

    private fun marksInLine(
        ink: InkImage,
        line: DebugNoteBox,
        voice: Int,
        left: Float,
        right: Float,
    ): List<Mark> {
        return glyphXs(ink, line.top, line.bottom, left, right).map { Mark(it, setOf(voice), measure = false) }
    }

    private fun strokeMarks(
        strokes: List<VerticalStroke>,
        lines: List<DebugNoteBox>,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
    ): List<Mark> {
        return strokes.mapNotNull { stroke ->
            if (stroke.x <= left + edgeMargin || stroke.x >= right - edgeMargin) return@mapNotNull null
            if (min(stroke.bottom.toFloat(), bottom) - max(stroke.top.toFloat(), top) <= 4f) return@mapNotNull null
            val voices = lines.indices.filter { covers(stroke, lines[it]) }.toSet()
            if (voices.isEmpty()) return@mapNotNull null
            Mark(stroke.x, voices, measure = spansBox(stroke, top, bottom))
        }
    }

    private fun columns(marks: List<Mark>, voiceCount: Int): List<DelimiterColumn> {
        val found = clusters(marks).mapNotNull { cluster -> columnOf(cluster, voiceCount) }
        return dropWide(found).map { DelimiterColumn(it.x, it.kind) }.sortedBy { it.x }
    }

    private fun columnOf(cluster: List<Mark>, voiceCount: Int): Candidate? {
        val xs = cluster.map { it.x }.sorted()
        val median = xs[xs.size / 2]
        val chosen = (0 until voiceCount).map { voice ->
            cluster.filter { voice in it.voices }.minByOrNull { abs(it.x - median) }
        }
        if (chosen.any { it == null }) return null
        val kept = chosen.filterNotNull()
        val spread = kept.maxOf { it.x } - kept.minOf { it.x }
        val kind = if (cluster.any { it.measure }) DelimiterKind.MEASURE else DelimiterKind.BEAT
        return Candidate(median, spread, kind)
    }

    private fun dropWide(candidates: List<Candidate>): List<Candidate> {
        if (candidates.size < 2) return candidates
        return candidates.filter { candidate ->
            val others = candidates.filter { it !== candidate }.map { it.spread }.sorted()
            val typical = others[others.size / 2]
            val limit = max(typical * 2.5f, typical + 8f)
            candidate.spread <= limit
        }
    }

    private fun clusters(marks: List<Mark>): List<List<Mark>> {
        if (marks.isEmpty()) return emptyList()
        val sorted = marks.sortedBy { it.x }
        val groups = mutableListOf(mutableListOf(sorted.first()))
        for (mark in sorted.drop(1)) {
            if (mark.x - groups.last().last().x > clusterTolerance) groups.add(mutableListOf(mark))
            else groups.last().add(mark)
        }
        return groups
    }

    private fun covers(stroke: VerticalStroke, line: DebugNoteBox): Boolean {
        val overlap = min(stroke.bottom.toFloat(), line.bottom) - max(stroke.top.toFloat(), line.top)
        val height = (line.bottom - line.top).coerceAtLeast(1f)
        return overlap >= height * 0.4f
    }

    private fun spansBox(stroke: VerticalStroke, top: Float, bottom: Float): Boolean {
        val height = (bottom - top).coerceAtLeast(1f)
        val overlap = min(stroke.bottom.toFloat(), bottom) - max(stroke.top.toFloat(), top)
        return overlap >= height * 0.7f
    }

    private fun leavesLine(ink: InkImage, x: Float, top: Int, bottom: Int): Boolean {
        val start = (x - 1f).roundToInt()
        val end = (x + 1f).roundToInt()
        for (column in start..end) {
            if (column !in 0 until ink.width) continue
            if (outsideRun(ink, column, top, -1) >= 3) return true
            if (outsideRun(ink, column, bottom, 1) >= 3) return true
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

    private fun isPipe(blob: InkBlob, bandHeight: Int): Boolean {
        if (blob.count < 4) return false
        val width = blob.maxX - blob.minX + 1
        val height = blob.maxY - blob.minY + 1
        val hairline = max(3, (bandHeight * 0.22f).roundToInt())
        if (height < bandHeight * 0.45f) return false
        if (width > hairline) return false
        if (height < width * 3) return false
        return true
    }

    private fun isColonDot(blob: InkBlob, bandHeight: Int): Boolean {
        if (blob.count < 3) return false
        val width = blob.maxX - blob.minX + 1
        val height = blob.maxY - blob.minY + 1
        if (width > bandHeight * 0.7f) return false
        if (height > bandHeight * 0.62f) return false
        if (width > height * 2.8f) return false
        if (isPipe(blob, bandHeight)) return false
        return true
    }

    private fun isColon(upper: InkBlob, lower: InkBlob, bandHeight: Int): Boolean {
        if (lower.minY <= upper.maxY) return false
        val gap = lower.minY - upper.maxY - 1
        if (gap < 1 || gap > bandHeight * 0.65f) return false
        val span = lower.maxY - upper.minY + 1
        if (span < bandHeight * 0.4f) return false
        val align = max(upper.maxX - upper.minX + 1, lower.maxX - lower.minX + 1).toFloat().coerceAtLeast(4f)
        if (abs(centerX(upper) - centerX(lower)) > align) return false
        return true
    }

    private fun centerX(blob: InkBlob): Float = (blob.minX + blob.maxX) / 2f

    private data class Mark(val x: Float, val voices: Set<Int>, val measure: Boolean)

    private data class Candidate(val x: Float, val spread: Float, val kind: DelimiterKind)
}
