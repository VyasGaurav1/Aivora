package com.chronoit.aivora.brain

import android.content.Context
import com.chronoit.aivora.connectivity.NetworkRoute
import com.chronoit.aivora.connectivity.NetworkSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext

/**
 * First-run download of the pinned offline reasoning model (Qwen3 0.6B, Q4_0).
 *
 * Rules, from SCREEN_FREE_NEXT.md §2 — none of these may be relaxed without a
 * design change:
 * - Never silent: a download starts only from an explicit user action — the
 *   exact voice phrase "yes, download the offline model" (or "download the
 *   offline model"), or the Download button in Settings. A first offline
 *   question only *offers* the download; it never starts one.
 * - Wi-Fi only: the gate refuses cellular, VPN and ethernet, and requires a
 *   validated connection. No metered-network download, no fallback to mobile
 *   data if Wi-Fi drops mid-download (the download fails and keeps its
 *   partial file so it can resume).
 * - Pinned source only: one URL, one revision, one file. The size and SHA-256
 *   are not duplicated here — they come from [LlamaLocalReasoningEngine],
 *   the same constants the build-time hash check and [prepareModel] use.
 *   The upstream filename is `Qwen3-0.6B-Q4_0.gguf` (capital Q); the
 *   lowercase `qwen3-0.6b-q4_0.gguf` is only AIVORA's local name and 404s
 *   on Hugging Face — verified against the repo on 2026-11-15.
 * - One verify path: the download streams to a `.download` file in the same
 *   private `aivora_reasoning/` directory [LlamaLocalReasoningEngine]
 *   uses, and installation goes through its `prepareModel()` — SHA-256
 *   verify plus atomic move. No second copy/verify path is written here.
 * - Never a web fallback: on any failure the question path keeps returning
 *   an honest unavailable reply. The model is never fetched from anywhere
 *   except the pinned URL, and never bundled into the starter APK.
 * - Partial files: an explicit cancel deletes the partial file. A failure
 *   (network drop, app killed) keeps it, so the next start resumes with an
 *   HTTP Range request. A checksum mismatch deletes it.
 * - Status is honest: "installed" here means the file exists with the exact
 *   pinned size. The full SHA-256 verification still runs inside
 *   `prepareModel()` before the model is first used.
 */
object ModelDownloadPolicy {
    const val REPOSITORY = "ggml-org/Qwen3-0.6B-GGUF"
    const val REVISION = "13ac06624948e4d8589cb4b6f90100837342fcb8"
    const val UPSTREAM_FILENAME = "Qwen3-0.6B-Q4_0.gguf"
    const val DOWNLOAD_FILENAME = "qwen3-0.6b-q4_0.download"
    const val DOWNLOAD_URL = "https://huggingface.co/$REPOSITORY/resolve/$REVISION/$UPSTREAM_FILENAME"
    val MODEL_BYTES: Long get() = LlamaLocalReasoningEngine.MODEL_BYTES
    val SHA256: String get() = LlamaLocalReasoningEngine.SHA256
    const val EXTRA_FREE_BYTES = 64L * 1024 * 1024
    val REQUIRED_FREE_BYTES: Long get() = MODEL_BYTES + EXTRA_FREE_BYTES

    const val NOT_INSTALLED_MESSAGE =
        "The offline answer model isn't installed yet. It's about 429 MB, downloaded once over Wi-Fi. " +
            "Say \"yes, download the offline model\" to start, or download it from Settings. Phone commands still work."

    /** Null means the download may start; otherwise the returned text is the spoken refusal. */
    fun canStart(snapshot: NetworkSnapshot, usableSpaceBytes: Long): String? = when {
        !snapshot.validated -> "I need a working internet connection to download the offline model."
        snapshot.route != NetworkRoute.WIFI -> "The offline model downloads over Wi-Fi only. Connect to Wi-Fi and ask again."
        usableSpaceBytes <= REQUIRED_FREE_BYTES ->
            "There isn't enough free space for the 429 MB offline model. Free some space and ask again."
        else -> null
    }

    enum class Command { DOWNLOAD, REMOVE, CANCEL }

    private val downloadPhrases = setOf(
        "download the offline model",
        "yes download the offline model",
        "download the offline answer model",
        "yes download the offline answer model",
        "install the offline model",
        "yes install the offline model",
    )
    private val removePhrases = setOf("remove the offline model", "delete the offline model")
    private val cancelPhrases = setOf("cancel the offline model download", "stop the offline model download")

    fun normalize(text: String): String = text.trim().lowercase(Locale.ROOT)
        .replace(",", "").replace(Regex("\\s+"), " ").trim().trimEnd('.', '?', '!')

    /** Exact phrases only. A bare "yes", "download it", or "cancel" never matches. */
    fun match(text: String): Command? {
        val normalized = normalize(text)
        return when (normalized) {
            in downloadPhrases -> Command.DOWNLOAD
            in removePhrases -> Command.REMOVE
            in cancelPhrases -> Command.CANCEL
            else -> null
        }
    }

    fun statusLabel(state: ModelDownloadState): String = when (state) {
        ModelDownloadState.NotInstalled -> "Offline model: not installed (about 429 MB, one-time Wi-Fi download)"
        is ModelDownloadState.Downloading -> "Offline model: downloading, ${state.percent}%"
        ModelDownloadState.Verifying -> "Offline model: verifying download"
        ModelDownloadState.Installed -> "Offline model: installed"
        is ModelDownloadState.Failed -> "Offline model: download failed — ${state.reason}"
    }
}

