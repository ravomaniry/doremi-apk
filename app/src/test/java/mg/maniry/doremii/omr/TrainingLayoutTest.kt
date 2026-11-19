package mg.maniry.doremii.omr

import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TrainingLayoutTest {
    @Test
    fun matchesAnnotatedBoxesLinesAndBeats() {
        val dir = trainingDir()
        val report = StringBuilder()
        val failures = mutableListOf<String>()
        for (page in 2..14) {
            val ink = loadInk(File(dir, "$page.png"))
            val divided = try {
                scan(ink)
            } finally {
                ink.release()
            }
            val boxes = wideRects(File(dir, "$page-boxes.png"))
            val lines = wideRects(File(dir, "$page-lines.png"))
            val beatsFile = File(dir, "$page-beats.png")
            val beats = if (beatsFile.isFile) beatStrokes(beatsFile) else null
            failures += score(page, divided, boxes, lines, beats, report)
        }
        val text = report.toString().trimEnd()
        assertTrue(text + "\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private data class Scanned(
        val lines: List<DebugLine>,
        val blocks: List<DebugBlock>,
        val beats: List<DebugMeasureLine>,
    )

    private fun scan(ink: MatInk): Scanned {
        val stripped = HorizontalRules.strip(ink)
        val lines = TextLineDetector.detect(stripped)
        val blocks = TextBlockDetector.detect(stripped, lines)
        val marks = LineMarks.find(stripped, lines, blocks)
        val classified = MusicalLineClassifier.classify(lines, blocks, marks)
        val divided = BeatDividers.find(stripped, classified.lines, classified.blocks, marks)
        return Scanned(divided.lines, classified.blocks, divided.beats)
    }

    private fun score(
        page: Int,
        predicted: Scanned,
        boxes: List<AnnRect>,
        lines: List<AnnRect>,
        beats: List<AnnRect>?,
        report: StringBuilder,
    ): List<String> {
        val failures = mutableListOf<String>()
        val predBlocks = predicted.blocks.sortedBy { it.top }
        if (predBlocks.size != boxes.size) {
            failures.add(
                "page $page box count ${predBlocks.size} != ${boxes.size} pred ${predBlocks.joinToString { boxText(it) }}",
            )
        }
        val matches = matchBoxes(predBlocks, boxes)
        val ious = matches.map { it.third }
        report.append(
            "page $page boxes ${predBlocks.size}/${boxes.size}" +
                if (ious.isEmpty()) " unmatched" else " meanIoU ${"%.3f".format(ious.average())} minIoU ${"%.3f".format(ious.min())}",
        )
        for ((pred, gt, iou) in matches) {
            if (iou < 0.55f) {
                failures.add("page $page box IoU ${"%.3f".format(iou)} pred ${boxText(pred)} gt ${rectText(gt)}")
            }
            val predLines = musicalLines(predicted, pred)
            val gtLines = lines.filter { centerInside(it.top.toFloat(), it.bottom.toFloat(), gt.top.toFloat(), gt.bottom.toFloat()) }
            if (predLines.size != gtLines.size) {
                failures.add(
                    "page $page lines ${predLines.size} != ${gtLines.size} in box ${rectText(gt)} predY ${predLines.joinToString { "${it.top.toInt()}-${it.bottom.toInt()}" }}",
                )
            }
            for ((line, ann, overlap) in matchLines(predLines, gtLines)) {
                if (overlap < 0.45f) {
                    failures.add(
                        "page $page line overlap ${"%.3f".format(overlap)} pred ${line.top.toInt()}-${line.bottom.toInt()} gt ${ann.top}-${ann.bottom}",
                    )
                }
            }
            if (beats != null) {
                failures += scoreBeats(page, predicted, pred, gt, beats)
            }
        }
        val lineScores = matches.flatMap { (pred, gt, _) ->
            val predLines = musicalLines(predicted, pred)
            val gtLines = lines.filter { centerInside(it.top.toFloat(), it.bottom.toFloat(), gt.top.toFloat(), gt.bottom.toFloat()) }
            matchLines(predLines, gtLines).map { it.third }
        }
        report.append(
            if (lineScores.isEmpty()) {
                " lines 0"
            } else {
                " lines ${lineScores.size} minY ${"%.3f".format(lineScores.min())}"
            },
        )
        if (beats == null) {
            report.append(" beats skipped\n")
        } else {
            val beatCount = matches.sumOf { (pred, _) -> beatsIn(predicted, pred).size }
            report.append(" beats $beatCount/${beats.size}\n")
        }
        if (matches.size != boxes.size && predBlocks.size == boxes.size) {
            failures.add("page $page left ${boxes.size - matches.size} boxes unmatched")
        }
        return failures
    }

    private fun scoreBeats(
        page: Int,
        predicted: Scanned,
        block: DebugBlock,
        box: AnnRect,
        beats: List<AnnRect>,
    ): List<String> {
        val pred = beatsIn(predicted, block).map { it.x }.sorted()
        val gt = beats.filter { beatInBox(it, box) }.map { (it.left + it.right) / 2f }.sorted()
        val spacing = medianGap(gt.ifEmpty { pred })
        val tolerance = max(20f, spacing * 0.30f)
        val (extra, missing) = unmatched(pred, gt, tolerance)
        if (extra.isEmpty() && missing.isEmpty()) return emptyList()
        if (doubledGap(extra, missing, gt, spacing)) {
            return emptyList()
        }
        return listOf(
            "page $page beats in ${rectText(box)} tol ${"%.1f".format(tolerance)} extra ${fmt(extra)} missing ${fmt(missing)} pred ${fmt(pred)} gt ${fmt(gt)}",
        )
    }

    private fun doubledGap(extra: List<Float>, missing: List<Float>, gt: List<Float>, spacing: Float): Boolean {
        if (missing.isNotEmpty() || extra.size != 1 || gt.size < 3 || spacing < 8f) return false
        val x = extra.single()
        val sorted = gt.sorted()
        for (index in 0 until sorted.lastIndex) {
            val gap = sorted[index + 1] - sorted[index]
            if (x > sorted[index] && x < sorted[index + 1] && gap >= spacing * 1.7f) return true
        }
        return false
    }

    private fun unmatched(pred: List<Float>, gt: List<Float>, tolerance: Float): Pair<List<Float>, List<Float>> {
        val used = BooleanArray(gt.size)
        val extra = mutableListOf<Float>()
        for (x in pred) {
            var best = -1
            var bestDistance = Float.MAX_VALUE
            for (index in gt.indices) {
                if (used[index]) continue
                val distance = abs(x - gt[index])
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = index
                }
            }
            if (best >= 0 && bestDistance <= tolerance) used[best] = true else extra.add(x)
        }
        val missing = gt.filterIndexed { index, _ -> !used[index] }
        return extra to missing
    }

    private fun medianGap(xs: List<Float>): Float {
        if (xs.size < 2) return 0f
        val gaps = xs.sorted().zipWithNext { a, b -> b - a }.filter { it > 1f }.sorted()
        if (gaps.isEmpty()) return 0f
        return gaps[gaps.size / 2]
    }

    private fun musicalLines(predicted: Scanned, block: DebugBlock): List<DebugLine> {
        return predicted.lines.filter { line ->
            line.musical && centerInside(line.top, line.bottom, block.top, block.bottom)
        }.sortedBy { it.top }
    }

    private fun beatsIn(predicted: Scanned, block: DebugBlock): List<DebugMeasureLine> {
        return predicted.beats.filter { beat ->
            val center = (beat.top + beat.bottom) / 2f
            center >= block.top - 4f && center <= block.bottom + 4f
        }
    }

    private fun beatInBox(stroke: AnnRect, box: AnnRect): Boolean {
        val overlap = min(stroke.bottom, box.bottom) - max(stroke.top, box.top)
        val span = stroke.bottom - stroke.top
        if (span <= 0 || overlap < span * 0.4f) return false
        val x = (stroke.left + stroke.right) / 2f
        return x >= box.left - 12 && x <= box.right + 12
    }

    private fun matchBoxes(pred: List<DebugBlock>, gt: List<AnnRect>): List<Triple<DebugBlock, AnnRect, Float>> {
        val used = BooleanArray(gt.size)
        val pairs = mutableListOf<Triple<DebugBlock, AnnRect, Float>>()
        for (block in pred.sortedBy { it.top }) {
            var best = -1
            var bestIou = 0f
            for (index in gt.indices) {
                if (used[index]) continue
                val score = iou(block, gt[index])
                if (score > bestIou) {
                    bestIou = score
                    best = index
                }
            }
            if (best < 0 || bestIou <= 0f) continue
            used[best] = true
            pairs.add(Triple(block, gt[best], bestIou))
        }
        return pairs
    }

    private fun matchLines(pred: List<DebugLine>, gt: List<AnnRect>): List<Triple<DebugLine, AnnRect, Float>> {
        val used = BooleanArray(gt.size)
        val pairs = mutableListOf<Triple<DebugLine, AnnRect, Float>>()
        for (line in pred.sortedBy { it.top }) {
            var best = -1
            var bestOverlap = 0f
            for (index in gt.indices) {
                if (used[index]) continue
                val score = verticalOverlap(line.top, line.bottom, gt[index].top.toFloat(), gt[index].bottom.toFloat())
                if (score > bestOverlap) {
                    bestOverlap = score
                    best = index
                }
            }
            if (best < 0 || bestOverlap <= 0f) continue
            used[best] = true
            pairs.add(Triple(line, gt[best], bestOverlap))
        }
        return pairs
    }

    private fun centerInside(top: Float, bottom: Float, boxTop: Float, boxBottom: Float): Boolean {
        val center = (top + bottom) / 2f
        return center >= boxTop - 4f && center <= boxBottom + 4f
    }

    private fun iou(block: DebugBlock, rect: AnnRect): Float {
        return areaIou(
            block.left,
            block.top,
            block.right,
            block.bottom,
            rect.left.toFloat(),
            rect.top.toFloat(),
            rect.right.toFloat(),
            rect.bottom.toFloat(),
        )
    }

    private fun areaIou(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        otherLeft: Float,
        otherTop: Float,
        otherRight: Float,
        otherBottom: Float,
    ): Float {
        val width = max(0f, min(right, otherRight) - max(left, otherLeft))
        val height = max(0f, min(bottom, otherBottom) - max(top, otherTop))
        val inter = width * height
        val area = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        val other = (otherRight - otherLeft).coerceAtLeast(0f) * (otherBottom - otherTop).coerceAtLeast(0f)
        val union = area + other - inter
        if (union <= 0f) return 0f
        return inter / union
    }

    private fun verticalOverlap(top: Float, bottom: Float, otherTop: Float, otherBottom: Float): Float {
        val inter = max(0f, min(bottom, otherBottom) - max(top, otherTop))
        val union = (bottom - top) + (otherBottom - otherTop) - inter
        if (union <= 0f) return 0f
        return inter / union
    }

    private fun loadInk(file: File): MatInk {
        OpenCvImages.ensure()
        val color = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_COLOR)
        check(!color.empty()) { "unreadable ${file.absolutePath}" }
        val gray = Mat()
        return try {
            Imgproc.cvtColor(color, gray, Imgproc.COLOR_BGR2GRAY)
            Core.normalize(gray, gray, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8U)
            val ink = OpenCvImages.adaptiveInk(gray)
            MatInk(gray, ink)
        } catch (error: Throwable) {
            gray.release()
            throw error
        } finally {
            color.release()
        }
    }

    private fun wideRects(file: File): List<AnnRect> {
        return redComponents(file).filter { rect ->
            val width = rect.right - rect.left
            val height = rect.bottom - rect.top
            width >= 80 && height >= 12 && width * height >= 80
        }
    }

    private fun beatStrokes(file: File): List<AnnRect> {
        return redComponents(file).filter { rect ->
            val width = rect.right - rect.left
            val height = rect.bottom - rect.top
            width in 1..24 && height >= 20
        }
    }

    private fun redComponents(file: File): List<AnnRect> {
        OpenCvImages.ensure()
        val color = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_COLOR)
        check(!color.empty()) { "unreadable ${file.absolutePath}" }
        val channels = ArrayList<Mat>(3)
        val rOk = Mat()
        val gOk = Mat()
        val bOk = Mat()
        val diff = Mat()
        val diffOk = Mat()
        val mask = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            Core.split(color, channels)
            val blue = channels[0]
            val green = channels[1]
            val red = channels[2]
            Imgproc.threshold(red, rOk, 170.0, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.threshold(green, gOk, 89.0, 255.0, Imgproc.THRESH_BINARY_INV)
            Imgproc.threshold(blue, bOk, 89.0, 255.0, Imgproc.THRESH_BINARY_INV)
            Core.subtract(red, green, diff)
            Imgproc.threshold(diff, diffOk, 70.0, 255.0, Imgproc.THRESH_BINARY)
            Core.bitwise_and(rOk, gOk, mask)
            Core.bitwise_and(mask, bOk, mask)
            Core.bitwise_and(mask, diffOk, mask)
            val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8, CvType.CV_32S)
            val height = color.rows()
            val rects = mutableListOf<AnnRect>()
            for (index in 1 until count) {
                val x = stats.get(index, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(index, Imgproc.CC_STAT_TOP)[0].toInt()
                val width = stats.get(index, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val componentHeight = stats.get(index, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                if (y >= height - 80 && width < 150) continue
                rects.add(AnnRect(x, y, x + width, y + componentHeight))
            }
            return rects.sortedWith(compareBy({ it.top }, { it.left }))
        } finally {
            color.release()
            channels.forEach { it.release() }
            rOk.release()
            gOk.release()
            bOk.release()
            diff.release()
            diffOk.release()
            mask.release()
            labels.release()
            stats.release()
            centroids.release()
        }
    }

    private fun trainingDir(): File {
        val candidates = listOf(File("training-data"), File("../training-data"))
        return candidates.firstOrNull { File(it, "2.png").isFile }
            ?: error("training-data not found from ${File(".").absolutePath}")
    }

    private fun boxText(block: DebugBlock): String {
        return "(${block.left.toInt()},${block.top.toInt()}-${block.right.toInt()},${block.bottom.toInt()})"
    }

    private fun rectText(rect: AnnRect): String = "(${rect.left},${rect.top}-${rect.right},${rect.bottom})"

    private fun fmt(xs: List<Float>): String = xs.joinToString(",") { it.toInt().toString() }

    private data class AnnRect(val left: Int, val top: Int, val right: Int, val bottom: Int)
}
