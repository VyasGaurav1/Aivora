package com.chronoit.aivora

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronoit.aivora.actions.*
import com.chronoit.aivora.connectivity.AndroidNetworkMonitor
import com.chronoit.aivora.platform.AndroidActionExecutor
import com.chronoit.aivora.scheduler.*
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import androidx.test.core.app.ActivityScenario

@RunWith(AndroidJUnit4::class)
class OnlineTaskIntegrationTest {
    @Test fun staleReviewTokenCannotStartMicrophoneCapture() {
        ActivityScenario.launch(com.chronoit.aivora.ui.MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = androidx.lifecycle.ViewModelProvider(activity)[com.chronoit.aivora.ui.AssistantViewModel::class.java]
                model.record("stale-fixture-token")
                assertFalse(model.voice.state.value.capturing)
                assertTrue(model.ui.value.conversation.last().text.contains("changed or expired"))
            }
        }
    }

    @Test fun youtubeHandoffOrOfflineRefusalMatchesActualNetworkAndNeverClaimsPlayback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scenario = ActivityScenario.launch(com.chronoit.aivora.ui.MainActivity::class.java)
        var launchedYouTube = false
        try {
        if (InstrumentationRegistry.getArguments().getString("expectedNetwork") == "offline") withTimeout(15_000) {
            while (AndroidNetworkMonitor(context).snapshot().validated) kotlinx.coroutines.delay(50)
        }
        val snapshot = AndroidNetworkMonitor(context).snapshot()
        launchedYouTube = snapshot.validated && context.packageManager.getLaunchIntentForPackage("com.google.android.youtube") != null
        val result = AndroidActionExecutor(context).execute(Action.YouTubeSearch("AIVORA test birds"))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("network_branch", if (snapshot.validated) "validated" else "offline") })
        if (!snapshot.validated) {
            assertEquals(ExecutionStatus.FAILED, result.status)
            assertEquals(snapshot.wifiEnabled, AndroidNetworkMonitor(context).snapshot().wifiEnabled)
        } else {
            assertEquals(ExecutionStatus.REQUIRES_USER_INTERACTION, result.status)
            assertTrue(result.message.contains("not been verified"))
            val installed = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube") != null
            if (installed) withTimeout(10_000) {
                while (true) {
                    val state = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys activity activities").use { descriptor ->
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
                    }
                    if (state.lineSequence().any { ("mResumedActivity" in it || "topResumedActivity" in it) && "com.google.android.youtube/" in it }) break
                    kotlinx.coroutines.delay(20)
                }
            }
        }
        } finally {
            if (launchedYouTube) InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am force-stop com.google.android.youtube").use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.close()
        }
    }
    @Test fun scheduledOnlineActionsPersistValidatedNetworkRequirement() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "online-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, TaskDatabase::class.java, name).build()
        val scheduler = AIVORAScheduler(context, database)
        var id: String? = null
        try {
            val task = scheduler.scheduleAction(Action.YouTubeSearch("birds"), "Online fixture", 3_600_000)
            id = task.id
            assertEquals(RequiredNetwork.VALIDATED, scheduler.list().single().conditions.network)
        } finally { id?.let { scheduler.cancel(it) }; database.close(); context.deleteDatabase(name) }
    }
}
