package com.chronoit.aivora.brain

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

private object NativeReasoningBridge {
    init { System.loadLibrary("aivora_reasoning") }
    external fun generate(path: String, prompt: ByteArray, limit: Int, threads: Int, id: Long): ByteArray
    external fun cancel(id: Long)
    external fun phase(): Int
}
class LlamaLocalReasoningEngine(private val context: Context) : LocalReasoningEngine {
    @Volatile internal var lastSummaryCandidate: String? = null
    internal fun nativePhase(): Int = NativeReasoningBridge.phase()
    companion object {
        private val ownership = Mutex()
        private val ids = AtomicLong()
        const val MODEL = "models/reasoning/qwen3-0.6b-q4_0.gguf"
        const val MODEL_BYTES = 428970080L
        const val SHA256 = "da2572f16c06133561ce56accaa822216f2391ef4d37fba427801cd6736417d4"
    }
    override suspend fun answer(question: String): ReasoningResult {
        if (!ReasoningQuestions.eligible(question)) return ReasoningResult.Unavailable("Use an explicit command or a short question.")
        return generate(ReasoningQuestions.prompt(question))
    }
    override suspend fun classifyRequest(text: String): String? = StarterCapabilities.knownRoute(text)
        ?: StarterCapabilities.label(generate(StarterCapabilities.routePrompt(text)))
    override suspend fun summarize(question: String, evidence: String): ReasoningResult {
        val result = generate(GroundedSummary.prompt(question, evidence))
        lastSummaryCandidate = (result as? ReasoningResult.Answer)?.text
        if (result is ReasoningResult.Answer) {
            val spoken = GroundedSummary.forSpeech(question, result.text)
            if (!GroundedSummary.supported(spoken, evidence)) return ReasoningResult.Unavailable("I couldn't produce a reliable summary of those sources.")
            return ReasoningResult.Answer(spoken)
        }
        return result
    }
    private suspend fun generate(prompt: String): ReasoningResult = ownership.withLock {
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        if (memory.lowMemory || memory.availMem < 768L * 1024 * 1024) return@withLock ReasoningResult.Unavailable("Not enough free memory for a local answer right now. Phone commands remain available.")
        try {
            val model = withContext(Dispatchers.IO) { prepareModel() }
            val id = ids.incrementAndGet()
            val bytes = coroutineScope {
                val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { NativeReasoningBridge.cancel(id) }
                }
                try { withContext(Dispatchers.IO) { NativeReasoningBridge.generate(model.absolutePath, prompt.toByteArray(Charsets.UTF_8), 192, Runtime.getRuntime().availableProcessors().coerceIn(1, 4), id) } }
                finally { withContext(NonCancellable) { watcher.cancelAndJoin() } }
            }
            currentCoroutineContext().ensureActive()
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.keys().asSequence().toSet() == setOf("reply"))
            val reply = json.get("reply") as? String ?: error("Invalid local reply")
            require(reply.isNotBlank() && reply.length <= 1800 && reply.none { it.isISOControl() && it !in "\n\t" })
            if (!ReasoningQuestions.safeAnswer(reply)) return@withLock ReasoningResult.Unavailable("A local explanation cannot verify phone actions. Use an explicit command or check the relevant action status.")
            ReasoningResult.Answer(reply)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: LinkageError) { ReasoningResult.Unavailable("The local reasoning runtime is unavailable. Phone commands remain available.") }
        catch (error: Exception) {
            val message = when (error.message) {
                "Local model loading timed out" -> "The local model took too long to load."
                "Local prompt processing timed out" -> "The local model took too long to process the question."
                "Local generation timed out" -> "The local model ran out of time."
                "Local answer reached the token limit" -> "The local model reached its answer length limit."
                else -> "I couldn't produce a complete local answer."
            }
            ReasoningResult.Unavailable("$message Try a shorter question. Phone commands remain available.")
        }
    }
    internal suspend fun prepareModel(): File {
        val directory = File(context.filesDir, "aivora_reasoning").apply { check(isDirectory || mkdirs()) }
        val target = File(directory, "qwen3-0.6b-q4_0.gguf")
        suspend fun verified(file: File): Boolean {
            if (!file.isFile || file.length() != MODEL_BYTES) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) { currentCoroutineContext().ensureActive(); val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == SHA256
        }
        if (verified(target)) return target
        val staged = context.getExternalFilesDir(null)?.let { File(it, MODEL) }
        if (staged != null && staged.exists()) check(verified(staged)) { "Local model checksum mismatch" }
        check(directory.usableSpace > MODEL_BYTES + 64L * 1024 * 1024)
        val temporary = File(directory, "qwen3-0.6b-q4_0.part")
        try {
            (if (staged?.isFile == true) staged.inputStream() else context.assets.open(MODEL)).use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) { currentCoroutineContext().ensureActive(); val size = input.read(buffer); if (size < 0) break; output.write(buffer, 0, size) }
            } }
            check(verified(temporary))
            java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            staged?.delete()
        } finally { temporary.delete() }
        return target
    }
}
