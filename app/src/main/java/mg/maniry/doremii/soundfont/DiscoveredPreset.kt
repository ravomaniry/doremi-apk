package mg.maniry.doremii.soundfont

data class DiscoveredPreset(
    val bank: Int,
    val program: Int,
    val name: String,
) {
    companion object {
        fun parseRow(row: String): DiscoveredPreset? {
            val parts = row.split('\t', limit = 3)
            if (parts.size < 3) return null
            val bank = parts[0].toIntOrNull() ?: return null
            val program = parts[1].toIntOrNull() ?: return null
            val name = parts[2].ifBlank { "Program $program" }
            return DiscoveredPreset(bank, program, name)
        }
    }
}
