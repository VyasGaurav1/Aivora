package com.chronoit.aivora.actions

import com.chronoit.aivora.policy.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.Collections
import com.chronoit.aivora.brain.LocalDocument

enum class ExecutionStatus { SUCCESS, FAILED, PARTIAL, REQUIRES_USER_INTERACTION, NOT_SUPPORTED, PERMISSION_MISSING, CANCELLED }
data class ActionResult(val status: ExecutionStatus, val message: String, val undo: Action? = null, val documents: List<LocalDocument> = emptyList())
fun interface ActionExecutor { suspend fun execute(action: Action): ActionResult }
enum class CommandSource { FOREGROUND_OWNER, VERIFIED_VOICE, UNVERIFIED_VOICE }
fun interface ActionAudit { suspend fun record(action: Action, result: ActionResult, confirmed: Boolean, source: CommandSource) }
data class Confirmation(val token: String, val plan: ActionPlan, val expiresAt: Long)
sealed interface DispatchResult {
    data class Executed(val results: List<ActionResult>) : DispatchResult
    data class NeedsConfirmation(val confirmation: Confirmation) : DispatchResult
    data class NeedsAuthentication(val confirmation: Confirmation) : DispatchResult
    data class Rejected(val reason: String) : DispatchResult
}

/** The only dispatch entry: validate -> policy -> exact-plan confirmation -> executor. */
class ActionRegistry(
    private val executor: ActionExecutor,
    private val audit: ActionAudit,
    private val context: () -> ExecutionContext,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val policy: ActionPolicyEngine = ActionPolicyEngine(),
    private val requireConfirmation: Boolean = false,
    private val reviewGuard: suspend (ActionPlan) -> Boolean = { true },
) {
    private val mutex = Mutex()
    private data class Pending(val confirmation: Confirmation, val source: CommandSource, val authenticated: Boolean, val awaitingAuthentication: Boolean)
    private var pending: Pending? = null

    suspend fun dispatch(plan: ActionPlan, source: CommandSource = CommandSource.FOREGROUND_OWNER): DispatchResult = mutex.withLock {
        pending = null
        val snapshot = plan.copy(actions = Collections.unmodifiableList(plan.actions.toList()))
        if (!reviewGuard(snapshot)) return@withLock DispatchResult.Rejected("The saved task or its conditions changed. Review it again when ready.")
        val current = currentContext(source, false)
        val evaluated = policy.evaluate(snapshot, current)
        val decision = if (requireConfirmation && evaluated == PolicyDecision.Allow) PolicyDecision.Confirm("Review and confirm this exact saved action.") else evaluated
        when (decision) {
            PolicyDecision.Allow -> execute(snapshot, false, source, false)
            is PolicyDecision.Confirm -> {
                val confirmation = Confirmation(UUID.randomUUID().toString(), snapshot, clock() + 60_000)
                pending = Pending(confirmation, source, false, false)
                DispatchResult.NeedsConfirmation(confirmation)
            }
            is PolicyDecision.Authenticate -> {
                if (snapshot.riskLevel < RiskLevel.HIGH || !current.deviceUnlocked || (!current.trustedForegroundInput && !current.speakerVerified)) DispatchResult.Rejected(decision.reason)
                else {
                    val confirmation = Confirmation(UUID.randomUUID().toString(), snapshot, clock() + 60_000)
                    pending = Pending(confirmation, source, false, true)
                    DispatchResult.NeedsAuthentication(confirmation)
                }
            }
            is PolicyDecision.Deny -> DispatchResult.Rejected(decision.reason)
        }
    }
    suspend fun confirm(token: String): DispatchResult = mutex.withLock {
        val request = pending
        pending = null
        if (request == null || request.awaitingAuthentication || request.confirmation.token != token || request.confirmation.expiresAt <= clock()) {
            return@withLock DispatchResult.Rejected("Confirmation expired or no longer matches. Please repeat the command.")
        }
        when (val decision = policy.evaluate(request.confirmation.plan, currentContext(request.source, request.authenticated))) {
            is PolicyDecision.Authenticate -> DispatchResult.Rejected(decision.reason)
            is PolicyDecision.Deny -> DispatchResult.Rejected(decision.reason)
            else -> execute(request.confirmation.plan, true, request.source, request.authenticated)
        }
    }
    /** UI broker calls this only after a successful Android credential/biometric result. No execution here. */
    suspend fun authenticate(token: String): DispatchResult = mutex.withLock {
        val request = pending
        pending = null
        if (request == null || !request.awaitingAuthentication || request.confirmation.token != token || request.confirmation.expiresAt <= clock())
            return@withLock DispatchResult.Rejected("Authentication request expired or no longer matches.")
        when (val decision = policy.evaluate(request.confirmation.plan, currentContext(request.source, true))) {
            is PolicyDecision.Authenticate -> DispatchResult.Rejected(decision.reason)
            is PolicyDecision.Deny -> DispatchResult.Rejected(decision.reason)
            else -> {
                val next = request.confirmation.copy(token = UUID.randomUUID().toString())
                pending = request.copy(confirmation = next, authenticated = true, awaitingAuthentication = false)
                DispatchResult.NeedsConfirmation(next)
            }
        }
    }
    suspend fun cancel() = mutex.withLock { pending = null }

    private fun currentContext(source: CommandSource, authenticated: Boolean): ExecutionContext {
        val current = context()
        return current.copy(trustedForegroundInput = current.trustedForegroundInput && source == CommandSource.FOREGROUND_OWNER,
            speakerVerified = source == CommandSource.VERIFIED_VOICE, deviceAuthenticated = authenticated)
    }
    private suspend fun execute(plan: ActionPlan, confirmed: Boolean, source: CommandSource, authenticated: Boolean): DispatchResult {
        val results = mutableListOf<ActionResult>()
        for (action in plan.actions) {
            when (val decision = policy.evaluate(plan, currentContext(source, authenticated))) {
                is PolicyDecision.Authenticate -> return DispatchResult.Executed(results + ActionResult(ExecutionStatus.PERMISSION_MISSING, decision.reason))
                is PolicyDecision.Deny -> return DispatchResult.Executed(results + ActionResult(ExecutionStatus.FAILED, decision.reason))
                is PolicyDecision.Confirm -> if (!confirmed) return DispatchResult.Rejected(decision.reason)
                PolicyDecision.Allow -> Unit
            }
            // Persist the attempt before side effects; unavailable audit storage fails closed.
            audit.record(action, ActionResult(ExecutionStatus.PARTIAL, "Started"), confirmed, source)
            val result = try { executor.execute(action) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) { ActionResult(ExecutionStatus.PERMISSION_MISSING, "Permission was denied or revoked.") }
            catch (_: Exception) { ActionResult(ExecutionStatus.FAILED, "The action failed. Check capabilities and try again.") }
            audit.record(action, result, confirmed, source)
            results += result
            if (result.status != ExecutionStatus.SUCCESS) break
        }
        return DispatchResult.Executed(results)
    }
}
