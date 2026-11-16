package mg.maniry.doremii.omr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class DividedBeats(
    val lines: List<DebugLine>,
    val beats: List<DebugMeasureLine>,
)

object BeatDividers {
    fun find(
        ink: InkImage,
        lines: List<DebugLine>,
        blocks: List<DebugBlock>,
        marks: List<DebugMark>,
    ): DividedBeats {
        if (lines.isEmpty() || blocks.isEmpty()) return DividedBeats(lines, emptyList())
        val musical = BooleanArray(lines.size) { lines[it].musical }
        val beats = mutableListOf<DebugMeasureLine>()
        for (block in blocks.sortedBy { it.top }) {
            val members = lines.indices
                .filter { musical[it] && inside(lines[it], block) }
                .sortedBy { lines[it].top }
            if (members.size < 2) continue
            val memberLines = members.map { lines[it] }
            val height = medianHeight(memberLines)
            val tolerance = max(4f, height * 0.35f)
            val columns = columns(members, lines, marks, tolerance)
            if (columns.isEmpty()) continue
            val promoted = if (columns.size < 2) {
                emptyList()
            } else {
                recover(ink, lines, musical, blocks, block, members, columns, marks, tolerance, height)
            }
            for (index in promoted) musical[index] = true
            val included = (members + promoted).map { lines[it] }
            val top = included.minOf { it.top }
            val bottom = included.maxOf { it.bottom }
            if (bottom - top < 2f) continue
            for (x in columns) beats.add(DebugMeasureLine(x, top, bottom))
        }
        val updated = lines.mapIndexed { index, line ->
            if (line.musical == musical[index]) line else line.copy(musical = musical[index])
        }
        return DividedBeats(updated, beats.sortedWith(compareBy({ it.top }, { it.x })))
    }

    private fun recover(
        ink: InkImage,
        lines: List<DebugLine>,
        musical: BooleanArray,
        blocks: List<DebugBlock>,
        block: DebugBlock,
        members: List<Int>,
        columns: List<Float>,
        marks: List<DebugMark>,
        tolerance: Float,
        height: Float,
    ): List<Int> {
        val promoted = mutableListOf<Int>()
        val taken = BooleanArray(lines.size)
        for (index in members) taken[index] = true
        for (gap in members.zipWithNext()) {
            val upper = lines[gap.first]
            val lower = lines[gap.second]
            val midpoint = (upper.bottom + lower.top) / 2f
            val candidates = lines.indices.filter { index ->
                !musical[index] && !taken[index] &&
                    inside(lines[index], block) &&
                    sameColumn(lines[index], block) &&
                    between(lines[index], upper, lower)
            }
            val chosen = bestMatch(ink, lines, candidates, columns, marks, tolerance, midpoint) ?: continue
            promoted.add(chosen)
            taken[chosen] = true
        }
        val limit = max(12f, height * 2f)
        adjacent(
            ink, lines, musical, taken, blocks, block, lines[members.first()],
            above = true, limit, height, columns, marks, tolerance,
        )?.let {
            promoted.add(it)
            taken[it] = true
        }
        adjacent(
            ink, lines, musical, taken, blocks, block, lines[members.last()],
            above = false, limit, height, columns, marks, tolerance,
        )?.let { promoted.add(it) }
        return promoted
    }

    private fun adjacent(
        ink: InkImage,
        lines: List<DebugLine>,
        musical: BooleanArray,
        taken: BooleanArray,
        blocks: List<DebugBlock>,
        block: DebugBlock,
        edge: DebugLine,
        above: Boolean,
        limit: Float,
        height: Float,
        columns: List<Float>,
        marks: List<DebugMark>,
        tolerance: Float,
    ): Int? {
        val edgeCenter = (edge.top + edge.bottom) / 2f
        val candidates = lines.indices.filter { index ->
            if (musical[index] || taken[index]) return@filter false
            val line = lines[index]
            val center = (line.top + line.bottom) / 2f
            if (above && center >= edgeCenter) return@filter false
            if (!above && center <= edgeCenter) return@filter false
            if (!separate(line, edge) || !sameColumn(line, block)) return@filter false
            if (closerToOtherBlock(line, block, blocks)) return@filter false
            val distance = if (above) edge.top - line.bottom else line.top - edge.bottom
            distance <= limit && distance >= -height * 0.35f
        }
        val nearest = if (above) {
            candidates.maxByOrNull { (lines[it].top + lines[it].bottom) / 2f }
        } else {
            candidates.minByOrNull { (lines[it].top + lines[it].bottom) / 2f }
        } ?: return null
        val anchor = (lines[nearest].top + lines[nearest].bottom) / 2f
        return bestMatch(ink, lines, listOf(nearest), columns, marks, tolerance, anchor)
    }

