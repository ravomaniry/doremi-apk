package mg.maniry.doremii.omr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object BeatSignature {
    fun detect(
        lines: List<DebugLine>,
        blocks: List<DebugBlock>,
        marks: List<DebugMark>,
        beats: List<DebugMeasureLine>,
    ): Int? {
        if (blocks.isEmpty() || beats.isEmpty()) return null
        val tolerance = tolerance(lines)
        val votes = mutableListOf<Int>()
        for (block in blocks) {
            val columns = beats.filter { overlaps(it, block) }.sortedBy { it.x }
            val local = marks.filter { overlaps(it, block) }
            votes += votesBetween(columns, local, tolerance)
        }
        return majority(votes)
    }

    private fun votesBetween(
        columns: List<DebugMeasureLine>,
        marks: List<DebugMark>,
        tolerance: Float,
    ): List<Int> {
        val votes = mutableListOf<Int>()
        var open = false
        var colons = 0
        for (column in columns) {
            when (kind(column, marks, tolerance)) {
                ColumnKind.WALL -> {
                    if (open) vote(colons)?.let { votes.add(it) }
                    open = true
                    colons = 0
                }
                ColumnKind.COLON -> if (open) colons++
                ColumnKind.OTHER -> Unit
            }
        }
        return votes
    }

    private fun vote(colons: Int): Int? {
        return when (colons) {
            1 -> 4
            2 -> 3
            else -> null
        }
    }

    private fun majority(votes: List<Int>): Int? {
        if (votes.isEmpty()) return null
        val counts = votes.groupingBy { it }.eachCount()
        val best = counts.entries.maxWith(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
        val tied = counts.values.count { it == best.value } > 1
        return if (tied) null else best.key
    }

    private fun kind(
        column: DebugMeasureLine,
        marks: List<DebugMark>,
        tolerance: Float,
    ): ColumnKind {
        val hits = marks.filter { onColumn(it, column, tolerance) }
        if (hits.any { it.kind == DebugMarkKind.PIPE || it.kind == DebugMarkKind.BAR }) {
            return ColumnKind.WALL
        }
        if (hits.isNotEmpty() && hits.all { it.kind == DebugMarkKind.COLON }) return ColumnKind.COLON
        return ColumnKind.OTHER
    }

    private fun onColumn(mark: DebugMark, column: DebugMeasureLine, tolerance: Float): Boolean {
        val center = (mark.left + mark.right) / 2f
        if (abs(center - column.x) > tolerance) return false
        return min(mark.bottom, column.bottom) - max(mark.top, column.top) > 0f
    }

    private fun overlaps(beat: DebugMeasureLine, block: DebugBlock): Boolean {
        val vertical = min(beat.bottom, block.bottom) - max(beat.top, block.top)
        if (vertical <= 4f) return false
        return beat.x >= block.left - 2f && beat.x <= block.right + 2f
    }

    private fun overlaps(mark: DebugMark, block: DebugBlock): Boolean {
        val vertical = min(mark.bottom, block.bottom) - max(mark.top, block.top)
        if (vertical <= 0f) return false
        val center = (mark.left + mark.right) / 2f
        return center >= block.left - 2f && center <= block.right + 2f
    }

    private fun tolerance(lines: List<DebugLine>): Float {
        val heights = lines.map { it.bottom - it.top }.filter { it >= 1f }.sorted()
        if (heights.isEmpty()) return 8f
        return max(4f, heights[heights.size / 2] * 0.35f)
    }

    private enum class ColumnKind { WALL, COLON, OTHER }
}