sealed interface ModelDownloadState {
    data object NotInstalled : ModelDownloadState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long = LlamaLocalReasoningEngine.MODEL_BYTES) : ModelDownloadState {
        val percent: Int get() = ((downloadedBytes * 100) / totalBytes.coerceAtLeast(1)).toInt().coerceIn(0, 100)
    }
    data object Verifying : ModelDownloadState
    data object Installed : ModelDownloadState
    data class Failed(val reason: String) : ModelDownloadState
}

class ModelDownloader(
    context: Context,
    private val engine: LlamaLocalReasoningEngine,
    private val networkSnapshot: () -> NetworkSnapshot,
) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, LlamaLocalReasoningEngine.DIRECTORY)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<ModelDownloadState>(
        if (engine.modelInstalled()) ModelDownloadState.Installed else ModelDownloadState.NotInstalled,
    )
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()
    @Volatile private var downloadJob: Job? = null

    /**
     * Starts a download after the Wi-Fi/storage gate. Returns the spoken
     * reply: a refusal, an already-installed/already-downloading note, or
     * the started confirmation. The download itself runs in this
     * downloader's own scope, so cancelling a voice task does not stop it.
     */
    suspend fun start(): String = withContext(Dispatchers.IO) {
        if (engine.modelInstalled()) {
            _state.value = ModelDownloadState.Installed
            return@withContext "The offline model is already installed."
        }
        if (_state.value is ModelDownloadState.Downloading || _state.value is ModelDownloadState.Verifying) {
            return@withContext "The offline model is already downloading."
        }
        val refusal = ModelDownloadPolicy.canStart(networkSnapshot(), directory.usableSpace)
        if (refusal != null) {
            _state.value = ModelDownloadState.Failed(refusal)
            return@withContext refusal
        }
        downloadJob = scope.launch { runDownload() }
        "Downloading the offline model, about 429 MB, over Wi-Fi. I'll tell you when it's ready. Phone commands still work."
    }

    /** Cancels a running download and deletes the partial file. */
    fun cancel() {
        downloadJob?.cancel()
    }

    /**
     * Removes the installed model and any partial download. Refuses while a
     * download is running. Returns the bytes freed, or -1 while downloading.
     */
    suspend fun remove(): Long = withContext(Dispatchers.IO) {
        if (_state.value is ModelDownloadState.Downloading || _state.value is ModelDownloadState.Verifying) return@withContext -1L
        val freed = engine.removeModel()
        _state.value = ModelDownloadState.NotInstalled
        freed
    }

    private suspend fun runDownload() {
        val downloadFile = File(directory, ModelDownloadPolicy.DOWNLOAD_FILENAME)
        try {
            directory.mkdirs()
            var startAt = when {
                !downloadFile.isFile -> 0L
                downloadFile.length() > ModelDownloadPolicy.MODEL_BYTES -> { downloadFile.delete(); 0L }
                else -> downloadFile.length()
            }
            val connection = URL(ModelDownloadPolicy.DOWNLOAD_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = true
                if (startAt > 0) connection.setRequestProperty("Range", "bytes=$startAt-")
                connection.connect()
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        // Server ignored the Range request: restart from zero.
                        if (startAt > 0) { downloadFile.delete(); startAt = 0 }
                        if (connection.contentLengthLong != ModelDownloadPolicy.MODEL_BYTES) {
                            _state.value = ModelDownloadState.Failed("The download source didn't return the expected 429 MB file.")
                            return
                        }
                    }
                    HttpURLConnection.HTTP_PARTIAL -> {
                        if (connection.contentLengthLong != ModelDownloadPolicy.MODEL_BYTES - startAt) {
                            _state.value = ModelDownloadState.Failed("The download source didn't return the expected file size.")
                            return
                        }
                    }
                    else -> {
                        _state.value = ModelDownloadState.Failed("The model download was refused by the server (HTTP ${connection.responseCode}).")
                        return
                    }
                }
                val digest = MessageDigest.getInstance("SHA-256")
                if (startAt > 0) {
                    // Re-hash the resumed prefix so the final digest covers the whole file.
                    downloadFile.inputStream().use { input ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            digest.update(buffer, 0, size)
                        }
                    }
                }
                _state.value = ModelDownloadState.Downloading(startAt)
                var downloaded = startAt
                connection.inputStream.use { input ->
                    FileOutputStream(downloadFile, startAt > 0).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            output.write(buffer, 0, size)
                            digest.update(buffer, 0, size)
                            downloaded += size
                            _state.value = ModelDownloadState.Downloading(downloaded)
                        }
                    }
                }
                if (downloaded != ModelDownloadPolicy.MODEL_BYTES) {
                    _state.value = ModelDownloadState.Failed("The download stopped early at ${downloaded / 1024 / 1024} MB of 429 MB. Your partial download is kept so it can resume.")
                    return
                }
                if (digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } != ModelDownloadPolicy.SHA256) {
                    downloadFile.delete()
                    _state.value = ModelDownloadState.Failed("The downloaded model failed its checksum check, so I deleted it. Try again on Wi-Fi.")
                    return
                }
                _state.value = ModelDownloadState.Verifying
                // Single install path: prepareModel() re-verifies the
                // downloaded file and atomically moves it into place.
                engine.prepareModel()
                _state.value = ModelDownloadState.Installed
            } finally {
                connection.disconnect()
            }
        } catch (cancelled: CancellationException) {
            downloadFile.delete()
            _state.value = ModelDownloadState.Failed("Download cancelled. The offline model isn't installed.")
            throw cancelled
        } catch (_: java.io.IOException) {
            // Partial file is kept so the next start resumes with Range.
            _state.value = ModelDownloadState.Failed("The download stopped — check Wi-Fi and try again. Your partial download is kept so it can resume.")
        } catch (error: Exception) {
            _state.value = ModelDownloadState.Failed("The offline model couldn't be installed: ${error.message ?: "unknown error"}.")
        }
    }
}
