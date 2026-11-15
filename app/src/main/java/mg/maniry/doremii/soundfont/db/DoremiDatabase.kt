package mg.maniry.doremii.soundfont.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SoundFontEntity::class, PresetEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class DoremiDatabase : RoomDatabase() {
    abstract fun soundFonts(): SoundFontDao

    companion object {
        @Volatile
        private var instance: DoremiDatabase? = null

        fun get(context: Context): DoremiDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DoremiDatabase::class.java,
                    "soundfonts.db",
                ).build().also { instance = it }
            }
        }
    }
}
