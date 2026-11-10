package com.chronoit.aivora.platform

/** Public subscription metadata, not a hardware SIM identifier or a Telecom account ID. */
data class VoiceCallTarget(val subscriptionId: Int, val slotIndex: Int, val carrierId: Int, val label: String) {
    fun valid() = subscriptionId >= 0 && slotIndex >= 0 && carrierId >= -1 && label.isNotBlank() && label.length <= 100 && label.none(Char::isISOControl)
}
