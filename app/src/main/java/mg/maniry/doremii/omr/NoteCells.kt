package mg.maniry.doremii.omr

import mg.maniry.doremii.editor.partition.PartitionData
import kotlin.math.min

data class NoteCell(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

object NoteCells {
    private const val trailingFraction = 0.45f

    fun grid(
        lines: List<DebugLine>,
        blocks: List<DebugBlock>,
        beats: List<DebugMeasureLine>,
    ): List<List<List<NoteCell>>> {
        return blocks.sortedBy { it.top }.mapNotNull { block ->
            val voices = lines
                .filter { it.musical && inBlock(it, block) }
                .sortedBy { it.top }
            if (voices.isEmpty()) return@mapNotNull null
            val left = voices.minOf { it.left }
            val right = voices.maxOf { it.right }
            val columns = beats
                .filter { overlaps(it, block) }
                .map { it.x }
                .let { unique(it) }
            val gaps = gaps(left, right, columns)
            if (gaps.isEmpty()) return@mapNotNull null
            voices.map { voice ->
                gaps.map { (start, end) -> NoteCell(start, voice.top, end, voice.bottom) }
            }
        }
    }

    fun read(
        systems: List<List<List<NoteCell>>>,
        recognize: (NoteCell) -> String,
    ): List<ParsedVoice> {
        val read = systems.map { voices ->
            voices.map { cells -> cells.map { normalize(recognize(it)) } }
        }
        val voiceCount = read.maxOfOrNull { it.size } ?: 0
        if (voiceCount == 0) return emptyList()
        val texts = List(voiceCount) { mutableListOf<String>() }
        for (system in read) {
            val width = system.maxOfOrNull { it.size } ?: 0
            for (index in 0 until voiceCount) {
                val row = system.getOrNull(index)
                if (row == null) {
                    repeat(width) { texts[index].add("") }
                } else {
                    texts[index].addAll(row)
                    repeat(width - row.size) { texts[index].add("") }
                }
            }
        }
        return texts.mapIndexed { index, cells ->
            ParsedVoice(
                id = PartitionData.voiceIds[index % PartitionData.voiceIds.size],
                cells = cells,
                inferredBeats = emptyList(),
            )
        }
    }

    private fun normalize(raw: String): String {
        if (raw.isBlank()) return ""
        return SolfaCellMapper.fromOcrText(raw)
    }

    private fun gaps(left: Float, right: Float, columns: List<Float>): List<Pair<Float, Float>> {
        if (right - left < 2f) return emptyList()
        val xs = columns.filter { it > left + 12f && it < right - 1.5f }
        if (xs.isEmpty()) return listOf(left to right)
        val gaps = mutableListOf<Pair<Float, Float>>()
        var cursor = left
        for (x in xs) {
            gaps += cursor to x
            cursor = x
        }
        val typical = typicalBeat(xs, left)
        if (typical > 1f && right - cursor >= typical * trailingFraction) {
            gaps += cursor to right
        }
        return gaps
    }

    private fun typicalBeat(xs: List<Float>, left: Float): Float {
        val between = xs.zipWithNext { start, end -> end - start }.filter { it > 1.5f }
        if (between.isNotEmpty()) return median(between)
        return (xs.first() - left).coerceAtLeast(0f)
    }

    private fun unique(xs: List<Float>): List<Float> {
        if (xs.isEmpty()) return emptyList()
        val sorted = xs.sorted()
        val kept = mutableListOf(sorted.first())
        for (x in sorted.drop(1)) {
            if (x - kept.last() > 1.5f) kept.add(x)
        }
        return kept
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun inBlock(line: DebugLine, block: DebugBlock): Boolean {
        val center = (line.top + line.bottom) / 2f
        return center >= block.top && center <= block.bottom
    }

    private fun overlaps(beat: DebugMeasureLine, block: DebugBlock): Boolean {
        val vertical = min(beat.bottom, block.bottom) - maxOf(beat.top, block.top)
        if (vertical <= 4f) return false
        return beat.x >= block.left - 2f && beat.x <= block.right + 2f
    }
}
