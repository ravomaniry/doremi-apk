package mg.maniry.doremii.omr

data class OcrSymbol(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float = 1f,
    val inferred: Boolean = false,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val uncertain: Boolean get() = inferred || confidence < 0.55f

    fun plain(): String = text.trim()
}
