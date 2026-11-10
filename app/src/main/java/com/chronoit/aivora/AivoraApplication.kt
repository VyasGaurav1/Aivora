package com.chronoit.aivora

import android.app.Application
import android.app.KeyguardManager
import com.chronoit.aivora.actions.ActionRegistry
import com.chronoit.aivora.data.*
import com.chronoit.aivora.diagnostics.RoomActionAudit
import com.chronoit.aivora.memory.AivoraMemory
import com.chronoit.aivora.platform.*
import com.chronoit.aivora.policy.ExecutionContext
import com.chronoit.aivora.security.*
import kotlinx.coroutines.*

class AivoraApplication : Application() {
    val visibility = AppVisibility()
    override fun onCreate() {
        super.onCreate(); registerActivityLifecycleCallbacks(visibility)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { scheduler.restore() }.onFailure { android.util.Log.w("AIVORA", "Could not restore scheduled reminders", it) }
        }
    }
    val scheduler by lazy { com.chronoit.aivora.scheduler.AIVORAScheduler(this) }
    val settings by lazy { SettingsRepository(this) }
    val database by lazy { AivoraDatabase.open(this) }
    val smsDatabase by lazy { SmsDatabase.open(this) }
    val smsJournal by lazy { SmsJournal(smsDatabase, FieldCipher(KeystoreKeys::dataKey)) }
    val memory by lazy { AivoraMemory(database, settings, FieldCipher(KeystoreKeys::dataKey)) }
    val conversationMemory by lazy { com.chronoit.aivora.memory.ConversationMemory(memory, settings) }
    val reasoning: com.chronoit.aivora.brain.LocalReasoningEngine by lazy { com.chronoit.aivora.brain.LlamaLocalReasoningEngine(this) }
    val contacts by lazy { ContactsDirectory(this) }
    val documents by lazy { LocalDocuments(this, database, settings, FieldCipher(KeystoreKeys::dataKey), SecureSearchIndex(KeystoreKeys::searchKey)) }
    val capabilities by lazy { DeviceCapabilityManager(this) }
    internal val webAnswers by lazy { com.chronoit.aivora.connectivity.SpokenWebAnswers(this) }
    // Foreground ownership is supplied by the activity, never by a planner/model.
    fun foregroundRegistry(isForeground: () -> Boolean) = ActionRegistry(AndroidActionExecutor(this), RoomActionAudit(database.auditDao()), {
        ExecutionContext(!getSystemService(KeyguardManager::class.java).isKeyguardLocked, trustedForegroundInput = isForeground())
    })
    fun scheduledRegistry(id: String, dueAt: Long, isForeground: () -> Boolean) = ActionRegistry(
        com.chronoit.aivora.actions.ActionExecutor { action -> scheduler.executeApproved(id, action, AndroidActionExecutor(this), dueAt) },
        RoomActionAudit(database.auditDao()), {
            ExecutionContext(!getSystemService(KeyguardManager::class.java).isKeyguardLocked, trustedForegroundInput = isForeground())
        }, requireConfirmation = true, reviewGuard = { plan -> scheduler.reviewStillValid(id, dueAt, plan) })
}
