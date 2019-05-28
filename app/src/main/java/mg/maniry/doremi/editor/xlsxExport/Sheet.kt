package mg.maniry.doremi.editor.xlsxExport

import android.util.Xml
import mg.maniry.doremi.editor.partition.PartitionData
import org.xmlpull.v1.XmlSerializer
import java.io.StringWriter


class Sheet(private val shared: SharedStrings, private val partitionData: PartitionData) {
    private val xmlns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private val xmlnsR = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private lateinit var serializer: XmlSerializer
    private lateinit var writer: StringWriter
    private val maxPageWidth = 94
    private lateinit var cellWidths: List<Float>
    private var measurePerRow = 0
    private var rowSize = 6
    private val chars = listOf('A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
            'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z')


    fun createXml(): String {
        writer = StringWriter()
        serializer = Xml.newSerializer()
        cellWidths = getCellWidths()
        initializeDoc()

        sheetPr()
        dimension()
        sheetViews()
        sheetFormatPr()
        cols()
        sheetData()
        printOptions()
        pageMargins()
        pageSetup()
        headerFooter()
        finish()

        return writer.toString()
    }


    private fun getCellWidths(): List<Float> {
        val width = with(partitionData) {
            val sig = signature.value!!
            val tmpCellWidths = (0 until sig).map { 0f }.toFloatArray()
            notes.forEach { voiceNotes ->
                voiceNotes.forEachIndexed { index, note ->
                    val cellIndex = index % sig
                    val requiredWith = getRequiredWidth(note)
                    if (tmpCellWidths[cellIndex] < requiredWith) {
                        tmpCellWidths[cellIndex] = requiredWith
                    }
                }
            }
            return@with tmpCellWidths
        }

        var measureWidth = 0f
        val cellWidths = mutableListOf<Float>()
        width.forEachIndexed { i, w ->
            measureWidth += w
            cellWidths.add(w)
            if (i + 1 != partitionData.signature.value) {
                measureWidth += 1
                cellWidths.add(1f)
            }
        }

        measurePerRow = 0
        while (measurePerRow * measureWidth < maxPageWidth) {
            measurePerRow++
        }

        if (measurePerRow > 1 && measurePerRow * measureWidth > maxPageWidth + 8) {
            measurePerRow--
        }
        val oneMeasureCellWidth = cellWidths.map { it }
        for (i in 0 until measurePerRow + 1) {
            cellWidths.addAll(oneMeasureCellWidth)
        }

        return cellWidths
    }


    private fun getRequiredWidth(note: String): Float {
        return when (note.length) {
            0 -> 3f
            1 -> 3f
            2 -> 3f
            3 -> 4.8f
            4 -> 4.8f
            5 -> 5.5f
            6 -> 5.9f
            else -> 2f * 0.3f * note.length
        }
    }


    private fun initializeDoc() {
        serializer.apply {
            setOutput(writer)
            startDocument("UTF-8", true)
            startTag("", "worksheet")
            attribute("", "xmlns", xmlns)
            attribute("", "xmlns:r", xmlnsR)
        }
    }


    private fun sheetPr() {
        serializer.apply {
            startTag("", "sheetPr")
            attribute("", "filterMode", "false")
            startTag("", "pageSetUpPr")
            attribute("", "fitToPage", "false")
            endTag("", "pageSetUpPr")
            endTag("", "sheetPr")
        }
    }


    private fun sheetViews() {
        serializer.apply {
            startTag("", "sheetViews")

            startTag("", "sheetView")
            attribute("", "showFormulas", "false")
            attribute("", "showGridLines", "true")
            attribute("", "showRowColHeaders", "true")
            attribute("", "showZeros", "true")
            attribute("", "rightToLeft", "false")
            attribute("", "tabSelected", "true")
            attribute("", "showOutlineSymbols", "true")
            attribute("", "defaultGridColor", "true")
            attribute("", "view", "normal")
            attribute("", "topLeftCell", "A1")
            attribute("", "colorId", "64")
            attribute("", "zoomScale", "100")
            attribute("", "zoomScaleNormal", "100")
            attribute("", "zoomScalePageLayoutView", "100")
            attribute("", "workbookViewId", "0")
            endTag("", "sheetView")

            startTag("", "selection")
            attribute("", "pane", "topLeft")
            attribute("", "activeCell", "A1")
            attribute("", "activeCellId", "0")
            attribute("", "sqref", "L4")
            endTag("", "selection")

            endTag("", "sheetViews")
        }
    }


