# AIVORA: five first-release priorities

The fixed product/package remain AIVORA / com.chronoit.aivora, Android 11+.

## Routing and local learning

ConversationRouting resolves repeat, explanation/simplification, previous answers, saved-memory requests, ratings and explicit corrections before any web decision. Missing context asks for clarification. Saved-memory and current-reply follow-ups use offline synthesis; rephrasing uses the local Qwen model with the prior answer as evidence. Stored context is not attached to Brave requests. New information questions can use Brave; explicit fresh searches bypass saved answer corrections. Ambiguous bare topics such as Java or Mercury ask which meaning, and the next reply keeps that topic.

A correction introduced with "the correct answer is" is encrypted locally and keyed to the normalized previous question. Later matching questions quote the user's correction as such; it is not treated as a verified web fact or an action instruction. Explicit remembered facts remain separate. This is retrieval/preference learning, not model-weight reinforcement training. Exact-question corrections do not guarantee paraphrase matching. Recently recognized conversation pairs remain bounded at 80; private mode blocks persistent corrections/history. Runtime current-reply context can still serve immediate repetition without persisting private conversation.

Alarm/reminder slot filling accepts polite requests, "I need an alarm", "wake me at", "don't let me forget", reminder time before content and pending-time corrections such as "no, make it six AM". Dates the Clock next-occurrence API cannot represent are clarified. English command semantics remain the current baseline.

## Speech interruption

During foreground speech, a separate offline wake detector listens with VOICE_COMMUNICATION capture and Android acoustic echo cancellation when available. Say "Hey Jarvis" or "Hey Aivora", wait for "Go ahead", then give the next request. The detector stops playback, cancels active request processing when appropriate and releases its microphone lease before command capture. This is wake-word barge-in, not arbitrary speech overlap or speaker authentication. Audio used by this detector stays local. Reminder speech also supports stopping on the wake name; opening a panel still requires an unlocked device and a bound Android assistant service. Interrupted reminders are marked acknowledged rather than falsely recorded as fully played.

Hardware echo cancellation and wake accuracy vary. Generated fixture detection, actual AudioTrack stopping, acoustic self-echo and neutral-playback tests cover the POCO only; they do not prove human wake accuracy across devices.

## Reminder delivery

Encrypted task definitions remain authoritative. AlarmManager wakeups supplement persistent WorkManager jobs; duplicate workers share the delivery mutex/state checks and do not create duplicate speech occurrences. Android 12+ precise timing needs user-granted Alarms & reminders access; without it, approximate fallback remains active. Settings explains this and links to Android's access screen. Recurring alarms schedule their next wakeup directly. Reboot, package-update, clock and timezone events enqueue recovery and reschedule waiting occurrences. Cancellation removes alarm and worker paths; queued speech rechecks cancellation after waiting for a conversation to finish.

Saved reminder text is synthesized offline. Device-encrypted task storage needs first unlock after reboot. Android Doze quotas, revoked access, OEM restrictions and force-stop remain platform limitations; exact access is not an unlimited real-time guarantee.

## Web answer quality

All new online answers retrieve evidence and use the local grounded summarizer, including credentials that support the old Answers endpoint. Source ranking favors relevant independent educational/government/reference hosts and direct explanatory titles for causal questions. Duplicate hosts, unrelated words and obvious prompt-injection snippets are excluded. The model is asked for a direct, simple answer and preservation of subject/property relationships. Support checks reject unsupported numbers, unsupported negation, action-completion claims and low lexical overlap. These are conservative heuristics, not a mathematical factual guarantee. A failed summary asks for clarification instead of reading unchecked snippets.

Verification records are in TEST_MATRIX.md and app/build/reports. Keep genuine device results separate from synthetic audio fixtures, controlled battery-state simulation and prolonged real-world acceptance.
