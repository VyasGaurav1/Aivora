# MASTER BUILD INSTRUCTION — OFFLINE MOBILE AIVORA PERSONAL ASSISTANT

You are the principal Android architect and implementation agent for this project.

Your job is NOT to create a demo, prototype, chatbot UI, or proof of concept.

Build a production-quality, mobile-only, offline-first personal voice assistant that gradually learns its owner's vocabulary, preferences, habits, routines, work style, and previous conversations.

The assistant should feel like a highly capable personal companion/assistant similar in interaction style to fictional assistants such as AIVORA, while remaining honest that it is software.

The assistant must operate entirely on the Android phone.

There is:

- NO PC companion.
- NO external server.
- NO OpenAI API.
- NO Gemini API.
- NO Claude API.
- NO Google Assistant dependency.
- NO Alexa dependency.
- NO Firebase.
- NO Tasker.
- NO Shizuku.
- NO hosted STT.
- NO hosted TTS.
- NO cloud memory.
- NO cloud database.
- NO cloud AI dependency.
- NO mandatory account.
- NO analytics/telemetry service.
- NO runtime requirement for the internet.

Internet is a TOOL the assistant may temporarily enable/use only when a user-requested task itself requires internet access.

The assistant's voice understanding, reasoning, memory, scheduling, permission decisions, basic commands, and personality must continue operating offline.

---

# 0. CRITICAL CODEX CREDIT / EFFICIENCY RULES

My Codex credits are valuable.

Optimize your work to minimize unnecessary reasoning, rereading, rebuilding, experimentation and rewrites.

Follow these rules strictly.

1. Inspect the existing repository before writing anything.

2. If working code already exists:
   - preserve it;
   - understand it;
   - modify only what is necessary;
   - never recreate working components merely because you prefer another implementation.

3. Do NOT repeatedly redesign the architecture.

4. The architecture in this prompt is the source of truth.

5. Do NOT give me several technology alternatives and wait for me to choose.
   Pick the most appropriate implementation consistent with this specification and proceed.

6. Do NOT ask questions unless progress is genuinely impossible without information only I can provide.

7. If a device/OEM-specific capability is unknown, implement:
   - capability detection;
   - graceful fallback;
   - clear diagnostics;
   and continue.

8. Never use trial-and-error dependency upgrades.

9. Pin dependency versions.

10. Add as few dependencies as reasonably possible.

11. Prefer:
   - Android SDK;
   - AndroidX/Jetpack;
   - Kotlin standard libraries;
   - our own implementation.

12. A third-party local library/runtime is permitted ONLY when implementing the equivalent ourselves would be unreasonable, especially ML inference.

Any such component must:
   - operate completely offline;
   - make no network requests;
   - be version-pinned;
   - have an acceptable open-source license;
   - be isolated behind our interface;
   - be replaceable later;
   - be listed in THIRD_PARTY_NOTICES.md.

13. Do not introduce huge frameworks for small problems.

14. Prefer manual dependency injection rather than introducing a DI framework unless complexity later clearly justifies it.

15. Before changing a major subsystem, determine the root cause.

16. Do not rewrite a whole file when a small patch is enough.

17. After every meaningful phase:
   - compile;
   - run relevant tests;
   - fix failures before continuing.

18. Use targeted tests during development.
Run the entire test suite at major integration gates.

19. Maintain a short file:

PROJECT_STATE.md

It must contain only:
   - current architecture;
   - implemented capabilities;
   - current blockers;
   - important decisions;
   - next tasks.

Keep it concise so future Codex sessions can read this instead of rediscovering the entire repository.

20. Also maintain:

DECISIONS.md
TEST_MATRIX.md
KNOWN_LIMITATIONS.md

Keep all of them concise.

21. Do not create excessive documentation.

22. Do not spend credits polishing animations/UI until the functional core is reliable.

23. Never claim something works unless it has either:
   - an automated test;
   - successful compilation plus appropriate integration verification;
   - or a documented physical-device test requirement.

24. Do NOT stop after generating architecture or TODO files.

Actually build the application.

---

# 1. TARGET PLATFORM

Build for modern Android.

Use:

- Kotlin
- Android Studio/Gradle
- Jetpack Compose
- Coroutines/Flow
- Room
- DataStore
- WorkManager
- Android Keystore
- appropriate Android platform APIs

Initial targets:

minSdk = 30
compileSdk = 37
targetSdk = 37

Keep platform-specific behavior isolated.

Main compatibility testing:

- API 30 (Android 11; owner-confirmed minimum)
- API 31
- API 34
- API 36
- API 37

Do not downgrade targetSdk simply to bypass modern Android restrictions.

Respect Android's security model.

The application should work in two capability tiers.

## Tier A — Standard Android

Works without root.

Uses legitimate Android capabilities such as:

- Assistant role
- VoiceInteractionService
- AccessibilityService
- NotificationListenerService
- runtime permissions
- contacts
- media APIs
- telecom/intents where appropriate
- alarms
- WorkManager
- settings panels
- package manager
- storage APIs

