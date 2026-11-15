package com.chronoit.aivora.ui

import android.app.Application
import android.app.KeyguardManager
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chronoit.aivora.AivoraApplication
import com.chronoit.aivora.actions.*
import com.chronoit.aivora.brain.*
import com.chronoit.aivora.data.AssistantSettings
import com.chronoit.aivora.memory.Memory
import com.chronoit.aivora.memory.MemoryType
import com.chronoit.aivora.memory.MemorySource
import com.chronoit.aivora.platform.Capability
import com.chronoit.aivora.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import android.net.Uri
import com.chronoit.aivora.connectivity.*

enum class AssistantState { IDLE, LISTENING, UNDERSTANDING, SEARCHING, SUMMARIZING, ASKING, PLANNING, WAITING_FOR_CONFIRMATION, EXECUTING, SPEAKING, ERROR }

internal fun AssistantState.statusLabel(): String = when (this) {
    AssistantState.IDLE -> "Ready"
    AssistantState.LISTENING -> "Listening…"
    AssistantState.UNDERSTANDING -> "Understanding your request…"
    AssistantState.SEARCHING -> "Searching the web…"
    AssistantState.SUMMARIZING -> "Preparing your answer…"
    AssistantState.ASKING -> "Waiting for your answer"
    AssistantState.PLANNING -> "Planning…"
    AssistantState.WAITING_FOR_CONFIRMATION -> "Waiting for confirmation"
    AssistantState.EXECUTING -> "Working on your request…"
    AssistantState.SPEAKING -> "Speaking…"
    AssistantState.ERROR -> "Needs attention"
}
data class ConversationLine(val owner: Boolean, val text: String)
data class AssistantUiState(
    val state: AssistantState = AssistantState.IDLE,
    val conversation: List<ConversationLine> = listOf(ConversationLine(false, "Type a command or use Voice input to begin.")),
    val confirmation: Confirmation? = null,
    val authentication: Confirmation? = null,
    val memories: List<Memory> = emptyList(),
    val capabilities: List<Capability> = emptyList(),
    val discoveredApps: List<LaunchableApp> = emptyList(),
    val voiceDraft: String = "",
    val voiceDraftRevision: Long = 0,
    val webSuggestions: String? = null,
    val conversationRunning: Boolean = false,
    val contactChoices: List<ContactCandidate> = emptyList(),
    val documentChoices: List<LocalDocument> = emptyList(),
    val contactChoiceToken: String? = null,
    val documentChoiceToken: String? = null,
    val documents: List<LocalDocument> = emptyList(),
    val reminders: List<com.chronoit.aivora.scheduler.Reminder> = emptyList(),
    val preciseReminders: Boolean = false,
    val reminderStatus: String = "Reminders use approximate Android scheduling.",
    val networkPromptToken: String? = null,
    val fileStatus: String = "Add local files or a folder to allow offline file search.",
)

class AssistantViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AivoraApplication
    private val gnaniSettings by lazy { GnaniSpeechSettings(app) }
    fun gnaniConfigured() = gnaniSettings.configured()
    fun gnaniLanguage() = gnaniSettings.language()
    fun setGnaniLanguage(code: String) = gnaniSettings.setLanguage(code)
    fun saveGnaniKey(key: String) { viewModelScope.launch(Dispatchers.IO) {
        val saved = runCatching { gnaniSettings.saveKey(key) }.getOrDefault(false)
        appendReply(if (saved) "Speech settings saved." else "The Gnani key could not be saved.")
    } }
    private val parser = CommandParser()
    fun webSearchConfigured(): Boolean = app.webAnswers.configured()
    fun saveWebSearchKey(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = runCatching { app.webAnswers.saveKey(key) }.getOrDefault(false)
            appendReply(if (!saved) "The search key could not be saved." else if (key.isBlank()) "Online search key removed." else "Online search key saved.")
        }
    }
    private val dialogue = DialogueCoordinator(parser, app.contacts, app.documents, vocabulary = com.chronoit.aivora.memory.MemoryVocabulary(app.memory))
    private val visible = AtomicBoolean(true)
    private val resumed = AtomicBoolean(false)
    private val connectivity = ConnectivityOrchestrator()
    private val networkMonitor = AndroidNetworkMonitor(app)
    private var onlineRegistry: ActionRegistry? = null
    private var connectionRegistry: ActionRegistry? = null
    private var connectionTaskToken: String? = null
    private var registry = app.foregroundRegistry(visible::get)
    private val voiceConfirmation = VoiceConfirmationGate()
    private val voiceChoice = VoiceChoiceGate()
    private val dueTaskChoices = com.chronoit.aivora.scheduler.DueTaskChoices()
    private val voiceNetwork = VoiceNetworkGate()
    private var choicePage = 0
    private var choicePageToken: String? = null
    private val authenticationLaunch = AuthenticationLaunchGate()
    private var activeCommandSource = CommandSource.FOREGROUND_OWNER
    private var scheduleDueAt: Long? = null
    private var scheduleConditions = com.chronoit.aivora.scheduler.TaskConditions()
    private var scheduleRepeatMillis = 0L
    private val mutable = MutableStateFlow(AssistantUiState())
    val ui = mutable.asStateFlow()
    val settings = app.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AssistantSettings())
    val audit = app.database.auditDao().observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    @Volatile private var task: Job? = null
    private val reasoningFollowUp = ReasoningFollowUp()
    @Volatile private var activeSmsSend = false
    private var apps: List<LaunchableApp> = emptyList()
    val voice = VoiceSessionController(app, app.settings, viewModelScope,
        onDraft = { text -> mutable.update { it.copy(voiceDraft = text, voiceDraftRevision = it.voiceDraftRevision + 1) } },
        onListening = { listening -> mutable.update { it.copy(state = if (listening) AssistantState.LISTENING else if (it.state == AssistantState.LISTENING) AssistantState.IDLE else it.state) } },
        beforeCapture = { prepareVoiceCapture() },
        automaticWakeAllowed = { automaticWakeAllowed() },
        beforeAutomaticCapture = {
            if (!automaticWakeAllowed()) false else {
                voiceConfirmation.arm(mutable.value.confirmation)
                voiceChoice.arm(pendingChoiceRequests())
                voiceNetwork.arm(currentNetworkReview())
                mutable.update { it.copy(voiceDraft = "") }
                true
            }
        },
        onVerifiedCommand = ::acceptVoiceCommand,
        onSpeechInterrupted = { task?.cancel() })
    internal fun acceptVoiceCommand(command: VerifiedVoiceCommand) {
        if (settings.value.handsFreeControls) command.consume()?.let { submitCommand(it, CommandSource.VERIFIED_VOICE) }
    }
    private fun automaticWakeAllowed() = visible.get() && resumed.get() && settings.value.handsFreeControls &&
        task?.isActive != true && mutable.value.authentication == null
    fun record(reviewedToken: String? = null, contactToken: String? = null, contactPage: Int = 0, assistantShortcut: Boolean = false, publicWake: Boolean = false) {
        val state = mutable.value
        val contactReview = pendingChoiceRequests(state).singleOrNull {
            it.kind == ChoiceKind.CONTACT && it.token == contactToken && choiceIsCurrent(it)
        }
        if (contactToken != null && contactReview == null) {
            reply("The contact choices changed. Review the current choices before replying.")
            return
        }
        val connectionReview = currentNetworkReview()?.takeIf { it.token == reviewedToken }
        if (reviewedToken != null && connectionReview == null) {
            reply("The online task changed or expired. Review the current task before recording a new reply.")
            return
        }
        val contactReadback = contactReview?.let {
            ChoiceReadback.create(ChoiceKind.CONTACT, state.contactChoices.map(::spokenContact), contactPage)
        }
        val reviewPrompt = contactReadback?.prompt ?: connectionReview?.let { ActionReadback.create(it.plan, connection = true) }
        if ((connectionReview != null || contactReview != null) && reviewPrompt == null) {
            reply("Use the on-screen review. The details are too long to read aloud.")
            return
        }
        task?.cancel()
        val prompt = reviewPrompt ?: when {
            publicWake -> "Yes sir, what can I do for you?"
            assistantShortcut -> "Say your command."
            else -> null
        }
        voice.transcribe(prompt, stillCurrent = {
            (connectionReview == null && contactReview == null && !assistantShortcut && !publicWake) || (
                visible.get() && resumed.get() &&
                    !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
                    (connectionReview == null || currentNetworkReview() == connectionReview) &&
                    (contactReview == null || choiceIsCurrent(contactReview))
                )
        }) { text ->
            if (resumed.get()) {
                if (publicWake) {
                    if (parser.parse(text, apps) == ParsedCommand.Cancel) cancel()
                    else acceptPublicWakeCommand(text)
                    return@transcribe
                }
                if (contactReview != null && !choiceIsCurrent(contactReview)) return@transcribe
                if (contactReview != null && contactReadback != null && parser.parse(text, apps) != ParsedCommand.Cancel) {
                    val choice = ChoiceReply.parse(text)
                    if (choice?.pageDelta != null) {
                        record(contactToken = contactReview.token, contactPage = contactReadback.page + choice.pageDelta)
                    } else if (choice?.index == null || choice.kind?.let { it != ChoiceKind.CONTACT } == true) {
                        reply("Say a contact choice such as the second contact, or say Cancel.")
                    } else {
                        chooseContact(choice.index, contactReview.token)
                    }
                    return@transcribe
                }
                when (parser.parse(text, apps)) {
                    ParsedCommand.ConfirmAction ->
                        reply("Use the on-screen review to approve this request.")
                    ParsedCommand.ConnectPendingTask -> {
                        if (connectionReview == null || currentNetworkReview() != connectionReview) {
                            reply("The online task changed or expired. Review the current task before recording a new reply.")
                        } else {
                            submitCommand(text, CommandSource.FOREGROUND_OWNER, connectionReview.token)
                        }
                    }
                    ParsedCommand.Cancel -> cancel()
                    else -> if (connectionReview == null) submit(text) else reply("Say Connect for this task or Cancel.")
                }
            }
        }
    }
    fun finishRecording() = voice.finishRecording()
    private var voiceConversationJob: Job? = null
    private val starterVoiceDialogue = StarterVoiceDialogue()
    private var conversationRequest: String? = null
    private var pendingWebClarification: String? = null
    private var currentTurn: com.chronoit.aivora.memory.ConversationMemory.Turn? = null
    private fun localConversationCommand(text: String): Boolean {
        val normalized = ConversationRouting.normalize(text)
        val route = ConversationRouting.local(text)
        if (route == null && normalized !in setOf("speak slower", "speak faster", "forget conversation history")) return false
        conversationRequest = null
        task = viewModelScope.launch(Dispatchers.IO) { safely {
            when {
                route == ConversationRouting.Local.REPEAT -> reply(currentTurn?.answer ?: app.conversationMemory.repeat() ?: "I don't have a reply to repeat yet.", localSpeech = true)
                route == ConversationRouting.Local.HISTORY -> reply(app.conversationMemory.recent(), localSpeech = true)
                route == ConversationRouting.Local.MEMORY -> {
                    val query = ConversationRouting.memoryQuery(text)
                    val saved = app.memory.recall(query).filter { it.type == MemoryType.SEMANTIC && it.source in setOf(MemorySource.EXPLICIT_OWNER_STATEMENT, MemorySource.OWNER_CORRECTION) }.take(5)
                    reply(if (saved.isEmpty()) app.conversationMemory.find(query) ?: "I haven't saved a matching fact yet. Tell me remember that, followed by a fact." else "You told me: " + saved.joinToString(". ") { it.content }, localSpeech = true)
                }
                route in setOf(ConversationRouting.Local.EXPLAIN, ConversationRouting.Local.SIMPLIFY, ConversationRouting.Local.SHORTEN, ConversationRouting.Local.EXAMPLE) -> {
                    val previous = currentTurn ?: app.conversationMemory.last()
                    if (previous == null) reply("Which answer would you like me to explain?", AssistantState.ASKING, localSpeech = true)
                    else {
                        mutable.update { it.copy(state = AssistantState.UNDERSTANDING) }
                        val result = if (route == ConversationRouting.Local.SHORTEN) ReasoningResult.Answer(previous.answer.split(Regex("(?<=[.!?])\\s+")).first())
                            else app.reasoning.summarize("${previous.request.take(220)}. $text. Use only this previous reply, do not invent missing details.", previous.answer)
                        val answer = (result as? ReasoningResult.Answer)?.text ?: "I can't explain that further reliably from the current reply. Please ask a more specific question."
                        currentTurn = previous.copy(answer = answer)
                        app.conversationMemory.record(previous.request, answer)
                        reply(answer, localSpeech = true)
                    }
                }
                route == ConversationRouting.Local.CORRECT -> {
                    val previous = currentTurn ?: app.conversationMemory.last()
                    val correction = ConversationRouting.correction(text)
                    if (previous == null) reply("Which answer are you correcting?", AssistantState.ASKING, localSpeech = true)
                    else {
                        val saved = app.conversationMemory.correct(correction, previous.request)
                        if (saved) { currentTurn = previous.copy(answer = correction); app.conversationMemory.record(previous.request, correction) }
                        reply(if (saved) "I've saved your correction locally. I'll use it when you ask that question again. It is your correction, not a verified web fact." else "Memory is paused, so I haven't saved that correction.", localSpeech = true)
                    }
                }
                route == ConversationRouting.Local.REJECT || route == ConversationRouting.Local.APPROVE -> {
                    val saved = app.memory.remember("Feedback on the last response: $normalized", MemoryType.CORRECTION, MemorySource.OWNER_CORRECTION)
                    reply(if (!saved) "Memory is paused. I haven't saved that feedback." else if (route == ConversationRouting.Local.APPROVE) "Thanks. Your feedback is saved on this phone." else "Tell me the correct answer, starting with: the correct answer is.", AssistantState.ASKING, localSpeech = true)
                }
                normalized == "forget conversation history" -> { app.conversationMemory.clear(); currentTurn = null; reply("Your saved conversation history is cleared.", localSpeech = true) }
                else -> {
                    val current = settings.first()
                    val speed = (current.speechSpeed + if (normalized == "speak slower") -0.1f else 0.1f).coerceIn(0.85f, 1.15f)
                    app.settings.setSpeechOptions(speed, current.speechVoice)
                    reply("I'll ${if (normalized == "speak slower") "speak more slowly" else "speak faster"}. This preference is saved on your phone.", localSpeech = true)
                }
            }
        } }
        return true
    }
    fun startVoiceConversation() {
        val previous = voiceConversationJob
        previous?.cancel()
        voiceConversationJob = viewModelScope.launch {
            previous?.join()
            starterVoiceDialogue.clear()
            conversationRequest = null
            app.settings.setSpokenReplies(true)
            settings.first { it.spokenReplies }
            mutable.update { it.copy(conversationRunning = true) }
            val conversationOwnership = VoiceRuntime.hold(conversation = true)
            try {
                var firstTurn = true
                while (isActive && visible.get() && resumed.get() &&
                    !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                    val result = CompletableDeferred<String?>()
                    voice.transcribe(
                        prompt = if (voice.consumeSpeechInterruption()) "Go ahead." else if (firstTurn) "What can I do for you?" else if (!starterVoiceDialogue.awaitingInput && mutable.value.state == AssistantState.IDLE) "Anything else?" else null,
                        stillCurrent = { visible.get() && resumed.get() &&
                            !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked },
                        onEmpty = { result.complete(null) },
                    ) { result.complete(it) }
                    val text = withTimeoutOrNull(90000) { result.await() }
                    if (text == null) { voice.interrupt(); break }
                    firstTurn = false
                    if (text.trim().trimEnd('.', '!', '?').lowercase(java.util.Locale.ROOT) in
                        setOf("stop", "cancel", "goodbye", "go to sleep", "that's all", "that is all", "no", "no thanks", "no thank you", "nothing", "nothing else", "not now", "not right now", "that's it", "that is it")) {
                        voice.speak("Goodbye.")
                        voice.awaitSpeech()
                        break
                    }
                    acceptPublicWakeCommand(text)
                    task?.join()
                    voice.awaitSpeech()
                }
            } finally {
                conversationOwnership.close()
                starterVoiceDialogue.clear()
                mutable.update { it.copy(conversationRunning = false,
                    state = if (it.confirmation == null && it.authentication == null) AssistantState.IDLE else it.state) }
            }
        }
    }
    private fun acceptPublicWakeCommand(spokenText: String) {
        if (localConversationCommand(spokenText)) return
        conversationRequest = spokenText.take(1600).takeUnless { wakeTextMatches(spokenText, "Aivora") }
        if (StarterCapabilities.knownRoute(spokenText) == "UNSUPPORTED") {
            reply("I can't do that currently. I can help with reminders, alarms, remembering things and web questions.")
            return
        }
        if (StarterCapabilities.isQuestion(spokenText)) {
            starterVoiceDialogue.clear()
            reply(StarterCapabilities.description)
            return
        }
        if (wakeTextMatches(spokenText, "Aivora")) {
            starterVoiceDialogue.clear()
            reply("What can I do for you?", AssistantState.ASKING)
            return
        }
        val pendingTopic = pendingWebClarification
        pendingWebClarification = null
        val input = if (pendingTopic != null && parser.parse(spokenText, apps) is ParsedCommand.Clarify && !ConversationRouting.explicitSearch(spokenText)) "$pendingTopic $spokenText" else spokenText
        if (input != spokenText) conversationRequest = input
        val text = when (val turn = starterVoiceDialogue.next(input)) {
            is StarterVoiceDialogue.Turn.Ask -> { reply(turn.question, AssistantState.ASKING); return }
            is StarterVoiceDialogue.Turn.Command -> turn.text
        }
        val greeting = text.trim().trimEnd('.', '!', '?').lowercase(java.util.Locale.ROOT)
        if (greeting in setOf("hello", "hi", "thank you", "thanks")) {
            reply(if (greeting in setOf("thank you", "thanks")) "You're welcome." else "Hello. What can I do for you?")
            return
        }
        val parsed = parser.parse(text, apps)
        when {
            parsed is ParsedCommand.Remember || parsed is ParsedCommand.Reminder || parsed is ParsedCommand.Recall ||
                parsed is ParsedCommand.PersonalFactQuestion || parsed == ParsedCommand.ListReminders ||
                parsed is ParsedCommand.Plan && parsed.value.actions.singleOrNull() is Action.Alarm -> submit(text)
            parsed is ParsedCommand.Clarify -> {
                val request = webQuestion(text)
                if (request != null) {
                    task = viewModelScope.launch(Dispatchers.IO) {
                        val correction = if (ConversationRouting.explicitSearch(text)) null else app.conversationMemory.correctedAnswer(text)
                        if (correction != null) reply("You corrected this earlier: $correction", localSpeech = true)
                        else answerWebQuestion(request.first, request.second)
                    }
                } else routeUnrecognizedRequest(text)
            }
            else -> routeUnrecognizedRequest(text)
        }
    }
    private fun routeUnrecognizedRequest(text: String) {
        task = viewModelScope.launch(Dispatchers.IO) {
            mutable.update { it.copy(state = AssistantState.UNDERSTANDING) }
            val acknowledgement = launch {
                delay(1800)
                voice.speak("Let me understand that.")
                voice.awaitSpeech()
            }
            val label = try { app.reasoning.classifyRequest(text) }
                finally { acknowledgement.cancelAndJoin(); voice.awaitSpeech() }
            when (label) {
                "CAPABILITIES" -> reply(StarterCapabilities.description)
                "QUESTION" -> answerWebQuestion(text, false)
                "ALARM" -> { starterVoiceDialogue.next("set alarm"); reply("What time should I set the alarm for?", AssistantState.ASKING) }
                "REMINDER" -> { starterVoiceDialogue.next("set a reminder"); reply("What should I remind you about, and when?", AssistantState.ASKING) }
                "MEMORY" -> { starterVoiceDialogue.next("remember"); reply("What would you like me to remember?", AssistantState.ASKING) }
                null -> reply("I couldn't understand that request. Could you say it another way?", AssistantState.ASKING)
                else -> reply("I can't do that currently. I can help with reminders, alarms, remembering things and web questions.")
            }
        }
    }
    fun openReminderTimingSettings() {
        if (android.os.Build.VERSION.SDK_INT >= 31) app.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun testWakeGreeting() {
        voice.speak("What can I do for you?")
    }
    fun microphonePermissionDenied() {
        reply("Microphone access is off. Allow it in Android app permissions, then try voice input again.")
    }
    fun foreground(value: Boolean) {
        visible.set(value)
        if (!value) voiceConversationJob?.cancel()
        voice.foreground(value)
    }
    internal suspend fun prepareVoiceCapture() {
        val before = mutable.value
        val wasAwaitingReply = before.confirmation != null || before.contactChoices.isNotEmpty() || before.documentChoices.isNotEmpty() || dueTaskChoices.current() != null || before.networkPromptToken != null
        if (wasAwaitingReply) task?.join() else task?.cancelAndJoin()
        val pending = mutable.value.confirmation
        voiceConfirmation.arm(pending)
        voiceChoice.arm(pendingChoiceRequests())
        voiceNetwork.arm(currentNetworkReview())
        if (pending == null && !wasAwaitingReply) { registry.cancel(); mutable.update { it.copy(authentication = null) } }
        mutable.update { it.copy(voiceDraft = "") }
    }
    fun resumed(value: Boolean) {
        resumed.set(value)
        if (!value) voice.pauseForegroundWake()
        else voice.resumeForegroundWake()
        if (value) resumeOnlineTask()
    }
    init {
        viewModelScope.launch { networkMonitor.changes().collect { if (it.validated && resumed.get()) resumeOnlineTask() } }
        viewModelScope.launch(Dispatchers.IO) {
            apps = app.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { LaunchableApp(it.loadLabel(app.packageManager).toString(), it.activityInfo.packageName) }.distinctBy { it.packageName }
            mutable.update { it.copy(discoveredApps = apps.sortedBy { app -> app.label.lowercase() }) }
        }
        refresh()
    }
    fun refresh() {
        voice.refresh()
        mutable.update { it.copy(capabilities = app.capabilities.snapshot(), preciseReminders = app.scheduler.preciseTimingAllowed()) }
        viewModelScope.launch(Dispatchers.IO) { safely { refreshMemory(); refreshDocuments() } }
        refreshReminders()
    }
    fun submit(text: String) = submitCommand(text, CommandSource.FOREGROUND_OWNER)
    private val qrScanRequests = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    val paymentQrRequests = qrScanRequests.receiveAsFlow()
    private var spokenQrPayment: Pair<String, String>? = null

    fun paymentQrUnavailable() {
        spokenQrPayment = null
        reply("The QR scanner is unavailable. You can enter a merchant UPI link instead.")
    }

    fun stagePaymentQr(payload: String?) {
        val spoken = spokenQrPayment
        spokenQrPayment = null
        if (payload == null) {
            reply("The QR scan ended without a code. Start a new scan to try again.")
            return
        }
        val request = PaymentRequest.parse(payload)
        if (request == null && PaymentRequest.parse(payload, 1) == null) {
            appendReply("This QR code does not contain a supported merchant UPI link.")
            return
        }
        val draft = if (spoken != null) "pay ${spoken.first} rupees from $payload using ${spoken.second}"
            else if (request != null) "review payment $payload using Google Pay"
            else "pay AMOUNT rupees from $payload using Google Pay"
        if (draft.length > 4000 || payload.any(Char::isWhitespace)) {
            appendReply("This QR code cannot be used in a payment command.")
            return
        }
        if (spoken != null) {
            submit(draft)
            return
        }
        mutable.update { it.copy(voiceDraft = draft, voiceDraftRevision = it.voiceDraftRevision + 1) }
        appendReply("Check the payment command. Replace AMOUNT if shown, choose Google Pay or PhonePe, then tap Send to review.")
    }
    private fun submitCommand(text: String, source: CommandSource, capturedConnectionToken: String? = null) {
        if (text.isBlank()) return
        if (!visible.get() || app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        if (localConversationCommand(text)) return
        if (conversationRequest == null) conversationRequest = text.take(1600)
        val qrCommand = Regex("scan (?:the )?qr and (?:send|pay) ([a-z0-9. -]{1,150}?)(?: rupees?| *rs)(?: using (google pay|gpay|phonepe|phonepay))?", RegexOption.IGNORE_CASE)
            .matchEntire(text.trim().trimEnd('.', '?', '!'))
        if (qrCommand != null) {
            val amount = qrCommand.groupValues[1]
            val value = if (amount.matches(Regex("[0-9]{1,10}(?:[.][0-9]{1,2})?"))) amount.toBigDecimal()
                else EnglishNumber.parse(amount)?.toBigDecimal()
            if (value == null || value.signum() <= 0) {
                reply("Give an exact positive amount in rupees.")
                return
            }
            cancel()
            spokenQrPayment = amount to qrCommand.groupValues[2].ifEmpty { "Google Pay" }
            qrScanRequests.trySend(Unit)
            return
        }
        val parsedAtEntry = parser.parse(text, apps)
        if (parsedAtEntry == ParsedCommand.Cancel) { cancel(); return }
        if (parsedAtEntry == ParsedCommand.ConnectivityStatus) {
            val status = connectivity.status(networkMonitor.snapshot())
            appendReply(status)
            if (settings.value.spokenReplies && task?.isActive != true) voice.speak(status)
            return
        }
        val connectionToken = if (parsedAtEntry == ParsedCommand.ConnectPendingTask) {
            val review = currentNetworkReview()
            when (source) {
                CommandSource.VERIFIED_VOICE -> voiceNetwork.consume(source, review)
                CommandSource.FOREGROUND_OWNER -> review?.token?.takeIf {
                    capturedConnectionToken == null || it == capturedConnectionToken
                }
                CommandSource.UNVERIFIED_VOICE -> null
            }
        } else null
        if (parsedAtEntry == ParsedCommand.ConnectPendingTask && connectionToken == null) {
            appendReply("No matching connection approval was accepted. Review the current online task and capture a fresh reply, or repeat the complete task if it expired.")
            return
        }
        if (parsedAtEntry != ParsedCommand.ConnectPendingTask && parsedAtEntry != ParsedCommand.ConfirmAction && ChoiceReply.parse(text) == null) {
            // Invalidate before joining older work, so a delayed voice reply cannot revive the superseded task.
            dueTaskChoices.clear(); voiceChoice.clear()
            voiceNetwork.clear(); connectivity.cancel(); mutable.update { it.copy(networkPromptToken = null) }
        }
        val previous = task
        previous?.cancel()
        voice.interrupt()
        task = viewModelScope.launch(Dispatchers.IO) {
            previous?.join()
            safely {
                currentCoroutineContext().ensureActive()
                val parsedInput = parsedAtEntry
                if (parsedInput == ParsedCommand.ConnectPendingTask) {
                    voiceNetwork.clear()
                    handleNetworkDecision(connectivity.approve(connectionToken!!))
                    return@safely
                }
                if (parsedInput == ParsedCommand.ConfirmAction) {
                    val token = if (source == CommandSource.VERIFIED_VOICE) voiceConfirmation.consume(source, mutable.value.confirmation) else mutable.value.confirmation?.token
                    if (token == null) {
                        mutable.update { it.copy(conversation = (it.conversation + ConversationLine(false, "No matching approval was accepted. Review the current action and capture a fresh confirmation.")).takeLast(60)) }
                        return@safely
                    }
                    val reviewedPlan = mutable.value.confirmation?.plan
                    mutable.update { it.copy(confirmation = null, state = AssistantState.EXECUTING) }
                    confirmReviewed(token, reviewedPlan)
                    return@safely
                }
                val choiceReply = ChoiceReply.parse(text)
                val currentChoices = pendingChoiceRequests()
                if (choiceReply != null && (source == CommandSource.VERIFIED_VOICE || currentChoices.isNotEmpty())) {
                    val request = if (source == CommandSource.FOREGROUND_OWNER) currentChoices.singleOrNull()?.takeIf {
                        (choiceReply.kind == null || choiceReply.kind == it.kind) && (choiceReply.index == null || choiceReply.index in 0 until it.count)
                    } else voiceChoice.consume(source, choiceReply, currentChoices)
                    if (request == null) {
                        appendReply("No matching choice was accepted. Read the current choices and capture a fresh reply.")
                        return@safely
                    }
                    if (choiceReply.pageDelta != null) { readChoices(choiceReply.pageDelta); return@safely }
                    if (request.kind == ChoiceKind.TASK) {
                        val selected = dueTaskChoices.consume(request, choiceReply.index!!)
                        voiceChoice.clear(); voiceConfirmation.clear(); authenticationLaunch.clear()
                        registry.cancel(); registry = app.foregroundRegistry(visible::get)
                        connectivity.cancel(); onlineRegistry = null
                        mutable.update { it.copy(networkPromptToken = null) }
                        if (selected == null) reply("That task choice expired. Request fresh due-action choices.", AssistantState.ASKING)
                        else reviewScheduled(selected.id, source, selected.dueAt)
                        return@safely
                    }
                    voiceChoice.clear(); voiceConfirmation.clear(); authenticationLaunch.clear()
                    activeCommandSource = source
                    registry.cancel(); registry = app.foregroundRegistry(visible::get)
                    connectivity.cancel(); onlineRegistry = null
                    mutable.update { it.copy(contactChoices = emptyList(), documentChoices = emptyList(), networkPromptToken = null,
                        conversation = (it.conversation + ConversationLine(true, text.take(4000))).takeLast(60)) }
                    val turn = if (request.kind == ChoiceKind.CONTACT) dialogue.chooseContact(choiceReply.index!!, request.token)
                        else dialogue.chooseDocument(choiceReply.index!!, request.token)
                    handleDialogue(turn, source)
                    return@safely
                }
                voiceConfirmation.clear()
                voiceChoice.clear()
                dueTaskChoices.clear()
                voiceNetwork.clear()
                authenticationLaunch.clear()
                connectionRegistry = null; connectionTaskToken = null
                activeCommandSource = source
                registry.cancel()
                registry = app.foregroundRegistry(visible::get)
                scheduleDueAt = null
                scheduleConditions = com.chronoit.aivora.scheduler.TaskConditions()
                scheduleRepeatMillis = 0L
                connectivity.cancel(); onlineRegistry = null; mutable.update { it.copy(networkPromptToken = null) }
                if (StarterCapabilities.isQuestion(text)) { reply(StarterCapabilities.description); return@safely }
                if (!ConversationRouting.explicitSearch(text) && (parsedAtEntry is ParsedCommand.Clarify || parsedAtEntry is ParsedCommand.PersonalFactQuestion)) {
                    val correction = app.conversationMemory.correctedAnswer(text)
                    if (correction != null) { reply("You corrected this earlier: $correction", localSpeech = true); return@safely }
                }
                // Offline-first routing: ordinary questions go to live web search only
                // when the user opted in with a Brave key. Otherwise they go to the
                // on-device local reasoning path below (or an honest unavailable
                // reply when the local model is not installed). No key is required
                // for core use, and no question is sent to the web by default.
                val search = webQuestion(text, allowOrdinary = parsedAtEntry is ParsedCommand.Clarify && webSearchConfigured())
                if (search != null) {
                    answerWebQuestion(search.first, search.second)
                    return@safely
                }
                mutable.update { it.copy(state = AssistantState.UNDERSTANDING, confirmation = null, contactChoices = emptyList(), documentChoices = emptyList(),
                    authentication = null, conversation = (it.conversation + ConversationLine(true, text.take(4000))).takeLast(60)) }
                val explicit = parser.parse(text, apps)
                val learned = if (explicit is ParsedCommand.Clarify) app.memory.byType(MemoryType.ROUTINE)
                    .mapNotNull { it.structuredData?.let(LearnedPhrase::decode) }
                    .firstOrNull { LearnedPhrase.key(it.phrase) == LearnedPhrase.key(text) } else null
                val followUpInstruction = if (explicit is ParsedCommand.Clarify && learned == null && settings.value.localReasoning) reasoningFollowUp.instruction(text) else null
                val followUp = followUpInstruction?.let(reasoningFollowUp::resolve)
                reasoningFollowUp.clear()
                if (learned != null) {
                    val plan = learned.plan(parser, apps)
                    if (plan == null) reply("That taught phrase can no longer be resolved. Teach it again with explicit commands.", AssistantState.ASKING)
                    else { dialogue.clear(); beginOnlinePlan(plan, source) }
                } else if (followUpInstruction != null && followUp == null) {
                    reply("I don't have a recent offline topic for that follow-up. Ask the full question again.", AssistantState.ASKING)
                } else if (explicit is ParsedCommand.Clarify && settings.value.localReasoning && (followUp != null || ReasoningQuestions.eligible(text))) {
                    dialogue.clear()
                    mutable.update { it.copy(state = AssistantState.PLANNING) }
                    when (val answer = app.reasoning.answer(followUp?.first ?: text)) {
                        is ReasoningResult.Answer -> {
                            currentCoroutineContext().ensureActive()
                            reasoningFollowUp.remember(followUp?.second ?: text)
                            reply("Unverified offline answer; no phone action performed. ${answer.text}")
                        }
                        is ReasoningResult.Unavailable -> reply(answer.reason, AssistantState.ASKING)
                    }
                } else handleDialogue(dialogue.process(text, apps), source)
            }
        }
    }
    private fun webQuestion(text: String, allowOrdinary: Boolean = true): Pair<String?, Boolean>? =
        WebQuestionRouting.request(text, app.webAnswers.lastQuestion, allowOrdinary)
    private suspend fun answerWebQuestion(question: String?, suggestions: Boolean) {
        dialogue.clear()
        mutable.update { it.copy(state = AssistantState.UNDERSTANDING, confirmation = null, authentication = null,
            contactChoices = emptyList(), documentChoices = emptyList()) }
        val clarification = question?.let(WebQuestionRouting::clarification)
        if (clarification != null) { pendingWebClarification = question; reply(clarification, AssistantState.ASKING); return }
        val answer = when {
            question.isNullOrBlank() || question.length > 1600 || question.any(Char::isISOControl) ->
                "Ask a short web question or say show suggestions for a topic."
            !webSearchConfigured() -> "Online search is optional. Add a Brave Search API key in AIVORA Settings to use it — short questions work offline without a key."
            !networkMonitor.snapshot().validated -> "Online search needs internet. Connect to the internet and ask again."
            else -> {
                mutable.update { it.copy(state = AssistantState.SEARCHING) }
                val result = coroutineScope {
                    val acknowledgement = launch {
                        delay(1200)
                        voice.speak("I'll look that up for you.")
                        voice.awaitSpeech()
                        delay(10000)
                        voice.speak(if (mutable.value.state == AssistantState.SUMMARIZING) "I've found sources. I'm preparing a short answer." else "I'm still searching. Please give me a moment.")
                        voice.awaitSpeech()
                    }
                    try { runCatching { app.webAnswers.answer(question, suggestions) {
                        mutable.update { state -> state.copy(state = AssistantState.SUMMARIZING) }
                    } } }
                    finally { acknowledgement.cancelAndJoin(); voice.awaitSpeech() }
                }
                currentCoroutineContext().ensureActive()
                val response = result.getOrNull()
                if (response == null) (result.exceptionOrNull() as? WebAnswerException)?.message ?: "I couldn't reach web search. Check your internet connection and try again."
                else {
                    app.webAnswers.lastQuestion = question
                    response
                }
            }
        }
        currentCoroutineContext().ensureActive()
        mutable.update { it.copy(state = AssistantState.IDLE, webSuggestions = answer.takeIf { suggestions }) }
        reply(answer)
    }
    fun dismissWebSuggestions() {
        mutable.update { it.copy(webSuggestions = null) }
    }
    private suspend fun handleDialogue(turn: DialogueTurn, source: CommandSource) {
        when (turn) {
            is DialogueTurn.Answer -> {
                val wasScheduling = scheduleDueAt != null
                scheduleDueAt = null
                if (wasScheduling) dialogue.clear()
                reply(turn.text + if (wasScheduling) " Resolve the reference, then repeat the complete scheduled request. No action was scheduled or run." else "")
            }
            is DialogueTurn.Contacts -> {
                reply(turn.question, AssistantState.ASKING); mutable.update { it.copy(contactChoices = turn.choices, contactChoiceToken = turn.token) }
                if (source == CommandSource.VERIFIED_VOICE && settings.value.handsFreeControls) readChoices()
            }
            is DialogueTurn.Documents -> {
                reply(turn.question, AssistantState.ASKING); mutable.update { it.copy(documentChoices = turn.choices, documentChoiceToken = turn.token) }
                if (source == CommandSource.VERIFIED_VOICE && settings.value.handsFreeControls) readChoices()
            }
            is DialogueTurn.Command -> when (val command = turn.command) {
                    is ParsedCommand.MobileData -> {
                        if (!settings.value.mobileDataControls) { scheduleDueAt = null; reply("Enable supported mobile-data controls in Settings first.", AssistantState.ASKING) }
                        else {
                            val state = try { AndroidMobileDataMonitor(app).snapshot() } catch (_: SecurityException) { null }
                            if (state == null || !MobileDataGuard.matches(state.target, state, command.enabled)) {
                                scheduleDueAt = null
                                reply("Allow SIM verification in Capabilities. Mobile-data controls need one unchanged default data SIM and do not enable data while roaming.", AssistantState.ASKING)
                            } else handleDialogue(DialogueTurn.Command(ParsedCommand.Plan(ActionPlan(listOf(Action.SetMobileData(command.enabled, state.target)), 1.0, "Explicit mobile-data request for verified data SIM"))), source)
                        }
                    }
                    ParsedCommand.ConfirmAction -> reply("There is no pending action to confirm.", AssistantState.ASKING)
                    ParsedCommand.ConnectivityStatus -> Unit
                    ParsedCommand.ConnectPendingTask -> reply("Review the current online task and capture a fresh connection reply.", AssistantState.ASKING)
                    is ParsedCommand.Conditioned -> {
                        val parsed = parser.parse(command.command, apps)
                        if (parsed is ParsedCommand.Schedule || parsed is ParsedCommand.Reminder) {
                            scheduleConditions = command.conditions
                            handleDialogue(DialogueTurn.Command(parsed), source)
                        } else reply("Include a reminder interval or a scheduled action time after the condition.", AssistantState.ASKING)
                    }
                    is ParsedCommand.Schedule -> {
                        if (scheduleDueAt != null) reply("Nested schedules are not supported.", AssistantState.ASKING)
                        else {
                            val parsed = parser.parse(command.command, apps)
                            if (parsed is ParsedCommand.ForgetPersonalFact) {
                                scheduleConditions = com.chronoit.aivora.scheduler.TaskConditions()
                                reply("Memory deletion needs a fresh immediate request and cannot be scheduled.", AssistantState.ASKING)
                            } else if (parsed !is ParsedCommand.Plan && parsed !is ParsedCommand.CallPerson && parsed !is ParsedCommand.MessagePerson && parsed !is ParsedCommand.FileReference && parsed !is ParsedCommand.MobileData)
                                reply("Schedule an explicit supported device, contact or file action.", AssistantState.ASKING)
                            else {
                                scheduleDueAt = command.dueAtMillis ?: Math.addExact(System.currentTimeMillis(), command.delayMillis)
                                scheduleRepeatMillis = command.repeatMillis
                                handleDialogue(if (parsed is ParsedCommand.Plan) DialogueTurn.Command(parsed) else dialogue.process(command.command, apps), source)
                            }
                        }
                    }
                    is ParsedCommand.Reminder -> {
                        val reminderText = if (command.text == null || command.text.lowercase(java.util.Locale.ROOT) in setOf("that", "this", "it", "this thing")) app.memory.latestRememberedText() ?: currentTurn?.answer else command.text
                        if (reminderText == null) {
                            reply("What should I remind you about? Say the delay and what to remember.", AssistantState.ASKING)
                        } else {
                            val reminder = app.scheduler.create(reminderText, command.delayMillis, command.repeatMillis, scheduleConditions)
                            refreshReminders()
                            val due = java.time.Instant.ofEpochMilli(reminder.dueAt).atZone(java.time.ZoneId.systemDefault())
                            val whenText = due.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM 'at' h:mm a", java.util.Locale.ENGLISH))
                            reply("Reminder saved for $whenText: $reminderText.", localSpeech = true)
                        }
                        scheduleConditions = com.chronoit.aivora.scheduler.TaskConditions()
                    }
                    ParsedCommand.ListReminders -> {
                        val reminders = app.scheduler.list()
                        reply(if (reminders.isEmpty()) "No active reminders." else reminders.joinToString("\n") { "${it.text} · ${java.util.Date(it.dueAt)} · ${it.state}" }, localSpeech = true)
                    }
                    ParsedCommand.SmsStatus -> reply(app.smsJournal.latestDescription())
                    ParsedCommand.YouTubeStatus -> reply(com.chronoit.aivora.automation.YouTubeAutomationAdapter(app).playbackStatus())
                    is ParsedCommand.ListReplyNotifications -> {
                        val titles = com.chronoit.aivora.platform.AivoraNotificationListener.listReplyTitles(command.app)
                        when {
                            titles == null -> reply("Unlock the phone and check notification access to list replies.", AssistantState.ASKING)
                            titles.isEmpty() -> reply("No active ${command.app.displayName} notifications are available for replies.")
                            else -> reply("Recent ${command.app.displayName} conversation titles available for replies: ${titles.joinToString(". ")}. Say reply to ${command.app.displayName} notification from the title, then say your message after saying.")
                        }
                    }
                    is ParsedCommand.NotificationReply -> {
                        val action = com.chronoit.aivora.platform.AivoraNotificationListener.bindReply(command.app, command.title, command.body)
                        if (action == null) reply("I need one active ${command.app.displayName} message notification with that title and a reply action. Check notification access and the notification title.", AssistantState.ASKING)
                        else beginOnlinePlan(ActionPlan(listOf(action), 1.0, "Live notification reply"), source)
                    }
                    ParsedCommand.PermissionStatus -> {
                        val capabilities = app.capabilities.snapshot()
                        mutable.update { it.copy(capabilities = capabilities) }
                        val missing = capabilities.filter { it.status == com.chronoit.aivora.platform.CapabilityStatus.MISSING_PERMISSION }
                        val unsupported = capabilities.filter { it.status == com.chronoit.aivora.platform.CapabilityStatus.UNSUPPORTED }
                        val privileged = capabilities.filter { it.status == com.chronoit.aivora.platform.CapabilityStatus.SYSTEM_PRIVILEGE_REQUIRED }
                        reply(buildList {
                            if (missing.isEmpty()) add("The capability scan found no missing permissions.")
                            else {
                                add("These features need Android access: ${missing.joinToString(", ") { it.name }}.")
                                add("Say assistant role settings, open app permissions, open brightness permission, app control settings, or notification access settings to find the relevant controls.")
                            }
                            val setup = capabilities.filter { it.status == com.chronoit.aivora.platform.CapabilityStatus.SETUP_REQUIRED }
                            if (setup.isNotEmpty()) add("These features need setup: ${setup.joinToString(", ") { it.name }}.")
                            if (setup.any { it.name == "Device credential" }) {
                                add("Say open security settings to review your screen lock.")
                            }
                            if (unsupported.isNotEmpty()) add("Currently unavailable: ${unsupported.joinToString(", ") { it.name }}.")
                            if (privileged.isNotEmpty()) add("These features require system privileges: ${privileged.joinToString(", ") { it.name }}.")
                        }.joinToString(" "))
                    }
                    is ParsedCommand.ReviewScheduledAction -> {
                        val tasks = app.scheduler.list()
                        val selected = com.chronoit.aivora.scheduler.DueActionSelector.select(tasks, System.currentTimeMillis(), command.id, command.next)
                        when (selected) {
                            is com.chronoit.aivora.scheduler.DueActionSelection.Selected -> reviewScheduled(selected.task.id, source, selected.task.dueAt)
                            is com.chronoit.aivora.scheduler.DueActionSelection.Multiple -> {
                                reply("Several actions are due. Choose a numbered task to review.", AssistantState.ASKING)
                                dueTaskChoices.present(tasks, System.currentTimeMillis())
                                readChoices()
                            }
                            com.chronoit.aivora.scheduler.DueActionSelection.None -> reply("No matching due action is ready for review. Reminders and future or handled actions are not executed by this command.", AssistantState.ASKING)
                        }
                    }
                    is ParsedCommand.CancelReminder -> { app.scheduler.cancel(command.id); refreshReminders(); reply("Reminder canceled if it was active.") }
                    is ParsedCommand.Plan -> {
                        val plan = prepareDirectSms(prepareDirectCalls(command.value) ?: return)
                        val dueAt = scheduleDueAt
                        scheduleDueAt = null
                        if (dueAt != null) {
                            require(plan.actions.size == 1)
                            val action = plan.actions.single()
                            app.scheduler.scheduleAction(action, action.describe(), dueAt - System.currentTimeMillis(), scheduleConditions, scheduleRepeatMillis)
                            scheduleRepeatMillis = 0L
                            scheduleConditions = com.chronoit.aivora.scheduler.TaskConditions()
                            refreshReminders()
                            reply("Action scheduled for review. When due, open Reminders to review it; authentication and confirmation will be requested as needed. It has not run.")
                        } else beginOnlinePlan(plan, source)
                    }
                    is ParsedCommand.Clarify -> reply(command.question, AssistantState.ASKING)
                    is ParsedCommand.Rename -> { app.settings.rename(command.name); reply("Name saved. Enroll the new wake name in Voice. The previous wake profile stays active until enrollment succeeds.") }
                    is ParsedCommand.PrivateMode -> {
                        app.settings.setPrivateMode(command.enabled)
                        mutable.update { it.copy(conversation = emptyList()) }
                        reply(if (command.enabled) "Private mode is on. I won't create long-term memories." else "Private mode is off.")
                    }
                    is ParsedCommand.Remember -> {
                        val saved = app.memory.remember(command.content)
                        reply(if (saved) "I've saved that in memory." else "Private mode is on, so I didn't save this.")
                        refreshMemory()
                    }
                    is ParsedCommand.TeachPhrase -> {
                        val phrase = LearnedPhrase.create(command.phrase, command.definition)
                        require(parser.parse(phrase.phrase, apps) is ParsedCommand.Clarify) { "Choose a phrase that does not replace a built-in command." }
                        if (phrase.plan(parser, apps) == null) reply("Teach explicit supported commands, separated by 'then'. Resolve contacts and files directly for now.", AssistantState.ASKING)
                        else {
                            val previous = app.memory.byType(MemoryType.ROUTINE).firstOrNull {
                                it.structuredData?.let(LearnedPhrase::decode)?.let { saved -> LearnedPhrase.key(saved.phrase) == LearnedPhrase.key(phrase.phrase) } == true
                            }
                            val saved = app.memory.remember("When I say ${phrase.phrase}: ${phrase.commands.joinToString(" then ")}", MemoryType.ROUTINE,
                                if (previous == null) MemorySource.EXPLICIT_OWNER_STATEMENT else MemorySource.OWNER_CORRECTION,
                                replaces = previous?.id, structuredData = phrase.encode())
                            reply(if (saved) "Phrase saved locally. Its actions will be checked each time you use it." else "Private mode is on, so I didn't save this phrase.")
                            refreshMemory()
                        }
                    }
                    is ParsedCommand.Recall -> {
                        val memories = app.memory.recall(command.query).filter { it.type != MemoryType.EPISODIC }
                        reply(if (memories.isEmpty()) "I don't have a saved memory about that." else memories.joinToString("\n") { it.content }, localSpeech = true)
                    }
                    is ParsedCommand.PersonalFactQuestion -> reply(app.memory.answerPersonalFact(command.subject), localSpeech = true)
                    is ParsedCommand.ForgetPersonalFact -> {
                        if (scheduleDueAt != null) {
                            scheduleDueAt = null; scheduleRepeatMillis = 0L; scheduleConditions = com.chronoit.aivora.scheduler.TaskConditions()
                            reply("Memory deletion needs a fresh immediate request and cannot be scheduled.", AssistantState.ASKING)
                        } else {
                            val deletion = app.memory.prepareForgetPersonalFact(command.subject)
                            if (deletion == null) reply("No matching saved personal-fact records were found.", AssistantState.ASKING)
                            else beginOnlinePlan(ActionPlan(listOf(deletion), 1.0, "Explicit owner request to forget one personal-fact subject"), source)
                        }
                    }
                    ParsedCommand.InspectMemory -> { refreshMemory(); reply("Open Memory to inspect or delete saved memories.") }
                    ParsedCommand.Cancel -> { voice.stopAll(); reply("Cancelled.") }
                    is ParsedCommand.CallPerson, is ParsedCommand.MessagePerson, is ParsedCommand.FileReference,
                    is ParsedCommand.ProjectContext, is ParsedCommand.TeachFileAlias -> reply("Please clarify the contact or file reference.", AssistantState.ASKING)
                }
        }
    }
    fun chooseContact(index: Int, token: String) = choose { handleDialogue(dialogue.chooseContact(index, token), CommandSource.FOREGROUND_OWNER) }
    fun chooseDocument(index: Int, token: String) = choose { handleDialogue(dialogue.chooseDocument(index, token), CommandSource.FOREGROUND_OWNER) }
    fun actOnFile(id: String, operation: FileOperation) = choose { scheduleDueAt = null; handleDialogue(dialogue.useDocument(id, operation), CommandSource.FOREGROUND_OWNER) }
    private fun choose(keepOnlineTask: Boolean = false, block: suspend () -> Unit) {
        dueTaskChoices.clear()
        val previous = task
        previous?.cancel(); voice.interrupt()
        task = viewModelScope.launch(Dispatchers.IO) { safely {
            previous?.join()
            if (!keepOnlineTask) { connectivity.cancel(); onlineRegistry = null; mutable.update { it.copy(networkPromptToken = null) } }
            registry.cancel(); mutable.update { it.copy(contactChoices = emptyList(), documentChoices = emptyList(), authentication = null, confirmation = null) }
            registry = app.foregroundRegistry(visible::get)
            block()
        } }
    }
    fun addDocumentGrant(uri: Uri) { viewModelScope.launch(Dispatchers.IO) { fileSafely {
        val result = app.documents.addGrant(uri)
        mutable.update { it.copy(fileStatus = "Indexed ${result.indexed} local files.${if (result.truncated) " The folder exceeded the scan limit." else ""}") }
        refreshDocuments()
    } } }
    fun refreshDocumentCatalog() { viewModelScope.launch(Dispatchers.IO) { fileSafely {
        val result = app.documents.refresh()
        mutable.update { it.copy(fileStatus = "Indexed ${result.indexed} files; ${result.unavailableRoots} locations unavailable.${if (result.truncated) " Scan limit reached." else ""}") }
        refreshDocuments()
    } } }
    fun revokeDocumentGrant(id: String) { viewModelScope.launch(Dispatchers.IO) { safely {
        app.documents.get(id)?.let { app.documents.revokeGrant(it) }; dialogue.clear(); refreshDocuments()
    } } }
    private suspend fun refreshDocuments() { val docs = app.documents.find(""); mutable.update { it.copy(documents = docs) } }
    fun refreshReminders() { viewModelScope.launch(Dispatchers.IO) {
        try {
            app.scheduler.restore()
            val reminders = app.scheduler.list()
            val enabled = app.scheduler.notificationsAllowed()
            mutable.update { it.copy(reminders = reminders, reminderStatus = if (enabled) "Notifications are enabled. Android may delay reminders." else "Reminder notifications are blocked. Enable AIVORA notifications in Android settings.") }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(reminderStatus = "Could not load reminders. Local storage may be unavailable.") } }
    } }
    fun cancelReminder(id: String) = choose { app.scheduler.cancel(id); refreshReminders() }
    fun reviewScheduledAction(id: String) = choose { reviewScheduled(id, CommandSource.FOREGROUND_OWNER) }
    private suspend fun reviewScheduled(id: String, source: CommandSource, expectedDueAt: Long? = null) {
        scheduleDueAt = null
        require(visible.get() && !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val review = try { app.scheduler.reviewAction(id, allowNetworkSetup = true, expectedDueAt = expectedDueAt) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            reply("This task is not ready, its saved conditions do not hold, or its target changed. Check the conditions and target before reviewing again.", AssistantState.ASKING)
            return
        }
        registry = app.scheduledRegistry(id, review.dueAt, visible::get)
        beginOnlinePlan(review.plan, source)
        refreshReminders()
    }
    fun createReminder(text: String, minutes: Long, repeat: Boolean, conditions: com.chronoit.aivora.scheduler.TaskConditions = com.chronoit.aivora.scheduler.TaskConditions()) {
        if (!visible.get() || app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        choose {
            scheduleDueAt = null
            scheduleRepeatMillis = 0L
            scheduleConditions = com.chronoit.aivora.scheduler.TaskConditions()
            try {
                val delay = Math.multiplyExact(minutes, 60_000L)
                val reminder = app.scheduler.create(text, delay, if (repeat) delay else 0, conditions)
                val reminders = app.scheduler.list()
                mutable.update { it.copy(reminders = reminders, reminderStatus = "Saved for approximately ${java.util.Date(reminder.dueAt)}. Android may delay delivery.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(reminderStatus = "Could not save this reminder. Check the text, interval and available local storage.") } }
        }
    }
    private suspend fun fileSafely(block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(fileStatus = "Could not index this location. Choose local Android storage, grant read access, and turn off private mode before updating the catalog.") } }
    }
    fun confirm() {
        val confirmation = mutable.value.confirmation ?: return
        confirm(confirmation.token)
    }
    private fun confirm(token: String) {
        val confirmation = mutable.value.confirmation?.takeIf { it.token == token } ?: return
        voiceConfirmation.clear()
        voice.interrupt()
        mutable.update { it.copy(confirmation = null, state = AssistantState.EXECUTING) }
        task = viewModelScope.launch(Dispatchers.IO) { safely { confirmReviewed(confirmation.token, confirmation.plan) } }
    }
    fun replyToActionFromShortcut(token: String) {
        val confirmation = mutable.value.confirmation?.takeIf { it.token == token } ?: return
        val action = confirmation.plan.actions.singleOrNull()
        val wifiTaskToken = connectionTaskToken.takeIf { action == Action.SetWifi(true) && connectionRegistry === registry }
        fun wifiTaskCurrent() = wifiTaskToken != null && connectionTaskToken == wifiTaskToken &&
            connectionRegistry === registry && connectivity.approvedSource(wifiTaskToken) != null
        fun current() = mutable.value.confirmation == confirmation &&
            confirmation.expiresAt > System.nanoTime() / 1_000_000 &&
            (wifiTaskToken == null || wifiTaskCurrent()) &&
            visible.get() && resumed.get() && !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!current()) return
        if (action !is Action.DraftMessage && action !is Action.DraftAppMessage && !wifiTaskCurrent()) {
            readConfirmation()
            return
        }
        val prompt = ActionReadback.create(confirmation.plan) ?: run {
            readConfirmation()
            return
        }
        voice.transcribe(prompt, stillCurrent = ::current) { text ->
            if (!current()) return@transcribe
            when (parser.parse(text, apps)) {
                ParsedCommand.ConfirmAction -> confirm(token)
                ParsedCommand.Cancel -> cancel()
                else -> reply("Say Confirm action or Cancel.")
            }
        }
    }
    private suspend fun confirmReviewed(token: String, reviewedPlan: ActionPlan?) {
        activeSmsSend = reviewedPlan?.actions?.any { it is Action.SendSms } == true
        try { show(registry.confirm(token)) } finally { activeSmsSend = false }
        if (reviewedPlan?.actions?.any { it is Action.ForgetPersonalFact } == true) refreshMemory()
    }
    fun authenticated(token: String) {
        if (mutable.value.authentication?.token != token) return
        voiceConfirmation.clear()
        voice.interrupt()
        authenticationLaunch.clear()
        val previous = task
        task = viewModelScope.launch(Dispatchers.IO) {
            previous?.join()
            if (mutable.value.authentication?.token != token) return@launch
            mutable.update { it.copy(authentication = null) }
            safely { show(registry.authenticate(token)) }
        }
    }
    fun consumeVoiceAuthentication(): String? = authenticationLaunch.consume(mutable.value.authentication,
        settings.value.handsFreeControls && visible.get() && resumed.get() && !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
    fun authenticationFailed(token: String) {
        if (mutable.value.authentication?.token == token) cancel()
    }
    fun cancel() {
        voiceConversationJob?.cancel()
        spokenQrPayment = null
        qrScanRequests.tryReceive()
        val wasSmsSend = activeSmsSend
        dueTaskChoices.clear()
        voiceConfirmation.clear()
        voiceChoice.clear()
        voiceNetwork.clear()
        connectionRegistry = null; connectionTaskToken = null
        authenticationLaunch.clear()
        connectivity.cancel()
        mutable.update { it.copy(networkPromptToken = null) }
        voice.stopAll()
        val previous = task
        previous?.cancel()
        mutable.update { it.copy(confirmation = null, authentication = null, contactChoices = emptyList(), documentChoices = emptyList(), state = AssistantState.IDLE) }
        task = viewModelScope.launch {
            previous?.join(); connectivity.cancel(); onlineRegistry = null
            mutable.update { it.copy(networkPromptToken = null) }
            reasoningFollowUp.clear()
            dialogue.clear(); registry.cancel(); reply(if (wasSmsSend) "Stopped waiting. The SMS may already have been submitted. Say SMS status before resending." else "Cancelled.")
        }
    }
    fun rename(name: String) { viewModelScope.launch(Dispatchers.IO) { safely { app.settings.rename(name); reply("Name saved. Record the new wake name in Settings.") } } }
    fun privateMode(value: Boolean) { submit(if (value) "private mode on" else "private mode off") }
    fun forget(id: String) { viewModelScope.launch(Dispatchers.IO) { safely { app.memory.forget(id); refreshMemory() } } }
    fun finishOnboarding() { viewModelScope.launch(Dispatchers.IO) { safely { app.settings.finishOnboarding() } } }
    fun spokenReplies(value: Boolean) { if (!value) voice.stopSpeaking(); viewModelScope.launch { app.settings.setSpokenReplies(value) } }
    fun handsFreeControls(value: Boolean) { if (!value) voice.stopAll(); viewModelScope.launch { app.settings.setHandsFreeControls(value) } }
    fun speechOptions(speed: Float, voiceId: Int) { viewModelScope.launch { app.settings.setSpeechOptions(speed, voiceId) } }
    fun internetBehavior(value: com.chronoit.aivora.data.InternetBehavior) { viewModelScope.launch { app.settings.setInternetBehavior(value) } }
    fun wifiControls(value: Boolean) { viewModelScope.launch { app.settings.setWifiControls(value) } }
    fun mobileDataControls(value: Boolean) { viewModelScope.launch { app.settings.setMobileDataControls(value) } }
    private suspend fun beginOnlinePlan(plan: ActionPlan, source: CommandSource) {
        val missing = plan.actions.filterIsInstance<Action.DraftAppMessage>().firstOrNull { app.packageManager.getLaunchIntentForPackage(it.app.packageName) == null }
        if (missing != null) { reply("${missing.app.displayName} is not installed. No message was drafted or sent.", AssistantState.ASKING); return }
        onlineRegistry = registry
        handleNetworkDecision(connectivity.begin(plan, source, networkMonitor.snapshot(), app.settings.settings.first().internetBehavior))
    }
    fun approveOnlineTask(token: String) = choose(keepOnlineTask = true) { handleNetworkDecision(connectivity.approve(token)) }
    private fun resumeOnlineTask(expectedToken: String? = connectivity.token()) {
        if (task?.isActive == true || expectedToken == null) return
        task = viewModelScope.launch(Dispatchers.IO) { safely {
            if (!resumed.get() || !visible.get() || app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return@safely
            connectivity.resume(networkMonitor.snapshot(), expectedToken)?.let { handleNetworkDecision(it) }
        } }
    }
    private suspend fun handleNetworkDecision(decision: NetworkDecision) {
        when (decision) {
            is NetworkDecision.Run -> {
                connectionRegistry = null; connectionTaskToken = null
                activeCommandSource = decision.source
                mutable.update { it.copy(networkPromptToken = null, state = AssistantState.EXECUTING) }
                onlineRegistry?.let { registry = it }
                onlineRegistry = null
                val result = registry.dispatch(decision.plan, decision.source)
                show(if (decision.restoreRequested && result is DispatchResult.Executed) result.copy(results = result.results.map {
                    it.copy(message = it.message + " Automatic connection restoration is unavailable in standard Android mode; restore it in Android's internet controls when finished.")
                }) else result)
            }
            is NetworkDecision.Ask -> {
                reply("This task needs internet. Say 'connect for this task' or open Android's internet controls below.", AssistantState.ASKING)
                mutable.update { it.copy(networkPromptToken = decision.token) }
                if (connectivity.review(decision.token)?.source == CommandSource.VERIFIED_VOICE && settings.value.handsFreeControls) readOnlineTask(decision.token)
            }
            is NetworkDecision.OpenControls -> {
                mutable.update { it.copy(networkPromptToken = null) }
                connectivity.resume(networkMonitor.snapshot(), decision.token)?.let { handleNetworkDecision(it); return }
                connectivity.review(decision.token)?.let { review ->
                    viewModelScope.launch {
                        val remaining = review.expiresAt - System.nanoTime() / 1_000_000
                        delay(remaining.coerceAtLeast(0))
                        if (connectivity.expireApproved(review.token)) {
                            mutable.update {
                                if (it.networkPromptToken == review.token) it.copy(networkPromptToken = null) else it
                            }
                            appendReply("The online task timed out before it could resume. Check your connection and repeat the request.")
                        }
                    }
                }
                val source = connectivity.approvedSource(decision.token)
                if (source != null && app.settings.settings.first().wifiControls && networkMonitor.snapshot().wifiEnabled == false &&
                    com.chronoit.aivora.automation.AivoraAccessibilityService.connected()) {
                    val helper = app.foregroundRegistry(visible::get)
                    connectionRegistry = helper; connectionTaskToken = decision.token; registry = helper; activeCommandSource = source
                    show(helper.dispatch(ActionPlan(listOf(Action.SetWifi(true)), 1.0, "Connect Wi-Fi for the approved online task"), source))
                    return
                }
                if (source != null && app.settings.settings.first().mobileDataControls && com.chronoit.aivora.automation.AivoraAccessibilityService.connected()) {
                    val mobile = try { AndroidMobileDataMonitor(app).snapshot() } catch (_: SecurityException) { null }
                    if (mobile != null && !mobile.enabled && MobileDataGuard.matches(mobile.target, mobile, true)) {
                        val helper = app.foregroundRegistry(visible::get)
                        connectionRegistry = helper; connectionTaskToken = decision.token; registry = helper; activeCommandSource = source
                        show(helper.dispatch(ActionPlan(listOf(Action.SetMobileData(true, mobile.target)), 1.0, "Mobile data for the approved online task; carrier charges may apply"), source))
                        return
                    }
                }
                val panel = app.foregroundRegistry(visible::get).dispatch(ActionPlan(listOf(Action.OpenSettings(SettingsPanel.INTERNET)), 1.0, "Owner-authorized connectivity controls"))
                show(panel)
                if (panel is DispatchResult.Executed && panel.results.any { it.status == ExecutionStatus.REQUIRES_USER_INTERACTION }) {
                    reply("Enable connectivity in Android's internet controls, then return to AIVORA within one minute. I will recheck validated internet before resuming the same task.")
                } else if (connectivity.approvedSource(decision.token) != null) {
                    mutable.update { it.copy(networkPromptToken = decision.token) }
                    if (source == CommandSource.VERIFIED_VOICE && settings.value.handsFreeControls) readOnlineTask(decision.token)
                }
                val finishing = currentCoroutineContext().job
                viewModelScope.launch {
                    finishing.join()
                    if (!finishing.isCancelled && connectivity.approvedSource(decision.token) != null &&
                        resumed.get() && networkMonitor.snapshot().validated) {
                        resumeOnlineTask(decision.token)
                    }
                }
            }
            is NetworkDecision.Wait -> {
                mutable.update { it.copy(networkPromptToken = connectivity.token()) }
                reply(decision.message, AssistantState.ASKING)
            }
        }
    }
    fun readLastReply() { mutable.value.conversation.lastOrNull { !it.owner }?.let { voice.speak(it.text, localOnly = true) } }
    override fun onCleared() { voice.close(); super.onCleared() }
    private suspend fun refreshMemory() { val memories = app.memory.recall(); mutable.update { it.copy(memories = memories) } }
    private fun reply(text: String, state: AssistantState = AssistantState.IDLE, localSpeech: Boolean = false) {
        val request = conversationRequest
        if (request != null) currentTurn = com.chronoit.aivora.memory.ConversationMemory.Turn(request, text)
        conversationRequest = null
        if (request != null) viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.conversationMemory.record(request, text) }
        }
        mutable.update { it.copy(state = state, confirmation = null, authentication = null, conversation = (it.conversation + ConversationLine(false, text)).takeLast(60)) }
        if (settings.value.spokenReplies && text != "Cancelled.") voice.speak(text, localOnly = localSpeech)
    }
    private suspend fun show(result: DispatchResult) {
        when (result) {
            is DispatchResult.Executed -> {
                var waitingForValidation = false
                if (registry === connectionRegistry) {
                    val token = connectionTaskToken
                    val switchVerified = result.results.singleOrNull()?.status == ExecutionStatus.SUCCESS
                    waitingForValidation = switchVerified && !networkMonitor.snapshot().validated
                    connectionRegistry = null; connectionTaskToken = null
                    val finishing = currentCoroutineContext().job
                    viewModelScope.launch {
                        finishing.join()
                        if (connectivity.token() == token && token != null) {
                            if (!switchVerified && !networkMonitor.snapshot().validated) {
                                mutable.update { it.copy(networkPromptToken = token) }
                            }
                            resumeOnlineTask(token)
                        }
                    }
                }
                result.results.flatMap { it.documents }.singleOrNull()?.let(dialogue::recordDocument)
                val message = result.results.joinToString("\n") { it.message }
                reply(if (waitingForValidation) "$message Waiting for Android to verify internet access." else message)
                refreshReminders()
            }
            is DispatchResult.Rejected -> reply(result.reason, AssistantState.ASKING)
            is DispatchResult.NeedsConfirmation -> {
                mutable.update { it.copy(state = AssistantState.WAITING_FOR_CONFIRMATION, confirmation = result.confirmation) }
                if (activeCommandSource == CommandSource.VERIFIED_VOICE && settings.value.handsFreeControls) speakConfirmation()
            }
            is DispatchResult.NeedsAuthentication -> {
                authenticationLaunch.prepare(result.confirmation, activeCommandSource, settings.value.handsFreeControls)
                mutable.update { it.copy(state = AssistantState.WAITING_FOR_CONFIRMATION, authentication = result.confirmation) }
            }
        }
    }
    fun speakConfirmation() {
        val confirmation = mutable.value.confirmation ?: return
        if (!settings.value.handsFreeControls) {
            mutable.update { it.copy(conversation = (it.conversation + ConversationLine(false, "Enable hands-free controls and enroll the owner before spoken approval.")).takeLast(60)) }
            return
        }
        val prompt = ActionReadback.create(confirmation.plan)
        if (prompt == null) {
            appendReply("This review cannot be fully represented within the spoken prompt limit. Use full on-screen review or request a shorter plan.")
            return
        }
        voice.verifiedReply(prompt) {
            mutable.value.confirmation == confirmation && confirmation.expiresAt > System.nanoTime() / 1_000_000
        }
    }
    fun readConfirmation() {
        val confirmation = mutable.value.confirmation ?: return
        if (confirmation.expiresAt <= System.nanoTime() / 1_000_000) {
            reply("This review expired. Repeat the command.")
            return
        }
        val prompt = ActionReadback.create(confirmation.plan, readOnly = true)
        if (prompt == null) {
            reply("Use the on-screen review to check all details for this action.")
            return
        }
        voice.speak(prompt)
    }
    private fun pendingChoiceRequests(state: AssistantUiState = mutable.value): List<ChoiceRequest> = buildList {
        dueTaskChoices.current()?.let { add(it) }
        state.contactChoiceToken?.takeIf { state.contactChoices.isNotEmpty() }?.let { add(ChoiceRequest(ChoiceKind.CONTACT, it, state.contactChoices.size)) }
        state.documentChoiceToken?.takeIf { state.documentChoices.isNotEmpty() }?.let { add(ChoiceRequest(ChoiceKind.DOCUMENT, it, state.documentChoices.size)) }
    }
    private fun appendReply(text: String) {
        mutable.update { it.copy(conversation = (it.conversation + ConversationLine(false, text)).takeLast(60)) }
    }
    private fun currentNetworkReview(): NetworkReview? = mutable.value.networkPromptToken?.let(connectivity::review)
    fun onlineTaskDescription(): String? = currentNetworkReview()?.plan?.actions?.joinToString("\n") { it.describe() }
    fun readOnlineTask() = readOnlineTask(mutable.value.networkPromptToken)

    private fun readOnlineTask(expectedToken: String?) {
        val review = currentNetworkReview()?.takeIf { it.token == expectedToken }
        if (review == null) {
            mutable.update { state -> if (state.networkPromptToken != null && connectivity.review(state.networkPromptToken) == null) state.copy(networkPromptToken = null) else state }
            appendReply("That online task expired or changed. Repeat the complete task.")
            return
        }
        val prompt = ActionReadback.create(review.plan, connection = true)
        if (prompt == null) { appendReply("This task cannot be fully represented within the spoken prompt limit. Use full on-screen review or request a shorter task."); return }
        appendReply(prompt)
        if (!settings.value.handsFreeControls || !voice.state.value.ownerEnrolled) {
            val instruction = "Tap Voice input before speaking your reply."
            appendReply(instruction)
            voice.speak("$prompt $instruction")
            return
        }
        voice.verifiedReply(prompt) { currentNetworkReview() == review }
    }
    fun readChoices(pageDelta: Int = 0) {
        val state = mutable.value
        val request = pendingChoiceRequests(state).singleOrNull() ?: return
        if (!choiceIsCurrent(request)) {
            voiceChoice.clear()
            mutable.update { current -> if (pendingChoiceRequests(current).singleOrNull() == request)
                current.copy(contactChoices = emptyList(), documentChoices = emptyList()) else current }
            appendReply("That choice expired or changed. Repeat the complete command.")
            return
        }
        if (choicePageToken != request.token) { choicePage = 0; choicePageToken = request.token }
        fun clean(value: String) = value.map { if (it.isISOControl()) ' ' else it }.joinToString("")
        val descriptions = if (request.kind == ChoiceKind.CONTACT) state.contactChoices.map(::spokenContact)
            else if (request.kind == ChoiceKind.TASK) dueTaskChoices.entries(request).map { "${clean(it.text)}, due ${java.util.Date(it.dueAt)}" }
            else state.documentChoices.map { "${clean(it.name)}, ${clean(it.mimeType)}, ${it.size} bytes" }
        val readback = ChoiceReadback.create(request.kind, descriptions, choicePage + pageDelta)
        if (readback == null) { appendReply("These detailed choices need on-screen review. Narrow the original request for a shorter spoken list."); return }
        choicePage = readback.page
        appendReply(readback.prompt)
        if (!settings.value.handsFreeControls || !voice.state.value.ownerEnrolled) {
            appendReply("Enable hands-free controls and enroll the owner to answer this list by voice.")
            return
        }
        voice.verifiedReply(readback.prompt) { pendingChoiceRequests().singleOrNull() == request && choiceIsCurrent(request) }
    }
    private fun choiceIsCurrent(request: ChoiceRequest): Boolean =
        if (request.kind == ChoiceKind.TASK) dueTaskChoices.current() == request else dialogue.choiceIsCurrent(request)
    private fun spokenContact(contact: ContactCandidate): String {
        fun clean(value: String) = value.map { if (it.isISOControl()) ' ' else it }.joinToString("")
        return "${clean(contact.name)}, ${clean(contact.label)}, ${contact.number.toCharArray().joinToString(" ")}"
    }
    private suspend fun prepareDirectCalls(plan: ActionPlan): ActionPlan? {
        if (!settings.value.directCalling || plan.actions.none { it is Action.Dial }) return plan
        if (PlanValidator.error(plan) != null) return plan
        val allowed = app.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val selection = if (!allowed) null else try { com.chronoit.aivora.platform.AndroidVoiceCallMonitor(app).selection() }
            catch (_: SecurityException) { null } catch (_: UnsupportedOperationException) { null }
        val bound = DirectCallPlanner.bind(plan, selection?.target)
        if (bound === plan) appendReply("Direct calling is unavailable for this request. Review the dialer-only action; Android's dialer handles call placement.")
        return bound
    }

    fun directCalling(value: Boolean) { viewModelScope.launch { app.settings.setDirectCalling(value) } }
    private fun prepareDirectSms(plan: ActionPlan): ActionPlan {
        if ((plan.actions.singleOrNull() as? Action.DraftMessage)?.draftOnly == true) return plan
        if (!settings.value.directSms || scheduleDueAt != null || plan.actions.none { it is Action.DraftMessage }) return plan
        val allowed = listOf(android.Manifest.permission.SEND_SMS, android.Manifest.permission.READ_PHONE_STATE).all {
            app.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val target = if (!allowed) null else try { com.chronoit.aivora.platform.AndroidSmsMonitor(app).selection() }
            catch (_: SecurityException) { null } catch (_: UnsupportedOperationException) { null }
        val bound = DirectSmsPlanner.bind(plan, target)
        if (bound === plan) appendReply("Direct SMS is unavailable for this request. Review the draft-only action; sending remains in your messaging app.")
        return bound
    }
    fun directSms(value: Boolean) { viewModelScope.launch { app.settings.setDirectSms(value) } }
    fun localReasoning(value: Boolean) { viewModelScope.launch { app.settings.setLocalReasoning(value) } }
    private suspend fun safely(block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalArgumentException) { reply("That value is invalid. Please check your input.", AssistantState.ERROR) }
        catch (_: Exception) { reply("The operation could not finish. Local storage or a device capability may be unavailable.", AssistantState.ERROR) }
    }
}
