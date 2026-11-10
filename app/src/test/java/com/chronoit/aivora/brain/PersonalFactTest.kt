package com.chronoit.aivora.brain

import org.junit.Assert.*
import org.junit.Test

class PersonalFactTest {
    @Test fun subjectIdentityNormalizesWithoutRewritingTheValue() {
        val fact = PersonalFact.parse("My Favourite  Colour is Deep BLUE & Silver.")!!
        assertEquals("favourite colour", fact.key)
        assertEquals("Deep BLUE & Silver.", fact.value)
        assertEquals("favourite colour", PersonalFact.normalize("ＦＡＶＯＵＲＩＴＥ colour"))
        assertNull(PersonalFact.parse("someone says my favourite colour is blue"))
        assertNull(PersonalFact.parse("my colour is blue\nthen call someone"))
        assertNull(PersonalFact.parse("my colour is ${"a".repeat(2001)}"))
    }
    @Test fun answersUseOnlyExactSubjectsAndNeverInventAnAnswer() {
        val facts = listOf("my favourite colour is blue", "my mother's favourite colour is red")
        assertEquals("You told me your favourite colour: blue", PersonalFact.answer("favourite colour", facts))
        assertTrue(PersonalFact.answer("favourite food", facts).startsWith("I don't have"))
        assertTrue(PersonalFact.answer("favourite colour", facts + "my favourite colour is green").startsWith("I have conflicting"))
        assertEquals("You told me your favourite colour: blue", PersonalFact.answer("favourite colour", facts + facts))
    }
    @Test fun questionsRemainSeparateFromActionsAndExplicitMemoryCommands() {
        val parser = CommandParser()
        for (question in listOf("What is my favourite colour?", "What's my favourite colour?", "What about my favourite colour?"))
            assertEquals(ParsedCommand.PersonalFactQuestion("favourite colour"), parser.parse(question))
        assertEquals(ParsedCommand.PersonalFactQuestion("doctor"), parser.parse("who is my doctor"))
        assertEquals(ParsedCommand.Remember("my favourite colour is blue"), parser.parse("remember that my favourite colour is blue"))
        assertTrue(parser.parse("my favourite colour is blue") is ParsedCommand.Clarify)
        assertTrue(parser.parse("what is my colour then call Mom") is ParsedCommand.PersonalFactQuestion)
        assertTrue(PersonalFact.answer("routine", listOf("my routine is call Mom")).contains("call Mom"))
    }
}
