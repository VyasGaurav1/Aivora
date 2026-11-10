package com.chronoit.aivora.actions

import java.time.LocalTime
import java.util.UUID

enum class RiskLevel { LOW, MEDIUM, HIGH, DESTRUCTIVE, CRITICAL }
enum class MediaOperation { PLAY, PAUSE, NEXT, PREVIOUS }
enum class SettingsPanel { GENERAL, INTERNET, BLUETOOTH, NOTIFICATION_ACCESS, ACCESSIBILITY, APP_PERMISSIONS, BRIGHTNESS_ACCESS, DEFAULT_APPS, DEVICE_SECURITY }

sealed interface Action {
    data class ReplyToNotification(val app: MessagingApp, val title: String, val incoming: String, val body: String, val token: String) : Action
    data class SendSms(val phoneNumber: String, val body: String, val target: com.chronoit.aivora.platform.SmsTarget, val contactId: String? = null, val contactName: String? = null) : Action
    data class ForgetPersonalFact(val subject: String, val summary: String, val recordCount: Int, val fingerprint: String) : Action
    data class PaymentHandoff(val app: PaymentApp, val request: PaymentRequest) : Action
    data class Flashlight(val enabled: Boolean) : Action
    data class SetWifi(val enabled: Boolean) : Action
    data class SetMobileData(val enabled: Boolean, val target: com.chronoit.aivora.connectivity.MobileDataTarget) : Action
    data class SetVolume(val percent: Int) : Action
    data class SetBrightness(val percent: Int) : Action
    data class OpenApp(val packageName: String) : Action
    data class YouTubeSearch(val query: String) : Action
    data class YouTubeVideo(val videoId: String) : Action
    data class YouTubePlayback(val operation: com.chronoit.aivora.automation.PlaybackControl) : Action
    data class Media(val operation: MediaOperation) : Action
    data class PlaceCall(val phoneNumber: String, val target: com.chronoit.aivora.platform.VoiceCallTarget, val contactId: String? = null, val contactName: String? = null, val speakerphone: Boolean = false) : Action
    data class Dial(val phoneNumber: String, val contactId: String? = null, val contactName: String? = null, val speakerphone: Boolean = false) : Action
    data class DraftMessage(val phoneNumber: String, val body: String, val contactId: String? = null, val contactName: String? = null, val draftOnly: Boolean = false) : Action
    data class DraftAppMessage(val app: MessagingApp, val phoneNumber: String, val body: String, val contactId: String? = null, val contactName: String? = null) : Action
    data class Alarm(val time: LocalTime, val label: String) : Action
    data class OpenSettings(val panel: SettingsPanel) : Action
    data object Camera : Action
    data object ReadNotifications : Action
    data class FindFile(val query: String) : Action
    data class OpenFile(val uri: String, val mimeType: String, val displayName: String? = null) : Action
    data class ShareFile(val uri: String, val mimeType: String, val recipientName: String? = null, val displayName: String? = null) : Action
}

fun Action.requiresNetwork(): Boolean = this is Action.YouTubeSearch || this is Action.YouTubeVideo || this is Action.DraftAppMessage || this is Action.PaymentHandoff || this is Action.ReplyToNotification

internal fun validReplyText(text: String, maxLength: Int): Boolean =
    text.length <= maxLength && text.isNotBlank() && text.none(Char::isISOControl)

data class ActionPlan(
    val actions: List<Action>,
    val confidence: Double,
    val reasoningSummary: String,
    val planId: String = UUID.randomUUID().toString(),
) {
    val riskLevel: RiskLevel get() = actions.maxOfOrNull { it.risk() } ?: RiskLevel.LOW
}

fun Action.risk(): RiskLevel = when (this) {
    is Action.ForgetPersonalFact -> RiskLevel.DESTRUCTIVE
    is Action.Dial, is Action.DraftMessage, is Action.DraftAppMessage, is Action.SetBrightness, is Action.SetWifi -> RiskLevel.MEDIUM
    is Action.PaymentHandoff -> RiskLevel.CRITICAL
    is Action.ShareFile, is Action.SetMobileData, is Action.PlaceCall, is Action.SendSms, is Action.ReplyToNotification -> RiskLevel.HIGH
    is Action.OpenFile -> if (mimeType == "application/vnd.android.package-archive") RiskLevel.HIGH else RiskLevel.LOW
    else -> RiskLevel.LOW
}

