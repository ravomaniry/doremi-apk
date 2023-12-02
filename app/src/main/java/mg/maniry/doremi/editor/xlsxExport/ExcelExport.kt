package mg.maniry.doremi.editor.xlsxExport

import android.content.res.AssetManager
import mg.maniry.doremi.commonUtils.FileManager
import mg.maniry.doremi.editor.partition.PartitionData
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


class ExcelExport(private val asset: AssetManager) {
    private var fileName = "doremi"
    private val files = listOf(
        FileAddress("content_type", "[Content_Types].xml"),
        FileAddress("app", "docProps/app.xml"),
        FileAddress("core", "docProps/core.xml"),
        FileAddress("rels", "_rels/.rels"),
        FileAddress("workbook_rels", "xl/_rels/workbook.xml.rels"),
        FileAddress("workbook", "xl/workbook.xml"),
        FileAddress("styles", "xl/styles.xml")
    )


    fun export(partitionData: PartitionData) {
        val sharedStrings = SharedStrings()
        val sheet = Sheet(sharedStrings, partitionData)
        val sheetXml = sheet.createXml()
        fileName = partitionData.songInfo.filename
        writeFile(sharedStrings.toString(), sheetXml)
    }


    private fun writeFile(sharedStrings: String, sheet: String) {
        try {
            val resultOut = ByteArrayOutputStream()
            val zipOut = ZipOutputStream(resultOut)
            zipOut.apply {
                files.forEach {
                    putNextEntry(ZipEntry(it.dest))
                    val bytes = readFile(it.src, asset).toByteArray()
                    write(bytes)
                    closeEntry()
                }
                putNextEntry(ZipEntry("xl/sharedStrings.xml"))
                val sharedBytes = sharedStrings.toByteArray()
                write(sharedBytes)
                closeEntry()
                putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                val sheetBytes = sheet.toByteArray()
                write(sheetBytes)
                closeEntry()
            }
            zipOut.finish()
            val fileOut = FileOutputStream(FileManager.createExportFilePath(fileName, ".xlsx"))
            resultOut.apply {
                writeTo(fileOut)
                close()
            }
        } catch (e: Exception) {
            print(e)
        }
    }


    private fun readFile(name: String, asset: AssetManager): String {
        val file = asset.open("xlsx/$name.txt")

        var content = ""
        Scanner(file).run {
            while (hasNextLine()) {
                content += nextLine()
            }
        }

        return content
    }
}
