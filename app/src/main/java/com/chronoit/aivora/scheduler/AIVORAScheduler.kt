package com.chronoit.aivora.scheduler

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.chronoit.aivora.R
import com.chronoit.aivora.security.FieldCipher
import com.chronoit.aivora.security.KeystoreKeys
import com.chronoit.aivora.ui.MainActivity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.chronoit.aivora.actions.*
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class Reminder(val id: String, val text: String, val dueAt: Long, val repeatMillis: Long, val state: String, val hasAction: Boolean = false, val conditions: TaskConditions = TaskConditions())
data class ScheduledReview(val plan: ActionPlan, val dueAt: Long)

/** Persistent definitions are authoritative; WorkManager supplies restart-safe, approximate wakeups. */
class AIVORAScheduler(private val context: Context, private val database: TaskDatabase = TaskDatabase.open(context)) {
    private val dao = database.tasks()
    private val cipher = FieldCipher(KeystoreKeys::dataKey)
    private val voiceJournal = VoiceReminderJournal(context)
    private val timing = ReminderTiming(context)
    fun preciseTimingAllowed() = timing.preciseAllowed()
    private val work get() = WorkManager.getInstance(context)
    fun notificationsAllowed(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "AIVORA reminders", NotificationManager.IMPORTANCE_DEFAULT))
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE &&
            (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    suspend fun create(text: String, delayMillis: Long, repeatMillis: Long = 0, conditions: TaskConditions = TaskConditions()): Reminder = mutex.withLock {
        require(text.isNotBlank() && text.length <= 2000)
        require(delayMillis in 1000..ScheduleTiming.MAX_DELAY)
        require(repeatMillis == 0L || repeatMillis in ScheduleTiming.MIN_REPEAT..ScheduleTiming.MAX_DELAY)
        require(dao.active().size < 500) { "Cancel an existing reminder before adding more." }
        val id = UUID.randomUUID().toString()
        val due = Math.addExact(System.currentTimeMillis(), delayMillis)
        dao.insert(ScheduledTask(id, cipher.encrypt(text, "task:$id"), due, repeatMillis,
            encryptedConditions = cipher.encrypt(conditions.encode(), "task:$id:conditions")))
        enqueue(id, due)
        recovery()
        Reminder(id, text, due, repeatMillis, "PENDING", conditions = conditions)
    }
    suspend fun scheduleAction(action: Action, description: String, delayMillis: Long, conditions: TaskConditions = TaskConditions(), repeatMillis: Long = 0): Reminder = mutex.withLock {
        require(action !is Action.PaymentHandoff) { "Payment requests need a fresh immediate review, not a saved schedule." }
        require(action !is Action.ForgetPersonalFact) { "Memory deletion needs fresh immediate review." }
        require(action !is Action.SendSms) { "Direct SMS needs fresh immediate review." }
        require(description.isNotBlank() && description.length <= 2000 && delayMillis in 1000..ScheduleTiming.MAX_DELAY)
        require(repeatMillis == 0L || repeatMillis in ScheduleTiming.MIN_REPEAT..ScheduleTiming.MAX_DELAY)
        require(dao.active().size < 500)
        val requiredConditions = if (action.requiresNetwork() && conditions.network == RequiredNetwork.NONE) conditions.copy(network = RequiredNetwork.VALIDATED) else conditions
        val encoded = ActionCodec.encode(action)
        val fingerprint = targetFingerprint(action)
        val id = UUID.randomUUID().toString()
        val due = Math.addExact(System.currentTimeMillis(), delayMillis)
        val payload = encoded + "\nAIVORA_TARGET_1\n" + fingerprint + "\nAUTH_REQUIRE_FRESH_OWNER_REVIEW\nNETWORK_FROM_SAVED_CONDITIONS\n" + requiredConditions.encode()
        dao.insert(ScheduledTask(id, cipher.encrypt(description, "task:$id"), due, repeatMillis,
            encryptedAction = cipher.encrypt(payload, "task:$id:action"), encryptedConditions = cipher.encrypt(requiredConditions.encode(), "task:$id:conditions")))
        enqueue(id, due); recovery()
        Reminder(id, description, due, repeatMillis, "PENDING", true, requiredConditions)
    }
    private fun storedAction(task: ScheduledTask): Pair<Action, String> {
        val parts = cipher.decrypt(requireNotNull(task.encryptedAction), "task:${task.id}:action").split("\nAIVORA_TARGET_1\n")
        require(parts.size == 2)
        val legacy = parts[1].endsWith("\nNETWORK_NONE")
        if (!legacy) requireNotNull(task.encryptedConditions)
        val policy = "\nAUTH_REQUIRE_FRESH_OWNER_REVIEW\n" + if (legacy) "NETWORK_NONE" else "NETWORK_FROM_SAVED_CONDITIONS\n" + conditions(task).encode()
        require(parts[1].endsWith(policy))
        return ActionCodec.decode(parts[0]) to parts[1].removeSuffix(policy)
    }
    private suspend fun targetFingerprint(action: Action): String {
        val app = context.applicationContext as com.chronoit.aivora.AivoraApplication
        when (action) {
            is Action.SetMobileData -> require(com.chronoit.aivora.connectivity.MobileDataGuard.matches(action.target, com.chronoit.aivora.connectivity.AndroidMobileDataMonitor(context).snapshot(), action.enabled))
            is Action.PlaceCall -> {
                require(action.contactId == null || app.contacts.stillMatches(action.contactId, action.phoneNumber))
                require(com.chronoit.aivora.platform.AndroidVoiceCallMonitor(context).selection()?.target == action.target)
            }
            is Action.Dial -> require(action.contactId == null || app.contacts.stillMatches(action.contactId, action.phoneNumber))
            is Action.DraftMessage -> require(action.contactId == null || app.contacts.stillMatches(action.contactId, action.phoneNumber))
            is Action.DraftAppMessage -> {
                require(action.contactId == null || app.contacts.stillMatches(action.contactId, action.phoneNumber))
                require(context.packageManager.getLaunchIntentForPackage(action.app.packageName) != null)
            }
            is Action.OpenApp -> require(context.packageManager.getLaunchIntentForPackage(action.packageName) != null)
            else -> Unit
        }
        val uri = when (action) { is Action.OpenFile -> action.uri; is Action.ShareFile -> action.uri; else -> return "NO_FILE" }
        val file = requireNotNull(app.documents.authorized(uri))
        require(app.documents.readable(file))
        val mime = when (action) { is Action.OpenFile -> action.mimeType; is Action.ShareFile -> action.mimeType; else -> error("Invalid file action") }
        require(file.mimeType == mime)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(android.net.Uri.parse(uri)).use { input ->
            requireNotNull(input)
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= 256L * 1024 * 1024) { "Scheduled file exceeds the verification limit" }
                digest.update(buffer, 0, count)
            }
        }
        val recipient = if (action is Action.ShareFile && action.recipientName != null) {
            val resolved = com.chronoit.aivora.brain.ContactResolver.resolve(action.recipientName, app.contacts.search(action.recipientName))
            require(resolved is com.chronoit.aivora.brain.ContactResolution.Resolved) { "Scheduled recipient is ambiguous or unavailable" }
            "|${resolved.contact.id}|${resolved.contact.number}|${resolved.contact.name}"
        } else ""
        return digest.digest().joinToString("") { "%02x".format(it) } + recipient
    }
    suspend fun reviewAction(id: String, allowNetworkSetup: Boolean = false, expectedDueAt: Long? = null): ScheduledReview = mutex.withLock {
        val task = requireNotNull(dao.get(id))
        require(expectedDueAt == null || task.dueAt == expectedDueAt) { "Scheduled occurrence changed; request fresh choices" }
        require(task.encryptedAction != null && task.state in ScheduledReviewPolicy.states && task.dueAt <= System.currentTimeMillis())
        val (action, fingerprint) = storedAction(task)
        val savedConditions = conditions(task)
        val snapshot = conditionSnapshot()
        require(savedConditions.satisfied(snapshot) || (allowNetworkSetup && savedConditions.permitsConnectionReview(snapshot, action.requiresNetwork()))) { "Task conditions no longer hold" }
        require(targetFingerprint(action) == fingerprint) { "Scheduled target changed; create a new task" }
        if (task.state != "AWAITING_REVIEW") dao.update(id, "AWAITING_REVIEW", task.dueAt, task.lastDeliveryAt)
        ScheduledReview(ActionPlan(listOf(action), 1.0, "Scheduled action; fresh owner approval required"), task.dueAt)
    }
    suspend fun reviewStillValid(id: String, expectedDueAt: Long, plan: ActionPlan): Boolean = mutex.withLock {
        val task = dao.get(id) ?: return@withLock false
        if (task.state != "AWAITING_REVIEW" || task.dueAt != expectedDueAt || task.dueAt > System.currentTimeMillis()) return@withLock false
        if (!conditions(task).satisfied(conditionSnapshot())) return@withLock false
        val (action, fingerprint) = storedAction(task)
        plan.actions == listOf(action) && targetFingerprint(action) == fingerprint && conditions(task).satisfied(conditionSnapshot())
    }
    suspend fun executeApproved(id: String, action: Action, executor: ActionExecutor, expectedDueAt: Long): ActionResult = mutex.withLock {
        val task = dao.get(id) ?: return@withLock ActionResult(ExecutionStatus.CANCELLED, "Scheduled task no longer exists.")
        if (task.state != "AWAITING_REVIEW" || task.dueAt != expectedDueAt || task.dueAt > System.currentTimeMillis()) return@withLock ActionResult(ExecutionStatus.CANCELLED, "Scheduled occurrence was canceled, changed or already handled.")
        if (!conditions(task).satisfied(conditionSnapshot())) return@withLock ActionResult(ExecutionStatus.FAILED, "Task conditions no longer hold. Review again when they are satisfied.")
        val (stored, fingerprint) = storedAction(task)
        if (stored != action || targetFingerprint(action) != fingerprint) return@withLock ActionResult(ExecutionStatus.FAILED, "Scheduled target changed. Create a new task.")
        if (!conditions(task).satisfied(conditionSnapshot())) return@withLock ActionResult(ExecutionStatus.FAILED, "Task conditions changed during verification. Review again when they hold.")
        if (task.dueAt > System.currentTimeMillis()) return@withLock ActionResult(ExecutionStatus.CANCELLED, "The clock changed during verification. Wait until the saved due time.")
        dao.update(id, "STARTED", task.dueAt, task.lastDeliveryAt)
        // Commit before side effects. Interrupted/uncertain actions are never retried automatically.
        val result = executor.execute(action)
        val now = System.currentTimeMillis()
        if (task.repeatMillis > 0 && result.status in setOf(ExecutionStatus.SUCCESS, ExecutionStatus.REQUIRES_USER_INTERACTION)) {
            val next = ScheduleTiming.afterExecution(task.dueAt, task.repeatMillis, now)
            dao.update(id, "PENDING", next, now)
            enqueue(id, next, replace = true); recovery()
        } else dao.update(id, if (task.repeatMillis > 0) "PAUSED" else "HANDLED", task.dueAt, now)
        context.getSystemService(NotificationManager::class.java).cancel(id, NOTIFICATION_ID)
        result
    }
    private fun conditions(task: ScheduledTask) = task.encryptedConditions?.let { TaskConditions.decode(cipher.decrypt(it, "task:${task.id}:conditions")) } ?: TaskConditions()
    fun conditionSnapshot(): ConditionSnapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        val plugged = (battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val charging = plugged && (status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL)
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        return ConditionSnapshot(charging, capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true &&
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET), capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true)
    }
    suspend fun list(): List<Reminder> = dao.active().map { Reminder(it.id, cipher.decrypt(it.encryptedText, "task:${it.id}"), it.dueAt, it.repeatMillis, it.state, it.encryptedAction != null, conditions(it)) }
    suspend fun cancel(id: String) = mutex.withLock {
        dao.cancel(id)
        timing.cancel(id)
        work.cancelUniqueWork("aivora.reminder.alarm.$id")
        work.cancelUniqueWork("aivora.reminder.$id")
        work.cancelAllWorkByTag("aivora.voice.reminder.$id")
        context.getSystemService(NotificationManager::class.java).cancel(id, NOTIFICATION_ID)
        if (dao.active().isEmpty()) work.cancelUniqueWork("aivora.scheduler.recovery")
    }
    suspend fun restore(reschedule: Boolean = false) = mutex.withLock {
        val active = dao.active()
        val waiting = active.filter { it.state in setOf("PENDING", "BLOCKED", "WAITING_CONDITIONS") }
        waiting.forEach { enqueue(it.id, it.dueAt, reschedule || it.state == "BLOCKED" && notificationsAllowed()) }
        val speechPending = dao.recentlyDelivered(maxOf(voiceJournal.enabledSince, System.currentTimeMillis() - 86400000L))
            .filter { voiceJournal.status(it.id, it.lastDeliveryAt) == "PENDING" }
        speechPending.forEach { enqueueSpeech(it.id, it.lastDeliveryAt) }
        if (waiting.isNotEmpty() || speechPending.isNotEmpty()) recovery() else work.cancelUniqueWork("aivora.scheduler.recovery")
    }
    private fun recovery() {
        work.enqueueUniquePeriodicWork("aivora.scheduler.recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderRecoveryWorker>(15, TimeUnit.MINUTES).build())
    }
    private suspend fun enqueue(id: String, dueAt: Long, replace: Boolean = false) {
        val conditions = dao.get(id)?.let(::conditions) ?: return
        val constraints = Constraints.Builder().setRequiresCharging(conditions.charging).setRequiredNetworkType(when (conditions.network) {
            RequiredNetwork.NONE -> NetworkType.NOT_REQUIRED
            RequiredNetwork.VALIDATED -> NetworkType.CONNECTED
            RequiredNetwork.UNMETERED -> NetworkType.UNMETERED
        }).build()
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInputData(workDataOf("taskId" to id))
            .setConstraints(constraints)
            .setInitialDelay((dueAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS).build()
        work.enqueueUniqueWork("aivora.reminder.$id", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        timing.schedule(id, dueAt)
    }
    suspend fun deliver(id: String): Boolean = mutex.withLock {
        val task = dao.get(id) ?: return@withLock true
        if (task.state !in setOf("PENDING", "BLOCKED", "WAITING_CONDITIONS")) return@withLock true
        val now = System.currentTimeMillis()
        if (task.dueAt > now) return@withLock false
        if (!conditions(task).satisfied(conditionSnapshot())) {
            dao.update(id, "WAITING_CONDITIONS", task.dueAt, task.lastDeliveryAt)
            return@withLock false
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val allowed = notificationsAllowed()
        if (!allowed) { dao.update(id, "BLOCKED", task.dueAt, task.lastDeliveryAt); return@withLock false }
        val text = cipher.decrypt(task.encryptedText, "task:$id")
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setData(android.net.Uri.parse("aivora://reminder/$id")), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_aivora)
            .setContentTitle(if (task.encryptedAction == null) "AIVORA reminder" else "AIVORA action ready for review")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text + if (task.encryptedAction != null) "\nOpen Reminders to review. No action has run." else ""))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        manager.notify(id, NOTIFICATION_ID, notification)
        // Posting is asynchronous on Android. Await observable acceptance, not just notify() returning.
        val posted = kotlinx.coroutines.withTimeoutOrNull(2000) {
            while (manager.activeNotifications.none { it.tag == id && it.id == NOTIFICATION_ID }) kotlinx.coroutines.delay(20)
            true
        } ?: false
        if (!posted) return@withLock false
        timing.cancel(id)
        val next = if (task.repeatMillis > 0 && task.encryptedAction == null) ScheduleTiming.nextOccurrence(task.dueAt, task.repeatMillis, now) else task.dueAt
        dao.update(id, if (task.encryptedAction != null) "AWAITING_REVIEW" else if (task.repeatMillis > 0) "PENDING" else "DELIVERED", next, now)
        if (task.encryptedAction == null) {
            if (task.repeatMillis > 0) timing.schedule(id, next)
            enqueueSpeech(id, now); recovery()
        }
        // Recovery enqueues recurring instances, avoiding replacement of this worker while it is running.
        true
    }
    private fun enqueueSpeech(id: String, delivery: Long) {
        work.enqueueUniqueWork("aivora.voice.$id.$delivery", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SpokenReminderWorker>().setInputData(workDataOf("taskId" to id, "delivery" to delivery))
                .addTag("aivora.voice.reminder.$id").build())
    }
    internal suspend fun voiceState(id: String): String? = dao.get(id)?.let { voiceJournal.status(id, it.lastDeliveryAt) }
    internal suspend fun speakDelivered(id: String, delivery: Long): Boolean {
        if (voiceJournal.status(id, delivery) != "PENDING") return true
        val task = dao.get(id)
        if (task == null || task.state == "CANCELED" || task.encryptedAction != null || task.lastDeliveryAt != delivery) {
            voiceJournal.complete(id, delivery, "SKIPPED"); return true
        }
        val completed = kotlinx.coroutines.withTimeoutOrNull(180000) {
            val played = ReminderSpeaker(context).speak(cipher.decrypt(task.encryptedText, "task:$id")) {
                val current = dao.get(id)
                current != null && current.state != "CANCELED" && current.lastDeliveryAt == delivery
            }
            if (!played) voiceJournal.complete(id, delivery, if (dao.get(id)?.state == "CANCELED") "SKIPPED" else "ACKNOWLEDGED")
            true
        } ?: false
        if (completed && voiceJournal.status(id, delivery) == "PENDING") voiceJournal.complete(id, delivery)
        return completed
    }
    companion object {
        private val mutex = Mutex()
        private const val CHANNEL = "aivora_reminders"
        private const val NOTIFICATION_ID = 8101
    }
}

class SpokenReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val id = inputData.getString("taskId") ?: return Result.failure()
        val delivered = inputData.getLong("delivery", 0)
        if (delivered == 0L) Result.failure()
        else if ((applicationContext as com.chronoit.aivora.AivoraApplication).scheduler.speakDelivered(id, delivered)) Result.success()
        else Result.retry()
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
}

class ReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val id = inputData.getString("taskId") ?: return Result.failure()
        if ((applicationContext as com.chronoit.aivora.AivoraApplication).scheduler.deliver(id)) Result.success() else Result.retry()
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
}
class ReminderRecoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        (applicationContext as com.chronoit.aivora.AivoraApplication).scheduler.restore(inputData.getBoolean("reschedule", false)); Result.success()
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
}