## Tier B — Privileged / Root

Optional.

If the device is already rooted or AIVORA is installed as a privileged/system application, expose additional capabilities through a separate PrivilegeBridge.

DO NOT:
- exploit Android;
- root devices;
- bypass lock-screen security;
- implement privilege escalation.

Detect already available privileges.

Architecture:

PrivilegeBridge
├── StandardPrivilegeBridge
├── RootPrivilegeBridge
└── SystemPrivilegeBridge

The rest of AIVORA must not care which implementation is active.

Never expose unrestricted shell execution to the AI planner.

Every privileged operation must be an allow-listed typed operation.

Example:

enableMobileData()

NOT:

executeShell(aiGeneratedString)

---

# 2. CORE PRODUCT EXPERIENCE

The expected interaction should eventually resemble:

Owner:
"AIVORA."

Assistant:
"Yes?"

Owner:
"Message Rahul that I'll be there in twenty minutes."

Assistant resolves the person, understands the task and performs it if authorized.

Another example:

Owner:
"AIVORA, usual setup."

If AIVORA doesn't know "usual setup":

Assistant:
"I'm not sure what you mean by 'usual setup'. What should I change?"

Owner:
"Wi-Fi on, Bluetooth on, brightness around forty percent and sound on."

Assistant learns the phrase.

Next week:

Owner:
"AIVORA, usual setup."

AIVORA performs the learned routine.

The owner should normally need to explain something only once.

---

# 3. REQUIRED HIGH-LEVEL PIPELINE

Implement this conceptual pipeline:

Audio
↓
Voice Activity Detection
↓
Wake Name Detection
↓
Speaker Verification
↓
Speech-to-Text
↓
Dialogue Manager
↓
Context Retrieval
↓
Long-Term Memory Retrieval
↓
Intent / Reasoning
↓
Structured Action Plan
↓
Risk + Permission Policy
↓
Execution
↓
Execution Verification
↓
Memory Learning
↓
Text-to-Speech

No AI component may directly execute Android operations.

---

# 4. ASSISTANT ROLE / BACKGROUND VOICE

Implement AIVORA as a proper Android assistant candidate.

Investigate and correctly implement:

VoiceInteractionService

and Android's Assistant role where supported.

Keep the always-running component extremely lightweight.

Do NOT continuously run a large language model while idle.

Idle state should perform only the minimum work necessary for activation.

Respect modern microphone foreground/background restrictions.

Do not hack around Android lifecycle restrictions.

---

# 5. CUSTOM ASSISTANT NAME

The user must be able to choose the assistant's name during setup.

Example:

"AIVORA"

"Friday"

"Nova"

etc.

The user must later be able to say:

"AIVORA, from now on I'm calling you Friday."

AIVORA should:

1. understand the rename request;
2. update assistant identity;
3. explain that the new wake name needs voice enrollment if necessary;
4. collect several samples;
5. update the wake-name model/profile;
6. deactivate the previous name when successful.

Do not pretend arbitrary custom hotword recognition is perfect without enrollment.

Implement:

WakeWordEngine

with a replaceable backend.

Prefer a low-power personalized wake-name implementation.

A reasonable fallback is:

VAD → short audio window → lightweight offline recognition/classification.

Do not continuously run the full STT/LLM pipeline merely to detect the wake name.

---

# 6. OWNER VOICE VERIFICATION

Create:

SpeakerVerifier

During onboarding, collect multiple owner voice samples.

Generate/store only the features needed for verification whenever possible.

Do not permanently keep raw enrollment recordings unless required.

Normal low-risk commands may rely on speaker verification.

Sensitive commands must NOT rely solely on voice authentication.

For sensitive actions use Android biometric/device credential confirmation.

Implement spoof-resistant design where practical.

If speaker confidence is low:

do not execute.

Ask for authentication.

---

# 7. OFFLINE SPEECH RECOGNITION

Create:

SpeechToTextEngine

Requirements:

- offline;
- streaming where practical;
- punctuation where practical;
- English first;
- architecture ready for additional languages;
- optimized for conversational commands;
- no network calls.

Do NOT rely on a cloud SpeechRecognizer implementation as the core speech system.

Any model/runtime must be bundled locally and abstracted behind SpeechToTextEngine.

Provide confidence information where available.

---

# 8. OFFLINE TEXT-TO-SPEECH

Create:

TextToSpeechEngine

Requirements:

- completely offline;
- natural enough for daily conversation;
- interruption support;
- stop speaking instantly when owner interrupts;
- adjustable speed;
- adjustable voice;
- low latency.

Avoid mandatory dependence on an externally installed/cloud TTS engine.

---

# 9. LOCAL REASONING ENGINE

Create:

LocalReasoningEngine

The application may use a small locally running language model when needed.

It must:

