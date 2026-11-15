package mg.maniry.doremii.omr

interface InkImage {
    val width: Int
    val height: Int
    fun isDark(x: Int, y: Int): Boolean
}
