package mg.maniry.doremii.omr

import android.graphics.Bitmap
import android.graphics.Color
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

internal class MatView(val mat: Mat, private val owned: Boolean) {
    fun close() {
        if (owned) mat.release()
    }
}

internal class MatInk(val gray: Mat, val ink: Mat) : InkImage {
    override val width = ink.cols()
    override val height = ink.rows()
    private val dark: BooleanArray
    private val levels: ByteArray
    private var released = false

    init {
        val count = width * height
        val inkBytes = ByteArray(count)
        val grayBytes = ByteArray(count)
        if (count > 0) {
            ink.get(0, 0, inkBytes)
            gray.get(0, 0, grayBytes)
        }
        dark = BooleanArray(count) { inkBytes[it].toInt() != 0 }
        levels = grayBytes
    }

    fun release() {
        if (released) return
        released = true
        gray.release()
        ink.release()
    }

    override fun isDark(x: Int, y: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        return dark[y * width + x]
    }

    override fun shade(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 255
        return levels[y * width + x].toInt() and 0xFF
    }
}

internal data class InkBlob(
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
    val count: Int,
)

internal data class InkPatch(
    val minX: Int,
    val minY: Int,
    val width: Int,
    val height: Int,
    val count: Int,
)

internal object OpenCvImages {
    @Volatile
    private var loaded = false