- run locally;
- never send prompts off-device;
- be replaceable;
- support structured output;
- be used only where language reasoning is valuable.

Do NOT send trivial commands through a large model.

Examples handled deterministically:

- flashlight on
- volume 40%
- open WhatsApp
- call Mom
- set alarm 7 AM
- brightness 30%
- pause music

Complex requests may use local reasoning.

Example:

"Find the PDF I downloaded yesterday about my internship and send it to Rahul."

Use:

deterministic command layer
+
local reasoning layer

not LLM-for-everything.

---

# 10. STRUCTURED ACTION SYSTEM

Create typed actions.

Example conceptual structure:

ActionPlan
- planId
- sourceCommand
- actions[]
- confidence
- reasoningSummary
- riskLevel
- requiresConfirmation

Action
- actionType
- parameters
- requiredCapabilities
- requiresNetwork
- reversible
- verificationStrategy

Use sealed classes/data classes rather than arbitrary strings wherever practical.

Examples:

OpenAppAction
SendMessageAction
CallContactAction
SetVolumeAction
SetBrightnessAction
PlayMediaAction
PauseMediaAction
CreateReminderAction
CreateScheduledTaskAction
EnableConnectivityAction
OpenFileAction
ShareFileAction
DeleteFileAction
UninstallAppAction

THE REASONING ENGINE MUST NEVER DIRECTLY CALL EXECUTORS.

Pipeline must always be:

Reasoning
→ structured action
→ validator
→ policy
→ executor.

Reject malformed/unknown actions.

---

# 11. RISK / PERMISSION ENGINE

Create:

ActionPolicyEngine

Suggested levels:

LOW
- open app
- flashlight
- media
- volume
- answer question

MEDIUM
- calls
- messages
- launching external workflows
- changing settings

HIGH
- installing apps
- changing accounts
- modifying important files
- sharing sensitive data

DESTRUCTIVE
- deleting files
- deleting contacts
- uninstalling
- resetting settings
- destructive system changes

CRITICAL
- financial operations
- credentials
- security configuration
- device administration

Rules:

Low-risk pre-authorized actions can execute immediately.

High/destructive actions require confirmation unless the owner created a precise persistent authorization.

Critical operations require explicit confirmation and biometric/device credential where appropriate.

Never infer blanket permission from vague language.

Example persistent authorization:

"When I say clean temp files, you can delete files in my Temp folder older than 30 days without asking."

Store the exact scope.

Never interpret that as permission to delete arbitrary files.

---

# 12. INTERNET ORCHESTRATOR

Create:

ConnectivityOrchestrator

AIVORA must remain intelligent offline.

Actions declare:

requiresNetwork = true/false

When a task requiring internet arrives:

1. detect current connectivity;
2. remember previous Wi-Fi/mobile-data state;
3. if connected, continue;
4. if disconnected, attempt authorized connectivity activation;
5. wait for validated internet connectivity;
6. execute task;
7. optionally restore previous state according to user preference.

Settings:

Internet behavior:
- leave current state unchanged;
- automatically enable when required;
- ask before enabling;
- restore previous state afterward.

Standard Android mode must respect Android restrictions.

If silent Wi-Fi/mobile-data control is unavailable:
- use the legitimate system settings/panel flow;
- explain the required user action;
- resume automatically afterward when possible.

Privileged/root/system mode may use the corresponding typed PrivilegeBridge methods.

Do not fake successful connectivity changes.

---

# 13. MEMORY — CENTRAL FEATURE

Memory is NOT an optional feature.

Build it as a core subsystem:

AivoraMemory

Memory categories:

WORKING
EPISODIC
SEMANTIC
VOCABULARY
PREFERENCE
HABIT
PROCEDURAL
RELATIONSHIP
CORRECTION
ROUTINE

Each memory should support fields similar to:

id
type
content
structuredData
confidence
source
createdAt
updatedAt
lastUsedAt
useCount
sensitivity
expiresAt
status

Memory sources should distinguish:

EXPLICIT_OWNER_STATEMENT
OWNER_CORRECTION
CONFIRMED_INFERENCE
OBSERVED_PATTERN
CONVERSATION_SUMMARY
SYSTEM_EVENT

Explicit owner teaching should have higher confidence than observed inference.

---

# 14. CLARIFICATION → LEARNING LOOP

This behavior is mandatory.

AIVORA must NEVER confidently guess ambiguous personal meanings.

Example:

Owner:
"Send him the blue one."

If unknown:

AIVORA:
"Who do you mean by 'him', and what is 'the blue one'?"

Owner:
"Rahul. In this project, blue one means FinalReport.pdf."

AIVORA must:

1. resolve current command;
2. store the clarification;
3. associate it with the correct context;
4. reuse it later.

Future:

"Send Rahul the blue one."

Should work without asking again when confidence is high.

Implement:

ClarificationManager

If intent/entity/action confidence is below threshold:

ASK.

Do not guess.

For dangerous actions use stricter thresholds.

