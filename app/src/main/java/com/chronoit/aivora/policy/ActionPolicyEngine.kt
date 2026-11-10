package com.chronoit.aivora.policy

import com.chronoit.aivora.actions.*

data class ExecutionContext(
    val deviceUnlocked: Boolean,
    val trustedForegroundInput: Boolean,
    val speakerVerified: Boolean = false,
    val deviceAuthenticated: Boolean = false,
)

sealed interface PolicyDecision {
    data object Allow : PolicyDecision
    data class Confirm(val reason: String) : PolicyDecision
    data class Authenticate(val reason: String) : PolicyDecision
    data class Deny(val reason: String) : PolicyDecision
}

class ActionPolicyEngine {
    fun evaluate(plan: ActionPlan, context: ExecutionContext): PolicyDecision {
        PlanValidator.error(plan)?.let { return PolicyDecision.Deny(it) }
        if (plan.confidence < if (plan.riskLevel >= RiskLevel.HIGH) 0.98 else 0.9) {
            return PolicyDecision.Deny("I need clarification before performing this action.")
        }
        if (!context.deviceUnlocked) return PolicyDecision.Authenticate("Unlock your device before running actions.")
        if (!context.trustedForegroundInput && !context.speakerVerified) return PolicyDecision.Authenticate("Verify the owner before running actions.")
        return when (plan.riskLevel) {
            RiskLevel.LOW -> PolicyDecision.Allow
            RiskLevel.MEDIUM -> PolicyDecision.Confirm("Review and confirm this exact action plan.")
            RiskLevel.HIGH, RiskLevel.DESTRUCTIVE, RiskLevel.CRITICAL -> if (context.deviceAuthenticated)
                PolicyDecision.Confirm("Review and explicitly confirm this authenticated action plan.")
            else PolicyDecision.Authenticate("Sensitive actions need device authentication and explicit confirmation.")
        }
    }
}
