package mg.maniry.doremii.omr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object OcrBands {
    fun find(projection: IntArray): List<IntRange> {
        if (projection.isEmpty()) return emptyList()
        val sorted = projection.sorted()
        val floor = sorted[projection.size / 8]
        val peak = projection.max()
        val rise = peak - floor
        if (rise <= 1) return emptyList()
        val minInk = floor + max(2, (rise * 0.12f).roundToInt())
        val ink = BooleanArray(projection.size) { projection[it] >= minInk }
        for (index in 1 until ink.size - 1) {
            if (!ink[index] && ink[index - 1] && ink[index + 1]) ink[index] = true
        }
        val bands = mutableListOf<IntRange>()
        var start = -1
        for (index in ink.indices) {
            if (ink[index] && start < 0) start = index
            val closing = start >= 0 && (!ink[index] || index == ink.lastIndex)
            if (!closing) continue
            val end = if (ink[index]) index else index - 1
            if (end - start + 1 >= 2) bands.add(start..end)
            start = -1
        }
        return mergeMarks(bands)
    }

    fun expand(bands: List<IntRange>, height: Int): List<IntRange> {
        if (height <= 0) return emptyList()
        return bands.mapIndexed { index, band ->
            val gapAbove = if (index == 0) band.first else band.first - bands[index - 1].last - 1
            val gapBelow = if (index == bands.lastIndex) {
                height - 1 - band.last
            } else {
                bands[index + 1].first - band.last - 1
            }
            val top = (band.first - min(3, gapAbove / 2)).coerceAtLeast(0)
            val bottom = (band.last + min(3, gapBelow / 2)).coerceAtMost(height - 1)
            top..bottom
        }
    }

    fun resolutionDpi(scaledLineHeight: Int): Int {
        val xHeight = scaledLineHeight.coerceAtLeast(1) * 0.55f
        return (xHeight * 72f / 18f).roundToInt().coerceIn(70, 300)
    }

    private fun mergeMarks(bands: List<IntRange>): List<IntRange> {
        if (bands.size < 2) return bands
        val merged = mutableListOf(bands.first())
        for (band in bands.drop(1)) {
            val previous = merged.last()
            val gap = band.first - previous.last - 1
            val previousHeight = previous.last - previous.first + 1
            val bandHeight = band.last - band.first + 1
            val markAbove = previousHeight <= bandHeight * 0.5f && gap <= previousHeight
            val markBelow = bandHeight <= previousHeight * 0.5f && gap <= bandHeight
            if (markAbove || markBelow) {
                merged[merged.lastIndex] = previous.first..band.last
            } else {
                merged.add(band)
            }
        }
        return merged
    }
}
