package mg.maniry.doremii.omr

import mg.maniry.doremii.editor.partition.PartitionData
import kotlin.math.ceil

object ScanScoreBuilder {
    fun build(
        voices: List<ParsedVoice>,
        signature: Int,
        keyIndex: Int,
        title: String,
    ): PartitionData {
        val data = PartitionData()
        val count = voices.size.coerceIn(1, 12)
        val sig = signature.coerceIn(2, 6)
        data.updateVoicesNum(count)
        data.signature.value = sig
        data.key.value = keyIndex.coerceIn(0, 11)
        data.tempo = 120
        data.swing.value = false
        data.lyrics.value = ""
        data.changeEvents.clear()
        data.songInfo.title.value = title
        val longest = voices.take(count).maxOfOrNull { it.cells.size } ?: 0
        val padded = if (longest == 0) 0 else ceil(longest / sig.toFloat()).toInt() * sig
        voices.take(count).forEachIndexed { index, voice ->
            val id = voice.id.ifBlank { PartitionData.voiceIds[index % PartitionData.voiceIds.size] }
            data.voices[index] = id
            val cells = voice.cells.toMutableList()
            while (cells.size < padded) cells.add("")
            data.notes[index] = cells
        }
        return data
    }
}
