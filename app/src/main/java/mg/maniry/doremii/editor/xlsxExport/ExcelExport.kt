package mg.maniry.doremii.editor.xlsxExport

import mg.maniry.doremii.commonUtils.FileManager
import mg.maniry.doremii.editor.partition.ChangeEvent
import mg.maniry.doremii.editor.partition.PartitionData
import mg.maniry.doremii.editor.views.NotesToSpan
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.Font
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.PageMargin
import org.apache.poi.ss.usermodel.PrintSetup
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFFont
import org.apache.poi.xssf.usermodel.XSSFRichTextString
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt


class ExcelExport {
    fun export(partitionData: PartitionData) {
        try {
            val bytes = ByteArrayOutputStream().use { out ->
                build(partitionData).use { workbook -> workbook.write(out) }
                out.toByteArray()
            }
            FileManager.writeDownload(
                "${partitionData.songInfo.filename}.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                bytes
            )
        } catch (e: Exception) {
            print(e)
        }
    }


    fun build(partitionData: PartitionData): XSSFWorkbook {
        ensurePoi()
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Doremi")
        val fonts = ScoreFonts(workbook)
        val styles = ScoreStyles(workbook, fonts)
        val layout = columnLayout(partitionData)

        layout.cellWidths.forEachIndexed { index, width ->
            sheet.setColumnWidth(index, (width * 256).roundToInt())
        }

        val scoreColumns = scoreColumnCount(partitionData, layout.measurePerRow)
        val scoreStart = writeSongHeader(sheet, partitionData, scoreColumns, styles, fonts)
        val afterLyrics = writeNotes(
            sheet,
            partitionData,
            layout.measurePerRow,
            styles,
            fonts,
            scoreStart
        )
        writeCredits(sheet, partitionData, afterLyrics, scoreColumns, styles)
        pageSetup(sheet, partitionData)
        return workbook
    }


    private fun writeSongHeader(
        sheet: XSSFSheet,
        partitionData: PartitionData,
        scoreColumns: Int,
        styles: ScoreStyles,
        fonts: ScoreFonts
    ): Int {
        val lastCol = scoreColumns - 1
        val titleRow = sheet.createRow(0)
        titleRow.heightInPoints = 24f
        titleRow.createCell(0).apply {
            cellStyle = styles.title
            setCellValue(partitionData.songInfo.title.value.orEmpty())
        }
        merge(sheet, 0, lastCol)

        var row = 1
        val structureRow = sheet.createRow(row)
        structureRow.createCell(0).apply {
            cellStyle = styles.bold
            setCellValue("${partitionData.signature.value}/4")
        }
        val singer = partitionData.songInfo.singer.value.orEmpty()
        if (singer.isNotEmpty()) {
            structureRow.createCell(lastCol).apply {
                cellStyle = styles.right
                setCellValue(singer)
            }
        }
        row++

        sheet.createRow(row++).createCell(0).apply {
            cellStyle = styles.bold
            setCellValue("Do dia ${KEY_NAMES[partitionData.key.value!!]}")
        }
        sheet.createRow(row++).createCell(0).apply {
            cellStyle = styles.bold
            setCellValue("Tempo: ${partitionData.tempo} bpm")
        }
        if (partitionData.swing.value == true) {
            sheet.createRow(row++).createCell(0).apply {
                cellStyle = styles.italic
                setCellValue(swingLabel(fonts))
            }
        }
        return row + 1
    }


    private fun writeCredits(
        sheet: XSSFSheet,
        partitionData: PartitionData,
        afterLyrics: Int,
        scoreColumns: Int,
        styles: ScoreStyles
    ) {
        val lines = with(partitionData.songInfo) {
            listOfNotNull(
                author.value?.takeIf { it.isNotEmpty() }?.let { "Tonony: $it" },
                compositor.value?.takeIf { it.isNotEmpty() }?.let { "Feony: $it" },
                releaseDate.value?.takeIf { it.isNotEmpty() }
            )
        }
        lines.forEachIndexed { index, line ->
            val rowIndex = afterLyrics + 1 + index
            sheet.createRow(rowIndex).createCell(0).apply {
                cellStyle = styles.rightItalic
                setCellValue(line)
            }
            merge(sheet, rowIndex, scoreColumns - 1)
        }
    }


