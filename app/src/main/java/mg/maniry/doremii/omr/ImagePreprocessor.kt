package mg.maniry.doremii.omr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class PreparedImage(val ocr: Bitmap, val ink: InkImage) {
    fun release() {
        if (!ocr.isRecycled) ocr.recycle()
        (ink as? MatInk)?.release()
    }
}

data class OcrCanvas(
    val bitmap: Bitmap,
    val scale: Float,
    val pad: Int,
    val originX: Int,
    val originY: Int,
    val contentHeight: Int,
    val singleLine: Boolean,
)

object ImagePreprocessor {
    fun prepare(source: Bitmap): PreparedImage {
        val readable = software(source)
        val gray = OpenCvImages.normalizedGray(readable)
        if (readable !== source && !readable.isRecycled) readable.recycle()
        val ink = try {
            OpenCvImages.adaptiveInk(gray)
        } catch (error: Throwable) {
            gray.release()
            throw error
        }
        return try {
            PreparedImage(OpenCvImages.toOpaqueBitmap(gray), MatInk(gray, ink))
        } catch (error: Throwable) {
            gray.release()
            ink.release()
            throw error
        }
    }

    fun rotate(source: Bitmap, degrees: Int): Bitmap = rotate(source, degrees.toFloat())

    fun rotate(source: Bitmap, degrees: Float): Bitmap {
        val turns = ((degrees % 360f) + 360f) % 360f
        if (turns < 0.05f || turns > 359.95f) return source
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    fun warp(source: Bitmap, quad: PageQuad, width: Int, height: Int): Bitmap {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val matrix = Matrix()
        val from = floatArrayOf(
            quad.topLeft.x, quad.topLeft.y,
            quad.topRight.x, quad.topRight.y,
            quad.bottomRight.x, quad.bottomRight.y,
            quad.bottomLeft.x, quad.bottomLeft.y,
        )
        val to = floatArrayOf(
            0f, 0f,
            safeWidth.toFloat(), 0f,
            safeWidth.toFloat(), safeHeight.toFloat(),
            0f, safeHeight.toFloat(),
        )
        val mapped = matrix.setPolyToPoly(from, 0, to, 0, 4)
        val out = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        if (mapped) {
            canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    fun software(source: Bitmap): Bitmap {
        if (source.config != Bitmap.Config.HARDWARE) return source
        return source.copy(Bitmap.Config.ARGB_8888, false) ?: source
    }

    fun cropDisplayed(source: Bitmap, viewWidth: Int, viewHeight: Int, rect: RectF): Bitmap? {
        if (viewWidth <= 0 || viewHeight <= 0 || source.width <= 0 || source.height <= 0) return null
        val scale = min(viewWidth.toFloat() / source.width, viewHeight.toFloat() / source.height)
        if (scale <= 0f) return null
        val dx = (viewWidth - source.width * scale) / 2f
        val dy = (viewHeight - source.height * scale) / 2f
        val left = ((rect.left - dx) / scale).toInt()
        val top = ((rect.top - dy) / scale).toInt()
        val right = ((rect.right - dx) / scale).toInt()
        val bottom = ((rect.bottom - dy) / scale).toInt()
        val l = left.coerceIn(0, source.width - 1)
        val t = top.coerceIn(0, source.height - 1)
        val r = right.coerceIn(l + 1, source.width)
        val b = bottom.coerceIn(t + 1, source.height)
        if (r - l < 8 || b - t < 8) return null
        return Bitmap.createBitmap(source, l, t, r - l, b - t)
    }

    fun recognitionCanvases(source: Bitmap): List<OcrCanvas> {
        if (source.width < 2 || source.height < 2) return emptyList()
        val readable = software(source)
        val gray = OpenCvImages.normalizedGray(readable)
        if (readable !== source && !readable.isRecycled) readable.recycle()
        val mask = try {
            OpenCvImages.adaptiveInk(gray)
        } catch (error: Throwable) {
            gray.release()
            throw error
        }
        val height = gray.rows()
        val bands = try {
            val found = OcrBands.find(OpenCvImages.rowCounts(mask))
            if (found.isEmpty()) {
                listOf((0 until height) to (height < 96))
            } else {
                val expanded = OcrBands.expand(found, height)
                val heights = expanded.map { it.last - it.first + 1 }.sorted()
                val median = heights[heights.size / 2].coerceAtLeast(1)
                expanded.map { band ->
                    val bandHeight = band.last - band.first + 1
                    band to (bandHeight <= median * 1.8)
                }
            }
        } finally {
            mask.release()
        }
        val bitmap = OpenCvImages.toOpaqueBitmap(gray)
        gray.release()
        return try {
            bands.map { (band, singleLine) -> lineCanvas(bitmap, band.first, band.last + 1, singleLine) }
        } finally {
            bitmap.recycle()
        }
    }

    fun scaleDown(source: Bitmap, maxSide: Int = 2400): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= maxSide) return source
        val scale = maxSide.toFloat() / longest
        val width = max(1, (source.width * scale).toInt())
        val height = max(1, (source.height * scale).toInt())
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun lineCanvas(gray: Bitmap, top: Int, bottom: Int, singleLine: Boolean): OcrCanvas {
        val contentHeight = (bottom - top).coerceAtLeast(1)
        val contentWidth = gray.width
        var scale = if (contentHeight >= 56) 1f else (64f / contentHeight).coerceAtMost(4f)
        if (contentWidth * scale > 3200f) scale = (3200f / contentWidth).coerceAtLeast(1f)
        val pad = 16
        val destWidth = max(1, (contentWidth * scale).roundToInt())
        val destHeight = max(1, (contentHeight * scale).roundToInt())
        val bitmap = Bitmap.createBitmap(destWidth + pad * 2, destHeight + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(
            gray,
            Rect(0, top, contentWidth, bottom),
            Rect(pad, pad, pad + destWidth, pad + destHeight),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return OcrCanvas(bitmap, scale, pad, 0, top, destHeight, singleLine)
    }
}
