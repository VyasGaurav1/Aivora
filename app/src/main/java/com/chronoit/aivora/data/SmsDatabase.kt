package com.chronoit.aivora.data

import android.content.Context
import androidx.room.*

@Entity(tableName = "sms_attempts", indices = [Index(value = ["createdAt"])])
data class SmsAttempt(
    @PrimaryKey val id: String,
    val encryptedAction: ByteArray,
    val createdAt: Long,
    val state: String = "REQUESTED",
    val resultCode: Int? = null,
)
@Dao interface SmsAttemptDao {
    @Insert suspend fun insert(attempt: SmsAttempt)
    @Query("SELECT * FROM sms_attempts ORDER BY rowid DESC LIMIT 1") suspend fun latest(): SmsAttempt?
    @Query("SELECT * FROM sms_attempts WHERE id = :id") suspend fun get(id: String): SmsAttempt?
    @Query("DELETE FROM sms_attempts WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE sms_attempts SET state = :state, resultCode = :code WHERE id = :id AND state IN ('REQUESTED', 'UNKNOWN')")
    suspend fun sentResult(id: String, state: String, code: Int): Int
    @Query("UPDATE sms_attempts SET state = :state WHERE id = :id AND state = 'REQUESTED'")
    suspend fun uncertainOrNotRequested(id: String, state: String): Int
}
@Database(entities = [SmsAttempt::class], version = 1, exportSchema = true)
abstract class SmsDatabase : RoomDatabase() {
    abstract fun attempts(): SmsAttemptDao
    companion object {
        fun open(context: Context) = Room.databaseBuilder(context.applicationContext, SmsDatabase::class.java, "aivora_sms.db").build()
    }
}
