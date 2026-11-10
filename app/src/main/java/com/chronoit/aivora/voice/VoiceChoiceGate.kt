package com.chronoit.aivora.voice

import com.chronoit.aivora.actions.CommandSource
import com.chronoit.aivora.brain.*

/** A captured choice can only address the same numbered list, never a replacement list. */
class VoiceChoiceGate {
    private var captured: ChoiceRequest? = null
    @Synchronized fun arm(requests: List<ChoiceRequest>) { captured = requests.singleOrNull()?.takeIf { it.token.isNotBlank() && it.count > 0 } }
    @Synchronized fun clear() { captured = null }
    @Synchronized fun consume(source: CommandSource, reply: ChoiceReply, current: List<ChoiceRequest>): ChoiceRequest? {
        val request = captured
        captured = null
        return request?.takeIf { source == CommandSource.VERIFIED_VOICE && current.singleOrNull() == it &&
            ((reply.index != null) xor (reply.pageDelta != null)) && (reply.pageDelta == null || reply.pageDelta in -1..1) &&
            (reply.kind == null || reply.kind == it.kind) && (reply.index == null || reply.index in 0 until it.count) }
    }
}