    private fun swingLabel(fonts: ScoreFonts): XSSFRichTextString {
        val label = "Swing"
        val text = "$label $SWING_NOTE"
        return XSSFRichTextString(text).apply {
            applyFont(0, label.length, fonts.boldItalic)
            applyFont(label.length, label.length + 1, fonts.italic)
            applyFont(label.length + 1, text.length, fonts.smallItalic)
        }
    }


    private fun merge(sheet: XSSFSheet, row: Int, lastCol: Int) {
        if (lastCol > 0) {
            sheet.addMergedRegion(CellRangeAddress(row, row, 0, lastCol))
        }
    }


    private fun scoreColumnCount(partitionData: PartitionData, measurePerRow: Int): Int {
        val signature = partitionData.signature.value!!
        return (measurePerRow * (signature * 2 - 1)).coerceAtLeast(1)
    }


    private fun writeNotes(
        sheet: XSSFSheet,
        partitionData: PartitionData,
        measurePerRow: Int,
        styles: ScoreStyles,
        fonts: ScoreFonts,
        startRow: Int
    ): Int {
        val groups = groupNotesPerMeasure(partitionData, measurePerRow)
        val signature = partitionData.signature.value!!
        val notesPerGroup = measurePerRow * signature
        val rowSize = partitionData.notes.size + 3
        var lastRow = 0

        groups.forEachIndexed { groupIndex, row ->
            val header = sheet.createRow(startRow + groupIndex * rowSize)
            for (measureIndex in row.measures.indices) {
                for (timeIndex in 0 until signature) {
                    val changePosition =
                        notesPerGroup * groupIndex + measureIndex * signature + timeIndex
                    val events = partitionData.changeEvents
                        .filter { it.position == changePosition }
                        .joinToString(" ") {
                            when (it.type) {
                                ChangeEvent.MOD -> "Do=${it.value}"
                                ChangeEvent.TEMPO -> "T=${it.value}"
                                else -> it.value
                            }
                        }
                    if (events != "") {
                        val colIndex = (signature + signature - 1) * measureIndex + timeIndex * 2
                        header.createCell(colIndex).apply {
                            cellStyle = styles.plain
                            setCellValue(events)
                        }
                    }
                }
            }

            for (voiceIndex in partitionData.notes.indices) {
                val rowIndex = startRow + groupIndex * rowSize + voiceIndex + 1
                lastRow = rowIndex + 1
                val voiceRow = sheet.createRow(rowIndex)
                row.measures.forEachIndexed { measureIndex, measure ->
                    for (timeIndex in 0 until signature) {
                        val colIndex = (signature + signature - 1) * measureIndex + timeIndex * 2
                        val content = measure.voices.getOrNull(voiceIndex)?.getOrNull(timeIndex).orEmpty()
                        voiceRow.createCell(colIndex).apply {
                            cellStyle = when (timeIndex) {
                                0 -> styles.leftBorder
                                signature - 1 -> styles.rightBorder
                                else -> styles.plain
                            }
                            if (content.length <= 1) {
                                if (content.isNotEmpty()) {
                                    setCellValue(content)
                                }
                            } else {
                                setCellValue(richNote(content, fonts))
                            }
                        }
                        if (timeIndex < signature - 1) {
                            val separator = if (signature % 2 == 0 && timeIndex % 2 == 1) "|" else ":"
                            voiceRow.createCell(colIndex + 1).apply {
                                cellStyle = styles.plain
                                setCellValue(separator)
                            }
                        }
                    }
                }
            }
        }

        var lyricRowNumber = lastRow + 2
        partitionData.lyrics.value?.split('\n')?.forEach { line ->
            lyricRowNumber++
            sheet.createRow(lyricRowNumber - 1).createCell(0).apply {
                cellStyle = styles.plain
                setCellValue(line)
            }
        }
        return lyricRowNumber
    }


    private fun richNote(content: String, fonts: ScoreFonts): XSSFRichTextString {
        val runs = octaveRuns(content)
        val rich = XSSFRichTextString(runs.joinToString("") { it.text })
        var cursor = 0
        runs.forEach { run ->
            val end = cursor + run.text.length
            val font = when (run.offset) {
                Font.SS_SUPER -> fonts.superscript
                Font.SS_SUB -> fonts.subscript
                else -> fonts.base
            }
            rich.applyFont(cursor, end, font)
            cursor = end
        }
        return rich
    }


