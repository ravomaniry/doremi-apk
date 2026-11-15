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
            debug.windows.isEmpty()
        ) {
            return copy
        }
        val canvas = Canvas(copy)
        val stroke = max(2f, min(copy.width, copy.height) / 220f)
        val photoHeight = copy.height.toFloat()
        val measureGlow = linePaint(stroke * 5f, 0x99FF1744.toInt())
        val measureLine = linePaint(stroke * 1.4f, 0xFFFF1744.toInt())
        val beatGlow = linePaint(stroke * 5f, 0x9900C853.toInt())
        val beatLine = linePaint(stroke * 1.4f, 0xFF00C853.toInt())
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
        for (beat in debug.beats) {
            val start = photoPoint(beat.x, beat.top, photoHeight, quarterTurn)
            val end = photoPoint(beat.x, beat.bottom, photoHeight, quarterTurn)
            canvas.drawLine(start.first, start.second, end.first, end.second, beatGlow)
            canvas.drawLine(start.first, start.second, end.first, end.second, beatLine)
        }
        val windowBox = linePaint(stroke * 2f, 0xFFAA00FF.toInt())
        val failedWindowBox = linePaint(stroke * 2.4f, 0xFFFF1744.toInt())
        val failedLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF1744.toInt()
            textAlign = Paint.Align.LEFT
            isFakeBoldText = true
            setShadowLayer(max(1f, stroke), 0f, 0f, 0xE6000000.toInt())
        }
        for (window in debug.windows) {
            val rect = photoRect(window.left, window.top, window.right, window.bottom, photoHeight, quarterTurn)
            val failed = window.bounds == DebugBounds.LEFT
            canvas.drawRect(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                if (failed) failedWindowBox else windowBox,
            )
            if (failed && window.label.isNotEmpty()) {
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
        return copy
    }

    private fun linePaint(width: Float, color: Int): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            this.color = color
        }
    }
}
