package mg.maniry.doremii.editor.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.widget.TableLayout
import android.widget.TableRow
import androidx.core.content.ContextCompat
import mg.maniry.doremii.R


class MeasureView(context: Context) : TableLayout(context) {
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private val paint = Paint().apply {
        color = Color.rgb(100, 100, 100)
        strokeWidth = 1.4f
    }
    private var playheadColumn = NO_COLUMN
    private var playheadNextColumn = NO_COLUMN
    private var playheadFraction = 0f
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.colorAccent)
        style = Paint.Style.FILL
    }
    private val playheadPath = Path()
    private val playheadWidth = 12f * resources.displayMetrics.density
    private val playheadHeight = 8f * resources.displayMetrics.density


    fun setPlayhead(column: Int, nextColumn: Int?, fraction: Float) {
        val next = nextColumn ?: NO_COLUMN
        val clamped = fraction.coerceIn(0f, 1f)
        val moved = playheadColumn != column || playheadNextColumn != next || playheadFraction != clamped
        playheadColumn = column
        playheadNextColumn = next
        playheadFraction = clamped
        if (moved) invalidate()
    }

    fun clearPlayhead() {
        if (playheadColumn == NO_COLUMN) return
        playheadColumn = NO_COLUMN
        playheadNextColumn = NO_COLUMN
        playheadFraction = 0f
        invalidate()
    }


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        endX = (w - 0.4).toFloat()
        endY = h.toFloat()
        startY = (h / 5).toFloat()
    }


    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        canvas.drawLine(0f, startY, 0f, endY, paint)
        canvas.drawLine(endX, startY, endX, endY, paint)
        drawPlayhead(canvas)
    }

    /**
     * A downward triangle centered on the beat, with its tip on the top of the first voice.
     * It slides from this column's center to the next. Size stays fixed.
     */
    private fun drawPlayhead(canvas: Canvas) {
        if (playheadColumn == NO_COLUMN) return
        val row = getChildAt(0) as? TableRow ?: return
        val from = row.getChildAt(playheadColumn) ?: return
        if (from.width <= 0) return
        val fromCenter = row.left + from.left + from.width / 2f
        val center = if (playheadNextColumn == NO_COLUMN) {
            fromCenter
        } else {
            val to = row.getChildAt(playheadNextColumn) ?: return
            val toCenter = row.left + to.left + to.width / 2f
            fromCenter + (toCenter - fromCenter) * playheadFraction
        }
        val voiceTop = (getChildAt(1) as? TableRow)?.top?.toFloat() ?: row.bottom.toFloat()
        var top = voiceTop - playheadHeight
        var tip = voiceTop
        if (top < 0f) {
            tip -= top
            top = 0f
        }
        val half = playheadWidth / 2f
        playheadPath.rewind()
        playheadPath.moveTo(center - half, top)
        playheadPath.lineTo(center + half, top)
        playheadPath.lineTo(center, tip)
        playheadPath.close()
        canvas.drawPath(playheadPath, playheadPaint)
    }

    private companion object {
        const val NO_COLUMN = -1
    }
}
