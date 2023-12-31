package mg.maniry.doremi.editor.managers

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.View
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import com.google.android.flexbox.FlexboxLayout
import mg.maniry.doremi.editor.views.MeasureView
import kotlin.math.max

class OnclickListenerWrapper(val id: String, val listener: () -> Unit)

abstract class VirtualView<T : View>(
    var view: T?,
    private val onClickListener: OnclickListenerWrapper? = null,
    private val backgroundColor: Int? = null,
    private val minWidth: Int? = null
) {
    open fun renderDiff(newValue: VirtualView<T>) {
        newValue.view = view
        val v = view as View
        if (newValue.onClickListener?.id != onClickListener?.id) {
            if (newValue.onClickListener == null) {
                v.setOnClickListener(null)
            } else {
                v.setOnClickListener { newValue.onClickListener.listener() }
            }
        }
        if (newValue.backgroundColor != backgroundColor) {
            v.setBackgroundColor(newValue.backgroundColor ?: Color.TRANSPARENT)
        }
        if (newValue.minWidth != minWidth) {
            v.minimumWidth = newValue.minWidth ?: 0
        }
    }
}

class VirtualTable(
    backgroundColor: Int = Color.TRANSPARENT,
    private val rows: List<VirtualTableRow>,
    onClickListener: OnclickListenerWrapper? = null,
    view: MeasureView? = null,
) : VirtualView<MeasureView>(view, onClickListener, backgroundColor) {
    companion object {
        fun create(container: FlexboxLayout): VirtualTable {
            val view = MeasureView(container.context)
            container.addView(view, container.childCount - 1)
            return VirtualTable(Color.WHITE, listOf(), null, view)
        }
    }

    fun renderDiff(newValue: VirtualTable) {
        super.renderDiff(newValue)
        for (i in 0 until max(newValue.rows.size, rows.size)) {
            if (i >= rows.size) {
                // new row
                VirtualTableRow.create(view!!).renderDiff(newValue.rows[i])
            } else if (i >= newValue.rows.size) {
                // deleted row
                view?.removeView(rows[i].view)
            } else {
                // updated row
                rows[i].renderDiff(newValue.rows[i])
            }
        }
    }
}

class VirtualTableRow(
    private val cells: List<VirtualCell>,
    view: TableRow? = null,
) : VirtualView<TableRow>(view) {
    companion object {
        fun create(container: TableLayout): VirtualTableRow {
            val view = TableRow(container.context)
            view.gravity = Gravity.CENTER
            container.addView(view)
            return VirtualTableRow(listOf(), view)
        }
    }

    fun renderDiff(newRow: VirtualTableRow) {
        super.renderDiff(newRow)
        for (i in 0 until max(newRow.cells.size, cells.size)) {
            if (i >= cells.size) {
                // new cell
                VirtualCell.create(view!!).renderDiff(newRow.cells[i])
            } else if (i >= newRow.cells.size) {
                // deleted cell
                view?.removeView(cells[i].view)
            } else {
                // updated cell
                cells[i].renderDiff(newRow.cells[i])
            }
        }
    }
}

class VirtualCell(
    private val text: String? = null,
    private val span: SpannableStringBuilder? = null,
    private val color: Int = Color.BLACK,
    backgroundColor: Int = Color.TRANSPARENT,
    minWidth: Int = 0,
    onClickListener: OnclickListenerWrapper? = null,
    view: TextView? = null,
) : VirtualView<TextView>(view, onClickListener, backgroundColor, minWidth) {
    companion object {
        fun create(container: TableRow): VirtualCell {
            val view = TextView(container.context)
            view.gravity = Gravity.CENTER_HORIZONTAL
            view.setPadding(2, 0, 2, 0)
            container.addView(view)
            return VirtualCell(view = view)
        }
    }

    fun renderDiff(newValue: VirtualCell) {
        super.renderDiff(newValue)
        if (newValue.text != text) {
            view!!.text = newValue.span ?: newValue.text ?: ""
        }
        if (newValue.color != color) {
            view!!.setTextColor(newValue.color)
        }
    }
}