---

# 15. CORRECTIONS MUST MODIFY MEMORY

Example:

AIVORA opens Spotify.

Owner:
"No, when I say play music use YouTube Music."

AIVORA must update the preference.

Later:

Owner:
"When I'm exercising, use Spotify though."

AIVORA should represent:

default music app = YouTube Music

IF context = workout:
music app = Spotify

Corrections should supersede conflicting older rules while preserving historical provenance.

---

# 16. HABIT LEARNING

AIVORA should detect patterns over time.

Example:

Owner repeatedly:

opens WhatsApp
opens email
checks calendar

around the same morning period.

Do NOT silently turn observations into powerful automatic routines.

Instead produce a suggestion after sufficient evidence:

"You usually check WhatsApp, email and your calendar in the morning. Want me to make this a routine?"

Only after owner confirmation create automation.

Store:

- observation count;
- consistency;
- context;
- confidence;
- confirmation status.

One-off behavior must not become a habit.

---

# 17. PROCEDURAL MEMORY

AIVORA should learn HOW the owner prefers tasks performed.

Example:

When sending a college file, owner normally:

1. renames it cleanly;
2. checks it;
3. sends through WhatsApp;
4. sends no extra text.

After repeated observation plus confirmation, store this procedure.

Future command:

"Handle the assignment."

may invoke the learned procedure if context/confidence is sufficient.

---

# 18. RELATIONSHIP / ENTITY MEMORY

Implement a lightweight local entity graph.

Entities:

- people;
- applications;
- files;
- projects;
- places where appropriate;
- routines;
- devices;
- concepts.

Relations:

- works_with
- related_to
- preferred_for
- current_project
- friend/contact
- file_for
- routine_contains
- alias_of

Example:

Owner
→ works_with → Rahul
→ current_project → Internship Report
→ final_file → FinalReport.pdf

This allows contextual references.

Use Room relational tables and FTS initially.

Do not require a graph database.

---

# 19. CONVERSATION MEMORY

AIVORA should remember useful previous conversations.

Do NOT permanently store every word and every audio sample.

Pipeline:

temporary transcript
→ memory extraction
→ importance evaluation
→ store useful memory
→ discard unnecessary raw audio

Support queries such as:

"Do you remember the presentation we worked on last week?"

"How did I normally prepare assignments last semester?"

"What did I call that setup?"

Use recency + relevance + entity relationships + importance.

Add local semantic embeddings only if the local model stack already supports them efficiently.

FTS and structured retrieval must work even without embeddings.

---

# 20. MEMORY CONSOLIDATION

Prevent uncontrolled database growth.

Example memories:

- used YouTube Music
- corrected Spotify to YouTube Music
- selected YouTube Music again

May consolidate into:

Default music player: YouTube Music
Confidence: high

Keep provenance references.

Implement conservative consolidation.

Never delete important explicit owner memories merely because they are old.

---

# 21. MEMORY EVOLUTION

The owner changes over time.

AIVORA must distinguish:

CURRENT preference
HISTORICAL preference

If a previously strong preference conflicts with recent repeated behavior:

ask:

"You've been choosing Spotify recently. Should I make it your default?"

Do not silently rewrite high-confidence explicit preferences.

---

# 22. MEMORY CONTROL

Owner must be able to say:

"What do you remember about Rahul?"

"What have you learned about me?"

"Forget that."

"Forget what I said about X."

"Never remember activity from banking apps."

"Don't remember anything for the next hour."

"Private mode."

Implement:

Private Mode:
- no new long-term memories;
- no habit learning;
- temporary task context only;
- no unnecessary transcript retention.

Provide UI to inspect/delete/edit memory.

---

# 23. LOCAL DATA SECURITY

Everything remains local.

Do not add telemetry.

Do not upload diagnostics.

Do not upload conversations.

Sensitive stored values should be encrypted using keys protected by Android Keystore.

Use AES-GCM or another appropriate authenticated encryption primitive available through standard Android/Java cryptography.

Never hard-code encryption keys.

Protect:

- sensitive memories;
- speaker data;
- authorization rules;
- private task details.

Use Room for structured storage.

Encrypt sensitive fields before Room persistence where needed.

---

# 24. PERSONALITY

AIVORA should gradually adapt to communication style.

Store preferences such as:

- response length;
- formality;
- commonly used phrases;
- preferred language/style;
- preferred apps;
- whether explanations are wanted;
- frequency of proactive suggestions;
- humor preference if explicitly learned.

Example:

Owner:
"Don't explain every little thing. Just tell me when it's done."

Future responses should become shorter.

The assistant may be warm, familiar and supportive.

Do NOT make it falsely claim:
- consciousness;
- human emotions;
- human identity.

It should feel familiar because it remembers and understands the owner, not because it lies about being human.

---

# 25. ACTION EXECUTION ENGINE

Create:

ActionRegistry
ActionExecutor<T>

Executors should exist for common tasks.

