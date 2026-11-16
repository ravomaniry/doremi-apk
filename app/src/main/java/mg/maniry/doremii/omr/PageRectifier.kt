package mg.maniry.doremii.omr

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

data class Point2(val x: Float, val y: Float)

data class PageQuad(
    val topLeft: Point2,
    val topRight: Point2,
    val bottomRight: Point2,
    val bottomLeft: Point2,
) {
    val topWidth: Float get() = abs(topRight.x - topLeft.x)
    val bottomWidth: Float get() = abs(bottomRight.x - bottomLeft.x)

    fun scaled(sx: Float, sy: Float): PageQuad = PageQuad(
        Point2(topLeft.x * sx, topLeft.y * sy),
        Point2(topRight.x * sx, topRight.y * sy),
        Point2(bottomRight.x * sx, bottomRight.y * sy),
        Point2(bottomLeft.x * sx, bottomLeft.y * sy),
    )
}

data class RectifyPlan(
    val rotationDegrees: Float,
    val quad: PageQuad?,
    val outputWidth: Int,
    val outputHeight: Int,
)

object PageRectifier {
    fun plan(ink: InkImage): RectifyPlan {
        val angle = bestRotation(ink)
        val upright = uprightSample(ink, angle)
        val fitted = fitQuad(upright.ink)?.scaled(upright.scaleX, upright.scaleY)
        val quad = fitted?.takeIf { needsWarp(it, upright.fullWidth) }
        val width = if (quad != null) {
            max(1, ((quad.topWidth + quad.bottomWidth) / 2f).roundToInt())
        } else {
            upright.fullWidth
        }
        val height = if (quad != null) {
            val left = abs(quad.bottomLeft.y - quad.topLeft.y)
            val right = abs(quad.bottomRight.y - quad.topRight.y)
            max(1, ((left + right) / 2f).roundToInt())
        } else {
            upright.fullHeight
        }
        return RectifyPlan(
            rotationDegrees = if (abs(angle) < 0.5f) 0f else angle,
            quad = quad,
            outputWidth = width,
            outputHeight = height,
        )
    }

    fun bestRotation(ink: InkImage): Float {
        val source = OpenCvImages.inkOf(ink)
        try {
            val small = scaled(source.mat, 160)
            try {
                var bestAngle = 0
                var bestScore = columnVariance(small)
                for (angle in -15..15) {
                    if (angle == 0) continue
                    val turned = rotate(small, angle.toFloat())
                    try {
                        val score = columnVariance(turned)
                        if (score > bestScore) {
                            bestScore = score
                            bestAngle = angle
                        }
                    } finally {
                        turned.release()
                    }
                }
                return bestAngle.toFloat()
            } finally {
                if (small !== source.mat) small.release()
            }
        } finally {
            source.close()
        }
    }

    fun fitQuad(ink: InkImage): PageQuad? {
        val source = OpenCvImages.inkOf(ink)
        try {
            return fitQuad(source.mat)
        } finally {
            source.close()
        }
    }

