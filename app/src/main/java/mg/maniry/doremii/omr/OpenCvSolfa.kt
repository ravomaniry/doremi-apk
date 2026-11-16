package mg.maniry.doremii.omr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import mg.maniry.doremii.editor.partition.PartitionData
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class OpenCvScan(val page: Bitmap, val result: ScanResult)

object OpenCvSolfa {
    private const val scoreFloor = 0.65
    private const val uncertainScore = 0.8f
    private val letterNames = setOf("d", "r", "m", "f", "s", "l", "t", "i", "a")
    private val pitchNames = setOf("d", "r", "m", "f", "s", "l", "t")
    private val typefaces = listOf(Typeface.SANS_SERIF, Typeface.SERIF, Typeface.MONOSPACE)
    private val scales = floatArrayOf(0.8f, 1f, 1.2f)

    @Volatile
    private var ready = false
    private val loadLock = Any()

    fun ensureLoaded(): Boolean {
        if (ready) return true
        synchronized(loadLock) {
            if (ready) return true
            ready = try {
                OpenCVLoader.initLocal()
            } catch (_: Throwable) {
                false
            }
            return ready
        }
    }

    fun read(source: Bitmap): OpenCvScan {
        if (!ensureLoaded()) throw IllegalStateException("Could not start OpenCV")
        if (source.isRecycled || source.width < 16 || source.height < 16) return OpenCvScan(source, emptyResult())
        val software = softwareBitmap(source)
        val bin = MatBin()
        try {
            val rgba = bin.keep(Mat())
            Utils.bitmapToMat(software, rgba)
            val gray = bin.keep(Mat())
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val quad = pageQuad(gray)
            val color = when {
                quad != null -> bin.keep(warpQuad(rgba, quad))
                else -> {
                    val angle = inkAngle(gray)
                    if (angle == null) rgba else bin.keep(rotate(rgba, angle))
                }
            }
            val work = if (color === rgba) {
                gray
            } else {
                val next = bin.keep(Mat())
                Imgproc.cvtColor(color, next, Imgproc.COLOR_RGBA2GRAY)
                next
            }
            Imgproc.GaussianBlur(work, work, Size(3.0, 3.0), 0.0)
            val binary = bin.keep(Mat())
            Imgproc.adaptiveThreshold(
                work,
                binary,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                oddBlock(work),
                8.0,
            )
            val page = if (color === rgba) software else matBitmap(color)
            val rows = findRows(binary, glyphBoxes(binary))
            if (rows.isEmpty()) {
                recycleCopy(source, software, page)
                return OpenCvScan(page, emptyResult())
            }
            val candidates = findBars(binary, rows)
            val hits = matchGlyphs(binary, rows, candidates)
            val bars = keepBars(candidates, hits, rows)
            recycleCopy(source, software, page)
            return OpenCvScan(page, assemble(binary, rows, hits, bars))
        } finally {
            bin.release()
        }
    }

