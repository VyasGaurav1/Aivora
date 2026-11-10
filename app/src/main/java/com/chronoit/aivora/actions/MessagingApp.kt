package com.chronoit.aivora.actions

import com.chronoit.aivora.brain.normalizePhone

enum class MessagingApp(val packageName: String, val displayName: String) {
    WHATSAPP("com.whatsapp", "WhatsApp"),
    WHATSAPP_BUSINESS("com.whatsapp.w4b", "WhatsApp Business"),
}

fun internationalPhone(number: String): String? = if (number.length > 40) null else normalizePhone(number).takeIf { it.matches(Regex("\\+[1-9][0-9]{5,14}")) }
