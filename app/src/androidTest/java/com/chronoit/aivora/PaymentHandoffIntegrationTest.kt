package com.chronoit.aivora

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chronoit.aivora.actions.*
import com.chronoit.aivora.automation.PaymentHandoffAdapter
import com.chronoit.aivora.brain.CommandParser
import com.chronoit.aivora.brain.ParsedCommand
import com.chronoit.aivora.ui.AssistantViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Inspects payload only: never starts a payment app or creates a transaction. */
@RunWith(AndroidJUnit4::class)
class PaymentHandoffIntegrationTest {
    private val request = PaymentRequest("example.shop@bank", "Test Shop", "5411", "fixture-reference", 12550,
        "Tea & coffee?", "https://example.com/order?id=1&item=2")

    @Test fun exactFieldsAreEncodedAndPinnedToEachExplicitApp() {
        for (app in PaymentApp.entries) {
            val intent = PaymentHandoffAdapter.intent(Action.PaymentHandoff(app, request))
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(app.packageName, intent.`package`)
            assertNull(intent.component)
            assertEquals(0, intent.flags)
            val uri = intent.data!!
            assertEquals("upi", uri.scheme)
            assertEquals("pay", uri.authority)
            assertEquals(request.payeeVpa, uri.getQueryParameter("pa"))
            assertEquals(request.payeeName, uri.getQueryParameter("pn"))
            assertEquals(request.merchantCode, uri.getQueryParameter("mc"))
            assertEquals(request.reference, uri.getQueryParameter("tr"))
            assertEquals("125.50", uri.getQueryParameter("am"))
            assertEquals("INR", uri.getQueryParameter("cu"))
            assertEquals(request.note, uri.getQueryParameter("tn"))
            assertEquals(request.transactionUrl, uri.getQueryParameter("url"))
            assertEquals(setOf("pa", "pn", "mc", "tr", "am", "cu", "tn", "url"), uri.queryParameterNames)
        }
    }

    @Test fun bundledDecoderReadsMerchantQrImageAndPreservesThePaymentRequest() {
        val matrix = QRCodeWriter().encode(request.uri(), BarcodeFormat.QR_CODE, 400, 400)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) 0xff000000.toInt() else -1
        }
        val image = RGBLuminanceSource(matrix.width, matrix.height, pixels)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(image)))
        assertEquals(request, PaymentRequest.parse(decoded.text))
    }

    @Test fun invalidTypedRequestCannotProduceAnIntent() {
        assertTrue(runCatching { PaymentHandoffAdapter.intent(Action.PaymentHandoff(PaymentApp.GOOGLE_PAY, request.copy(amountPaise = -1))) }.isFailure)
        assertTrue(runCatching { PaymentHandoffAdapter.intent(Action.PaymentHandoff(PaymentApp.PHONEPE, request.copy(payeeVpa = "other@bank&am=1"))) }.isFailure)
    }

    @Test fun scannerResultRejectsAmountThatConflictsWithTheCommand() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as AivoraApplication
        val store = ViewModelStore()
        val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[AssistantViewModel::class.java]
        try {
            instrumentation.runOnMainSync {
                model.foreground(true)
                model.submit("scan QR and send 30rs using PhonePe")
            }
            withTimeout(5000) { model.paymentQrRequests.first() }
            instrumentation.runOnMainSync { model.stagePaymentQr(request.uri()) }
            val state = withTimeout(5000) {
                model.ui.first { it.conversation.any { line -> line.text.contains("differs from the amount you requested") } }
            }
            assertNull(state.authentication)
            assertNull(state.confirmation)
            instrumentation.runOnMainSync {
                model.submit("scan QR and send thirty rupees using PhonePe")
                model.cancel()
                model.stagePaymentQr(request.uri())
            }
            assertEquals("review payment ${request.uri()} using Google Pay", model.ui.value.voiceDraft)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    @Test fun amountCheckedByCommandParserReachesTheSelectedPaymentAppIntent() {
        val link = request.copy(amountPaise = 3000).uri()
        val parser = CommandParser()
        val plan = parser.parse("pay 30 rs from $link using PhonePe") as ParsedCommand.Plan
        val intent = PaymentHandoffAdapter.intent(plan.value.actions.single() as Action.PaymentHandoff)
        assertEquals(PaymentApp.PHONEPE.packageName, intent.`package`)
        assertEquals("30.00", intent.data?.getQueryParameter("am"))
        assertEquals(request.payeeVpa, intent.data?.getQueryParameter("pa"))
        assertTrue(parser.parse("pay 31 rs from $link using PhonePe") is ParsedCommand.Clarify)
    }
}
