package mg.maniry.doremii.omr

import kotlin.math.max
import kotlin.math.min

enum class DebugBounds { BOTH, LEFT }

data class DebugNoteBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val voiceIndex: Int,
    val voiceId: String,
    val musical: Boolean = false,
    val bounds: DebugBounds = DebugBounds.BOTH,
    val label: String = "",
)

data class DebugWindow(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val bounds: DebugBounds = DebugBounds.BOTH,
    val label: String = "",
)

data class DebugMeasureLine(
    val x: Float,
    val top: Float,
    val bottom: Float,
)

data class DebugGlyph(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val bounds: DebugBounds = DebugBounds.BOTH,
)

data class DebugLine(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val musical: Boolean = false,
)

data class DebugBlock(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class ScanDebug(
    val notes: List<DebugNoteBox> = emptyList(),
    val measures: List<DebugMeasureLine> = emptyList(),
    val beats: List<DebugMeasureLine> = emptyList(),
    val glyphs: List<DebugGlyph> = emptyList(),
    val windows: List<DebugWindow> = emptyList(),
    val lines: List<DebugLine> = emptyList(),
    val blocks: List<DebugBlock> = emptyList(),
)

data class PhotoRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

fun photoPoint(x: Float, y: Float, photoHeight: Float, quarterTurn: Boolean): Pair<Float, Float> {
    if (!quarterTurn) return x to y
    return y to (photoHeight - x)
}

fun photoRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    photoHeight: Float,
    quarterTurn: Boolean,
): PhotoRect {
    if (!quarterTurn) return PhotoRect(left, top, right, bottom)
    val x1 = top
    val x2 = bottom
    val y1 = photoHeight - right
    val y2 = photoHeight - left
    return PhotoRect(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
}