Prioritize:

- flashlight;
- volume;
- brightness;
- DND where authorized;
- app launching;
- calls;
- SMS where permitted;
- notifications;
- media control;
- alarms;
- reminders;
- files;
- contacts;
- camera launch;
- Bluetooth operations;
- connectivity;
- sharing;
- browser;
- scheduled tasks.

Every execution returns structured status:

SUCCESS
FAILED
PARTIAL
REQUIRES_USER_INTERACTION
NOT_SUPPORTED
PERMISSION_MISSING

plus useful error details.

---

# 26. UI AUTOMATION

Create an AccessibilityService-based automation layer for operations lacking direct APIs.

Rules:

1. Prefer official Android API/Intent first.
2. Prefer app-supported deep links/share intents second.
3. Accessibility automation is fallback.
4. Never use fixed screen pixel coordinates unless absolutely unavoidable.
5. Prefer:
   - resource id;
   - text;
   - content description;
   - class;
   - accessibility tree relationships.
6. Every step must verify expected UI state.
7. Timeouts must produce controlled failure.
8. Never randomly tap if state differs from expectation.
9. Keep app-specific logic in adapters.

Example:

AppAutomationAdapter

WhatsAppAutomationAdapter
YouTubeAutomationAdapter
etc.

If UI changes break an adapter:

stop safely and report inability.

Do NOT continue guessing.

---

# 27. WHATSAPP / MESSAGING

Implement a layered strategy.

Preferred:

supported Android intents/share mechanisms.

Fallback:

Accessibility adapter.

Example:

"Message Rahul on WhatsApp that I'll be late."

Pipeline:

resolve Rahul
→ resolve phone/contact
→ open appropriate conversation
→ populate message
→ send according to authorization policy
→ verify UI result where possible.

Do not assume WhatsApp UI structure never changes.

Keep selectors centralized in the adapter.

---

# 28. SCHEDULER / FUTURE TASKS

Create:

AIVORAScheduler

Support:

- reminders;
- alarms;
- one-time tasks;
- recurring tasks;
- conditional tasks where Android allows;
- network-dependent tasks.

Use appropriate Android mechanisms.

Use WorkManager for deferrable persistent work.

Use AlarmManager only where timing semantics require it.

Persist task definitions in Room.

Tasks survive:

- app process death;
- device restart when Android permits;
- application UI closure.

After reboot, restore/reschedule persistent AIVORA tasks appropriately.

Do not blindly use exact alarms for everything.

---

# 29. SCHEDULED ACTION SECURITY

Example:

"Tomorrow at 9 send Rahul FinalReport.pdf."

At creation time:

store:
- action;
- target;
- file identity;
- schedule;
- authorization;
- network requirement.

Before execution verify:

- target still exists;
- file still exists;
- permission remains valid;
- task has not been canceled;
- network requirements.

If execution would become materially different from what the owner authorized:

do not execute automatically.

Ask.

---

# 30. SCREEN LOCK / DEVICE SECURITY

AIVORA must respect Android lock-screen security.

Do not attempt to bypass PIN/password/biometrics.

If a scheduled task requires an unlocked screen and Android does not legitimately permit it:

queue task;
notify owner;
resume safely after unlock if appropriate.

---

# 31. ONBOARDING

First-run setup should feel polished.

Flow:

1. Welcome
2. explain offline/private design
3. assistant name
4. microphone permission
5. assistant-role setup
6. notification access
7. accessibility setup
8. contact/call permissions if requested
9. scheduler/alarm permissions if needed
10. battery/background setup where relevant
11. voice enrollment
12. speaker enrollment
13. destructive-action security preference
14. internet behavior preference
15. short test conversation
16. readiness screen

Do not request every possible permission blindly.

Explain why each permission is required.

Request permissions progressively where practical.

The user should always know what AIVORA can currently control.

Create a:

Capability Dashboard

showing:

Available
Missing Permission
Unsupported
Root Required
System Privilege Required

---

# 32. DEVICE CAPABILITY DETECTION

Create:

DeviceCapabilityManager

Detect things such as:

- Assistant role availability
- Accessibility enabled
- notification listener enabled
- root available
- system privilege available
- telephony
- Bluetooth
- camera
- biometrics
- exact alarm capability
- required permissions
- connectivity capabilities

Never rely on assumptions.

---

# 33. COMMAND CONTEXT

DialogueManager must understand follow-ups.

Example:

Owner:
"Find my latest internship PDF."

AIVORA:
"I found InternshipReportFinal.pdf."

Owner:
"Send it to Rahul."

"It" must refer to the retrieved file.

Working context should include:

- recent entities;
- current task;
- previous assistant result;
- unresolved questions;
- active project/context;
- conversation state.

---

# 34. INTERRUPTION

Owner should be able to interrupt speech.

If AIVORA is talking and wake/interrupt speech is detected:

stop TTS quickly;
listen.

Support commands such as:

"Stop."
"Cancel."
"Never mind."

Cancellation should propagate through active task execution where safely possible.

---

# 35. PROACTIVE ASSISTANCE

Implement conservatively.

AIVORA can notice:

- low battery before an alarm;
- repeated missed routine;
- scheduled task approaching;
- network needed for queued action;
- recurring behavior.

Do not bombard the user.

Create a configurable:

ProactivityLevel

LOW
NORMAL
HIGH

Default NORMAL.

Proactive actions should normally suggest rather than perform consequential operations without authorization.

---

# 36. AUDIT LOG

Keep a local private audit log for actions.

For each significant action record:

- timestamp;
- initiating command;
- action type;
- result;
- authorization source;
- confirmation status.

Do NOT store sensitive content unnecessarily.

Allow owner to inspect:

"What did you do today?"

This is essential for trust.

---

# 37. UNDO / REVERSIBILITY

Where an Android action can reasonably be reversed, expose undo information.

Examples:

- moved file;
- changed volume;
- changed brightness;
- changed preference.

Destructive irreversible actions require stronger confirmation.

---

# 38. PROJECT STRUCTURE

Avoid unnecessary Gradle multi-module complexity initially.

Use one application module with clean package boundaries.

Suggested conceptual packages:

com.chronoit.aivora
├── voice
│   ├── audio
│   ├── wakeword
│   ├── speaker
│   ├── stt
│   └── tts
├── brain
│   ├── dialogue
│   ├── intent
│   ├── reasoning
│   ├── planning
│   └── clarification
├── memory
│   ├── working
│   ├── longterm
│   ├── habit
│   ├── procedure
│   └── retrieval
├── actions
│   ├── model
│   ├── executor
│   └── adapters
├── policy
├── security
├── scheduler
├── connectivity
├── automation
├── platform
├── privileged
├── data
├── ui
└── diagnostics

Split Gradle modules later only if build complexity justifies it.

---

# 39. NO GIANT GOD CLASSES

Avoid:

AIVORAManager.kt with thousands of lines.

Use focused interfaces and implementations.

Core interfaces should include concepts similar to:

WakeWordEngine
SpeakerVerifier
SpeechToTextEngine
TextToSpeechEngine
LocalReasoningEngine
DialogueManager
MemoryRepository
MemoryLearner
ClarificationManager
ActionPlanner
ActionPolicyEngine
ActionRegistry
AIVORAScheduler
ConnectivityOrchestrator
DeviceCapabilityManager
PrivilegeBridge

Keep interfaces small.

---

# 40. LOCAL MODEL POLICY

Do NOT waste development time trying to train competitive STT/TTS/LLM models from zero.

This application is a product-engineering project.

Use replaceable local inference backends.

Any third-party offline ML component must:

- run fully locally;
- be bundled/pinned;
- never phone home;
- never auto-download;
- have its network access disabled/not required;
- expose no cloud dependency;
- be isolated behind our interface.

Prefer one runtime that can cover multiple voice functions if doing so genuinely simplifies the build.

Do not add three large ML frameworks where one is enough.

Do not hardwire AIVORA business logic to a particular model.

---

# 41. MODEL FAILURE FALLBACK

If local reasoning model fails or is unavailable:

AIVORA must still support deterministic commands.

Core phone control cannot completely stop because the language model failed.

Architecture:

Fast Command Engine
+
Optional Local Reasoning Engine.

---

# 42. PERFORMANCE / BATTERY

Always-on mode must be engineered for battery efficiency.

Measure rather than guess.

Do not keep:
- full STT;
- large LLM;
- heavy database retrieval

running continuously.

Use stages:

low-power audio monitoring
→ wake detection
→ speaker check
→ STT
→ reasoning only after activation.

Avoid unnecessary wake locks.

Release audio and CPU resources correctly.

Add diagnostics for:

- wake detections;
- false wakes;
- STT latency;
- reasoning latency;
- action latency;
- memory retrieval latency;
- approximate battery impact.

Keep diagnostics local.

---

# 43. FAILURE HANDLING

Never respond "Done" unless execution succeeded.

Examples:

If WhatsApp failed to send:

"I couldn't send it because WhatsApp changed screens."

Not:

"Done."

If Wi-Fi cannot be enabled silently:

state the actual status and provide the legitimate one-step interaction.

Every executor must verify success where practical.

---

# 44. TESTING STRATEGY

Create unit tests for:

- intent parsing;
- deterministic commands;
- action validation;
- risk classification;
- permission rules;
- memory insertion;
- memory conflict resolution;
- clarification logic;
- vocabulary learning;
- habit confidence;
- task scheduling calculations;
- connectivity decision logic;
- structured reasoning output parsing.

Create fake executors for action tests.

Create instrumentation tests for:

- onboarding state;
- permission handling;
- Room migrations;
- process restart;
- scheduled task persistence;
- activity/service lifecycle;
- accessibility integration where testable.

Test scenarios:

