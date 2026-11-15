package mg.maniry.doremii.soundfont

sealed class TimbreRow {
    data class Header(val title: String) : TimbreRow()
    data class Preset(val preset: DiscoveredPreset) : TimbreRow()
}

object TimbreGrouping {
    val categories = listOf(
        "Piano",
        "Chromatic Percussion",
        "Organ",
        "Guitar",
        "Bass",
        "Strings",
        "Ensemble",
        "Brass",
        "Reed",
        "Pipe",
        "Synth Lead",
        "Synth Pad",
        "Synth Effects",
        "Ethnic",
        "Percussive",
        "Sound Effects",
    )

    fun rows(presets: List<DiscoveredPreset>): List<TimbreRow> {
        if (!isGeneralMidi(presets)) {
            return presets.sortedWith(compareBy({ it.bank }, { it.program }))
                .map { TimbreRow.Preset(it) }
        }
        val result = mutableListOf<TimbreRow>()
        val melodic = presets.filter { it.bank == 0 }.sortedBy { it.program }
        melodic.groupBy { categoryIndex(it.program) }.toSortedMap().forEach { (index, items) ->
            result += TimbreRow.Header(categories[index])
            result += items.map { TimbreRow.Preset(it) }
        }
        val drums = presets.filter { it.bank == 128 }.sortedBy { it.program }
        if (drums.isNotEmpty()) {
            result += TimbreRow.Header("Drums")
            result += drums.map { TimbreRow.Preset(it) }
        }
        presets.filter { it.bank != 0 && it.bank != 128 }
            .groupBy { it.bank }
            .toSortedMap()
            .forEach { (bank, items) ->
                result += TimbreRow.Header("Bank $bank")
                result += items.sortedBy { it.program }.map { TimbreRow.Preset(it) }
            }
        return result
    }

    fun filter(rows: List<TimbreRow>, query: String): List<TimbreRow> {
        val needle = query.trim()
        if (needle.isEmpty()) return rows
        val result = mutableListOf<TimbreRow>()
        var header: TimbreRow.Header? = null
        var headerWritten = false
        for (row in rows) {
            when (row) {
                is TimbreRow.Header -> {
                    header = row
                    headerWritten = false
                }
                is TimbreRow.Preset -> {
                    if (!row.preset.name.contains(needle, ignoreCase = true)) continue
                    val section = header
                    if (section != null && !headerWritten) {
                        result += section
                        headerWritten = true
                    }
                    result += row
                }
            }
        }
        return result
    }

    private fun isGeneralMidi(presets: List<DiscoveredPreset>): Boolean {
        val melodic = presets.filter { it.bank == 0 && it.program in 0..127 }
        if (melodic.size < 16) return false
        return melodic.map { categoryIndex(it.program) }.distinct().size >= 3
    }

    private fun categoryIndex(program: Int): Int = (program / 8).coerceIn(0, categories.lastIndex)
}