    private fun columns(
        members: List<Int>,
        lines: List<DebugLine>,
        marks: List<DebugMark>,
        tolerance: Float,
    ): List<Float> {
        val hits = mutableListOf<Hit>()
        for (index in members) {
            val line = lines[index]
            for (mark in marks) {
                if (!overlaps(mark, line)) continue
                hits.add(Hit(index, (mark.left + mark.right) / 2f))
            }
        }
        if (hits.isEmpty()) return emptyList()
        val sorted = hits.sortedBy { it.x }
        val groups = mutableListOf(mutableListOf(sorted.first()))
        for (hit in sorted.drop(1)) {
            val median = median(groups.last().map { it.x })
            if (abs(hit.x - median) > tolerance) groups.add(mutableListOf(hit))
            else groups.last().add(hit)
        }
        return groups.mapNotNull { group ->
            if (group.map { it.line }.toSet().size < 2) null else median(group.map { it.x })
        }.sorted()
    }

    private fun bestMatch(
        ink: InkImage,
        lines: List<DebugLine>,
        candidates: List<Int>,
        columns: List<Float>,
        marks: List<DebugMark>,
        tolerance: Float,
        anchor: Float,
    ): Int? {
        var chosen = -1
        var bestScore = 0
        var bestDistance = Float.MAX_VALUE
        for (index in candidates) {
            val score = matchedColumns(ink, lines[index], columns, marks, tolerance)
            if (score < 2) continue
            val center = (lines[index].top + lines[index].bottom) / 2f
            val distance = abs(center - anchor)
            if (chosen < 0 || score > bestScore || (score == bestScore && distance < bestDistance)) {
                bestScore = score
                bestDistance = distance
                chosen = index
            }
        }
        return if (chosen >= 0) chosen else null
    }

    private fun matchedColumns(
        ink: InkImage,
        line: DebugLine,
        columns: List<Float>,
        marks: List<DebugMark>,
        tolerance: Float,
    ): Int {
        var count = 0
        for (x in columns) {
            val marked = marks.any { markAt(it, line, x, tolerance) }
            if (marked || delimiterAt(ink, line, x, tolerance)) count++
        }
        return count
    }

    private fun markAt(mark: DebugMark, line: DebugLine, x: Float, tolerance: Float): Boolean {
        if (abs((mark.left + mark.right) / 2f - x) > tolerance) return false
        return overlaps(mark, line)
    }

    private fun delimiterAt(ink: InkImage, line: DebugLine, x: Float, tolerance: Float): Boolean {
        if (ink.width < 2 || ink.height < 2) return false
        val top = line.top.roundToInt().coerceIn(0, ink.height)
        val bottom = line.bottom.roundToInt().coerceIn(top, ink.height)
        val band = bottom - top
        if (band < 4) return false
        val reach = max(2, tolerance.roundToInt())
        val x0 = (x.roundToInt() - reach).coerceIn(0, ink.width - 1)
        val x1 = (x.roundToInt() + reach).coerceIn(x0, ink.width - 1)
        for (column in x0..x1) {
            if (columnDelimiter(ink, column, top, bottom, band)) return true
        }
        return false
    }

    private fun columnDelimiter(ink: InkImage, x: Int, top: Int, bottom: Int, band: Int): Boolean {
        val rows = BooleanArray(band) { row -> ink.isDark(x, top + row) }
        var dark = 0
        for (row in rows) if (row) dark++
        if (dark < 2) return false
        if (thickness(ink, x, top, bottom) > max(5, (band * 0.4f).roundToInt())) return false
        val runs = rowRuns(rows).filter { it.last - it.first + 1 >= 2 }
        if (runs.size >= 2) {
            for (index in 0 until runs.lastIndex) {
                if (colon(runs[index], runs[index + 1], band)) return true
            }
        }
        if (runs.isNotEmpty() && runs.maxOf { it.last - it.first + 1 } >= band * 0.42f) return true
        if (dark >= band * 0.4f && (outside(ink, x, top - 1, -1) >= 3 || outside(ink, x, bottom, 1) >= 3)) {
            return true
        }
        return false
    }