1. fully offline;
2. internet enabled;
3. internet disabled;
4. permission denied;
5. permission revoked after setup;
6. app killed;
7. reboot;
8. battery saver;
9. screen locked;
10. unknown speaker;
11. low speaker confidence;
12. ambiguous command;
13. owner correction;
14. learned phrase used later;
15. conflicting new preference;
16. destructive command;
17. canceled scheduled task;
18. missing file at execution;
19. changed external-app UI;
20. local model unavailable.

Never proceed to final acceptance with failing core tests.

---

# 45. SECURITY TESTS

Specifically test that:

- another voice cannot easily issue sensitive actions;
- malformed local-model output cannot execute actions;
- arbitrary strings cannot become shell commands;
- memory database cannot expose plaintext sensitive values unnecessarily;
- destructive actions require proper confirmation;
- a vague permission does not become blanket authorization;
- accessibility automation stops when expected UI state is absent;
- network is not silently used by the AI core;
- private mode truly stops long-term learning.

---

# 46. ROOT / SYSTEM BRIDGE

Build root/system support as OPTIONAL.

The application must work without it.

Root bridge operations must be allow-listed.

Examples may include:

- connectivity operations;
- selected system settings;
- reboot only with explicit high-risk confirmation;
- approved package operations.

Do NOT provide a generic AI shell terminal.

Do NOT execute arbitrary LLM-produced shell commands.

All root operations require typed methods and policy checks.

---

# 47. INTERNET-REQUIRING USER TASKS

Core AIVORA stays offline.

However, if the owner explicitly asks something that inherently requires external internet content, AIVORA may use installed apps/browser or direct network functionality designed for that task.

Example:

"Open YouTube and play X."

Internet may be enabled and YouTube opened.

The assistant itself must not require an external AI service to interpret this command.

Do not introduce a proprietary backend just because an online task exists.

---

# 48. INITIAL COMMAND SET

Before sophisticated AI work, make these highly reliable:

- wake assistant;
- stop/cancel;
- change assistant name;
- flashlight;
- volume;
- brightness;
- media play/pause/next/previous;
- open app;
- call contact;
- create reminder;
- create alarm;
- read recent notifications;
- open settings;
- Bluetooth-related supported actions;
- connectivity workflow;
- search/open local file;
- share local file;
- basic WhatsApp/message workflow;
- create scheduled action;
- inspect tasks;
- cancel task;
- inspect memory;
- forget memory;
- private mode.

Accuracy of core commands is more important than hundreds of unreliable commands.

---

# 49. IMPLEMENTATION PHASES

Execute in this order.

Do NOT redesign between phases unless tests reveal a genuine architecture defect.

## Phase 1
Project foundation
- Gradle
- Compose
- Room
- DataStore
- Keystore utilities
- architecture
- capability manager
- logging
- tests

## Phase 2
Action model + policy engine
- typed actions
- action registry
- permission/risk policy
- fake executors
- unit tests

## Phase 3
Core Android actions
- apps
- flashlight
- volume
- brightness
- media
- calls
- alarms
- notifications
- files

## Phase 4
Assistant role + voice lifecycle
- VoiceInteractionService
- audio pipeline
- wake-name interface
- speaker interface

## Phase 5
Offline voice stack
- wake-name backend
- speaker verification
- STT
- TTS
- interruption

## Phase 6
Dialogue system
- deterministic command parser
- context
- clarification manager
- structured action planning

## Phase 7
Memory
- all memory types
- FTS
- vocabulary
- corrections
- relationships
- consolidation
- private mode

## Phase 8
Local reasoning model
- local model backend
- structured output
- validation
- complex multi-step requests

## Phase 9
Accessibility automation
- robust semantic automation framework
- WhatsApp adapter
- verification/failure detection

## Phase 10
Scheduler
- persistent tasks
- reboot restoration
- network requirements
- authorization persistence

## Phase 11
Connectivity orchestrator
- standard-mode fallback
- optional root/system bridge
- restore previous connectivity

## Phase 12
Habit/procedural learning
- pattern detection
- confirmation
- routine creation

## Phase 13
Onboarding and capability dashboard

## Phase 14
Performance/battery optimization

## Phase 15
Security/edge-case hardening

## Phase 16
Full end-to-end validation

Do not spend time on Phase 14-style visual polish while core phases remain broken.

---

# 50. ACCEPTANCE TESTS

Do not call the application complete until these behaviors work or are explicitly documented as Android/device limitations.

### Voice

- owner can choose assistant name;
- owner can rename assistant;
- assistant wakes from configured name;
- non-owner voice is rejected according to confidence/policy;
- owner can interrupt assistant speech.

### Offline

With Wi-Fi and mobile data OFF:

- wake works;
- STT works;
- TTS works;
- memory works;
- local reasoning works;
- flashlight works;
- volume works;
- local files work;
- reminders work;
- basic device commands work.

### Memory

Session 1:

