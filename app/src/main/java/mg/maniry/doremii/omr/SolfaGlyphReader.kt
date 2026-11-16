package mg.maniry.doremii.omr

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class SolfaGlyphReader(
    supplied: List<GlyphPattern>? = null,
) {
    private val patterns: List<GlyphPattern> by lazy { supplied ?: SolfaPatterns.android() }

    fun read(ink: InkImage, cell: NoteCell): String {
        return try {
            if (patterns.isEmpty()) "" else decode(ink, cell)
        } catch (_: Exception) {
            ""
        }
    }

    private fun decode(ink: InkImage, cell: NoteCell): String {
        if (ink.width < 2 || ink.height < 2) return ""
        val left = cell.left.roundToInt().coerceIn(0, ink.width - 1)
        val top = cell.top.roundToInt().coerceIn(0, ink.height - 1)
        val right = cell.right.roundToInt().coerceIn(left + 1, ink.width)
        val bottom = cell.bottom.roundToInt().coerceIn(top + 1, ink.height)
        val width = right - left
        val height = bottom - top
        if (width < 2 || height < 2) return ""
        val bytes = ByteArray(width * height)
        var index = 0
        for (y in top until bottom) {
            for (x in left until right) {
                bytes[index++] = if (ink.isDark(x, y)) 255.toByte() else 0
            }
        }
        clearEdgeBars(bytes, width, height)
        val blobs = components(bytes, width, height).toMutableList()
        if (blobs.isEmpty()) return ""
        if (blobs.size == 1 && isRest(blobs[0], height)) return "-"
        val letterHeight = letterHeight(blobs)
        val parts = splitWide(blobs, letterHeight).toMutableList()
        mergeDots(parts, letterHeight)
        val hits = ArrayList<LetterHit>()
        val marks = ArrayList<Blob>()
        for (blob in parts) {
            if (blob.height >= letterHeight * 0.36f) {
                val letter = classify(blob) ?: continue
                hits += LetterHit(letter.toString(), blob.minX, blob.maxX, blob.minY, blob.maxY)
            } else if (blob.area >= 6 && blob.height >= 2 && blob.width >= 2) {
                marks += blob
            }
        }
        if (hits.isEmpty()) return ""
        val syllables = glue(hits, letterHeight)
        if (syllables.isEmpty()) return ""
        attach(marks, syllables, letterHeight)
        return finish(render(syllables))
    }

    private fun finish(raw: String): String {
        if (raw.isEmpty() || raw == "-") return raw
        return if (SolfaCellMapper.fromOcrText(raw).isNotEmpty()) raw else ""
    }

    private fun classify(blob: Blob): Char? {
        blob.forced?.let { return it }
        val shape = SolfaPatterns.measure(blob.raster()) ?: return null
        var bestLetter = 0.toChar()
        var best = Float.POSITIVE_INFINITY
        var second = Float.POSITIVE_INFINITY
        val byLetter = HashMap<Char, Float>()
        for (pattern in patterns) {
            val score = score(shape, pattern.shape)
            val previous = byLetter[pattern.letter]
            if (previous == null || score < previous) byLetter[pattern.letter] = score
        }
        for ((letter, score) in byLetter) {
            if (score < best) {
                second = best
                best = score
                bestLetter = letter
            } else if (score < second) {
                second = score
            }
        }
        if (bestLetter == 0.toChar() || best > matchCap) return null
        val margin = if (best < 0.02f) 0.012f else matchMargin
        if (second - best < margin) return null
        return bestLetter
    }

    private fun score(seen: GlyphShape, known: GlyphShape): Float {
        return hamming(seen.mask, known.mask) * 0.6f +
            zones(seen.mask, known.mask) * 0.4f +
            abs(seen.holes - known.holes) * 0.06f +
            abs(seen.aspect - known.aspect).coerceAtMost(2f) * 0.16f +
            abs(seen.rightStem - known.rightStem) * 0.32f
    }

    private fun hamming(left: BooleanArray, right: BooleanArray): Float {
        val count = min(left.size, right.size)
        if (count == 0) return 1f
        var diff = abs(left.size - right.size)
        for (index in 0 until count) if (left[index] != right[index]) diff++
        return diff.toFloat() / SolfaPatterns.MASK_WIDTH / SolfaPatterns.MASK_HEIGHT
    }

    private fun zones(left: BooleanArray, right: BooleanArray): Float {
        val zoneW = SolfaPatterns.MASK_WIDTH / 4
        val zoneH = SolfaPatterns.MASK_HEIGHT / 8
        val zoneArea = (zoneW * zoneH).coerceAtLeast(1)
        var sum = 0f
        for (zoneY in 0 until 8) {
            for (zoneX in 0 until 4) {
                var leftInk = 0
                var rightInk = 0
                for (y in 0 until zoneH) {
                    var index = (zoneY * zoneH + y) * SolfaPatterns.MASK_WIDTH + zoneX * zoneW
                    for (x in 0 until zoneW) {
                        if (index in left.indices && left[index]) leftInk++
                        if (index in right.indices && right[index]) rightInk++
                        index++
                    }
                }
                sum += abs(leftInk - rightInk).toFloat() / zoneArea
            }
        }
        return sum / 32f
    }

    private fun glue(letters: List<LetterHit>, letterHeight: Int): List<Syllable> {
        val sorted = letters.sortedBy { it.left }
        val syllables = ArrayList<Syllable>()
        var index = 0
        val limit = max(6, (letterHeight * 0.6f).toInt())
        while (index < sorted.size) {
            val current = sorted[index]
            val next = sorted.getOrNull(index + 1)
            if (next != null && current.text.length == 1 && next.text.length == 1) {
                val gap = next.left - current.right
                val pair = SolfaCellMapper.extends(current.text[0], next.text[0])
                if (pair && gap <= limit && sharesBaseline(current, next)) {
                    syllables += Syllable(
                        current.text + next.text,
                        min(current.left, next.left),
                        max(current.right, next.right),
                        min(current.top, next.top),
                        max(current.bottom, next.bottom),
                    )
                    index += 2
                    continue
                }
            }
            syllables += Syllable(current.text, current.left, current.right, current.top, current.bottom)
            index++
        }
        return syllables
    }

    private fun sharesBaseline(left: LetterHit, right: LetterHit): Boolean {
        val top = max(left.top, right.top)
        val bottom = min(left.bottom, right.bottom)
        val overlap = bottom - top + 1
        val shorter = min(left.bottom - left.top, right.bottom - right.top) + 1
        return shorter > 0 && overlap >= shorter * 0.5f
    }

    private fun attach(marks: List<Blob>, syllables: List<Syllable>, letterHeight: Int) {
        val dividers = HashMap<Int, Char>()
        for (mark in marks) {
            val centerX = (mark.minX + mark.maxX) / 2f
            val centerY = (mark.minY + mark.maxY) / 2f
            val gap = gapIndex(syllables, centerX)
            if (gap >= 0) {
                val left = syllables[gap]
                val right = syllables[gap + 1]
                val top = min(left.top, right.top)
                val span = (max(left.bottom, right.bottom) - top).coerceAtLeast(1)
                val relative = (centerY - top) / span
                when {
                    relative < 0.40f -> left.quotes++
                    relative > 0.58f -> dividers[gap] = ','
                    else -> if (dividers[gap] == null) dividers[gap] = '.'
                }
                continue
            }
            val syllable = nearest(syllables, centerX) ?: continue
            val outside = when {
                centerX < syllable.left -> syllable.left - centerX
                centerX > syllable.right -> centerX - syllable.right
                else -> 0f
            }
            if (outside > letterHeight * 0.8f) continue
            val relative = (centerY - syllable.top) / syllable.height
            val onRight = centerX >= syllable.left + syllable.width * 0.42f
            when {
                relative < 0.42f && onRight -> syllable.quotes++
                relative > 0.58f -> syllable.trailingComma = true
            }
        }
        for (index in 0 until syllables.lastIndex) {
            if (syllables[index].trailingComma && dividers[index] == null) dividers[index] = ','
            syllables[index].trailingComma = false
        }
        syllables.forEachIndexed { index, syllable -> syllable.divider = dividers[index] }
    }

    private fun gapIndex(syllables: List<Syllable>, centerX: Float): Int {
        for (index in 0 until syllables.lastIndex) {
            val left = syllables[index]
            val right = syllables[index + 1]
            val leftMid = (left.left + left.right) / 2f
            val rightMid = (right.left + right.right) / 2f
            if (centerX <= leftMid || centerX >= rightMid) continue
            val gapMid = (left.right + right.left) / 2f
            val fromGap = abs(centerX - gapMid)
            if (fromGap <= abs(centerX - leftMid) && fromGap <= abs(centerX - rightMid)) return index
        }
        return -1
    }

    private fun nearest(syllables: List<Syllable>, centerX: Float): Syllable? {
        return syllables.minByOrNull { syllable ->
            when {
                centerX < syllable.left -> syllable.left - centerX
                centerX > syllable.right -> centerX - syllable.right
                else -> 0f
            }
        }
    }

    private fun render(syllables: List<Syllable>): String {
        return buildString {
            syllables.forEachIndexed { index, syllable ->
                if (index > 0) append(syllables[index - 1].divider ?: '.')
                append(syllable.text)
                repeat(syllable.quotes.coerceIn(0, 3)) { append('\'') }
                if (syllable.trailingComma) append(',')
            }
        }
    }

    private fun letterHeight(blobs: List<Blob>): Int {
        val tallest = blobs.maxOf { it.height }
        val tall = blobs.map { it.height }.filter { it >= tallest * 0.62f }.sorted()
        if (tall.isEmpty()) return tallest.coerceAtLeast(1)
        return tall[tall.size / 2].coerceAtLeast(1)
    }

    private fun isRest(blob: Blob, cellHeight: Int): Boolean {
        if (blob.height > cellHeight * 0.45f) return false
        if (blob.width < blob.height * 2.4f) return false
        return blob.height <= blob.width * 0.42f
    }

    private fun splitWide(blobs: List<Blob>, letterHeight: Int): List<Blob> {
        return blobs.flatMap { splitDeep(it, letterHeight, 0) }
    }

    private fun splitDeep(blob: Blob, letterHeight: Int, depth: Int): List<Blob> {
        if (depth > 4) return listOf(blob)
        val parts = splitOnce(blob, letterHeight)
        if (parts.size <= 1) return listOf(blob)
        return parts.flatMap { splitDeep(it, letterHeight, depth + 1) }
    }

    private fun splitOnce(blob: Blob, letterHeight: Int): List<Blob> {
        if (blob.forced != null) return listOf(blob)
        if (blob.height < letterHeight * 0.5f) return listOf(blob)
        if (blob.width < letterHeight * 1.25f) return listOf(blob)
        val columns = IntArray(blob.width)
        for (index in blob.xs.indices) {
            val x = blob.xs[index] - blob.minX
            if (x in columns.indices) columns[x]++
        }
        val from = (blob.width * 0.12f).toInt().coerceAtLeast(1)
        val to = (blob.width * 0.88f).toInt()
        val cuts = ArrayList<Int>()
        var x = from
        while (x < to) {
            if (columns[x] <= 1) {
                val gap = x
                while (x < to && columns[x] <= 1) x++
                cuts += blob.minX + (gap + x) / 2
            } else {
                x++
            }
        }
        if (cuts.isEmpty()) return listOf(blob)
        val edges = ArrayList<Int>(cuts.size + 2)
        edges += blob.minX
        edges += cuts
        edges += blob.maxX + 1
        val parts = ArrayList<Blob>()
        for (index in 0 until edges.lastIndex) {
            val part = blob.slice(edges[index], edges[index + 1] - 1) ?: continue
            parts += part
        }
        if (parts.size < 2) return listOf(blob)
        if (parts.any { it.area < blob.area * 0.12f }) return listOf(blob)
        return parts
    }

    private fun mergeDots(blobs: MutableList<Blob>, letterHeight: Int) {
        val consumed = HashSet<Blob>()
        for (stem in blobs) {
            if (!stem.isStem(letterHeight) || stem in consumed) continue
            val dot = blobs
                .asSequence()
                .filter { it !== stem && it !in consumed && it.looksLikeDot(stem, letterHeight) }
                .minByOrNull { stem.minY - it.maxY }
                ?: continue
            stem.absorb(dot)
            stem.forced = 'i'
            consumed += dot
        }
        if (consumed.isNotEmpty()) blobs.removeAll(consumed)
    }

    private fun components(bytes: ByteArray, width: Int, height: Int): List<Blob> {
        OpenCvImages.ensure()
        val mat = Mat(height, width, CvType.CV_8UC1)
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            if (bytes.isNotEmpty()) mat.put(0, 0, bytes)
            val count = Imgproc.connectedComponentsWithStats(
                mat,
                labels,
                stats,
                centroids,
                8,
                CvType.CV_32S,
            )
            if (count <= 1) return emptyList()
            val ids = IntArray(width * height)
            labels.get(0, 0, ids)
            val groups = Array(count) { ArrayList<Int>() }
            for (index in ids.indices) {
                val label = ids[index]
                if (label in 1 until count) groups[label].add(index)
            }
            val found = ArrayList<Blob>()
            for (label in 1 until count) {
                val pixels = groups[label]
                if (pixels.size < 4) continue
                val blob = Blob.fromIndices(pixels, width)
                if (isEdgeBar(blob, width, height)) continue
                found += blob
            }
            return found
        } finally {
            centroids.release()
            stats.release()
            labels.release()
            mat.release()
        }
    }

    private fun isEdgeBar(blob: Blob, cellWidth: Int, cellHeight: Int): Boolean {
        val thin = blob.width <= max(3, cellWidth / 25)
        val tall = blob.height >= (cellHeight * 0.72f).toInt()
        val atEdge = blob.minX <= 3 || blob.maxX >= cellWidth - 4
        return thin && tall && atEdge
    }

    private fun clearEdgeBars(bytes: ByteArray, width: Int, height: Int) {
        if (height < 8 || width < 6) return
        val tall = (height * 0.75f).toInt()
        val stroke = max(2, width / 28)
        clearSide(bytes, width, height, tall, stroke, left = true)
        clearSide(bytes, width, height, tall, stroke, left = false)
    }

    private fun clearSide(
        bytes: ByteArray,
        width: Int,
        height: Int,
        tall: Int,
        maxStroke: Int,
        left: Boolean,
    ) {
        val step = if (left) 1 else -1
        var x = if (left) 0 else width - 1
        var skipped = 0
        while (skipped <= 3 && x in 0 until width && columnInk(bytes, width, height, x) == 0) {
            x += step
            skipped++
        }
        if (x !in 0 until width) return
        var cursor = x
        var stroke = 0
        while (stroke < maxStroke && cursor in 0 until width && columnInk(bytes, width, height, cursor) >= tall) {
            cursor += step
            stroke++
        }
        if (stroke == 0) return
        if (stroke == maxStroke && cursor in 0 until width && columnInk(bytes, width, height, cursor) >= tall) return
        var wipe = x
        repeat(stroke) {
            if (wipe !in 0 until width) return@repeat
            var index = wipe
            for (y in 0 until height) {
                bytes[index] = 0
                index += width
            }
            wipe += step
        }
    }

    private fun columnInk(bytes: ByteArray, width: Int, height: Int, x: Int): Int {
        var count = 0
        var index = x
        for (y in 0 until height) {
            if (bytes[index].toInt() != 0) count++
            index += width
        }
        return count
    }

    private class LetterHit(
        val text: String,
        val left: Int,
        val right: Int,
        val top: Int,
        val bottom: Int,
    )

    private class Syllable(
        val text: String,
        val left: Int,
        val right: Int,
        val top: Int,
        val bottom: Int,
    ) {
        var quotes: Int = 0
        var trailingComma: Boolean = false
        var divider: Char? = null
        val width: Int get() = right - left + 1
        val height: Int get() = (bottom - top + 1).coerceAtLeast(1)
    }

    private class Blob(
        var minX: Int,
        var minY: Int,
        var maxX: Int,
        var maxY: Int,
        val xs: MutableList<Int>,
        val ys: MutableList<Int>,
        var forced: Char?,
    ) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
        val area: Int get() = xs.size

        fun raster(): GlyphRaster {
            val dark = BooleanArray(width * height)
            for (index in xs.indices) {
                val x = xs[index] - minX
                val y = ys[index] - minY
                if (x in 0 until width && y in 0 until height) dark[y * width + x] = true
            }
            return GlyphRaster(width, height, dark)
        }

        fun absorb(other: Blob) {
            xs.addAll(other.xs)
            ys.addAll(other.ys)
            minX = min(minX, other.minX)
            minY = min(minY, other.minY)
            maxX = max(maxX, other.maxX)
            maxY = max(maxY, other.maxY)
        }

        fun slice(fromX: Int, toX: Int): Blob? {
            val nextX = ArrayList<Int>()
            val nextY = ArrayList<Int>()
            for (index in xs.indices) {
                val x = xs[index]
                if (x in fromX..toX) {
                    nextX += x
                    nextY += ys[index]
                }
            }
            if (nextX.size < 4) return null
            return fromPoints(nextX, nextY)
        }

        fun isStem(letterHeight: Int): Boolean {
            if (forced != null) return false
            if (height < letterHeight * 0.45f) return false
            if (width > height * 0.62f) return false
            return width <= letterHeight * 0.42f
        }

        fun looksLikeDot(stem: Blob, letterHeight: Int): Boolean {
            if (height > letterHeight * 0.42f || width > letterHeight * 0.5f) return false
            if (area > stem.area) return false
            val gap = stem.minY - maxY
            val allowance = max(3, (letterHeight * 0.4f).toInt())
            if (gap !in -2..allowance) return false
            val center = (minX + maxX) / 2
            val stemCenter = (stem.minX + stem.maxX) / 2
            val tolerance = max(4, stem.width / 2 + 3)
            return abs(center - stemCenter) <= tolerance
        }

        companion object {
            fun fromIndices(indices: List<Int>, width: Int): Blob {
                val xs = ArrayList<Int>(indices.size)
                val ys = ArrayList<Int>(indices.size)
                for (index in indices) {
                    xs += index % width
                    ys += index / width
                }
                return fromPoints(xs, ys)
            }

            fun fromPoints(xs: List<Int>, ys: List<Int>): Blob {
                var minX = xs[0]
                var maxX = xs[0]
                var minY = ys[0]
                var maxY = ys[0]
                for (index in xs.indices) {
                    val x = xs[index]
                    val y = ys[index]
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                return Blob(minX, minY, maxX, maxY, xs.toMutableList(), ys.toMutableList(), null)
            }
        }
    }

    private companion object {
        const val matchCap = 0.5f
        const val matchMargin = 0.03f
    }
}
