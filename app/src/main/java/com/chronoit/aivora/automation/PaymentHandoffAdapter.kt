package com.chronoit.aivora.automation

import android.content.Intent
import android.net.Uri
import com.chronoit.aivora.actions.*

/** No payment authentication, Send/Pay click, response trust or completion claim. */
object PaymentHandoffAdapter {
    fun intent(action: Action.PaymentHandoff): Intent {
        require(action.request.valid())
        return Intent(Intent.ACTION_VIEW, Uri.parse(action.request.uri())).setPackage(action.app.packageName)
    }
}
