package com.chronoit.aivora.actions

import com.chronoit.aivora.platform.SmsTarget

object DirectSmsPlanner {
    fun bind(plan: ActionPlan, target: SmsTarget?): ActionPlan {
        if (target?.valid() != true || PlanValidator.error(plan) != null) return plan
        val draft = plan.actions.singleOrNull() as? Action.DraftMessage ?: return plan
        if (draft.draftOnly) return plan
        val number = internationalPhone(draft.phoneNumber) ?: return plan
        if (draft.body.length > 160) return plan
        return plan.copy(actions = listOf(Action.SendSms(number, draft.body, target, draft.contactId, draft.contactName)), reasoningSummary = "Explicit SMS bound to verified default SMS SIM")
    }
}
