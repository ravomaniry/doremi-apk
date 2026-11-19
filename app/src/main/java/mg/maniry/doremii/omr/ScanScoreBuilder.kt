package mg.maniry.doremii.omr

import mg.maniry.doremii.editor.partition.PartitionData
import kotlin.math.ceil

object ScanScoreBuilder {
    fun filename(title: String, singer: String, fallback: String): String {
        val cleanTitle = title.replace("/", "_").trim()
        if (cleanTitle.isEmpty()) return fallback
        val cleanSinger = singer.replace("/", "_").trim()
        return if (cleanSinger.isEmpty()) cleanTitle else "$cleanTitle - $cleanSinger"
    }

    fun build(
        voices: List<ParsedVoice>,
        signature: Int,
        keyIndex: Int,
        title: String,
        singer: String = "",
        author: String = "",
        compositor: String = "",
        tempo: Int = 120,
    ): PartitionData {
        val data = PartitionData()
        val count = voices.size.coerceIn(1, 12)
        val sig = signature.coerceIn(2, 6)
        data.updateVoicesNum(count)
        data.signature.value = sig
        data.key.value = keyIndex.coerceIn(0, 11)
        data.tempo = tempo
        data.swing.value = false
        data.lyrics.value = ""
        data.changeEvents.clear()
        data.songInfo.title.value = title
        data.songInfo.author.value = author
        data.songInfo.compositor.value = compositor
        data.songInfo.singer.value = singer
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
