package mg.maniry.doremii.omr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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
        val small = downscale(ink, 160)
        var bestAngle = 0
        var bestScore = columnVariance(small)
        for (angle in -15..15) {
            if (angle == 0) continue
            val score = columnVariance(rotateInk(small, angle.toFloat()))
            if (score > bestScore) {
                bestScore = score
                bestAngle = angle
            }
        }
        return bestAngle.toFloat()
    }

    fun fitQuad(ink: InkImage): PageQuad? {
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

    fun warp(ink: InkImage, quad: PageQuad, width: Int, height: Int): InkImage {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val homography = homography(
            from = arrayOf(
                Point2(0f, 0f),
                Point2(safeWidth.toFloat(), 0f),
                Point2(safeWidth.toFloat(), safeHeight.toFloat()),
                Point2(0f, safeHeight.toFloat()),
            ),
            to = arrayOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft),
        ) ?: return ArrayInk(safeWidth, safeHeight, BooleanArray(safeWidth * safeHeight))
        val dark = BooleanArray(safeWidth * safeHeight)
        for (y in 0 until safeHeight) {
            for (x in 0 until safeWidth) {
                val mapped = apply(homography, x + 0.5, y + 0.5) ?: continue
                if (nearDark(ink, mapped.first, mapped.second)) dark[y * safeWidth + x] = true
            }
        }
        return ArrayInk(safeWidth, safeHeight, dark)
    }

    fun rotateInk(ink: InkImage, degrees: Float): InkImage {
        if (abs(degrees) < 0.01f) return ink
        val bounds = rotatedBounds(ink.width, ink.height, degrees)
        val dark = BooleanArray(bounds.width * bounds.height)
        for (y in 0 until ink.height) {
            for (x in 0 until ink.width) {
                if (!ink.isDark(x, y)) continue
                val mapped = mapRotate(x.toFloat(), y.toFloat(), degrees)
                val dx = (mapped.first - bounds.minX).roundToInt()
                val dy = (mapped.second - bounds.minY).roundToInt()
                if (dx in 0 until bounds.width && dy in 0 until bounds.height) {
                    dark[dy * bounds.width + dx] = true
                }
            }
        }
        return ArrayInk(bounds.width, bounds.height, dark)
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

    private fun columnVariance(ink: InkImage): Double {
        if (ink.width == 0 || ink.height == 0) return 0.0
        val counts = IntArray(ink.width)
        for (y in 0 until ink.height) {
            for (x in 0 until ink.width) {
                if (ink.isDark(x, y)) counts[x]++
            }
        }
        val mean = counts.sum().toDouble() / ink.width
        var sum = 0.0
        for (count in counts) {
            val delta = count - mean
            sum += delta * delta
        }
        val height = ink.height.toDouble().coerceAtLeast(1.0)
        return sum / ink.width / (height * height)
    }

    private fun contentBounds(ink: InkImage): ContentBox? {
        var left = ink.width
        var top = ink.height
        var right = -1
        var bottom = -1
        for (y in 0 until ink.height) {
            for (x in 0 until ink.width) {
                if (!ink.isDark(x, y)) continue
                if (x < left) left = x
                if (y < top) top = y
                if (x > right) right = x
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return null
        return ContentBox(left, top, right + 1, bottom + 1)
    }

    private fun peaks(ink: InkImage, y0: Int, y1: Int): List<Float> {
        val span = y1 - y0
        if (span < 4 || ink.width < 2) return emptyList()
        val counts = IntArray(ink.width)
        for (y in y0 until y1) {
            for (x in 0 until ink.width) {
                if (ink.isDark(x, y)) counts[x]++
            }
        }
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
    }

    private fun crossesWhiteGap(ink: InkImage, xAt: (Float) -> Float, top: Float, bottom: Float): Boolean {
        var gap = 0
        var y = top.toInt()
        val end = bottom.toInt()
        while (y <= end) {
            val x = xAt(y.toFloat()).roundToInt()
            if ((x - 2..x + 2).any { ink.isDark(it, y) }) {
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
        val longest = max(ink.width, ink.height)
        if (longest <= maxSide) return ink
        val scale = longest.toFloat() / maxSide
        val width = max(1, (ink.width / scale).roundToInt())
        val height = max(1, (ink.height / scale).roundToInt())
        val dark = BooleanArray(width * height)
        val stepX = ink.width.toFloat() / width
        val stepY = ink.height.toFloat() / height
        for (y in 0 until height) {
            val y0 = (y * stepY).toInt().coerceIn(0, ink.height - 1)
            val y1 = min(ink.height, ((y + 1) * stepY).toInt().coerceAtLeast(y0 + 1))
            for (x in 0 until width) {
                val x0 = (x * stepX).toInt().coerceIn(0, ink.width - 1)
                val x1 = min(ink.width, ((x + 1) * stepX).toInt().coerceAtLeast(x0 + 1))
                var on = false
                for (yy in y0 until y1) {
                    for (xx in x0 until x1) {
                        if (ink.isDark(xx, yy)) {
                            on = true
                            break
                        }
                    }
                    if (on) break
                }
                dark[y * width + x] = on
            }
        }
        return ArrayInk(width, height, dark)
    }

    private fun nearDark(ink: InkImage, x: Double, y: Double): Boolean {
        val nearestX = x.roundToInt()
        val nearestY = y.roundToInt()
        var best = 2.0
        for (yy in nearestY - 1..nearestY + 1) {
            for (xx in nearestX - 1..nearestX + 1) {
                if (!ink.isDark(xx, yy)) continue
                val dx = x - xx
                val dy = y - yy
                val distance = dx * dx + dy * dy
                if (distance < best) best = distance
            }
        }
        return best <= 0.64
    }

    private fun homography(from: Array<Point2>, to: Array<Point2>): DoubleArray? {
        val matrix = Array(8) { DoubleArray(9) }
        for (index in 0 until 4) {
            val x = from[index].x.toDouble()
            val y = from[index].y.toDouble()
            val u = to[index].x.toDouble()
            val v = to[index].y.toDouble()
            val row0 = index * 2
            val row1 = row0 + 1
            matrix[row0][0] = x
            matrix[row0][1] = y
            matrix[row0][2] = 1.0
            matrix[row0][6] = -u * x
            matrix[row0][7] = -u * y
            matrix[row0][8] = u
            matrix[row1][3] = x
            matrix[row1][4] = y
            matrix[row1][5] = 1.0
            matrix[row1][6] = -v * x
            matrix[row1][7] = -v * y
            matrix[row1][8] = v
        }
        if (!eliminate(matrix)) return null
        return DoubleArray(8) { matrix[it][8] }
    }

    private fun eliminate(matrix: Array<DoubleArray>): Boolean {
        val size = 8
        for (col in 0 until size) {
            var pivot = col
            var best = abs(matrix[col][col])
            for (row in col + 1 until size) {
                val value = abs(matrix[row][col])
                if (value > best) {
                    best = value
                    pivot = row
                }
            }
            if (best < 1e-8) return false
            if (pivot != col) {
                val swap = matrix[col]
                matrix[col] = matrix[pivot]
                matrix[pivot] = swap
            }
            val divisor = matrix[col][col]
            for (column in col..size) matrix[col][column] /= divisor
            for (row in 0 until size) {
                if (row == col) continue
                val factor = matrix[row][col]
                if (factor == 0.0) continue
                for (column in col..size) matrix[row][column] -= factor * matrix[col][column]
            }
        }
        return true
    }

    private fun apply(homography: DoubleArray, x: Double, y: Double): Pair<Double, Double>? {
        val weight = homography[6] * x + homography[7] * y + 1.0
        if (abs(weight) < 1e-8) return null
        val sx = (homography[0] * x + homography[1] * y + homography[2]) / weight
        val sy = (homography[3] * x + homography[4] * y + homography[5]) / weight
        return sx to sy
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

private class ArrayInk(
    override val width: Int,
    override val height: Int,
    private val dark: BooleanArray,
) : InkImage {
    override fun isDark(x: Int, y: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        return dark[y * width + x]
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
