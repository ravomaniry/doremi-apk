package mg.maniry.doremii.omr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class GlyphRaster(
    val width: Int,
    val height: Int,
    val dark: BooleanArray,
)

internal class GlyphShape(
    val mask: BooleanArray,
    val holes: Int,
    val aspect: Float,
    val rightStem: Float,
)

internal class GlyphPattern(
    val letter: Char,
    val shape: GlyphShape,
)

internal object SolfaPatterns {
    const val MASK_WIDTH = 32
    const val MASK_HEIGHT = 48

    private val letters = charArrayOf('d', 'r', 'm', 'f', 's', 'l', 't', 'i', 'a')
    private val cached: List<GlyphPattern> by lazy {
        try {
            drawAndroid()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun android(): List<GlyphPattern> = cached

    fun fromRaster(letter: Char, raster: GlyphRaster): GlyphPattern? {
        val shape = shapeOf(raster) ?: return null
        return GlyphPattern(letter, shape)
    }

    fun measure(raster: GlyphRaster): GlyphShape? = shapeOf(raster)

    fun cropped(raster: GlyphRaster): GlyphRaster? {
        if (raster.width <= 0 || raster.height <= 0 || raster.dark.size != raster.width * raster.height) {
            return null
        }
        var minX = raster.width
        var minY = raster.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until raster.height) {
            val row = y * raster.width
            for (x in 0 until raster.width) {
                if (!raster.dark[row + x]) continue
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        if (maxX < minX || maxY < minY) return null
        val width = maxX - minX + 1
        val height = maxY - minY + 1
        val dark = BooleanArray(width * height)
        for (y in 0 until height) {
            val source = (y + minY) * raster.width + minX
            val target = y * width
            for (x in 0 until width) dark[target + x] = raster.dark[source + x]
        }
        return GlyphRaster(width, height, dark)
    }

    private fun shapeOf(raster: GlyphRaster): GlyphShape? {
        val box = cropped(raster) ?: return null
        val area = box.dark.count { it }
        if (area < 4) return null
        return GlyphShape(
            mask = normalize(box.width, box.height, box.dark),
            holes = holeCount(box.width, box.height, box.dark),
            aspect = box.width.toFloat() / box.height.toFloat(),
            rightStem = rightWeight(box.width, box.height, box.dark),
        )
    }

    private fun normalize(width: Int, height: Int, dark: BooleanArray): BooleanArray {
        val mask = BooleanArray(MASK_WIDTH * MASK_HEIGHT)
        val scale = min(MASK_WIDTH.toFloat() / width, MASK_HEIGHT.toFloat() / height)
        val drawnW = max(1, (width * scale).roundToInt()).coerceAtMost(MASK_WIDTH)
        val drawnH = max(1, (height * scale).roundToInt()).coerceAtMost(MASK_HEIGHT)
        val originX = (MASK_WIDTH - drawnW) / 2
        val originY = (MASK_HEIGHT - drawnH) / 2
        for (y in 0 until drawnH) {
            val sourceY = ((y + 0.5f) * height / drawnH).toInt().coerceIn(0, height - 1)
            val row = (originY + y) * MASK_WIDTH + originX
            val sourceRow = sourceY * width
            for (x in 0 until drawnW) {
                val sourceX = ((x + 0.5f) * width / drawnW).toInt().coerceIn(0, width - 1)
                if (dark[sourceRow + sourceX]) mask[row + x] = true
            }
        }
        return mask
    }

    private fun rightWeight(width: Int, height: Int, dark: BooleanArray): Float {
        val cut = width * 2 / 3
        var total = 0
        var right = 0
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (!dark[row + x]) continue
                total++
                if (x >= cut) right++
            }
        }
        if (total == 0) return 0f
        return right.toFloat() / total
    }

    private fun holeCount(width: Int, height: Int, dark: BooleanArray): Int {
        if (width < 3 || height < 3) return 0
        return try {
            OpenCvImages.ensure()
            val mat = Mat(height, width, CvType.CV_8UC1)
            val bytes = ByteArray(dark.size) { index -> if (dark[index]) 255.toByte() else 0 }
            if (bytes.isNotEmpty()) mat.put(0, 0, bytes)
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            val copy = mat.clone()
            try {
                Imgproc.findContours(
                    copy,
                    contours,
                    hierarchy,
                    Imgproc.RETR_CCOMP,
                    Imgproc.CHAIN_APPROX_SIMPLE,
                )
                countHoles(contours, hierarchy)
            } finally {
                copy.release()
                contours.forEach { it.release() }
                hierarchy.release()
                mat.release()
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun countHoles(contours: List<MatOfPoint>, hierarchy: Mat): Int {
        if (contours.isEmpty() || hierarchy.empty()) return 0
        var holes = 0
        for (index in contours.indices) {
            val parent = hierarchyValue(hierarchy, index, 3)
            if (parent < 0) continue
            val grand = hierarchyValue(hierarchy, parent, 3)
            if (grand < 0) holes++
        }
        return holes
    }

    private fun hierarchyValue(hierarchy: Mat, index: Int, field: Int): Int {
        if (index < 0) return -1
        val row = if (hierarchy.rows() == 1) 0 else index
        val col = if (hierarchy.rows() == 1) index else 0
        if (row !in 0 until hierarchy.rows() || col !in 0 until hierarchy.cols()) return -1
        val values = hierarchy.get(row, col) ?: return -1
        if (field !in values.indices) return -1
        return values[field].toInt()
    }

    private fun drawAndroid(): List<GlyphPattern> {
        val faces = ArrayList<Typeface>(6)
        for (serif in booleanArrayOf(true, false)) {
            val family = if (serif) Typeface.SERIF else Typeface.SANS_SERIF
            faces += Typeface.create(family, Typeface.NORMAL)
            faces += Typeface.create(family, Typeface.BOLD)
            faces += Typeface.create(family, Typeface.ITALIC)
        }
        val drawn = ArrayList<GlyphPattern>(faces.size * letters.size)
        for (face in faces) {
            for (letter in letters) {
                val raster = draw(letter, face) ?: continue
                drawn += fromRaster(letter, raster) ?: continue
            }
        }
        return drawn
    }

    private fun draw(letter: Char, face: Typeface): GlyphRaster? {
        val paint = Paint().apply {
            typeface = face
            textSize = 96f
            color = Color.BLACK
            isAntiAlias = false
            isSubpixelText = false
        }
        val text = letter.toString()
        val metrics = paint.fontMetrics
        val width = (paint.measureText(text) + 24f).toInt().coerceAtLeast(8)
        val height = (metrics.descent - metrics.ascent + 16f).toInt().coerceAtLeast(8)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawText(text, 8f, 8f - metrics.ascent, paint)
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val dark = BooleanArray(pixels.size) { index ->
                val pixel = pixels[index]
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                (red + green + blue) / 3 < 160
            }
            return GlyphRaster(width, height, dark)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}
