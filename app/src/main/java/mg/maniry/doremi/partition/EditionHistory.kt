package mg.maniry.doremi.partition

class EditionHistory {
    data class Change(val voice: Int, val index: Int, val prev: String, val next: String)

    private var anticipations = mutableListOf<Cell>()
    private val backChanges = mutableListOf<Change>()
    private val fwdChanges = mutableListOf<Change>()
    private val maxHistoryLength = 20

    fun reset() {
        anticipations.removeAll { true }
        backChanges.removeAll { true }
        fwdChanges.removeAll { true }
    }


    fun anticipate(cursorPos: Cell?, partitionData: PartitionData) {
        cursorPos?.run {
            anticipations = mutableListOf(
                    partitionData.getCell(voice, index),
                    partitionData.getCell(voice, index + 1))

            if (index > 0)
                anticipations.add(partitionData.getCell(voice, index - 1))
        }
    }


    fun handle(updatedCell: Cell) {
        anticipations.find { it.voice == updatedCell.voice && it.index == updatedCell.index }
                ?.run {
                    backChanges.add(Change(voice, index, content, updatedCell.content))
                }

        if (fwdChanges.size > 0)
            fwdChanges.removeAll { true }

        while (backChanges.size > maxHistoryLength)
            backChanges.removeAt(0)
    }


    fun restore(partitionData: PartitionData, forward: Boolean): Cell? {
        return if (forward) {
            redo(partitionData)
        } else {
            undo(partitionData)
        }
    }


    private fun redo(partitionData: PartitionData): Cell? {
        if (fwdChanges.size > 0) {
            fwdChanges.last().run {
                partitionData.notes[voice][index] = prev
                backChanges.add(copy(prev = next, next = prev))
                fwdChanges.removeAt(fwdChanges.size - 1)
                return Cell(voice, index, prev)
            }
        }

        return null
    }


    private fun undo(partitionData: PartitionData): Cell? {
        if (backChanges.size > 0) {
            backChanges.last().run {
                partitionData.notes[voice][index] = prev
                fwdChanges.add(copy(prev = next, next = prev))
                backChanges.removeAt(backChanges.size - 1)
                return Cell(voice, index, prev)
            }
        }

        return null
    }
}
