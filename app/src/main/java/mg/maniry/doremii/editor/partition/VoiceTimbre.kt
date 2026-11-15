package mg.maniry.doremii.editor.partition

data class VoiceTimbre(
    val soundFontId: String,
    val bank: Int,
    val program: Int,
    val legacyIndex: Int? = null,
) {
    fun serialize(): String {
        legacyIndex?.let { return it.toString() }
        return "$soundFontId@$bank.$program"
    }

    fun midiBankAndProgram(): Pair<Int, Int> {
        val legacy = legacyIndex
        if (legacy != null) {
            val item = InstrumentsList.list.getOrElse(legacy) { InstrumentsList.list.first() }
            return 0 to item.program
        }
        return bank.coerceAtLeast(0) to program.coerceIn(0, 127)
    }

    companion object {
        const val PIANO_ID = "piano"
        private val token = Regex("""^([A-Za-z0-9_-]+)@(\d+)\.(\d+)$""")

        fun piano() = VoiceTimbre(PIANO_ID, 0, 0)

        fun parseToken(raw: String): VoiceTimbre {
            val value = raw.trim()
            if (value.isEmpty()) return piano()
            value.toIntOrNull()?.let { index ->
                return VoiceTimbre(PIANO_ID, 0, 0, legacyIndex = index)
            }
            val match = token.matchEntire(value) ?: return piano()
            return VoiceTimbre(
                soundFontId = match.groupValues[1],
                bank = match.groupValues[2].toInt(),
                program = match.groupValues[3].toInt(),
            )
        }
    }
}