    private fun dimension() {
        val lastColName = numberToAZ(cellWidths.size + 1)
        val rowsNum = with(partitionData) {
            Math.ceil(getMaxLength().toDouble() / (measurePerRow * signature.value!!.toDouble()))
        }
        serializer.apply {
            startTag("", "dimension")
            attribute("", "ref", "A1:$lastColName${rowsNum * 6}")
            endTag("", "dimension")
        }
    }


    private fun sheetFormatPr() {
        serializer.apply {
            startTag("", "sheetFormatPr")
            attribute("", "defaultRowHeight", "12.8")
            attribute("", "zeroHeight", "false")
            attribute("", "outlineLevelRow", "0")
            attribute("", "outlineLevelCol", "0")
            endTag("", "sheetFormatPr")
        }
    }


    private fun cols() {
        serializer.apply {
            startTag("", "cols")
            cellWidths.forEachIndexed { i, w ->
                startTag("", "col")
                attribute("", "collapsed", "false")
                attribute("", "customWidth", "true")
                attribute("", "hidden", "false")
                attribute("", "outlineLevel", "0")
                attribute("", "max", "${i + 1}")
                attribute("", "min", "${i + 1}")
                attribute("", "style", "0")
                attribute("", "width", "$w")
                endTag("", "col")
            }
            endTag("", "cols")
        }
    }


    private fun sheetData() {
        val groups = groupNotesPerMeasure()
        val signature = partitionData.signature.value!!
        val colNames = (0 until measurePerRow * 2 * signature * signature + 4).map {
            numberToAZ(it)
        }

        serializer.apply {
            startTag("", "sheetData")

            groups.forEachIndexed { rowIndex, row ->
                startTag("", "row")
                attribute("", "r", "${rowIndex + 1}")
                attribute("", "s", "2")
                attribute("", "customFormat", "false")
                attribute("", "ht", "12.8")
                attribute("", "hidden", "false")
                attribute("", "customHeight", "false")
                attribute("", "outlineLevel", "0")
                attribute("", "collapsed", "false")

                row.mData.forEachIndexed { measureIndex, measure ->
                    val startIndex = (2 * signature - 1) * measureIndex
                    val startRow = 1 + rowIndex * rowSize
                    measure.mData.forEachIndexed { voiceIndex, voiceNotes ->
                        voiceNotes.forEachIndexed { indexInMeasure, notes ->
                            val currentIndex = startIndex + 2 * indexInMeasure
                            val s = when (indexInMeasure) {
                                0 -> "2"
                                signature - 1 -> "3"
                                else -> "0"
                            }
                            startTag("", "c")
                            attribute("", "r", "${colNames[currentIndex]}${startRow + voiceIndex}")
                            attribute("", "t", "s")
                            attribute("", "s", s)

                            if (notes != "") {
                                startTag("", "v")
                                text("${shared.getIndex(notes)}")
                                endTag("", "v")
                            }

                            endTag("", "c")

                            if (indexInMeasure < signature - 1) {
                                val sep = when {
                                    signature % 2 == 0 && indexInMeasure % 2 == 1 -> "|"
                                    else -> ":"
                                }
                                startTag("", "c")
                                attribute("", "r", "${colNames[currentIndex + 1]}${startRow + voiceIndex}")
                                attribute("", "s", "0")
                                attribute("", "t", "s")

                                startTag("", "v")
                                text("${shared.getIndex(sep)}")
                                endTag("", "v")

                                endTag("", "c")
                            }
                        }
                    }
                }

                endTag("", "row")
            }

            endTag("", "sheetData")
        }
    }


