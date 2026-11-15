package mg.maniry.doremii.omr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

data class PreparedImage(val ocr: Bitmap, val ink: InkImage)

object ImagePreprocessor {
    fun prepare(source: Bitmap): PreparedImage {
        val gray = contrastGray(source)
        val binary = adaptiveThreshold(gray)
        val ink = BitmapInk(binary)
        binary.recycle()
        return PreparedImage(gray, ink)
    }

    fun rotate(source: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
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

    fun scaleDown(source: Bitmap, maxSide: Int = 2400): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= maxSide) return source
        val scale = maxSide.toFloat() / longest
        val width = max(1, (source.width * scale).toInt())
        val height = max(1, (source.height * scale).toInt())
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun contrastGray(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        var minLum = 255
        var maxLum = 0
        val lum = IntArray(pixels.size)
        for (index in pixels.indices) {
            val color = pixels[index]
            val value = (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)).toInt()
            lum[index] = value
            if (value < minLum) minLum = value
            if (value > maxLum) maxLum = value
        }
        val span = (maxLum - minLum).coerceAtLeast(1)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (index in pixels.indices) {
            val value = ((lum[index] - minLum) * 255 / span).coerceIn(0, 255)
            pixels[index] = Color.rgb(value, value, value)
        }
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    private fun adaptiveThreshold(gray: Bitmap): Bitmap {
        val width = gray.width
        val height = gray.height
        val pixels = IntArray(width * height)
        gray.getPixels(pixels, 0, width, 0, 0, width, height)
        val integral = LongArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var row = 0L
            for (x in 0 until width) {
                row += Color.red(pixels[y * width + x])
                val dest = (y + 1) * (width + 1) + (x + 1)
                integral[dest] = integral[y * (width + 1) + (x + 1)] + row
            }
        }
        val radius = max(8, min(width, height) / 32)
        val binary = IntArray(pixels.size)
        for (y in 0 until height) {
            val y0 = max(0, y - radius)
            val y1 = min(height - 1, y + radius)
            for (x in 0 until width) {
                val x0 = max(0, x - radius)
                val x1 = min(width - 1, x + radius)
                val area = (x1 - x0 + 1) * (y1 - y0 + 1)
                val sum = rectSum(integral, width, x0, y0, x1, y1)
                val mean = sum / area
                val dark = Color.red(pixels[y * width + x]) < mean - 8
                binary[y * width + x] = if (dark) Color.BLACK else Color.WHITE
            }
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(binary, 0, width, 0, 0, width, height)
        return out
    }

    private fun rectSum(integral: LongArray, width: Int, left: Int, top: Int, right: Int, bottom: Int): Long {
        val stride = width + 1
        val a = integral[top * stride + left]
        val b = integral[top * stride + (right + 1)]
        val c = integral[(bottom + 1) * stride + left]
        val d = integral[(bottom + 1) * stride + (right + 1)]
        return d - b - c + a
    }
}

private class BitmapInk(bitmap: Bitmap) : InkImage {
    override val width = bitmap.width
    override val height = bitmap.height
    private val dark: BooleanArray

    init {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        dark = BooleanArray(pixels.size) { Color.red(pixels[it]) < 128 }
    }

    override fun isDark(x: Int, y: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        return dark[y * width + x]
    }
}
