package mg.maniry.doremii.omr

import kotlin.math.roundToInt

internal object OcrMetrics {
    fun characterWidth(symbols: List<OcrSymbol>): Int? {
        val widths = letters(symbols).map { it.width }.sorted()
        if (widths.size < 2) return null
        return widths[widths.size / 2].roundToInt().takeIf { it >= 2 }
    }

    fun tallHeight(symbols: List<OcrSymbol>): Int? {
        val heights = letters(symbols).map { it.height }.sorted()
        if (heights.size < 2) return null
        val median = heights[heights.size / 2]
        val upper = heights.filter { it >= median }
        val ascenders = upper.filter { it >= median * 1.25f }
        val chosen = if (ascenders.size >= 2) ascenders else upper
        if (chosen.isEmpty()) return null
        return chosen[chosen.size / 2].roundToInt().takeIf { it >= 4 }
    }

    private fun letters(symbols: List<OcrSymbol>): List<OcrSymbol> {
        return symbols.filter { symbol ->
            val text = symbol.plain()
            text.any { it.isLetter() || it.isDigit() } && symbol.height >= 4f && symbol.width >= 2f
        }
    }
}
