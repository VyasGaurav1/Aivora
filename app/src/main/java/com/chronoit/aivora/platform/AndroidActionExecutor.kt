package com.chronoit.aivora.platform

import android.Manifest
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import com.chronoit.aivora.actions.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import com.chronoit.aivora.AivoraApplication

class AndroidActionExecutor(private val context: Context) : ActionExecutor {
    override suspend fun execute(action: Action): ActionResult = if (action is Action.DraftAppMessage && context.packageManager.getLaunchIntentForPackage(action.app.packageName) == null)
        ActionResult(ExecutionStatus.NOT_SUPPORTED, "${action.app.displayName} is not installed or unavailable. No message was drafted or sent.")
        else if (action.requiresNetwork() && !com.chronoit.aivora.connectivity.AndroidNetworkMonitor(context).snapshot().validated)
        ActionResult(ExecutionStatus.FAILED, "Validated internet is unavailable. This online task has not run.") else when (action) {
        is Action.SendSms -> DirectSmsAdapter(context).execute(action)
        is Action.ReplyToNotification -> AivoraNotificationListener.reply(action)
        is Action.ForgetPersonalFact -> {
            val memory = (context.applicationContext as AivoraApplication).memory
            if (memory.forgetPersonalFact(action)) ActionResult(ExecutionStatus.SUCCESS, "Deleted the reviewed personal-fact records, including historical values.")
            else ActionResult(ExecutionStatus.CANCELLED, "The saved personal facts changed. Nothing was deleted; request a fresh review.")
        }
        is Action.PaymentHandoff -> {
            val intent = com.chronoit.aivora.automation.PaymentHandoffAdapter.intent(action)
            val resolved = intent.resolveActivity(context.packageManager)
            if (resolved == null || resolved.packageName != action.app.packageName)
                ActionResult(ExecutionStatus.NOT_SUPPORTED, "${action.app.displayName} cannot handle this payment request. No payment was verified.")
            else launch(intent, "Requested payment review in ${action.app.displayName}. Verify the bank-resolved recipient and amount there. AIVORA has not verified or completed a payment.")
        }
        is Action.PlaceCall -> DirectCallAdapter(context).execute(action)
        is Action.YouTubePlayback -> com.chronoit.aivora.automation.YouTubeAutomationAdapter(context).execute(action.operation)
        is Action.DraftAppMessage -> {
            if (action.contactId != null && !contacts.stillMatches(action.contactId, action.phoneNumber))
                ActionResult(ExecutionStatus.PERMISSION_MISSING, "This contact's number changed or contacts access was revoked. Resolve the recipient again.")
            else if (context.packageManager.getLaunchIntentForPackage(action.app.packageName) == null)
                ActionResult(ExecutionStatus.NOT_SUPPORTED, "${action.app.displayName} is not installed. No message was drafted or sent.")
            else launch(com.chronoit.aivora.automation.WhatsAppDraftAdapter.intent(action),
                "Requested a draft in ${action.app.displayName} for ${action.contactName ?: action.phoneNumber}. Review the recipient and message there. AIVORA has not sent it.")
        }
        is Action.YouTubeSearch -> youtube(Uri.Builder().scheme("https").authority("www.youtube.com").appendPath("results").appendQueryParameter("search_query", action.query).build(),
            "Requested YouTube search. Select a video in YouTube; playback has not been verified.")
        is Action.YouTubeVideo -> if (context.packageManager.getLaunchIntentForPackage("com.google.android.youtube") != null)
            com.chronoit.aivora.automation.YouTubeAutomationAdapter(context).openVideo(action.videoId)
        else youtube(Uri.Builder().scheme("https").authority("www.youtube.com").appendPath("watch").appendQueryParameter("v", action.videoId).build(),
            "Requested the YouTube video in the app. Playback has not been verified.")
        is Action.SetVolume -> {
            val audio = context.getSystemService(AudioManager::class.java)
            if (audio.isVolumeFixed) ActionResult(ExecutionStatus.NOT_SUPPORTED, "This device has fixed volume.")
            else {
                val old = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (max == 0) ActionResult(ExecutionStatus.NOT_SUPPORTED, "No adjustable media volume is available.")
                else {
                    val target = (max * action.percent / 100.0).roundToInt()
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                    if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == target)
                        ActionResult(ExecutionStatus.SUCCESS, "Media volume set to ${action.percent}%.", Action.SetVolume((old * 100.0 / max).roundToInt()))
                    else ActionResult(ExecutionStatus.FAILED, "Android did not apply the requested volume.")
                }
            }
        }
        is Action.SetBrightness -> {
            if (!Settings.System.canWrite(context)) {
                launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")), "Grant AIVORA permission to modify system settings, then repeat the command.")
            } else {
                val resolver = context.contentResolver
                val value = (action.percent * 255.0 / 100).roundToInt().coerceAtLeast(1)
                val manual = Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                val written = manual && Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
                if (written && Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) == value)
                    ActionResult(ExecutionStatus.SUCCESS, "Brightness set to ${action.percent}% (manual mode).")
                else ActionResult(ExecutionStatus.FAILED, "Android did not apply the requested brightness.")
            }
        }
        is Action.Flashlight -> torch(action.enabled)
        is Action.SetWifi -> com.chronoit.aivora.automation.WifiAutomationAdapter(context).execute(action.enabled)
        is Action.SetMobileData -> com.chronoit.aivora.automation.MobileDataAutomationAdapter(context).execute(action)
        is Action.OpenApp -> {
            val intent = context.packageManager.getLaunchIntentForPackage(action.packageName)
            if (intent == null) ActionResult(ExecutionStatus.NOT_SUPPORTED, "This app has no accessible launcher.")
            else launch(intent, "App launch requested. Return to AIVORA when finished.")
        }
        is Action.Dial -> if (action.contactId != null && !contacts.stillMatches(action.contactId, action.phoneNumber))
            ActionResult(ExecutionStatus.PERMISSION_MISSING, "That contact changed or contacts access was revoked. Resolve the recipient again.")
            else launch(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", action.phoneNumber, null)), "Number opened in the dialer. Review it and place the call there.${if (action.speakerphone) " Select speakerphone in the dialer." else ""}")
        is Action.DraftMessage -> if (action.contactId != null && !contacts.stillMatches(action.contactId, action.phoneNumber))
            ActionResult(ExecutionStatus.PERMISSION_MISSING, "That contact changed or contacts access was revoked. Resolve the recipient again.")
            else launch(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", action.phoneNumber, null)).putExtra("sms_body", action.body), "Message draft opened. Review and send it in your messaging app.")
        is Action.Alarm -> launch(Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, action.time.hour).putExtra(AlarmClock.EXTRA_MINUTES, action.time.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, action.label).putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            "I've requested an alarm for ${action.time} in your Clock app.")
        is Action.Media -> {
            val code = when (action.operation) {
                MediaOperation.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
                MediaOperation.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
                MediaOperation.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                MediaOperation.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            }
            val audio = context.getSystemService(AudioManager::class.java)
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            ActionResult(ExecutionStatus.PARTIAL, "Media command sent. Playback change could not be verified.")
        }
        is Action.OpenSettings -> launch(Intent(when (action.panel) {
            SettingsPanel.DEVICE_SECURITY -> Settings.ACTION_SECURITY_SETTINGS
            SettingsPanel.DEFAULT_APPS -> Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
            SettingsPanel.GENERAL -> Settings.ACTION_SETTINGS
            SettingsPanel.INTERNET -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            SettingsPanel.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            SettingsPanel.NOTIFICATION_ACCESS -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            SettingsPanel.ACCESSIBILITY -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            SettingsPanel.APP_PERMISSIONS -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            SettingsPanel.BRIGHTNESS_ACCESS -> Settings.ACTION_MANAGE_WRITE_SETTINGS
        }).apply {
            if (action.panel in setOf(SettingsPanel.APP_PERMISSIONS, SettingsPanel.BRIGHTNESS_ACCESS)) {
                data = Uri.fromParts("package", context.packageName, null)
            }
        }, when (action.panel) {
            SettingsPanel.APP_PERMISSIONS -> "Continue in Android app settings and choose Permissions."
            SettingsPanel.BRIGHTNESS_ACCESS -> "Review AIVORA's Modify system settings access in Android."
            SettingsPanel.DEVICE_SECURITY -> "Review your screen lock in Android security settings."
            SettingsPanel.DEFAULT_APPS -> "In Android default apps, choose the digital assistant app to review the Assistant role."
            else -> "Continue in Android settings."
        })
        Action.Camera -> launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Camera opened. Take your photo there.")
        Action.ReadNotifications -> {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
                .split(':').any { it.startsWith("${context.packageName}/") }
            if (!enabled) ActionResult(ExecutionStatus.PERMISSION_MISSING, "Enable AIVORA notification access in Capabilities first.")
            else {
                val items = AivoraNotificationListener.notifications.value
                ActionResult(ExecutionStatus.SUCCESS, if (items.isEmpty()) "No recent notifications are available." else items.joinToString("\n") { "${it.title}: ${it.text}" })
            }
        }
        is Action.FindFile -> {
            val found = documents.find(action.query).filter(documents::readable)
            if (found.isEmpty()) ActionResult(ExecutionStatus.FAILED, "No matching permitted local file is available. Add a file or folder in Files.")
            else ActionResult(ExecutionStatus.SUCCESS, found.joinToString("\n") { it.name }, documents = found)
        }
        is Action.OpenFile -> {
            val document = documents.authorized(action.uri)
            if (document == null || !documents.readable(document) || document.mimeType != action.mimeType)
                ActionResult(ExecutionStatus.PERMISSION_MISSING, "The file is missing, changed or no longer permitted. Select it again in Files.")
            else launch(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(document.uri), document.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Continue in the selected file viewer.")
        }
        is Action.ShareFile -> {
            val document = documents.authorized(action.uri)
            if (document == null || !documents.readable(document) || document.mimeType != action.mimeType)
                ActionResult(ExecutionStatus.PERMISSION_MISSING, "The file is missing, changed or no longer permitted. Select it again in Files.")
            else {
                val uri = Uri.parse(document.uri)
                val intent = Intent(Intent.ACTION_SEND).setType(document.mimeType).putExtra(Intent.EXTRA_STREAM, uri)
                    .apply { clipData = ClipData.newRawUri("AIVORA selected file", uri) }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                launch(Intent.createChooser(intent, "Share ${document.name}"),
                    if (action.recipientName == null) "Choose the recipient and complete sharing in the selected app."
                    else "Select ${action.recipientName} in the chosen app and complete sharing there. The file has not been sent yet.")
            }
        }
    }
    private val contacts get() = (context.applicationContext as AivoraApplication).contacts
    private val documents get() = (context.applicationContext as AivoraApplication).documents
    private suspend fun youtube(uri: Uri, message: String): ActionResult {
        val installed = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube") != null
        return launch(Intent(Intent.ACTION_VIEW, uri).apply { if (installed) setPackage("com.google.android.youtube") },
            if (installed) message else "YouTube is not installed. Continue with the YouTube page in your browser; playback has not been verified.")
    }

    private suspend fun launch(intent: Intent, message: String): ActionResult = withContext(Dispatchers.Main) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionResult(ExecutionStatus.REQUIRES_USER_INTERACTION, message)
        } catch (_: android.content.ActivityNotFoundException) {
            ActionResult(ExecutionStatus.NOT_SUPPORTED, "No installed app can handle this action.")
        }
    }

    private suspend fun torch(enabled: Boolean): ActionResult {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            return ActionResult(ExecutionStatus.PERMISSION_MISSING, "Allow camera access in Capabilities to control the flashlight.")
        val camera = context.getSystemService(CameraManager::class.java)
        val id = camera.cameraIdList.firstOrNull {
            val properties = camera.getCameraCharacteristics(it)
            properties.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && properties.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return ActionResult(ExecutionStatus.NOT_SUPPORTED, "No rear flashlight is available.")
        val verified = withTimeoutOrNull(2500) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                var requested = false
                val callback = object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, on: Boolean) {
                        if (requested && cameraId == id && on == enabled && continuation.isActive) {
                            camera.unregisterTorchCallback(this)
                            continuation.resume(true)
                        }
                    }
                    override fun onTorchModeUnavailable(cameraId: String) {
                        if (requested && cameraId == id && continuation.isActive) {
                            camera.unregisterTorchCallback(this)
                            continuation.resume(false)
                        }
                    }
                }
                camera.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
                continuation.invokeOnCancellation { camera.unregisterTorchCallback(callback) }
                try { requested = true; camera.setTorchMode(id, enabled) }
                catch (error: Exception) { camera.unregisterTorchCallback(callback); if (continuation.isActive) continuation.resume(false) }
            }
        }
        return if (verified == true) ActionResult(ExecutionStatus.SUCCESS, "Flashlight ${if (enabled) "on" else "off"}.")
        else ActionResult(ExecutionStatus.FAILED, "Flashlight state could not be verified.")
    }
}
