package mg.maniry.doremii.omr

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object TextBlockDetector {
    private const val pageSide = 0.12f

    fun detect(ink: InkImage, lines: List<DebugLine>): List<DebugBlock> {
        if (lines.isEmpty() || ink.width < 4 || ink.height < 4) return emptyList()
        val ordered = lines.sortedBy { it.top }
        val heights = ordered.map { it.bottom - it.top }.filter { it >= 2f }.sorted()
        if (heights.isEmpty()) return emptyList()
        val lineHeight = heights[heights.size / 2]
        val mask = OpenCvImages.inkOf(ink)
        try {
            val found = strokes(mask.mat, lineHeight)
            return group(ordered, found, ink.width, lineHeight * 0.45f)
        } finally {
            mask.close()
        }
    }

    private fun strokes(ink: Mat, lineHeight: Float): List<Stroke> {
        val kernelHeight = (lineHeight * 0.7f).roundToInt().coerceIn(6, max(6, ink.rows() / 4))
        val closeHeight = max(3, (lineHeight * 0.35f).roundToInt())
        val maxWidth = max(4, (lineHeight * 0.5f).roundToInt())
        val opened = Mat()
        val closed = Mat()
        val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, kernelHeight.toDouble()))
        val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, closeHeight.toDouble()))
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            Imgproc.morphologyEx(ink, opened, Imgproc.MORPH_OPEN, openKernel)
            Imgproc.morphologyEx(opened, closed, Imgproc.MORPH_CLOSE, closeKernel)
            val count = Imgproc.connectedComponentsWithStats(closed, labels, stats, centroids, 8, CvType.CV_32S)
            val found = mutableListOf<Stroke>()
            for (index in 1 until count) {
                val x = stats.get(index, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(index, Imgproc.CC_STAT_TOP)[0].toInt()
                val width = stats.get(index, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val height = stats.get(index, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                if (width <= 0 || height < kernelHeight || width > maxWidth || height < width * 4) continue
                found.add(Stroke(x + (width - 1) / 2f, y, y + height))
            }
            return collapse(found)
        } finally {
            opened.release()
            closed.release()
            openKernel.release()
            closeKernel.release()
            labels.release()
            stats.release()
            centroids.release()
        }
    }

    private fun collapse(strokes: List<Stroke>): List<Stroke> {
        if (strokes.size < 2) return strokes
        val sorted = strokes.sortedBy { it.x }
        val out = mutableListOf(sorted.first())
        for (stroke in sorted.drop(1)) {
            val last = out.last()
            val sameBar = stroke.x - last.x <= 4f && stroke.top < last.bottom && last.top < stroke.bottom
            if (!sameBar) {
                out.add(stroke)
                continue
            }
            val taller = if (stroke.bottom - stroke.top > last.bottom - last.top) stroke else last
            out[out.lastIndex] = taller
        }
        return out
    }

    private fun group(
        lines: List<DebugLine>,
        strokes: List<Stroke>,
        pageWidth: Int,
        slack: Float,
    ): List<DebugBlock> {
        val blocks = mutableListOf<DebugBlock>()
        var start = 0
        while (start < lines.size) {
            var end = -1
            for (candidate in lines.lastIndex downTo start) {
                if (qualifies(lines.subList(start, candidate + 1), strokes, pageWidth, slack)) {
                    end = candidate
                    break
                }
            }
            if (end < start) {
                start++
                continue
            }
            blocks.add(bounds(lines.subList(start, end + 1), strokes, slack))
            start = end + 1
        }
        return blocks
    }

    private fun qualifies(
        group: List<DebugLine>,
        strokes: List<Stroke>,
        pageWidth: Int,
        slack: Float,
    ): Boolean {
        val top = group.first().top
        val bottom = group.last().bottom
        if (bottom - top < 2f) return false
        val leftEdge = pageWidth * pageSide
        val rightEdge = pageWidth * (1f - pageSide)
        var left = 0
        var middle = 0
        var right = 0
        for (stroke in strokes) {
            if (stroke.top > top + slack || stroke.bottom < bottom - slack) continue
            when {
                stroke.x <= leftEdge -> left++
                stroke.x >= rightEdge -> right++
                else -> middle++
            }
        }
        return left >= 1 || middle >= 2 || right >= 1
    }

    private fun bounds(group: List<DebugLine>, strokes: List<Stroke>, slack: Float): DebugBlock {
        val top = group.first().top
        val bottom = group.last().bottom
        var left = group.minOf { it.left }
        var right = group.maxOf { it.right }
        for (stroke in strokes) {
            if (stroke.top > top + slack || stroke.bottom < bottom - slack) continue
            left = min(left, stroke.x - 1f)
            right = max(right, stroke.x + 1f)
        }
        return DebugBlock(left, top, right, bottom)
    }

    private data class Stroke(val x: Float, val top: Int, val bottom: Int)
}
