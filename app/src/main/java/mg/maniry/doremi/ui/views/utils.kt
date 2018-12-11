package mg.maniry.doremi.ui.views

import android.graphics.RectF


data class PianoKey(val left: Float, val right: Float, val height: Float, val note: String) {
    val rect = RectF(left, 0f, right, height)
}