package com.chronoit.aivora

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronoit.aivora.actions.*
import com.chronoit.aivora.automation.MobileDataAutomationAdapter
import com.chronoit.aivora.connectivity.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileDataIntegrationTest {
    @Test fun separateOptOutRefusesBeforeSimOrPanelAccess() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>() as AivoraApplication
        val original = app.settings.settings.first().mobileDataControls
        val before = Settings.Global.getString(app.contentResolver, "mobile_data")
        try {
            app.settings.setMobileDataControls(false)
            val result = MobileDataAutomationAdapter(app).execute(Action.SetMobileData(false, MobileDataTarget(1, 0, -1, "test")))
            assertEquals(ExecutionStatus.PERMISSION_MISSING, result.status)
            assertEquals(before, Settings.Global.getString(app.contentResolver, "mobile_data"))
        } finally { app.settings.setMobileDataControls(original) }
    }
    @Test fun simMonitorChecksPhonePermissionBeforeReadingSubscriptions() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
        val denied = object : ContextWrapper(app) {
            override fun checkSelfPermission(permission: String) = if (permission == Manifest.permission.READ_PHONE_STATE) PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
        }
        assertTrue(runCatching { AndroidMobileDataMonitor(denied).snapshot() }.exceptionOrNull() is SecurityException)
    }
}
