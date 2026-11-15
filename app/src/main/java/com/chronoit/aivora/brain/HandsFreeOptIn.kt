package com.chronoit.aivora.brain

import java.util.Locale

/** The five voice controls that default off and were Settings-switches only. */
enum class HandsFreeControl(val label: String, val plural: Boolean) {
    HANDS_FREE("hands-free controls", true),
    DIRECT_CALLING("direct calling", false),
    DIRECT_SMS("direct SMS", false),
    WIFI("Wi-Fi controls", true),
    MOBILE_DATA("mobile data controls", true),
}

sealed interface HandsFreeOptInCommand {
    data class Request(val control: HandsFreeControl, val enable: Boolean) : HandsFreeOptInCommand
    data class Confirm(val control: HandsFreeControl, val enable: Boolean) : HandsFreeOptInCommand
    data object Status : HandsFreeOptInCommand
}

/**
 * Voice opt-in for the five sensitive controls, in the same exact-phrase
 * style as [ModelDownloadPolicy]: a bare "yes", "enable it" or "turn it on"
 * never opts anything in, and a confirmation phrase only counts for the
 * control named in a still-current request.
 *
 * Rules — none of these may be relaxed without a design change:
 * - Two exact phrases: a request ("enable hands-free controls") and then a
 *   confirmation ("yes, enable hands-free controls"). The request opens a
 *   single pending slot that expires after [PENDING_TTL_MILLIS]; a
 *   confirmation for a different control, or after expiry, does nothing.
 * - Owner enrolled: the ViewModel refuses every opt-in command while no
 *   owner voice profile is enrolled. This policy does not know about
 *   enrollment; the caller passes it in.
 * - Device authentication on enable: enabling any of the five controls
 *   needs Android device-credential authentication (BiometricPrompt, the
 *   same helper the action-auth flow uses) before the setting flips.
 *   Disabling needs none — turning a capability off is not a grant — but
 *   still needs the two exact phrases and owner enrollment.
 * - Defaults stay OFF: this policy never writes a setting. The ViewModel
 *   applies a change only after the full request + confirm + (for enable)
 *   device-auth chain, and logs it to the audit table.
 * - No public-wake opt-in: a public-wake session never routes arbitrary
 *   commands to submitCommand, so these phrases cannot opt anything in
 *   from an unauthenticated wake. Opt-in needs the app open and unlocked
 *   (foreground owner, typed or mic) or a verified-voice command once
 *   hands-free is already on. The policy itself is source-agnostic; the
 *   caller enforces the entry point and records the source in the audit.
 * - Per-control: enabling one control never enables another. Enabling a
 *   control does not bypass its use-time guards (permissions, SIM
 *   readiness, exact confirmation and device auth for each individual
 *   call, message or network change).
 */
