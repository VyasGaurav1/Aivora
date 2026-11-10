package com.chronoit.aivora.scheduler

import com.chronoit.aivora.brain.ChoiceKind
import com.chronoit.aivora.brain.ChoiceRequest
import java.util.UUID

/** Immutable occurrence snapshots; a spoken list never silently reorders itself. */
class DueTaskChoices(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var pending: Pair<ChoiceRequest, List<Reminder>>? = null
    private var expires = 0L
    @Synchronized fun present(tasks: List<Reminder>, now: Long): ChoiceRequest? {
        val due = tasks.filter { it.hasAction && it.dueAt <= now && it.state in ScheduledReviewPolicy.states }
            .sortedWith(compareBy<Reminder> { it.dueAt }.thenBy { it.id }).toList()
        clear()
        if (due.isEmpty()) return null
        val request = ChoiceRequest(ChoiceKind.TASK, UUID.randomUUID().toString(), due.size)
        pending = request to due
        expires = clock() + 120_000
        return request
    }
    @Synchronized fun current(): ChoiceRequest? {
        if (clock() >= expires) clear()
        return pending?.first
    }
    @Synchronized fun entries(request: ChoiceRequest): List<Reminder> =
        if (current() == request) pending!!.second else emptyList()
    @Synchronized fun consume(request: ChoiceRequest, index: Int): Reminder? {
        val selected = entries(request).getOrNull(index) ?: return null
        clear()
        return selected
    }
    @Synchronized fun clear() { pending = null; expires = 0 }
}
