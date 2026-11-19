package mg.maniry.doremii.omr

object OcrGlyphs {
    fun accept(raw: String): String {
        if (raw.isEmpty()) return ""
        return buildString(raw.length) {
            for (char in raw) {
                val mapped = when (char) {
                    '\u2019', '\u2032', '`', '\u00B4', '\u2018', '\u02BC' -> '\''
                    '\u2013', '\u2014', '\u2212', '_' -> '-'
                    '\u00B7', '\u2022', '\u22C5' -> '.'
                    '\uFF1A', '\u2236', '\u02D0' -> ':'
                    '\uFF1B' -> ';'
                    '\u2502', '\u00A6', '\u01C0', '\u2223' -> '|'
                    else -> char
                }
                if (mapped.isWhitespace()) continue
                if (mapped in allowed) append(mapped)
            }
        }
    }

    fun split(symbol: OcrSymbol): List<OcrSymbol> {
        val text = symbol.text
        if (text.length <= 1) return listOf(symbol)
        val width = symbol.width / text.length
        return text.mapIndexed { index, char ->
            val left = symbol.left + index * width
            val right = if (index == text.lastIndex) symbol.right else left + width
            symbol.copy(text = char.toString(), left = left, right = right)
        }
    }

    private const val allowed = "drmfsltDRMFSLTaeiouAEIOU',.-:;|lI123"
}
