package mg.maniry.doremii.omr

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class VerticalStroke(
    val x: Float,
    val top: Int,
    val bottom: Int,
    val score: Float,
) {
    val height: Int get() = bottom - top
}

data class ClassifiedBars(
    val borders: List<VerticalStroke>,
    val delimiters: List<VerticalStroke>,
)

object BarlineDetector {
    private const val black = 40
    private const val scoreFloor = 0.55f
    private const val beatWindow = 0.35f

    fun strokes(ink: InkImage): List<VerticalStroke> {
        if (ink.width < 2 || ink.height < 4) return emptyList()
        val mask = OpenCvImages.shadeMask(ink, black)
        val gray = OpenCvImages.grayOf(ink)
        try {
            return components(mask.mat, gray.mat)
        } finally {
            mask.close()
            gray.close()
        }
    }

    fun detect(ink: InkImage, top: Int, bottom: Int): List<Float> {
        val y0 = top.coerceIn(0, ink.height)
        val y1 = bottom.coerceIn(0, ink.height)
        val span = y1 - y0
        if (span < 8 || ink.width < 2) return emptyList()
        val minRun = (span * 0.62f).toInt().coerceAtLeast(6)
        return strokes(ink).mapNotNull { stroke ->
            if (stroke.score < scoreFloor) return@mapNotNull null
            val overlap = min(stroke.bottom, y1) - max(stroke.top, y0)
            if (overlap >= minRun) stroke.x else null
        }
    }

    fun classify(
        row: List<VerticalStroke>,
        signature: Int,
        markerXs: List<Float> = emptyList(),
    ): ClassifiedBars {
        val sig = signature.coerceIn(2, 6)
        val kept = collapse(row.filter { it.score >= scoreFloor }.sortedBy { it.x })
        if (kept.size < 2) return ClassifiedBars(kept, emptyList())
        val gaps = kept.zipWithNext { a, b -> b.x - a.x }.filter { it >= 1f }
        val beat = if (gaps.isEmpty()) 1f else gaps.sorted()[gaps.size / 2]
        val counts = gapMarkerCounts(kept, markerXs)
        val medianMarkers = if (counts.isEmpty()) 0 else counts.sorted()[counts.size / 2]
        return if (medianMarkers >= 1 || kept.size <= sig) {
            measureBars(kept, markerXs, beat, sig)
        } else {
            beatBars(kept, beat, sig)
        }
    }

