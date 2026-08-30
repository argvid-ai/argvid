package ai.argvid.gen0.media.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Gen0Converters {
    @TypeConverter
    fun momentStatus(value: MomentDbStatus): String = value.name

    @TypeConverter
    fun momentStatus(value: String): MomentDbStatus = MomentDbStatus.valueOf(value)
}

@Database(
    entities = [SessionEntity::class, MomentEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Gen0Converters::class)
abstract class Gen0Database : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun momentDao(): MomentDao
}
