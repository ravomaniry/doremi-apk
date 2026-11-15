package mg.maniry.doremii.omr

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
        val done = mutableListOf<OpenRun>()
        var active = emptyList<OpenRun>()
        for (x in 0 until ink.width) {
            val runs = columnRuns(ink, x)
            val next = mutableListOf<OpenRun>()
            val matched = BooleanArray(runs.size)
            val pairs = mutableListOf<RunMatch>()
            for (index in active.indices) {
                val stroke = active[index]
                val strokeHeight = stroke.bottom - stroke.top
                for (runIndex in runs.indices) {
                    val run = runs[runIndex]
                    if (abs(run.top - stroke.top) > 2 || abs(run.bottom - stroke.bottom) > 2) continue
                    val runHeight = run.bottom - run.top
                    val shared = sharedRows(stroke.top, stroke.bottom, run.top, run.bottom)
                    val shorter = min(strokeHeight, runHeight)
                    if (shared > 0 && shared * 20 >= shorter * 17) {
                        pairs.add(RunMatch(index, runIndex, shared))
                    }
                }
            }
            pairs.sortByDescending { it.overlap }
            val usedStroke = BooleanArray(active.size)
            for (pair in pairs) {
                if (usedStroke[pair.stroke] || matched[pair.run]) continue
                usedStroke[pair.stroke] = true
                matched[pair.run] = true
                val stroke = active[pair.stroke]
                val run = runs[pair.run]
                stroke.x1 = x
                if (run.top < stroke.top) stroke.top = max(run.top, stroke.top - 2)
                if (run.bottom > stroke.bottom) stroke.bottom = min(run.bottom, stroke.bottom + 2)
                stroke.shadeSum += run.shadeSum
                stroke.blackCount += run.count
                next.add(stroke)
            }
            for (index in active.indices) {
                if (!usedStroke[index]) done.add(active[index])
            }
            for (runIndex in runs.indices) {
                if (matched[runIndex]) continue
                val run = runs[runIndex]
                next.add(OpenRun(x, x, run.top, run.bottom, run.shadeSum, run.count))
            }
            active = next
        }
        done.addAll(active)
        return done.mapNotNull { it.toStroke() }
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

    private fun columnRuns(ink: InkImage, x: Int): List<ColumnRun> {
        val found = mutableListOf<ColumnRun>()
        var y = 0
        val height = ink.height
        while (y < height) {
            while (y < height && ink.shade(x, y) > black) y++
            if (y >= height) break
            val start = y
            var shadeSum = 0
            var count = 0
            while (y < height && ink.shade(x, y) <= black) {
                shadeSum += ink.shade(x, y)
                count++
                y++
            }
            if (count >= 4) found.add(ColumnRun(start, y, shadeSum, count))
        }
        return found
    }

    private fun sharedRows(top: Int, bottom: Int, otherTop: Int, otherBottom: Int): Int {
        return min(bottom, otherBottom) - max(top, otherTop)
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

private class OpenRun(
    val x0: Int,
    var x1: Int,
    var top: Int,
    var bottom: Int,
    var shadeSum: Int,
    var blackCount: Int,
) {
    fun toStroke(): VerticalStroke? {
        val thickness = x1 - x0 + 1
        val height = bottom - top
        if (height < 4 || height < thickness * 4 || blackCount <= 0) return null
        val blackness = (1f - shadeSum.toFloat() / (blackCount * 255f)).coerceIn(0f, 1f)
        val continuity = (blackCount.toFloat() / (thickness * height)).coerceIn(0f, 1f)
        val slenderness = (height.toFloat() / (thickness * 8f)).coerceAtMost(1f)
        return VerticalStroke((x0 + x1) / 2f, top, bottom, blackness * continuity * slenderness)
    }
}

private data class ColumnRun(val top: Int, val bottom: Int, val shadeSum: Int, val count: Int)

private data class RunMatch(val stroke: Int, val run: Int, val overlap: Int)
