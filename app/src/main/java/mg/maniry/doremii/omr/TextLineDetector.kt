package mg.maniry.doremii.omr

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

object TextLineDetector {
    fun detect(ink: InkImage): List<DebugLine> {
        if (ink.width < 4 || ink.height < 4) return emptyList()
        val mask = OpenCvImages.inkOf(ink)
        try {
            return lines(mask.mat)
        } finally {
            mask.close()
        }
    }

    private fun lines(ink: Mat): List<DebugLine> {
        val glyph = glyphHeight(ink)
        val vertical = Mat()
        val text = Mat()
        val kernelHeight = (glyph * 2).coerceAtLeast(8)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, kernelHeight.toDouble()))
        try {
            Imgproc.morphologyEx(ink, vertical, Imgproc.MORPH_OPEN, kernel)
            Core.subtract(ink, vertical, text)
            val counts = OpenCvImages.rowCounts(text)
            val peak = counts.maxOrNull() ?: 0
            if (peak < 3) return emptyList()
            val minInk = max(3, (peak * 0.08f).roundToInt())
            val rows = BooleanArray(counts.size) { counts[it] >= minInk }
            bridge(rows, max(1, glyph / 6))
            val bands = runs(rows).flatMap { splitTall(it, counts, glyph, minInk) }
            return mergeMarks(bands, glyph).mapNotNull { bounds(text, it, glyph) }
        } finally {
            vertical.release()
            text.release()
            kernel.release()
        }
    }

    private fun glyphHeight(ink: Mat): Int {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            val count = Imgproc.connectedComponentsWithStats(ink, labels, stats, centroids, 8, CvType.CV_32S)
            val heights = ArrayList<Int>(max(0, count - 1))
            for (index in 1 until count) {
                val width = stats.get(index, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val height = stats.get(index, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val area = stats.get(index, Imgproc.CC_STAT_AREA)[0].toInt()
                if (area < 6 || height < 3 || width < 2) continue
                if (height > width * 5) continue
                if (width > height * 8 && height <= 3) continue
                heights.add(height)
            }
            if (heights.isEmpty()) return max(8, ink.rows() / 30)
            heights.sort()
            val median = heights[heights.size / 2]
            val typical = heights.filter { it in (median * 0.45f).toInt()..(median * 2.2f).toInt() }
            val chosen = if (typical.isEmpty()) median else typical[typical.size / 2]
            return chosen.coerceIn(4, max(8, ink.rows() / 8))
        } finally {
            labels.release()
            stats.release()
            centroids.release()
        }
    }

    private fun bridge(rows: BooleanArray, gap: Int) {
        if (gap <= 0) return
        var index = 0
        while (index < rows.size) {
            if (rows[index]) {
                index++
                continue
            }
            val start = index
            while (index < rows.size && !rows[index]) index++
            val hole = index - start
            val before = start > 0 && rows[start - 1]
            val after = index < rows.size && rows[index]
            if (before && after && hole <= gap) {
                for (row in start until index) rows[row] = true
            }
        }
    }

    private fun runs(rows: BooleanArray): List<IntRange> {
        val bands = mutableListOf<IntRange>()
        var start = -1
        for (index in rows.indices) {
            if (rows[index] && start < 0) start = index
            val closing = start >= 0 && (!rows[index] || index == rows.lastIndex)
            if (!closing) continue
            val end = if (rows[index]) index else index - 1
            if (end >= start) bands.add(start..end)
            start = -1
        }
        return bands
    }

    private fun splitTall(band: IntRange, counts: IntArray, glyph: Int, minInk: Int): List<IntRange> {
        val height = band.last - band.first + 1
        if (height <= glyph * 2) return listOf(band)
        val valley = max(2, minInk / 2)
        val rows = BooleanArray(height) { counts[band.first + it] > valley }
        val parts = runs(rows).map { (band.first + it.first)..(band.first + it.last) }
        return if (parts.size < 2) listOf(band) else parts
    }

    private fun mergeMarks(bands: List<IntRange>, glyph: Int): List<IntRange> {
        if (bands.size < 2) return bands
        val thin = glyph * 0.45f
        val gapLimit = max(1, (glyph * 0.35f).roundToInt())
        val merged = mutableListOf(bands.first())
        for (band in bands.drop(1)) {
            val previous = merged.last()
            val gap = band.first - previous.last - 1
            val previousHeight = previous.last - previous.first + 1
            val bandHeight = band.last - band.first + 1
            val mark = gap <= gapLimit && (previousHeight <= thin || bandHeight <= thin)
            if (mark) merged[merged.lastIndex] = previous.first..band.last
            else merged.add(band)
        }
        return merged
    }

    private fun bounds(text: Mat, band: IntRange, glyph: Int): DebugLine? {
        val top = band.first.coerceIn(0, text.rows())
        val bottom = (band.last + 1).coerceIn(top, text.rows())
        if (bottom <= top || text.cols() <= 0) return null
        val roi = text.submat(top, bottom, 0, text.cols())
        val sums = Mat()
        try {
            Core.reduce(roi, sums, 0, Core.REDUCE_SUM, CvType.CV_32S)
            val raw = IntArray(text.cols())
            sums.get(0, 0, raw)
            var left = -1
            var right = -1
            for (x in raw.indices) {
                if (raw[x] <= 0) continue
                if (left < 0) left = x
                right = x
            }
            if (left < 0) return null
            val width = right - left + 1
            val height = bottom - top
            if (width < max(4, glyph / 2)) return null
            if (height < max(2, glyph / 5) && width < glyph * 2) return null
            var dark = 0
            for (x in left..right) dark += raw[x]
            dark /= 255
            if (height * 2 < glyph && dark < max(glyph, width / 4)) return null
            return DebugLine(left.toFloat(), top.toFloat(), (right + 1).toFloat(), bottom.toFloat())
        } finally {
            roi.release()
            sums.release()
        }
    }
}
