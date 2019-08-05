package mg.maniry.doremi.editor.partition

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan


data class Cell(val voice: Int = 0, val index: Int = 0, val content: String = "")


class KeyValue constructor(keyValue: List<String>) {
    val mKey = keyValue[0].trim()
    val mValue = if (keyValue.size < 2) "" else keyValue[1].trim()
}


data class Note(var channel: Int, var pitch: Int, var velocity: Int, var tick: Long, var duration: Long) {
    fun addDuration(additional: Long) {
        duration += additional
    }

    fun tickEqual(expectedTick: Long): Boolean {
        return expectedTick == tick
    }

    override fun toString() = "$pitch $tick $duration $velocity"
}


class Labels {
    companion object {
        const val KEY = "P_KEY"
        const val SIGNATURE = "P_SIGNATURE"
        const val TEMPO = "P_TEMPO"
        const val SWING = "P_SWING"

        const val TITLE = "P_TITLE"
        const val AUTHOR = "P_AUT"
        const val COMP = "P_COMP"
        const val DATE = "P_DATE"
        const val SINGER = "P_SINGER"

        const val CHANGES = "CHANGES"
        const val INSTR = "INSTR"
        const val VOICES = "VOICES"
        const val VERSION = "VERSION"
    }
}


data class MeasureHeader(val position: Int, var text: String, val types: MutableList<String>) {

    fun toSpan(): SpannableStringBuilder {
        val span = SpannableStringBuilder(text).apply {
            setSpan(RelativeSizeSpan(0.6f), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        text.split(' ').forEachIndexed { i, t ->
            val start = text.indexOf(t)
            val color = when (types[i]) {
                ChangeEvent.DAL -> Color.rgb(0, 177, 46)
                ChangeEvent.SIGN -> Color.rgb(160, 90, 0)
                ChangeEvent.MOD -> Color.RED
                ChangeEvent.VELOCITY -> Color.rgb(10, 10, 245)
                else -> Color.rgb(0, 168, 176)
            }

            span.setSpan(ForegroundColorSpan(color), start, start + t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        return span
    }
}


data class PartitionPasteResult(val updatedCell: MutableList<Cell>, val changes: MutableList<EditionHistory.Change>)
