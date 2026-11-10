package com.chronoit.aivora.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechTextNormalizerTest {
    private val dictionary = (('a'..'z').map(Char::toString) + listOf("aivora", "volume", "forty", "percent", "alarm", "seven", "thirty", "zero", "one", "two", "three", "hundred", "thousand", "nine", "six", "eight", "five", "four", "word")).toSet()
    private val normalizer = SpeechTextNormalizer(dictionary)
    @Test fun readsPercentagesTimesAndProductName() {
        assertEquals("aivora volume forty percent .", normalizer.normalize("AIVORA volume 40%."))
        assertEquals("alarm seven : thirty", normalizer.normalize("Alarm 7:30"))
    }
    @Test fun spellsUnknownNamesInsteadOfDroppingThem() {
        assertEquals("z y q n o v a", normalizer.normalize("Zyqnova"))
        assertEquals("zero zero one two", normalizer.normalize("0012"))
    }
    @Test fun readsLargeNumbersAsDigitsWithoutOverflow() {
        assertEquals("nine nine nine nine nine nine nine nine nine nine nine nine", normalizer.normalize("999999999999"))
    }
}
