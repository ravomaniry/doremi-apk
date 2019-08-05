package mg.maniry.doremi.editor.xlsxExport

import android.util.Xml
import mg.maniry.doremi.editor.partition.ChangeEvent
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
    private var rowSize = partitionData.notes.size + 3
    private var lastRow = 0


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
        while ((measurePerRow + 1) * measureWidth + 1 <= maxPageWidth) {
            measurePerRow++
        }

        val oneMeasureCellWidth = cellWidths.map { it }
        for (i in 0 until measurePerRow + 1) {
            cellWidths.addAll(oneMeasureCellWidth)
        }

        return cellWidths
    }


    private fun getRequiredWidth(note: String): Float {
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


    private fun initializeDoc() {
        serializer.apply {
            setOutput(writer)
            startDocument("UTF-8", true)
            setTagAttrValue(tag = "worksheet", closeTag = false,
                    attr = arrayOf("xmlns", xmlns, "xmlns:r", xmlnsR))
        }
    }


    private fun sheetPr() {
        serializer.apply {
            setTagAttrValue("sheetPr", arrayOf("filterMode", "false"), null, false)
            setTagAttrValue("pageSetUpPr", arrayOf("fitToPage", "false"))
            endTag("", "sheetPr")
        }
    }


    private fun sheetViews() {
        serializer.apply {
            setTagAttrValue(tag = "sheetViews", closeTag = false)

            setTagAttrValue("sheetView", arrayOf(
                    "showFormulas", "false",
                    "showGridLines", "true",
                    "showRowColHeaders", "true",
                    "showZeros", "true",
                    "rightToLeft", "false",
                    "tabSelected", "true",
                    "showOutlineSymbols", "true",
                    "defaultGridColor", "true",
                    "view", "normal",
                    "topLeftCell", "A1",
                    "colorId", "64",
                    "zoomScale", "100",
                    "zoomScaleNormal", "100",
                    "zoomScalePageLayoutView", "100",
                    "workbookViewId", "0"))

            setTagAttrValue("selection", arrayOf(
                    "pane", "topLeft",
                    "activeCell", "A1",
                    "activeCellId", "0",
                    "sqref", "L4"))

            endTag("", "sheetViews")
        }
    }


    private fun dimension() {
        val lastColName = numberToAZ(cellWidths.size + 1)
        val rowsNum = with(partitionData) {
            Math.ceil(getMaxLength().toDouble() / (measurePerRow * signature.value!!.toDouble()))
        }
        serializer.setTagAttrValue("dimension", arrayOf("ref", "A1:$lastColName${rowsNum * 6}"))
    }


    private fun sheetFormatPr() {
        serializer.setTagAttrValue("sheetFormatPr", arrayOf(
                "defaultRowHeight", "12.8",
                "zeroHeight", "false",
                "outlineLevelRow", "0",
                "outlineLevelCol", "0"
        ))
    }


    private fun cols() {
        serializer.apply {
            setTagAttrValue(tag = "cols", closeTag = false)
            cellWidths.forEachIndexed { i, w ->
                setTagAttrValue("col", arrayOf(
                        "collapsed", "false",
                        "customWidth", "true",
                        "hidden", "false",
                        "outlineLevel", "0",
                        "max", "${i + 1}",
                        "min", "${i + 1}",
                        "style", "0",
                        "width", "$w"
                ))
            }
            endTag("", "cols")
        }
    }


    private fun sheetData() {
        val groups = groupNotesPerMeasure()
        val signature = partitionData.signature.value!!
        val notesPerGroup = measurePerRow * signature
        val colNames = (0 until measurePerRow * 2 * signature * signature + 4).map {
            numberToAZ(it)
        }

        serializer.apply {
            startTag("", "sheetData")

            groups.forEachIndexed { groupIndex, row ->
                val headerRowIndex = groupIndex * rowSize + 1
                setTagAttrValue(tag = "row", closeTag = false, attr = arrayOf(
                        "r", "$headerRowIndex",
                        "s", "2",
                        "customFormat", "false",
                        "ht", "12.8",
                        "hidden", "false",
                        "customHeight", "false",
                        "outlineLevel", "0",
                        "collapsed", "false"
                ))

                for (measureIndex in (0 until row.mData.size)) {
                    for (timeIndex in 0 until signature) {
                        val changePosition = notesPerGroup * groupIndex + measureIndex * signature + timeIndex
                        val colIndex = (signature + signature - 1) * measureIndex + timeIndex * 2
                        val cellRef = "${colNames[colIndex]}$headerRowIndex"
                        val events = partitionData.changeEvents.filter { it.position == changePosition }.joinToString(" ") {
                            when (it.type) {
                                ChangeEvent.MOD -> "Do=${it.value}"
                                ChangeEvent.TEMPO -> "T=${it.value}"
                                else -> it.value
                            }
                        }

                        if (events != "") {
                            setTagAttrValue(tag = "c", closeTag = false, attr = arrayOf("r", cellRef, "t", "s", "s", "0"))
                            setTagAttrValue("v", null, "${shared.getIndex(events, true)}")
                            endTag("", "c")
                        }
                    }
                }

                endTag("", "row")


                for (voiceIndex in (0 until partitionData.notes.size)) {
                    val rowIndex = groupIndex * rowSize + voiceIndex + 2
                    lastRow = rowIndex

                    setTagAttrValue(tag = "row", closeTag = false, attr = arrayOf(
                            "r", "$rowIndex",
                            "s", "2",
                            "customFormat", "false",
                            "ht", "12.8",
                            "hidden", "false",
                            "customHeight", "false",
                            "outlineLevel", "0",
                            "collapsed", "false"
                    ))

                    row.mData.forEachIndexed { measureIndex, xlsMeasure ->
                        for (timeIndex in 0 until signature) {
                            val colIndex = (signature + signature - 1) * measureIndex + timeIndex * 2
                            val cellName = "${colNames[colIndex]}$rowIndex"
                            val content = if (xlsMeasure.mData[voiceIndex].size > timeIndex) {
                                xlsMeasure.mData[voiceIndex][timeIndex]
                            } else {
                                ""
                            }

                            val s = when (timeIndex) {
                                0 -> "2"
                                signature - 1 -> "3"
                                else -> "0"
                            }

                            setTagAttrValue(tag = "c", closeTag = false, attr = arrayOf(
                                    "r", cellName, "t", "s", "s", s
                            ))

                            if (content != "") {
                                setTagAttrValue("v", null, "${shared.getIndex(content)}")
                            }

                            endTag("", "c")

                            if (timeIndex < signature - 1) {
                                val sepColName = "${colNames[colIndex + 1]}$rowIndex"
                                val sep = when {
                                    signature % 2 == 0 && timeIndex % 2 == 1 -> "|"
                                    else -> ":"
                                }

                                setTagAttrValue(tag = "c", closeTag = false, attr = arrayOf(
                                        "r", sepColName, "s", "0", "t", "s"
                                ))
                                setTagAttrValue("v", null, "${shared.getIndex(sep)}")
                                endTag("", "c")
                            }
                        }
                    }
                    endTag("", "row")
                }
            }
        }


        var rowIndex = lastRow + 2
        partitionData.lyrics.value?.split('\n')?.forEach {
            rowIndex++

            serializer.apply {
                setTagAttrValue(tag = "row", closeTag = false, attr = arrayOf(
                        "r", "$rowIndex",
                        "s", "2",
                        "customFormat", "false",
                        "ht", "12.8",
                        "hidden", "false",
                        "customHeight", "false",
                        "outlineLevel", "0",
                        "collapsed", "false"
                ))

                setTagAttrValue(tag = "c", closeTag = false, attr = arrayOf(
                        "r", "A$rowIndex", "t", "s", "s", "0"
                ))
                setTagAttrValue("v", null, "${shared.getIndex(it, true)}")

                endTag("", "c")
                endTag("", "row")
            }
        }

        serializer.endTag("", "sheetData")
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
        serializer.setTagAttrValue("printOptions", arrayOf(
                "headings", "false",
                "gridLines", "false",
                "gridLinesSet", "true",
                "horizontalCentered", "true",
                "verticalCentered", "false"
        ))
    }


    private fun pageMargins() {
        serializer.setTagAttrValue("pageMargins", arrayOf(
                "left", "0.4",
                "right", "0.4",
                "top", "1",
                "bottom", "1",
                "header", "0.4",
                "footer", "0.4"
        ))
    }


    private fun pageSetup() {
        serializer.setTagAttrValue("pageSetup", arrayOf(
                "paperSize", "1",
                "scale", "96",
                "firstPageNumber", "1",
                "fitToWidth", "1",
                "fitToHeight", "1",
                "pageOrder", "downThenOver",
                "orientation", "portrait",
                "blackAndWhite", "false",
                "draft", "false",
                "cellComments", "none",
                "useFirstPageNumber", "true",
                "horizontalDpi", "300",
                "verticalDpi", "300",
                "copies", "1"
        ))
    }


    private fun headerFooter() {
        serializer.apply {
            setTagAttrValue(tag = "headerFooter", closeTag = false,
                    attr = arrayOf("differentFirst", "false", "differentOddEven", "false"))
            setTagAttrValue("oddHeader", null, partitionData.songInfo.filename)
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
        var currentValue = number + 1
        val sb = StringBuilder()
        while (currentValue > 0) {
            currentValue--
            sb.append('A' + currentValue % 26)
            currentValue /= 26
        }
        return sb.reverse().toString()
    }
}