    fun warp(ink: InkImage, quad: PageQuad, width: Int, height: Int): InkImage {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val source = OpenCvImages.inkOf(ink)
        val out = Mat.zeros(safeHeight, safeWidth, CvType.CV_8UC1)
        val from = MatOfPoint2f(
            Point(quad.topLeft.x.toDouble(), quad.topLeft.y.toDouble()),
            Point(quad.topRight.x.toDouble(), quad.topRight.y.toDouble()),
            Point(quad.bottomRight.x.toDouble(), quad.bottomRight.y.toDouble()),
            Point(quad.bottomLeft.x.toDouble(), quad.bottomLeft.y.toDouble()),
        )
        val to = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(safeWidth.toDouble(), 0.0),
            Point(safeWidth.toDouble(), safeHeight.toDouble()),
            Point(0.0, safeHeight.toDouble()),
        )
        val transform = Imgproc.getPerspectiveTransform(from, to)
        try {
            Imgproc.warpPerspective(
                source.mat,
                out,
                transform,
                Size(safeWidth.toDouble(), safeHeight.toDouble()),
                Imgproc.INTER_NEAREST,
                Core.BORDER_CONSTANT,
                Scalar(0.0),
            )
            return OpenCvImages.toInk(out)
        } finally {
            transform.release()
            from.release()
            to.release()
            out.release()
            source.close()
        }
    }

    fun rotateInk(ink: InkImage, degrees: Float): InkImage {
        if (abs(degrees) < 0.01f) return ink
        val source = OpenCvImages.inkOf(ink)
        try {
            val turned = rotate(source.mat, degrees)
            try {
                return OpenCvImages.toInk(turned)
            } finally {
                turned.release()
            }
        } finally {
            source.close()
        }
    }

    private fun uprightSample(ink: InkImage, angle: Float): UprightSample {
        val small = downscale(ink, 640)
        if (abs(angle) < 0.5f) {
            return UprightSample(
                ink = small,
                scaleX = ink.width / small.width.toFloat(),
                scaleY = ink.height / small.height.toFloat(),
                fullWidth = ink.width,
                fullHeight = ink.height,
            )
        }
        val rotated = rotateInk(small, angle)
        val full = rotatedBounds(ink.width, ink.height, angle)
        return UprightSample(
            ink = rotated,
            scaleX = full.width / rotated.width.toFloat(),
            scaleY = full.height / rotated.height.toFloat(),
            fullWidth = full.width,
            fullHeight = full.height,
        )
    }

    private fun needsWarp(quad: PageQuad, width: Int): Boolean {
        val tolerance = max(2.5f, width * 0.012f)
        val left = abs(quad.topLeft.x - quad.bottomLeft.x)
        val right = abs(quad.topRight.x - quad.bottomRight.x)
        val top = abs(quad.topLeft.y - quad.topRight.y)
        val bottom = abs(quad.bottomLeft.y - quad.bottomRight.y)
        return left > tolerance || right > tolerance || top > tolerance || bottom > tolerance
    }

    private fun fitQuad(ink: Mat): PageQuad? {
        val content = contentBounds(ink) ?: return null
        val span = content.bottom - content.top
        if (span < 16 || content.right - content.left < 16) return null
        val slices = 4
        val left = mutableListOf<Point2>()
        val right = mutableListOf<Point2>()
        for (index in 0 until slices) {
            val y0 = content.top + span * index / slices
            val y1 = content.top + span * (index + 1) / slices
            val found = peaks(ink, y0, y1)
            if (found.size < 2) continue
            val separation = found.last() - found.first()
            if (separation < max(24f, (content.right - content.left) * 0.18f)) continue
            val midY = (y0 + y1) / 2f
            left.add(Point2(found.first(), midY))
            right.add(Point2(found.last(), midY))
        }
        if (left.size < 2 || right.size < 2) return null
        val leftAt = lineAt(left)
        val rightAt = lineAt(right)
        val top = content.top.toFloat()
        val bottom = (content.bottom - 1).toFloat()
        val quad = PageQuad(
            topLeft = Point2(leftAt(top), top),
            topRight = Point2(rightAt(top), top),
            bottomRight = Point2(rightAt(bottom), bottom),
            bottomLeft = Point2(leftAt(bottom), bottom),
        )
        if (quad.topLeft.x >= quad.topRight.x - 4f) return null
        if (quad.bottomLeft.x >= quad.bottomRight.x - 4f) return null
        if (crossesWhiteGap(ink, leftAt, top, bottom)) return null
        if (crossesWhiteGap(ink, rightAt, top, bottom)) return null
        return quad
    }

    private fun columnVariance(ink: Mat): Double {
        val width = ink.cols()
        val height = ink.rows()
        if (width == 0 || height == 0) return 0.0
        val sum = Mat()
        try {
            Core.reduce(ink, sum, 0, Core.REDUCE_SUM, CvType.CV_64F)
            val totals = DoubleArray(width)
            sum.get(0, 0, totals)
            val counts = DoubleArray(width) { totals[it] / 255.0 }
            val mean = counts.sum() / width
            var acc = 0.0
            for (count in counts) {
                val delta = count - mean
                acc += delta * delta
            }
            val rows = height.toDouble().coerceAtLeast(1.0)
            return acc / width / (rows * rows)
        } finally {
            sum.release()
        }
    }

    private fun contentBounds(ink: Mat): ContentBox? {
        if (ink.empty() || Core.countNonZero(ink) == 0) return null
        val points = MatOfPoint()
        try {
            Core.findNonZero(ink, points)
            val rect = Imgproc.boundingRect(points)
            if (rect.width <= 0 || rect.height <= 0) return null
            return ContentBox(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height)
        } finally {
            points.release()
        }
    }

    private fun peaks(ink: Mat, y0: Int, y1: Int): List<Float> {
        val span = y1 - y0
        if (span < 4 || ink.cols() < 2) return emptyList()
        val roi = ink.submat(y0, y1, 0, ink.cols())
        val sum = Mat()
        try {
            Core.reduce(roi, sum, 0, Core.REDUCE_SUM, CvType.CV_32S)
            val totals = IntArray(ink.cols())
            sum.get(0, 0, totals)
            val counts = IntArray(totals.size) { totals[it] / 255 }
            val floor = (span * 0.18f).toInt().coerceAtLeast(2)
            val minPeak = (span * 0.22f).toInt().coerceAtLeast(floor)
            val maxThick = max(18, (span * 0.75f).toInt())
            val centers = mutableListOf<Float>()
            var x = 0
            while (x < counts.size) {
                if (counts[x] < floor) {
                    x++
                    continue
                }
                var peak = counts[x]
                val start = x
                while (x < counts.size && counts[x] >= floor) {
                    if (counts[x] > peak) peak = counts[x]
                    x++
                }
                if (x - start <= maxThick && peak >= minPeak) centers.add((start + x - 1) / 2f)
            }
            return centers
        } finally {
            sum.release()
            roi.release()
        }
    }

    private fun crossesWhiteGap(ink: Mat, xAt: (Float) -> Float, top: Float, bottom: Float): Boolean {
        var gap = 0
        var y = top.toInt()
        val end = bottom.toInt()
        val pixel = ByteArray(1)
        while (y <= end) {
            val x = xAt(y.toFloat()).roundToInt()
            var dark = false
            for (column in x - 2..x + 2) {
                if (column !in 0 until ink.cols() || y !in 0 until ink.rows()) continue
                ink.get(y, column, pixel)
                if (pixel[0].toInt() != 0) {
                    dark = true
                    break
                }
            }
            if (dark) {
                gap = 0
            } else {
                gap++
                if (gap > 2) return true
            }
            y++
        }
        return false
    }

    private fun lineAt(points: List<Point2>): (Float) -> Float {
        val count = points.size.toDouble()
        var sumY = 0.0
        var sumX = 0.0
        var sumYY = 0.0
        var sumYX = 0.0
        for (point in points) {
            sumY += point.y
            sumX += point.x
            sumYY += point.y * point.y
            sumYX += point.y * point.x
        }
        val denom = count * sumYY - sumY * sumY
        if (abs(denom) < 1e-3) {
            val x = (sumX / count).toFloat()
            return { x }
        }
        val slope = (count * sumYX - sumY * sumX) / denom
        val intercept = (sumX - slope * sumY) / count
        return { y -> (slope * y + intercept).toFloat() }
    }

    private fun downscale(ink: InkImage, maxSide: Int): InkImage {
        val source = OpenCvImages.inkOf(ink)
        try {
            val small = scaled(source.mat, maxSide)
            try {
                if (small === source.mat) return ink
                return OpenCvImages.toInk(small)
            } finally {
                if (small !== source.mat) small.release()
            }
        } finally {
            source.close()
        }
    }

    private fun scaled(ink: Mat, maxSide: Int): Mat {
        val longest = max(ink.cols(), ink.rows())
        if (longest <= maxSide || longest == 0) return ink
        val scale = longest.toFloat() / maxSide
        val width = max(1, (ink.cols() / scale).roundToInt())
        val height = max(1, (ink.rows() / scale).roundToInt())
        val floats = Mat()
        val resized = Mat()
        try {
            ink.convertTo(floats, CvType.CV_32F)
            Imgproc.resize(
                floats,
                resized,
                Size(width.toDouble(), height.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_AREA,
            )
            Imgproc.threshold(resized, resized, 0.0, 255.0, Imgproc.THRESH_BINARY)
            val out = Mat()
            resized.convertTo(out, CvType.CV_8U)
            return out
        } finally {
            floats.release()
            resized.release()
        }
    }

    private fun rotate(src: Mat, degrees: Float): Mat {
        val bounds = rotatedBounds(src.cols(), src.rows(), degrees)
        val radians = Math.toRadians(degrees.toDouble())
        val matrix = Mat(2, 3, CvType.CV_64F)
        matrix.put(
            0,
            0,
            cos(radians),
            -sin(radians),
            -bounds.minX.toDouble(),
            sin(radians),
            cos(radians),
            -bounds.minY.toDouble(),
        )
        val dst = Mat.zeros(bounds.height, bounds.width, src.type())
        Imgproc.warpAffine(
            src,
            dst,
            matrix,
            dst.size(),
            Imgproc.INTER_NEAREST,
            Core.BORDER_CONSTANT,
            Scalar(0.0),
        )
        matrix.release()
        return dst
    }

    private fun mapRotate(x: Float, y: Float, degrees: Float): Pair<Float, Float> {
        val radians = Math.toRadians(degrees.toDouble())
        val cos = cos(radians)
        val sin = sin(radians)
        return (cos * x - sin * y).toFloat() to (sin * x + cos * y).toFloat()
    }

    private fun rotatedBounds(width: Int, height: Int, degrees: Float): RotatedBounds {
        val corners = arrayOf(
            mapRotate(0f, 0f, degrees),
            mapRotate(width.toFloat(), 0f, degrees),
            mapRotate(width.toFloat(), height.toFloat(), degrees),
            mapRotate(0f, height.toFloat(), degrees),
        )
        val minX = corners.minOf { it.first }
        val minY = corners.minOf { it.second }
        val maxX = corners.maxOf { it.first }
        val maxY = corners.maxOf { it.second }
        return RotatedBounds(
            minX = minX,
            minY = minY,
            width = max(1, ceil(maxX - minX.toDouble()).toInt()),
            height = max(1, ceil(maxY - minY.toDouble()).toInt()),
        )
    }
}

private data class UprightSample(
    val ink: InkImage,
    val scaleX: Float,
    val scaleY: Float,
    val fullWidth: Int,
    val fullHeight: Int,
)

private data class ContentBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

private data class RotatedBounds(val minX: Float, val minY: Float, val width: Int, val height: Int)
