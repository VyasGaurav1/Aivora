package com.chronoit.aivora.voice

import com.chronoit.aivora.actions.*

/** Complete parameter review only; oversized prompts are refused, never shortened. */
object ActionReadback {
    const val MAX_PROMPT_CHARACTERS = 1200
    fun create(plan: ActionPlan, connection: Boolean = false, readOnly: Boolean = false): String? {
        if (PlanValidator.error(plan) != null) return null
        val descriptions = plan.actions.map { action ->
            if (action is Action.PaymentHandoff) PaymentReadback.create(action) ?: return null else action.describe()
        }
        val description = descriptions.joinToString(". ")
        val prompt = if (readOnly) "Review this action: $description. Use Confirm or Cancel on the screen."
            else if (connection) "This task needs internet: $description. Say connect for this task to allow connection controls, or cancel."
            else "Review this action: $description. Say confirm the action I just reviewed to approve, or cancel."
        return prompt.takeIf { it.length <= MAX_PROMPT_CHARACTERS }
    }
}
