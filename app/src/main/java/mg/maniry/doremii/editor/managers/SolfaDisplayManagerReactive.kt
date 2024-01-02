package mg.maniry.doremii.editor.managers

import android.graphics.Color
import android.text.SpannableStringBuilder
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
    private var signature: Int = 1
    private var currentLayout = listOf<VirtualTable>()

    fun reRender() {
        val layout = buildLayout()
        renderDiffs(layout)
        currentLayout = layout
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
        val backgroundColor =
            if (index == editorVM.measureToStartPlayer) colors.playerCursor else colors.regularBg
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
        // Click listener
        val onClickListener = OnclickListenerWrapper("$voiceIndex$voiceIndex") {
            editorVM.moveCursor(voiceIndex, cellIndex)
        }
        return VirtualCell(
            text, span, textColor, backgroundColor, 30, onClickListener
        )
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
