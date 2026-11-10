package com.chronoit.aivora.security

import java.text.Normalizer
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Blind word index: ciphertext stays encrypted; equality/frequency of indexed tokens remains visible. */
class SecureSearchIndex(private val keyProvider: () -> SecretKey) {
    // One Keystore operation derives an in-memory subkey; indexing does not call hardware for every word.
    private val key: SecretKey by lazy {
        val root = Mac.getInstance("HmacSHA256").apply { init(keyProvider()) }
        SecretKeySpec(root.doFinal("com.chronoit.aivora.search.subkey.v1".toByteArray(Charsets.UTF_8)), "HmacSHA256")
    }
    fun key(value: String): String = Mac.getInstance("HmacSHA256").run {
        init(key)
        doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun words(text: String, compounds: Boolean): List<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val expanded = if (compounds) normalized + " " + normalized.replace(Regex("(?<=[\\p{Ll}\\p{N}])(?=\\p{Lu})"), " ") else normalized
        return Regex("[\\p{L}\\p{N}]+").findAll(expanded.lowercase(Locale.ROOT)).map { it.value }.filter { it.length <= 80 }.distinct().toList()
    }
    fun tokens(text: String): List<String> = words(text, true).map { key("word:$it") }
    fun content(text: String): String = tokens(text).joinToString(" ")
    fun query(text: String): String? = words(text, false).take(20).takeIf { it.isNotEmpty() }?.joinToString(" ") { "tokens:${key("word:$it")}" }
    fun fileContent(name: String): String = (words(name, true).flatMap { word ->
        listOf(key("word:$word")) + (3..minOf(12, word.length)).map { key("prefix:${word.take(it)}") }
    }).distinct().joinToString(" ")
    fun fileQuery(text: String): String? = words(text, false).take(12).takeIf { it.isNotEmpty() }?.joinToString(" ") {
        "tokens:${key(if (it.length in 3..12) "prefix:$it" else "word:$it")}" }
}
