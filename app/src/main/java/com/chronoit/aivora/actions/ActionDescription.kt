package com.chronoit.aivora.actions

fun Action.describe(): String = when (this) {
    is Action.ReplyToNotification -> "Reply through ${app.displayName} to the notification titled $title. Incoming message: $incoming. Your reply: $body"
    is Action.SendSms -> "Send SMS to ${contactName ?: "number"}: $phoneNumber, using ${target.label}, SIM slot ${target.slotIndex + 1}. Message: $body. Carrier charges may apply. Delivery is not verified."
    is Action.ForgetPersonalFact -> "Delete $recordCount saved personal-fact records about your $subject, including historical values. Current values: $summary. This deletion cannot be undone."
    is Action.PaymentHandoff -> "Review INR ${request.amount} to ${request.payeeName}, UPI ${request.payeeVpa}, in ${app.displayName}. Reference ${request.reference}; merchant code ${request.merchantCode}. Note: ${request.note.ifEmpty { "none" }}. Transaction URL: ${request.transactionUrl ?: "none"}. Payee identity is unverified. Complete authentication in the payment app; AIVORA cannot verify payment completion."

    is Action.SetMobileData -> "Turn mobile data ${if (enabled) "on" else "off"} for ${target.label}, SIM slot ${target.slotIndex + 1}. Carrier charges may apply."
    is Action.SetWifi -> "Turn Wi-Fi ${if (enabled) "on" else "off"} using Android Settings"
    is Action.DraftAppMessage -> "Draft in ${app.displayName} to ${contactName ?: phoneNumber} ($phoneNumber): $body"
    is Action.YouTubePlayback -> "${if (operation == com.chronoit.aivora.automation.PlaybackControl.PLAY) "Resume" else "Pause"} the current YouTube video"
    is Action.YouTubeSearch -> "Search YouTube for: $query"
    is Action.YouTubeVideo -> "Open YouTube video: $videoId"
    is Action.Flashlight -> "Turn flashlight ${if (enabled) "on" else "off"}"
    is Action.SetVolume -> "Set media volume to $percent%"
    is Action.SetBrightness -> "Set manual screen brightness to $percent%"
    is Action.OpenApp -> "Open $packageName"
    is Action.Media -> "Media: ${operation.name.lowercase()}"
    is Action.PlaceCall -> "Place a phone call to ${contactName ?: "number"}: $phoneNumber${if (speakerphone) " on speakerphone" else ""}, using ${target.label}, SIM slot ${target.slotIndex + 1}. Carrier charges may apply. Connection is not verified by AIVORA."
    is Action.Dial -> "Open dialer for ${contactName ?: "number"}: $phoneNumber${if (speakerphone) ". Select speakerphone in the dialer" else ""}"
    is Action.DraftMessage -> "Draft message to ${contactName ?: "number"} ($phoneNumber): $body${if (draftOnly) ". Sending remains in your messaging app" else ""}"
    is Action.Alarm -> "Create alarm at $time: $label"
    is Action.OpenSettings -> "Open ${panel.name.lowercase().replace('_', ' ')} settings"
    Action.Camera -> "Open camera"
    Action.ReadNotifications -> "Read recent notifications"
    is Action.FindFile -> "Find file: $query"
    is Action.OpenFile -> "Open ${displayName ?: "selected file"}"
    is Action.ShareFile -> "Share ${displayName ?: "selected file"} through another app${recipientName?.let { "; intended recipient: $it" } ?: ""}"
}


