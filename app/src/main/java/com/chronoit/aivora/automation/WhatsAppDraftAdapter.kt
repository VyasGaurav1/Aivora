package com.chronoit.aivora.automation

import android.content.Intent
import android.net.Uri
import com.chronoit.aivora.actions.*

/** Supported click-to-chat handoff only. Never presses Send or falls back to a browser. */
object WhatsAppDraftAdapter {
    fun intent(action: Action.DraftAppMessage): Intent {
        require(PlanValidator.error(ActionPlan(listOf(action), 1.0, "Message draft")) == null)
        val number = requireNotNull(internationalPhone(action.phoneNumber)).removePrefix("+")
        val uri = Uri.Builder().scheme("https").authority("wa.me").appendPath(number).appendQueryParameter("text", action.body).build()
        return Intent(Intent.ACTION_VIEW, uri).setPackage(action.app.packageName)
    }
}
