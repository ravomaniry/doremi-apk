package mg.maniry.doremii.editor.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import mg.maniry.doremii.editor.partition.Note
import mg.maniry.doremii.editor.partition.pianoPitchRange

/** Display-only keyboard for the notes a song actually plays. */
class PlaybackPianoView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 40, 40)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val voicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var lowPitch = 60
    private var highPitch = 71
    private var lights: Map<Int, List<Int>> = emptyMap()
    private var keys: List<KeyRect> = emptyList()
    private var scored: List<Note>? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(notes: List<Note>) {
        if (notes === scored) return
        scored = notes
        val range = pianoPitchRange(notes)
        lowPitch = range.first
        highPitch = range.last
        rebuild()
        invalidate()
    }

    fun setActive(voicesByPitch: Map<Int, List<Int>>) {
        if (voicesByPitch == lights) return
        lights = voicesByPitch
        invalidate()
    }

    fun clear() {
        scored = null
        lights = emptyMap()
        lowPitch = 60
        highPitch = 71
        rebuild()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(32, 24, 20))
        for (key in keys) {
            if (!key.black) drawKey(canvas, key)
        }
        for (key in keys) {
            if (key.black) drawKey(canvas, key)
        }
    }

    private fun drawKey(canvas: Canvas, key: KeyRect) {
        val voices = lights[key.pitch].orEmpty()
        if (voices.isEmpty()) {
            canvas.drawRect(key.rect, if (key.black) blackPaint else whitePaint)
        } else {
            val band = key.rect.height() / voices.size
            voices.forEachIndexed { index, voice ->
                val top = key.rect.top + index * band
                val bottom = if (index == voices.lastIndex) key.rect.bottom else top + band
                voicePaint.color = voiceColor(voice)
                canvas.drawRect(key.rect.left, top, key.rect.right, bottom, voicePaint)
            }
        }
        if (!key.black) canvas.drawRect(key.rect, edgePaint)
    }

    private fun rebuild() {
        val width = width
        val height = height
        if (width <= 0 || height <= 0 || highPitch < lowPitch) {
            keys = emptyList()
            return
        }
        val pitches = lowPitch..highPitch
        val whites = pitches.filter { isWhite(it) }
        if (whites.isEmpty()) {
            keys = emptyList()
            return
        }
        val whiteWidth = width.toFloat() / whites.size
        val whiteIndex = whites.withIndex().associate { it.value to it.index }
        val built = mutableListOf<KeyRect>()
        for (pitch in whites) {
            val index = whiteIndex.getValue(pitch)
            val left = index * whiteWidth
            built += KeyRect(
                pitch,
                RectF(left + 0.5f, 0f, left + whiteWidth - 0.5f, height.toFloat()),
                false,
            )
        }
        val blackWidth = whiteWidth * 0.58f
        val blackHeight = height * 0.62f
        for (pitch in pitches) {
            if (isWhite(pitch)) continue
            val previous = whiteIndex[pitch - 1] ?: continue
            val boundary = (previous + 1) * whiteWidth
            val left = boundary - blackWidth / 2f
            built += KeyRect(pitch, RectF(left, 0f, left + blackWidth, blackHeight), true)
        }
        keys = built
    }

    private data class KeyRect(val pitch: Int, val rect: RectF, val black: Boolean)

    companion object {
        private val voiceColors = intArrayOf(
            0xFFE53935.toInt(),
            0xFF1E88E5.toInt(),
            0xFF43A047.toInt(),
            0xFFFB8C00.toInt(),
            0xFF8E24AA.toInt(),
            0xFF00ACC1.toInt(),
            0xFFFDD835.toInt(),
            0xFF6D4C41.toInt(),
            0xFFD81B60.toInt(),
            0xFF3949AB.toInt(),
            0xFF7CB342.toInt(),
            0xFF00897B.toInt(),
        )

        fun voiceColor(voice: Int): Int {
            val index = voice.mod(voiceColors.size).let { if (it < 0) it + voiceColors.size else it }
            return voiceColors[index]
        }

        private fun isWhite(pitch: Int): Boolean {
            return when (floorMod(pitch, 12)) {
                1, 3, 6, 8, 10 -> false
                else -> true
            }
        }

        private fun floorMod(value: Int, divisor: Int): Int {
            val remainder = value % divisor
            return if (remainder < 0) remainder + divisor else remainder
        }
    }
}
