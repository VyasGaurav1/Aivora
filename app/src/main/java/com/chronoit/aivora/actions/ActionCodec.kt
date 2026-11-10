package com.chronoit.aivora.actions

import java.util.Base64
import java.time.LocalTime

/** Versioned allowlist. No executable code, policy source, authentication or confirmation is serialized. */
object ActionCodec {
    fun encode(action: Action): String {
        require(PlanValidator.error(ActionPlan(listOf(action), 1.0, "Stored action")) == null)
        val fields: List<String?> = when (action) {
            is Action.SendSms -> listOf("sendSms", action.phoneNumber, action.body, action.target.subscriptionId.toString(), action.target.slotIndex.toString(), action.target.carrierId.toString(), action.target.label, action.contactId, action.contactName)
            is Action.ReplyToNotification -> error("Notification replies require a live notification and cannot be saved")
            is Action.ForgetPersonalFact -> listOf("forgetPersonalFact", action.subject, action.summary, action.recordCount.toString(), action.fingerprint)
            is Action.PaymentHandoff -> listOf("paymentHandoff", action.app.name, action.request.uri())
            is Action.Flashlight -> listOf("torch", action.enabled.toString())
            is Action.SetWifi -> listOf("wifi", action.enabled.toString())
            is Action.SetMobileData -> listOf("mobileData", action.enabled.toString(), action.target.subscriptionId.toString(), action.target.slotIndex.toString(), action.target.carrierId.toString(), action.target.label)
            is Action.SetVolume -> listOf("volume", action.percent.toString())
            is Action.SetBrightness -> listOf("brightness", action.percent.toString())
            is Action.OpenApp -> listOf("app", action.packageName)
            is Action.YouTubeSearch -> listOf("youtubeSearch", action.query)
            is Action.YouTubeVideo -> listOf("youtubeVideo", action.videoId)
            is Action.YouTubePlayback -> listOf("youtubePlayback", action.operation.name)
            is Action.Media -> listOf("media", action.operation.name)
            is Action.PlaceCall -> listOf(if (action.speakerphone) "placeCallSpeaker" else "placeCall", action.phoneNumber, action.target.subscriptionId.toString(), action.target.slotIndex.toString(), action.target.carrierId.toString(), action.target.label, action.contactId, action.contactName)
            is Action.Dial -> listOf(if (action.speakerphone) "dialSpeaker" else "dial", action.phoneNumber, action.contactId, action.contactName)
            is Action.DraftMessage -> listOf(if (action.draftOnly) "smsDraftOnly" else "message", action.phoneNumber, action.body, action.contactId, action.contactName)
            is Action.DraftAppMessage -> listOf("appMessage", action.app.name, action.phoneNumber, action.body, action.contactId, action.contactName)
            is Action.Alarm -> listOf("alarm", action.time.toString(), action.label)
            is Action.OpenSettings -> listOf("settings", action.panel.name)
            Action.Camera -> listOf("camera")
            Action.ReadNotifications -> listOf("notifications")
            is Action.FindFile -> listOf("find", action.query)
            is Action.OpenFile -> listOf("openFile", action.uri, action.mimeType, action.displayName)
            is Action.ShareFile -> listOf("shareFile", action.uri, action.mimeType, action.recipientName, action.displayName)
        }
        return "AIVORA_ACTION_1\n" + fields.joinToString("\t") { it?.let { value -> Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8)) } ?: "~" }
    }
    fun decode(value: String): Action {
        require(value.length <= 50_000 && value.startsWith("AIVORA_ACTION_1\n"))
        val fields = value.removePrefix("AIVORA_ACTION_1\n").split('\t').map { if (it == "~") null else Base64.getUrlDecoder().decode(it).toString(Charsets.UTF_8) }
        fun field(index: Int) = requireNotNull(fields[index])
        val action = when (field(0)) {
            "sendSms" -> Action.SendSms(field(1), field(2), com.chronoit.aivora.platform.SmsTarget(field(3).toInt(), field(4).toInt(), field(5).toInt(), field(6)), fields[7], fields[8])
            "forgetPersonalFact" -> Action.ForgetPersonalFact(field(1), field(2), field(3).toInt(), field(4))
            "paymentHandoff" -> Action.PaymentHandoff(PaymentApp.valueOf(field(1)), requireNotNull(PaymentRequest.parse(field(2))))
            "torch" -> Action.Flashlight(field(1).toBooleanStrict())
            "wifi" -> Action.SetWifi(field(1).toBooleanStrict())
            "mobileData" -> Action.SetMobileData(field(1).toBooleanStrict(), com.chronoit.aivora.connectivity.MobileDataTarget(field(2).toInt(), field(3).toInt(), field(4).toInt(), field(5)))
            "volume" -> Action.SetVolume(field(1).toInt())
            "brightness" -> Action.SetBrightness(field(1).toInt())
            "app" -> Action.OpenApp(field(1))
            "youtubeSearch" -> Action.YouTubeSearch(field(1))
            "youtubeVideo" -> Action.YouTubeVideo(field(1))
            "youtubePlayback" -> Action.YouTubePlayback(com.chronoit.aivora.automation.PlaybackControl.valueOf(field(1)))
            "media" -> Action.Media(MediaOperation.valueOf(field(1)))
            "placeCall", "placeCallSpeaker" -> Action.PlaceCall(field(1), com.chronoit.aivora.platform.VoiceCallTarget(field(2).toInt(), field(3).toInt(), field(4).toInt(), field(5)), fields[6], fields[7], speakerphone = field(0) == "placeCallSpeaker")
            "dial", "dialSpeaker" -> Action.Dial(field(1), fields[2], fields[3], speakerphone = field(0) == "dialSpeaker")
            "message", "smsDraftOnly" -> Action.DraftMessage(field(1), field(2), fields[3], fields[4], draftOnly = field(0) == "smsDraftOnly")
            "appMessage" -> Action.DraftAppMessage(MessagingApp.valueOf(field(1)), field(2), field(3), fields[4], fields[5])
            "alarm" -> Action.Alarm(LocalTime.parse(field(1)), field(2))
            "settings" -> Action.OpenSettings(SettingsPanel.valueOf(field(1)))
            "camera" -> Action.Camera
            "notifications" -> Action.ReadNotifications
            "find" -> Action.FindFile(field(1))
            "openFile" -> Action.OpenFile(field(1), field(2), fields[3])
            "shareFile" -> Action.ShareFile(field(1), field(2), fields[3], fields[4])
            else -> error("Unsupported stored action")
        }
        // Reject extra fields, noncanonical values and malformed parameters as well as unknown tags.
        require(encode(action) == value)
        return action
    }
}
