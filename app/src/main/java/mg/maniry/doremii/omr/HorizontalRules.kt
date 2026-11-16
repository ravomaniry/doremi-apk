package mg.maniry.doremii.omr

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

internal object HorizontalRules {
    private const val gapBridge = 3

    fun strip(ink: InkImage, symbols: List<OcrSymbol> = emptyList()): InkImage {
        if (ink.width < 4 || ink.height < 4) return ink
        val widthFromLetters = OcrMetrics.characterWidth(symbols)
        val tallFromLetters = OcrMetrics.tallHeight(symbols)
        val parts = if (widthFromLetters == null || tallFromLetters == null) components(ink) else emptyList()
        val charWidth = widthFromLetters ?: componentWidth(parts) ?: return ink
        val tall = tallFromLetters ?: componentHeight(parts) ?: charWidth
        if (charWidth < 2 || tall < 2) return ink
        val bytes = readBytes(ink)
        erase(bytes, ink.width, ink.height, charWidth * 2, max(2, (tall * 0.35f).roundToInt()))
        return writeBytes(ink, bytes)
    }

    private fun erase(bytes: ByteArray, width: Int, height: Int, minLength: Int, maxThickness: Int) {
        if (minLength < 2 || maxThickness < 1) return
        val pending = mutableListOf<Run>()
        for (y in 0 until height) {
            for (span in runsOnRow(bytes, width, y, minLength)) {
                pending.add(Run(y, span.first, span.last + 1))
            }
        }
        val used = BooleanArray(pending.size)
        for (index in pending.indices.sortedBy { pending[it].y }) {
            if (used[index]) continue
            val seed = pending[index]
            var top = seed.y
            var bottom = seed.y + 1
            val left = seed.left
            val right = seed.right
            used[index] = true
            while (top > 0 && bottom - (top - 1) <= maxThickness && covers(bytes, width, height, top - 1, left, right)) {
                top--
            }
            while (bottom < height && bottom + 1 - top <= maxThickness && covers(bytes, width, height, bottom, left, right)) {
                bottom++
            }
            val thicker = (top > 0 && covers(bytes, width, height, top - 1, left, right)) ||
                (bottom < height && covers(bytes, width, height, bottom, left, right))
            if (thicker) continue
            for (y in top until bottom) {
                val row = y * width
                for (x in left until right) bytes[row + x] = 0
            }
            for (other in pending.indices) {
                val run = pending[other]
                if (run.y in top until bottom && run.left < right && run.right > left) used[other] = true
            }
        }
    }

    private fun runsOnRow(bytes: ByteArray, width: Int, y: Int, minLength: Int): List<IntRange> {
        val found = mutableListOf<IntRange>()
        val row = y * width
        var x = 0
        while (x < width) {
            if (!dark(bytes, row + x)) {
                x++
                continue
            }
            val start = x
            var end = x
            var gap = 0
            x++
            while (x < width) {
                if (dark(bytes, row + x)) {
                    end = x
                    gap = 0
                    x++
                } else {
                    gap++
                    if (gap > gapBridge) break
                    x++
                }
            }
            if (end - start + 1 >= minLength) found.add(start..end)
        }
        return found
    }

    private fun covers(bytes: ByteArray, width: Int, height: Int, y: Int, left: Int, right: Int): Boolean {
        if (y !in 0 until height) return false
        val span = right - left
        if (span <= 0) return false
        var count = 0
        val row = y * width
        for (x in left until right) if (dark(bytes, row + x)) count++
        return count * 5 >= span * 4
    }

    private fun dark(bytes: ByteArray, index: Int): Boolean = bytes[index].toInt() != 0

    private fun readBytes(ink: InkImage): ByteArray {
        val count = ink.width * ink.height
        val bytes = ByteArray(count)
        if (count == 0) return bytes
        if (ink is MatInk) {
            ink.ink.get(0, 0, bytes)
            return bytes
        }
        var index = 0
        for (y in 0 until ink.height) {
            for (x in 0 until ink.width) {
                bytes[index++] = if (ink.isDark(x, y)) 255.toByte() else 0
            }
        }
        return bytes
    }

    private fun writeBytes(ink: InkImage, bytes: ByteArray): InkImage {
        if (ink is MatInk) {
            ink.replaceInk(bytes)
            return ink
        }
        return ArrayInk(ink.width, ink.height, BooleanArray(bytes.size) { bytes[it].toInt() != 0 })
    }

    private fun components(ink: InkImage): List<Pair<Int, Int>> {
        val mask = OpenCvImages.inkOf(ink)
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            val count = Imgproc.connectedComponentsWithStats(mask.mat, labels, stats, centroids, 8, CvType.CV_32S)
            val found = ArrayList<Pair<Int, Int>>(max(0, count - 1))
            for (index in 1 until count) {
                val width = stats.get(index, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val height = stats.get(index, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val area = stats.get(index, Imgproc.CC_STAT_AREA)[0].toInt()
                if (area < 6 || height < 3 || width < 2) continue
                found.add(width to height)
            }
            return found
        } finally {
            labels.release()
            stats.release()
            centroids.release()
            mask.close()
        }
    }

    private fun componentWidth(parts: List<Pair<Int, Int>>): Int? {
        val widths = parts.mapNotNull { (width, height) ->
            if (height > width * 5) return@mapNotNull null
            if (width > height * 4) return@mapNotNull null
            width
        }.sorted()
        if (widths.size < 2) return null
        return widths[widths.size / 2]
    }

    private fun componentHeight(parts: List<Pair<Int, Int>>): Int? {
        val heights = parts.mapNotNull { (width, height) ->
            if (height > width * 5) return@mapNotNull null
            if (width > height * 8 && height <= 3) return@mapNotNull null
            height
        }.sorted()
        if (heights.isEmpty()) return null
        return heights[heights.size / 2]
    }

    private data class Run(val y: Int, val left: Int, val right: Int)
}