class HandsFreeOptInPolicy(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Pending(val control: HandsFreeControl, val enable: Boolean, val requestedAtMillis: Long, val source: String)

    companion object {
        const val PENDING_TTL_MILLIS = 60_000L

        fun normalize(text: String): String = text.trim().lowercase(Locale.ROOT)
            .replace(",", "").replace("-", " ").replace(Regex("\\s+"), " ").trim().trimEnd('.', '?', '!')

        /** Spoken scope for a request: what the control unlocks and what still guards each use. */
        fun scopeText(control: HandsFreeControl): String = when (control) {
            HandsFreeControl.HANDS_FREE ->
                "Hands-free controls let your enrolled owner voice run low-risk actions and answer confirmations and choices by voice. " +
                    "Calls, messages and payments still need their own exact confirmation, and sensitive actions still need your device credential. " +
                    "Voice matching cannot guarantee liveness."
            HandsFreeControl.DIRECT_CALLING ->
                "Direct calling lets a confirmed call go straight to the phone app instead of opening the dialer. " +
                    "It still needs call placement permission, one ready default voice SIM, your device credential, and an exact confirmation for every call. " +
                    "Connection is not verified."
            HandsFreeControl.DIRECT_SMS ->
                "Direct SMS lets a confirmed short message be sent from AIVORA instead of opening your messaging app. " +
                    "It still needs SMS and SIM permissions, one ready default SMS SIM, your device credential, and an exact confirmation. " +
                    "Carrier charges may apply, and sending is not proof of delivery."
            HandsFreeControl.WIFI ->
                "Wi-Fi controls let AIVORA confirm a Wi-Fi change in the Android Internet panel. " +
                    "It never picks a network or enters a password, and the change is not silent."
            HandsFreeControl.MOBILE_DATA ->
                "Mobile data controls let AIVORA confirm a mobile-data change in the Android Internet panel for one ready default SIM. " +
                    "It never switches SIMs or enables roaming."
        }

        fun requestPrompt(control: HandsFreeControl, enable: Boolean): String =
            scopeText(control) + " Say \"yes, ${if (enable) "enable" else "disable"} ${control.label}\" to confirm. This request expires in one minute."

        fun confirmPrompt(control: HandsFreeControl, enable: Boolean): String =
            "Confirm on your device to finish ${if (enable) "enabling" else "disabling"} ${control.label}."

        fun statusText(handsFree: Boolean, directCalling: Boolean, directSms: Boolean, wifi: Boolean, mobileData: Boolean): String =
            "Voice controls: hands-free controls ${onOff(handsFree)}, direct calling ${onOff(directCalling)}, " +
                "direct SMS ${onOff(directSms)}, Wi-Fi controls ${onOff(wifi)}, mobile data controls ${onOff(mobileData)}."

        private fun onOff(value: Boolean) = if (value) "on" else "off"

        /** Enabling any control needs device authentication; disabling does not. */
        fun requiresDeviceAuth(enable: Boolean): Boolean = enable

        private fun requestPhrases(control: HandsFreeControl, enable: Boolean): Set<String> {
            val verb = if (enable) listOf("enable", "turn on") else listOf("disable", "turn off")
            return verb.flatMap { v ->
                when (control) {
                    HandsFreeControl.HANDS_FREE -> listOf("$v hands free controls")
                    HandsFreeControl.DIRECT_CALLING -> listOf("$v direct calling")
                    HandsFreeControl.DIRECT_SMS -> listOf("$v direct sms", "$v direct texting")
                    HandsFreeControl.WIFI -> listOf("$v wi fi controls")
                    HandsFreeControl.MOBILE_DATA -> listOf("$v mobile data controls")
                }
            }.toSet()
        }

        private fun confirmPhrases(control: HandsFreeControl, enable: Boolean): Set<String> {
            val verb = if (enable) "enable" else "disable"
            return when (control) {
                HandsFreeControl.HANDS_FREE -> setOf("yes $verb hands free controls")
                HandsFreeControl.DIRECT_CALLING -> setOf("yes $verb direct calling")
                HandsFreeControl.DIRECT_SMS -> setOf("yes $verb direct sms", "yes $verb direct texting")
                HandsFreeControl.WIFI -> setOf("yes $verb wi fi controls")
                HandsFreeControl.MOBILE_DATA -> setOf("yes $verb mobile data controls")
            }
        }

        private val statusPhrases = setOf(
            "voice controls status",
            "hands free controls status",
            "what voice controls are enabled",
            "which voice controls are enabled",
            "which controls are enabled",
        )
    }

    /** Exact phrases only. A bare "yes", "enable it", or "turn it on" never matches. */
    fun match(text: String): HandsFreeOptInCommand? {
        val normalized = normalize(text)
        if (normalized in statusPhrases) return HandsFreeOptInCommand.Status
        for (control in HandsFreeControl.entries) {
            if (normalized in confirmPhrases(control, true)) return HandsFreeOptInCommand.Confirm(control, true)
            if (normalized in confirmPhrases(control, false)) return HandsFreeOptInCommand.Confirm(control, false)
            if (normalized in requestPhrases(control, true)) return HandsFreeOptInCommand.Request(control, true)
            if (normalized in requestPhrases(control, false)) return HandsFreeOptInCommand.Request(control, false)
        }
        return null
    }

    fun isCurrent(pending: Pending?, nowMillis: Long = clock()): Boolean =
        pending != null && nowMillis - pending.requestedAtMillis in 0..PENDING_TTL_MILLIS
}
