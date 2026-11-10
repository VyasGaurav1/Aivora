package com.chronoit.aivora.connectivity

data class MobileDataTarget(val subscriptionId: Int, val slotIndex: Int, val carrierId: Int, val label: String) {
    fun valid() = subscriptionId >= 0 && slotIndex >= 0 && carrierId >= -1 && label.isNotBlank() && label.length <= 100 && label.none(Char::isISOControl)
}
data class MobileDataState(val target: MobileDataTarget, val enabled: Boolean, val roaming: Boolean)
object MobileDataGuard {
    fun matches(target: MobileDataTarget, state: MobileDataState?, desired: Boolean): Boolean =
        target.valid() && state?.target == target && (!desired || !state.roaming)
}
