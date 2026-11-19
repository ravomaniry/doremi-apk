package mg.maniry.doremii.omr

import android.graphics.Color
import android.view.Gravity
import android.widget.TableRow
import android.widget.TextView
import com.google.android.flexbox.FlexboxLayout
import mg.maniry.doremii.editor.managers.SolfaColors
import mg.maniry.doremii.editor.partition.HtmlExport
import mg.maniry.doremii.editor.views.MeasureView
import mg.maniry.doremii.editor.views.NotesToSpan
import kotlin.math.ceil

object ScanScorePreview {
    fun render(
        container: FlexboxLayout,
        voices: List<ParsedVoice>,
        signature: Int,
        keyIndex: Int,
    ) {
        container.removeAllViews()
        val sig = signature.coerceIn(2, 6)
        val shown = voices.ifEmpty { listOf(ParsedVoice("S", List(sig) { "" }, emptyList())) }
        val longest = shown.maxOf { it.cells.size }.coerceAtLeast(sig)
        val measures = ceil(longest / sig.toFloat()).toInt().coerceAtLeast(1)
        val colors = SolfaColors(container.context)
        val density = container.resources.displayMetrics.density
        val margin = (4 * density).toInt()
        container.addView(
            table(container, colors, shown, sig, keyIndex, measure = null),
            params(margin),
        )
        for (measure in 0 until measures) {
            container.addView(
                table(container, colors, shown, sig, keyIndex, measure),
                params(margin),
            )
        }
    }

    private fun params(margin: Int): FlexboxLayout.LayoutParams {
        return FlexboxLayout.LayoutParams(
            FlexboxLayout.LayoutParams.WRAP_CONTENT,
            FlexboxLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(0, 0, margin, margin)
        }
    }

    private fun table(
        container: FlexboxLayout,
        colors: SolfaColors,
        voices: List<ParsedVoice>,
        signature: Int,
        keyIndex: Int,
        measure: Int?,
    ): MeasureView {
        val view = MeasureView(container.context)
        view.setBackgroundColor(Color.WHITE)
        view.isClickable = false
        view.addView(row(view, headerCells(view, colors, signature, keyIndex, measure)))
        voices.forEach { voice ->
            view.addView(row(view, voiceCells(view, colors, voice, signature, measure)))
        }
        return view
    }

    private fun row(table: MeasureView, cells: List<TextView>): TableRow {
        return TableRow(table.context).apply {
            gravity = Gravity.CENTER
            cells.forEach { addView(it) }
        }
    }

    private fun headerCells(
        table: MeasureView,
        colors: SolfaColors,
        signature: Int,
        keyIndex: Int,
        measure: Int?,
    ): List<TextView> {
        if (measure == null) {
            return listOf(text(table, HtmlExport.notes[keyIndex.coerceIn(0, HtmlExport.notes.lastIndex)], colors.key, 0))
        }
        return beatCells(signature, 0) { index, separator ->
            if (separator) text(table, separator(signature, index), colors.separator, 30)
            else text(table, " ", colors.separator, 30)
        }
    }

    private fun voiceCells(
        table: MeasureView,
        colors: SolfaColors,
        voice: ParsedVoice,
        signature: Int,
        measure: Int?,
    ): List<TextView> {
        if (measure == null) {
            val label = voice.id.ifBlank { "S" }
            return listOf(text(table, label, colors.separator, 0))
        }
        return beatCells(signature, measure * signature) { index, separator ->
            if (separator) {
                text(table, separator(signature, index), colors.separator, 30)
            } else {
                val notes = voice.cells.getOrElse(index) { "" }
                text(table, notes, colors.separator, 30, NotesToSpan.convert(notes))
            }
        }
    }

    private fun beatCells(
        signature: Int,
        start: Int,
        cell: (index: Int, separator: Boolean) -> TextView,
    ): List<TextView> {
        var index = start
        return List(signature * 2 - 1) { position ->
            if (position % 2 == 0) {
                if (position > 0) index++
                cell(index, false)
            } else {
                cell(index, true)
            }
        }
    }

    private fun separator(signature: Int, cellIndex: Int): String {
        return if (signature > 3 && signature % 2 == 0 && cellIndex % 2 == 1) " | " else " : "
    }

    private fun text(
        table: MeasureView,
        value: String,
        color: Int,
        minWidthDp: Int,
        span: CharSequence? = null,
    ): TextView {
        val density = table.resources.displayMetrics.density
        return TextView(table.context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(2, 0, 2, 0)
            setTextColor(color)
            textSize = 16f
            minWidth = (minWidthDp * density).toInt()
            text = span ?: value
            isClickable = false
        }
    }
}
