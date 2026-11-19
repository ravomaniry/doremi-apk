package mg.maniry.doremii.soundfont.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sound_fonts")
data class SoundFontEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val sourceUrl: String?,
    val bundled: Boolean,
    val active: Boolean,
)

@Entity(
    tableName = "presets",
    foreignKeys = [
        ForeignKey(
            entity = SoundFontEntity::class,
            parentColumns = ["id"],
            childColumns = ["soundFontId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("soundFontId")],
)
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val soundFontId: String,
    val bank: Int,
    val program: Int,
    val name: String,
)
