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

    internal fun extends(base: Char, vowel: Char): Boolean {
        return "$base$vowel" in syllables
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
        val pieces = tokenize(compact) ?: return ""
        return spell(pieces)
    }

    private fun tokenize(text: String): List<Piece>? {
        val pieces = mutableListOf<Piece>()
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '.' || char == ';') {
                if (char == '.' && text.getOrNull(index + 1) == ',') {
                    pieces += Piece.Mark(".,")
                    index += 2
                } else if (char == ';') {
                    pieces += Piece.Mark(".,")
                    index++
                } else {
                    pieces += Piece.Mark(".")
                    index++
                }
                continue
            }
            val read = readNote(text, index) ?: return null
            pieces += Piece.Note(read.first)
            index = read.second
        }
        return pieces
    }

    private fun spell(pieces: List<Piece>): String {
        val notes = pieces.mapNotNull { (it as? Piece.Note)?.note }
        if (notes.isEmpty()) return ""
        val hasTime = pieces.any { it is Piece.Mark }
        if (!hasTime) {
            if (notes.all { it.syllable == "-" }) return "-"
            return equalBar(notes)
        }
        val written = StringBuilder()
        val group = mutableListOf<MappedNote>()
        fun flush() {
            if (group.isEmpty()) return
            written.append(group.joinToString(",") { token(it) })
            group.clear()
        }
        for (piece in pieces) {
            when (piece) {
                is Piece.Note -> group += piece.note
                is Piece.Mark -> {
                    flush()
                    written.append(piece.text)
                }
            }
        }
        flush()
        return written.toString()
    }

    private fun equalBar(notes: List<MappedNote>): String {
        val tokens = notes.map { token(it) }
        return when {
            tokens.size <= 1 -> tokens.firstOrNull().orEmpty()
            tokens.size % 2 == 1 || tokens.size == 2 -> tokens.joinToString(".")
            else -> tokens.chunked(2).joinToString(".") { pair -> pair.joinToString(",") }
        }
    }

    private fun readNote(text: String, start: Int): Pair<MappedNote, Int>? {
        var index = start
        if (index >= text.length) return null
        if (text[index] == '-') return MappedNote("-") to index + 1
        if (text[index] !in "drmfslt") return null
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
        } else if (octave == 0 && index < text.length && text[index] == ',') {
            octave = -1
            index++
        }
        return MappedNote(syllable, octave) to index
    }

    private sealed class Piece {
        class Note(val note: MappedNote) : Piece()
        class Mark(val text: String) : Piece()
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
