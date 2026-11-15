package com.chronoit.aivora.brain

/** Release capabilities are authoritative; the model may route, never grant new actions. */
object StarterCapabilities {
    fun knownRoute(text: String): String? {
        if (isQuestion(text)) return "CAPABILITIES"
        val request = text.lowercase(java.util.Locale.ROOT).trim()
            .replace(Regex("^(?:(?:can|could|would) you |are you able to |i (?:want|need) you to |please )+"), "")
        if (Regex("^(?:send|pay|transfer|call|dial|open|launch|turn|enable|disable|delete|install|book|order|play|take|record|unlock)\\b").containsMatchIn(request)) return "UNSUPPORTED"
        if (Regex("^(?:i (?:need|want)|make|put|give me|arrange) (?:an? )?alarm\\b").containsMatchIn(request)) return "ALARM"
        return null
    }
    const val description = "I can remember things you tell me, set timed reminders and speak them aloud, request alarms in your Clock app, and answer short questions offline. I can prepare WhatsApp and SMS drafts and open the dialer for a call after your exact confirmation — I never press Send or place a call on my own, and direct calling or SMS need to be switched on in Settings first, or enabled by voice with your device credential. Web search is optional and needs a Brave key. Voice opt-ins: say \"enable hands-free controls\" — or direct calling, direct SMS, Wi-Fi controls or mobile data controls — then confirm with the full phrase and your device credential. Say \"voice controls status\" to hear what is on. Try: remind me about my competition tomorrow at 7 AM."
    fun isQuestion(text: String): Boolean {
        val value = text.lowercase(java.util.Locale.ROOT).trim().trimEnd('.', '?', '!')
        return Regex("(?:(?:please )?(?:tell me )?(?:what can you do|what do you do|what are your capabilities|your capabilities|what are your features|your features|how can you help(?: me)?|what are you able to do|what tasks can you do|what can i ask you))", RegexOption.IGNORE_CASE).matches(value)
    }
    fun routePrompt(text: String): String = "<|im_start|>system\nClassify a request for AIVORA. Reply as JSON with a reply string containing exactly one label: CAPABILITIES, ALARM, REMINDER, MEMORY, QUESTION, UNSUPPORTED. ALARM: setting a clock alarm. REMINDER: timed reminder. MEMORY: remember or recall personal information. QUESTION: information, explanations, web questions. UNSUPPORTED: requests to perform other actions, including calls, payments, messages, app controls. CAPABILITIES: questions about what AIVORA can do. Do not execute or rewrite requests.\n<|im_end|>\n<|im_start|>user\n${text.take(1600).replace("<|", "< |")} /no_think\n<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n"
    fun label(result: ReasoningResult): String? = (result as? ReasoningResult.Answer)?.text?.trim()?.takeIf {
        it in setOf("CAPABILITIES", "ALARM", "REMINDER", "MEMORY", "QUESTION", "UNSUPPORTED")
    }
}
