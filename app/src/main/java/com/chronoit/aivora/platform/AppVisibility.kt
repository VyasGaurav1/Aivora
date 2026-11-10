package com.chronoit.aivora.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppVisibility : Application.ActivityLifecycleCallbacks {
    private var started = 0
    private val mutable = MutableStateFlow(false)
    val foreground = mutable.asStateFlow()
    override fun onActivityStarted(activity: Activity) { started++; mutable.value = true }
    override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0); mutable.value = started > 0 }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
