package dev.anonrode.player.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MediaStateEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class MediaDatabase : RoomDatabase() {

    abstract fun mediaStateDao(): MediaStateDao

    companion object {
        /** v2: piecewise cut-segment storage for the auto-sync lock. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE media_state ADD COLUMN auto_sync_piecewise TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /** v3: explicit subtitle source choice (embedded/sidecar/online/none). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE media_state ADD COLUMN subtitle_choice TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        @Volatile private var instance: MediaDatabase? = null

        fun get(context: Context): MediaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MediaDatabase::class.java,
                    "media_db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
