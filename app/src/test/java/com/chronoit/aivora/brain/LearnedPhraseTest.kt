package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.*
import org.junit.Assert.*
import org.junit.Test

class LearnedPhraseTest {
    private val parser = CommandParser()
    @Test fun roundTripAndReparseRoutineWithoutStoredAuthority() {
        val phrase = LearnedPhrase.create("usual setup", "volume 30 then pause music")
        assertEquals(phrase, LearnedPhrase.decode(phrase.encode()))
        assertEquals(listOf(Action.SetVolume(30), Action.Media(MediaOperation.PAUSE)), phrase.plan(parser, emptyList())!!.actions)
        assertNotEquals(phrase.plan(parser, emptyList())!!.planId, phrase.plan(parser, emptyList())!!.planId)
    }
    @Test fun missingAppsAndContextCannotBecomeExecutablePlans() {
        val phrase = LearnedPhrase.create("work time", "open Editor")
        assertNotNull(phrase.plan(parser, listOf(LaunchableApp("Editor", "org.editor.app"))))
        assertNull(phrase.plan(parser, emptyList()))
        assertNull(LearnedPhrase.create("blue one", "send it to Rahul").plan(parser, emptyList()))
        assertNull(LearnedPhrase.create("bad volume", "volume 101").plan(parser, emptyList()))
        assertNull(LearnedPhrase.decode("unknown\nphrase\nvolume 30"))
    }
    @Test fun parsesExplicitTeachingAndBoundsDefinitions() {
        assertEquals(ParsedCommand.TeachPhrase("usual setup", "volume 30 then pause music"), parser.parse("When I say usual setup, do volume 30 then pause music"))
        assertThrows(IllegalArgumentException::class.java) { LearnedPhrase.create("x\ny", "volume 30") }
        assertThrows(IllegalArgumentException::class.java) { LearnedPhrase.create("x", (1..21).joinToString(" then ") { "pause music" }) }
    }
}
