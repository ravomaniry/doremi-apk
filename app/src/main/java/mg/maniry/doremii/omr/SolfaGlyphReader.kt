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
        clearDividerEdges(bytes, width, height)
        val blobs = components(bytes, width, height).toMutableList()
        if (blobs.isEmpty()) return ""
        val letterHeight = letterHeight(blobs, width, height)
        if (hasRest(blobs, width, height, letterHeight)) return "-"
        val parts = splitWide(blobs, letterHeight)
            .flatMap { blob ->
                val peeled = detachLowMarks(blob)
                val comma = if (peeled.size > 1) peeled else detachTrailingComma(blob)
                if (comma.size > 1) comma else detachBaselineHook(blob)
            }
            .toMutableList()
        mergeDots(parts, letterHeight)
        val hits = ArrayList<LetterHit>()
        val marks = ArrayList<Blob>()
        for (blob in parts) {
            if (isIgnored(blob, parts, width, height)) continue
            if (isRest(blob, width, height, letterHeight)) {
                hits += LetterHit("-", blob.minX, blob.maxX, blob.minY, blob.maxY)
                continue
            }
            if (blob.comma) {
                if (blob.area >= 6) marks += blob
                continue
            }
            if (isLetterSized(blob, letterHeight)) {
                val letter = classify(blob, letterHeight)
                val cut = if (letter == null || letter == 'a') joinedFiCut(blob) else null
                if (letter == null || cut != null) {
                    val left = if (cut == null) null else blob.slice(blob.minX, cut - 1)
                    val right = if (cut == null) null else blob.slice(cut, blob.maxX)
                    if (left != null && right != null) {
                        val peeled = detachTrailingComma(right)
                        val body = peeled.first()
                        hits += LetterHit("f", left.minX, left.maxX, left.minY, left.maxY)
                        hits += LetterHit("i", body.minX, body.maxX, body.minY, body.maxY)
                        for (mark in peeled.drop(1)) if (mark.comma && mark.area >= 6) marks += mark
                        continue
                    }
                    if (letter != null) {
                        hits += LetterHit(letter.toString(), blob.minX, blob.maxX, blob.minY, blob.maxY)
                        continue
                    }
                    if (isMarkSized(blob, letterHeight)) marks += blob
                    continue
                }
                hits += LetterHit(letter.toString(), blob.minX, blob.maxX, blob.minY, blob.maxY)
            } else if (blob.area >= 6 && blob.height >= 2 && blob.width >= 2) {
                marks += blob
            }
        }
        if (hits.isEmpty()) return ""
        val syllables = glue(hits, letterHeight)
        if (syllables.isEmpty()) return ""
        attach(marks, syllables, letterHeight, width)
        return finish(render(syllables))
    }

    private fun finish(raw: String): String {
        if (raw.isEmpty() || raw == "-") return raw
        return if (SolfaCellMapper.fromOcrText(raw).isNotEmpty()) raw else ""
    }

    private fun classify(blob: Blob, letterHeight: Int): Char? {
        blob.forced?.let { return it }
        val shape = SolfaPatterns.measure(blob.raster()) ?: return null
        var bestLetter = 0.toChar()
        var secondLetter = 0.toChar()
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
                secondLetter = bestLetter
                best = score
                bestLetter = letter
            } else if (score < second) {
                second = score
                secondLetter = letter
            }
        }
        if (bestLetter == 0.toChar() || best > matchCap) return null
        val feature = featureLetter(bestLetter, blob, letterHeight)
        val margin = if (best < 0.02f) 0.012f else matchMargin
        if (second - best < margin) {
            if (feature != null) return feature
            if ((bestLetter == 'l' && secondLetter == 'i') || (bestLetter == 'i' && secondLetter == 'l')) {
                return 'l'
            }
            if (best <= 0.34f) return bestLetter
            return null
        }
        return feature ?: bestLetter
    }

    private fun featureLetter(best: Char, blob: Blob, letterHeight: Int): Char? {
        val stem = stemColumn(blob)
        val upperRight = upperRightRows(blob, stem)
        val cross = hasCrossbar(blob, stem)
        val narrow = blob.width * 10 < blob.height * 8
        val ascender = blob.height * 10 >= blob.width * 16 && blob.height >= letterHeight * 0.9f
        if (cross && upperRight <= 3 && narrow && best in "ri") return 't'
        if (ascender && upperRight <= 1 && !cross && best in "tr") return 'l'
        return null
    }

    private fun stemColumn(blob: Blob): Int {
        val counts = IntArray(blob.width)
        for (index in blob.xs.indices) {
            val x = blob.xs[index] - blob.minX
            if (x in counts.indices) counts[x]++
        }
        var best = 0
        for (x in counts.indices) if (counts[x] > counts[best]) best = x
        return best
    }

    private fun upperRightRows(blob: Blob, stem: Int): Int {
        val limit = blob.minY + (blob.height * 0.42f).toInt()
        val rows = HashSet<Int>()
        for (index in blob.xs.indices) {
            val x = blob.xs[index] - blob.minX
            val y = blob.ys[index]
            if (y <= limit && x > stem + 2) rows += y
        }
        return rows.size
    }

    private fun hasCrossbar(blob: Blob, stem: Int): Boolean {
        val limit = blob.minY + (blob.height * 0.45f).toInt()
        val leftAt = HashMap<Int, Int>()
        val rightAt = HashMap<Int, Int>()
        for (index in blob.xs.indices) {
            val y = blob.ys[index]
            if (y > limit) continue
            val x = blob.xs[index] - blob.minX
            val left = leftAt[y]
            val right = rightAt[y]
            if (left == null || x < left) leftAt[y] = x
            if (right == null || x > right) rightAt[y] = x
        }
        var wide = 0
        for (y in leftAt.keys) {
            val left = leftAt[y] ?: continue
            val right = rightAt[y] ?: continue
            val span = right - left + 1
            if (span >= blob.width * 0.55f && right > stem + blob.width * 0.22f) wide++
        }
        return wide in 1..4
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

    private fun attach(marks: List<Blob>, syllables: List<Syllable>, letterHeight: Int, cellWidth: Int) {
        val gaps = HashMap<Int, GapMarks>()
        for (mark in marks) {
            val centerX = (mark.minX + mark.maxX) / 2f
            val centerY = (mark.minY + mark.maxY) / 2f
            val gap = gapIndex(syllables, centerX)
            if (gap >= 0) {
                val left = syllables[gap]
                val right = syllables[gap + 1]
                val top = min(left.top, right.top).toFloat()
                val baseline = max(left.bottom, right.bottom).toFloat()
                val span = (baseline - top).coerceAtLeast(1f)
                val bucket = gaps.getOrPut(gap) { GapMarks() }
                when {
                    mark.comma || isComma(mark, baseline) -> bucket.noteLow(centerX)
                    centerY < top + span * 0.40f && mark.height <= span * 0.45f -> left.quotes++
                    else -> bucket.noteDot(centerX)
                }
                continue
            }
            val syllable = nearest(syllables, centerX) ?: continue
            val outside = when {
                centerX < syllable.left -> syllable.left - centerX
                centerX > syllable.right -> centerX - syllable.right
                else -> 0f
            }
            if (outside > letterHeight * 2.4f) continue
            val onRight = centerX >= syllable.left + syllable.width * 0.42f
            when {
                mark.comma || isComma(mark, syllable.bottom.toFloat()) -> syllable.trailingComma = true
                centerY < syllable.top + syllable.height * 0.42f && onRight -> syllable.quotes++
                centerX < syllable.left && mark.minX > 4 -> syllable.lead = "."
                centerX > syllable.right && mark.maxX < cellWidth - 5 -> syllable.trail = "."
            }
        }
        for ((index, bucket) in gaps) {
            val syllable = syllables[index]
            val dotX = bucket.dotX
            val leftLow = bucket.lows.any { dotX == null || it < dotX }
            val rightLow = dotX != null && bucket.lows.any { it >= dotX }
            when {
                dotX != null && leftLow && rightLow -> {
                    syllable.trailingComma = true
                    syllable.divider = ".,"
                }
                dotX != null && rightLow -> syllable.divider = ".,"
                dotX != null && leftLow -> {
                    syllable.trailingComma = true
                    syllable.divider = "."
                }
                dotX != null -> syllable.divider = "."
                leftLow -> syllable.trailingComma = true
            }
        }
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
                if (index > 0) append(syllables[index - 1].divider.orEmpty())
                append(syllable.lead)
                append(syllable.text)
                repeat(syllable.quotes.coerceIn(0, 3)) { append('\'') }
                if (syllable.trailingComma) append(',')
                append(syllable.trail)
            }
        }
    }

    private fun letterHeight(blobs: List<Blob>, cellWidth: Int, cellHeight: Int): Int {
        val bodies = blobs.filter { blob ->
            !isUnderline(blob, cellWidth, cellHeight) &&
                !isEdgeSpeck(blob, cellWidth) &&
                !isColonSpeck(blob, blobs, cellWidth)
        }
        val pool = if (bodies.isEmpty()) blobs else bodies
        val tallest = pool.maxOf { it.height }
        val tall = pool.map { it.height }.filter { it >= tallest * 0.62f }.sorted()
        if (tall.isEmpty()) return tallest.coerceAtLeast(1)
        return tall[tall.size / 2].coerceAtLeast(1)
    }

    private fun isComma(mark: Blob, baseline: Float): Boolean {
        if (mark.height < 6 || mark.height < mark.width * 1.25f) return false
        return mark.maxY >= baseline + 2f
    }

    private fun isRest(blob: Blob, cellWidth: Int, cellHeight: Int, letterHeight: Int): Boolean {
        if (blob.width < 6 || blob.height > 10 || isUnderline(blob, cellWidth, cellHeight)) return false
        if (blob.width < blob.height * 1.7f || blob.height > cellHeight * 0.35f) return false
        val center = (blob.minY + blob.maxY) / 2f
        return center < cellHeight * 0.82f && letterHeight > 0
    }

    private fun splitWide(blobs: List<Blob>, letterHeight: Int): List<Blob> {
        return blobs.flatMap { blob ->
            dropRuledLines(blob).flatMap { splitDeep(it, letterHeight, 0) }
        }
    }

    private fun dropRuledLines(blob: Blob): List<Blob> {
        if (blob.width < 24 || blob.height < 12) return listOf(blob)
        val rows = IntArray(blob.height)
        for (y in blob.ys) {
            val row = y - blob.minY
            if (row in rows.indices) rows[row]++
        }
        val drop = BooleanArray(blob.height)
        val wide = (blob.width * 0.75f).toInt()
        val sparse = (blob.width * 0.3f).toInt()
        var top = 0
        while (top < blob.height / 2 && rows[top] >= wide) {
            drop[top] = true
            top++
        }
        if (top in rows.indices && rows[top] > sparse) {
            for (row in 0 until top) drop[row] = false
        }
        var bottom = rows.lastIndex
        while (bottom > blob.height / 2 && rows[bottom] >= wide) {
            drop[bottom] = true
            bottom--
        }
        if (bottom in rows.indices && rows[bottom] > sparse) {
            for (row in bottom + 1 until rows.size) drop[row] = false
        }
        if (drop.none { it }) return listOf(blob)
        val keepX = ArrayList<Int>()
        val keepY = ArrayList<Int>()
        for (index in blob.xs.indices) {
            if (drop[blob.ys[index] - blob.minY]) continue
            keepX += blob.xs[index]
            keepY += blob.ys[index]
        }
        val parts = cluster(keepX, keepY)
        if (parts.isEmpty() || parts.any { it.height < blob.height * 0.45f }) return listOf(blob)
        return parts
    }

    private fun cluster(xs: List<Int>, ys: List<Int>): List<Blob> {
        if (xs.isEmpty()) return emptyList()
        val indexOf = HashMap<Long, Int>(xs.size)
        for (index in xs.indices) indexOf[pack(xs[index], ys[index])] = index
        val seen = BooleanArray(xs.size)
        val parts = ArrayList<Blob>()
        for (start in xs.indices) {
            if (seen[start]) continue
            val qx = ArrayList<Int>()
            val qy = ArrayList<Int>()
            val stack = ArrayList<Int>()
            stack += start
            seen[start] = true
            var cursor = 0
            while (cursor < stack.size) {
                val index = stack[cursor++]
                qx += xs[index]
                qy += ys[index]
                val x = xs[index]
                val y = ys[index]
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        val next = indexOf[pack(x + dx, y + dy)] ?: continue
                        if (seen[next]) continue
                        seen[next] = true
                        stack += next
                    }
                }
            }
            if (qx.size >= 8) parts += Blob.fromPoints(qx, qy)
        }
        return parts
    }

    private fun pack(x: Int, y: Int): Long = x.toLong().shl(32) or (y.toLong() and 0xffffffffL)

    private fun splitDeep(blob: Blob, letterHeight: Int, depth: Int): List<Blob> {
        if (depth > 4) return listOf(blob)
        val parts = splitOnce(blob, letterHeight)
        if (parts.size <= 1) return listOf(blob)
        return parts.flatMap { splitDeep(it, letterHeight, depth + 1) }
    }

    private fun splitOnce(blob: Blob, letterHeight: Int): List<Blob> {
        if (blob.forced != null) return listOf(blob)
        if (blob.height < letterHeight * 0.5f) return listOf(blob)
        if (blob.width < letterHeight * 0.72f) return listOf(blob)
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
        if (parts.any { it.area < blob.area * 0.12f || it.height < blob.height * 0.62f }) return listOf(blob)
        return parts
    }


    private fun joinedFiCut(blob: Blob): Int? {
        if (blob.width < blob.height * 0.55f || blob.width > blob.height * 1.4f) return null
        val height = blob.height
        val rows = Array(blob.width) { BooleanArray(height) }
        for (index in blob.xs.indices) {
            val x = blob.xs[index] - blob.minX
            val y = blob.ys[index] - blob.minY
            if (x in rows.indices && y in 0 until height) rows[x][y] = true
        }
        val gap = IntArray(blob.width)
        val total = IntArray(blob.width)
        val firstInk = IntArray(blob.width) { height }
        for (x in rows.indices) {
            var run = 0
            var best = 0
            var seen = false
            for (y in 0 until height) {
                if (rows[x][y]) {
                    total[x]++
                    if (!seen) firstInk[x] = y
                    if (seen) best = max(best, run)
                    seen = true
                    run = 0
                } else if (seen) {
                    run++
                }
            }
            gap[x] = best
        }
        val stemGap = (height * 0.2f).toInt()
        fun stem(x: Int) = total[x] >= height * 0.65f && gap[x] <= stemGap
        var stemRuns = 0
        var runWidth = 0
        var wideStem = false
        var inStem = false
        for (column in total.indices) {
            if (stem(column)) {
                if (!inStem) {
                    stemRuns++
                    runWidth = 1
                } else {
                    runWidth++
                }
                if (runWidth > 5) wideStem = true
                inStem = true
            } else {
                inStem = false
            }
        }
        if (stemRuns != 2 || wideStem) return null
        val bridgeGap = (height * 0.3f).toInt()
        val belowStem = (height * 0.18f).toInt()
        val from = (blob.width * 0.12f).toInt().coerceAtLeast(1)
        val to = (blob.width * 0.88f).toInt()
        var x = from
        while (x < to) {
            if (gap[x] >= bridgeGap && total[x] >= 3) {
                val start = x
                while (x < to && gap[x] >= bridgeGap && total[x] >= 3) x++
                val len = x - start
                val left = (start - 1 downTo 0).firstOrNull { stem(it) }
                val right = (x until blob.width).firstOrNull { stem(it) }
                val bandFrom = (height * 0.22f).toInt()
                val bandTo = (height * 0.62f).toInt()
                var barHits = 0
                var barRow = -1
                for (y in bandFrom until bandTo) {
                    var hitsOnRow = 0
                    for (column in start until x) if (rows[column][y]) hitsOnRow++
                    if (hitsOnRow > barHits) {
                        barHits = hitsOnRow
                        barRow = y
                    }
                }
                val bar = barRow >= 0 && barHits * 2 >= len && left != null && right != null &&
                    barRow >= firstInk[left] + belowStem &&
                    barRow >= firstInk[right] + belowStem &&
                    start - left <= 5 && right - x <= 5 && len in 3..10
                if (bar && right != null) return blob.minX + right
            } else {
                x++
            }
        }
        return null
    }

    private fun detachLowMarks(blob: Blob): List<Blob> {
        if (blob.forced != null || blob.width < 8 || blob.height < 10) return listOf(blob)
        val columns = IntArray(blob.width)
        val upper = IntArray(blob.width)
        val lowerCut = blob.minY + (blob.height * 0.55f).toInt()
        for (index in blob.xs.indices) {
            val x = blob.xs[index] - blob.minX
            if (x !in columns.indices) continue
            columns[x]++
            if (blob.ys[index] < lowerCut) upper[x]++
        }
        var stem = 0
        for (x in columns.indices) if (columns[x] > columns[stem]) stem = x
        if (columns[stem] < blob.height * 0.45f) return listOf(blob)
        var tailStart = stem + 2
        while (tailStart < columns.size && columns[tailStart] > 2) tailStart++
        if (tailStart >= columns.size - 2) return listOf(blob)
        var gap = false
        for (x in stem + 1 until tailStart) if (columns[x] <= 2) gap = true
        if (tailStart > stem + 1 && columns[tailStart] <= 2) gap = true
        if (!gap) return listOf(blob)
        val keepX = ArrayList<Int>()
        val keepY = ArrayList<Int>()
        val dropX = ArrayList<Int>()
        val dropY = ArrayList<Int>()
        for (index in blob.xs.indices) {
            val x = blob.xs[index]
            val y = blob.ys[index]
            val column = x - blob.minX
            val tail = column >= tailStart && upper[column] <= 1 && y >= lowerCut
            if (tail) {
                dropX += x
                dropY += y
            } else {
                keepX += x
                keepY += y
            }
        }
        if (dropX.size < 10 || keepX.size < 8) return listOf(blob)
        val letter = Blob.fromPoints(keepX, keepY)
        val mark = Blob.fromPoints(dropX, dropY)
        if (mark.height < 4 || mark.width < 3) return listOf(blob)
        if (mark.height > blob.height * 0.75f || mark.area > letter.area) return listOf(blob)
        if (letter.height < blob.height * 0.55f) return listOf(blob)
        mark.comma = true
        return listOf(letter, mark)
    }

    private fun detachTrailingComma(blob: Blob): List<Blob> {
        if (blob.forced != null || blob.width < 10 || blob.height < 10) return listOf(blob)
        val columns = IntArray(blob.width)
        val upper = IntArray(blob.width)
        val lowerCut = blob.minY + (blob.height * 0.62f).toInt()
        for (index in blob.xs.indices) {
            val x = blob.xs[index] - blob.minX
            if (x !in columns.indices) continue
            columns[x]++
            if (blob.ys[index] < lowerCut) upper[x]++
        }
        var right = columns.lastIndex
        while (right > 0 && columns[right] == 0) right--
        var tail = right
        while (tail > 0 && columns[tail] > 0 && upper[tail] <= 1) tail--
        val start = tail + 1
        if (start > right || right - start > 8 || right - start < 2) return listOf(blob)
        var bodyBottom = blob.minY
        for (index in blob.xs.indices) {
            if (blob.xs[index] - blob.minX < start && blob.ys[index] > bodyBottom) bodyBottom = blob.ys[index]
        }
        val keepX = ArrayList<Int>()
        val keepY = ArrayList<Int>()
        val dropX = ArrayList<Int>()
        val dropY = ArrayList<Int>()
        for (index in blob.xs.indices) {
            val column = blob.xs[index] - blob.minX
            if (column >= start) {
                dropX += blob.xs[index]
                dropY += blob.ys[index]
            } else {
                keepX += blob.xs[index]
                keepY += blob.ys[index]
            }
        }
        if (dropX.size < 8 || keepX.size < 8) return listOf(blob)
        val letter = Blob.fromPoints(keepX, keepY)
        val mark = Blob.fromPoints(dropX, dropY)
        val hangs = mark.maxY >= bodyBottom + 3
        val levelComma = mark.height in 8..(letter.height * 0.55f).toInt() &&
            mark.height * 5 >= mark.width * 6 &&
            mark.maxY >= bodyBottom - 2
        if ((!hangs && !levelComma) || mark.height < 6) return listOf(blob)
        if (mark.area > letter.area * 0.5f || letter.height < blob.height * 0.55f) return listOf(blob)
        mark.comma = true
        return listOf(letter, mark)
    }

    private fun detachBaselineHook(blob: Blob): List<Blob> {
        if (blob.forced != null || blob.width < 12 || blob.height < 16) return listOf(blob)
        val rows = IntArray(blob.height)
        val rowMin = IntArray(blob.height) { blob.width }
        val rowMax = IntArray(blob.height) { -1 }
        for (index in blob.xs.indices) {
            val row = blob.ys[index] - blob.minY
            val column = blob.xs[index] - blob.minX
            if (row !in rows.indices || column !in 0 until blob.width) continue
            rows[row]++
            if (column < rowMin[row]) rowMin[row] = column
            if (column > rowMax[row]) rowMax[row] = column
        }
        val bodyInk = (blob.width * 0.28f).toInt().coerceAtLeast(8)
        var body = -1
        for (row in rows.indices) if (rows[row] >= bodyInk) body = row
        if (body < blob.height * 0.45f || body > blob.height - 6) return listOf(blob)
        val below = body + 1
        if (rows[below] == 0 || rows[below] > rows[body] * 0.45f) return listOf(blob)
        val rightSide = blob.width * 0.45f
        var hookRows = 0
        for (row in below until blob.height) {
            if (rows[row] == 0) continue
            val span = rowMax[row] - rowMin[row] + 1
            if (span > blob.width * 0.45f || rowMin[row] < rightSide) return listOf(blob)
            hookRows++
        }
        if (hookRows < 5) return listOf(blob)
        val cut = blob.minY + below
        val keepX = ArrayList<Int>()
        val keepY = ArrayList<Int>()
        val dropX = ArrayList<Int>()
        val dropY = ArrayList<Int>()
        for (index in blob.xs.indices) {
            if (blob.ys[index] >= cut) {
                dropX += blob.xs[index]
                dropY += blob.ys[index]
            } else {
                keepX += blob.xs[index]
                keepY += blob.ys[index]
            }
        }
        if (dropX.size < 8 || keepX.size < 8) return listOf(blob)
        val letter = Blob.fromPoints(keepX, keepY)
        val mark = Blob.fromPoints(dropX, dropY)
        if (mark.height < 5 || mark.area > letter.area * 0.45f) return listOf(blob)
        if (letter.height < blob.height * 0.55f) return listOf(blob)
        mark.comma = true
        return listOf(letter, mark)
    }

    private fun isLetterSized(blob: Blob, letterHeight: Int): Boolean {
        if (blob.height >= letterHeight * 0.62f) return true
        return blob.height >= letterHeight * 0.50f &&
            blob.width >= letterHeight * 0.34f &&
            blob.area >= letterHeight * 4
    }

    private fun isMarkSized(blob: Blob, letterHeight: Int): Boolean {
        if (blob.area < 6 || blob.height < 2 || blob.width < 2) return false
        return blob.height <= letterHeight * 0.72f && blob.width <= letterHeight * 0.55f
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
                if (isDividerEdge(blob, width, height)) continue
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

    private fun hasRest(blobs: List<Blob>, cellWidth: Int, cellHeight: Int, letterHeight: Int): Boolean {
        val kept = blobs.filter { blob -> !isIgnored(blob, blobs, cellWidth, cellHeight) }
        if (kept.none { isRest(it, cellWidth, cellHeight, letterHeight) }) return false
        val speck = (letterHeight * 0.45f).toInt().coerceAtLeast(3)
        return kept.all { blob ->
            isRest(blob, cellWidth, cellHeight, letterHeight) || (blob.height <= speck && blob.width <= speck)
        }
    }

    private fun isIgnored(
        blob: Blob,
        blobs: List<Blob>,
        cellWidth: Int,
        cellHeight: Int,
    ): Boolean {
        return isColonSpeck(blob, blobs, cellWidth) ||
            isUnderline(blob, cellWidth, cellHeight) ||
            isEdgeSpeck(blob, cellWidth)
    }

    private fun isUnderline(blob: Blob, cellWidth: Int, cellHeight: Int): Boolean {
        if (blob.height > 6 || blob.width < cellWidth * 0.55f) return false
        if (blob.height * 5 > blob.width) return false
        val center = (blob.minY + blob.maxY) / 2f
        return center < cellHeight * 0.22f || center > cellHeight * 0.82f
    }

    private fun isEdgeSpeck(blob: Blob, cellWidth: Int): Boolean {
        if (blob.width > 8 || blob.height > 8 || blob.area > 45) return false
        if (blob.width > blob.height * 1.6f) return false
        return blob.minX <= 6 || blob.maxX >= cellWidth - 6
    }

    private fun isColonSpeck(blob: Blob, blobs: List<Blob>, cellWidth: Int): Boolean {
        val atEdge = blob.minX <= 4 || blob.maxX >= cellWidth - 5
        if (!atEdge || blob.width > 6 || blob.height > 8) return false
        val centerX = (blob.minX + blob.maxX) / 2
        val centerY = (blob.minY + blob.maxY) / 2
        return blobs.any { other ->
            if (other === blob || other.width > 6 || other.height > 8) return@any false
            val otherX = (other.minX + other.maxX) / 2
            val otherY = (other.minY + other.maxY) / 2
            abs(otherX - centerX) <= 3 && abs(otherY - centerY) >= blob.height
        }
    }

    private fun isDividerEdge(blob: Blob, cellWidth: Int, cellHeight: Int): Boolean {
        val margin = max(12, cellWidth / 12)
        val onLeft = blob.maxX <= margin
        val onRight = blob.minX >= cellWidth - margin - 1
        if (!onLeft && !onRight) return false
        if (blob.width > 6 || blob.height < blob.width * 2) return false
        return blob.height >= 10 && blob.height >= cellHeight * 0.28f
    }

    private fun clearDividerEdges(bytes: ByteArray, width: Int, height: Int) {
        if (height < 8 || width < 6) return
        val tall = (height * 0.75f).toInt()
        val stroke = max(8, width / 14)
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

    private class GapMarks {
        var dotX: Float? = null
        val lows = ArrayList<Float>()

        fun noteDot(x: Float) {
            val current = dotX
            if (current == null || x < current) dotX = x
        }

        fun noteLow(x: Float) {
            lows += x
        }
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
        var lead: String = ""
        var trail: String = ""
        var divider: String? = null
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
        var comma: Boolean = false,
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
                return Blob(minX, minY, maxX, maxY, xs.toMutableList(), ys.toMutableList(), null, false)
            }
        }
    }

    private companion object {
        const val matchCap = 0.5f
        const val matchMargin = 0.03f
    }
}
