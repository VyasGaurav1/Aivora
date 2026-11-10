package com.chronoit.aivora.scheduler

import android.content.Context
import androidx.room.*

@Entity(tableName = "scheduled_tasks")
data class ScheduledTask(
    @PrimaryKey val id: String,
    val encryptedText: ByteArray,
    val dueAt: Long,
    val repeatMillis: Long,
    val state: String = "PENDING",
    val lastDeliveryAt: Long = 0,
    val encryptedAction: ByteArray? = null,
    val encryptedConditions: ByteArray? = null,
)
@Dao interface TaskDao {
    @Insert suspend fun insert(task: ScheduledTask)
    @Query("SELECT * FROM scheduled_tasks WHERE state IN ('PENDING', 'BLOCKED', 'WAITING_CONDITIONS', 'AWAITING_REVIEW', 'STARTED', 'PAUSED') ORDER BY dueAt LIMIT 500") suspend fun active(): List<ScheduledTask>
    @Query("SELECT * FROM scheduled_tasks WHERE id = :id") suspend fun get(id: String): ScheduledTask?
    @Query("SELECT * FROM scheduled_tasks WHERE lastDeliveryAt >= :after AND lastDeliveryAt > 0 AND state != 'CANCELED' AND encryptedAction IS NULL ORDER BY lastDeliveryAt DESC LIMIT 500")
    suspend fun recentlyDelivered(after: Long): List<ScheduledTask>
    @Query("UPDATE scheduled_tasks SET state = :state, dueAt = :dueAt, lastDeliveryAt = :delivered WHERE id = :id")
    suspend fun update(id: String, state: String, dueAt: Long, delivered: Long)
    @Query("UPDATE scheduled_tasks SET state = 'CANCELED' WHERE id = :id") suspend fun cancel(id: String)
}
@Database(entities = [ScheduledTask::class], version = 3, exportSchema = true)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN encryptedAction BLOB") }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN encryptedConditions BLOB") }
        }
        fun open(context: Context) = Room.databaseBuilder(context.applicationContext, TaskDatabase::class.java, "aivora_tasks.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}
