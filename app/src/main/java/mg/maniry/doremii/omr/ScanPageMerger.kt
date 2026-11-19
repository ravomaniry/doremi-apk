package mg.maniry.doremii.omr

import mg.maniry.doremii.editor.partition.PartitionData
import kotlin.math.ceil

object ScanPageMerger {
    fun merge(pages: List<SolfaParse>, signatures: List<Int>, fallbackSignature: Int = 4): SolfaParse {
        val signature = (signatures.firstOrNull() ?: fallbackSignature).coerceIn(2, 6)
        val ids = linkedSetOf<String>()
        pages.forEach { page -> page.voices.forEach { ids += it.id } }
        val order = PartitionData.voiceIds.filter { it in ids } + ids.filter { it !in PartitionData.voiceIds }
        if (order.isEmpty()) {
            return SolfaParse(emptyList(), pages.firstNotNullOfOrNull { it.keyIndex }, empty = true)
        }
        val cells = order.associateWith { mutableListOf<String>() }
        val inferred = order.associateWith { mutableListOf<Int>() }
        pages.forEach { page ->
            val pageLength = page.voices.maxOfOrNull { it.cells.size } ?: 0
            val padded = if (pageLength == 0) 0 else ceil(pageLength / signature.toFloat()).toInt() * signature
            val base = cells.values.maxOf { it.size }
            order.forEach { id ->
                val list = cells.getValue(id)
                while (list.size < base) list.add("")
            }
            if (padded == 0) return@forEach
            order.forEach { id ->
                val voice = page.voices.find { it.id == id }
                val source = voice?.cells.orEmpty()
                val start = cells.getValue(id).size
                val row = cells.getValue(id)
                row.addAll(source)
                while (row.size < start + padded) row.add("")
                voice?.inferredBeats?.forEach { beat ->
                    if (beat in source.indices) inferred.getValue(id).add(start + beat)
                }
            }
        }
        val voices = order.map { id -> ParsedVoice(id, cells.getValue(id).toList(), inferred.getValue(id).toList()) }
        val empty = voices.all { voice -> voice.cells.all { it.isBlank() } }
        return SolfaParse(
            voices = if (empty) emptyList() else voices,
            keyIndex = pages.firstNotNullOfOrNull { it.keyIndex },
            empty = empty,
        )
    }
}
