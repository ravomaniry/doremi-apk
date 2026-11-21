package mg.maniry.doremii.editor.managers

import android.graphics.Color
import android.graphics.Rect
import android.text.SpannableStringBuilder
import android.view.View
import android.widget.ScrollView
import com.google.android.flexbox.FlexboxLayout
import mg.maniry.doremii.editor.partition.HtmlExport
import mg.maniry.doremii.editor.partition.MeasureHeader
import mg.maniry.doremii.editor.viewModels.EditorViewModel
import mg.maniry.doremii.editor.viewModels.SelectMode
import mg.maniry.doremii.editor.views.NotesToSpan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max


class SolfaDisplayManagerReactive(
    private val editorVM: EditorViewModel,
    private val container: FlexboxLayout,
) {
    private val colors = SolfaColors(container.context)
    private val gesture = SelectionGesture(editorVM, container)
    private var signature: Int = 1
    private var currentLayout = listOf<VirtualTable>()
    private var playSession = false
    private var playheadMeasure: Int? = null

    fun reRender() {
        val layout = buildLayout()
        renderDiffs(layout)
        currentLayout = layout
        playheadMeasure?.let { paintMeasure(it) }
    }

    fun setPlaySession(active: Boolean) {
        if (playSession == active) return
        playSession = active
        val start = editorVM.measureToStartPlayer
        if (!active) {
            val was = playheadMeasure
            playheadMeasure = null
            paintMeasure(was)
            paintMeasure(start)
        } else {
            paintMeasure(start)
        }
    }

    fun setPlayheadMeasure(measure: Int?) {
        if (measure == playheadMeasure) return
        val previous = playheadMeasure
        playheadMeasure = measure
        paintMeasure(previous)
        paintMeasure(measure)
        if (measure != null) reveal(measure)
    }

    private fun buildLayout(): List<VirtualTable> {
        val partitionData = editorVM.partitionData
        signature = partitionData.signature.value!!
        val numOfMeasures = ceil(partitionData.getMaxLength().toDouble() / signature).toInt()
        // 0 is for the S A T B table so we have it -1 for the real measure index
        return List(numOfMeasures + 1) { if (it == 0) buildVoiceIdsTable() else buildVirtualTable(it - 1) }
    }

    private fun buildVoiceIdsTable(): VirtualTable {
        return VirtualTable(
            // row[0] is used for the key so we need offset - 1 for the voices
            rows = List(editorVM.partitionData.voicesNum + 1) {
                VirtualTableRow(
                    cells = listOf(
                        VirtualCell(
                            text = if (it == 0) HtmlExport.notes[editorVM.partitionData.key.value
                                ?: 0] else editorVM.partitionData.voices[it - 1],
                            color = if (it == 0) colors.key else colors.voiceId,
                        ),
                    )
                )
            },
        )
    }

    private fun buildVirtualTable(index: Int): VirtualTable {
        val voicesNum = editorVM.partitionData.voicesNum
        val backgroundColor = colorFor(index)
        val rows = List(voicesNum + 1) {
            if (it == 0) buildTableHeader(index) else buildTableRow(index, it - 1)
        }
        return VirtualTable(backgroundColor, rows, OnclickListenerWrapper("$index") {
            editorVM.onPlayerCursorPosChanged(index)
        })
    }

    private fun buildTableHeader(measureIndex: Int): VirtualTableRow {
        val byPos = mutableMapOf<Int, MeasureHeader>()
        for (event in editorVM.partitionData.changeEvents) {
            val cumulative = byPos[event.position] ?: MeasureHeader(
                event.position, mutableListOf(), mutableListOf()
            )
            cumulative.values.add(event.value)
            cumulative.types.add(event.type)
            byPos[event.position] = cumulative
        }
        var cellIndex = measureIndex * signature
        val cells = List(signature * 2 - 1) {
            if (it % 2 == 0) {
                if (it > 0) {
                    cellIndex++
                }
                return@List buildNoteHeader(byPos, cellIndex)
            }
            return@List buildHeaderSeparator()
        }
        return VirtualTableRow(cells)
    }

    private fun buildHeaderSeparator(): VirtualCell {
        return VirtualCell()
    }

    private fun buildNoteHeader(
        headersByIndex: Map<Int, MeasureHeader>, cellIndex: Int
    ): VirtualCell {
        val header = headersByIndex[cellIndex]
        var text: String? = null
        var span: SpannableStringBuilder? = null
        val cursorCellIndex = editorVM.cursorPos.index
        var color = Color.BLACK
        if (header != null) {
            text = header.values.joinToString(" ")
            span = header.toSpan(colors)
        }
        val isInActiveMeasure =
            floor(cellIndex.toDouble() / signature) == floor(cursorCellIndex.toDouble() / signature)
        // Highlight empty headers in the measure where the cursor is
        if (text == null && isInActiveMeasure) {
            color = colors.cursorBg
            text = "*"
        }
        val onClickListener = OnclickListenerWrapper("header$cellIndex") {
            editorVM.openDialog(cellIndex)
        }
        return VirtualCell(text, span, color, Color.TRANSPARENT, 0, onClickListener)
    }

    private fun buildTableRow(measureIndex: Int, voiceIndex: Int): VirtualTableRow {
        return VirtualTableRow(buildCells(measureIndex, voiceIndex))
    }

    private fun buildCells(measureIndex: Int, voiceIndex: Int): List<VirtualCell> {
        var cellIndex = signature * measureIndex
        return List(signature * 2 - 1) {
            // 1 cell per bar + 1 separator per 2 bars
            if (it % 2 == 0) {
                if (it > 0) {
                    cellIndex++
                }
                buildNoteCell(voiceIndex, cellIndex)
            } else {
                buildSeparatorCell(cellIndex)
            }
        }
    }

    private fun buildSeparatorCell(cellIndex: Int): VirtualCell {
        val text = when {
            // separator: "|" every 4 and ":" every 2
            signature > 3 && signature % 2 == 0 && cellIndex % 2 == 1 -> " | "
            else -> " : "
        }
        return VirtualCell(
            text, null, colors.separator, Color.TRANSPARENT, 30
        )
    }

    private fun buildNoteCell(voiceIndex: Int, cellIndex: Int): VirtualCell {
        val cursorPos = editorVM.cursorPos
        val selectMode = editorVM.selectMode
        val clipBoard = editorVM.clipBoard
        val text = editorVM.partitionData.safelyGetNote(voiceIndex, cellIndex)
        val span = NotesToSpan.convert(text)
        // Colors & BG
        var backgroundColor = Color.TRANSPARENT
        var textColor = colors.separator
        if (selectMode == SelectMode.COPY && clipBoard != null) {
            if (voiceIndex >= clipBoard.start.voice && voiceIndex <= clipBoard.end.voice && cellIndex >= clipBoard.start.index && cellIndex <= clipBoard.end.index) {
                backgroundColor = colors.selectBg
            }
        } else {
            if (voiceIndex == cursorPos.voice && cursorPos.index == cellIndex) {
                backgroundColor = when (editorVM.selectMode) {
                    SelectMode.CURSOR -> colors.cursorBg
                    else -> colors.selectBg
                }
                textColor = when (editorVM.selectMode) {
                    SelectMode.CURSOR -> Color.WHITE
                    else -> Color.DKGRAY
                }
            }
        }
        val cellId = "$voiceIndex:$cellIndex"
        val onClickListener = OnclickListenerWrapper(cellId) {
            editorVM.moveCursor(voiceIndex, cellIndex)
        }
        val onTouchListener = OnTouchListenerWrapper(cellId) { view, event ->
            gesture.onNoteTouch(voiceIndex, cellIndex, view, event)
        }
        return VirtualCell(
            text,
            span,
            textColor,
            backgroundColor,
            30,
            onClickListener,
            onTouchListener = onTouchListener,
            noteRef = NoteCellRef(voiceIndex, cellIndex),
        )
    }

    private fun colorFor(measure: Int): Int {
        if (measure == playheadMeasure) return colors.playhead
        if (!playSession && measure == editorVM.measureToStartPlayer) return colors.playerCursor
        return colors.regularBg
    }

    private fun paintMeasure(measure: Int?) {
        if (measure == null) return
        currentLayout.getOrNull(measure + 1)?.view?.setBackgroundColor(colorFor(measure))
    }

    private fun reveal(measure: Int) {
        val view = currentLayout.getOrNull(measure + 1)?.view ?: return
        view.post {
            if (playheadMeasure != measure || !view.isAttachedToWindow) return@post
            val scroll = findScrollView(view) ?: return@post
            if (!isOffScreen(view, scroll)) return@post
            val delta = scrollDeltaToReveal(view, scroll)
            if (delta != 0) scroll.smoothScrollBy(0, delta)
        }
    }

    /** True when the measure no longer intersects the score viewport. */
    private fun isOffScreen(view: View, scroll: ScrollView): Boolean {
        val visible = Rect()
        if (!view.getGlobalVisibleRect(visible) || visible.isEmpty) return true
        val viewport = Rect()
        if (!scroll.getGlobalVisibleRect(viewport)) return true
        return !Rect.intersects(visible, viewport)
    }

    /**
     * Smallest scroll that brings the whole measure into the viewport.
     * A measure taller than the viewport is aligned to the top.
     */
    private fun scrollDeltaToReveal(view: View, scroll: ScrollView): Int {
        val viewLoc = IntArray(2)
        val scrollLoc = IntArray(2)
        view.getLocationOnScreen(viewLoc)
        scroll.getLocationOnScreen(scrollLoc)
        val viewTop = viewLoc[1]
        val viewBottom = viewTop + view.height
        val viewportTop = scrollLoc[1] + scroll.paddingTop
        val viewportBottom = scrollLoc[1] + scroll.height - scroll.paddingBottom
        val viewportHeight = viewportBottom - viewportTop
        if (viewportHeight <= 0) return 0
        if (view.height >= viewportHeight) return viewTop - viewportTop
        val minDelta = viewBottom - viewportBottom
        val maxDelta = viewTop - viewportTop
        return when {
            maxDelta < 0 -> maxDelta
            minDelta > 0 -> minDelta
            else -> 0
        }
    }

    private fun findScrollView(view: View): ScrollView? {
        var parent = view.parent
        while (parent != null) {
            if (parent is ScrollView) return parent
            parent = parent.parent
        }
        return null
    }

    private fun renderDiffs(newLayout: List<VirtualTable>) {
        for (i in 0 until max(newLayout.size, currentLayout.size)) {
            if (i >= currentLayout.size) {
                //  new measure
                VirtualTable.create(container).renderDiff(newLayout[i])
            } else if (i >= newLayout.size) {
                // deleted measure
                container.removeView(currentLayout[i].view)
            } else {
                // existing measure
                currentLayout[i].renderDiff(newLayout[i])
            }
        }
    }
}
