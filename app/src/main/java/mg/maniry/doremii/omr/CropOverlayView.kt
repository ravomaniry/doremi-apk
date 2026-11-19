package mg.maniry.doremii.omr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import mg.maniry.doremii.R
import kotlin.math.min

class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    val crop = RectF()
    private var ready = false
    private val density = resources.displayMetrics.density
    private val edge = 28f * density
    private val minSize = 48f * density
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = ContextCompat.getColor(context, R.color.colorPrimary)
    }
    private val shade = Color.parseColor("#99000000")
    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!ready && w > 0 && h > 0) {
            val width = w * 0.92f
            val height = h * 0.32f
            crop.set((w - width) / 2f, (h - height) / 2f, (w + width) / 2f, (h + height) / 2f)
            ready = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (crop.isEmpty) return
        canvas.save()
        canvas.clipOutRect(crop)
        canvas.drawColor(shade)
        canvas.restore()
        canvas.drawRect(crop, frame)
        val mark = 18f * density
        canvas.drawLine(crop.left, crop.top, crop.left + mark, crop.top, frame)
        canvas.drawLine(crop.left, crop.top, crop.left, crop.top + mark, frame)
        canvas.drawLine(crop.right, crop.top, crop.right - mark, crop.top, frame)
        canvas.drawLine(crop.right, crop.top, crop.right, crop.top + mark, frame)
        canvas.drawLine(crop.left, crop.bottom, crop.left + mark, crop.bottom, frame)
        canvas.drawLine(crop.left, crop.bottom, crop.left, crop.bottom - mark, frame)
        canvas.drawLine(crop.right, crop.bottom, crop.right - mark, crop.bottom, frame)
        canvas.drawLine(crop.right, crop.bottom, crop.right, crop.bottom - mark, frame)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = hit(event.x, event.y)
                lastX = event.x
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                mode = Mode.MOVE
                lastX = centroidX(event)
                lastY = centroidY(event)
            }
            MotionEvent.ACTION_MOVE -> {
                val x = if (event.pointerCount >= 2) centroidX(event) else event.x
                val y = if (event.pointerCount >= 2) centroidY(event) else event.y
                val dx = x - lastX
                val dy = y - lastY
                when (mode) {
                    Mode.MOVE -> moveBy(dx, dy)
                    Mode.LEFT -> crop.left = min(crop.left + dx, crop.right - minSize)
                    Mode.RIGHT -> crop.right = maxOf(crop.right + dx, crop.left + minSize)
                    Mode.TOP -> crop.top = min(crop.top + dy, crop.bottom - minSize)
                    Mode.BOTTOM -> crop.bottom = maxOf(crop.bottom + dy, crop.top + minSize)
                    Mode.NONE -> Unit
                }
                clamp()
                lastX = x
                lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = Mode.NONE
        }
        return true
    }

    private fun hit(x: Float, y: Float): Mode {
        val nearLeft = kotlin.math.abs(x - crop.left) <= edge
        val nearRight = kotlin.math.abs(x - crop.right) <= edge
        val nearTop = kotlin.math.abs(y - crop.top) <= edge
        val nearBottom = kotlin.math.abs(y - crop.bottom) <= edge
        return when {
            nearLeft && y in crop.top..crop.bottom -> Mode.LEFT
            nearRight && y in crop.top..crop.bottom -> Mode.RIGHT
            nearTop && x in crop.left..crop.right -> Mode.TOP
            nearBottom && x in crop.left..crop.right -> Mode.BOTTOM
            crop.contains(x, y) -> Mode.MOVE
            else -> Mode.NONE
        }
    }

    private fun moveBy(dx: Float, dy: Float) {
        val width = crop.width()
        val height = crop.height()
        var left = crop.left + dx
        var top = crop.top + dy
        left = left.coerceIn(0f, (this.width - width).coerceAtLeast(0f))
        top = top.coerceIn(0f, (this.height - height).coerceAtLeast(0f))
        crop.set(left, top, left + width, top + height)
    }

    private fun clamp() {
        crop.left = crop.left.coerceAtLeast(0f)
        crop.top = crop.top.coerceAtLeast(0f)
        crop.right = crop.right.coerceAtMost(width.toFloat())
        crop.bottom = crop.bottom.coerceAtMost(height.toFloat())
        if (crop.width() < minSize) crop.right = (crop.left + minSize).coerceAtMost(width.toFloat())
        if (crop.height() < minSize) crop.bottom = (crop.top + minSize).coerceAtMost(height.toFloat())
    }

    private fun centroidX(event: MotionEvent): Float {
        var sum = 0f
        for (index in 0 until event.pointerCount) sum += event.getX(index)
        return sum / event.pointerCount
    }

    private fun centroidY(event: MotionEvent): Float {
        var sum = 0f
        for (index in 0 until event.pointerCount) sum += event.getY(index)
        return sum / event.pointerCount
    }

    private enum class Mode { NONE, MOVE, LEFT, RIGHT, TOP, BOTTOM }
}
