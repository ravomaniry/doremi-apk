package mg.maniry.doremi.editor.xlsxExport

import org.xmlpull.v1.XmlSerializer

data class FileAddress(val src: String, val dest: String)

data class XlsText(var value: String, val type: Int)

class XlsRow {
    val mData = mutableListOf<XlsMeasure>()

    fun add(measure: XlsMeasure) {
        mData.add(measure)
    }
}


class XlsMeasure(voices: Int) {
    val mData = mutableListOf<MutableList<String>>()

    init {
        while (mData.size < voices) {
            mData.add(mutableListOf())
        }
    }


    fun add(voice: Int, notes: String) {
        mData[voice].add(notes)
    }
}


fun XmlSerializer.setTagAttrValue(tag: String, attr: Array<String>? = null, value: String? = null, closeTag: Boolean = true) {
    startTag("", tag)

    if (attr != null) {
        var attrIndex = 0
        while (attrIndex < attr.size) {
            val attrName = attr[attrIndex]
            attrIndex++
            if (attrIndex < attr.size) {
                val attrValue = attr[attrIndex]
                attribute("", attrName, attrValue)
                attrIndex++
            }
        }
    }

    if (value != null) {
        text(value)
    }

    if (closeTag) {
        endTag("", tag)
    }
}