    private fun assemble(
        binary: Mat,
        rows: List<Rect>,
        hits: List<Hit>,
        bars: List<VerticalStroke>,
    ): ScanResult {
        val kept = rows.mapNotNull { row ->
            val mine = hits.filter { belongs(it, row, rows) }
            if (!musical(binary, row, mine)) null else row to symbolsOf(mine)
        }
        if (kept.isEmpty()) return emptyResult()
        val grouped = systems(kept.map { it.first }, bars)
        val notes = mutableListOf<DebugNoteBox>()
        val glyphs = mutableListOf<DebugGlyph>()
        val windows = mutableListOf<DebugWindow>()
        val boxes = mutableListOf<OpenBox>()
        for (group in grouped) {
            val voices = group.mapNotNull { rect -> kept.firstOrNull { it.first === rect }?.second }
            if (voices.isEmpty()) continue
            val symbols = voices.flatten()
            if (symbols.isEmpty()) continue
            val left = symbols.minOf { it.left } - 2f
            val right = symbols.maxOf { it.right } + 2f
            val top = group.minOf { it.y }.toFloat()
            val bottom = group.maxOf { it.y + it.height }.toFloat()
            val strokes = bars.filter { stroke -> group.any { overlaps(stroke, it) } }
            boxes += MeasureDelimiters.open(voices, strokes, left, right, top, bottom)
            windows += DebugWindow(left, top, right, bottom)
            group.forEachIndexed { index, rect ->
                val id = PartitionData.voiceIds[index % PartitionData.voiceIds.size]
                notes += DebugNoteBox(
                    left = rect.x.toFloat(),
                    top = rect.y.toFloat(),
                    right = (rect.x + rect.width).toFloat(),
                    bottom = (rect.y + rect.height).toFloat(),
                    voiceIndex = index,
                    voiceId = id,
                    musical = true,
                )
            }
            for (symbol in symbols) {
                glyphs += DebugGlyph(symbol.text, symbol.left, symbol.top, symbol.right, symbol.bottom)
            }
        }
        if (boxes.isEmpty()) return emptyResult()
        val draft = ScanDraft(boxes, notes, glyphs, windows)
        val finished = MeasureDelimiters.finish(draft, null)
        val parse = finished.parse.copy(debug = finished.parse.debug.copy(glyphs = draft.glyphs))
        return ScanResult(parse, finished.signature, draft)
    }

    private fun pageQuad(gray: Mat): Array<Point>? {
        val binary = Mat()
        val clone = Mat()
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            binary.copyTo(clone)
            Imgproc.findContours(clone, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val largest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
            val area = Imgproc.contourArea(largest)
            val imageArea = gray.width().toDouble() * gray.height()
            if (area < imageArea * 0.25) return null
            val contour = MatOfPoint2f(*largest.toArray())
            val approx = MatOfPoint2f()
            try {
                val peri = Imgproc.arcLength(contour, true)
                Imgproc.approxPolyDP(contour, approx, 0.02 * peri, true)
                val points = approx.toArray()
                if (points.size != 4 || !convex(points) || !sidesReasonable(points, gray)) return null
                if (hugsBorder(points, gray)) return null
                return orderQuad(points)
            } finally {
                contour.release()
                approx.release()
            }
        } finally {
            binary.release()
            clone.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    private fun inkAngle(gray: Mat): Double? {
        val binary = Mat()
        val closed = Mat()
        val clone = Mat()
        val hierarchy = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(max(15.0, gray.width() / 20.0), 3.0))
        val contours = ArrayList<MatOfPoint>()
        try {
            Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, kernel)
            closed.copyTo(clone)
            Imgproc.findContours(clone, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val largest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
            if (Imgproc.contourArea(largest) < 32.0) return null
            val contour = MatOfPoint2f(*largest.toArray())
            try {
                val rect = Imgproc.minAreaRect(contour)
                var angle = rect.angle
                if (angle < -45.0) angle += 90.0
                if (abs(angle) < 0.75) return null
                return angle
            } finally {
                contour.release()
            }
        } finally {
            binary.release()
            closed.release()
            clone.release()
            hierarchy.release()
            kernel.release()
            contours.forEach { it.release() }
        }
    }

    private fun warpQuad(source: Mat, quad: Array<Point>): Mat {
        val width = max(hypot(quad[0].x - quad[1].x, quad[0].y - quad[1].y), hypot(quad[3].x - quad[2].x, quad[3].y - quad[2].y))
        val height = max(hypot(quad[0].x - quad[3].x, quad[0].y - quad[3].y), hypot(quad[1].x - quad[2].x, quad[1].y - quad[2].y))
        val destWidth = width.roundToInt().coerceIn(16, 4000)
        val destHeight = height.roundToInt().coerceIn(16, 4000)
        val from = MatOfPoint2f(*quad)
        val to = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(destWidth - 1.0, 0.0),
            Point(destWidth - 1.0, destHeight - 1.0),
            Point(0.0, destHeight - 1.0),
        )
        val matrix = Imgproc.getPerspectiveTransform(from, to)
        val out = Mat()
        try {
            Imgproc.warpPerspective(
                source,
                out,
                matrix,
                Size(destWidth.toDouble(), destHeight.toDouble()),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                Scalar(255.0, 255.0, 255.0, 255.0),
            )
        } finally {
            from.release()
            to.release()
            matrix.release()
        }
        return out
    }

