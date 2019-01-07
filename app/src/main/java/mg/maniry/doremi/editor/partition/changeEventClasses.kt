package mg.maniry.doremi.editor.partition

import java.lang.Exception


data class ParserStructureEvent(
        val position: Int,
        var tempo: Int = -1,
        var key: Int = -1,
        var velocity: Int = -1)


class ChangeEvent(val position: Int, val type: String, val value: String = "", var treated: Boolean = false) {
    companion object {
        const val MOD = "MD"
        const val MVMT = "MV"
        const val DAL = "DL"
        const val SIGN = "SI"
        const val VELOCITY = "VEL"


        fun fromString(str: String): ChangeEvent? {
            val parts = str.split('_')
            val pos = if (parts[0] == "") null else parts[0].toInt()

            return if (pos == null)
                null
            else
                ChangeEvent(pos, parts[1], parts[2])
        }
    }


    fun isValid(): Boolean {
        if (type == MVMT) {
            return try {
                value.toInt()
                true
            } catch (e: Exception) {
                false
            }
        }

        return true
    }


    override fun toString() = "${position}_${type}_$value"
}
