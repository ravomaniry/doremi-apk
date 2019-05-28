package mg.maniry.doremi.editor.viewModels


data class FileContent(val error: String? = null, val content: String = "")


fun addOctaveToNote(note: String, octave: Int?): String {
    var octaveToAdd = if (octave == null || octave == 0) "" else octave.toString()
    var noteToAdd = note

    if (note == "d1") {
        octaveToAdd = if (octave == -1) "" else (octave ?: 0).plus(1).toString()
        noteToAdd = "d"
    } else if (arrayOf("-", ".", ",", ". ,", ".,", " ").contains(note)) {
        octaveToAdd = ""
    }

    return "$noteToAdd$octaveToAdd"
}


enum class SelectMode {
    CURSOR,
    COPY,
}
