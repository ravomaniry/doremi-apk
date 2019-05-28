package mg.maniry.doremi.editor.xlsxExport

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
