package com.focusassistant.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
internal data class ProjectRow(@PrimaryKey val id: String, val payload: String, val position: Int)
@Entity(tableName = "todos")
internal data class TodoRow(@PrimaryKey val id: String, val payload: String, val position: Int)
@Entity(tableName = "sessions")
internal data class SessionRow(@PrimaryKey val id: String, val payload: String, val position: Int)
@Entity(tableName = "progress")
internal data class ProgressRow(@PrimaryKey val id: String, val payload: String, val position: Int)
@Entity(tableName = "settings")
internal data class SettingsRow(@PrimaryKey val id: Int = 1, val payload: String)
@Entity(tableName = "active_timer")
internal data class TimerRow(@PrimaryKey val id: Int = 1, val payload: String)
@Entity(tableName = "store_revision")
internal data class RevisionRow(@PrimaryKey val id: Int = 1, val revision: Long = 0)
@Entity(tableName = "diary")
internal data class DiaryRow(@PrimaryKey val id: String, val payload: String, val position: Int)

@Dao
internal interface FocusDao {
    @Query("SELECT * FROM projects ORDER BY position") suspend fun projects(): List<ProjectRow>
    @Query("SELECT * FROM todos ORDER BY position") suspend fun todos(): List<TodoRow>
    @Query("SELECT * FROM sessions ORDER BY position") suspend fun sessions(): List<SessionRow>
    @Query("SELECT * FROM progress ORDER BY position") suspend fun progress(): List<ProgressRow>
    @Query("SELECT * FROM settings WHERE id = 1") suspend fun settings(): SettingsRow?
    @Query("SELECT * FROM active_timer WHERE id = 1") suspend fun timer(): TimerRow?
    @Query("SELECT revision FROM store_revision WHERE id = 1") fun observeRevision(): Flow<Long?>
    @Query("SELECT revision FROM store_revision WHERE id = 1") suspend fun revision(): Long?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun initializeRevision(row: RevisionRow)
    @Query("UPDATE store_revision SET revision = revision + 1 WHERE id = 1") suspend fun bumpRevision()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putProjects(rows: List<ProjectRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTodos(rows: List<TodoRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSessions(rows: List<SessionRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putProgress(rows: List<ProgressRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSettings(row: SettingsRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTimer(row: TimerRow)
    @Query("DELETE FROM projects WHERE id IN (:ids)") suspend fun removeProjects(ids: List<String>)
    @Query("DELETE FROM todos WHERE id IN (:ids)") suspend fun removeTodos(ids: List<String>)
    @Query("DELETE FROM sessions WHERE id IN (:ids)") suspend fun removeSessions(ids: List<String>)
    @Query("DELETE FROM progress WHERE id IN (:ids)") suspend fun removeProgress(ids: List<String>)
    @Query("DELETE FROM active_timer") suspend fun clearTimer()
    @Query("SELECT * FROM diary ORDER BY position") suspend fun diaries(): List<DiaryRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDiaries(rows: List<DiaryRow>)
    @Query("DELETE FROM diary WHERE id IN (:ids)") suspend fun removeDiaries(ids: List<String>)
}

@Database(entities = [ProjectRow::class, TodoRow::class, SessionRow::class, ProgressRow::class, SettingsRow::class, TimerRow::class, RevisionRow::class, DiaryRow::class], version = 2, exportSchema = false)
internal abstract class FocusDatabase : RoomDatabase() {
    abstract fun dao(): FocusDao

    companion object {
        /** v1 → v2 只新增日记表，覆盖升级保留原有专注、待办与设置数据。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `diary` (`id` TEXT NOT NULL, `payload` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }
    }
}
