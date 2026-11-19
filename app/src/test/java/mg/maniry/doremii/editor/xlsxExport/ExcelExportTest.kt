package mg.maniry.doremii.editor.xlsxExport

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import mg.maniry.doremii.editor.partition.sampleScore
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.Font
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.PageMargin
import org.apache.poi.ss.usermodel.PrintSetup
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream


class ExcelExportTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun buildKeepsScoreLayout() {
        val workbook = ExcelExport().build(sampleScore())
        val bytes = ByteArrayOutputStream().use { out ->
            workbook.use { it.write(out) }
            out.toByteArray()
        }

        XSSFWorkbook(ByteArrayInputStream(bytes)).use { saved ->
            val sheet = saved.getSheet("Doremi")
            val voice = sheet.getRow(7)
            val title = sheet.getRow(0).getCell(0)
            val swing = sheet.getRow(4).getCell(0).richStringCellValue
            val singer = sheet.getRow(1).getCell(sheet.getRow(1).lastCellNum - 1)

            assertEquals("Doremi", sheet.sheetName)
            assertEquals("Hira & Feo", title.stringCellValue)
            assertEquals(HorizontalAlignment.CENTER, title.cellStyle.alignment)
            assertEquals(true, title.cellStyle.font.bold)
            assertEquals(18, title.cellStyle.font.fontHeightInPoints.toInt())
            assertEquals("4/4", sheet.getRow(1).getCell(0).stringCellValue)
            assertEquals(true, sheet.getRow(1).getCell(0).cellStyle.font.bold)
            assertEquals("Rakoto", singer.stringCellValue)
            assertEquals(HorizontalAlignment.RIGHT, singer.cellStyle.alignment)
            assertEquals("Do dia C", sheet.getRow(2).getCell(0).stringCellValue)
            assertEquals("Tempo: 96 bpm", sheet.getRow(3).getCell(0).stringCellValue)
            assertEquals("Swing (vakiana hoe \"d.-.d\" ny \"d.d\" sy ny \"d.,d\")", swing.string)
            assertEquals(true, swing.getFontAtIndex(0).bold)
            assertEquals(true, swing.getFontAtIndex(0).italic)
            assertEquals(false, swing.getFontAtIndex(6).bold)
            assertEquals(8, swing.getFontAtIndex(6).fontHeightInPoints.toInt())
            assertNull(sheet.getRow(5))
            assertEquals("mf Do=G T=80", sheet.getRow(6).getCell(0).stringCellValue)
            assertEquals("d1", voice.getCell(0).stringCellValue)
            assertEquals(Font.SS_SUPER.toInt(), voice.getCell(0).richStringCellValue.getFontAtIndex(1).typeOffset.toInt())
            assertEquals(BorderStyle.HAIR, voice.getCell(0).cellStyle.borderLeft)
            assertEquals(HorizontalAlignment.CENTER, voice.getCell(0).cellStyle.alignment)
            assertEquals(":", voice.getCell(1).stringCellValue)
            assertEquals(BorderStyle.NONE, voice.getCell(1).cellStyle.borderLeft)
            assertEquals("|", voice.getCell(3).stringCellValue)
            assertEquals("f1", voice.getCell(6).stringCellValue)
            assertEquals(Font.SS_SUB.toInt(), voice.getCell(6).richStringCellValue.getFontAtIndex(1).typeOffset.toInt())
            assertEquals(BorderStyle.HAIR, voice.getCell(6).cellStyle.borderRight)
            assertNull(sheet.getRow(11))
            assertNull(sheet.getRow(12))
            assertEquals("Line one", sheet.getRow(13).getCell(0).stringCellValue)
            assertEquals("Line <two>", sheet.getRow(15).getCell(0).stringCellValue)
            assertEquals("Tonony: Tonon", sheet.getRow(17).getCell(0).stringCellValue)
            assertEquals(HorizontalAlignment.RIGHT, sheet.getRow(17).getCell(0).cellStyle.alignment)
            assertEquals(true, sheet.getRow(17).getCell(0).cellStyle.font.italic)
            assertEquals("Feony: Feo", sheet.getRow(18).getCell(0).stringCellValue)
            assertEquals("2020", sheet.getRow(19).getCell(0).stringCellValue)
            assertEquals(3.2, sheet.getColumnWidth(0) / 256.0, 0.02)
            assertEquals(1.0, sheet.getColumnWidth(1) / 256.0, 0.02)
            assertEquals(false, sheet.fitToPage)
            assertEquals(PrintSetup.LETTER_PAPERSIZE.toInt(), sheet.printSetup.paperSize.toInt())
            assertEquals(96, sheet.printSetup.scale.toInt())
            assertEquals(false, sheet.printSetup.landscape)
            assertEquals(0.4, sheet.getMargin(PageMargin.LEFT), 0.001)
            assertEquals(0.4, sheet.getMargin(PageMargin.RIGHT), 0.001)
            assertEquals(1.0, sheet.getMargin(PageMargin.TOP), 0.001)
            assertEquals(1.0, sheet.getMargin(PageMargin.BOTTOM), 0.001)
            assertEquals(0.4, sheet.getMargin(PageMargin.HEADER), 0.001)
            assertEquals(0.4, sheet.getMargin(PageMargin.FOOTER), 0.001)
            assertEquals(true, sheet.horizontallyCenter)
            assertEquals("Hira & Feo - Rakoto", sheet.ctWorksheet.headerFooter.oddHeader)
        }
    }
}
