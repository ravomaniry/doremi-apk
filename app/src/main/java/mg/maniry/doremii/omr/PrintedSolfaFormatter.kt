package mg.maniry.doremii.omr

data class FormattedLine(val text: String, val inferredRanges: List<IntRange>)

object PrintedSolfaFormatter {
    fun separatorAfter(cellIndex: Int, signature: Int): String {
        return if (signature > 3 && signature % 2 == 0 && cellIndex % 2 == 1) " | " else " : "
    }

    fun format(
        cells: List<String>,
        signature: Int,
        inferredBeats: Set<Int> = emptySet(),
    ): FormattedLine {
        val sig = signature.coerceIn(2, 6)
        val padded = cells.toMutableList()
        if (padded.isEmpty()) padded.addAll(List(sig) { "" })
        while (padded.size % sig != 0) padded.add("")
        val text = StringBuilder()
        val ranges = mutableListOf<IntRange>()
        padded.forEachIndexed { index, cell ->
            val start = text.length
            text.append(cell)
            if (index in inferredBeats && cell.isNotEmpty()) {
                ranges.add(start until text.length)
            }
            if (index < padded.lastIndex) {
                val endOfMeasure = (index + 1) % sig == 0
                text.append(if (endOfMeasure) " | " else separatorAfter(index, sig))
            }
        }
        return FormattedLine(text.toString(), ranges)
    }

    fun parse(text: String, signature: Int): List<String> {
        val sig = signature.coerceIn(2, 6)
        if (text.isBlank()) return List(sig) { "" }
        val beats = text.split(Regex("[:;|│¦]")).map { it.trim() }.toMutableList()
        while (beats.size % sig != 0) beats.add("")
        return beats
    }
}
