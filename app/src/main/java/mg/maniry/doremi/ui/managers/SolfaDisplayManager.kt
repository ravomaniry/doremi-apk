package mg.maniry.doremi.ui.managers

import android.arch.lifecycle.Observer
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.android.flexbox.FlexboxLayout
import mg.maniry.doremi.R.id.*
import mg.maniry.doremi.ui.MainActivity
import mg.maniry.doremi.ui.views.MeasureView
import mg.maniry.doremi.ui.views.NotesToSpan
import mg.maniry.doremi.viewModels.EditorViewModel
import mg.maniry.doremi.partition.MeasureHeader
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread


class SolfaDisplayManager constructor(
        private val mainContext: Context,
        mainView: View,
        private val editorVM: EditorViewModel) {

    private val partitionData = editorVM.partitionData
    private val viewsCont = mainView.findViewById<FlexboxLayout>(partition_cont)
    private var textViews = Array(4) { mutableListOf<TextView>() }
    private var headerTextViews = mutableListOf<TextView>()
    private var currentSize = 0
    private var playerCursor: TableLayout? = null


    init {
        observeReRender()
        observeUpdate()
        observeCursor()
    }


    fun movePlayerCursor(table: TableLayout?): Boolean {
        playerCursor?.setBackgroundColor(Color.WHITE)
        playerCursor = table
                ?.apply { setBackgroundColor(Color.rgb(200, 255, 205)) }
                ?.also { editorVM.playerCursorPosition = table.tag as Int }
        return true
    }


    private fun observeUpdate() {
        editorVM.updatedCell.observe(mainContext as MainActivity, Observer {
            if (it != null)
                with(it) {
                    createTables(index + 1)
                    textViews[voice][index].text = NotesToSpan.convert(content)
                }
        })

        editorVM.headerTvTrigger.observe(mainContext, Observer {
            if (it != null)
                printHeaders()
        })
    }


    private fun observeCursor() {
        editorVM.cursorPos.observe(mainContext as MainActivity, Observer {
            with(it) {
                if (this != null && textViews[voice].size > index)
                    textViews[voice][index].setBackgroundColor(Color.YELLOW)
            }

            with(editorVM.prevCursorPos) {
                if (this != null && textViews[voice].size > index)
                    textViews[voice][index].setBackgroundColor(Color.TRANSPARENT)
            }
        })
    }


    private fun observeReRender() {
        editorVM.partitionData.signature.observe(mainContext as MainActivity, Observer {
            it?.run { reRender() }
        })
    }


    private fun reRender() {
        currentSize = 0
        textViews = Array(4) { mutableListOf<TextView>() }
        headerTextViews = mutableListOf()
        viewsCont.apply {
            removeAllViews()
            addView(ProgressBar(mainContext))
        }

        doAsync {
            val tables = createTables(addView = false)

            partitionData.notes.forEachIndexed { voice, notes ->
                notes.forEachIndexed { index, note ->
                    if (note != "" && note != " ")
                        textViews[voice][index].text = NotesToSpan.convert(note)
                }
            }

            with(editorVM.cursorPos.value) {
                this?.run { textViews[voice][index].setBackgroundColor(Color.YELLOW) }
            }

            uiThread {
                viewsCont.removeAllViews()
                printHeaders()
                tables.forEach { table -> addMeasureTable(table) }
            }
        }
    }


    private fun createTables(maxSize: Int = partitionData.getMaxLength(), addView: Boolean = true): MutableList<TableLayout> {
        val tables = mutableListOf<TableLayout>()

        while (currentSize < maxSize || currentSize == 0) {
            MeasureView(mainContext)
                    .apply {
                        setBackgroundColor(Color.WHITE)
                        tag = currentSize
                        setOnClickListener { movePlayerCursor(this) }
                    }
                    .also {
                        addTableCells(it, currentSize)
                        if (addView)
                            addMeasureTable(it)
                        else
                            tables.add(it)
                    }

            currentSize += partitionData.signature.value!!
        }

        return tables
    }


    private fun addTableCells(table: TableLayout, currentSize: Int) {
        val signature = partitionData.signature.value!!
        table.addView(TableRow(mainContext).apply {
            gravity = Gravity.CENTER

            (0..(signature - 1)).forEach { i ->
                addView(TextView(mainContext)
                        .apply {
                            minWidth = 30
                            setOnClickListener { editorVM.openDialog(currentSize + i) }
                        }
                        .also { tv -> headerTextViews.add(tv) })

                if (i != signature - 1)
                    addView(TextView(mainContext))
            }
        })

        Array(4) { TableRow(mainContext) }.forEachIndexed { voice, tRow ->
            for (i in 0..(signature - 1)) {
                tRow.addView(TextView(mainContext)
                        .apply {
                            minWidth = 30
                            gravity = Gravity.CENTER_HORIZONTAL
                            setOnClickListener { editorVM.moveCursor(voice, currentSize + i) }
                            textViews[voice].add(this)
                        }
                )

                if (i != signature - 1) {
                    tRow.addView(TextView(mainContext).apply {
                        setTextColor(Color.rgb(40, 100, 50))
                        text = if (signature % 2 == 0 && i + 1 == signature / 2) " | " else " : "
                    })
                }
            }

            table.addView(tRow)
        }
    }


    private fun printHeaders() {
        val headers = mutableListOf<MeasureHeader>()
        var currentPosition = -1

        partitionData.changeEvents.forEach {
            if (currentPosition == it.position) {
                headers[headers.size - 1].apply {
                    text += " ${it.value}"
                    types.add(it.type)
                }

            } else {
                headers.add(MeasureHeader(it.position, it.value, mutableListOf(it.type)))
                currentPosition = it.position
            }
        }

        headerTextViews.forEachIndexed { i, textView ->
            val scopeHeader = headers.find { h -> h.position == i }
            if (scopeHeader == null) {
                textView.text = ""
            } else {
                textView.text = scopeHeader.toSpan()
            }
        }
    }


    private fun addMeasureTable(table: TableLayout) {
        viewsCont.addView(table)
    }
}
