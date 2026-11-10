package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.*
import java.util.Locale

/** Stores command text, never execution authority. Every invocation is parsed and validated again. */
data class LearnedPhrase(val phrase: String, val commands: List<String>) {
    fun encode(): String = (listOf("AIVORA_PHRASE_1", phrase) + commands).joinToString("\n")
    fun plan(parser: CommandParser, apps: List<LaunchableApp>): ActionPlan? {
        val actions = commands.flatMap { command ->
            val parsed = parser.parse(command, apps) as? ParsedCommand.Plan ?: return null
            if (parsed.value.actions.any { it is Action.PaymentHandoff || it is Action.ForgetPersonalFact || it is Action.SendSms }) return null
            parsed.value.actions
        }
        val plan = ActionPlan(actions, 1.0, "Explicitly taught phrase: $phrase")
        return plan.takeIf { PlanValidator.error(it) == null }
    }
    companion object {
        fun key(text: String) = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        fun create(phrase: String, definition: String): LearnedPhrase {
            require(phrase.length in 1..100 && !phrase.contains('\n') && !phrase.contains('\r'))
            require(definition.length in 1..4000 && !definition.contains('\n') && !definition.contains('\r'))
            val commands = definition.split(Regex("\\s+then\\s+", RegexOption.IGNORE_CASE)).map(String::trim)
            require(commands.size in 1..20 && commands.none(String::isBlank))
            return LearnedPhrase(phrase.trim(), commands)
        }
        fun decode(value: String): LearnedPhrase? = runCatching {
            val parts = value.split('\n')
            require(parts.size in 3..22 && parts.first() == "AIVORA_PHRASE_1")
            create(parts[1], parts.drop(2).joinToString(" then "))
        }.getOrNull()
    }
}
