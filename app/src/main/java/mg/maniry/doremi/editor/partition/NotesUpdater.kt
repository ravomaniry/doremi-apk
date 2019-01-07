package mg.maniry.doremi.editor.partition


class NotesUpdater constructor(
        private val partitionData: PartitionData) {

    private var cell = Cell()
    private var allNtsL = 0
    private var lastNtL = 0
    private var nextNtL = 12
    private var replaceNote = true


    init {
        moveCursor(0, 0)
    }


    fun moveCursor(voice: Int, index: Int) = partitionData.getCell(voice, index).also {
        cell = it
        initVars()
    }


    private fun initVars() {
        val content = cell.content.replace(" ", "")
        if (content == "") {
            replaceNote = true
            allNtsL = 0
            lastNtL = 0
            nextNtL = 12

        } else if (!content.contains('.') && !content.contains(',')) {
            replaceNote = true
            allNtsL = 12
            lastNtL = 12
            nextNtL = 12

        } else {
            val unit = 12.div(content.split('.').size)

            if (!content.endsWith('.') && !content.endsWith(',')) {
                replaceNote = true
                allNtsL = 12
                lastNtL = unit
                nextNtL = unit

            } else if (content.endsWith('.')) {
                replaceNote = false
                allNtsL = 12 - unit
                lastNtL = unit
                nextNtL = unit

            } else {
                replaceNote = false
                lastNtL = unit / 2
                nextNtL = lastNtL
                allNtsL = 12 - nextNtL
            }
        }
    }


    fun add(note: String): Cell? {
        var noteToAdd = ""
        var isNewNote = false
        var updatedCell: Cell? = null

        if (note == "." || note == "," || note == ".,") {
            if (note == ".") {
                if (allNtsL == 0 || (allNtsL == 12 && lastNtL == 12)) {
                    allNtsL = 6
                    nextNtL = 6
                    lastNtL = 6
                    noteToAdd = (if (allNtsL == 0) " " else "") + "."
                    replaceNote = false

                } else if (allNtsL == 12 && (lastNtL == 6 || lastNtL == 3)) {
                    allNtsL = 8
                    nextNtL = 4
                    lastNtL = lastNtL * 2 / 3
                    noteToAdd = "."
                    replaceNote = false
                }

            } else if (note == ",") {

                if (allNtsL == 0 || (allNtsL == 12 && lastNtL == 12)) {
                    allNtsL = 3
                    nextNtL = 3
                    lastNtL = 3
                    noteToAdd = (if (allNtsL == 0) " " else "") + ","
                    replaceNote = false

                } else if (allNtsL == 6) {
                    allNtsL = 9
                    nextNtL = 3
                    lastNtL = 3
                    noteToAdd = ","
                    replaceNote = false

                } else if (allNtsL == 8) {
                    allNtsL = 10
                    nextNtL = 2
                    lastNtL = 2
                    noteToAdd = ","
                    replaceNote = false

                } else if (lastNtL > 3) {
                    allNtsL -= 3
                    nextNtL = 3
                    lastNtL = 3
                    noteToAdd = ","
                    replaceNote = false
                }

            } else if (note == ".,") {
                if (allNtsL == 0 || (allNtsL == 12 && lastNtL == 12)) {
                    allNtsL = 9
                    nextNtL = 3
                    lastNtL = 3
                    noteToAdd = (if (allNtsL == 0) " " else "") + ".,"
                    replaceNote = false
                }
            }

        } else {
            noteToAdd = note
            isNewNote = (allNtsL == 12 || lastNtL == 0)
            allNtsL += if (allNtsL == 12) 0 else nextNtL
            lastNtL = nextNtL

            // d.d
            if (allNtsL == 12) {
                nextNtL = 12
            } else if (allNtsL == 6 && lastNtL == 3) {
                nextNtL = 6
                noteToAdd += "."
            }
        }

        if (noteToAdd != "") {
            updatedCell = when {
                replaceNote -> cell.copy(content = noteToAdd)
                isNewNote -> cell.copy(index = cell.index + 1, content = noteToAdd)
                else -> cell.copy(content = cell.content + noteToAdd)
            }.also {
                cell = it
                partitionData.updateNote(it)
            }
        }

        replaceNote = false
        return updatedCell
    }


    fun delete() = when (cell.content) {
        "" -> if (cell.index > 0) cell.copy(index = cell.index - 1, content = "") else null
        else -> cell.copy(content = "")
    }.also {
        if (it != null) {
            partitionData.updateNote(it)
            cell = it
            initVars()
        }
    }
}