package mg.maniry.doremii.omr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object CellReader {
    suspend fun read(
        source: Bitmap,
        ink: InkImage,
        detected: DetectedGrid,
        signature: Int,
        cellText: MutableMap<CellKey, String>,
        regionText: MutableMap<CellKey, String>,
        recognize: suspend (Bitmap) -> List<OcrLine>,
    ): SolfaParse {
        val image = ImagePreprocessor.software(source)
        val borrowed = image !== source
        try {
            val layout = detected.layout(ink, signature)
            readCells(image, layout, cellText, recognize)
            readRegions(image, layout, regionText, recognize)
            return GridScore.fromLayout(layout, cellText, regionText)
        } finally {
            if (borrowed && !image.isRecycled) image.recycle()
        }
    }

    private suspend fun readCells(
        source: Bitmap,
        layout: LaidOutScore,
        cellText: MutableMap<CellKey, String>,
        recognize: suspend (Bitmap) -> List<OcrLine>,
    ) {
        val unread = layout.voices.flatten().filter { it.ink == CellInk.GLYPH && it.key !in cellText }
        val unresolved = mutableListOf<GridCell>()
        for (cells in unread.groupBy { it.systemIndex }.values) {
            val sheet = drawSheet(source, cells)
            if (sheet == null) {
                unresolved.addAll(cells)
                continue
            }
            try {
                val assigned = assign(recognize(sheet.bitmap), sheet.slots)
                for (cell in cells) {
                    val normalized = SolfaCellMapper.fromOcrText(assigned[cell.key].orEmpty())
                    if (normalized.isNotEmpty()) cellText[cell.key] = normalized else unresolved.add(cell)
                }
            } finally {
                if (!sheet.bitmap.isRecycled) sheet.bitmap.recycle()
            }
        }
        val missed = unresolved.filter { it.key !in cellText }
        for ((place, cells) in missed.groupBy { it.systemIndex to it.voiceIndex }) {
            val span = layout.spans.find { it.systemIndex == place.first && it.voiceIndex == place.second }
            if (span == null) {
                cells.forEach { cellText.putIfAbsent(it.key, "") }
                continue
            }
            val row = crop(source, span.left, span.top, span.right, span.bottom)
            if (row == null) {
                cells.forEach { cellText.putIfAbsent(it.key, "") }
                continue
            }
            try {
                val symbols = recognize(row).flatMap { it.symbols }.map { symbol ->
                    symbol.copy(
                        left = symbol.left + span.left,
                        top = symbol.top + span.top,
                        right = symbol.right + span.left,
                        bottom = symbol.bottom + span.top,
                    )
                }
                for (cell in cells) {
                    val text = symbols
                        .filter { inside(it, cell) && !markerPipe(it, span.markerXs) }
                        .sortedBy { it.centerX }
                        .joinToString("") { it.plain() }
                    cellText[cell.key] = SolfaCellMapper.fromOcrText(text)
                }
            } finally {
                if (!row.isRecycled) row.recycle()
            }
        }
        for (cell in missed) cellText.putIfAbsent(cell.key, "")
    }

    private suspend fun readRegions(
        source: Bitmap,
        layout: LaidOutScore,
        regionText: MutableMap<CellKey, String>,
        recognize: suspend (Bitmap) -> List<OcrLine>,
    ) {
        val header = layout.header
        if (header != null && header !in regionText) {
            val strip = crop(source, header.left, header.top, header.right, header.bottom)
            regionText[header] = if (strip == null) {
                ""
            } else {
                try {
                    recognize(strip).joinToString(" ") { it.text }
                } finally {
                    if (!strip.isRecycled) strip.recycle()
                }
            }
        }
        val labels = layout.labels.filter { it.key !in regionText }
        if (labels.isEmpty()) return
        val left = labels.minOf { it.left }
        val top = labels.minOf { it.top }
        val right = labels.maxOf { it.right }
        val bottom = labels.maxOf { it.bottom }
        val gutter = crop(source, left, top, right, bottom)
        if (gutter == null) {
            labels.forEach { regionText[it.key] = "" }
            return
        }
        try {
            val symbols = recognize(gutter).flatMap { it.symbols }.map { symbol ->
                symbol.copy(
                    left = symbol.left + left,
                    top = symbol.top + top,
                    right = symbol.right + left,
                    bottom = symbol.bottom + top,
                )
            }
            for (label in labels) {
                regionText[label.key] = symbols
                    .filter { it.centerY >= label.top && it.centerY < label.bottom }
                    .sortedBy { it.centerX }
                    .joinToString("") { it.plain() }
            }
        } finally {
            if (!gutter.isRecycled) gutter.recycle()
        }
    }

    private fun drawSheet(source: Bitmap, cells: List<GridCell>): Sheet? {
        if (cells.isEmpty() || source.width < 2 || source.height < 2) return null
        val pad = 16
        val target = 64
        val gap = 48
        var cursor = pad
        val planned = mutableListOf<SheetSlot>()
        var sheetWidth = target
        for (cell in cells) {
            val height = (cell.bottom - cell.top).coerceAtLeast(1)
            val width = (cell.right - cell.left).coerceAtLeast(1)
            val destWidth = max(8, (width * target.toFloat() / height).roundToInt())
            planned.add(SheetSlot(cell, cursor, destWidth))
            sheetWidth = max(sheetWidth, destWidth + pad * 2)
            cursor += target + gap
        }
        val sheetHeight = max(pad + target, cursor - gap + pad)
        val bitmap = Bitmap.createBitmap(sheetWidth, sheetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val slots = mutableListOf<SheetSlot>()
        for (slot in planned) {
            val cell = slot.cell
            val l = cell.left.coerceIn(0, source.width - 1)
            val t = cell.top.coerceIn(0, source.height - 1)
            val r = cell.right.coerceIn(l + 1, source.width)
            val b = cell.bottom.coerceIn(t + 1, source.height)
            if (r - l < 1 || b - t < 1) continue
            canvas.drawBitmap(
                source,
                Rect(l, t, r, b),
                Rect(pad, slot.top, pad + slot.destWidth, slot.top + target),
                paint,
            )
            slots.add(slot)
        }
        if (slots.isEmpty()) {
            bitmap.recycle()
            return null
        }
        return Sheet(bitmap, slots)
    }

    private fun assign(lines: List<OcrLine>, slots: List<SheetSlot>): Map<CellKey, String> {
        if (slots.isEmpty()) return emptyMap()
        val sorted = lines.filter { it.text.isNotBlank() }.sortedBy { it.top }
        if (sorted.isNotEmpty() && sorted.size == slots.size) {
            return sorted.zip(slots).associate { (line, slot) -> slot.cell.key to line.text }
        }
        val used = mutableSetOf<Int>()
        val result = mutableMapOf<CellKey, String>()
        for (slot in slots) {
            val slotTop = slot.top.toFloat()
            val slotBottom = slotTop + 64f
            var best = -1
            var bestOverlap = 0f
            for ((index, line) in sorted.withIndex()) {
                if (index in used) continue
                val overlap = min(line.bottom, slotBottom) - max(line.top, slotTop)
                if (overlap > bestOverlap) {
                    bestOverlap = overlap
                    best = index
                }
            }
            if (best >= 0) {
                used.add(best)
                result[slot.cell.key] = sorted[best].text
            }
        }
        return result
    }

    private fun crop(source: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Bitmap? {
        if (source.width < 2 || source.height < 2) return null
        val l = left.coerceIn(0, source.width - 1)
        val t = top.coerceIn(0, source.height - 1)
        val r = right.coerceIn(l + 1, source.width)
        val b = bottom.coerceIn(t + 1, source.height)
        if (r - l < 2 || b - t < 2) return null
        val out = Bitmap.createBitmap(r - l, b - t, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, Rect(l, t, r, b), Rect(0, 0, r - l, b - t), null)
        return out
    }

    private fun inside(symbol: OcrSymbol, cell: GridCell): Boolean {
        return symbol.centerX >= cell.left && symbol.centerX < cell.right &&
            symbol.centerY >= cell.top && symbol.centerY < cell.bottom
    }

    private fun markerPipe(symbol: OcrSymbol, markerXs: List<Float>): Boolean {
        val text = symbol.plain().lowercase()
        if (text !in setOf("l", "i", "|", "1")) return false
        if (symbol.height <= symbol.width || symbol.width > symbol.height * 0.45f) return false
        val reach = max(6f, symbol.height * 0.5f)
        return markerXs.any { abs(it - symbol.centerX) <= reach }
    }
}

private data class SheetSlot(val cell: GridCell, val top: Int, val destWidth: Int)

private data class Sheet(val bitmap: Bitmap, val slots: List<SheetSlot>)