    fun ensure() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val local = try {
                OpenCVLoader.initLocal()
            } catch (_: Throwable) {
                false
            }
            if (!local) loadDesktop()
            loaded = true
        }
    }

    fun normalizedGray(bitmap: Bitmap): Mat {
        ensure()
        val rgba = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Core.normalize(gray, gray, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8U)
            return gray
        } catch (error: Throwable) {
            gray.release()
            throw error
        } finally {
            rgba.release()
        }
    }

    fun adaptiveInk(gray: Mat): Mat {
        ensure()
        val ink = Mat()
        val width = gray.cols()
        val height = gray.rows()
        val limit = min(width, height)
        if (limit < 3) {
            Imgproc.threshold(gray, ink, 200.0, 255.0, Imgproc.THRESH_BINARY_INV)
            return ink
        }
        val radius = max(8, limit / 32)
        var block = radius * 2 + 1
        if (block > limit) block = if (limit % 2 == 0) limit - 1 else limit
        if (block < 3) block = 3
        if (block % 2 == 0) block -= 1
        Imgproc.adaptiveThreshold(
            gray,
            ink,
            255.0,
            Imgproc.ADAPTIVE_THRESH_MEAN_C,
            Imgproc.THRESH_BINARY_INV,
            block,
            8.0,
        )
        return ink
    }

    fun toOpaqueBitmap(gray: Mat): Bitmap {
        val width = gray.cols()
        val height = gray.rows()
        val bytes = ByteArray(width * height)
        if (bytes.isNotEmpty()) gray.get(0, 0, bytes)
        val pixels = IntArray(bytes.size) { index ->
            val value = bytes[index].toInt() and 0xFF
            Color.rgb(value, value, value)
        }
        val out = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        if (width > 0 && height > 0) out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    fun inkOf(image: InkImage): MatView {
        ensure()
        if (image is MatInk) return MatView(image.ink, false)
        return MatView(raster(image) { x, y -> image.isDark(x, y) }, true)
    }

    fun grayOf(image: InkImage): MatView {
        ensure()
        if (image is MatInk) return MatView(image.gray, false)
        val mat = Mat(image.height, image.width, CvType.CV_8UC1)
        val bytes = ByteArray(image.width * image.height)
        var index = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                bytes[index++] = image.shade(x, y).coerceIn(0, 255).toByte()
            }
        }
        if (bytes.isNotEmpty()) mat.put(0, 0, bytes)
        return MatView(mat, true)
    }

    fun shadeMask(image: InkImage, maxShade: Int): MatView {
        ensure()
        val gray = grayOf(image)
        val mask = Mat()
        return try {
            Imgproc.threshold(gray.mat, mask, maxShade.toDouble(), 255.0, Imgproc.THRESH_BINARY_INV)
            MatView(mask, true)
        } catch (error: Throwable) {
            mask.release()
            throw error
        } finally {
            gray.close()
        }
    }

    fun toInk(ink: Mat): InkImage {
        val width = ink.cols()
        val height = ink.rows()
        val bytes = ByteArray(width * height)
        if (bytes.isNotEmpty()) ink.get(0, 0, bytes)
        val dark = BooleanArray(bytes.size) { bytes[it].toInt() != 0 }
        return ArrayInk(width, height, dark)
    }

    fun rowCounts(ink: Mat): IntArray {
        if (ink.empty() || ink.rows() == 0) return IntArray(0)
        val sum = Mat()
        try {
            Core.reduce(ink, sum, 1, Core.REDUCE_SUM, CvType.CV_32S)
            val raw = IntArray(ink.rows())
            sum.get(0, 0, raw)
            return IntArray(raw.size) { raw[it] / 255 }
        } finally {
            sum.release()
        }
    }

    fun horizontalProjection(image: InkImage, top: Int, bottom: Int, skip: BooleanArray?): IntArray {
        val mask = inkOf(image)
        try {
            val counts = IntArray(image.height)
            if (bottom <= top || image.width == 0) return counts
            val roi = mask.mat.submat(top, bottom, 0, image.width)
            val work = if (skip == null) roi else roi.clone()
            try {
                if (skip != null) {
                    for (x in skip.indices) {
                        if (!skip[x] || x >= work.cols()) continue
                        val column = work.col(x)
                        column.setTo(Scalar(0.0))
                        column.release()
                    }
                }
                val partial = rowCounts(work)
                for (index in partial.indices) counts[top + index] = partial[index]
            } finally {
                if (work !== roi) work.release()
                roi.release()
            }
            return counts
        } finally {
            mask.close()
        }
    }

    fun verticalRunColumns(image: InkImage, top: Int, bottom: Int, minLength: Int): BooleanArray {
        val mask = inkOf(image)
        try {
            val columns = BooleanArray(image.width)
            if (bottom <= top || image.width == 0 || minLength <= 0) return columns
            val roi = mask.mat.submat(top, bottom, 0, image.width)
            val opened = Mat()
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(1.0, minLength.toDouble()),
            )
            val sums = Mat()
            try {
                Imgproc.morphologyEx(roi, opened, Imgproc.MORPH_OPEN, kernel)
                Core.reduce(opened, sums, 0, Core.REDUCE_SUM, CvType.CV_32S)
                val raw = IntArray(image.width)
                sums.get(0, 0, raw)
                for (x in raw.indices) columns[x] = raw[x] > 0
            } finally {
                roi.release()
                opened.release()
                kernel.release()
                sums.release()
            }
            return columns
        } finally {
            mask.close()
        }
    }

    fun blobs(image: InkImage, top: Int, bottom: Int): List<InkBlob> {
        val mask = inkOf(image)
        try {
            if (bottom <= top || image.width <= 0) return emptyList()
            val roi = mask.mat.submat(top, bottom, 0, image.width)
            val labels = Mat()
            val stats = Mat()
            val centroids = Mat()
            try {
                val count = Imgproc.connectedComponentsWithStats(
                    roi,
                    labels,
                    stats,
                    centroids,
                    4,
                    CvType.CV_32S,
                )
                val found = ArrayList<InkBlob>(max(0, count - 1))
                for (index in 1 until count) {
                    val area = stats.get(index, Imgproc.CC_STAT_AREA)[0].toInt()
                    if (area < 3) continue
                    val x = stats.get(index, Imgproc.CC_STAT_LEFT)[0].toInt()
                    val y = stats.get(index, Imgproc.CC_STAT_TOP)[0].toInt() + top
                    val width = stats.get(index, Imgproc.CC_STAT_WIDTH)[0].toInt()
                    val height = stats.get(index, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                    found.add(InkBlob(x, x + width - 1, y, y + height - 1, area))
                }
                return found
            } finally {
                roi.release()
                labels.release()
                stats.release()
                centroids.release()
            }
        } finally {
            mask.close()
        }
    }

    fun patch(image: InkImage, left: Int, top: Int, right: Int, bottom: Int): InkPatch? {
        val mask = inkOf(image)
        try {
            if (right <= left || bottom <= top) return null
            val roi = mask.mat.submat(top, bottom, left, right)
            val points = MatOfPoint()
            try {
                val count = Core.countNonZero(roi)
                if (count <= 0) return null
                Core.findNonZero(roi, points)
                val box = Imgproc.boundingRect(points)
                return InkPatch(left + box.x, top + box.y, box.width, box.height, count)
            } finally {
                roi.release()
                points.release()
            }
        } finally {
            mask.close()
        }
    }

    fun rowsDark(image: InkImage, x0: Int, x1: Int, y0: Int, y1: Int): BooleanArray {
        val mask = inkOf(image)
        try {
            val rows = BooleanArray((y1 - y0).coerceAtLeast(0))
            if (rows.isEmpty() || x1 < x0) return rows
            val roi = mask.mat.submat(y0, y1, x0, x1 + 1)
            val reduced = Mat()
            try {
                Core.reduce(roi, reduced, 1, Core.REDUCE_MAX, CvType.CV_8U)
                val bytes = ByteArray(rows.size)
                reduced.get(0, 0, bytes)
                for (index in rows.indices) rows[index] = bytes[index].toInt() != 0
            } finally {
                roi.release()
                reduced.release()
            }
            return rows
        } finally {
            mask.close()
        }
    }

    fun rowRun(image: InkImage, x: Int, y: Int): Int {
        val mask = inkOf(image)
        try {
            if (y !in 0 until image.height || image.width == 0) return 0
            val bytes = ByteArray(image.width)
            mask.mat.get(y, 0, bytes)
            var width = 0
            var cursor = x
            while (cursor >= 0 && bytes[cursor].toInt() != 0) {
                width++
                cursor--
            }
            cursor = x + 1
            while (cursor < bytes.size && bytes[cursor].toInt() != 0) {
                width++
                cursor++
            }
            return width
        } finally {
            mask.close()
        }
    }

    private fun raster(image: InkImage, dark: (Int, Int) -> Boolean): Mat {
        val mat = Mat(image.height, image.width, CvType.CV_8UC1)
        val bytes = ByteArray(image.width * image.height)
        var index = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                bytes[index++] = if (dark(x, y)) 255.toByte() else 0
            }
        }
        if (bytes.isNotEmpty()) mat.put(0, 0, bytes)
        return mat
    }

    private fun loadDesktop() {
        try {
            Class.forName("nu.pattern.OpenCV").getMethod("loadLocally").invoke(null)
        } catch (error: Throwable) {
            throw IllegalStateException("OpenCV failed to load", error)
        }
    }
}

internal class ArrayInk(
    override val width: Int,
    override val height: Int,
    private val dark: BooleanArray,
) : InkImage {
    override fun isDark(x: Int, y: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        return dark[y * width + x]
    }
}
