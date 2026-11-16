package mg.maniry.doremii.omr

import kotlin.math.max
import kotlin.math.min

data class ClassifiedLines(
    val lines: List<DebugLine>,
    val blocks: List<DebugBlock>,
)

object MusicalLineClassifier {
    fun classify(ink: InkImage, lines: List<DebugLine>, blocks: List<DebugBlock>): ClassifiedLines {
        if (lines.isEmpty() || blocks.isEmpty()) return ClassifiedLines(lines, emptyList())
        val strokes = BarlineDetector.strokes(ink)
        val musical = BooleanArray(lines.size)
        val kept = mutableListOf<DebugBlock>()
        for (block in blocks) {
            val members = lines.indices.filter { belongs(lines[it], block) }
            val chosen = musicalMembers(ink, lines, members, block, strokes)
            if (chosen.isEmpty()) continue
            kept.add(block)
            for (index in chosen) musical[index] = true
        }
        val classified = lines.mapIndexed { index, line ->
            if (line.musical == musical[index]) line else line.copy(musical = musical[index])
        }
        return ClassifiedLines(classified, kept)
    }

    private fun belongs(line: DebugLine, block: DebugBlock): Boolean {
        val center = (line.top + line.bottom) / 2f
        return center >= block.top && center <= block.bottom
    }

    private fun musicalMembers(
        ink: InkImage,
        lines: List<DebugLine>,
        members: List<Int>,
        block: DebugBlock,
        strokes: List<VerticalStroke>,
    ): List<Int> {
        if (members.isEmpty()) return emptyList()
        val heights = members.map { lines[it].bottom - lines[it].top }.filter { it >= 1f }.sorted()
        val typical = if (heights.isEmpty()) 12f else heights[heights.size / 2]
        val tolerance = max(4f, typical * 0.35f)
        if (members.size == 1) {
            val index = members[0]
            val marks = merge(glyphXs(ink, lines[index], block), tolerance)
            return if (marks.isNotEmpty()) listOf(index) else emptyList()
        }
        val markSets = members.map { index ->
            val line = lines[index]
            val xs = glyphXs(ink, line, block).toMutableList()
            for (stroke in strokes) {
                if (stroke.x < block.left || stroke.x > block.right) continue
                if (!overlaps(stroke, line)) continue
                xs.add(stroke.x)
            }
            merge(xs, tolerance)
        }
        val scores = columnScores(markSets, tolerance)
        val reference = scores.maxOrNull() ?: 0
        if (reference <= 0) return emptyList()
        val floor = reference * 4
        return members.filterIndexed { offset, _ ->
            val score = scores[offset]
            score > 0 && score * 5 >= floor
        }
    }

    private fun glyphXs(ink: InkImage, line: DebugLine, block: DebugBlock): List<Float> {
        return BeatMarks.glyphXs(ink, line.top, line.bottom, block.left, block.right)
    }

    private fun overlaps(stroke: VerticalStroke, line: DebugLine): Boolean {
        val overlap = min(stroke.bottom.toFloat(), line.bottom) - max(stroke.top.toFloat(), line.top)
        val height = (line.bottom - line.top).coerceAtLeast(1f)
        return overlap >= height * 0.4f
    }

    private fun merge(xs: List<Float>, tolerance: Float): List<Float> {
        if (xs.isEmpty()) return emptyList()
        val sorted = xs.sorted()
        val out = mutableListOf(sorted.first())
        for (x in sorted.drop(1)) {
            if (x - out.last() > tolerance) out.add(x)
        }
        return out
    }

    private fun columnScores(markSets: List<List<Float>>, tolerance: Float): IntArray {
        val hits = markSets.flatMapIndexed { index, xs -> xs.map { index to it } }.sortedBy { it.second }
        val scores = IntArray(markSets.size)
        var start = 0
        while (start < hits.size) {
            var end = start + 1
            while (end < hits.size && hits[end].second - hits[end - 1].second <= tolerance) end++
            val lines = HashSet<Int>()
            for (index in start until end) lines.add(hits[index].first)
            if (lines.size >= 2) {
                for (line in lines) scores[line]++
            }
            start = end
        }
        return scores
    }
}
