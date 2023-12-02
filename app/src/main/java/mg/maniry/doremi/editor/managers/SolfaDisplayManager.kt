package mg.maniry.doremi.editor.managers

import android.content.Context
import android.graphics.Color
import android.support.v4.content.ContextCompat
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.android.flexbox.FlexboxLayout
import mg.maniry.doremi.R.id.*
import mg.maniry.doremi.R
import mg.maniry.doremi.editor.EditorActivity
import mg.maniry.doremi.editor.partition.HtmlExport
import mg.maniry.doremi.editor.views.MeasureView
import mg.maniry.doremi.editor.views.NotesToSpan
import mg.maniry.doremi.editor.viewModels.EditorViewModel
import mg.maniry.doremi.editor.partition.MeasureHeader
import mg.maniry.doremi.editor.viewModels.SelectMode
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread


class SolfaDisplayManager constructor(
    private val mainContext: Context, mainView: View, private val editorVM: EditorViewModel
) {

    private val partitionData = editorVM.partitionData
    private val viewsCont = mainView.findViewById<FlexboxLayout>(partition_cont)
    private var textViews = Array(partitionData.voices.size) { mutableListOf<TextView>() }
    private var headerTextViews = mutableListOf<TextView>()
    private var currentSize = 0
    private var playerCursor: TableLayout? = null
    private val cursorBgColor = ContextCompat.getColor(mainContext, R.color.colorPrimary)
    private val selectBgColor = ContextCompat.getColor(mainContext, R.color.colorAccent)
    private val voiceIdColor = ContextCompat.getColor(mainContext, R.color.gray)
    private val separatorColor = ContextCompat.getColor(mainContext, R.color.dark_gray)
    private val accentColor = ContextCompat.getColor(mainContext, R.color.colorAccent)
    private val accentColorLight = ContextCompat.getColor(mainContext, R.color.colorAccentLight)
    private val regularBg = Color.TRANSPARENT
    private var isRendering = false
    private var shouldRerender = false
    private val selectedTextViews = mutableListOf<TextView>()
    private val addButton = View.inflate(mainContext, R.layout.add_measure_btn, null).apply {
        findViewById<ImageView>(add_measure_btn).setOnClickListener {
            addMeasure()
        }
    }


    init {
        observeReRender()
        observeUpdate()
        observeCursor()
        observeSelectMode()
    }


    fun movePlayerCursor(table: TableLayout?): Boolean {
        playerCursor?.setBackgroundColor(Color.WHITE)
        playerCursor = table
            ?.apply { setBackgroundColor(accentColorLight) }
            ?.also { editorVM.playerCursorPosition = table.tag as Int }
        return true
    }


    private fun observeUpdate() {
        editorVM.updatedCells.observe(mainContext as EditorActivity) {
            it?.forEach { cell ->
                cell?.run {
                    createTables(index + 1)
                    textViews[voice][index].text = NotesToSpan.convert(content)
                }
            }
        }

        editorVM.headerTvTrigger.observe(mainContext) {
            if (it != null) {
                printHeaders()
            }
        }
    }


    private fun observeCursor() {
        editorVM.cursorPos.observe(mainContext as EditorActivity) {
            it?.run { placeCursorOn(voice, index) }
            editorVM.prevCursorPos?.run { removeCursorFrom(voice, index) }
            highlightClipBoard()
        }
    }


    private fun observeSelectMode() {
        editorVM.selectMode.observe(mainContext as EditorActivity) {
            highlightClipBoard()
            if (it == SelectMode.CURSOR) {
                editorVM.cursorPos.value?.run { placeCursorOn(voice, index) }
            }
        }
    }


    private fun highlightClipBoard() {
        selectedTextViews.removeAll {
            it.setBackgroundColor(regularBg)
            true
        }
        if (editorVM.selectMode.value == SelectMode.COPY) {
            editorVM.clipBoard?.run {
                editorVM.cursorPos.value?.run { removeCursorFrom(voice, index) }
                (start.voice until end.voice + 1).forEach { voiceIndex ->
                    (start.index until end.index + 1).forEach { noteIndex ->
                        textViews[voiceIndex][noteIndex].run {
                            selectedTextViews.add(this)
                            setBackgroundColor(selectBgColor)
                        }
                    }
                }
            }
        }
    }


    private fun observeReRender() {
        editorVM.partitionData.signature.observe(mainContext as EditorActivity) {
            it?.run { reRender() }
        }
    }


    private fun addMeasure() {
        createTables(partitionData.getMaxLength() + (editorVM.partitionData.signature.value ?: 4))
    }


    private fun voiceIdsTable(): TableLayout {
        return TableLayout(mainContext).apply {
            addView(TableRow(mainContext).apply {
                addView(TextView(mainContext).apply {
                    setTextColor(accentColor)
                    text = when (partitionData.key.value) {
                        null -> ""
                        else -> HtmlExport.notes[partitionData.key.value!!]
                    }
                })
            })

            partitionData.voices.forEach {
                addView(TableRow(mainContext).apply {
                    addView(TextView(mainContext).apply {
                        text = it
                        setTextColor(voiceIdColor)
                    })
                })
            }
        }
    }


    private fun reRender() {
        if (isRendering) {
            shouldRerender = true
        } else {
            isRendering = true
            currentSize = 0
            textViews = Array(partitionData.voices.size) { mutableListOf<TextView>() }
            headerTextViews = mutableListOf()
            viewsCont.apply {
                removeAllViews()
                addView(ProgressBar(mainContext))
            }

            doAsync {
                val tables = createTables(addView = false)

                partitionData.voices.forEachIndexed { voice, _ ->
                    val voiceNotes = partitionData.notes[voice]
                    voiceNotes.forEachIndexed { index, note ->
                        if (note != "" && note != " ") {
                            textViews[voice][index].text = NotesToSpan.convert(note)
                        }
                    }
                }

                with(editorVM.cursorPos.value) {
                    this?.run { placeCursorOn(voice, index) }
                }

                uiThread {
                    isRendering = false
                    if (shouldRerender) {
                        shouldRerender = false
                        reRender()
                    } else {
                        viewsCont.apply {
                            removeAllViews()
                            addView(voiceIdsTable())
                            addView(addButton)
                        }
                        printHeaders()
                        tables.forEach { table -> addMeasureTable(table) }
                    }
                }
            }
        }
    }


    private fun createTables(
        maxSize: Int = partitionData.getMaxLength(), addView: Boolean = true
    ): MutableList<TableLayout> {
        val tables = mutableListOf<TableLayout>()

        while (currentSize < maxSize || currentSize == 0) {
            MeasureView(mainContext).apply {
                setBackgroundColor(Color.WHITE)
                tag = currentSize
                setOnClickListener { movePlayerCursor(this) }
            }.also {
                addTableCells(it, currentSize)
                if (addView) {
                    addMeasureTable(it)
                } else {
                    tables.add(it)
                }
            }

            currentSize += partitionData.signature.value!!
        }

        return tables
    }


    private fun addTableCells(table: TableLayout, currentSize: Int) {
        val signature = partitionData.signature.value!!
        table.addView(TableRow(mainContext).apply {
            gravity = Gravity.CENTER

            (0 until signature).forEach { i ->
                addView(TextView(mainContext).apply {
                    minWidth = 30
                    setOnClickListener { editorVM.openDialog(currentSize + i) }
                }.also { tv -> headerTextViews.add(tv) })
                if (i != signature - 1) {
                    addView(TextView(mainContext))
                }
            }
        })

        Array(partitionData.voicesNum) { TableRow(mainContext) }.forEachIndexed { voice, tRow ->
            for (i in 0 until signature) {
                tRow.addView(TextView(mainContext).apply {
                    minWidth = 30
                    gravity = Gravity.CENTER_HORIZONTAL
                    setTextColor(Color.DKGRAY)
                    setOnClickListener { selectCell(voice, currentSize + i) }
                }.also {
                    textViews[voice].add(it)
                })

                if (i != signature - 1) {
                    tRow.addView(TextView(mainContext).apply {
                        setTextColor(separatorColor)
                        text = when {
                            signature > 3 && signature % 2 == 0 && i + 1 == signature / 2 -> " | "
                            else -> " : "
                        }
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
        viewsCont.addView(table, viewsCont.childCount - 1)
    }


    private fun placeCursorOn(voice: Int, index: Int) {
        if (textViews.size > voice && textViews[voice].size > index) {
            textViews[voice][index].apply {
                val bg = when (editorVM.selectMode.value) {
                    SelectMode.CURSOR -> cursorBgColor
                    else -> selectBgColor
                }
                val fg = when (editorVM.selectMode.value) {
                    SelectMode.CURSOR -> Color.WHITE
                    else -> Color.DKGRAY
                }
                setBackgroundColor(bg)
                setTextColor(fg)
            }
        }
    }


    private fun removeCursorFrom(voice: Int, index: Int) {
        if (textViews.size > voice && textViews[voice].size > index) {
            textViews[voice][index].apply {
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.BLACK)
            }
        }
    }


    private fun selectCell(voice: Int, index: Int) {
        editorVM.moveCursor(voice, index)
    }
}
