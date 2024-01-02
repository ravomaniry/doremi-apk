package mg.maniry.doremii.editor.xlsxExport

import android.util.Xml
import mg.maniry.doremii.editor.views.NotesToSpan
import org.xmlpull.v1.XmlSerializer
import java.io.StringWriter

class SharedStrings {
    private val xmlns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private val values = mutableListOf<String>()
    private val toPreserve = mutableListOf<String>()
    private var count = 0


    fun getIndex(s: String, preserve: Boolean = false): Int {
        if (preserve && !toPreserve.contains(s)) {
            toPreserve.add(s)
        }

        return if (values.contains(s)) {
            count++
            values.indexOf(s)
        } else {
            values.add(s)
            count++
            values.size - 1
        }
    }


    override fun toString(): String {
        val serializer = Xml.newSerializer()
        val writer = StringWriter()
        serializer.apply {
            setOutput(writer)
            startDocument("UTF-8", true)
            startTag("", "sst")
            attribute("", "xmlns", xmlns)
            attribute("", "count", count.toString())
            attribute("", "uniqueCount", values.size.toString())

            values.forEach { value -> supSubOctave(value, serializer) }

            endTag("", "sst")
            endDocument()
        }
        return writer.toString()
    }


    private fun supSubOctave(strNotes: String, serializer: XmlSerializer) {
        if (strNotes.length <= 1 || toPreserve.contains(strNotes)) {
            serializer.apply {
                startTag("", "si")
                startTag("", "t")
                attribute("", "xml:space", "preserve")
                text(strNotes)
                endTag("", "t")
                endTag("", "si")
            }
        } else {
            val textElements = mutableListOf<XlsText>()
            var htmlNotes = XlsText("", 0)
            textElements.add(htmlNotes)
            var index = 0
            var skipNext = false

            for (i in 0 until strNotes.length) {
                if (!skipNext) {
                    if (NotesToSpan.symbols.contains(strNotes[i])) {
                        if (strNotes[i] == '-') {
                            if (i < strNotes.length - 1 && NotesToSpan.symbols.contains(strNotes[i + 1])) {
                                htmlNotes = XlsText("", 0)
                                textElements.add(XlsText(strNotes[i + 1].toString(), -1))
                                textElements.add(htmlNotes)
                                skipNext = true
                                index++
                            } else {
                                htmlNotes.value += strNotes[i]
                                index++
                            }
                        } else {
                            htmlNotes = XlsText("", 0)
                            textElements.add(XlsText(strNotes[i].toString(), 1))
                            textElements.add(htmlNotes)
                            index++
                        }
                    } else {
                        htmlNotes.value += strNotes[i]
                        index++
                    }
                } else {
                    skipNext = false
                }
            }

            serializer.apply {
                startTag("", "si")

                textElements.filter { it.value != "" }.forEach {
                    startTag("", "r")

                    startTag("", "rPr")

                    if (it.type != 0) {
                        startTag("", "vertAlign")
                        attribute("", "val", if (it.type == 1) "superscript" else "subscript")
                        endTag("", "vertAlign")
                    }

                    startTag("", "sz")
                    attribute("", "val", "10")
                    endTag("", "sz")

                    startTag("", "family")
                    attribute("", "val", "2")
                    endTag("", "family")

                    startTag("", "charset")
                    attribute("", "val", "1")
                    endTag("", "charset")

                    endTag("", "rPr")

                    startTag("", "t")
                    attribute("", "xml:space", "preserve")
                    text(it.value)
                    endTag("", "t")

                    endTag("", "r")
                }

                endTag("", "si")
            }
        }
    }
}
