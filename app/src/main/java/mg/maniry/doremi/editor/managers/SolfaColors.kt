package mg.maniry.doremi.editor.managers

import android.content.Context
import android.graphics.Color
import android.support.v4.content.ContextCompat
import mg.maniry.doremi.R

data class SolfaColors(private val mainContext: Context) {
    val regularBg = Color.TRANSPARENT
    val cursorBg = ContextCompat.getColor(mainContext, R.color.colorPrimary)
    val selectBg = ContextCompat.getColor(mainContext, R.color.colorAccent)
    val voiceId = ContextCompat.getColor(mainContext, R.color.gray)
    val separator = ContextCompat.getColor(mainContext, R.color.dark_gray)
    val key = ContextCompat.getColor(mainContext, R.color.colorAccent)
    val playerCursor = ContextCompat.getColor(mainContext, R.color.colorAccentLight)
    val modulation = ContextCompat.getColor(mainContext, R.color.colorPrimaryLight)
    val velocity = ContextCompat.getColor(mainContext, R.color.colorPrimaryLight)
    val sign = ContextCompat.getColor(mainContext, R.color.secondaryColor)
    val tempoChange = ContextCompat.getColor(mainContext, R.color.colorAccentDark)
}
