package mg.maniry.doremii.commonUtils


data class SongFile(
    val name: String,
    val modifiedAt: Long,
) {
    val untitled: Boolean get() = UNTITLED.matches(name)

    val title: String
        get() {
            val index = name.lastIndexOf(SEPARATOR)
            return if (index <= 0) name else name.substring(0, index)
        }

    val singer: String
        get() {
            val index = name.lastIndexOf(SEPARATOR)
            return if (index <= 0) "" else name.substring(index + SEPARATOR.length)
        }

    companion object {
        private const val SEPARATOR = " - "
        private val UNTITLED = Regex("^doremi_\\d+$")
    }
}
