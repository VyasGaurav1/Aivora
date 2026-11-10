package com.chronoit.aivora.platform

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.chronoit.aivora.actions.PaymentApp

enum class CapabilityStatus { AVAILABLE, MISSING_PERMISSION, UNSUPPORTED, ROOT_REQUIRED, SYSTEM_PRIVILEGE_REQUIRED, SETUP_REQUIRED }
data class Capability(val name: String, val status: CapabilityStatus, val detail: String)

class DeviceCapabilityManager(private val context: Context) {
    fun snapshot(): List<Capability> {
        val pm = context.packageManager
        fun permission(name: String) = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
        fun status(value: Boolean) = if (value) CapabilityStatus.AVAILABLE else CapabilityStatus.MISSING_PERMISSION
        val role = context.getSystemService(RoleManager::class.java)
        val notifications = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return listOf(
            Capability("Assistant role", if (role.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) status(role.isRoleHeld(RoleManager.ROLE_ASSISTANT)) else CapabilityStatus.UNSUPPORTED, "System invocation opens AIVORA; its recognition provider uses the bundled offline model."),
            Capability("Microphone", status(permission(Manifest.permission.RECORD_AUDIO)), "Required only for voice input."),
            Capability("Flashlight", if (!pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) CapabilityStatus.UNSUPPORTED else status(permission(Manifest.permission.CAMERA)), "Camera permission is required for torch control."),
            Capability("Payment QR scanner", if (!pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) CapabilityStatus.UNSUPPORTED else status(permission(Manifest.permission.CAMERA)), "Reads merchant QR codes offline. Camera access is required."),
            Capability("Brightness", status(Settings.System.canWrite(context)), "Grant modify-system-settings access for direct control."),
            Capability("Notification access", status(notifications.split(':').any { it.startsWith("${context.packageName}/") }), "Optional access to read recent notifications."),
            Capability("YouTube app controls", status(com.chronoit.aivora.automation.AivoraAccessibilityService.connected()), "Optional scoped accessibility fallback for YouTube play/pause. It must be enabled in Android accessibility settings; unknown controls are not pressed."),
            Capability("Wi-Fi settings controls", status(com.chronoit.aivora.automation.AivoraAccessibilityService.connected()), "Requires separate Wi-Fi-controls opt-in and exact-action confirmation. Supports the known Android Internet panel Wi-Fi toggle; no passwords, network selection or mobile-data automation."),
            Capability("WhatsApp drafts", if (pm.getLaunchIntentForPackage("com.whatsapp") != null) CapabilityStatus.AVAILABLE else CapabilityStatus.UNSUPPORTED, "Supported chat draft handoff only; account setup and sending remain in WhatsApp. Automatic send is not implemented."),
            Capability("WhatsApp Business drafts", if (pm.getLaunchIntentForPackage("com.whatsapp.w4b") != null) CapabilityStatus.AVAILABLE else CapabilityStatus.UNSUPPORTED, "Uses the selected Business app; no fallback to another app or verified-send claim."),
            Capability("Do not disturb", CapabilityStatus.UNSUPPORTED, "Direct Do Not Disturb control is unavailable."),
            Capability("Exact alarms", CapabilityStatus.UNSUPPORTED, "Set alarms through the Android clock app. AIVORA reminders use approximate scheduling."),
            Capability("Contacts", status(permission(Manifest.permission.READ_CONTACTS)), "Requested only for resolving contacts."),
            Capability("Direct SMS permission", if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) CapabilityStatus.UNSUPPORTED else status(permission(Manifest.permission.SEND_SMS)), "Optional short SMS submission requires separate opt-in, verified default SMS SIM, authentication and confirmation. Delivery is not verified."),
            Capability("Direct calling permission", if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) CapabilityStatus.UNSUPPORTED else status(permission(Manifest.permission.CALL_PHONE)), "Optional direct-call requests require a verified single default voice SIM/account, separate opt-in, device authentication and confirmation. Connection is not verified."),
            Capability("SIM/mobile-data verification", if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) CapabilityStatus.UNSUPPORTED else status(permission(Manifest.permission.READ_PHONE_STATE)), "Identifies the single default data SIM and verifies its user data setting. No phone numbers, call logs or hardware subscriber identifiers are read."),
            Capability("Reminder notifications", status(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()), "Needed to notify you about due reminders and action reviews."),
            Capability("Device credential", if (context.getSystemService(KeyguardManager::class.java).isDeviceSecure) CapabilityStatus.AVAILABLE else CapabilityStatus.SETUP_REQUIRED, "Sensitive actions require a secure device credential. Say open security settings to review your screen lock."),
            Capability("Silent mobile data control", CapabilityStatus.SYSTEM_PRIVILEGE_REQUIRED, "Standard Android uses the system internet panel."),
            Capability("Silent Wi-Fi control", CapabilityStatus.SYSTEM_PRIVILEGE_REQUIRED, "Standard Android requires the internet panel or additional device privileges."),
            Capability("Voice-only secure unlock", CapabilityStatus.UNSUPPORTED, "Voice matching cannot replace Android PIN, password or biometric approval."),
            *PaymentApp.entries.map { app ->
                Capability("${app.displayName} payment handoff",
                    if (pm.getLaunchIntentForPackage(app.packageName) != null) CapabilityStatus.AVAILABLE else CapabilityStatus.SETUP_REQUIRED,
                    "Checks whether AIVORA can see the app. The payment app handles account setup and UPI PIN entry.")
            }.toTypedArray(),
        )
    }
}
