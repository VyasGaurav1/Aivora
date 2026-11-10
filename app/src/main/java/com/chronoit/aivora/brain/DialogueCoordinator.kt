package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.*
import java.util.UUID

enum class FileOperation { FIND, OPEN, SHARE }
sealed interface DialogueTurn {
    data class Command(val command: ParsedCommand) : DialogueTurn
    data class Answer(val text: String) : DialogueTurn
    data class Contacts(val question: String, val choices: List<ContactCandidate>, val token: String) : DialogueTurn
    data class Documents(val question: String, val choices: List<LocalDocument>, val token: String) : DialogueTurn
}

/** Working context and clarification only; this component cannot execute Android actions. */
class DialogueCoordinator(
    private val parser: CommandParser,
    private val contacts: ContactDirectory,
    private val documents: DocumentDirectory,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val vocabulary: ReferenceVocabulary? = null,
) {
    private var project: String? = null
    private var teachingAlias: String? = null
    private val clarifications = ClarificationManager(clock)
    private data class PendingContact(val choices: List<ContactCandidate>, val body: String?, val share: LocalDocument?, val expires: Long, val token: String = UUID.randomUUID().toString(), val app: MessagingApp? = null, val speakerphone: Boolean = false, val draftOnly: Boolean = false)
    private data class PendingDocument(val choices: List<LocalDocument>, val operation: FileOperation, val recipient: String?, val expires: Long, val token: String = UUID.randomUUID().toString())
    @Volatile private var pendingContact: PendingContact? = null
    @Volatile private var pendingDocument: PendingDocument? = null
    private var currentFile: Pair<LocalDocument, Long>? = null
    private var currentContact: Pair<ContactCandidate, Long>? = null
    fun choiceIsCurrent(request: ChoiceRequest): Boolean = when (request.kind) {
        ChoiceKind.CONTACT -> pendingContact?.let { it.token == request.token && it.choices.size == request.count && it.expires > clock() } == true
        ChoiceKind.DOCUMENT -> pendingDocument?.let { it.token == request.token && it.choices.size == request.count && it.expires > clock() } == true
        ChoiceKind.TASK -> false
    }
    suspend fun process(text: String, apps: List<LaunchableApp>): DialogueTurn {
        val choice = ChoiceReply.parse(text)
        if (choice?.pageDelta != null) {
            pendingContact?.takeIf { it.expires > clock() }?.let { return DialogueTurn.Contacts("Which contact and number do you mean?", it.choices, it.token) }
            pendingDocument?.takeIf { it.expires > clock() }?.let { return DialogueTurn.Documents("Which file do you mean?", it.choices, it.token) }
            return DialogueTurn.Answer("There is no choice waiting. Repeat the complete command.")
        }
        if (choice?.index != null) {
            if (choice.kind == null || choice.kind == ChoiceKind.CONTACT) pendingContact?.let { return chooseContact(choice.index) }
            if (choice.kind == null || choice.kind == ChoiceKind.DOCUMENT) pendingDocument?.let { return chooseDocument(choice.index) }
            return DialogueTurn.Answer("There is no matching choice waiting. Repeat the complete command.")
        }
        pendingContact = null; pendingDocument = null
        teachingAlias = null
        clarifications.answer(text, project)?.let { answer ->
            if (vocabulary != null) {
                FileAlias.create(project!!, answer.phrase, "validation")
                teachingAlias = answer.phrase
                return file(answer.filename, FileOperation.SHARE, answer.recipient)
            }
        }
        val parsed = parser.parse(text, apps)
        return when (parsed) {
            is ParsedCommand.ProjectContext -> {
                require(parsed.name.isNotBlank() && parsed.name.length <= 100 && '\n' !in parsed.name && '\r' !in parsed.name)
                clear(); project = LearnedPhrase.key(parsed.name)
                DialogueTurn.Answer("Using project $project for learned file references.")
            }
            is ParsedCommand.TeachFileAlias -> {
                if (project == null || vocabulary == null) DialogueTurn.Answer("First say 'use project' followed by a project name.")
                else { FileAlias.create(project!!, parsed.phrase, "validation"); teachingAlias = parsed.phrase; file(parsed.file, FileOperation.FIND, null) }
            }
            is ParsedCommand.CallPerson -> person(parsed.reference, null, null, speakerphone = parsed.speakerphone)
            is ParsedCommand.MessagePerson -> person(parsed.reference, parsed.body, null, parsed.app, draftOnly = parsed.draftOnly)
            is ParsedCommand.FileReference -> file(parsed.reference, parsed.operation, parsed.recipient)
            is ParsedCommand.Plan -> if (parsed.value.actions.size == 1 && parsed.value.actions.single() is Action.FindFile)
                file((parsed.value.actions.single() as Action.FindFile).query, FileOperation.FIND, null) else DialogueTurn.Command(parsed)
            ParsedCommand.Cancel -> { clear(); DialogueTurn.Command(parsed) }
            else -> DialogueTurn.Command(parsed)
        }
    }
    suspend fun chooseContact(index: Int, token: String? = null): DialogueTurn {
        val pending = pendingContact ?: return DialogueTurn.Answer("There is no contact choice waiting. Repeat the command.")
        if (token != null && token != pending.token) return DialogueTurn.Answer("That contact choice no longer matches the command. Repeat it.")
        if (pending.expires <= clock() || index !in pending.choices.indices) { pendingContact = null; return DialogueTurn.Answer("That contact choice expired or is invalid. Repeat the command.") }
        pendingContact = null
        val contact = pending.choices[index]
        currentContact = contact to clock()
        return contactPlan(contact, pending.body, pending.share, pending.app, pending.speakerphone, pending.draftOnly)
    }
    suspend fun chooseDocument(index: Int, token: String? = null): DialogueTurn {
        val pending = pendingDocument ?: return DialogueTurn.Answer("There is no file choice waiting. Repeat the command.")
        if (token != null && token != pending.token) return DialogueTurn.Answer("That file choice no longer matches the command. Repeat it.")
        if (pending.expires <= clock() || index !in pending.choices.indices) { pendingDocument = null; teachingAlias = null; return DialogueTurn.Answer("That file choice expired or is invalid. Repeat the command.") }
        pendingDocument = null
        return useFile(pending.choices[index], pending.operation, pending.recipient)
    }
    fun clear() { pendingContact = null; pendingDocument = null; teachingAlias = null; clarifications.clear(); currentFile = null; currentContact = null }
    fun recordDocument(document: LocalDocument) { currentFile = document to clock() }
    suspend fun useDocument(id: String, operation: FileOperation): DialogueTurn {
        clear()
        val document = documents.get(id) ?: return DialogueTurn.Answer("That file's access is unavailable. Select it again.")
        return useFile(document, operation, null)
    }
    private suspend fun person(reference: String, body: String?, share: LocalDocument?, app: MessagingApp? = null, speakerphone: Boolean = false, draftOnly: Boolean = false): DialogueTurn {
        if (reference.length > 100 || (body?.length ?: 0) > 4000) return DialogueTurn.Answer("That contact or message is too long.")
        if (normalizeReference(reference) in setOf("him", "her", "them")) {
            val recent = currentContact?.takeIf { clock() - it.second <= 300_000 }?.first
                ?: return DialogueTurn.Answer("Who do you mean? Use a contact name or phone number.")
            return contactPlan(recent, body, share, app, speakerphone, draftOnly)
        }
        val matches = try { contacts.search(reference) } catch (_: SecurityException) {
            return DialogueTurn.Answer("Allow contacts access in Capabilities to resolve names. You can also use a phone number.")
        }
        return when (val resolved = ContactResolver.resolve(reference, matches)) {
            is ContactResolution.Resolved -> { currentContact = resolved.contact to clock(); contactPlan(resolved.contact, body, share, app, speakerphone, draftOnly) }
            is ContactResolution.Choose -> {
                pendingContact = PendingContact(resolved.candidates, body, share, clock() + 120_000, app = app, speakerphone = speakerphone, draftOnly = draftOnly)
                DialogueTurn.Contacts("Which contact and number do you mean?", resolved.candidates, pendingContact!!.token)
            }
            ContactResolution.Missing -> DialogueTurn.Answer("I couldn't find that contact. Use the full name or a phone number.")
            ContactResolution.TooMany -> DialogueTurn.Answer("Too many contacts match. Use the full name.")
        }
    }
    private fun contactPlan(contact: ContactCandidate, body: String?, share: LocalDocument?, app: MessagingApp? = null, speakerphone: Boolean = false, draftOnly: Boolean = false): DialogueTurn {
        if (app != null && internationalPhone(contact.number) == null) return DialogueTurn.Answer("This contact needs a full international number including '+' and country code for ${app.displayName}. Correct the contact or use the full number.")
        val action = when {
            share != null -> Action.ShareFile(share.uri, share.mimeType, contact.name, share.name)
            body != null && app != null -> Action.DraftAppMessage(app, contact.number, body, contact.id, contact.name)
            body != null -> Action.DraftMessage(contact.number, body, contact.id, contact.name, draftOnly)
            else -> Action.Dial(contact.number, contact.id, contact.name, speakerphone)
        }
        return plan(action)
    }
    private suspend fun file(reference: String, operation: FileOperation, recipient: String?): DialogueTurn {
        if (teachingAlias == null) {
            val alias = project?.let { vocabulary?.lookup(it, reference) }
            if (alias != null) {
                val target = documents.get(alias.documentId)?.takeIf(documents::readable)
                    ?: return DialogueTurn.Answer("The learned file is missing or access was revoked. Select it again and correct this reference.")
                return useFile(target, operation, recipient)
            }
        }
        if (normalizeReference(reference) in setOf("it", "that", "the file")) {
            val recent = currentFile?.takeIf { clock() - it.second <= 300_000 }?.first
                ?: return DialogueTurn.Answer("Which file do you mean? Find or select a file first.")
            return useFile(recent, operation, recipient)
        }
        val found = documents.find(reference).filter(documents::readable)
        val exact = found.filter { it.name.equals(reference, true) }
        val choices = exact.ifEmpty { found }
        return when (choices.size) {
            0 -> if (operation == FileOperation.SHARE && teachingAlias == null)
                DialogueTurn.Answer(clarifications.ask(reference, recipient, project))
                else DialogueTurn.Answer("No matching permitted local file is available. Add a local file or folder in Files, then search again.")
            1 -> useFile(choices.single(), operation, recipient)
            else -> {
                pendingDocument = PendingDocument(choices, operation, recipient, clock() + 120_000)
                DialogueTurn.Documents("Which file do you mean?", choices, pendingDocument!!.token)
            }
        }
    }
    private suspend fun useFile(document: LocalDocument, operation: FileOperation, recipient: String?): DialogueTurn {
        val current = documents.get(document.id)?.takeIf(documents::readable)
            ?: return DialogueTurn.Answer("That file is missing or its access was revoked. Select it again in Files.")
        currentFile = current to clock()
        teachingAlias?.let { phrase ->
            teachingAlias = null
            val saved = vocabulary!!.save(FileAlias.create(project!!, phrase, current.id))
            if (operation != FileOperation.SHARE) return DialogueTurn.Answer(if (saved) "In project $project, $phrase now means ${current.name}." else "Private mode is on, so I didn't save this reference.")
        }
        return when (operation) {
            FileOperation.FIND -> DialogueTurn.Answer("I found ${current.name}.")
            FileOperation.OPEN -> plan(Action.OpenFile(current.uri, current.mimeType, current.name))
            FileOperation.SHARE -> if (recipient == null) plan(Action.ShareFile(current.uri, current.mimeType, displayName = current.name)) else person(recipient, null, current)
        }
    }
    private fun plan(action: Action) = DialogueTurn.Command(ParsedCommand.Plan(ActionPlan(listOf(action), 1.0, "Resolved explicit owner request")))
}