    private fun octaveRuns(strNotes: String): List<TextRun> {
        val pieces = mutableListOf<TextPiece>()
        var current = TextPiece("", 0)
        pieces.add(current)
        var skipNext = false

        for (i in strNotes.indices) {
            if (skipNext) {
                skipNext = false
                continue
            }
            val symbol = strNotes[i]
            if (symbol in NotesToSpan.symbols) {
                if (symbol == '-' && i < strNotes.lastIndex && strNotes[i + 1] in NotesToSpan.symbols) {
                    current = TextPiece("", 0)
                    pieces.add(TextPiece(strNotes[i + 1].toString(), -1))
                    pieces.add(current)
                    skipNext = true
                } else if (symbol == '-') {
                    current.value += symbol
                } else {
                    current = TextPiece("", 0)
                    pieces.add(TextPiece(symbol.toString(), 1))
                    pieces.add(current)
                }
            } else {
                current.value += symbol
            }
        }

        return pieces.filter { it.value.isNotEmpty() }.map {
            TextRun(
                it.value,
                when (it.type) {
                    1 -> Font.SS_SUPER
                    -1 -> Font.SS_SUB
                    else -> Font.SS_NONE
                }
            )
        }
    }


    private fun pageSetup(sheet: XSSFSheet, partitionData: PartitionData) {
        sheet.fitToPage = false
        sheet.printSetup.paperSize = PrintSetup.LETTER_PAPERSIZE
        sheet.printSetup.scale = 96.toShort()
        sheet.printSetup.landscape = false
        sheet.setMargin(PageMargin.LEFT, 0.4)
        sheet.setMargin(PageMargin.RIGHT, 0.4)
        sheet.setMargin(PageMargin.TOP, 1.0)
        sheet.setMargin(PageMargin.BOTTOM, 1.0)
        sheet.setMargin(PageMargin.HEADER, 0.4)
        sheet.setMargin(PageMargin.FOOTER, 0.4)
        sheet.horizontallyCenter = true
        if (!sheet.ctWorksheet.isSetHeaderFooter) {
            sheet.ctWorksheet.addNewHeaderFooter()
        }
        sheet.ctWorksheet.headerFooter.oddHeader = partitionData.songInfo.filename
    }


    private fun columnLayout(partitionData: PartitionData): ColumnLayout {
        val signature = partitionData.signature.value!!
        val beatWidths = FloatArray(signature)
        partitionData.notes.forEach { voiceNotes ->
            voiceNotes.forEachIndexed { index, note ->
                val beat = index % signature
                val required = requiredWidth(note)
                if (beatWidths[beat] < required) {
                    beatWidths[beat] = required
                }
            }
        }

        val oneMeasure = mutableListOf<Float>()
        var measureWidth = 0f
        beatWidths.forEachIndexed { index, width ->
            measureWidth += width
            oneMeasure.add(width)
            if (index + 1 != signature) {
                measureWidth += 1
                oneMeasure.add(1f)
            }
        }

        var measurePerRow = 0
        while ((measurePerRow + 1) * measureWidth + 1 <= MAX_PAGE_WIDTH) {
            measurePerRow++
        }

        val cellWidths = mutableListOf<Float>()
        cellWidths.addAll(oneMeasure)
        repeat(measurePerRow + 1) {
            cellWidths.addAll(oneMeasure)
        }
        return ColumnLayout(cellWidths, measurePerRow)
    }


    private fun requiredWidth(note: String): Float {
        return when (note.length) {
            0 -> 2.4f
            1 -> 2.8f
            2 -> 3.2f
            3 -> 3.8f
            4 -> 4.2f
            5 -> 4.8f
            6 -> 5.2f
            else -> 1.8f * 0.3f * note.length
        }
    }


    private fun groupNotesPerMeasure(partitionData: PartitionData, measurePerRow: Int): List<NoteRow> {
        val groups = mutableListOf<NoteRow>()
        val signature = partitionData.signature.value!!
        var measureIndex = -1
        var row = NoteRow()

        for (index in 0 until partitionData.getMaxLength() + 1) {
            if (index % signature == 0) {
                measureIndex++
                if (measureIndex % measurePerRow == 0) {
                    row = NoteRow()
                    groups.add(row)
                }
                row.measures.add(NoteMeasure(partitionData.notes.size))
            }
            val measure = row.measures.last()
            partitionData.notes.forEachIndexed { voice, voiceNotes ->
                measure.voices[voice].add(if (voiceNotes.size > index) voiceNotes[index] else "")
            }
        }
        return groups
    }


