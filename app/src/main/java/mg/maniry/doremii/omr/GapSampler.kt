package mg.maniry.doremii.omr

import kotlin.math.abs

enum class GapMark { COLON, DOT, DASH }

object GapSampler {
    fun sample(ink: InkImage, x: Int, top: Int, bottom: Int, baseline: Int): GapMark? {
        if (ink.width == 0 || ink.height == 0) return null
        val y0 = top.coerceIn(0, ink.height)
        val y1 = bottom.coerceIn(0, ink.height)
        if (y1 - y0 < 4) return null
        val rows = BooleanArray(y1 - y0)
        for (i in rows.indices) {
            val y = y0 + i
            rows[i] = (-1..1).any { dx -> ink.isDark(x + dx, y) }
        }
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
        if (runs.size >= 2) {
            val gap = runs[1].first - runs[0].last
            if (gap >= 1) return GapMark.COLON
        }
        if (runs.isEmpty()) return null
        val run = runs[0]
        val mid = y0 + (run.first + run.last) / 2
        val runHeight = (run.last - run.first + 1).coerceAtLeast(1)
        val total = y1 - y0
        val horizontal = horizontalWidth(ink, x, mid)
        if (horizontal >= runHeight * 2 && runHeight <= total * 0.4f) return GapMark.DASH
        if (abs(mid - baseline) <= total * 0.35f) return GapMark.DOT
        return null
    }

    private fun horizontalWidth(ink: InkImage, x: Int, y: Int): Int {
        if (y !in 0 until ink.height) return 0
        var width = 0
        var cursor = x
        while (cursor >= 0 && ink.isDark(cursor, y)) {
            width++
            cursor--
        }
        cursor = x + 1
        while (cursor < ink.width && ink.isDark(cursor, y)) {
            width++
            cursor++
        }
        return width
    }
}
