package com.chronoit.aivora.brain

/** Short-lived slot filling for the three release tasks. Never executes an action itself. */
class StarterVoiceDialogue(private val now: () -> java.time.LocalDateTime = { java.time.LocalDateTime.now() }) {
    sealed interface Turn {
        data class Command(val text: String) : Turn
        data class Ask(val question: String) : Turn
    }
    private var pending: String? = null
    private var reminderText: String? = null
    private var reminderDay: String? = null
    val awaitingInput: Boolean get() = pending != null
    private var alarmDay: String? = null
    private var alarmPeriod: String? = null
    fun clear() { pending = null; reminderText = null; reminderDay = null; alarmDay = null; alarmPeriod = null }
    fun next(input: String): Turn {
        val correctedInput = if (pending != null) input.replace(Regex("^(?:no[, ]+)?(?:make it|change it to|i meant) ", RegexOption.IGNORE_CASE), "") else input
        val text = correctedInput.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
            .replace(Regex("^(?:(?:can|could|would) you |please )+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:i (?:need|want)|put|make|give me) (?:an? )?alarm\\b", RegexOption.IGNORE_CASE), "set an alarm")
            .replace(Regex("^wake me (?=at|tomorrow|today)", RegexOption.IGNORE_CASE), "wake me up ")
            .replace(Regex("^(?:don't let me forget|do not let me forget|make sure i remember) ", RegexOption.IGNORE_CASE), "remind me to ")
        val lower = text.lowercase(java.util.Locale.ROOT)
        val explicit = Regex("^(?:set |create |wake me|remind |remember |search |what |who |how |when |why |tell me ).*", RegexOption.IGNORE_CASE).matches(text)
        val reminderInput = if (pending == "reminder_text" && !explicit) "remind me about $text" else text
        when (val reminder = NaturalReminder.parse(reminderInput, now())) {
            is NaturalReminder.Result.Ready -> { clear(); return Turn.Command(reminder.command) }
            is NaturalReminder.Result.NeedsTime -> {
                clear(); reminderText = reminder.content; reminderDay = reminder.day; pending = "reminder_delay"
                return Turn.Ask(if (reminderDay != null) "What time $reminderDay should I remind you?" else "When should I remind you?")
            }
            null -> Unit
        }
        if (explicit) clear()
        when (pending) {
            "alarm" -> return alarm(text)
            "memory" -> { clear(); return Turn.Command("remember that $text") }
            "reminder_text" -> { reminderText = text; pending = "reminder_delay"; return Turn.Ask("When should I remind you? For example, in ten minutes.") }
            "reminder_delay" -> {
                val payload = reminderText ?: return Turn.Ask("What should I remind you about?")
                NaturalReminder.absolute(payload, "${reminderDay.orEmpty()} $text", now())?.let { clear(); return Turn.Command(it.command) }
                val interval = NaturalReminder.interval(text.replace(Regex("^(?:in|after) ", RegexOption.IGNORE_CASE), ""))
                val command = "remind me in $interval to $payload"
                if (CommandParser().parse(command) is ParsedCommand.Reminder) { clear(); return Turn.Command(command) }
                return Turn.Ask("How long from now? Say, for example, in ten minutes or after three days.")
            }
            "search" -> { clear(); return Turn.Command("search Google for $text") }
        }
        if (lower in setOf("set alarm", "set an alarm", "set a alarm", "set the alarm", "alarm", "create an alarm")) {
            pending = "alarm"; return Turn.Ask("What time should I set the alarm for?")
        }
        Regex("(?:(?:set|create) (?:an? |the )?)?alarm(?: clock)?(?: (?:for|at|to))? (.+)", RegexOption.IGNORE_CASE).matchEntire(text)?.let {
            return alarm(it.groupValues[1])
        }
        Regex("(?:wake me(?: up)?|I (?:need|want) to (?:wake|get) up)(?: (?:at|for))? (.+)", RegexOption.IGNORE_CASE).matchEntire(text)?.let {
            return alarm(it.groupValues[1])
        }
        Regex("(?:remind me to|set (?:a |the )?reminder to) (.+?) (?:in|after) (.+)", RegexOption.IGNORE_CASE).matchEntire(text)?.let {
            return Turn.Command("remind me in ${it.groupValues[2]} to ${it.groupValues[1]}")
        }
        Regex("(?:remind me to|set (?:a |the )?reminder (?:to|for)) (.+)", RegexOption.IGNORE_CASE).matchEntire(text)?.let {
            reminderText = it.groupValues[1]; pending = "reminder_delay"
            return Turn.Ask("When should I remind you?")
        }
        if (lower in setOf("remind me", "set a reminder", "set reminder", "set the reminder", "create a reminder")) {
            pending = "reminder_text"; return Turn.Ask("What should I remind you about?")
        }
        if (lower in setOf("remember", "remember this", "remember something", "save a memory")) {
            pending = "memory"; return Turn.Ask("What would you like me to remember?")
        }
        if (lower in setOf("search", "search google", "search the web", "ask a question")) {
            pending = "search"; return Turn.Ask("What would you like me to look up?")
        }
        return Turn.Command(text)
    }
    private fun alarm(input: String): Turn {
        val lower = input.lowercase(java.util.Locale.ROOT)
        Regex("\\b(tomorrow|today)\\b").find(lower)?.let { alarmDay = it.value }
        Regex("\\b(morning|afternoon|evening)\\b").find(lower)?.let { alarmPeriod = it.value }
        var value = lower.replace(Regex("\\b(?:tomorrow|today|morning|afternoon|evening|in the|at|for)\\b"), " ")
            .replace(Regex(" o['’]?clock", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ").trim()
        val hasPeriod = Regex("[ap]\\.? ?m\\.?$").containsMatchIn(value)
        if (alarmPeriod != null && !hasPeriod) value += if (alarmPeriod == "morning") " am" else " pm"
        val time = SpokenClockTime.parse(value)
        if (time != null) {
            val current = now()
            val nativeDate = current.toLocalDate().plusDays(if (time.isAfter(current.toLocalTime())) 0 else 1)
            val requestedDate = when (alarmDay) { "today" -> current.toLocalDate(); "tomorrow" -> current.toLocalDate().plusDays(1); else -> nativeDate }
            if (nativeDate != requestedDate) {
                pending = "alarm"
                return Turn.Ask("Your Clock app would ring at the next occurrence of that time, which is a different day. Please choose another time or set the dated alarm in Clock.")
            }
            if (alarmPeriod == "morning" && time.hour >= 12 || alarmPeriod in setOf("afternoon", "evening") && time.hour < 12) {
                alarmPeriod = null
                pending = "alarm"
                return Turn.Ask("Did you mean AM or PM? Please say the time with AM or PM.")
            }
            clear()
            return Turn.Command("set alarm for $time")
        }
        pending = "alarm"
        return Turn.Ask("What time should I use? Say, for example, seven thirty AM.")
    }
}
