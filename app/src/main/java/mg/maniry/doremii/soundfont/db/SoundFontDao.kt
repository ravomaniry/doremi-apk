package mg.maniry.doremii.soundfont.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class SoundFontDao {
    @Query("SELECT * FROM sound_fonts ORDER BY displayName COLLATE NOCASE")
    abstract suspend fun all(): List<SoundFontEntity>

    @Query("SELECT * FROM sound_fonts WHERE id = :id")
    abstract suspend fun get(id: String): SoundFontEntity?

    @Query("SELECT * FROM sound_fonts WHERE active = 1 LIMIT 1")
    abstract suspend fun active(): SoundFontEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(font: SoundFontEntity)

    @Query("UPDATE sound_fonts SET active = 0")
    abstract suspend fun clearActive()

    @Query("UPDATE sound_fonts SET active = 1 WHERE id = :id")
    abstract suspend fun markActive(id: String)

    @Query("DELETE FROM sound_fonts WHERE id = :id")
    abstract suspend fun delete(id: String)

    @Query("SELECT * FROM presets WHERE soundFontId = :id ORDER BY bank, program, name")
    abstract suspend fun presets(id: String): List<PresetEntity>

    @Query("DELETE FROM presets WHERE soundFontId = :id")
    abstract suspend fun deletePresets(id: String)

    @Insert
    abstract suspend fun insertPresets(presets: List<PresetEntity>)

    @Transaction
    open suspend fun replacePresets(id: String, presets: List<PresetEntity>) {
        deletePresets(id)
        if (presets.isNotEmpty()) insertPresets(presets)
    }

    @Transaction
    open suspend fun setActive(id: String) {
        clearActive()
        markActive(id)
    }
}
