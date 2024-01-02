package mg.maniry.doremii.editor.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.TableLayout


class MeasureView(context: Context) : TableLayout(context) {
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private val paint = Paint().apply {
        color = Color.rgb(100, 100, 100)
        strokeWidth = 1.4f
    }


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        endX = (w - 0.4).toFloat()
        endY = h.toFloat()
        startY = (h / 5).toFloat()
    }


    override fun dispatchDraw(canvas: Canvas?) {
        super.dispatchDraw(canvas)
        canvas?.run {
            drawLine(0f, startY, 0f, endY, paint)
            drawLine(endX, startY, endX, endY, paint)
        }
    }
}