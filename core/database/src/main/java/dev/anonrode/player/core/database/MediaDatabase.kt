package dev.anonrode.player.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MediaStateEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class MediaDatabase : RoomDatabase() {

    abstract fun mediaStateDao(): MediaStateDao

    companion object {
        /** v2: piecewise cut-segment storage for the auto-sync lock.
         *  Also adds auto_sync_speed_factor: the entity gained the column
         *  in the v0.3.0 drift work while the schema version was still 1,
         *  so v1-era databases never received it via migration — Room's
         *  post-migration schema validation crashed those installs on
         *  launch (IllegalStateException: missing column). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE media_state ADD COLUMN auto_sync_piecewise TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE media_state ADD COLUMN auto_sync_speed_factor REAL NOT NULL DEFAULT 1.0"
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

        /** v4: background-fingerprint "already checked" timestamp (see the
         *  entity). Same shape as MIGRATION_1_2/2_3 — a column the v3-era
         *  entity did not declare, added with the NOT NULL DEFAULT its
         *  @ColumnInfo default implies, so Room's post-migration schema
         *  validation passes (the v1-era missing-column crash class). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE media_state ADD COLUMN auto_sync_checked_at_ms INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** v5: persisted playlist shuffle + repeat mode. The player's Shuffle
         *  and Loop ribbon tools used to reset on every open; these columns
         *  make the choice survive a re-open. Same shape as the migrations
         *  above — NOT NULL with the DEFAULT its @ColumnInfo declares, so
         *  Room's post-migration schema validation passes on rows written by
         *  the v4-era entity. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE media_state ADD COLUMN shuffle_enabled INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE media_state ADD COLUMN repeat_mode INTEGER NOT NULL DEFAULT 0"
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
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                ).build().also { instance = it }
            }
    }
}
