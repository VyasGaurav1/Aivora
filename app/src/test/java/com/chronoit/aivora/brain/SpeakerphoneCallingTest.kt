package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.*
import com.chronoit.aivora.platform.VoiceCallTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SpeakerphoneCallingTest {
    private val target = VoiceCallTarget(1, 0, 10, "Test carrier")
    private val contact = ContactCandidate("1", "Rahul", "+12025550123", "mobile")
    private fun action(text: String) = (CommandParser().parse(text) as ParsedCommand.Plan).value.actions.single()
    @Test fun explicitSpeakerSuffixKeepsNumericAndNamedTargetsSeparate() {
        listOf("speaker", "speakerphone", "loudspeaker").forEach {
            assertEquals(Action.Dial("+12025550123", speakerphone = true), action("call +12025550123 on $it"))
            assertEquals(ParsedCommand.CallPerson("Rahul", true), CommandParser().parse("Call Rahul on $it!"))
        }
        assertEquals(ParsedCommand.CallPerson("Speaker"), CommandParser().parse("call Speaker"))
        assertEquals(Action.Dial("+12025550123"), action("call +12025550123"))
        assertTrue(CommandParser().parse("call Rahul on speaker and send money") is ParsedCommand.Clarify)
    }
    @Test fun oldDefaultAudioRecordsRemainByteCompatibleAndSpeakerTagsRoundTrip() {
        val old = "AIVORA_ACTION_1\nZGlhbA\tKzEyMDI1NTUwMTIz\t~\t~"
        assertEquals(old, ActionCodec.encode(Action.Dial("+12025550123")))
        assertEquals(Action.Dial("+12025550123"), ActionCodec.decode(old))
        val direct = Action.PlaceCall("+12025550123", target)
        for (call in listOf<Action>(Action.Dial("+12025550123", speakerphone = true), direct, direct.copy(speakerphone = true))) {
            assertEquals(call, ActionCodec.decode(ActionCodec.encode(call)))
            assertTrue(runCatching { ActionCodec.decode(ActionCodec.encode(call) + "\textra") }.isFailure)
        }
        assertNotEquals(ActionCodec.encode(direct), ActionCodec.encode(direct.copy(speakerphone = true)))
    }
    @Test fun bindingAndFallbackKeepSpeakerIntentVisibleToPolicyReview() {
        val plan = ActionPlan(listOf(Action.Dial("+12025550123", speakerphone = true)), 1.0, "speaker request")
        val bound = DirectCallPlanner.bind(plan, target)
        assertEquals(Action.PlaceCall("+12025550123", target, speakerphone = true), bound.actions.single())
        assertEquals(RiskLevel.HIGH, bound.riskLevel)
        assertTrue(bound.actions.single().describe().contains("on speakerphone"))
        assertSame(plan, DirectCallPlanner.bind(plan, null))
        assertTrue(plan.actions.single().describe().contains("Select speakerphone in the dialer"))
    }
    private fun coordinator(people: List<ContactCandidate>) = DialogueCoordinator(CommandParser(), ContactDirectory { people }, object : DocumentDirectory {
        override suspend fun find(query: String) = emptyList<LocalDocument>()
        override suspend fun get(id: String): LocalDocument? = null
        override fun readable(document: LocalDocument) = false
    })
    private fun resolved(turn: DialogueTurn) = ((turn as DialogueTurn.Command).command as ParsedCommand.Plan).value.actions.single()
    @Test fun contactChoiceRetainsSpeakerFlagAndRejectsStaleTokens() = runTest {
        val second = contact.copy(number = "+12025550124", label = "work")
        val dialogue = coordinator(listOf(contact, second))
        val choices = dialogue.process("call Rahul on speaker", emptyList()) as DialogueTurn.Contacts
        assertTrue(dialogue.chooseContact(0, "stale") is DialogueTurn.Answer)
        assertEquals(Action.Dial(second.number, second.id, second.name, true), resolved(dialogue.chooseContact(1, choices.token)))
    }
    @Test fun pronounsRetainOnlyTheirOwnExplicitSpeakerRequest() = runTest {
        val dialogue = coordinator(listOf(contact))
        assertEquals(Action.Dial(contact.number, contact.id, contact.name, true), resolved(dialogue.process("call Rahul on speaker", emptyList())))
        assertEquals(Action.Dial(contact.number, contact.id, contact.name, true), resolved(dialogue.process("call him on speakerphone", emptyList())))
        assertEquals(Action.Dial(contact.number, contact.id, contact.name), resolved(dialogue.process("call him", emptyList())))
    }
    @Test fun taughtExplicitCallsReparseSpeakerIntentWithoutStoringAuthority() {
        val phrase = LearnedPhrase.create("speaker call", "call +12025550123 on speaker")
        val restored = LearnedPhrase.decode(phrase.encode())!!.plan(CommandParser(), emptyList())!!
        assertEquals(Action.Dial("+12025550123", speakerphone = true), restored.actions.single())
        assertEquals(RiskLevel.HIGH, DirectCallPlanner.bind(restored, target).riskLevel)
    }
}
