package mg.maniry.doremii.editor.partition


data class ClipBoard(var start: Cell, var end: Cell) {
    fun reorder() {
        val startVoice = start.voice
        val startIndex = start.index

        if (startVoice > end.voice && startIndex > end.index) {
            val startCopy = start.copy()
            start = end.copy()
            end = startCopy
        } else if (startVoice > end.voice) {
            start = start.copy(voice = end.voice)
            end = end.copy(voice = startVoice)
        } else if (startIndex > end.index) {
            start = start.copy(index = end.index)
            end = end.copy(index = startIndex)
        }
    }
}
