package mg.maniry.doremii.commonUtils

import android.content.Context


object PlaybackSettings {
    private const val PREFS = "playback"
    private const val LOOPS = "loops"
    private const val VELOCITY = "velocity"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loops(context: Context): Int = prefs(context).getInt(LOOPS, 0).coerceIn(0, 9)

    fun setLoops(context: Context, index: Int) {
        prefs(context).edit().putInt(LOOPS, index.coerceIn(0, 9)).apply()
    }

    fun velocity(context: Context): Boolean = prefs(context).getBoolean(VELOCITY, true)

    fun setVelocity(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(VELOCITY, enabled).apply()
    }
}
