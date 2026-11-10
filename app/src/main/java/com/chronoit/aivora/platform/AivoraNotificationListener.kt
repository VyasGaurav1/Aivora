package com.chronoit.aivora.platform

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.chronoit.aivora.actions.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

data class RecentNotification(val key: String, val packageName: String, val title: String, val text: String)

/** Current notifications only, in process memory. Nothing is copied into long-term memory. */
class AivoraNotificationListener : NotificationListenerService() {
    private data class ReplyTarget(val key: String, val posted: Long, val title: String, val incoming: String, val intent: PendingIntent, val inputKey: String)
    private data class ReplyBinding(val action: Action.ReplyToNotification, val target: ReplyTarget, val expires: Long)
    private var binding: ReplyBinding? = null

    override fun onListenerConnected() { connected = this; refresh() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { invalidate(sbn); refresh() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { invalidate(sbn); refresh() }
    override fun onListenerDisconnected() { detach() }
    override fun onDestroy() { detach(); super.onDestroy() }

    private fun detach() {
        binding = null
        if (connected === this) {
            connected = null
            current.value = emptyList()
        }
    }

    private fun invalidate(sbn: StatusBarNotification?) {
        if (sbn?.key == binding?.target?.key) binding = null
    }

    private fun messages(app: MessagingApp): List<StatusBarNotification>? {
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return null
        if (!getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(this, AivoraNotificationListener::class.java))) return null
        return activeNotifications.orEmpty().filter {
            it.packageName == app.packageName && it.user == Process.myUserHandle() &&
                it.notification.category == Notification.CATEGORY_MESSAGE &&
                it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
        }
    }

    private fun title(notification: StatusBarNotification): String? =
        notification.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun target(app: MessagingApp, expectedTitle: String, messages: List<StatusBarNotification>): ReplyTarget? {
        val notification = messages.singleOrNull {
            title(it)?.equals(expectedTitle, ignoreCase = true) == true
        } ?: return null
        val incoming = notification.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return null
        val reply = notification.notification.actions.orEmpty().singleOrNull {
            it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
        } ?: return null
        val input = reply.remoteInputs?.singleOrNull()?.takeIf { it.allowFreeFormInput } ?: return null
        val pending = reply.actionIntent ?: return null
        if (pending.creatorPackage != app.packageName || pending.creatorUserHandle != Process.myUserHandle()) return null
        if (Build.VERSION.SDK_INT >= 31 && (pending.isActivity || pending.isImmutable)) return null
        val displayedTitle = title(notification) ?: return null
        if (!validReplyText(displayedTitle, 120) || displayedTitle != displayedTitle.trim() ||
            displayedTitle.contains(" saying ", ignoreCase = true) || !validReplyText(incoming, 500)) return null
        return ReplyTarget(notification.key, notification.postTime, displayedTitle, incoming, pending, input.resultKey)
    }

    private fun replyTitles(app: MessagingApp): List<String>? {
        val messages = messages(app) ?: return null
        return messages.sortedByDescending { it.postTime }.asSequence()
            .mapNotNull(::title)
            .mapNotNull { target(app, it, messages)?.title }
            .take(5).toList()
    }

    private fun bind(app: MessagingApp, title: String, body: String): Action.ReplyToNotification? {
        binding = null
        val target = target(app, title, messages(app) ?: return null) ?: return null
        val action = Action.ReplyToNotification(app, target.title, target.incoming, body, UUID.randomUUID().toString())
        if (PlanValidator.error(ActionPlan(listOf(action), 1.0, "Live notification reply")) != null) return null
        binding = ReplyBinding(action, target, SystemClock.elapsedRealtime() + 120_000)
        return action
    }

    private fun submitReply(action: Action.ReplyToNotification): ActionResult {
        val reviewed = binding
        binding = null
        val currentTarget = messages(action.app)?.let { target(action.app, action.title, it) }
        if (reviewed == null || reviewed.action != action || SystemClock.elapsedRealtime() >= reviewed.expires || currentTarget != reviewed.target) {
            return ActionResult(ExecutionStatus.CANCELLED, "The notification changed or expired. Review a fresh reply.")
        }
        val input = RemoteInput.Builder(reviewed.target.inputKey).build()
        val results = Bundle().apply { putCharSequence(input.resultKey, action.body) }
        val fillIn = Intent()
        RemoteInput.addResultsToIntent(arrayOf(input), fillIn, results)
        RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
        return try {
            reviewed.target.intent.send(this, 0, fillIn)
            ActionResult(ExecutionStatus.PARTIAL, "Reply submitted to ${action.app.displayName}. Message delivery is unverified.")
        } catch (_: PendingIntent.CanceledException) {
            ActionResult(ExecutionStatus.CANCELLED, "Android canceled the notification action. Review a fresh reply.")
        }
    }
    private fun refresh() {
        current.value = activeNotifications.orEmpty().filter { it.packageName != packageName }
            .sortedByDescending { it.postTime }.take(20).map {
                RecentNotification(it.key, it.packageName,
                    it.notification.extras.getCharSequence("android.title")?.toString().orEmpty(),
                    it.notification.extras.getCharSequence("android.text")?.toString().orEmpty())
            }
    }
    companion object {
        private var connected: AivoraNotificationListener? = null
        suspend fun listReplyTitles(app: MessagingApp): List<String>? =
            withContext(Dispatchers.Main.immediate) { connected?.replyTitles(app) }

        suspend fun bindReply(app: MessagingApp, title: String, body: String): Action.ReplyToNotification? =
            withContext(Dispatchers.Main.immediate) { connected?.bind(app, title, body) }

        suspend fun reply(action: Action.ReplyToNotification): ActionResult = withContext(Dispatchers.Main.immediate) {
            connected?.submitReply(action) ?: ActionResult(ExecutionStatus.PERMISSION_MISSING, "Notification access is unavailable. Review a fresh reply after reconnecting it.")
        }

        private val current = MutableStateFlow<List<RecentNotification>>(emptyList())
        val notifications = current.asStateFlow()
    }
}
