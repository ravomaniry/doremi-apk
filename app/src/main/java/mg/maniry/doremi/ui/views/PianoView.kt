package mg.maniry.doremi.ui.views

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.util.AttributeSet
import android.graphics.Canvas
import android.view.MotionEvent


class PianoView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val whiteKeys: Array<PianoKey?> = Array(8) { null }
    private val blackKeys: Array<PianoKey?> = Array(5) { null }
    private var keyWidth = 1f
    private var keyHeight = 1f
    private var octave = 0
    private val black = Paint()
    private val white = Paint()
    private var gray = Paint()
    private val nb = 8
    var onPressed: (String?) -> Unit = { }

    init {
        black.apply { color = Color.BLACK; style = Paint.Style.FILL }
        white.apply { color = Color.WHITE; style = Paint.Style.FILL }
        gray.apply { color = Color.rgb(20, 20, 20); style = Paint.Style.FILL }
    }


    fun changeOctave(newOctave: Int) {
        octave = newOctave
        postInvalidate()
    }


    private fun getPressedKey(x: Float, y: Float): PianoKey? {
        for (key in blackKeys) {
            if (key!!.rect.contains(x, y))
                return key
        }

        for (key in whiteKeys) {
            if (key!!.rect.contains(x, y))
                return key
        }
        return null
    }


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        keyWidth = 1f * w / nb
        keyHeight = 1f * h - 1

        val whiteNotes = arrayOf("d", "r", "m", "f", "s", "l", "t", "d1")
        val blackNotes = arrayOf("di", "ri", "fi", "si", "ta")
        var left: Float
        var right: Float
        var bLeft: Float
        var bRight: Float
        var bHeight: Float
        var blackKeyIndex = 0

        for (i in 0 until nb) {
            left = i * keyWidth
            right = left + keyWidth - 1
            whiteKeys[i] = PianoKey(left, right, keyHeight, whiteNotes[i])

            if (i != 2 && i < 6) {
                bLeft = (i + 0.6f) * keyWidth
                bRight = bLeft + keyWidth * 0.8f
                bHeight = h * 0.6f
                blackKeys[blackKeyIndex] = mg.maniry.doremi.ui.views.PianoKey(bLeft, bRight, bHeight, blackNotes[blackKeyIndex])
                blackKeyIndex++
            }
        }
    }


    override fun onDraw(canvas: Canvas) {
        for (i in 0 until nb) {
            whiteKeys[i]?.run { canvas.drawRect(rect, white) }
            canvas.drawLine(i * keyWidth, 0f, i * keyWidth, keyHeight, black)
        }

        blackKeys.forEach {
            it?.run { canvas.drawRect(rect, black) }
        }

        canvas.drawLine(0f, keyHeight, nb * keyWidth, keyHeight, black)

        black.apply {
            textSize = 34f
            textAlign = Paint.Align.CENTER
        }

        listOf("d", "r", "m", "f", "s", "l", "t", "d").forEachIndexed { index, s ->
            canvas.drawText(s, (index * keyWidth) + keyWidth / 2 - 6, keyHeight - 30f, black)
        }

        black.textSize = 18f
        if (octave != 0) {
            val octY = if (octave < 0) keyHeight - 16f else keyHeight - 54f
            (0..6).forEach { canvas.drawText(Math.abs(octave).toString(), (it + 0.5f) * keyWidth + 14, octY, black) }
        }

        if (octave != -1) {
            val d1OctY = if (octave < -1) keyHeight - 16f else keyHeight - 54f
            canvas.drawText(Math.abs(octave + 1).toString(), 7.5f * keyWidth + 12, d1OctY, black)
        }
    }


    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null) {
            if (event.action == MotionEvent.ACTION_UP) {
                onPressed(getPressedKey(event.x, event.y)?.note)
            }
        }

        return true
    }
}