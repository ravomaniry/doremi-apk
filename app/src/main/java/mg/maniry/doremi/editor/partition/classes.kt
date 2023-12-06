package mg.maniry.doremi.editor.partition

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import mg.maniry.doremi.editor.managers.SolfaColors


data class Cell(val voice: Int = 0, val index: Int = 0, val content: String = "")


class KeyValue constructor(keyValue: List<String>) {
    val mKey = keyValue[0].trim()
    val mValue = if (keyValue.size < 2) "" else keyValue[1].trim()
}


data class Note(
    var channel: Int, var pitch: Int, var velocity: Int, var tick: Long, var duration: Long
) {
    fun addDuration(additional: Long) {
        duration += additional
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

    fun toSpan(colors: SolfaColors): SpannableStringBuilder {
        val span = SpannableStringBuilder(text).apply {
            setSpan(RelativeSizeSpan(0.6f), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        text.split(' ').forEachIndexed { i, t ->
            val start = text.indexOf(t)
            val color = when (types[i]) {
                ChangeEvent.DAL -> colors.sign
                ChangeEvent.SIGN -> colors.sign
                ChangeEvent.MOD -> colors.modulation
                ChangeEvent.VELOCITY -> colors.velocity
                else -> colors.tempoChange
            }
            span.setSpan(
                ForegroundColorSpan(color),
                start,
                start + t.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        return span
    }
}


data class PartitionPasteResult(
    val updatedCell: MutableList<Cell>, val changes: MutableList<EditionHistory.Change>
)