    private fun components(mask: Mat, gray: Mat): List<VerticalStroke> {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 4, CvType.CV_32S)
            val found = mutableListOf<VerticalStroke>()
            for (index in 1 until count) {
                val x = stats.get(index, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(index, Imgproc.CC_STAT_TOP)[0].toInt()
                val width = stats.get(index, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val height = stats.get(index, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val area = stats.get(index, Imgproc.CC_STAT_AREA)[0].toInt()
                val stroke = toStroke(gray, labels, index, x, y, width, height, area) ?: continue
                found.add(stroke)
            }
            return found
        } finally {
            labels.release()
            stats.release()
            centroids.release()
        }
    }

    private fun toStroke(
        gray: Mat,
        labels: Mat,
        index: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        area: Int,
    ): VerticalStroke? {
        if (height < 4 || width <= 0 || area <= 0 || height < width * 4) return null
        val grayRoi = gray.submat(y, y + height, x, x + width)
        val labelRoi = labels.submat(y, y + height, x, x + width)
        val component = Mat()
        try {
            Core.compare(labelRoi, Scalar(index.toDouble()), component, Core.CMP_EQ)
            val average = Core.mean(grayRoi, component).`val`[0]
            val blackness = (1f - average.toFloat() / 255f).coerceIn(0f, 1f)
            val continuity = (area.toFloat() / (width * height)).coerceIn(0f, 1f)
            val slenderness = (height.toFloat() / (width * 8f)).coerceAtMost(1f)
            return VerticalStroke(
                x + (width - 1) / 2f,
                y,
                y + height,
                blackness * continuity * slenderness,
            )
        } finally {
            grayRoi.release()
            labelRoi.release()
            component.release()
        }
    }

    private fun collapse(sorted: List<VerticalStroke>): List<VerticalStroke> {
        if (sorted.isEmpty()) return sorted
        val out = mutableListOf(sorted.first())
        for (stroke in sorted.drop(1)) {
            val last = out.last()
            if (stroke.x - last.x <= 2f) {
                if (stroke.score > last.score) out[out.lastIndex] = stroke
            } else {
                out.add(stroke)
            }
        }
        return out
    }

    private fun gapMarkerCounts(strokes: List<VerticalStroke>, markerXs: List<Float>): List<Int> {
        return strokes.zipWithNext { start, end ->
            markerXs.count { it > start.x + 1.5f && it < end.x - 1.5f }
        }
    }

    private fun measureBars(
        kept: List<VerticalStroke>,
        markerXs: List<Float>,
        beat: Float,
        signature: Int,
    ): ClassifiedBars {
        val borders = kept.toMutableList()
        val delimiters = mutableListOf<VerticalStroke>()
        dropWeakBorders(borders, delimiters, beat, signature) { left, right ->
            val marks = markerXs.count { it > left + 1.5f && it < right - 1.5f }
            if (marks > 0) marks + 1 else null
        }
        return ClassifiedBars(borders, trim(borders, delimiters, signature))
    }

    private fun beatBars(kept: List<VerticalStroke>, beat: Float, signature: Int): ClassifiedBars {
        val origin = kept.first().x
        val borders = mutableListOf(kept.first())
        val delimiters = mutableListOf<VerticalStroke>()
        for (stroke in kept.drop(1).dropLast(1)) {
            val beats = ((stroke.x - origin) / beat).roundToInt()
            val delta = abs(stroke.x - origin - beats * beat)
            if (delta > beat * beatWindow) continue
            if (beats > 0 && beats % signature == 0) borders.add(stroke) else delimiters.add(stroke)
        }
        borders.add(kept.last())
        dropWeakBorders(borders, delimiters, beat, signature) { left, right ->
            ((right - left) / beat).roundToInt().coerceAtLeast(1)
        }
        return ClassifiedBars(borders, trim(borders, delimiters, signature))
    }

    private fun dropWeakBorders(
        borders: MutableList<VerticalStroke>,
        delimiters: MutableList<VerticalStroke>,
        beat: Float,
        signature: Int,
        beatsBetween: (Float, Float) -> Int?,
    ) {
        val outer = min(borders.first().score, borders.last().score)
        var guard = borders.size
        while (guard-- > 0 && borders.size > 2) {
            val counts = (0 until borders.lastIndex).map { index ->
                beatsBetween(borders[index].x, borders[index + 1].x)
            }
            val broken = counts.any { it != null && it != signature }
            val unknown = counts.any { it == null }
            val dropAt = (1 until borders.lastIndex)
                .filter { index ->
                    val score = borders[index].score
                    val before = counts.getOrNull(index - 1)
                    val after = counts.getOrNull(index)
                    val knownSplit = (before != null && before != signature) ||
                        (after != null && after != signature)
                    when {
                        broken && knownSplit && score + 0.12f < outer -> true
                        unknown && !broken && score + 0.3f < outer -> true
                        else -> false
                    }
                }
                .minByOrNull { borders[it].score }
                ?: break
            val dropped = borders.removeAt(dropAt)
            if (onBeatGrid(dropped, borders.first(), beat)) delimiters.add(dropped)
        }
    }

    private fun onBeatGrid(stroke: VerticalStroke, origin: VerticalStroke, beat: Float): Boolean {
        if (beat < 1f) return false
        val beats = ((stroke.x - origin.x) / beat).roundToInt()
        return abs(stroke.x - origin.x - beats * beat) <= beat * beatWindow
    }

    private fun trim(
        borders: List<VerticalStroke>,
        delimiters: List<VerticalStroke>,
        signature: Int,
    ): List<VerticalStroke> {
        if (borders.size < 2 || delimiters.isEmpty()) return delimiters.sortedBy { it.x }
        val chosen = mutableListOf<VerticalStroke>()
        for (index in 0 until borders.lastIndex) {
            val left = borders[index].x
            val right = borders[index + 1].x
            val width = right - left
            if (width < 1f) continue
            val inside = delimiters.filter { it.x > left + 1.5f && it.x < right - 1.5f }
            val used = mutableSetOf<Int>()
            val slot = width / signature
            for (beat in 1 until signature) {
                val ideal = left + width * beat / signature
                val match = inside.indices
                    .filter { it !in used }
                    .minByOrNull { abs(inside[it].x - ideal) }
                    ?: continue
                if (abs(inside[match].x - ideal) <= slot * 0.5f) {
                    used.add(match)
                    chosen.add(inside[match])
                }
            }
        }
        return chosen.sortedBy { it.x }
    }
}
