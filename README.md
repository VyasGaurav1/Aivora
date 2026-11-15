# AIVORA

**An offline-first personal assistant for Android — talk to your phone, and it gets things done.**

AIVORA is a voice assistant that lives on your device. Ask it to remember things, find and share files, set reminders, schedule actions for later, draft and send messages, make calls, control Wi-Fi, mobile data, volume and brightness, play YouTube, and answer short questions — with a safety model built around review, authentication, and exact confirmation before anything consequential happens.

> [!WARNING]
> **Status: under construction.** AIVORA is in active development. Many capabilities are implemented and emulator-tested, but reliable live wake-word, conversation, and physical-device / OEM acceptance are still in progress. See [Project status and limitations](#project-status-and-limitations) before relying on it day to day.

| | |
|---|---|
| **Application ID** | `com.chronoit.aivora` |
| **Assistant name** | Configurable — defaults to `Aivora` |
| **Platform** | Android 11 (API 30) and later |
| **Build** | JDK 21 · Android SDK 37 |
| **Speech** | On-device by default; Gnani Prisma v3.0 STT · Timbre v3.0 TTS optional (API key, online only) |

## Contents

- [Why AIVORA](#why-aivora)
- [Features](#features)
- [Quick start](#quick-start)
- [Voice command reference](#voice-command-reference)
- [Speech and languages](#speech-and-languages)
- [How AIVORA keeps actions safe](#how-aivora-keeps-actions-safe)
- [Build from source](#build-from-source)
- [Testing](#testing)
- [Project status and limitations](#project-status-and-limitations)
- [Documentation](#documentation)

## Why AIVORA

Most assistants send everything to the cloud and act the moment you ask. AIVORA takes the opposite approach:

- **Offline first.** Personal facts, reminders, routines, and short question answering work on-device. Nothing needs the internet unless the task itself does.
- **Nothing runs silently.** Scheduled and sensitive actions are stored, surfaced when due, and wait for a fresh review, device authentication where required, and an exact confirmation.
- **Your data stays yours.** Task definitions, SMS records, and API keys are encrypted on-device. Keys are never embedded in the APK. Private mode stops new learning entirely.
- **It says what it actually knows.** A handoff to another app is reported as a handoff — not as a completed call, sent message, or finished playback — and unverified results are labelled unverified.

## Features

### Voice conversation

- Wake with **Hey Aivora** or **Hey Jarvis** — no voice enrollment is required for these release phrases — then pause for the greeting before speaking. Say **Goodbye** to end the conversation.
- Live transcripts appear on Home and in the voice panel as you speak.
- Optional protected owner and wake enrollment, foreground verified follow-up, and a user-started background wake service. Background activation uses Android's selected Assistant session, with a notification fallback.
- **Keep listening while open** keeps lightweight wake listening running between requests while the app is in the foreground. It pauses when the app is not resumed or the phone is locked, and stops on Stop / Cancel, enrollment changes, or disabling hands-free controls.

### Personal memory that works offline

- Say `remember that my favourite colour is blue`, then ask `what is my favourite colour?` Repeating the remember command with a new value corrects the subject and keeps the old record as history.
- Only explicit facts from the owner are used. Conflicts ask for a current value, unknown subjects are never guessed, and remembered text is never executed as a command.
- Deleting a subject — `forget my favourite colour` — first reviews the current and historical values and record count, then requires device authentication and a separate exact confirmation. Changed facts invalidate the review.
- Teach routines in plain words: `when I say usual setup, do volume 30 then pause music`. Repeat the teaching command to correct a routine, inspect or delete routines in Memory, and trigger one by saying its name.
- Private mode prevents saving new facts, routines, and references.

### Files and project vocabulary

- Grant access to local files or folders in Files, then try `find FinalReport.pdf` and `send it to Rahul`. AIVORA asks about ambiguous references and opens Android's share chooser for sharing.
- Give files project nicknames: `use project Atlas`, then `blue one means file FinalReport.pdf`. After that, `open file the blue one` or `send Rahul the blue one` reuses the selected file, subject to current access and action policy. Project selection lasts for the current app session.
- An unresolved share request can be explained in one reply — for example, after `send him the blue one`, reply `Rahul. In this project, blue one means FinalReport.pdf.` within two minutes and AIVORA resolves, learns, and confirms as normal.

### Reminders, scheduled actions, and conditions

- Persistent reminders such as `remind me in 10 minutes to stretch` and `remind me every 1 hour to drink water`, managed by voice or from the Reminders tab. Definitions are encrypted in `aivora_tasks.db`; WorkManager provides approximate delivery and restart recovery. Blocked deliveries stay pending rather than being reported as delivered, and notification permission is independent of starting background voice.
- Scheduled actions such as `in 10 minutes volume 30` or `tomorrow at 9 send Rahul FinalReport.pdf`. The target is resolved immediately, stored as an encrypted typed action, and reviewed when due — nothing runs from the scheduler worker.
- Conditions can be prefixed or selected in the form: `when charging, remind me in 10 minutes to stretch`, `when online, in 10 minutes open Browser`, or both together. Conditions apply at or after the due time; charging and validated network state are rechecked before delivery, review, and execution.
- Recurring actions such as `every 1 hour volume 30`. Every occurrence waits for a fresh review and never inherits a previous approval. Failed or unverified results pause the series for inspection.
- Files attached to scheduled actions are SHA-256 checked at scheduling, review, and execution (256 MiB maximum). A changed file requires a new task.

### Messaging, SMS, and calls

- **WhatsApp drafts:** `message Rahul on WhatsApp that I'll be late` (WhatsApp Business works too). AIVORA resolves the contact, confirms the exact app, recipient, and body, and opens the supported chat draft for you to review and send. Full international numbers are required — missing country codes are not guessed. The client must be installed; drafts use [WhatsApp's supported click-to-chat format](https://faq.whatsapp.com/5913398998672934), with no browser or other-app fallback.
- **SMS drafts:** `draft SMS to Rahul that Hello` always stays a draft, even when direct SMS is enabled. Review and sending happen in your messaging app.
- **Direct SMS (opt-in, off by default):** with SMS permission and SIM verification enabled, `text +12025550123 that Your short message` goes through exact recipient / body / SIM review, device authentication, and fresh confirmation. Encrypted send records are kept separately from learned memory — ask `SMS status` or `did my last SMS send` at any time. A reported submission is not a delivery guarantee, and uncertain results are never retried automatically.
- **Notification replies:** `reply to WhatsApp notification from Priya saying I'll be there at 6`, with notification access, authentication, and exact-text confirmation. Use `list WhatsApp reply notifications` to hear eligible conversations.
- **Calls:** with call placement enabled, `call +12025550123` or an unambiguous contact requests authentication and exact confirmation before handing off to system Telecom. `call Mom on speaker` keeps the speakerphone request through to the confirmation. Without direct calling, the same request becomes a confirmed **Open dialer** action — country codes are never inferred.

### Device controls

- Wi-Fi and mobile data on / off through confirmed actions in the known Android Internet panels — no network selection, password entry, SIM switching, or silent connectivity changes.
- Volume and brightness by spoken percentage: `set volume to twenty five percent`, `brightness ninety-nine percent`. Values are whole numbers from zero to one hundred.
- Spoken durations and clock times are understood throughout: `remind me in five minutes to stretch`, `alarm seven thirty pm`, `tomorrow at seven thirty pm volume twenty five`. With no AM/PM, hours use 24-hour time; ambiguous forms ask for clarification.
- Connectivity help for online tasks: AIVORA can ask, open Android controls, or request a confirmed Wi-Fi / mobile-data change, then wait up to one minute for validated connectivity before resuming the same plan. It never switches or restores connectivity silently in standard mode.

### YouTube

- `search for birds on YouTube`, `play calm music on YouTube`, or paste an exact video link such as `play https://youtu.be/<11-character-video-ID>`. Title requests open search for you to pick; exact links request that video.
- `pause YouTube`, `resume YouTube`, and `YouTube status` work against the current media session. Success is reported only when YouTube itself reports the requested state, with matching video identity for exact links; otherwise the result is an unverified handoff.
- A scoped accessibility fallback can operate known English play / pause controls when **AIVORA app controls** is enabled. It checks the package, control identity, and a fresh window, and refuses password screens and ambiguous controls. No arbitrary taps, gestures, or payment controls are exposed.

### Payments review (incomplete)

- `review payment <complete merchant UPI link> using Google Pay` or `using PhonePe` reviews an INR payment link field by field. AIVORA does not invent merchant metadata or verify bank identity, and authentication plus exact confirmation precede a package-pinned handoff to the payment app, where you review the bank-resolved recipient and amount.
- No transfer, payment-app acceptance, or completion has been verified yet. Saved merchant-link routines and schedules are refused.

### Offline question answering

- Short general questions can use the bundled offline reasoning model — try `What is two plus two?` with **Offline question answers** enabled in Settings.
- The first question needs space for a verified private copy of the 429 MB model, which is released when the answer finishes or is cancelled. Generated explanations are labelled unverified, and phone commands and personal-fact answers always use their dedicated paths.

## Quick start

1. Install and open AIVORA, then allow microphone and notification access.
2. In Android settings, choose AIVORA as your digital assistant app.
3. In AIVORA Settings, play the test greeting to check speech output. Replies prefer your phone's downloaded English (India) voice — install it under Android's Text-to-speech settings if needed.
4. Tap **Start wake listening**, keep the phone unlocked on Home, and say **Hey Aivora** or **Hey Jarvis**.
5. Pause for the greeting, then speak your request. Say **Goodbye** when you are done.

Handy checks at any time: say `check permissions` to hear which features need Android access, `open app permissions` to jump to AIVORA's permission screen, `open brightness permission` to review Modify system settings access, `app control settings` for the accessibility control, and `notification access settings` for notification access.

> [!NOTE]
> The permission scan reports direct Do Not Disturb control and exact reminder scheduling as unavailable. Clock alarms use the Android clock app, and reminders are delivered approximately.

## Voice command reference

| Category | Say something like |
|---|---|
| Memory | `remember that my favourite colour is blue` · `what is my favourite colour?` · `forget my favourite colour` |
| Routines | `when I say usual setup, do volume 30 then pause music` · `usual setup` |
| Files | `find FinalReport.pdf` · `send it to Rahul` · `open file the blue one` |
| Reminders | `remind me in 10 minutes to stretch` · `remind me every 1 hour to drink water` · `list reminders` · `cancel reminder <ID>` |
| Scheduled actions | `in 10 minutes volume 30` · `tomorrow at 9 send Rahul FinalReport.pdf` · `review due action` · `review next due action` · `review scheduled action <ID>` |
| Conditions | `when charging, remind me in 10 minutes to stretch` · `when online, in 10 minutes open Browser` |
| Choosing from a list | `the second contact` · `choose contact number two` · `the first file` · `next choices` · `previous choices` · `read choices` |
| Messages | `message Rahul on WhatsApp that I'll be late` · `draft SMS to Rahul that Hello` · `text +12025550123 that Your short message` · `SMS status` |
| Calls | `call +12025550123` · `call Mom on speaker` |
| Controls | `turn Wi-Fi on` · `turn mobile data off` · `set volume to twenty five percent` · `brightness ninety-nine percent` |
| YouTube | `play calm music on YouTube` · `pause YouTube` · `resume YouTube` · `YouTube status` |
| Alarms | `alarm seven thirty pm` · `alarm seven oh five am` |
| Payments | `review payment <complete merchant UPI link> using Google Pay` |
| Questions | `What is two plus two?` |

Durations can be spoken (`five minutes`, `sixty-five minutes`) — compound durations currently need a single unit. Recurring intervals and reminder repeats must be at least 15 minutes apart.

## Speech and languages

AIVORA works in two speech modes:

- **With a Gnani key saved in Settings:** Gnani Prisma v3.0 speech-to-text and Timbre v3.0 text-to-speech. Select **Automatic** to cover all 10 supported speech languages — Bengali, English (India), Gujarati, Hindi, Kannada, Malayalam, Marathi, Punjabi, Tamil, and Telugu. This route requires internet and credits, and wake listening streams microphone audio to Gnani until stopped. Transcripts appear after recognised speech segments.
- **Without a Gnani key:** Android system recognition with Indian English and bundled speech output is used instead.

Keys are encrypted on-device and never embedded in the APK. Task parsing for reminders and alarms currently uses English.

Hands-free controls are a separate, explicit opt-in in Settings. Once enabled, speech features expand:

- **Spoken approval.** A verified request that needs confirmation can read back a short, complete plan and listen once for `confirm the action I just reviewed`, `confirm action`, or `approve action` — a bare `yes` is never accepted. Approval is bound to the exact token and plan at recording start and expires with that request. Plans over 300 characters keep their scrollable on-screen review instead of being truncated.
- **Spoken choices.** Ambiguous contacts and files can be read as numbered options, three at a time, and answered by voice.
- **Spoken connection approval.** The Internet dialog can read a pending task aloud and accept `connect for this task`, tied to that task's original token, plan, and deadline. Connection approval never confirms or authenticates the underlying action.
- **Sensitive requests.** Requests that require device authentication open Android's credential prompt automatically while AIVORA is resumed and unlocked. A successful result returns to exact-action confirmation — it does not execute the action, and voice matching never unlocks the phone or replaces its credential.

## How AIVORA keeps actions safe

- **Review, then confirm, then act.** Every saved action requires a fresh, exact confirmation when due. Sensitive actions require credentials first. Old, cancelled, expired, or replaced request tokens cannot approve a different plan.
- **Re-checked at execution.** Targets, cancellation, file hashes, conditions, and the exact saved occurrence are all checked again immediately before execution.
- **Uncertain means uncertain.** Interrupted attempts stay visible with uncertain status and are never retried automatically — for SMS, scheduled actions, and everything else.
- **Private mode.** Blocks saving new facts, routines, and references. Explicit task definitions you create still persist, by design.
- **Bounded control.** Accessibility controls are scoped to known screens and controls only. AIVORA refuses password screens, unknown or ambiguous controls, and truncated accessibility trees, and exposes no arbitrary taps, gestures, or payment controls.
- **Honest reporting.** Payment reviews spell out every field, with a 1,200-character complete-prompt budget; anything larger, stopped, superseded, or with unsupported characters requires full on-screen review.

## Build from source

**Requirements:** JDK 21 and Android SDK 37.

Set `ANDROID_HOME` to the SDK directory, then run:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease lintDebug
```

**Full offline build (local question answering):** the starter build omits the
local reasoning model. Fetch it first with `git lfs pull` -- it is a Git LFS
file (`qwen3-0.6b-q4_0.gguf`, 428970080 bytes, SHA-256 verified at build
time) -- then build with `-PaivoraIncludeLocalReasoning=true`. Without the
model, short questions fall back to an honest unavailable reply; no key is
required for core use, and live web answers remain an opt-in Brave key.

The debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it on a connected device or emulator after a successful build:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Verify the bundled speech artifacts before working with them:

```powershell
tools/Verify-Models.ps1
```

## Testing

Run the Android integration tests with `ANDROID_HOME` set first:

```powershell
tools/Test-Android.ps1 -Serial emulator-5560
```

This launches instrumentation directly, because this environment's Gradle UTP launcher has a Dagger linkage error, and it fails if the actual instrumentation result does not pass.

**Emulator acceptance so far:** 39 applicable tests passed on API 30 and 41 passed on API 35. API 33-only recognition-provider tests are excluded from the API 30 counts.

### Disruptive emulator probes

These probes are excluded from the normal suite because they change external state. Run them only on an isolated emulator — never on a personal device.

| Probe | How to run | Reports |
|---|---|---|
| Scheduler reboot | Run `SchedulerRebootProbe` with `rebootPhase=prepare`, reboot the emulator, then run with `rebootPhase=verify`. Asserts an increased boot count and a posted reminder. | `app/build/reports/scheduler-reboot.txt` |
| Scheduler permissions | Run `SchedulerPermissionProbe` with `permissionPhase=denied` after revoking `POST_NOTIFICATIONS`, then `permissionPhase=restored` after granting it. | `app/build/reports/scheduler-permission-denied.txt`, `app/build/reports/scheduler-permission-restored.txt` |
| Wi-Fi settings | Invoke `WifiSettingsProbe` with `wifiProbe=true`. Temporarily changes radios and accessibility; verifies radio controls separately from internet validation. | `app/build/reports/wifi-settings-probe.txt`, `app/build/reports/wifi-settings-evidence.txt` |

Wi-Fi and mobile-data controls have also been probe-verified on both API 30 (Android 11 Settings Internet Connectivity panel) and API 35 (SystemUI dialog), including radio off / on and return to AIVORA. Test-harness restoration of mobile connectivity in these probes is not a production mobile-data feature.

## Project status and limitations

AIVORA is unfinished by design and honest about it. Current scope in detail lives in `PROJECT_STATE.md` and `KNOWN_LIMITATIONS.md`, with the full checklist in `docs/COMPLETION_CHECKLIST.md`.

**Still open:**

- Reliable live wake-word and conversation acceptance on physical devices, including owner-wake accuracy, interruption handling, echo, liveness, and battery behaviour
- Physical and OEM validation (including POCO and OPPO devices), and real voice, calling, and payment acceptance
- Automatic background execution of scheduled actions, multi-action recurring plans and routines, and broader event triggers
- Exact alarms and exact reminder scheduling — delivery is approximate today
- Full voice sending for WhatsApp — AIVORA prepares drafts only, and does not press Send or verify delivery
- Payment completion — review and handoff only; no transfer has been verified
- Unknown Wi-Fi / settings screens, locales, and generic Settings pages, which remain unsupported
- Verified modem / carrier SMS submission and delivery, and full process-death recovery for SMS records

**Deliberate boundaries, not bugs:**

- Direct SMS refuses non-single-part messages, local numbers without a country code, and sends without permission or SIM verification — those fall back to draft-only review
- Voice matching never unlocks the phone, and connection approval never substitutes for action confirmation or authentication
- A media or app handoff is never reported as a completed playback, call, or delivery

## Documentation

| Document | What it covers |
|---|---|
| `PROJECT_STATE.md` | Current scope and state of the project |
| `KNOWN_LIMITATIONS.md` | Known limitations and unfinished capabilities |
| `docs/COMPLETION_CHECKLIST.md` | Completion and acceptance checklist |
| `docs/FOREGROUND_VOICE_DESIGN.md` | Repeated foreground wake listening design |
| `docs/SPOKEN_CHOICES_DESIGN.md` | Spoken numbered-choice design |
| `docs/WIFI_CONTROLS_DESIGN.md` | Wi-Fi settings control design |
| `docs/NETWORK_VOICE_APPROVAL.md` | Spoken connection approval design |
| `docs/PAYMENT_HANDOFF_DESIGN.md` | UPI payment-link review and handoff design |
| `docs/LOCAL_REASONING_DESIGN.md` | Offline reasoning model backend design and provenance |

---

*AIVORA is under construction. Expect rough edges, expect honesty about them, and check the checklist above before depending on any single capability.*
