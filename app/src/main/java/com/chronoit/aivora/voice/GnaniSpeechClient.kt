package com.chronoit.aivora.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SpeechServiceException(message: String, val retryable: Boolean = true) : IllegalStateException(message)

/** Gnani Prisma/Timbre transport. No audio, transcripts or credentials are written to logs. */
class GnaniSpeechClient(context: Context) : AutoCloseable {
    private val settings = GnaniSpeechSettings(context)
    private val microphone = MicrophoneCapture(context)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var call: Call? = null
    @Volatile private var track: AudioTrack? = null
    fun configured() = settings.configured()
    suspend fun listen(wakeOnly: Boolean = false, onTranscript: (String) -> Unit = {}, suppliedAudio: Flow<AudioFrame>? = null, beforeAudio: suspend () -> Unit = {}, onReady: () -> Unit = {}): String? = coroutineScope {
        val opened = CompletableDeferred<Unit>()
        val events = Channel<String>(32)
        val failure = CompletableDeferred<Nothing>()
        val request = Request.Builder().url("wss://api.vachana.ai/stt/v3/stream")
            .header("x-api-key-id", settings.key()).header("model", "prisma-v3.0")
            .header("language_code", settings.language()).header("format", if (wakeOnly) "transliterate" else "transcribe")
            .header("keyterms", "Aivora,Jarvis").header("x-sample-rate", "16000")
            .header("x-min-silence-ms", if (wakeOnly) "350" else "800")
            .header("x-vad-threshold", "0.5").header("x-max-speech-s", "20")
            .header("x-idle-timeout-sec", if (wakeOnly) "900" else "60").build()
        val active = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 65536) { failure.completeExceptionally(IllegalStateException("Speech response exceeded its limit.")); return }
                try {
                    val event = JSONObject(text)
                    when (event.optString("type")) {
                        "transcript" -> if (!events.trySend(event.optString("text")).isSuccess) failure.completeExceptionally(IllegalStateException("Speech responses arrived too quickly."))
                        "error" -> failure.completeExceptionally(SpeechServiceException("Gnani could not recognize speech. Check your plan, language and connection."))
                    }
                } catch (_: Exception) { failure.completeExceptionally(IllegalStateException("Gnani returned an unreadable speech response.")) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure.completeExceptionally(SpeechServiceException(if (response?.code == 401) "Gnani rejected the API key. Update it in Settings." else "Gnani speech connection failed. Check internet access and credits.", response?.code !in setOf(400, 401, 402, 403)))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.close() }
        })
        socket = active
        var sender: Job? = null
        val failureWatch = launch { failure.await() }
        try {
            withTimeout(12000) { opened.await() }
            Log.i("AivoraWake", "Gnani Prisma microphone starting; automatic multilingual=${settings.language() == "indic-auto"}, wake=$wakeOnly")
            beforeAudio()
            microphone.prepare()
            onReady()
            sender = launch(Dispatchers.IO) {
                do { (suppliedAudio ?: microphone.frames(if (wakeOnly) 60 else 20)).collect { frame ->
                    require(frame.sampleRate == 16000)
                    val pcm = ByteArray(frame.samples.size * 2)
                    frame.samples.forEachIndexed { index, value -> pcm[index * 2] = value.toByte(); pcm[index * 2 + 1] = (value.toInt() shr 8).toByte() }
                    check(active.queueSize() < 256_000 && active.send(pcm.toByteString())) { "Speech upload could not keep up. Check your connection." }
                    frame.samples.fill(0)
                } } while (wakeOnly && suppliedAudio == null && isActive)
                // Server VAD finishes the last utterance during a short silent tail.
                repeat(10) { active.send(ByteArray(3200).toByteString()); delay(100) }
            }
            withTimeoutOrNull(if (wakeOnly) 840000 else 25000) {
                var result: String? = null
                for (text in events) {
                    if (text.isBlank()) continue
                    if (wakeOnly) { if (wakeTextMatches(text, "Aivora")) { result = text; break } }
                    else { onTranscript(text); result = text; break }
                }
                result
            }
        } finally {
            microphone.stop()
            sender?.cancel()
            failureWatch.cancel()
            active.close(1000, "Listening finished")
            active.cancel()
            if (socket === active) socket = null
            events.close()
            withContext(NonCancellable) { sender?.join() }
        }
    }
    suspend fun speak(text: String, options: SpeechOptions) = withContext(Dispatchers.IO) {
        require(text.isNotBlank() && text.length <= 4000)
        val body = JSONObject().put("text", text).put("model", "timbre-v3.0").put("voice", "Devansh")
            .put("language", "auto").put("speed", options.speed.coerceIn(0.85f, 1.15f))
            .put("audio_config", JSONObject().put("sample_rate", 16000).put("num_channels", 1)
                .put("sample_width", 2).put("encoding", "linear_pcm").put("container", "raw"))
        val request = Request.Builder().url("https://api.vachana.ai/api/v1/tts/inference")
            .header("X-API-Key-ID", settings.key()).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val activeCall = http.newCall(request)
        call = activeCall
        val cancelOnCompletion = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { activeCall.cancel() } }
        try {
            activeCall.execute().use { response ->
                android.util.Log.i("AivoraWake", "Gnani voice response HTTP ${response.code}")
                if (!response.isSuccessful) throw SpeechServiceException("Gnani voice request failed. Check your API key, model access and credits.", response.code >= 500 || response.code == 429)
                check(response.header("Content-Type").orEmpty().let { it.startsWith("audio/") || it.startsWith("application/octet-stream") }) { "Gnani returned an unexpected audio format." }
                val output = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(32000, AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))).build()
                track = output
                try {
                    output.play()
                    var bytes = 0L
                    val framer = Pcm16Framer()
                    response.body!!.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            bytes += size
                            check(bytes <= 16_000_000) { "Gnani speech audio exceeded its limit." }
                            val aligned = framer.accept(buffer, size)
                            var offset = 0
                            while (offset < aligned.size) {
                                currentCoroutineContext().ensureActive()
                                val written = output.write(aligned, offset, aligned.size - offset)
                                check(written > 0) { "Phone audio write failed: $written" }
                                offset += written
                            }
                        }
                    }
                    framer.finish()
                    check(bytes > 0 && bytes % 2 == 0L) { "Gnani returned incomplete audio." }
                    withTimeout(5000) { while ((output.playbackHeadPosition.toLong() and 0xffffffffL) < bytes / 2) delay(10) }
                    Log.i("AivoraWake", "Gnani Timbre speech playback completed")
                } finally { if (track === output) track = null; output.release() }
            }
        } finally { cancelOnCompletion.cancel(); if (call === activeCall) call = null }
    }
    fun finishRecording() = microphone.stop()
    fun stop() { microphone.stop(); socket?.cancel(); call?.cancel(); runCatching { track?.pause(); track?.flush() } }
    override fun close() = stop()
    companion object {
        private val http = OkHttpClient.Builder().connectTimeout(10000, TimeUnit.MILLISECONDS).readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(15000, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false).build()
    }
}