    private fun colon(upper: IntRange, lower: IntRange, band: Int): Boolean {
        if (lower.first < upper.last) return false
        val gap = lower.first - upper.last - 1
        if (gap < 1 || gap > band * 0.7f) return false
        val upperHeight = upper.last - upper.first + 1
        val lowerHeight = lower.last - lower.first + 1
        if (upperHeight > band * 0.55f || lowerHeight > band * 0.55f) return false
        val span = lower.last + 1 - upper.first
        if (span < band * 0.28f) return false
        val small = min(upperHeight, lowerHeight).coerceAtLeast(1)
        return max(upperHeight, lowerHeight) <= small * 2.5f
    }

    private fun thickness(ink: InkImage, x: Int, top: Int, bottom: Int): Int {
        val samples = ArrayList<Int>(bottom - top)
        for (y in top until bottom) {
            if (ink.isDark(x, y)) samples.add(runWidth(ink, x, y))
        }
        if (samples.isEmpty()) return Int.MAX_VALUE
        samples.sort()
        return samples[samples.size / 2]
    }

    private fun runWidth(ink: InkImage, x: Int, y: Int): Int {
        var run = 1
        var cursor = x - 1
        while (cursor >= 0 && ink.isDark(cursor, y)) {
            run++
            cursor--
        }
        cursor = x + 1
        while (cursor < ink.width && ink.isDark(cursor, y)) {
            run++
            cursor++
        }
        return run
    }

    private fun outside(ink: InkImage, x: Int, start: Int, step: Int): Int {
        var count = 0
        var gap = 0
        var y = start
        while (count < 6 && y in 0 until ink.height && gap <= 2) {
            if (ink.isDark(x, y)) {
                count++
                gap = 0
            } else {
                gap++
            }
            y += step
        }
        return count
    }

    private fun rowRuns(rows: BooleanArray): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var index = 0
        while (index < rows.size) {
            if (!rows[index]) {
                index++
                continue
            }
            val start = index
            while (index < rows.size && rows[index]) index++
            runs.add(start until index)
        }
        return runs
    }

    private fun overlaps(mark: DebugMark, line: DebugLine): Boolean {
        val overlap = min(mark.bottom, line.bottom) - max(mark.top, line.top)
        if (overlap <= 0f) return false
        val height = (line.bottom - line.top).coerceAtLeast(1f)
        return overlap >= min(4f, height * 0.35f)
    }

    private fun inside(line: DebugLine, block: DebugBlock): Boolean {
        val center = (line.top + line.bottom) / 2f
        return center >= block.top && center <= block.bottom
    }

    private fun sameColumn(line: DebugLine, block: DebugBlock): Boolean {
        val center = (line.left + line.right) / 2f
        return center >= block.left && center <= block.right
    }

    private fun between(line: DebugLine, upper: DebugLine, lower: DebugLine): Boolean {
        val center = (line.top + line.bottom) / 2f
        val upperCenter = (upper.top + upper.bottom) / 2f
        val lowerCenter = (lower.top + lower.bottom) / 2f
        if (center <= upperCenter || center >= lowerCenter) return false
        return separate(line, upper) && separate(line, lower)
    }

    private fun separate(line: DebugLine, other: DebugLine): Boolean {
        val overlap = min(line.bottom, other.bottom) - max(line.top, other.top)
        if (overlap <= 0f) return true
        val height = (other.bottom - other.top).coerceAtLeast(1f)
        return overlap < height * 0.45f
    }

    private fun closerToOtherBlock(line: DebugLine, block: DebugBlock, blocks: List<DebugBlock>): Boolean {
        val center = (line.top + line.bottom) / 2f
        val distance = distanceTo(center, block)
        for (other in blocks) {
            if (other === block) continue
            if (distanceTo(center, other) < distance) return true
        }
        return false
    }

    private fun distanceTo(center: Float, block: DebugBlock): Float {
        return when {
            center < block.top -> block.top - center
            center > block.bottom -> center - block.bottom
            else -> 0f
        }
    }

    private fun medianHeight(lines: List<DebugLine>): Float {
        val heights = lines.map { it.bottom - it.top }.filter { it >= 1f }.sorted()
        if (heights.isEmpty()) return 12f
        return heights[heights.size / 2]
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private data class Hit(val line: Int, val x: Float)
}
