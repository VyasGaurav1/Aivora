package com.chronoit.aivora.actions

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.math.BigDecimal

/** User-provided merchant request data, never bank-verified identity or payment authority. */
enum class PaymentApp(val packageName: String, val displayName: String) {
    GOOGLE_PAY("com.google.android.apps.nbu.paisa.user", "Google Pay"),
    PHONEPE("com.phonepe.app", "PhonePe")
}

data class PaymentRequest(
    val payeeVpa: String,
    val payeeName: String,
    val merchantCode: String,
    val reference: String,
    val amountPaise: Long,
    val note: String = "",
    val transactionUrl: String? = null,
) {
    val amount: String get() = BigDecimal.valueOf(amountPaise, 2).toPlainString()
    fun valid(): Boolean = payeeVpa.matches(Regex("[A-Za-z0-9._-]{2,100}@[A-Za-z0-9.-]{2,64}")) &&
        safeText(payeeName, 100) && payeeName.isNotBlank() && merchantCode.matches(Regex("[0-9]{4}")) &&
        reference.matches(Regex("[A-Za-z0-9._-]{1,64}")) && amountPaise in 1..999999999999L && safeText(note, 140) &&
        (transactionUrl == null || validUrl(transactionUrl)) && encodedUri().length <= 4096

    fun uri(): String {
        require(valid())
        return encodedUri()
    }
    private fun encodedUri() = "upi://pay?" + fields().entries.joinToString("&") { (key, value) -> key + "=" + URLEncoder.encode(value, "UTF-8") }
    private fun fields(): Map<String, String> = linkedMapOf("pa" to payeeVpa, "pn" to payeeName, "mc" to merchantCode,
        "tr" to reference, "tn" to note, "am" to amount, "cu" to "INR").apply { transactionUrl?.let { put("url", it) } }

    companion object {
        private fun safeText(value: String, max: Int) = value.length <= max && value == value.trim() &&
            value.codePoints().noneMatch { it in 0xD800..0xDFFF } && value.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() || it == '\uFFFD' }
        private fun validUrl(value: String) = value.length <= 2048 && safeText(value, 2048) && runCatching {
            val uri = URI(value)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.port in setOf(-1, 443)
        }.getOrDefault(false)

        /** Unknown, repeated or incomplete fields fail closed; original URI is never executed. */
        fun parse(value: String, amountIfMissingPaise: Long? = null): PaymentRequest? = runCatching {
            require(value.length <= 4096 && value.none(Char::isISOControl))
            val uri = URI(value)
            require(uri.scheme == "upi" && uri.rawAuthority == "pay" && uri.rawPath.isNullOrEmpty() && uri.rawFragment == null)
            val entries = requireNotNull(uri.rawQuery).split('&').map { entry ->
                val parts = entry.split('=', limit = 2)
                require(parts.size == 2)
                parts[0] to URLDecoder.decode(parts[1], "UTF-8")
            }
            require(entries.map { it.first }.distinct().size == entries.size)
            val fields = entries.toMap()
            require(fields.keys.all { it in setOf("pa", "pn", "mc", "tr", "tn", "am", "cu", "url") })
            require(fields["cu"] == "INR")
            val amountPaise = fields["am"]?.let { amount ->
                require(amount.matches(Regex("[0-9]{1,10}(?:[.][0-9]{1,2})?")))
                BigDecimal(amount).movePointRight(2).longValueExact()
            } ?: requireNotNull(amountIfMissingPaise)
            PaymentRequest(requireNotNull(fields["pa"]), requireNotNull(fields["pn"]), requireNotNull(fields["mc"]),
                requireNotNull(fields["tr"]), amountPaise, fields["tn"].orEmpty(), fields["url"])
                .also { require(it.valid()) }
        }.getOrNull()
    }
}
