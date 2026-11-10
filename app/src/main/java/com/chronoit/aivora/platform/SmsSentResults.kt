package com.chronoit.aivora.platform

import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** In-process sent callbacks, never delivery proof or replayable authorization. */
object SmsSentResults {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Int>>()
    fun register(id: String = UUID.randomUUID().toString()): Pair<String, CompletableDeferred<Int>> {
        val result = CompletableDeferred<Int>()
        check(pending.putIfAbsent(id, result) == null)
        return id to result
    }
    fun complete(id: String, code: Int) { pending.remove(id)?.complete(code) }
    fun remove(id: String) { pending.remove(id)?.cancel() }
}
