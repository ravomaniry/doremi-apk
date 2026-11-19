package mg.maniry.doremii.omr

import kotlin.math.abs
import kotlin.math.max

data class ClassifiedLines(
    val lines: List<DebugLine>,
    val blocks: List<DebugBlock>,
)

object MusicalLineClassifier {
    fun classify(lines: List<DebugLine>, blocks: List<DebugBlock>, marks: List<DebugMark>): ClassifiedLines {
        if (lines.isEmpty() || blocks.isEmpty()) {
            return ClassifiedLines(plain(lines), emptyList())
        }
        val colons = marks.filter { it.kind == DebugMarkKind.COLON }
        val musical = BooleanArray(lines.size)
        val kept = mutableListOf<DebugBlock>()
        for (block in blocks) {
            val members = lines.indices.filter { belongs(lines[it], block) }
            if (members.size < 2) continue
            val heights = members.map { lines[it].bottom - lines[it].top }.filter { it >= 1f }.sorted()
            val typical = if (heights.isEmpty()) 12f else heights[heights.size / 2]
            val tolerance = max(4f, typical * 0.35f)
            val colonSets = members.map { index -> colonsOn(lines[index], colons) }
            val counts = alignedCounts(colonSets, tolerance)
            val minHeight = typical * 0.5f
            var chosen = members.filterIndexed { offset, index ->
                counts[offset] >= 2 && lines[index].bottom - lines[index].top >= minHeight
            }
            if (chosen.size >= 5) {
                val tally = chosen.map { index -> counts[members.indexOf(index)] }.sorted()
                val mid = tally[tally.size / 2].toFloat()
                chosen = chosen.filter { index -> counts[members.indexOf(index)] >= mid * 0.6f }
            }
            if (chosen.isEmpty()) continue
            kept.add(block)
            for (index in chosen) musical[index] = true
        }
        val classified = lines.mapIndexed { index, line ->
            if (line.musical == musical[index]) line else line.copy(musical = musical[index])
        }
        return ClassifiedLines(classified, kept)
    }

    private fun plain(lines: List<DebugLine>): List<DebugLine> {
        return lines.map { line -> if (line.musical) line.copy(musical = false) else line }
    }

    private fun belongs(line: DebugLine, block: DebugBlock): Boolean {
        val center = (line.top + line.bottom) / 2f
        return center >= block.top && center <= block.bottom
    }

    private fun colonsOn(line: DebugLine, colons: List<DebugMark>): List<Float> {
        return colons.mapNotNull { mark ->
            val centerY = (mark.top + mark.bottom) / 2f
            if (centerY < line.top || centerY > line.bottom) {
                null
            } else {
                (mark.left + mark.right) / 2f
            }
        }
    }

    private fun alignedCounts(markSets: List<List<Float>>, tolerance: Float): IntArray {
        val merged = markSets.map { merge(it, tolerance) }
        val counts = IntArray(merged.size)
        for (line in merged.indices) {
            for (x in merged[line]) {
                val shared = merged.indices.any { other ->
                    other != line && merged[other].any { abs(it - x) <= tolerance }
                }
                if (shared) counts[line]++
            }
        }
        return counts
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
}