    private fun rotate(source: Mat, angle: Double): Mat {
        val center = Point(source.cols() / 2.0, source.rows() / 2.0)
        val matrix = Imgproc.getRotationMatrix2D(center, angle, 1.0)
        val radians = Math.toRadians(angle)
        val sin = abs(kotlin.math.sin(radians))
        val cos = abs(kotlin.math.cos(radians))
        val width = (source.cols() * cos + source.rows() * sin).roundToInt().coerceIn(16, 4000)
        val height = (source.cols() * sin + source.rows() * cos).roundToInt().coerceIn(16, 4000)
        matrix.put(0, 2, matrix.get(0, 2)[0] + (width - source.cols()) / 2.0)
        matrix.put(1, 2, matrix.get(1, 2)[0] + (height - source.rows()) / 2.0)
        val out = Mat()
        try {
            Imgproc.warpAffine(
                source,
                out,
                matrix,
                Size(width.toDouble(), height.toDouble()),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                Scalar(255.0, 255.0, 255.0, 255.0),
            )
        } finally {
            matrix.release()
        }
        return out
    }

    private fun glyphBoxes(binary: Mat): List<Rect> {
        val clone = Mat()
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            binary.copyTo(clone)
            Imgproc.findContours(clone, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            return contours.map { Imgproc.boundingRect(it) }.filter { rect ->
                rect.width >= 2 && rect.height >= 2 && rect.width * rect.height >= 8 &&
                    !(rect.width <= 10 && rect.height > rect.width * 4)
            }
        } finally {
            clone.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    private fun findRows(binary: Mat, glyphs: List<Rect>): List<Rect> {
        val kernelWidth = max(24.0, binary.cols() / 12.0)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kernelWidth, 3.0))
        val closed = Mat()
        val clone = Mat()
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, kernel)
            closed.copyTo(clone)
            Imgproc.findContours(clone, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val wide = contours.map { Imgproc.boundingRect(it) }.filter { rect ->
                rect.width > binary.cols() * 0.1 && rect.height >= 8 && rect.width > rect.height * 1.6
            }
            if (wide.isEmpty()) return emptyList()
            val trimmed = mergeRects(wide.map { textBand(it, glyphs) })
            val heights = trimmed.map { it.height }.sorted()
            val median = heights[heights.size / 2].coerceAtLeast(1)
            return trimmed.filter { it.height >= median * 0.5 && it.width > it.height }
        } finally {
            kernel.release()
            closed.release()
            clone.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    private fun textBand(row: Rect, glyphs: List<Rect>): Rect {
        val inside = glyphs.filter { glyph ->
            val centerX = glyph.x + glyph.width / 2
            val centerY = glyph.y + glyph.height / 2
            centerX >= row.x && centerX < row.x + row.width &&
                centerY >= row.y && centerY < row.y + row.height
        }
        if (inside.isEmpty()) return row
        val heights = inside.map { it.height }.sorted()
        val median = heights[heights.size / 2].coerceAtLeast(1)
        val bodies = inside.filter { it.height >= median * 0.65f }
        val used = if (bodies.isEmpty()) inside else bodies
        val top = used.minOf { it.y }
        val bottom = used.maxOf { it.y + it.height }
        if (bottom - top < 6) return row
        return Rect(row.x, top, row.width, bottom - top)
    }

    private fun findBars(binary: Mat, rows: List<Rect>): List<VerticalStroke> {
        val rowHeight = medianHeight(rows)
        val kernelHeight = (rowHeight * 0.55f).toInt().coerceAtLeast(7)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, kernelHeight.toDouble()))
        val opened = Mat()
        val lines = Mat()
        try {
            Imgproc.morphologyEx(binary, opened, Imgproc.MORPH_OPEN, kernel)
            val minLength = rowHeight * 0.72
            val threshold = (rowHeight * 0.35f).toInt().coerceAtLeast(12)
            Imgproc.HoughLinesP(
                opened,
                lines,
                1.0,
                Math.PI / 180.0,
                threshold,
                minLength,
                rowHeight * 0.35,
            )
            val segments = mutableListOf<Segment>()
            for (index in 0 until lines.rows()) {
                val values = lines.get(index, 0) ?: continue
                if (values.size < 4) continue
                val dx = abs(values[2] - values[0])
                val dy = abs(values[3] - values[1])
                if (dy < minLength || dx > max(3.0, dy * 0.3)) continue
                segments += Segment(
                    x = (values[0] + values[2]) / 2.0,
                    top = min(values[1], values[3]),
                    bottom = max(values[1], values[3]),
                    length = dy,
                )
            }
            return mergeSegments(segments, rowHeight)
        } finally {
            kernel.release()
            opened.release()
            lines.release()
        }
    }

    private fun mergeSegments(segments: List<Segment>, rowHeight: Int): List<VerticalStroke> {
        if (segments.isEmpty()) return emptyList()
        val tolerance = max(4.0, rowHeight * 0.18)
        val groups = mutableListOf<MutableList<Segment>>()
        for (segment in segments.sortedBy { it.x }) {
            val group = groups.lastOrNull()
            if (group != null && abs(segment.x - group.last().x) <= tolerance) {
                group += segment
            } else {
                groups += mutableListOf(segment)
            }
        }
        return groups.mapNotNull { group ->
            val top = group.minOf { it.top }
            val bottom = group.maxOf { it.bottom }
            if (bottom - top < rowHeight * 0.7) return@mapNotNull null
            VerticalStroke(
                x = group.map { it.x }.average().toFloat(),
                top = top.toInt(),
                bottom = bottom.toInt(),
                score = group.sumOf { it.length }.toFloat(),
            )
        }
    }

    private fun matchGlyphs(binary: Mat, rows: List<Rect>, bars: List<VerticalStroke>): List<Hit> {
        val rowHeight = medianHeight(rows)
        val templates = buildTemplates(rowHeight)
        try {
            val found = mutableListOf<Hit>()
            for (row in rows) {
                val pad = (row.height * 0.9f).toInt()
                val y0 = (row.y - pad).coerceAtLeast(0)
                val y1 = (row.y + row.height + pad).coerceAtMost(binary.rows())
                val x0 = (row.x - row.height).coerceAtLeast(0)
                val x1 = (row.x + row.width + row.height).coerceAtMost(binary.cols())
                if (x1 - x0 < 8 || y1 - y0 < 8) continue
                val roi = binary.submat(y0, y1, x0, x1)
                val letters = mutableListOf<Hit>()
                try {
                    for (template in templates) {
                        if (template.name !in letterNames) continue
                        letters += peaks(roi, template, x0, y0)
                    }
                    val keptLetters = suppress(letters)
                    val punctImage = roi.clone()
                    try {
                        for (hit in keptLetters) {
                            val left = (hit.x - x0).coerceIn(0, punctImage.cols() - 1)
                            val top = (hit.y - y0).coerceIn(0, punctImage.rows() - 1)
                            val right = (hit.x + hit.w - x0).coerceIn(left + 1, punctImage.cols())
                            val bottom = (hit.y + hit.h - y0).coerceIn(top + 1, punctImage.rows())
                            Imgproc.rectangle(
                                punctImage,
                                Point(left.toDouble(), top.toDouble()),
                                Point((right - 1).toDouble(), (bottom - 1).toDouble()),
                                Scalar(0.0),
                                -1,
                            )
                        }
                        val marks = mutableListOf<Hit>()
                        for (template in templates) {
                            if (template.name in letterNames) continue
                            marks += peaks(punctImage, template, x0, y0)
                        }
                        found += keptLetters
                        found += suppress(marks)
                    } finally {
                        punctImage.release()
                    }
                } finally {
                    roi.release()
                }
            }
            val tall = bars.filter { it.height >= rowHeight * 1.15f }
            val clearOfBars = suppress(found).filter { hit ->
                tall.none { stroke ->
                    abs(hit.centerX - stroke.x) <= max(8f, rowHeight * 0.18f) &&
                        hit.centerY >= stroke.top && hit.centerY <= stroke.bottom
                }
            }
            return dropNested(clearOfBars)
        } finally {
            templates.forEach { it.mat.release() }
        }
    }

    private fun peaks(image: Mat, template: GlyphTemplate, originX: Int, originY: Int): List<Hit> {
        if (template.mat.cols() >= image.cols() || template.mat.rows() >= image.rows()) return emptyList()
        if (Core.countNonZero(template.mat) < 4) return emptyList()
        val result = Mat()
        try {
            Imgproc.matchTemplate(image, template.mat, result, Imgproc.TM_CCOEFF_NORMED)
            val hits = mutableListOf<Hit>()
            val width = template.mat.cols()
            val height = template.mat.rows()
            repeat(48) {
                val peak = Core.minMaxLoc(result)
                if (!peak.maxVal.isFinite() || peak.maxVal < scoreFloor) return hits
                val x = peak.maxLoc.x.toInt()
                val y = peak.maxLoc.y.toInt()
                hits += Hit(template.name, originX + x, originY + y, width, height, peak.maxVal.toFloat())
                val left = (x - width / 2).coerceAtLeast(0)
                val top = (y - height / 2).coerceAtLeast(0)
                val right = (x + width / 2).coerceAtMost(result.cols() - 1)
                val bottom = (y + height / 2).coerceAtMost(result.rows() - 1)
                if (right > left && bottom > top) {
                    Imgproc.rectangle(
                        result,
                        Point(left.toDouble(), top.toDouble()),
                        Point(right.toDouble(), bottom.toDouble()),
                        Scalar(0.0),
                        -1,
                    )
                }
            }
            return hits
        } finally {
            result.release()
        }
    }

    private fun buildTemplates(rowHeight: Int): List<GlyphTemplate> {
        val names = listOf("d", "r", "m", "f", "s", "l", "t", "i", "a", ":", "-", "'", ".", ",")
        val templates = mutableListOf<GlyphTemplate>()
        for (typeface in typefaces) {
            val probe = Paint().apply {
                this.typeface = typeface
                textSize = 100f
            }
            val bounds = android.graphics.Rect()
            probe.getTextBounds("d", 0, 1, bounds)
            val unit = bounds.height().coerceAtLeast(1)
            for (scale in scales) {
                val textSize = rowHeight * scale * 100f / unit
                for (name in names) {
                    val mat = drawGlyph(name, typeface, textSize) ?: continue
                    templates += GlyphTemplate(name, mat)
                }
            }
        }
        return templates
    }

    private fun drawGlyph(text: String, typeface: Typeface, textSize: Float): Mat? {
        val paint = Paint().apply {
            isAntiAlias = false
            color = Color.BLACK
            this.typeface = typeface
            this.textSize = textSize
        }
        val bounds = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        if (bounds.width() < 2 || bounds.height() < 2) return null
        val pad = 2
        val bitmap = Bitmap.createBitmap(bounds.width() + pad * 2, bounds.height() + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText(text, pad - bounds.left.toFloat(), pad - bounds.top.toFloat(), paint)
        val rgba = Mat()
        val gray = Mat()
        val binary = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.0)
            Imgproc.threshold(gray, binary, 200.0, 255.0, Imgproc.THRESH_BINARY_INV)
            if (Core.countNonZero(binary) < 4) return null
            return binary.clone()
        } finally {
            bitmap.recycle()
            rgba.release()
            gray.release()
            binary.release()
        }
    }

    private fun symbolsOf(hits: List<Hit>): List<OcrSymbol> {
        val letters = hits.filter { it.name in letterNames }
        return hits
            .map { hit -> hit to markText(hit, letters) }
            .sortedBy { (hit, text) -> sortKey(hit, text, letters) }
            .map { (hit, text) ->
                OcrSymbol(
                    text = text,
                    left = hit.x.toFloat(),
                    top = hit.y.toFloat(),
                    right = (hit.x + hit.w).toFloat(),
                    bottom = (hit.y + hit.h).toFloat(),
                    confidence = hit.score,
                    inferred = hit.score < uncertainScore,
                )
            }
    }

    private fun markText(hit: Hit, letters: List<Hit>): String {
        if (hit.name != "." && hit.name != "'") return hit.name
        val host = letters
            .filter { overlapX(hit, it) > hit.w * 0.35f }
            .minByOrNull { abs(it.centerY - hit.centerY) }
            ?: return hit.name
        return if (hit.centerY < host.centerY) "'" else ","
    }

    private fun sortKey(hit: Hit, text: String, letters: List<Hit>): Float {
        if (text == "'" || text == ",") {
            val host = letters
                .filter { overlapX(hit, it) > hit.w * 0.35f }
                .minByOrNull { abs(it.centerX - hit.centerX) }
            if (host != null) return host.centerX + 0.4f
        }
        return hit.centerX
    }

    private fun musical(binary: Mat, row: Rect, hits: List<Hit>): Boolean {
        if (hits.none { it.name in pitchNames } || hits.size < 3) return false
        val roi = binary.submat(row)
        try {
            val rows = roi.rows()
            val cols = roi.cols()
            if (rows < 1 || cols < 1) return false
            val bytes = ByteArray(rows * cols)
            roi.get(0, 0, bytes)
            var ink = 0
            var covered = 0
            for (x in 0 until cols) {
                var dark = false
                for (y in 0 until rows) {
                    if (bytes[y * cols + x].toInt() and 0xFF > 0) {
                        dark = true
                        break
                    }
                }
                if (!dark) continue
                ink++
                val pageX = row.x + x
                if (hits.any { pageX >= it.x && pageX < it.x + it.w }) covered++
            }
            return ink >= 8 && covered >= ink * 0.42f
        } finally {
            roi.release()
        }
    }

    private fun keepBars(candidates: List<VerticalStroke>, hits: List<Hit>, rows: List<Rect>): List<VerticalStroke> {
        val rowHeight = medianHeight(rows)
        return candidates.filter { stroke ->
            val tall = stroke.height >= rowHeight * 1.15 || rows.count { overlaps(stroke, it) } >= 2
            if (tall) return@filter true
            val stem = hits.any { hit ->
                hit.name in letterNames &&
                    stroke.x >= hit.x - 2 && stroke.x <= hit.x + hit.w + 2 &&
                    hit.y < stroke.bottom && hit.y + hit.h > stroke.top
            }
            !stem
        }
    }

    private fun dropNested(hits: List<Hit>): List<Hit> {
        val colons = hits.filter { it.name == ":" }
        val letters = hits.filter { it.name in letterNames }
        return hits.filter { hit ->
            if (hit.name in letterNames) return@filter true
            if (colons.any { colon -> colon !== hit && centerInside(colon, hit) }) return@filter false
            if (hit.name == "-" || hit.name == "." || hit.name == "," || hit.name == "'") {
                if (letters.any { centerInside(it, hit) }) return@filter false
            }
            true
        }
    }

    private fun suppress(hits: List<Hit>): List<Hit> {
        val kept = mutableListOf<Hit>()
        for (hit in hits.sortedByDescending { it.score }) {
            val conflict = kept.indexOfFirst { overlapsHit(it, hit) || sameGlyph(it, hit) }
            if (conflict < 0) {
                kept += hit
                continue
            }
            val previous = kept[conflict]
            val larger = if (hit.w * hit.h >= previous.w * previous.h) hit else previous
            val smaller = if (larger === hit) previous else hit
            kept[conflict] = if (larger.w * larger.h > smaller.w * smaller.h * 1.25) larger else previous
        }
        return kept
    }

    private fun sameGlyph(first: Hit, second: Hit): Boolean {
        val width = min(first.x + first.w, second.x + second.w) - max(first.x, second.x)
        val height = min(first.y + first.h, second.y + second.h) - max(first.y, second.y)
        if (width <= 0 || height <= 0) return false
        return width >= min(first.w, second.w) * 0.3 && height >= min(first.h, second.h) * 0.5
    }

    private fun overlapsHit(first: Hit, second: Hit): Boolean {
        if (centerInside(first, second) || centerInside(second, first)) return true
        val left = max(first.x, second.x)
        val top = max(first.y, second.y)
        val right = min(first.x + first.w, second.x + second.w)
        val bottom = min(first.y + first.h, second.y + second.h)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return false
        val inter = width * height
        val union = first.w * first.h + second.w * second.h - inter
        return union > 0 && inter.toFloat() / union > 0.25f
    }

    private fun centerInside(outer: Hit, inner: Hit): Boolean {
        return inner.centerX >= outer.x && inner.centerX < outer.x + outer.w &&
            inner.centerY >= outer.y && inner.centerY < outer.y + outer.h
    }

    private fun overlapX(first: Hit, second: Hit): Float {
        val left = max(first.x, second.x)
        val right = min(first.x + first.w, second.x + second.w)
        return (right - left).toFloat()
    }

    private fun belongs(hit: Hit, row: Rect, rows: List<Rect>): Boolean {
        val nearest = rows.minBy { distanceToRow(hit, it) }
        return nearest === row && distanceToRow(hit, row) <= row.height * 0.9f
    }

    private fun distanceToRow(hit: Hit, row: Rect): Float {
        val top = row.y.toFloat()
        val bottom = (row.y + row.height).toFloat()
        return when {
            hit.centerY < top -> top - hit.centerY
            hit.centerY > bottom -> hit.centerY - bottom
            else -> 0f
        }
    }

    private fun systems(rows: List<Rect>, bars: List<VerticalStroke>): List<List<Rect>> {
        if (rows.isEmpty()) return emptyList()
        val parent = IntArray(rows.size) { it }
        fun find(index: Int): Int {
            var current = index
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }
        fun union(first: Int, second: Int) {
            val left = find(first)
            val right = find(second)
            if (left != right) parent[right] = left
        }
        for (bar in bars) {
            val hit = rows.indices.filter { overlaps(bar, rows[it]) }
            for (index in 1 until hit.size) union(hit[0], hit[index])
        }
        return rows.indices.groupBy { find(it) }.values
            .map { indexes -> indexes.map { rows[it] }.sortedBy { it.y } }
            .sortedBy { it.first().y }
    }

    private fun overlaps(stroke: VerticalStroke, row: Rect): Boolean {
        return stroke.bottom > row.y && stroke.top < row.y + row.height
    }

    private fun mergeRects(rects: List<Rect>): List<Rect> {
        val merged = mutableListOf<Rect>()
        for (rect in rects.sortedBy { it.y }) {
            val last = merged.lastOrNull()
            if (last != null && verticalOverlap(last, rect) > min(last.height, rect.height) * 0.5f) {
                merged[merged.lastIndex] = unionRect(last, rect)
            } else {
                merged += rect
            }
        }
        return merged
    }

    private fun verticalOverlap(first: Rect, second: Rect): Int {
        return min(first.y + first.height, second.y + second.height) - max(first.y, second.y)
    }

    private fun unionRect(first: Rect, second: Rect): Rect {
        val left = min(first.x, second.x)
        val top = min(first.y, second.y)
        val right = max(first.x + first.width, second.x + second.width)
        val bottom = max(first.y + first.height, second.y + second.height)
        return Rect(left, top, right - left, bottom - top)
    }

    private fun convex(points: Array<Point>): Boolean {
        val contour = MatOfPoint(*points)
        return try {
            Imgproc.isContourConvex(contour)
        } finally {
            contour.release()
        }
    }

    private fun sidesReasonable(points: Array<Point>, gray: Mat): Boolean {
        val ordered = orderQuad(points)
        val shortest = min(gray.width(), gray.height()) * 0.08
        for (index in ordered.indices) {
            val next = ordered[(index + 1) % ordered.size]
            if (hypot(ordered[index].x - next.x, ordered[index].y - next.y) < shortest) return false
        }
        return true
    }

    private fun hugsBorder(points: Array<Point>, gray: Mat): Boolean {
        val marginX = gray.width() * 0.03
        val marginY = gray.height() * 0.03
        return points.minOf { it.x } <= marginX &&
            points.minOf { it.y } <= marginY &&
            points.maxOf { it.x } >= gray.width() - marginX &&
            points.maxOf { it.y } >= gray.height() - marginY
    }

    private fun orderQuad(points: Array<Point>): Array<Point> {
        val topLeft = points.minBy { it.x + it.y }
        val bottomRight = points.maxBy { it.x + it.y }
        val topRight = points.maxBy { it.x - it.y }
        val bottomLeft = points.minBy { it.x - it.y }
        return arrayOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun medianHeight(rows: List<Rect>): Int {
        val heights = rows.map { it.height }.sorted()
        return heights[heights.size / 2].coerceAtLeast(8)
    }

    private fun oddBlock(gray: Mat): Int {
        val limit = min(gray.width(), gray.height())
        if (limit < 3) return 3
        val base = max(15, limit / 16)
        var odd = if (base % 2 == 0) base + 1 else base
        val cap = if (limit % 2 == 0) limit - 1 else limit
        if (odd > cap) odd = cap
        return odd.coerceAtLeast(3)
    }

    private fun softwareBitmap(source: Bitmap): Bitmap {
        if (source.config == Bitmap.Config.ARGB_8888 || source.config == Bitmap.Config.RGB_565) return source
        return source.copy(Bitmap.Config.ARGB_8888, false) ?: source
    }

    private fun matBitmap(mat: Mat): Bitmap {
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bitmap)
        return bitmap
    }

    private fun recycleCopy(source: Bitmap, software: Bitmap, page: Bitmap) {
        if (software !== source && software !== page && !software.isRecycled) software.recycle()
    }

    private fun emptyResult(): ScanResult {
        val parse = SolfaParse(emptyList(), null, empty = true)
        return ScanResult(parse, 4, ScanDraft(emptyList(), emptyList(), emptyList(), emptyList()))
    }

    private class MatBin {
        private val mats = ArrayList<Mat>()

        fun <T : Mat> keep(mat: T): T {
            mats += mat
            return mat
        }

        fun release() {
            for (index in mats.indices.reversed()) {
                try {
                    mats[index].release()
                } catch (_: Throwable) {
                }
            }
            mats.clear()
        }
    }

    private data class Hit(
        val name: String,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val score: Float,
    ) {
        val centerX: Float get() = x + w / 2f
        val centerY: Float get() = y + h / 2f
    }

    private data class Segment(val x: Double, val top: Double, val bottom: Double, val length: Double)

    private data class GlyphTemplate(val name: String, val mat: Mat)
}
