package mg.maniry.doremii.omr

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

object TextLineDetector {
    fun detect(ink: InkImage, symbols: List<OcrSymbol> = emptyList()): List<DebugLine> {
        if (ink.width < 4 || ink.height < 4) return emptyList()
        val mask = OpenCvImages.inkOf(ink)
        try {
            return lines(mask.mat, symbols)
        } finally {
            mask.close()
        }
    }

    private fun lines(ink: Mat, symbols: List<OcrSymbol>): List<DebugLine> {
        val observed = glyphHeight(ink)
        val measured = characterHeight(symbols)
        val glyph = if (measured == null) observed else chosenGlyph(measured, observed)
        val vertical = Mat()
        val text = Mat()
        val kernelHeight = max(8, (glyph * 2.5f).roundToInt())
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
            var bands = runs(rows).flatMap { splitTall(it, counts, glyph, minInk) }
            bands = mergeMarks(bands, glyph)
            if (measured != null) {
                bands = mergeFragments(bands, glyph)
                bands = solidRows(bands, characterRows(symbols, glyph), glyph, counts)
            }
            bands = extendTips(bands, counts, glyph)
            return bands.mapNotNull { bounds(text, it, glyph) }
        } finally {
            vertical.release()
            text.release()
            kernel.release()
        }
    }

    private fun characterHeight(symbols: List<OcrSymbol>): Int? {
        val heights = symbols.mapNotNull { symbol ->
            val text = symbol.plain()
            val letter = text.any { it.isLetter() || it.isDigit() }
            if (!letter || symbol.height < 4f) null else symbol.height
        }.sorted()
        if (heights.size < 2) return null
        val median = heights[heights.size / 2]
        val typical = heights.filter { it in median * 0.5f..median * 1.7f }
        val chosen = (if (typical.isEmpty()) median else typical[typical.size / 2]).roundToInt()
        return chosen.takeIf { it >= 4 }
    }

    private fun chosenGlyph(measured: Int, observed: Int): Int {
        val glyph = when {
            measured > observed * 1.4f -> measured
            observed > measured * 1.4f -> observed
            else -> ((measured + observed) / 2f).roundToInt()
        }
        return glyph.coerceAtLeast(4)
    }

    private fun characterRows(symbols: List<OcrSymbol>, glyph: Int): List<IntRange> {
        val minHeight = glyph * 0.4f
        val maxHeight = glyph * 3.2f
        val body = symbols.filter { symbol ->
            val text = symbol.plain()
            text.any { it.isLetter() || it.isDigit() } && symbol.height in minHeight..maxHeight
        }.sortedWith(compareBy({ it.top }, { it.left }))
        if (body.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<OcrSymbol>>()
        for (symbol in body) {
            val group = groups.lastOrNull()
            if (group != null && sharesLine(group, symbol)) group.add(symbol)
            else groups.add(mutableListOf(symbol))
        }
        return groups.map { group ->
            group.minOf { it.top }.roundToInt()..group.maxOf { it.bottom }.roundToInt()
        }
    }

    private fun sharesLine(group: List<OcrSymbol>, symbol: OcrSymbol): Boolean {
        val top = group.minOf { it.top }
        val bottom = group.maxOf { it.bottom }
        val overlap = minOf(bottom, symbol.bottom) - maxOf(top, symbol.top)
        if (overlap <= 0f) return false
        val shorter = minOf(bottom - top, symbol.height)
        return shorter > 0f && overlap >= shorter * 0.45f
    }

    private fun mergeFragments(bands: List<IntRange>, glyph: Int): List<IntRange> {
        if (bands.size < 2) return bands
        val gapLimit = max(1, (glyph * 0.7f).roundToInt())
        val maxHeight = max(glyph + 2, (glyph * 1.55f).roundToInt())
        val merged = mutableListOf(bands.first())
        for (band in bands.drop(1)) {
            val previous = merged.last()
            val gap = band.first - previous.last - 1
            val combined = band.last - previous.first + 1
            if (gap <= gapLimit && combined <= maxHeight) {
                merged[merged.lastIndex] = previous.first..band.last
            } else {
                merged.add(band)
            }
        }
        return merged
    }

    private fun solidRows(
        bands: List<IntRange>,
        rows: List<IntRange>,
        glyph: Int,
        counts: IntArray,
    ): List<IntRange> {
        if (rows.isEmpty() || bands.isEmpty()) return bands
        val used = BooleanArray(bands.size)
        val produced = mutableListOf<IntRange>()
        val pad = max(1, (glyph * 0.15f).roundToInt())
        val bandCap = max(glyph + 2, (glyph * 2.6f).roundToInt())
        val letterCap = max(bandCap, (glyph * 3.2f).roundToInt())
        val lastRow = counts.lastIndex.coerceAtLeast(0)
        for (row in rows) {
            val top = row.first - pad
            val bottom = row.last + pad
            val covered = bands.indices.filter { index ->
                if (used[index]) return@filter false
                val band = bands[index]
                val center = (band.first + band.last) / 2
                center in top..bottom
            }
            if (covered.isEmpty()) continue
            val unionTop = covered.minOf { bands[it].first }
            val unionBottom = covered.maxOf { bands[it].last }
            if (unionBottom - unionTop + 1 > bandCap) continue
            val spanTop = minOf(unionTop, row.first).coerceAtLeast(0)
            val spanBottom = maxOf(unionBottom, row.last).coerceAtMost(lastRow)
            if (spanBottom < spanTop || spanBottom - spanTop + 1 > letterCap) continue
            covered.forEach { used[it] = true }
            produced.add(spanTop..spanBottom)
        }
        bands.forEachIndexed { index, band ->
            if (!used[index]) produced.add(band)
        }
        return joinTouching(produced.sortedBy { it.first })
    }

    private fun extendTips(bands: List<IntRange>, counts: IntArray, glyph: Int): List<IntRange> {
        if (bands.isEmpty() || counts.isEmpty()) return bands
        val reach = max(2, glyph * 2)
        val hole = max(1, glyph / 8)
        val grown = bands.mapIndexed { index, band ->
            val ceiling = if (index == 0) 0 else bands[index - 1].last + 1
            val floor = if (index == bands.lastIndex) counts.lastIndex else bands[index + 1].first - 1
            val top = extend(counts, band.first, ceiling, -1, reach, hole)
            val bottom = extend(counts, band.last, floor, 1, reach, hole)
            top..bottom
        }
        return separateTips(grown)
    }

    private fun extend(
        counts: IntArray,
        start: Int,
        limit: Int,
        step: Int,
        reach: Int,
        hole: Int,
    ): Int {
        var edge = start
        var skipped = 0
        var moved = 0
        var y = start + step
        while (moved < reach && y in counts.indices && ((step < 0 && y >= limit) || (step > 0 && y <= limit))) {
            if (counts[y] > 0) {
                edge = y
                skipped = 0
            } else {
                skipped++
                if (skipped > hole) break
            }
            moved++
            y += step
        }
        return edge
    }

    private fun separateTips(bands: List<IntRange>): List<IntRange> {
        if (bands.size < 2) return bands
        val tops = bands.map { it.first }.toIntArray()
        val bottoms = bands.map { it.last }.toIntArray()
        for (index in 1 until bands.size) {
            if (tops[index] > bottoms[index - 1]) continue
            val split = (bottoms[index - 1] + tops[index]) / 2
            val upperBottom = split.coerceIn(tops[index - 1], bottoms[index - 1])
            val lowerTop = (upperBottom + 1).coerceIn(tops[index], bottoms[index])
            bottoms[index - 1] = upperBottom
            if (lowerTop > upperBottom) tops[index] = lowerTop
        }
        return List(bands.size) { tops[it]..bottoms[it] }
    }

    private fun joinTouching(bands: List<IntRange>): List<IntRange> {
        if (bands.isEmpty()) return bands
        val merged = mutableListOf(bands.first())
        for (band in bands.drop(1)) {
            val previous = merged.last()
            if (band.first <= previous.last + 1) {
                merged[merged.lastIndex] = previous.first..max(previous.last, band.last)
            } else {
                merged.add(band)
            }
        }
        return merged
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
