package com.chronoit.aivora.scheduler

enum class RequiredNetwork { NONE, VALIDATED, UNMETERED }
data class ConditionSnapshot(val charging: Boolean, val validatedNetwork: Boolean, val metered: Boolean)
data class TaskConditions(val charging: Boolean = false, val network: RequiredNetwork = RequiredNetwork.NONE) {
    fun permitsConnectionReview(snapshot: ConditionSnapshot, actionNeedsNetwork: Boolean): Boolean =
        actionNeedsNetwork && network != RequiredNetwork.NONE && (!charging || snapshot.charging)

    fun satisfied(snapshot: ConditionSnapshot): Boolean = (!charging || snapshot.charging) && when (network) {
        RequiredNetwork.NONE -> true
        RequiredNetwork.VALIDATED -> snapshot.validatedNetwork
        RequiredNetwork.UNMETERED -> snapshot.validatedNetwork && !snapshot.metered
    }
    fun encode() = "AIVORA_CONDITIONS_1|$charging|${network.name}"
    companion object {
        fun decode(value: String): TaskConditions {
            val fields = value.split('|')
            require(fields.size == 3 && fields[0] == "AIVORA_CONDITIONS_1")
            return TaskConditions(fields[1].toBooleanStrict(), RequiredNetwork.valueOf(fields[2]))
        }
    }
}
