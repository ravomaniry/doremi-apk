package mg.maniry.doremii.omr

interface InkImage {
    val width: Int
    val height: Int
    fun isDark(x: Int, y: Int): Boolean

    /** 0 is black and 255 is white. Drawn test ink counts as black. */
    fun shade(x: Int, y: Int): Int = if (isDark(x, y)) 0 else 255
}
