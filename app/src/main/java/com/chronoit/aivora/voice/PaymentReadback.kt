package com.chronoit.aivora.voice

import com.chronoit.aivora.actions.*

/** Spoken representation only: never changes the UI/request or verifies a bank recipient. */
object PaymentReadback {
    fun create(action: Action.PaymentHandoff): String? {
        val request = action.request
        if (!request.valid()) return null
        val address = SpokenIdentifier.render(request.payeeVpa) ?: return null
        val name = SpokenIdentifier.render(request.payeeName) ?: return null
        val reference = SpokenIdentifier.render(request.reference) ?: return null
        val merchant = SpokenIdentifier.render(request.merchantCode) ?: return null
        val note = if (request.note.isEmpty()) "empty" else SpokenIdentifier.render(request.note) ?: return null
        val url = request.transactionUrl?.let { SpokenIdentifier.render(it) ?: return null } ?: "none"
        val app = if (action.app == PaymentApp.PHONEPE) "Phone Pay" else action.app.displayName
        val rupees = request.amountPaise / 100
        val fraction = request.amountPaise % 100
        return "Request payment review in $app. Amount $rupees Indian ${if (rupees == 1L) "rupee" else "rupees"} and $fraction ${if (fraction == 1L) "hundredth" else "hundredths"} of a rupee. " +
            "Fields use phonetic letter names. Capital marks upper case; other letters are lower case. " +
            "Payee name: $name. UPI address: $address. Reference: $reference. Merchant code: $merchant. " +
            "Note: $note. Transaction URL: $url. Payee identity is unverified. Complete authentication in the payment app. AIVORA cannot verify payment completion."
    }
}
