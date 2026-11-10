package com.chronoit.aivora.brain

import com.chronoit.aivora.scheduler.*
import org.junit.Assert.*
import org.junit.Test

class TaskConditionsTest {
    @Test fun allRequiredConditionsMustHoldAndUnvalidatedNetworksNeverQualify() {
        for (charging in listOf(false, true)) for (network in RequiredNetwork.entries) {
            val condition = TaskConditions(charging, network)
            assertEquals(condition, TaskConditions.decode(condition.encode()))
            for (plugged in listOf(false, true)) for (validated in listOf(false, true)) for (metered in listOf(false, true)) {
                val expected = (!charging || plugged) && (network == RequiredNetwork.NONE || validated && (network != RequiredNetwork.UNMETERED || !metered))
                assertEquals(expected, condition.satisfied(ConditionSnapshot(plugged, validated, metered)))
            }
        }
        assertTrue(runCatching { TaskConditions.decode("AIVORA_CONDITIONS_1|yes|NONE") }.isFailure)
        assertTrue(runCatching { TaskConditions.decode("AIVORA_CONDITIONS_1|true|WIFI") }.isFailure)
    }
    @Test fun explicitConditionalRequestsPreserveTheScheduledCommand() {
        assertEquals(ParsedCommand.Conditioned(TaskConditions(true, RequiredNetwork.UNMETERED), "in 10 minutes volume 30"),
            CommandParser().parse("when charging and on unmetered network, in 10 minutes volume 30"))
    }
}
