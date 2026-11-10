package com.chronoit.aivora

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronoit.aivora.ui.AssistantViewModel
import com.chronoit.aivora.ui.MainActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceApprovalIntegrationTest {
    @Test fun capturePreparationPreservesPendingPlanAndReplacementInvalidatesIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>() as AivoraApplication
        ActivityScenario.launch(MainActivity::class.java).use {
            val store = ViewModelStore()
            val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(context))[AssistantViewModel::class.java]
            try {
                model.submit("brightness 30")
                val pending = withTimeout(10_000) { model.ui.first { it.confirmation != null } }.confirmation!!
                model.prepareVoiceCapture()
                assertEquals(pending.token, model.ui.value.confirmation!!.token)
                assertEquals(pending.plan, model.ui.value.confirmation!!.plan)
                assertFalse(model.voice.state.value.capturing)
                // An old Android prompt result must not clear or replace the current action review.
                model.authenticated("stale-authentication-token")
                model.authenticationFailed("stale-authentication-token")
                assertEquals(pending.token, model.ui.value.confirmation!!.token)
                assertNull(model.consumeVoiceAuthentication())
                model.submit("connect for this task")
                assertEquals(pending.token, model.ui.value.confirmation!!.token)
                model.submit("volume 101")
                withTimeout(10_000) { model.ui.first { state -> state.conversation.lastOrNull()?.text?.contains("0 to 100") == true } }
                assertNull(model.ui.value.confirmation)
                model.cancel()
            } finally { store.clear() }
        }
    }
}
