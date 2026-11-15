package mg.maniry.doremii.omr

data class MappedNote(val syllable: String, val octave: Int = 0)

object SolfaCellMapper {
    private val syllables = setOf("d", "di", "r", "ri", "m", "f", "fi", "s", "si", "l", "ta", "t")

    fun syllable(letter: String, vowel: String? = null): String? {
        val base = letter.lowercase()
        if (vowel != null) {
            val merged = base + vowel.lowercase()
            if (merged in syllables) return merged
        }
        return base.takeIf { it in syllables }
    }

    fun token(note: MappedNote): String {
        if (note.syllable == "-") return "-"
        val name = note.syllable
        val octave = note.octave.coerceIn(-3, 3)
        return when {
            octave == 0 -> name
            else -> "$name$octave"
        }
    }

    fun toCell(notes: List<MappedNote>, joins: List<Char> = emptyList()): String {
        val pitched = notes.filter { it.syllable != "-" }
        if (pitched.isEmpty()) {
            return if (notes.any { it.syllable == "-" }) "-" else ""
        }
        if (pitched.size == 1) return token(pitched[0])
        val cell = StringBuilder()
        pitched.forEachIndexed { index, note ->
            if (index > 0) {
                val join = joins.getOrNull(index - 1)
                cell.append(if (join == ',') ',' else '.')
            }
            cell.append(token(note))
        }
        return cell.toString()
    }
}
