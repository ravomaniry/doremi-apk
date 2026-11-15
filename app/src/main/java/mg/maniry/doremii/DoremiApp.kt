package mg.maniry.doremii

import android.app.Application
import android.content.Context
import mg.maniry.doremii.commonUtils.FileManager
import mg.maniry.doremii.soundfont.SoundFontRepository

class DoremiApp : Application() {
    lateinit var soundFonts: SoundFontRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        FileManager.initDir(this)
        FileManager.migrateLegacySolfa()
        soundFonts = SoundFontRepository(this)
        soundFonts.start()
    }

    companion object {
        @Volatile
        var instance: DoremiApp? = null
            private set

        fun from(context: Context): DoremiApp = context.applicationContext as DoremiApp
    }
}