    private fun groupNotesPerMeasure(): List<XlsRow> {
        val groups = mutableListOf<XlsRow>()
        val maxLength = partitionData.getMaxLength()
        val signature = partitionData.signature.value!!
        var measureIndex = -1
        lateinit var xlsRow: XlsRow
        lateinit var xlsMeasure: XlsMeasure

        for (index in 0 until maxLength + 1) {
            val indexInMeasure = index % signature
            if (indexInMeasure == 0) {
                measureIndex++
                val indexInRow = measureIndex % measurePerRow
                if (indexInRow == 0) {
                    xlsRow = XlsRow()
                    groups.add(xlsRow)
                }
                xlsMeasure = XlsMeasure(partitionData.notes.size)
                xlsRow.add(xlsMeasure)
            }

            for (v in (0 until partitionData.notes.size)) {
                with(partitionData.notes[v]) {
                    if (size > index) {
                        xlsMeasure.add(v, this[index])
                    } else {
                        xlsMeasure.add(v, "")
                    }
                }
            }
        }

        return groups
    }


    private fun printOptions() {
        serializer.apply {
            startTag("", "printOptions")
            attribute("", "headings", "false")
            attribute("", "gridLines", "false")
            attribute("", "gridLinesSet", "true")
            attribute("", "horizontalCentered", "true")
            attribute("", "verticalCentered", "false")
            endTag("", "printOptions")
        }
    }


    private fun pageMargins() {
        serializer.apply {
            startTag("", "pageMargins")
            attribute("", "left", "0.4")
            attribute("", "right", "0.4")
            attribute("", "top", "1")
            attribute("", "bottom", "1")
            attribute("", "header", "0.4")
            attribute("", "footer", "0.4")
            endTag("", "pageMargins")
        }
    }


    private fun pageSetup() {
        serializer.apply {
            startTag("", "pageSetup")
            attribute("", "paperSize", "1")
            attribute("", "scale", "100")
            attribute("", "firstPageNumber", "1")
            attribute("", "fitToWidth", "1")
            attribute("", "fitToHeight", "1")
            attribute("", "pageOrder", "downThenOver")
            attribute("", "orientation", "portrait")
            attribute("", "blackAndWhite", "false")
            attribute("", "draft", "false")
            attribute("", "cellComments", "none")
            attribute("", "useFirstPageNumber", "true")
            attribute("", "horizontalDpi", "300")
            attribute("", "verticalDpi", "300")
            attribute("", "copies", "1")
            endTag("", "pageSetup")
        }
    }


    private fun headerFooter() {
        serializer.apply {
            startTag("", "headerFooter")
            attribute("", "differentFirst", "false")
            attribute("", "differentOddEven", "false")

            startTag("", "oddHeader")
            text(partitionData.songInfo.filename)
            endTag("", "oddHeader")

            endTag("", "headerFooter")
        }
    }


    private fun finish() {
        serializer.apply {
            endTag("", "worksheet")
            endDocument()
        }
    }


    private fun numberToAZ(number: Int): String {
        var digits = 0
        val base = 26
        var currentValue = number + 1
        val numbers = mutableListOf<Int>()

        while (currentValue > Math.pow(base.toDouble(), (digits + 1).toDouble())) {
            digits++
        }

        var i = digits
        while (i >= 0) {
            var j = base
            var done = false
            while (j >= 0) {
                val full = Math.pow(base.toDouble(), i.toDouble())
                if (!done && currentValue > full * j) {
                    done = true
                    numbers.add(j)
                    currentValue -= (full * j).toInt()
                }
                j--
            }
            i--
        }

        var str = ""
        numbers.forEachIndexed { index, n ->
            str += when (index) {
                numbers.size - 1 -> chars[n]
                else -> chars[n - 1]
            }
        }
        return str
    }
}
