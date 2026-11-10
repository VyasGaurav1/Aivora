package com.chronoit.aivora.scheduler

object ScheduledReviewPolicy {
    val states = setOf("PENDING", "BLOCKED", "WAITING_CONDITIONS", "AWAITING_REVIEW")
}
sealed interface DueActionSelection {
    data class Selected(val task: Reminder) : DueActionSelection
    data object None : DueActionSelection
    data class Multiple(val count: Int) : DueActionSelection
}
object DueActionSelector {
    fun select(tasks: List<Reminder>, now: Long, id: String? = null, next: Boolean = false): DueActionSelection {
        val candidates = tasks.filter { it.hasAction && it.dueAt <= now && it.state in ScheduledReviewPolicy.states }
            .sortedWith(compareBy<Reminder> { it.dueAt }.thenBy { it.id })
        if (id != null) return candidates.singleOrNull { it.id == id }?.let(DueActionSelection::Selected) ?: DueActionSelection.None
        if (candidates.isEmpty()) return DueActionSelection.None
        return if (next || candidates.size == 1) DueActionSelection.Selected(candidates.first()) else DueActionSelection.Multiple(candidates.size)
    }
}
