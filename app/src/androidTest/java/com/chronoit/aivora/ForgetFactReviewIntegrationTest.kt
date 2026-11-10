package com.chronoit.aivora

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronoit.aivora.ui.AssistantViewModel
import com.chronoit.aivora.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Credential callbacks are injected; this does not verify system credentials or acoustic approval. */
@RunWith(AndroidJUnit4::class)
class ForgetFactReviewIntegrationTest {
    @Test fun deletionNeedsAuthenticationAndFreshExactTargetConfirmation(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>() as AivoraApplication
        val original = app.settings.settings.first()
        val subject = "deletion fixture ${UUID.randomUUID()}"
        val store = ViewModelStore()
        ActivityScenario.launch(MainActivity::class.java).use {
            val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[AssistantViewModel::class.java]
            var stage = "settings"
            try {
                app.settings.setPrivateMode(false); app.settings.setHandsFreeControls(false); app.settings.setSpokenReplies(false)
                withTimeout(10000) { model.settings.first { !it.privateMode && !it.handsFreeControls && !it.spokenReplies } }
                model.foreground(true); model.resumed(true)
                assertTrue(app.memory.remember("my $subject is blue"))
                model.submit("in 10 minutes forget my $subject")
                stage = "scheduled refusal"
                withTimeout(10000) { model.ui.first { state -> state.conversation.any { it.text.startsWith("Memory deletion needs a fresh immediate") } } }
                assertNull(model.ui.value.authentication)
                assertTrue(app.memory.answerPersonalFact(subject).endsWith("blue"))
                model.submit("forget my $subject")
                stage = "first authentication"
                var auth = withTimeout(10000) { model.ui.first { it.authentication != null } }.authentication!!
                assertTrue(app.memory.answerPersonalFact(subject).endsWith("blue"))
                model.authenticated(auth.token)
                stage = "first confirmation"
                withTimeout(10000) { model.ui.first { it.confirmation != null } }
                assertTrue(app.memory.answerPersonalFact(subject).endsWith("blue"))
                assertTrue(app.memory.remember("my $subject is green"))
                model.confirm()
                stage = "changed target refusal"
                withTimeout(10000) { model.ui.first { state -> state.conversation.any { it.text.startsWith("The saved personal facts changed") } } }
                assertTrue(app.memory.answerPersonalFact(subject).endsWith("green"))
                model.submit("forget my $subject")
                stage = "fresh authentication"
                auth = withTimeout(10000) { model.ui.first { it.authentication != null } }.authentication!!
                model.authenticated(auth.token)
                stage = "fresh confirmation"
                withTimeout(10000) { model.ui.first { it.confirmation != null } }
                model.confirm()
                stage = "deletion result"
                withTimeout(10000) { model.ui.first { state -> state.conversation.any { it.text.startsWith("Deleted the reviewed personal-fact") } } }
                assertTrue(app.memory.answerPersonalFact(subject).startsWith("I don't have"))
                withTimeout(10000) { model.ui.first { state -> state.memories.none { it.content.contains(subject) } } }
            } catch (failure: Throwable) {
                throw AssertionError("Deletion stage=$stage state=${model.ui.value.state} last=${model.ui.value.conversation.lastOrNull()?.text}", failure)
            } finally {
                model.cancel(); store.clear()
                app.memory.prepareForgetPersonalFact(subject)?.let { app.memory.forgetPersonalFact(it) }
                app.settings.setPrivateMode(original.privateMode)
                app.settings.setHandsFreeControls(original.handsFreeControls)
                app.settings.setSpokenReplies(original.spokenReplies)
            }
        }
    }
}
