package com.chronoit.aivora.actions

import com.chronoit.aivora.platform.VoiceCallTarget

/** Upgrade only an eligible single call; otherwise preserve the original dialer review exactly. */
object DirectCallPlanner {
    fun bind(plan: ActionPlan, target: VoiceCallTarget?): ActionPlan {
        if (target?.valid() != true || PlanValidator.error(plan) != null) return plan
        val dial = plan.actions.singleOrNull() as? Action.Dial ?: return plan
        val number = internationalPhone(dial.phoneNumber) ?: return plan
        return plan.copy(actions = listOf(Action.PlaceCall(number, target, dial.contactId, dial.contactName, dial.speakerphone)),
            reasoningSummary = "Explicit direct call bound to the verified default voice SIM")
    }
}
