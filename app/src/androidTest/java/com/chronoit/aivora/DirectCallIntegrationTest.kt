package com.chronoit.aivora

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import com.chronoit.aivora.ui.AssistantViewModel
import com.chronoit.aivora.ui.MainActivity
import kotlinx.coroutines.withTimeout
import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronoit.aivora.actions.*
import com.chronoit.aivora.platform.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Denial paths only: this ordinary suite never calls Telecom.placeCall. */
@RunWith(AndroidJUnit4::class)
class DirectCallIntegrationTest {
    private val action = Action.PlaceCall("+12025550123", VoiceCallTarget(1, 0, 10, "Test carrier"))
    @Test fun optOutRefusesBeforePermissionOrTelecomAccess() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>() as AivoraApplication
        val original = app.settings.settings.first().directCalling
        try {
            app.settings.setDirectCalling(false)
            assertEquals(ExecutionStatus.PERMISSION_MISSING, DirectCallAdapter(app).execute(action).status)
        } finally { app.settings.setDirectCalling(original) }
    }
    @Test fun deniedCallPermissionRefusesBeforeAnyTelecomRequest() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>() as AivoraApplication
        val original = app.settings.settings.first().directCalling
        val denied = object : ContextWrapper(app) {
            override fun checkSelfPermission(permission: String): Int = if (permission == Manifest.permission.CALL_PHONE) PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
        }
        try {
            app.settings.setDirectCalling(true)
            assertEquals(ExecutionStatus.PERMISSION_MISSING, DirectCallAdapter(denied).execute(action).status)
        } finally { app.settings.setDirectCalling(original) }
    }
    @Test fun directModeKeepsShortAndLocalNumbersAsUnexecutedDialerReviews() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>() as AivoraApplication
        val original = app.settings.settings.first().directCalling
        val store = ViewModelStore()
        ActivityScenario.launch(MainActivity::class.java).use {
            val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[AssistantViewModel::class.java]
            try {
                app.settings.setDirectCalling(true)
                withTimeout(10000) { model.settings.first { it.directCalling } }
                var previous: String? = null
                for (number in listOf("112", "2025550123")) {
                    model.submit("call $number")
                    val review = withTimeout(10000) { model.ui.first { state ->
                        state.confirmation?.token != previous && state.confirmation?.plan?.actions == listOf(Action.Dial(number))
                    } }.confirmation!!
                    previous = review.token
                    assertNull(model.ui.value.authentication)
                    assertEquals(RiskLevel.MEDIUM, review.plan.riskLevel)
                    // Deliberately never confirm: no dialer launch or emergency/carrier call is requested.
                }
            } finally { model.cancel(); store.clear(); app.settings.setDirectCalling(original) }
        }
    }

}
