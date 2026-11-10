package com.chronoit.aivora.connectivity

import com.chronoit.aivora.actions.*
import com.chronoit.aivora.data.InternetBehavior
import java.util.UUID

data class NetworkSnapshot(val validated: Boolean, val wifiEnabled: Boolean? = null, val route: NetworkRoute? = null)
enum class NetworkRoute(val label: String) { VPN("VPN"), WIFI("Wi-Fi"), MOBILE("mobile data"), ETHERNET("Ethernet") }
data class NetworkReview(val token: String, val plan: ActionPlan, val source: CommandSource, val expiresAt: Long)
sealed interface NetworkDecision {
    data class Run(val plan: ActionPlan, val source: CommandSource, val restoreRequested: Boolean = false) : NetworkDecision
    data class Ask(val token: String) : NetworkDecision
    data class OpenControls(val token: String) : NetworkDecision
    data class Wait(val message: String) : NetworkDecision
}

/** Owner intent/provenance stays outside serialized plans. Pending work expires and is single-use. */
class ConnectivityOrchestrator(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Pending(val plan: ActionPlan, val source: CommandSource, val previous: NetworkSnapshot,
        val restore: Boolean, val expires: Long, val token: String = UUID.randomUUID().toString(), val approved: Boolean = false)
    private var pending: Pending? = null
    @Synchronized fun begin(plan: ActionPlan, source: CommandSource, snapshot: NetworkSnapshot, behavior: InternetBehavior): NetworkDecision {
        cancel()
        require(PlanValidator.error(plan) == null)
        if (source == CommandSource.UNVERIFIED_VOICE) return NetworkDecision.Wait("Verify the owner before continuing this task.")
        if (plan.actions.none { it.requiresNetwork() } || snapshot.validated) return NetworkDecision.Run(plan, source)
        if (behavior == InternetBehavior.LEAVE_UNCHANGED) return NetworkDecision.Wait("This task needs internet. Connectivity was left unchanged as configured. Connect and repeat the task.")
        val task = Pending(plan.copy(actions = java.util.Collections.unmodifiableList(plan.actions.toList())), source, snapshot,
            behavior == InternetBehavior.RESTORE_AFTERWARD, clock() + 60_000, approved = behavior == InternetBehavior.ENABLE_WHEN_REQUIRED)
        pending = task
        return if (task.approved) NetworkDecision.OpenControls(task.token) else NetworkDecision.Ask(task.token)
    }
    @Synchronized fun approve(token: String): NetworkDecision {
        val task = current() ?: return NetworkDecision.Wait("The online task expired. Repeat the task.")
        if (task.token != token) return NetworkDecision.Wait("That approval no longer matches the task.")
        pending = task.copy(approved = true)
        return NetworkDecision.OpenControls(task.token)
    }
    @Synchronized fun resume(snapshot: NetworkSnapshot, expectedToken: String): NetworkDecision? {
        val task = current() ?: return null
        if (task.token != expectedToken || !task.approved || !snapshot.validated) return null
        pending = null
        return NetworkDecision.Run(task.plan, task.source, task.restore)
    }
    @Synchronized fun status(snapshot: NetworkSnapshot): String {
        val internet = if (snapshot.validated) snapshot.route?.let { "Android reports internet access over ${it.label}." } ?: "Android reports internet access." else "Android has not validated internet access."
        val wifi = when (snapshot.wifiEnabled) {
            true -> "Wi-Fi is on."
            false -> "Wi-Fi is off."
            null -> "Wi-Fi state is unavailable."
        }
        val task = current() ?: return "$internet $wifi No online task is waiting."
        val pendingStatus = when {
            !task.approved -> "The online task is waiting for your connection approval."
            !snapshot.validated -> "The online task is approved and waiting for an internet connection."
            else -> "The online task is approved and the connection is ready."
        }
        return "$internet $wifi $pendingStatus"
    }

    @Synchronized fun token(): String? = current()?.token
    @Synchronized fun review(token: String): NetworkReview? = current()?.takeIf { it.token == token }?.let {
        NetworkReview(it.token, it.plan, it.source, it.expires)
    }
    @Synchronized fun previousSnapshot(): NetworkSnapshot? = current()?.previous
    @Synchronized fun approvedSource(token: String): CommandSource? = current()?.takeIf { it.token == token && it.approved }?.source
    @Synchronized fun expireApproved(token: String): Boolean {
        val task = pending ?: return false
        if (task.token != token || !task.approved || clock() < task.expires) return false
        pending = null
        return true
    }
    @Synchronized fun cancel() { pending = null }
    private fun current(): Pending? {
        val task = pending ?: return null
        if (clock() >= task.expires) { cancel(); return null }
        return task
    }
}
