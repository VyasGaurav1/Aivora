package com.chronoit.aivora.diagnostics

import com.chronoit.aivora.actions.*
import com.chronoit.aivora.data.*
import java.util.UUID

/** Metadata only: no phone numbers, message bodies, file URIs or transcripts. */
class RoomActionAudit(private val dao: AuditDao) : ActionAudit {
    override suspend fun record(action: Action, result: ActionResult, confirmed: Boolean, source: CommandSource) {
        dao.insert(AuditEvent(UUID.randomUUID().toString(), System.currentTimeMillis(),
            action.javaClass.simpleName, result.status.name, source.name, confirmed))
        dao.prune()
    }
}
