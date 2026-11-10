package com.chronoit.aivora.brain

import java.text.Normalizer
import java.util.Locale

data class ContactCandidate(val id: String, val name: String, val number: String, val label: String)
fun interface ContactDirectory { suspend fun search(name: String): List<ContactCandidate> }
fun normalizePhone(number: String): String {
    if (number.any { it !in "0123456789+ ()-." }) return ""
    val normalized = number.filter { it in '0'..'9' || it == '+' }
    return if (normalized.count { it == '+' } > 1 || normalized.drop(1).contains('+')) "" else normalized
}
fun normalizeReference(text: String): String = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

sealed interface ContactResolution {
    data class Resolved(val contact: ContactCandidate) : ContactResolution
    data class Choose(val candidates: List<ContactCandidate>) : ContactResolution
    data object Missing : ContactResolution
    data object TooMany : ContactResolution
}
object ContactResolver {
    fun resolve(query: String, candidates: List<ContactCandidate>): ContactResolution {
        if (candidates.size > 100) return ContactResolution.TooMany
        val unique = candidates.filter { normalizePhone(it.number).matches(Regex("\\+?[0-9]{3,30}")) }.distinctBy { "${it.id}:${normalizePhone(it.number)}" }
        val exact = unique.filter { normalizeReference(it.name) == normalizeReference(query) }
        return when {
            exact.size == 1 -> ContactResolution.Resolved(exact.single())
            exact.size > 1 -> ContactResolution.Choose(exact)
            unique.isNotEmpty() -> ContactResolution.Choose(unique)
            else -> ContactResolution.Missing
        }
    }
}
