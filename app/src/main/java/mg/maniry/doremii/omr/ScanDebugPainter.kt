package mg.maniry.doremii.omr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min

object ScanDebugPainter {
    fun paint(source: Bitmap, debug: ScanDebug, quarterTurn: Boolean): Bitmap {
        val copy = source.copy(Bitmap.Config.ARGB_8888, true) ?: return source
        if (
            debug.notes.isEmpty() &&
            debug.measures.isEmpty() &&
            debug.beats.isEmpty() &&
            debug.glyphs.isEmpty() &&
            debug.windows.isEmpty() &&
            debug.blocks.isEmpty() &&
            debug.marks.isEmpty()
        ) {
            return copy
        }
        val canvas = Canvas(copy)
        val stroke = max(2f, min(copy.width, copy.height) / 220f)
        val hairline = max(1.5f, min(copy.width, copy.height) / 420f)
        val photoHeight = copy.height.toFloat()
        val boxStroke = linePaint(stroke, 0xFF8E24AA.toInt())
        for (block in debug.blocks) {
            val rect = photoRect(block.left, block.top, block.right, block.bottom, photoHeight, quarterTurn)
            canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, boxStroke)
        }
        val measureGlow = linePaint(stroke * 5f, 0x99FF1744.toInt())
        val measureLine = linePaint(stroke * 1.4f, 0xFFFF1744.toInt())
        val noteBox = linePaint(stroke * 1.6f, 0xFF00C853.toInt())
        val textBox = linePaint(stroke * 1.6f, 0xFF1976D2.toInt())
        val rightNoteBox = linePaint(stroke * 1.6f, 0xFF00E5FF.toInt())
        val rightTextBox = linePaint(stroke * 1.6f, 0xFFFF4081.toInt())
        for (note in debug.notes) {
            val rect = photoRect(note.left, note.top, note.right, note.bottom, photoHeight, quarterTurn)
            val pad = stroke
            val paint = when {
                note.bounds == DebugBounds.LEFT && note.musical -> rightNoteBox
                note.bounds == DebugBounds.LEFT -> rightTextBox
                note.musical -> noteBox
                else -> textBox
            }
            canvas.drawRect(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad, paint)
        }
        for (wall in debug.measures) {
            val start = photoPoint(wall.x, wall.top, photoHeight, quarterTurn)
            val end = photoPoint(wall.x, wall.bottom, photoHeight, quarterTurn)
            canvas.drawLine(start.first, start.second, end.first, end.second, measureGlow)
            canvas.drawLine(start.first, start.second, end.first, end.second, measureLine)
        }
        val failedWindowBox = linePaint(stroke * 2.4f, 0xFFFF1744.toInt())
        val failedLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF1744.toInt()
            textAlign = Paint.Align.LEFT
            isFakeBoldText = true
            setShadowLayer(max(1f, stroke), 0f, 0f, 0xE6000000.toInt())
        }
        for (window in debug.windows) {
            if (window.bounds != DebugBounds.LEFT) continue
            val rect = photoRect(window.left, window.top, window.right, window.bottom, photoHeight, quarterTurn)
            canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, failedWindowBox)
            if (window.label.isNotEmpty()) {
                val width = (rect.right - rect.left - stroke * 2f).coerceAtLeast(1f)
                failedLabel.textSize = max(18f, stroke * 7f)
                val measured = failedLabel.measureText(window.label).coerceAtLeast(1f)
                if (measured > width) failedLabel.textSize *= width / measured
                val baseline = rect.top - failedLabel.ascent() + stroke
                canvas.drawText(window.label, rect.left + stroke, baseline, failedLabel)
            }
        }
        val lineLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.LEFT
            isFakeBoldText = true
            setShadowLayer(max(1f, stroke), 0f, 0f, 0xE6000000.toInt())
        }
        for (note in debug.notes) {
            if (note.label.isEmpty()) continue
            val rect = photoRect(note.left, note.top, note.right, note.bottom, photoHeight, quarterTurn)
            val height = (rect.bottom - rect.top).coerceAtLeast(stroke * 4f)
            lineLabel.color = if (note.musical) 0xFF00C853.toInt() else 0xFFFF1744.toInt()
            lineLabel.textSize = height * 0.7f
            val baseline = (rect.top + rect.bottom) / 2f - (lineLabel.ascent() + lineLabel.descent()) / 2f
            canvas.drawText(note.label, rect.right + stroke * 2f, baseline, lineLabel)
        }
        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            setShadowLayer(max(1f, stroke), 0f, 0f, 0xE6000000.toInt())
        }
        for (glyph in debug.glyphs) {
            val text = glyph.text
            if (text.isBlank()) continue
            val rect = photoRect(glyph.left, glyph.top, glyph.right, glyph.bottom, photoHeight, quarterTurn)
            val height = (rect.bottom - rect.top).coerceAtLeast(stroke * 4f)
            val width = (rect.right - rect.left).coerceAtLeast(1f)
            glyphPaint.color = if (glyph.bounds == DebugBounds.LEFT) 0xFFFFFFFF.toInt() else 0xFFFF6D00.toInt()
            glyphPaint.textSize = height
            val measured = glyphPaint.measureText(text).coerceAtLeast(1f)
            if (measured > width) glyphPaint.textSize = height * width / measured
            val centerX = (rect.left + rect.right) / 2f
            val baseline = (rect.top + rect.bottom) / 2f - (glyphPaint.ascent() + glyphPaint.descent()) / 2f
            canvas.drawText(text, centerX, baseline, glyphPaint)
        }
        val markStroke = hairline * 1.7f
        val markPaint = mapOf(
            DebugMarkKind.BAR to linePaint(markStroke, 0xFF6D4C41.toInt()),
            DebugMarkKind.PIPE to linePaint(markStroke, 0xFFE91E63.toInt()),
            DebugMarkKind.COLON to linePaint(markStroke, 0xFF1E88E5.toInt()),
        )
        val markPad = markStroke * 0.55f
        for (kind in listOf(DebugMarkKind.BAR, DebugMarkKind.PIPE, DebugMarkKind.COLON)) {
            val paint = markPaint.getValue(kind)
            for (mark in debug.marks) {
                if (mark.kind != kind) continue
                val rect = photoRect(mark.left, mark.top, mark.right, mark.bottom, photoHeight, quarterTurn)
                canvas.drawRect(
                    rect.left - markPad,
                    rect.top - markPad,
                    rect.right + markPad,
                    rect.bottom + markPad,
                    paint,
                )
            }
        }
        val beatLine = linePaint(max(2.5f, stroke * 1.7f), 0xFF2962FF.toInt())
        for (beat in debug.beats) {
            val start = photoPoint(beat.x, beat.top, photoHeight, quarterTurn)
            val end = photoPoint(beat.x, beat.bottom, photoHeight, quarterTurn)
            canvas.drawLine(start.first, start.second, end.first, end.second, beatLine)
        }
        drawLegend(canvas, copy.width, copy.height)
        return copy
    }

    private fun drawLegend(canvas: Canvas, width: Int, height: Int) {
        if (width < 8 || height < 8) return
        val entries = listOf(
            0xFF8E24AA.toInt() to "Musical box",
            0xFF2962FF.toInt() to "Beat divider",
            0xFF1E88E5.toInt() to "Colon",
            0xFFE91E63.toInt() to "Pipe",
            0xFF6D4C41.toInt() to "Bar",
        )
        val textSize = max(18f, min(width, height) / 42f)
        val swatch = textSize * 0.85f
        val pad = textSize * 0.5f
        val gap = textSize * 0.4f
        val row = textSize * 1.45f
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1A1A1A.toInt()
            this.textSize = textSize
            textAlign = Paint.Align.LEFT
        }
        val labelWidth = entries.maxOf { text.measureText(it.second) }
        val boxWidth = pad * 2f + swatch + gap + labelWidth
        val boxHeight = pad * 2f + row * (entries.size - 1) + swatch
        val margin = pad
        val left = (width - boxWidth - margin).coerceAtLeast(0f)
        val top = margin.coerceAtMost((height - boxHeight).coerceAtLeast(0f))
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6FFFFFF.toInt()
            style = Paint.Style.FILL
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33000000
            style = Paint.Style.STROKE
            strokeWidth = max(1f, textSize / 16f)
        }
        canvas.drawRoundRect(left, top, left + boxWidth, top + boxHeight, pad, pad, background)
        canvas.drawRoundRect(left, top, left + boxWidth, top + boxHeight, pad, pad, border)
        val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        entries.forEachIndexed { index, (color, label) ->
            val y = top + pad + index * row
            chip.color = color
            canvas.drawRect(left + pad, y, left + pad + swatch, y + swatch, chip)
            val baseline = y + swatch / 2f - (text.ascent() + text.descent()) / 2f
            canvas.drawText(label, left + pad + swatch + gap, baseline, text)
        }
    }

    private fun linePaint(width: Float, color: Int): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            this.color = color
        }
    }
}
