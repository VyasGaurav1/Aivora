package com.chronoit.aivora.brain

import org.junit.Assert.*
import org.junit.Test

class HandsFreeOptInTest {
    private var nowMillis = 1_000_000L
    private val policy = HandsFreeOptInPolicy(clock = { nowMillis })

    @Test fun onlyExactPhrasesRequestOrConfirmAnOptIn() {
        assertEquals(
            HandsFreeOptInCommand.Request(HandsFreeControl.HANDS_FREE, true),
            policy.match("enable hands-free controls"),
        )
        assertEquals(
            HandsFreeOptInCommand.Request(HandsFreeControl.HANDS_FREE, true),
            policy.match("Turn on hands-free controls."),
        )
        assertEquals(
            HandsFreeOptInCommand.Request(HandsFreeControl.DIRECT_CALLING, true),
            policy.match("ENABLE DIRECT CALLING"),
        )
        assertEquals(
            HandsFreeOptInCommand.Request(HandsFreeControl.DIRECT_SMS, true),
            policy.match("enable direct texting"),
        )
        assertEquals(
            HandsFreeOptInCommand.Request(HandsFreeControl.WIFI, true),
            policy.match("enable Wi-Fi controls"),
        )
        assertEquals(
            HandsFreeOptInCommand.Request(HandsFreeControl.MOBILE_DATA, false),
            policy.match("turn off mobile data controls"),
        )
        assertEquals(
            HandsFreeOptInCommand.Confirm(HandsFreeControl.HANDS_FREE, true),
            policy.match("yes, enable hands-free controls"),
        )
        assertEquals(
            HandsFreeOptInCommand.Confirm(HandsFreeControl.DIRECT_SMS, false),
            policy.match("Yes, disable direct SMS!"),
        )
        assertEquals(HandsFreeOptInCommand.Status, policy.match("voice controls status"))
        assertEquals(HandsFreeOptInCommand.Status, policy.match("What voice controls are enabled?"))
    }

    @Test fun bareYesAndVaguePhrasesNeverOptAnythingIn() {
        for (phrase in listOf(
            "yes",
            "yes please",
            "enable it",
            "turn it on",
            "turn it off",
            "yes, enable it",
            "enable hands-free",
            "enable calling",
            "enable direct",
            "disable sms",
            "turn on wifi",
            "enable the controls",
            "call Mom",
        )) {
            assertNull("'$phrase' must never match an opt-in command", policy.match(phrase))
        }
    }

    @Test fun aConfirmationOnlyCountsForTheRequestedControlWhileCurrent() {
        val pending = HandsFreeOptInPolicy.Pending(HandsFreeControl.DIRECT_CALLING, true, nowMillis, "FOREGROUND_OWNER")
        assertTrue(policy.isCurrent(pending))
        nowMillis += 59_000
        assertTrue("a confirmation 59 seconds later is still current", policy.isCurrent(pending))
        nowMillis += 2_000
        assertFalse("a confirmation 61 seconds later has expired", policy.isCurrent(pending))
        assertFalse(policy.isCurrent(null))
        // A confirmation phrase for a different control is a different
        // command, so the caller can refuse it without touching the pending
        // request for direct calling.
        assertEquals(
            HandsFreeOptInCommand.Confirm(HandsFreeControl.HANDS_FREE, true),
            policy.match("yes, enable hands-free controls"),
        )
        assertNotEquals(pending.control, (policy.match("yes, enable hands-free controls") as HandsFreeOptInCommand.Confirm).control)
    }

    @Test fun enablingNeedsDeviceAuthAndDisablingDoesNot() {
        for (control in HandsFreeControl.entries) {
            assertTrue(control.name, HandsFreeOptInPolicy.requiresDeviceAuth(true))
            assertFalse(control.name, HandsFreeOptInPolicy.requiresDeviceAuth(false))
        }
    }

    @Test fun scopeTextStatesWhatEachControlUnlocksAndWhatStillGuardsIt() {
        assertTrue(HandsFreeOptInPolicy.scopeText(HandsFreeControl.HANDS_FREE).contains("device credential"))
        assertTrue(HandsFreeOptInPolicy.scopeText(HandsFreeControl.HANDS_FREE).contains("cannot guarantee liveness"))
        assertTrue(HandsFreeOptInPolicy.scopeText(HandsFreeControl.DIRECT_CALLING).contains("exact confirmation for every call"))
        assertTrue(HandsFreeOptInPolicy.scopeText(HandsFreeControl.DIRECT_CALLING).contains("Connection is not verified"))
        assertTrue(HandsFreeOptInPolicy.scopeText(HandsFreeControl.DIRECT_SMS).contains("not proof of delivery"))
        assertTrue(HandsFreeOptInPolicy.scopeText(HandsFreeControl.WIFI).contains("never picks a network"))
        assertTrue(HandsFreeOptInPolicy.scopeText(HandsFreeControl.MOBILE_DATA).contains("never switches SIMs"))
        val prompt = HandsFreeOptInPolicy.requestPrompt(HandsFreeControl.WIFI, true)
        assertTrue(prompt.contains("yes, enable Wi-Fi controls"))
        assertTrue(prompt.contains("one minute"))
    }

    @Test fun statusTextListsAllFiveControlsHonestly() {
        val text = HandsFreeOptInPolicy.statusText(handsFree = true, directCalling = false, directSms = true, wifi = false, mobileData = false)
        assertTrue(text.contains("hands-free controls on"))
        assertTrue(text.contains("direct calling off"))
        assertTrue(text.contains("direct SMS on"))
        assertTrue(text.contains("Wi-Fi controls off"))
        assertTrue(text.contains("mobile data controls off"))
        assertTrue(HandsFreeOptInPolicy.statusText(false, false, false, false, false).contains("hands-free controls off"))
    }
}
