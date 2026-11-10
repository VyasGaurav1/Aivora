package com.chronoit.aivora.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded foreground capture. Audio stays in RAM and is discarded on completion/cancellation. */
class MicrophoneCapture(private val context: Context, private val echoCancelled: Boolean = false) {
    private val stopRequested = AtomicBoolean(false)
    fun prepare() { stopRequested.set(false) }
    fun stop() { stopRequested.set(true) }
    fun utteranceFrames(maximumSeconds: Int = 15): Flow<AudioFrame> {
        val endpoint = UtteranceEndpoint()
        return frames(maximumSeconds).takeWhile(endpoint::accept)
    }
    fun frames(maximumSeconds: Int = 15): Flow<AudioFrame> = flow {
        require(maximumSeconds in 1..60)
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone permission is missing" }
        if (stopRequested.get()) return@flow
        val lease = MicrophoneLease.acquire()
        try {
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Microphone format is unavailable" }
        val recorder = AudioRecord(if (echoCancelled) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 16000))
        val echo = if (echoCancelled && android.media.audiofx.AcousticEchoCanceler.isAvailable())
            runCatching { android.media.audiofx.AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not initialize" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            val buffer = ShortArray(1600)
            var total = 0
            val deadline = SystemClock.elapsedRealtime() + maximumSeconds * 1000L + 1000
            while (!stopRequested.get() && total < 16000 * maximumSeconds && SystemClock.elapsedRealtime() < deadline) {
                currentCoroutineContext().ensureActive()
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "Microphone read failed" }
                if (count > 0) { total += count; emit(AudioFrame(buffer.copyOf(count), 16000)) } else delay(10)
            }
        } finally {
            try { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
            finally { echo?.release(); recorder.release() }
        }
        } finally { lease.close() }
    }.flowOn(Dispatchers.IO).buffer(64)
}
