package mg.maniry.doremii.omr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object LineMarks {
    fun find(ink: InkImage, lines: List<DebugLine>, blocks: List<DebugBlock>): List<DebugMark> {
        if (lines.isEmpty() || ink.width < 2 || ink.height < 2) return emptyList()
        val bytes = mask(ink) ?: return emptyList()
        val found = mutableListOf<DebugMark>()
        for (line in lines) {
            found += scan(bytes, ink.width, ink.height, line, blocks)
        }
        return merge(found)
    }

    private fun scan(
        bytes: ByteArray,
        width: Int,
        height: Int,
        line: DebugLine,
        blocks: List<DebugBlock>,
    ): List<DebugMark> {
        val top = line.top.roundToInt().coerceIn(0, height)
        val bottom = line.bottom.roundToInt().coerceIn(top, height)
        val band = bottom - top
        if (band < 4) return emptyList()
        val block = owningBlock(line, blocks)
        val pad = max(8f, band.toFloat())
        val x0 = ((block?.left ?: (line.left - pad)) - 2f).roundToInt().coerceIn(0, width)
        val x1 = ((block?.right ?: (line.right + pad)) + 2f).roundToInt().coerceIn(x0, width)
        if (x1 - x0 < 2) return emptyList()
        val maxWidth = max(5, (band * 0.45f).roundToInt())
        val thickLimit = max(5, (band * 0.38f).roundToInt())
        val textLeft = line.left - 3f
        val textRight = line.right + 3f
        val marks = mutableListOf<DebugMark>()
        var x = x0
        while (x < x1) {
            if (!columnHasInk(bytes, width, x, top, bottom)) {
                x++
                continue
            }
            val start = x
            while (x < x1 && columnHasInk(bytes, width, x, top, bottom)) x++
            val end = x
            if (end - start > maxWidth) continue
            if (!isolated(bytes, width, start, end, top, bottom, x0, x1)) continue
            if (thickness(bytes, width, start, end, top, bottom) > thickLimit) continue
            val rows = BooleanArray(band) { row ->
                rowHit(bytes, width, start, end, top + row)
            }
            val runs = rowRuns(rows).filter { length(it) >= 2 }
            val leaves = outsideDark(bytes, width, height, start, end, top - 1, -1) >= 3 ||
                outsideDark(bytes, width, height, start, end, bottom, 1) >= 3
            val center = (start + end) / 2f
            val inText = center >= textLeft && center <= textRight
            when {
                inText && runs.size == 2 && !leaves && colon(runs[0], runs[1], band) -> {
                    marks += DebugMark(
                        start.toFloat(),
                        (top + runs[0].first).toFloat(),
                        end.toFloat(),
                        (top + runs[1].last + 1).toFloat(),
                        DebugMarkKind.COLON,
                    )
                }
                inText && !leaves && pipe(runs, band) -> {
                    val run = runs.maxBy { length(it) }
                    marks += DebugMark(
                        start.toFloat(),
                        (top + run.first).toFloat(),
                        end.toFloat(),
                        (top + run.last + 1).toFloat(),
                        DebugMarkKind.PIPE,
                    )
                }
                leaves && covers(rows, band) -> {
                    val traced = trace(bytes, width, height, start, end, top, bottom)
                    marks += DebugMark(
                        start.toFloat(),
                        traced.first.toFloat(),
                        end.toFloat(),
                        traced.second.toFloat(),
                        DebugMarkKind.BAR,
                    )
                }
            }
        }
        return marks
    }

    private fun colon(upper: IntRange, lower: IntRange, band: Int): Boolean {
        if (lower.first < upper.last) return false
        val gap = lower.first - upper.last - 1
        if (gap < 1 || gap > band * 0.7f) return false
        val upperHeight = length(upper)
        val lowerHeight = length(lower)
        if (upperHeight > band * 0.55f || lowerHeight > band * 0.55f) return false
        val span = lower.last + 1 - upper.first
        if (span < band * 0.28f) return false
        val small = min(upperHeight, lowerHeight).coerceAtLeast(1)
        return max(upperHeight, lowerHeight) <= small * 2.5f
    }

    private fun pipe(runs: List<IntRange>, band: Int): Boolean {
        if (runs.isEmpty()) return false
        val longest = runs.maxOf { length(it) }
        return longest >= band * 0.42f
    }

    private fun length(run: IntRange): Int {
        return run.last - run.first + 1
    }

    private fun covers(rows: BooleanArray, band: Int): Boolean {
        var dark = 0
        for (row in rows) if (row) dark++
        return dark >= band * 0.4f
    }

    private fun owningBlock(line: DebugLine, blocks: List<DebugBlock>): DebugBlock? {
        val mid = (line.top + line.bottom) / 2f
        return blocks.firstOrNull { block ->
            mid >= block.top && mid <= block.bottom && line.left <= block.right && line.right >= block.left
        }
    }

    private fun columnHasInk(bytes: ByteArray, width: Int, x: Int, top: Int, bottom: Int): Boolean {
        var count = 0
        for (y in top until bottom) {
            if (!dark(bytes, width, x, y)) continue
            count++
            if (count >= 2) return true
        }
        return false
    }

    private fun isolated(
        bytes: ByteArray,
        width: Int,
        start: Int,
        end: Int,
        top: Int,
        bottom: Int,
        limitLeft: Int,
        limitRight: Int,
    ): Boolean {
        if (start > limitLeft && inkFraction(bytes, width, start - 1, top, bottom) > 0.35f) return false
        if (end < limitRight && inkFraction(bytes, width, end, top, bottom) > 0.35f) return false
        return true
    }

    private fun inkFraction(bytes: ByteArray, width: Int, x: Int, top: Int, bottom: Int): Float {
        val band = (bottom - top).coerceAtLeast(1)
        var count = 0
        for (y in top until bottom) if (dark(bytes, width, x, y)) count++
        return count.toFloat() / band
    }

    private fun thickness(
        bytes: ByteArray,
        pageWidth: Int,
        start: Int,
        end: Int,
        top: Int,
        bottom: Int,
    ): Int {
        val x = (start + end - 1) / 2
        val samples = ArrayList<Int>(bottom - top)
        for (y in top until bottom) {
            if (dark(bytes, pageWidth, x, y)) samples.add(horizontalRun(bytes, pageWidth, x, y))
        }
        if (samples.isEmpty()) return Int.MAX_VALUE
        samples.sort()
        return samples[samples.size / 2]
    }

    private fun horizontalRun(bytes: ByteArray, width: Int, x: Int, y: Int): Int {
        var run = 1
        var cursor = x - 1
        while (cursor >= 0 && dark(bytes, width, cursor, y)) {
            run++
            cursor--
        }
        cursor = x + 1
        while (cursor < width && dark(bytes, width, cursor, y)) {
            run++
            cursor++
        }
        return run
    }

    private fun rowHit(bytes: ByteArray, width: Int, start: Int, end: Int, y: Int): Boolean {
        if (y !in 0 until bytes.size / width) return false
        for (x in start until end) if (dark(bytes, width, x, y)) return true
        return false
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

    private fun outsideDark(
        bytes: ByteArray,
        width: Int,
        height: Int,
        start: Int,
        end: Int,
        yStart: Int,
        step: Int,
    ): Int {
        var y = yStart
        var gap = 0
        var count = 0
        while (count < 6 && y in 0 until height && gap <= 2) {
            if (rowHit(bytes, width, start, end, y)) {
                count++
                gap = 0
            } else {
                gap++
            }
            y += step
        }
        return count
    }

    private fun trace(
        bytes: ByteArray,
        width: Int,
        height: Int,
        start: Int,
        end: Int,
        top: Int,
        bottom: Int,
    ): Pair<Int, Int> {
        var tracedTop = top
        var gap = 0
        var y = top - 1
        while (y >= 0 && gap <= 2) {
            if (rowHit(bytes, width, start, end, y)) {
                tracedTop = y
                gap = 0
            } else {
                gap++
            }
            y--
        }
        var tracedBottom = bottom
        gap = 0
        y = bottom
        while (y < height && gap <= 2) {
            if (rowHit(bytes, width, start, end, y)) {
                tracedBottom = y + 1
                gap = 0
            } else {
                gap++
            }
            y++
        }
        return tracedTop to tracedBottom.coerceAtLeast(tracedTop + 1)
    }

    private fun merge(marks: List<DebugMark>): List<DebugMark> {
        val ordered = marks.sortedWith(compareBy({ it.kind.ordinal }, { it.top }, { it.left }))
        val kept = mutableListOf<DebugMark>()
        for (mark in ordered) {
            val index = kept.indexOfFirst { same(it, mark) }
            if (index < 0) kept.add(mark) else kept[index] = union(kept[index], mark)
        }
        return kept.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun same(first: DebugMark, second: DebugMark): Boolean {
        if (first.kind != second.kind) return false
        val overlap = min(first.right, second.right) - max(first.left, second.left)
        if (overlap <= 0f) return false
        val gap = max(first.top, second.top) - min(first.bottom, second.bottom)
        return gap <= 4f
    }

    private fun union(first: DebugMark, second: DebugMark): DebugMark {
        return first.copy(
            left = min(first.left, second.left),
            top = min(first.top, second.top),
            right = max(first.right, second.right),
            bottom = max(first.bottom, second.bottom),
        )
    }

    private fun dark(bytes: ByteArray, width: Int, x: Int, y: Int): Boolean {
        return bytes[y * width + x].toInt() != 0
    }

    private fun mask(ink: InkImage): ByteArray? {
        val view = OpenCvImages.inkOf(ink)
        try {
            val count = ink.width * ink.height
            if (count <= 0) return null
            val bytes = ByteArray(count)
            view.mat.get(0, 0, bytes)
            return bytes
        } finally {
            view.close()
        }
    }
}