    private class ScoreFonts(workbook: XSSFWorkbook) {
        val base: XSSFFont = arial(workbook, 10, Font.SS_NONE)
        val superscript: XSSFFont = arial(workbook, 10, Font.SS_SUPER)
        val subscript: XSSFFont = arial(workbook, 10, Font.SS_SUB)
        val title: XSSFFont = arial(workbook, 18, Font.SS_NONE, isBold = true)
        val bold: XSSFFont = arial(workbook, 10, Font.SS_NONE, isBold = true)
        val italic: XSSFFont = arial(workbook, 10, Font.SS_NONE, isItalic = true)
        val boldItalic: XSSFFont = arial(workbook, 10, Font.SS_NONE, isBold = true, isItalic = true)
        val smallItalic: XSSFFont = arial(workbook, 8, Font.SS_NONE, isItalic = true)

        private fun arial(
            workbook: XSSFWorkbook,
            size: Short,
            offset: Short,
            isBold: Boolean = false,
            isItalic: Boolean = false
        ): XSSFFont {
            return workbook.createFont().apply {
                fontName = "Arial"
                fontHeightInPoints = size
                typeOffset = offset
                bold = isBold
                italic = isItalic
            }
        }
    }


    private class ScoreStyles(workbook: XSSFWorkbook, fonts: ScoreFonts) {
        val plain: XSSFCellStyle = textStyle(workbook, fonts.base, HorizontalAlignment.GENERAL)
        val title: XSSFCellStyle = textStyle(workbook, fonts.title, HorizontalAlignment.CENTER)
        val bold: XSSFCellStyle = textStyle(workbook, fonts.bold, HorizontalAlignment.LEFT)
        val italic: XSSFCellStyle = textStyle(workbook, fonts.italic, HorizontalAlignment.LEFT)
        val right: XSSFCellStyle = textStyle(workbook, fonts.base, HorizontalAlignment.RIGHT)
        val rightItalic: XSSFCellStyle = textStyle(workbook, fonts.italic, HorizontalAlignment.RIGHT)
        val leftBorder: XSSFCellStyle = workbook.createCellStyle().apply {
            setFont(fonts.base)
            alignment = HorizontalAlignment.CENTER
            borderLeft = BorderStyle.HAIR
        }
        val rightBorder: XSSFCellStyle = workbook.createCellStyle().apply {
            setFont(fonts.base)
            alignment = HorizontalAlignment.CENTER
            borderRight = BorderStyle.HAIR
        }

        private fun textStyle(
            workbook: XSSFWorkbook,
            font: XSSFFont,
            align: HorizontalAlignment
        ): XSSFCellStyle {
            return workbook.createCellStyle().apply {
                setFont(font)
                alignment = align
            }
        }
    }


    private class ColumnLayout(val cellWidths: List<Float>, val measurePerRow: Int)

    private class NoteRow {
        val measures = mutableListOf<NoteMeasure>()
    }

    private class NoteMeasure(voices: Int) {
        val voices = MutableList(voices) { mutableListOf<String>() }
    }

    private class TextPiece(var value: String, val type: Int)

    private data class TextRun(val text: String, val offset: Short)

    companion object {
        private val KEY_NAMES = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
        private const val SWING_NOTE = "(vakiana hoe \"d.-.d\" ny \"d.d\" sy ny \"d.,d\")"
        private const val MAX_PAGE_WIDTH = 94
        private var poiReady = false

        private fun ensurePoi() {
            if (poiReady) return
            System.setProperty(
                "org.apache.poi.javax.xml.stream.XMLInputFactory",
                "com.fasterxml.aalto.stax.InputFactoryImpl"
            )
            System.setProperty(
                "org.apache.poi.javax.xml.stream.XMLOutputFactory",
                "com.fasterxml.aalto.stax.OutputFactoryImpl"
            )
            System.setProperty(
                "org.apache.poi.javax.xml.stream.XMLEventFactory",
                "com.fasterxml.aalto.stax.EventFactoryImpl"
            )
            poiReady = true
        }
    }
}