object PlanValidator {
    fun error(plan: ActionPlan): String? {
        if (plan.planId.isBlank() || plan.actions.isEmpty() || plan.actions.size > 20) return "Invalid action plan size or identifier."
        if (!plan.confidence.isFinite() || plan.confidence !in 0.0..1.0) return "Invalid plan confidence."
        if (plan.actions.any { it is Action.ForgetPersonalFact } && plan.actions.size != 1) return "Review memory deletion separately from other actions."
        if (plan.actions.any { it is Action.PaymentHandoff } && plan.actions.size != 1) return "Review payment requests separately from other actions."
        if (plan.actions.any { it is Action.PlaceCall } && plan.actions.size != 1) return "Review phone calls separately from other actions."
        if (plan.actions.any { it is Action.SendSms } && plan.actions.size != 1) return "Review SMS sends separately from other actions."
        if (plan.actions.any { it is Action.ReplyToNotification } && plan.actions.size != 1) return "Review notification replies separately."
        for (action in plan.actions) {
            val valid = when (action) {
                is Action.SendSms -> action.target.valid() && internationalPhone(action.phoneNumber) == action.phoneNumber && action.body.isNotBlank() && action.body.length <= 160 && validContact(action.contactId, action.contactName)
                is Action.ReplyToNotification -> validReplyText(action.title, 120) && validReplyText(action.incoming, 500) &&
                    validReplyText(action.body, 500) && action.token.matches(Regex("[0-9a-f-]{36}"))
                is Action.ForgetPersonalFact -> action.subject.matches(Regex("[\\p{L}\\p{N}][\\p{L}\\p{N} '\u2019-]{0,119}")) && action.summary.isNotBlank() && action.summary.length <= 2000 && action.summary.none(Char::isISOControl) && action.recordCount > 0 && action.fingerprint.matches(Regex("[a-f0-9]{64}"))
                is Action.PaymentHandoff -> action.request.valid()
                is Action.SetVolume -> action.percent in 0..100
                is Action.SetBrightness -> action.percent in 0..100
                is Action.SetMobileData -> action.target.valid()
                is Action.OpenApp -> action.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
                is Action.YouTubeSearch -> action.query.isNotBlank() && action.query.length <= 300 && action.query.none(Char::isISOControl)
                is Action.YouTubeVideo -> action.videoId.matches(Regex("[A-Za-z0-9_-]{11}"))
                is Action.PlaceCall -> action.target.valid() && internationalPhone(action.phoneNumber) == action.phoneNumber && validContact(action.contactId, action.contactName)
                is Action.Dial -> action.phoneNumber.matches(Regex("\\+?[0-9 ()-]{3,30}")) && validContact(action.contactId, action.contactName)
                is Action.DraftMessage -> action.phoneNumber.matches(Regex("\\+?[0-9 ()-]{3,30}")) && action.body.isNotBlank() && action.body.length <= 4000 && validContact(action.contactId, action.contactName)
                is Action.DraftAppMessage -> internationalPhone(action.phoneNumber) != null && action.body.isNotBlank() && action.body.length <= 4000 && validContact(action.contactId, action.contactName)
                is Action.Alarm -> action.label.length <= 200
                is Action.FindFile -> action.query.isNotBlank() && action.query.length <= 200
                is Action.OpenFile -> validContentUri(action.uri) && validMime(action.mimeType)
                is Action.ShareFile -> validContentUri(action.uri) && validMime(action.mimeType) && (action.recipientName == null || action.recipientName.length <= 100)
                else -> true
            }
            if (!valid) return "Invalid parameters for ${action.javaClass.simpleName}."
        }
        return null
    }
    private fun validContentUri(uri: String) = uri.startsWith("content://") && uri.length <= 4096 && uri.none(Char::isISOControl)
    private fun validMime(mime: String) = mime.matches(Regex("[a-zA-Z0-9.+*-]+/[a-zA-Z0-9.+*-]+"))
    private fun validContact(id: String?, name: String?) = (id == null || id.matches(Regex("[0-9]{1,30}"))) && (name == null || name.length <= 100)
}
