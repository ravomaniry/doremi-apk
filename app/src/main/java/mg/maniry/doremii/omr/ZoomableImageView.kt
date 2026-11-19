package mg.maniry.doremii.omr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.ImageView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {
    private val drawMatrix = Matrix()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomBy(if (zoom > 1.05f) 1f / zoom else DOUBLE_TAP_ZOOM / zoom, e.x, e.y)
            invalidate()
            return true
        }
    })
    private var image: Bitmap? = null
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var placedWidth = 0
    private var placedHeight = 0
    private var primaryId = INVALID
    private var secondaryId = INVALID
    private var lastSpan = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastX = 0f
    private var lastY = 0f

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
        isFocusable = true
        setWillNotDraw(false)
        gestures.setIsLongpressEnabled(false)
    }

    override fun setImageBitmap(bm: Bitmap?) {
        val bitmap = bm?.takeUnless { it.isRecycled }
        val sameSize = bitmap != null && bitmap.width == placedWidth && bitmap.height == placedHeight && placedWidth > 0
        if (!sameSize) {
            zoom = 1f
            panX = 0f
            panY = 0f
        }
        image = bitmap
        placedWidth = bitmap?.width ?: 0
        placedHeight = bitmap?.height ?: 0
        super.setImageBitmap(bitmap)
        updateMatrix()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = image
        if (bitmap == null || bitmap.isRecycled) return
        updateMatrix()
        canvas.drawBitmap(bitmap, drawMatrix, bitmapPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (image == null) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                primaryId = event.getPointerId(0)
                secondaryId = INVALID
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (secondaryId == INVALID) {
                    secondaryId = event.getPointerId(event.actionIndex)
                    rememberPinch(event)
                }
            }
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                primaryId = INVALID
                secondaryId = INVALID
            }
        }
        return true
    }

    private fun onMove(event: MotionEvent) {
        if (primaryId != INVALID && secondaryId != INVALID) {
            val span = span(event)
            val focus = focus(event) ?: return
            if (lastSpan > 1f && span > 1f) {
                zoomBy(span / lastSpan, lastFocusX, lastFocusY)
                panX += focus.first - lastFocusX
                panY += focus.second - lastFocusY
                updateMatrix()
                invalidate()
            }
            lastSpan = span
            lastFocusX = focus.first
            lastFocusY = focus.second
            return
        }
        if (zoom <= 1f || primaryId == INVALID) return
        val index = event.findPointerIndex(primaryId)
        if (index < 0) return
        val x = event.getX(index)
        val y = event.getY(index)
        panX += x - lastX
        panY += y - lastY
        lastX = x
        lastY = y
        updateMatrix()
        invalidate()
    }

    private fun onPointerUp(event: MotionEvent) {
        val lifted = event.getPointerId(event.actionIndex)
        if (lifted == secondaryId) secondaryId = INVALID
        else if (lifted == primaryId) {
            primaryId = secondaryId
            secondaryId = INVALID
        }
        val index = event.findPointerIndex(primaryId)
        if (secondaryId == INVALID && index >= 0) {
            lastX = event.getX(index)
            lastY = event.getY(index)
        }
    }

    private fun rememberPinch(event: MotionEvent) {
        lastSpan = span(event)
        val focus = focus(event) ?: return
        lastFocusX = focus.first
        lastFocusY = focus.second
    }

    private fun span(event: MotionEvent): Float {
        val first = event.findPointerIndex(primaryId)
        val second = event.findPointerIndex(secondaryId)
        if (first < 0 || second < 0) return 0f
        return hypot(event.getX(first) - event.getX(second), event.getY(first) - event.getY(second))
    }

    private fun focus(event: MotionEvent): Pair<Float, Float>? {
        val first = event.findPointerIndex(primaryId)
        val second = event.findPointerIndex(secondaryId)
        if (first < 0 || second < 0) return null
        return (event.getX(first) + event.getX(second)) / 2f to (event.getY(first) + event.getY(second)) / 2f
    }

    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        val next = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        val applied = next / zoom
        if (applied == 1f) return
        val centerX = width / 2f
        val centerY = height / 2f
        panX = applied * (panX - (focusX - centerX)) + (focusX - centerX)
        panY = applied * (panY - (focusY - centerY)) + (focusY - centerY)
        zoom = next
    }

    private fun updateMatrix() {
        val bitmap = image ?: return
        if (bitmap.isRecycled || width == 0 || height == 0) return
        val imageWidth = bitmap.width.toFloat()
        val imageHeight = bitmap.height.toFloat()
        if (imageWidth <= 0f || imageHeight <= 0f) return
        val fit = min(width / imageWidth, height / imageHeight)
        val scale = fit * zoom
        val drawnWidth = imageWidth * scale
        val drawnHeight = imageHeight * scale
        val limitX = max(0f, (drawnWidth - width) / 2f)
        val limitY = max(0f, (drawnHeight - height) / 2f)
        panX = panX.coerceIn(-limitX, limitX)
        panY = panY.coerceIn(-limitY, limitY)
        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate((width - drawnWidth) / 2f + panX, (height - drawnHeight) / 2f + panY)
    }

    private companion object {
        const val INVALID = -1
        const val MAX_ZOOM = 8f
        const val DOUBLE_TAP_ZOOM = 3f
    }
}