Owner:
"When I say normal setup, I mean Bluetooth on, brightness 40%, sound on."

Close/restart application.

Session 2:

Owner:
"AIVORA, normal setup."

AIVORA remembers.

### Clarification

Unknown phrase:
AIVORA asks.

Owner explains.

Same phrase later:
AIVORA remembers without asking unnecessarily.

### Correction

Owner corrects preferred music app.

Future command follows corrected preference.

### Context

"Find FinalReport.pdf."

Then:

"Send it to Rahul."

"It" resolves correctly.

### Destructive safety

"Delete all my photos."

Must NOT execute immediately.

### Scheduling

"Tomorrow at 9 remind me to call Rahul."

Persists through process death/reboot within Android constraints.

### Internet

Internet OFF.

Owner requests internet-dependent task.

AIVORA recognizes network requirement and uses the configured connectivity policy.

### Memory privacy

Private mode enabled.

Conversation does not create new long-term memories.

### Failed automation

External app UI unexpected.

AIVORA stops safely instead of tapping random controls.

---

# 51. QUALITY BAR

The application must not be considered production-ready because:

"it compiled once."

Production-ready here means:

- structured architecture;
- clean error handling;
- tests;
- no obvious resource leaks;
- no uncontrolled coroutines;
- no unsafe arbitrary shell execution;
- no hidden cloud dependency;
- no false success responses;
- database migrations;
- process-death handling;
- permission revocation handling;
- safe accessibility behavior;
- encrypted sensitive data;
- memory controls;
- graceful unsupported-feature behavior.

---

# 52. WHEN YOU DISCOVER A LIMITATION

Do NOT hack around Android security.

Implement:

capability detection
+
best legitimate path
+
optional privileged implementation
+
clear fallback.

Record true platform limitations in KNOWN_LIMITATIONS.md.

Never tell the application user a capability exists when it does not.

---

# 53. UI DESIGN DIRECTION

Keep UI clean and minimal.

Primary screens:

Home / conversation
Tasks
Memory
Routines
Permissions / Capabilities
Settings

Home should emphasize voice.

Show assistant state:

IDLE
LISTENING
UNDERSTANDING
ASKING
PLANNING
WAITING_FOR_CONFIRMATION
EXECUTING
SPEAKING
ERROR

Do not clutter interface with developer/debug information.

Put technical diagnostics in a separate Developer screen.

---

# 54. FINAL DELIVERY REQUIREMENTS

Before presenting completion:

1. run clean build;
2. run tests;
3. inspect release build errors;
4. confirm no accidental internet AI dependency;
5. search codebase for:
   - API keys;
   - Firebase;
   - OpenAI;
   - Gemini;
   - Claude;
   - analytics SDKs;
   - unapproved network endpoints;
6. verify Room migrations;
7. verify permissions;
8. verify service declarations;
9. verify foreground-service requirements;
10. verify assistant-role flow;
11. verify release build;
12. update PROJECT_STATE.md;
13. update KNOWN_LIMITATIONS.md;
14. provide installation/testing instructions.

Final report to me must be concise.

Include:

- what works;
- what was tested;
- APK/build location;
- any capability requiring root/system privilege;
- any physical-device test still required;
- important known limitation.

Do not give me a long marketing explanation.

---

# 55. MOST IMPORTANT BEHAVIORAL RULE

AIVORA should become more useful the longer the owner uses it.

The loop is:

LISTEN
→ UNDERSTAND
→ if uncertain, ASK
→ OWNER EXPLAINS
→ LEARN
→ PERFORM
→ VERIFY
→ REMEMBER
→ USE THAT KNOWLEDGE NEXT TIME

AIVORA must not repeatedly ask for information already confidently taught by the owner.

AIVORA must also not falsely remember something that was never taught or reliably learned.

The target experience is:

Day 1:
AIVORA understands general commands.

Week 1:
AIVORA knows common phrases and preferences.

Month 1:
AIVORA knows routines, contacts, terminology and work patterns.

Long term:
AIVORA has useful shared history with the owner and increasingly requires less explanation.

All of this memory stays locally under the owner's control.

---

# 56. START NOW

Do the following immediately:

1. inspect the repository;
2. inspect existing Gradle/source files;
3. preserve any valid existing implementation;
4. create/update PROJECT_STATE.md with a concise baseline;
5. establish the project foundation if missing;
6. begin Phase 1;
7. compile and test;
8. continue phase-by-phase.

Do not return only a proposal.

Do not ask me to approve the architecture.

This specification IS the approved architecture.

Make conservative engineering choices.

Prefer correctness over unnecessary cleverness.

Prefer reliability over feature count.

Prefer measurable behavior over claims.

Prefer local/offline operation over convenience.

Prefer typed/validated actions over AI freedom.

Protect the owner from accidental/destructive operations.

Never allow the reasoning model to bypass the policy engine.

Continue building until the implementation is as complete as can reasonably be achieved in the current environment.

