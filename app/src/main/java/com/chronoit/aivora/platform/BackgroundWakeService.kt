package com.chronoit.aivora.platform

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.chronoit.aivora.AivoraApplication
import com.chronoit.aivora.R
import com.chronoit.aivora.security.*
import com.chronoit.aivora.ui.MainActivity
import com.chronoit.aivora.ui.VoicePanelActivity
import com.chronoit.aivora.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class BackgroundWakeState(val running: Boolean = false, val message: String = "Background wake listening is stopped.")

/** Started from visible user interaction; never silently requests privileges or bypasses lock state. */
class BackgroundWakeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val microphone by lazy { MicrophoneCapture(this) }
    private val gnani by lazy { GnaniSpeechClient(this) }
    private var listenerJob: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (listenerJob?.isActive == true) return START_NOT_STICKY
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            mutable.value = BackgroundWakeState(false, "Microphone and notification access are required for background wake listening.")
            stopSelf(); return START_NOT_STICKY
        }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "AIVORA voice", NotificationManager.IMPORTANCE_LOW))
        try {
            startForeground(STATUS_ID, statusNotification("Starting wake listening."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: Exception) {
            mutable.value = BackgroundWakeState(false, "Android did not allow microphone foreground service startup. Open AIVORA and start it again.")
            stopSelf(); return START_NOT_STICKY
        }
        listenerJob = scope.launch { try {
                VoiceRuntime.busy.collectLatest { busy ->
                    microphone.stop()
                    if (busy) {
                        mutable.value = BackgroundWakeState(true, "Wake listening resumes after the current conversation or spoken reminder.")
                        notifications.notify(STATUS_ID, statusNotification("Voice active. Wake listening resumes automatically afterward."))
                    } else {
                        notifications.notify(STATUS_ID, statusNotification("Listening for Hey Aivora or Hey Jarvis. Keep the phone unlocked."))
                        var failures = 0
                        while (currentCoroutineContext().isActive) {
                            mutable.value = BackgroundWakeState(true, "Listening for Hey Aivora or Hey Jarvis.")
                            val text = try {
                                (if (gnani.configured()) gnani.listen(wakeOnly = true)
                                else AndroidSpeechInput.listen(this@BackgroundWakeService, wakeOnly = true)).also { failures = 0 }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                android.util.Log.w("AivoraWake", "System wake recognition retry: ${error.message}")
                                failures++
                                if (error is SpeechServiceException && !error.retryable) throw error
                                mutable.value = BackgroundWakeState(true, "Speech connection interrupted. Retrying automatically.")
                                notifications.notify(STATUS_ID, statusNotification("Speech connection interrupted. Retrying automatically."))
                                delay(failures.coerceAtMost(10) * 3000L)
                                null
                            }
                            val matched = text != null && wakeTextMatches(text, "Aivora")
                            if (matched) {
                                android.util.Log.i("AivoraWake", "Wake name detected")
                                val unlocked = !getSystemService(KeyguardManager::class.java).isKeyguardLocked
                                if (unlocked && AivoraVoiceInteractionService.requestVoiceSession()) {
                                    val opened = withTimeoutOrNull(8000) { VoiceRuntime.inConversation.first { it } }
                                    if (opened == true) return@collectLatest
                                }
                                val token = VoiceLaunchTickets.store.pendingOrIssue()
                                val launch = Intent(this@BackgroundWakeService, VoicePanelActivity::class.java)
                                    .setData(Uri.parse("aivora://voice/$token")).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                val pending = PendingIntent.getActivity(this@BackgroundWakeService, 2, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                                notifications.notify(ACTIVATION_ID, Notification.Builder(this@BackgroundWakeService, CHANNEL)
                                    .setSmallIcon(R.drawable.ic_aivora).setContentTitle("AIVORA heard you")
                                    .setContentText("Tap to give your command. Unlock your device if needed.").setContentIntent(pending).setAutoCancel(true).build())
                                delay(2000)
                            }
                            if (!matched) delay(400)
                        }
                    }
                }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            android.util.Log.e("AivoraWake", "Wake listener stopped: ${error.javaClass.simpleName}")
            mutable.value = BackgroundWakeState(false, "Wake listening stopped. Check microphone access and start listening again.")
            stopSelf()
        } }
        return START_NOT_STICKY
    }
    private fun statusNotification(text: String): Notification {
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BackgroundWakeService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_aivora).setContentTitle("AIVORA background wake")
            .setContentText(text).setContentIntent(launch).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop listening", stop).build()).build()
    }
    override fun onDestroy() {
        scope.cancel(); microphone.stop(); gnani.close(); stopForeground(STOP_FOREGROUND_REMOVE)
        if (mutable.value.running) mutable.value = BackgroundWakeState()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "aivora_voice"
        private const val STATUS_ID = 100
        private const val ACTIVATION_ID = 101
        private const val STOP = "com.chronoit.aivora.STOP_WAKE"
        private val mutable = MutableStateFlow(BackgroundWakeState())
        val state = mutable.asStateFlow()
        fun start(context: Context): Boolean = try {
            if (!mutable.value.running) {
                mutable.value = BackgroundWakeState(false, "Starting background wake listening.")
                context.startForegroundService(Intent(context, BackgroundWakeService::class.java))
            }
            true
        } catch (_: Exception) {
            mutable.value = BackgroundWakeState(false, "Android blocked service startup. Start background wake listening while AIVORA is visible."); false
        }
        fun stop(context: Context) { context.stopService(Intent(context, BackgroundWakeService::class.java)); mutable.value = BackgroundWakeState() }
    }
}
