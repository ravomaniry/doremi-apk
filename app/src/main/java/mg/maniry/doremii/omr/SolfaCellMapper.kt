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

    fun fromOcrText(raw: String): String {
        val compact = buildString(raw.length) {
            for (char in raw.trim().lowercase()) {
                val mapped = when (char) {
                    '’', '′', '`' -> '\''
                    '–', '—' -> '-'
                    else -> char
                }
                if (!mapped.isWhitespace()) append(mapped)
            }
        }
        if (compact.isEmpty()) return ""
        if (compact.all { it == '-' }) return "-"
        val notes = mutableListOf<MappedNote>()
        val joins = mutableListOf<Char>()
        var index = 0
        val first = readNote(compact) { index } ?: return ""
        index = first.second
        notes.add(first.first)
        while (index < compact.length) {
            val join = when (compact[index]) {
                '.', ';' -> '.'
                ',' -> ','
                else -> return ""
            }
            index++
            val next = readNote(compact) { index } ?: return ""
            index = next.second
            joins.add(join)
            notes.add(next.first)
        }
        return toCell(notes, joins)
    }

    private fun readNote(text: String, cursor: () -> Int): Pair<MappedNote, Int>? {
        var index = cursor()
        if (index >= text.length || text[index] !in "drmfslt") return null
        var syllable = text[index].toString()
        index++
        if (index < text.length) {
            val merged = syllable + text[index]
            if (merged in syllables) {
                syllable = merged
                index++
            }
        }
        var octave = 0
        while (index < text.length && text[index] == '\'' && octave < 3) {
            octave++
            index++
        }
        if (octave == 0 && index < text.length && text[index] in "123") {
            octave = text[index].digitToInt().coerceIn(1, 3)
            index++
        } else if (index < text.length && text[index] == ',') {
            val next = text.getOrNull(index + 1)
            if (next == null || next !in "drmfslt") {
                octave = -1
                index++
            }
        }
        return MappedNote(syllable, octave) to index
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
