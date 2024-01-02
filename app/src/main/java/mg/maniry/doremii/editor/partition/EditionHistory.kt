package mg.maniry.doremii.editor.partition


class EditionHistory {
    data class Change(val voice: Int, val index: Int, val prev: String, val next: String)

    private var anticipations = mutableListOf<Cell>()
    private val backChanges = mutableListOf<List<Change>>()
    private val fwdChanges = mutableListOf<List<Change>>()
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

            if (index > 0) {
                anticipations.add(partitionData.getCell(voice, index - 1))
            }
        }
    }


    fun handle(updatedCell: Cell) {
        anticipations.find { it.voice == updatedCell.voice && it.index == updatedCell.index }?.run {
            backChanges.add(listOf(Change(voice, index, content, updatedCell.content)))
        }

        if (fwdChanges.size > 0) {
            fwdChanges.removeAll { true }
        }

        while (backChanges.size > maxHistoryLength) {
            backChanges.removeAt(0)
        }
    }


    fun handleBulkOps(changes: List<Change>) {
        backChanges.add(changes)
        while (backChanges.size > maxHistoryLength) {
            backChanges.removeAt(0)
        }
    }


    fun restore(partitionData: PartitionData, forward: Boolean): List<Cell>? {
        return if (forward) {
            redo(partitionData)
        } else {
            undo(partitionData)
        }
    }


    private fun redo(partitionData: PartitionData): List<Cell>? {
        filterHistory(partitionData, fwdChanges)?.run {
            forEach { partitionData.notes[it.voice][it.index] = it.prev }
            backChanges.add(map { it.copy(prev = it.next, next = it.prev) })
            fwdChanges.removeAt(fwdChanges.size - 1)
            return map { Cell(it.voice, it.index, it.prev) }
        }

        return null
    }


    private fun undo(partitionData: PartitionData): List<Cell>? {
        filterHistory(partitionData, backChanges)?.run {
            forEach { partitionData.notes[it.voice][it.index] = it.prev }
            fwdChanges.add(map { it.copy(prev = it.next, next = it.prev) })
            backChanges.removeAt(backChanges.size - 1)
            return map { Cell(it.voice, it.index, it.prev) }
        }

        return null
    }


    private fun filterHistory(partitionData: PartitionData, changes: MutableList<List<Change>>): List<Change>? {
        return if (changes.size > 0) {
            changes.last().filter {
                it.voice < partitionData.voicesNum && it.index < partitionData.notes[it.voice].size
            }
        } else {
            null
        }
    }
}
