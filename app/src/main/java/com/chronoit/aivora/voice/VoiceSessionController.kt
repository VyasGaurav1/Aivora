package com.chronoit.aivora.voice



import android.app.KeyguardManager

import android.content.Context

import android.Manifest

import android.content.pm.PackageManager

import android.os.SystemClock

import com.chronoit.aivora.data.SettingsRepository

import com.chronoit.aivora.security.*

import kotlinx.coroutines.*

import kotlinx.coroutines.flow.*

import java.io.File

import java.util.concurrent.atomic.AtomicLong

import java.util.concurrent.atomic.AtomicBoolean



enum class EnrollmentKind { OWNER, WAKE }

data class VoiceSessionState(

    val capturing: Boolean = false,

    val inputReady: Boolean = false,

    val speaking: Boolean = false,

    val ownerEnrolled: Boolean = false,

    val activeWakeName: String? = null,

    val enrollmentKind: EnrollmentKind? = null,

    val samplesCollected: Int = 0,

    val foregroundWakeEnabled: Boolean = false,

    val message: String = "Voice features are stored encrypted on this phone.",

)



/** Owns microphone/speech jobs, bounded recordings and temporary enrollment audio. */

class VoiceSessionController(

    private val context: Context,

    private val settings: SettingsRepository,

    private val scope: CoroutineScope,

    private val onDraft: (String) -> Unit,

    private val onListening: (Boolean) -> Unit,

    private val beforeCapture: suspend () -> Unit,

    private val automaticWakeAllowed: () -> Boolean,

    private val beforeAutomaticCapture: suspend () -> Boolean,

    private val onVerifiedCommand: (VerifiedVoiceCommand) -> Unit,

    private val profileDirectory: File = File(context.noBackupFilesDir, "voice"),
    private val onSpeechInterrupted: () -> Unit = {},

) : AutoCloseable {

    private val mutable = MutableStateFlow(VoiceSessionState())

    val state = mutable.asStateFlow()

    private val microphone = MicrophoneCapture(context)

    private val tts = IndianEnglishSpeechEngine(context)

    private val gnani = GnaniSpeechClient(context)

    private val interruption = SpeechInterruption(context)
    private val interruptedSpeech = AtomicBoolean(false)
    fun consumeSpeechInterruption() = interruptedSpeech.getAndSet(false)
    private suspend fun speakText(text: String, options: SpeechOptions, localOnly: Boolean = false) {
        VoiceRuntime.speak {
            interruption.play(stop = { tts.stop(); gnani.stop() }, onWake = {
                interruptedSpeech.set(true)
                onSpeechInterrupted()
                message("You interrupted. Listening for your next request.")
            }) {
                if (!localOnly && gnani.configured()) gnani.speak(text, options) else tts.speak(text, options)
            }
        }
    }

    private val speaker by lazy { SherpaSpeakerVerifier(context.assets, VoiceProfileStore(profileDirectory, FieldCipher(KeystoreKeys::dataKey), "owner.v1")) }

    private val wake by lazy { PersonalizedWakeWordEngine(VoiceProfileStore(profileDirectory, FieldCipher(KeystoreKeys::dataKey), "wake.v1")) }

    @Volatile private var capture: Job? = null

    @Volatile private var speech: Job? = null

    private val samples = mutableListOf<AudioFrame>()

    private val enrollmentSession = AtomicLong(0)

    private val visible = AtomicBoolean(true)

    private var authorizationExpires = 0L

    private fun automaticWakeReady(): Boolean = visible.get() && automaticWakeAllowed() &&

        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&

        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && mutable.value.enrollmentKind == null

    private val foregroundWake = ForegroundWakeLoop(scope,

        canStart = { automaticWakeReady() && !mutable.value.capturing && !mutable.value.speaking &&

            capture?.isActive != true && speech?.isActive != true && MicrophoneLease.isAvailable() && mutable.value.ownerEnrolled && mutable.value.activeWakeName != null },

        canContinue = ::automaticWakeReady,

        listen = { wakeCapture(automatic = true) },

        onStopped = { mutable.update { if (it.foregroundWakeEnabled) it.copy(foregroundWakeEnabled = false, message = "Foreground wake listening is stopped.") else it } },

        onStarted = { mutable.update { it.copy(foregroundWakeEnabled = true, message = "Foreground wake is enabled. Listening pauses during other voice work and while AIVORA is not active.") } })



    fun refresh() { scope.launch(Dispatchers.IO) { safe {

        mutable.update { it.copy(ownerEnrolled = speaker.isEnrolled(), activeWakeName = wake.activeWakeName()) }

    } } }

    fun transcribe(prompt: String? = null, stillCurrent: () -> Boolean = { true }, onEmpty: () -> Unit = {}, onCommand: (String) -> Unit) {

        var command: String? = null

        val recording = startCapture(precondition = stillCurrent) {

            suspend fun presentPrompt() {

                if (!stillCurrent()) throw CancellationException("Conversation changed")

                val options = settings.settings.first()

                mutable.update { it.copy(speaking = true) }

                try {

                    val cue = if (prompt != null) "$prompt I'm listening." else "I'm listening."

                    val presented = ReviewSpeech.present(stillCurrent) { speakText(cue, SpeechOptions(options.speechVoice, options.speechSpeed)) }

                    if (!presented) throw CancellationException("Conversation changed")

                } finally { mutable.update { it.copy(speaking = false) } }

                message("Listening for your reply.")

            }

            command = if (gnani.configured()) {

                message("Connecting to speech recognition...")

                gnani.listen(onTranscript = onDraft, beforeAudio = { presentPrompt() }, onReady = { mutable.update { it.copy(inputReady = true) } })

            } else {

                presentPrompt()

                AndroidSpeechInput.listen(context, onPartial = onDraft, onReady = { mutable.update { it.copy(inputReady = true) } })

            }

            if (command == null) message("No speech was recognized.")

        }

        scope.launch(Dispatchers.Main.immediate) {

            recording.join()

            if (!recording.isCancelled && capture === recording) {

                command?.let(onCommand) ?: onEmpty()

            } else onEmpty()

        }

    }

    private suspend fun captureDraft() {

        SherpaSpeechToTextEngine(context.assets).use { engine -> engine.transcribe(microphone.utteranceFrames()).collect {

            if (it.final) {

                onDraft(it.text)

                message(if (it.text.isBlank()) "No speech was recognized." else "Review the transcription, then press Send.")

            }

        } }

    }

    fun verifiedCommand() = startCapture {

        if (!speaker.isEnrolled()) captureDraft() else captureVerifiedCommand()

    }

    fun verifiedReply(prompt: String, stillCurrent: () -> Boolean) = startCapture(precondition = stillCurrent) {

        if (!speaker.isEnrolled() || !settings.settings.first().handsFreeControls) message("Enroll the owner and enable hands-free controls before answering by voice.")

        else captureVerifiedCommand(prompt, stillCurrent)

    }

    private suspend fun captureVerifiedCommand(prompt: String = "Yes sir, what can I do for you?", stillCurrent: () -> Boolean = { true }) {

        val options = settings.settings.first()

        mutable.update { it.copy(speaking = true) }

        val presented = try { ReviewSpeech.present(stillCurrent) { speakText(prompt, SpeechOptions(options.speechVoice, options.speechSpeed)) } }

        finally { mutable.update { it.copy(speaking = false) } }

        if (!presented) { message("The review expired or changed during speech. Review the current request again."); return }

        message("Listening for your command.")

        microphone.prepare()

        val frames = microphone.utteranceFrames().toList()

        try {

            SherpaSpeechToTextEngine(context.assets).use { engine ->

                when (val result = VerifiedVoicePipeline(speaker, engine).interpret(frames)) {

                    is VoiceCommandResult.Rejected -> message(result.reason)

                    is VoiceCommandResult.Verified -> {

                        currentCoroutineContext().ensureActive()

                        if (settings.settings.first().handsFreeControls) onVerifiedCommand(result.command)

                        else { result.command.consume()?.let(onDraft); message("Owner verified. Review the transcription, then press Send, or enable hands-free controls in Settings.") }

                    }

                }

            }

        } finally { frames.forEach { it.samples.fill(0) } }

    }

    /** Only call after a successful system device-credential prompt. */

    fun beginEnrollment(kind: EnrollmentKind) {

        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { message("Unlock your device before enrolling."); return }

        stopForegroundWake()

        capture?.cancel()

        enrollmentSession.incrementAndGet()

        authorizationExpires = SystemClock.elapsedRealtime() + 300_000

        clearSamples()

        mutable.update { it.copy(enrollmentKind = kind, samplesCollected = 0, message = prompt(kind)) }

    }

    fun recordEnrollmentSample() {

        val kind = mutable.value.enrollmentKind ?: return

        val session = enrollmentSession.get()

        if (SystemClock.elapsedRealtime() >= authorizationExpires || context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {

            cancelEnrollment(); message("Enrollment authorization expired. Authenticate again."); return

        }

        startCapture {

            val frames = microphone.frames(if (kind == EnrollmentKind.WAKE) 3 else 6).toList()

            val audio = combine(frames)

            currentCoroutineContext().ensureActive()

            if (session != enrollmentSession.get() || SystemClock.elapsedRealtime() >= authorizationExpires) {

                audio.samples.fill(0); return@startCapture

            }

            if (kind == EnrollmentKind.OWNER && !VoiceQuality.acceptable(audio)) {

                audio.samples.fill(0); message("That recording was too short, silent or clipped. Please try again."); return@startCapture

            }

            val enrollment = synchronized(samples) { samples += audio; samples.toList() }

            mutable.update { it.copy(samplesCollected = enrollment.size, message = "Sample ${enrollment.size} of 3 recorded. ${prompt(kind)}") }

            if (enrollment.size == 3) {

                try {

                    val saved = if (kind == EnrollmentKind.OWNER) speaker.enroll(enrollment) else wake.enroll(settings.settings.first().identity.wakeName, enrollment)

                    message(if (saved) "${if (kind == EnrollmentKind.OWNER) "Owner voice" else "Wake name"} enrollment saved." else "Samples did not form a consistent profile. The previous profile was kept. Please enroll again.")

                } finally {

                    clearSamples(); authorizationExpires = 0

                    mutable.update { it.copy(enrollmentKind = null, samplesCollected = 0) }

                    refresh()

                }

            }

        }

    }

    fun verifyVoice() = startCapture {

        val audio = combine(microphone.frames(6).toList())

        try {

            val result = speaker.verify(listOf(audio))

            message(if (result.verified) "Voice matched the enrolled profile. This is not proof against replay attacks." else "Voice did not match confidently. No action was executed.")

        } finally { audio.samples.fill(0) }

    }

    fun forgetProfile(kind: EnrollmentKind) {

        stopAll()

        val previous = capture

        scope.launch(Dispatchers.IO) { safe {

            previous?.join()

            if (kind == EnrollmentKind.OWNER) speaker.forget() else wake.forget()

            message("${if (kind == EnrollmentKind.OWNER) "Owner voice" else "Wake name"} profile removed.")

            refresh()

        } }

    }

    fun listenForWake() {

        if (!mutable.value.ownerEnrolled || mutable.value.activeWakeName == null) { message("Enroll an owner voice and wake name first."); return }

        wakeCapture(automatic = false)

    }

    fun startForegroundWake() {

        if (!mutable.value.ownerEnrolled || mutable.value.activeWakeName == null) { message("Enroll an owner voice and wake name first."); return }

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { message("Allow microphone access before starting wake listening."); return }

        if (!automaticWakeAllowed()) { message("Enable hands-free controls and finish the current review before starting foreground wake listening."); return }

        foregroundWake.start()

    }

    fun stopForegroundWake() { foregroundWake.stop() }

    @Synchronized private fun wakeCapture(automatic: Boolean): Job {

        if (automatic && (!automaticWakeReady() || capture?.isActive == true || speech?.isActive == true)) return Job().apply { complete() }

        return startCapture(

        precondition = { !automatic || automaticWakeReady() }, prepareCommand = !automatic) {

            wake.close()

            message("Waiting for your enrolled wake name.")

            val matched = BackgroundWakeListener(wake, speaker).awaitOwner(microphone.frames(60))

            if (!matched) { if (!automatic) message("Wake listening ended. No verified wake activation was detected."); return@startCapture }

            if (automatic && (!automaticWakeReady() || !beforeAutomaticCapture())) return@startCapture

            currentCoroutineContext().ensureActive()

            captureVerifiedCommand()

        }

    }

    @Synchronized fun speak(text: String, localOnly: Boolean = false) {

        if (!visible.get()) return

        microphone.stop(); capture?.cancel()

        val previous = speech

        stopSpeaking()

        speech = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) { safe {

            previous?.join()

            if (!visible.get()) return@safe

            val options = settings.settings.first()

            mutable.update { it.copy(speaking = true) }

            try {

                speakText(text.take(4000), SpeechOptions(options.speechVoice, options.speechSpeed), localOnly)

                android.util.Log.i("AivoraWake", if (interruptedSpeech.get()) "Speech interrupted by wake word" else "Speech playback completed")

            }

            finally { mutable.update { it.copy(speaking = false) } }

        } }.also { it.start() }

    }

    fun stopSpeaking() { tts.stop(); gnani.stop(); speech?.cancel() }

    suspend fun awaitSpeech() { speech?.join() }

    fun foreground(value: Boolean) { visible.set(value); if (!value) { foregroundWake.pause(); interrupt() } }

    fun pauseForegroundWake() { foregroundWake.pause() }

    fun resumeForegroundWake() { foregroundWake.resume() }

    fun finishRecording() { microphone.stop(); gnani.finishRecording(); AndroidSpeechInput.finishRecording() }

    @Synchronized fun interrupt() { microphone.stop(); capture?.cancel(); stopSpeaking(); cancelEnrollment() }

    fun stopAll() { stopForegroundWake(); interrupt() }

    fun cancelEnrollment() {

        capture?.cancel()

        microphone.stop()

        enrollmentSession.incrementAndGet()

        authorizationExpires = 0

        clearSamples()

        mutable.update { it.copy(enrollmentKind = null, samplesCollected = 0) }

    }

    @Synchronized private fun startCapture(precondition: () -> Boolean = { true }, prepareCommand: Boolean = true, block: suspend () -> Unit): Job {

        val previous = capture

        previous?.cancel()

        stopSpeaking()

        val next = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {

            previous?.join()

            if (!precondition()) return@launch

            val ownership = VoiceRuntime.hold()

            try {

            safe {

                check(withTimeoutOrNull(2500) { while (!MicrophoneLease.isAvailable()) delay(20); true } == true) { "Wake microphone did not release" }

                VoiceRuntime.awaitQuiet()

                if (prepareCommand) beforeCapture()

                if (!precondition()) return@safe

                check(visible.get() && !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Voice input requires an unlocked foreground activity" }

                microphone.prepare()

                mutable.update { it.copy(capturing = true, inputReady = false) }; onListening(true)

                try { block() }

                finally { microphone.stop(); mutable.update { it.copy(capturing = false, inputReady = false) }; onListening(false) }

            }

            } finally { ownership.close() }

        }

        capture = next

        next.start()

        return next

    }

    private fun combine(frames: List<AudioFrame>): AudioFrame {

        require(frames.all { it.sampleRate == 16000 } && frames.sumOf { it.samples.size } <= 240000)

        val result = ShortArray(frames.sumOf { it.samples.size })

        var offset = 0

        frames.forEach { it.samples.copyInto(result, offset); offset += it.samples.size; it.samples.fill(0) }

        return AudioFrame(result, 16000)

    }

    private fun clearSamples() = synchronized(samples) { samples.forEach { it.samples.fill(0) }; samples.clear() }

    fun credentialUnavailable() = message("Set a secure PIN, password or pattern in Android settings before enrolling voice profiles.")

    private fun prompt(kind: EnrollmentKind) = if (kind == EnrollmentKind.WAKE) "Say only your chosen wake name clearly, then finish recording." else "Read a different full sentence for each sample, for example: I am setting up my personal assistant today."

    private fun message(value: String) { mutable.update { it.copy(message = value) } }

    private suspend fun safe(block: suspend () -> Unit) {

        try { block() }

        catch (cancelled: CancellationException) { throw cancelled }

        catch (error: SpeechServiceException) { stopForegroundWake(); message(error.message ?: "Speech service is unavailable.") }

        catch (_: MicrophoneInUseException) { message(if (mutable.value.foregroundWakeEnabled)

            "Another AIVORA voice session is using the microphone. Foreground wake will wait until it is free."

            else "Another AIVORA voice session is using the microphone. Wait until it finishes, then try again.") }

        catch (_: Exception) { stopForegroundWake(); message("Voice processing could not finish. Check access, recording quality and local storage, then try again.") }

    }

    override fun close() { stopAll(); tts.close(); gnani.close() }

}

