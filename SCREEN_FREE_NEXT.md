# AIVORA — Screen-free next step (13 November 2026)

Branch: `offline/screen-free-next` (from `offline/aivora-first`, local only, not pushed)
Base: VyasGaurav7/Gnani-AI-Competition commit `97591c4`, plus `offline/aivora-first` commit `7db1a89`
Goal (BRO, 2026-11-12): screen-free, completely voice-based assistant that handles all mobile
tasks, complementing the Muse AI mobile app. Offline-first, API key optional.
Original Gnani repo untouched. Work for the new repo `VyasGaurav1/Aivora` (public fork,
same 7 commits, PR #1 already opened there for `offline/aivora-first`).

## 1. Voice coverage audit — what a user can do without the screen

Verified by reading the code in this clone (`app/src/main/java/com/chronoit/aivora/`),
`README.md`, `PROJECT_STATE.md` and `KNOWN_LIMITATIONS.md`. No device test was run here
(no JDK/SDK, no phone), so "voice-driven" below means the code path exists and is
deterministic — not that it passed a phone test.

Legend: **Voice** = start to finish by voice, no tap needed (except Android's own
credential prompt, which is a platform requirement). **Voice + screen** = voice starts
it, a screen step finishes it. **Screen-only** = no voice path exists today.

### Fully voice-driven (offline, no key, no network)

| Task | How it works in code | Evidence |
|---|---|---|
| Wake + conversation | Hey Aivora / Hey Jarvis, local KWS + phone recognizer fallback; `spokenReplies` now defaults true on fresh installs | `voice/KeywordWakeDetector.kt`, `platform/BackgroundWakeService.kt`, `data/AssistantSettings.kt:24` |
| Remember / recall / forget facts | Encrypted Room memory, deterministic parser, spoken readback; delete needs device auth + exact spoken confirmation | `memory/AivoraMemory.kt`, `brain/PersonalFact.kt` |
| Routines | Teach in plain words (`when I say usual setup, do …`), trigger by name; up to 20 actions joined by `then` | `brain/LearnedPhrase.kt` |
| Reminders (one-time + recurring) | Persistent encrypted definitions, AlarmManager exact-when-allowed + WorkManager fallback, spoken delivery | `scheduler/AIVORAScheduler.kt`, `scheduler/ReminderTiming.kt` |
| Scheduled actions | Stored encrypted typed action, reviewed fresh when due, never auto-runs from the scheduler worker | `scheduler/DueActionSelector.kt`, `policy/ActionPolicyEngine.kt` |
| Short questions (offline) | Local Qwen3 0.6B via llama.cpp, answers labelled unverified, never becomes an action | `brain/LlamaLocalReasoningEngine.kt` — **but see §2: model not in the starter build** |
| Volume / brightness | Spoken percentage, whole numbers 0–100 | README voice reference; `actions/ActionRegistry.kt` |
| Spoken choices / lists | Numbered options read 3 at a time, answer by number/ordinal; spoken approval needs the exact phrase, bare `yes` is never accepted | `voice/ChoiceReadback.kt`, `voice/VoiceConfirmationGate.kt` |
| Repeat / simplify / correct last answer | Local conversation memory, corrections saved encrypted and reused | `ui/AssistantViewModel.kt`, `memory/ConversationMemory.kt` |

### Voice + screen (voice starts it, the screen finishes it — by design or by Android)

| Task | Voice part | Screen part that remains | Why |
|---|---|---|---|
| Calls | Say `call Mom on speaker` → contact resolved, exact confirmation spoken | Dialer handoff by default; direct Telecom call only if `directCalling` is switched on in Settings + device auth + exact confirmation | `actions/DirectCallPlanner.kt` binds only an eligible single call to a verified default voice SIM; otherwise the dialer review is preserved. No real carrier connection is verified in code. |
| WhatsApp / SMS drafts | Say `message Rahul on WhatsApp that …` / `draft SMS …` → contact + exact body confirmed by voice | WhatsApp: click-to-chat draft opens, **user presses Send in WhatsApp**. SMS draft: review/send in messaging app | `automation/WhatsAppDraftAdapter.kt`: "Supported click-to-chat handoff only. Never presses Send." Direct SMS is opt-in only (`directSms`), single-part ≤160 chars, full international number, verified SMS SIM, device auth + exact confirmation (`actions/DirectSmsPlanner.kt`). |
| Notification replies | `reply to WhatsApp notification from Priya saying …` | Notification access must be granted in Android settings; reply needs auth + exact-text confirmation | `platform/AivoraNotificationListener.kt` |
| Alarms | `alarm seven thirty pm` → Clock intent with `EXTRA_SKIP_UI=true` | Ringing itself is the Clock app's; AIVORA only reports that the Clock request was made, not that it rang | `platform/AndroidActionExecutor.kt:102` |
| Files — find / open | `find FinalReport.pdf`, project nicknames (`blue one means …`) | **Granting folder/file access needs the Android SAF picker (touch)** — a platform requirement, no voice path. Sharing opens Android's share chooser | `data/LocalDocuments.kt` — persisted URI grants only; `ui/FilesScreen.kt` |
| Wi-Fi / mobile data | `turn Wi-Fi on` → confirmed action in the known Android Internet panel | User confirms in the Android panel; no network/password selection, no SIM switching, no silent change | `automation/WifiControls.kt`, `automation/MobileDataControls.kt` — scoped to known English SystemUI / Settings panels only |
| YouTube | `play calm music on YouTube`, `pause / resume YouTube`, `YouTube status` | Title requests open search **for the user to pick**; exact links request that video; playback verified only via media session | `automation/YouTubeAutomationAdapter.kt` — success only when YouTube's media session reports the state |
| Payments (UPI link review) | `review payment <link> using Google Pay` → field-by-field spoken review | Handoff to the payment app; bank-resolved recipient/amount reviewed **there**; no transfer, PIN, or completion verified | README Payments section; `actions/PaymentRequest.kt` |
| Permissions / assistant role / accessibility | `check permissions`, `open app permissions`, `app control settings` (voice commands that open the right screen) | The grant itself is a tap in Android settings; device credential entry is Android's own prompt, never voice | `ui/SetupScreen.kt` — "Android may require touch or device credentials… AIVORA cannot bypass your lock screen" |
| Hands-free controls, direct calling/SMS, Wi-Fi/mobile-data controls | — | **All default off** (`AssistantSettings.kt:27–31`) and can only be switched on with a Settings switch today. No voice command enables them. | This is the biggest screen-free onboarding gap (§3). |

### Needs a key and/or network (opt-in online only, never required for core)

| Task | Needs |
|---|---|
| Gnani cloud STT/TTS (10 languages) | Saved Gnani key in Settings; internet + credits; wake audio streams to Gnani while enabled. Without a key: Android recognition + bundled on-device TTS. |
| Brave web search / web answers | Saved Brave key; internet. Ordinary questions no longer require it (branch `offline/aivora-first`). Brave Answers plan is not on the current key — falls back to labelled Web Search excerpts. |
| YouTube search/playback | Internet + YouTube app; title search needs the user to pick. |
| WhatsApp / SMS delivery | The other app / carrier; AIVORA never claims delivery. |

### Not voice-driven at all today

- **App control beyond YouTube play/pause:** no generic app UI control, no arbitrary taps/gestures (deliberate boundary in `automation/AivoraAccessibilityService.kt`).
- **Automatic background execution** of scheduled actions, multi-action recurring plans, broader event triggers — every occurrence still waits for fresh review.
- **Incoming calls, end-call by voice, multi-SIM / VoIP / emergency calling** — unfinished per `KNOWN_LIMITATIONS.md`.
- **Payments:** review + handoff only; no verified transfer.
- **Opening / controlling arbitrary apps:** only launch + YouTube media-session controls are wired.

## 2. The two real blockers for "completely voice-based"

### (a) The capability answer lied to the user — fixed on this branch
`brain/StarterCapabilities.kt` answered "What can you do?" with:
"Calls, messages, payments and other phone controls aren't available in this release."
That contradicts the code: dialer calls, WhatsApp/SMS drafts, direct calling/SMS
(opt-in), YouTube controls and device controls all exist as deterministic paths.
The model-routing guard (`knownRoute("Can you send a message") == UNSUPPORTED`) is
correct — the *model* must never grant send/call — but the user-facing description
conflated model routing with the deterministic parser, which *can* draft them after
confirmation. A screen-free user asking what the assistant can do was told the
opposite of the truth.

**Fixed:** description now says what is voice-driven, what is draft/handoff-only
("I never press Send or place a call on my own"), and that direct calling/SMS need
to be switched on in Settings first. `StarterCapabilitiesTest` updated to match
(asserts `Clock`, `draft`, `offline`, `never press Send`); `knownRoute`/`label`
behaviour is unchanged.

### (b) The local reasoning model is not in the starter build — plan only, not implemented
- `app/src/localReasoning/assets/models/reasoning/qwen3-0.6b-q4_0.gguf` is a
  **134-byte Git LFS pointer** in this clone, not the 428,970,080-byte model
  (SHA-256 `da2572f1…`, pinned in `docs/LOCAL_REASONING_DESIGN.md` and
  `app/build.gradle.kts:verifyReasoningModel`).
- `app/build.gradle.kts:8`: the model is packaged only with
  `-PaivoraIncludeLocalReasoning=true`; the flag is off by default, and turning
  it on without the real file fails the build-time hash check.
- `LlamaLocalReasoningEngine.prepareModel()` already does the right thing at
  runtime: copy from assets *or* from a staged external copy, SHA-256-verify,
  atomic-move into private storage, needs >429 MB + 64 MB free and ≥768 MiB free
  RAM, otherwise returns an honest `Unavailable` — never a crash, never a silent
  web fallback. What is missing is getting the file onto the phone in the first
  place without a developer doing `git lfs pull` + a custom build.

**First-run model provisioning plan (not implemented yet — design only):**
1. On first offline question, if the model is absent, say so by voice and offer
   a one-time download: "The offline answer model is about 429 MB. Download it
   once over Wi-Fi?" — explicit spoken confirmation, Wi-Fi-only by default,
   resumable, with progress spoken at start/end only (not a running commentary).
2. Download from the pinned source (same revision as `LOCAL_REASONING_DESIGN.md`:
   ggml-org/Qwen3-0.6B-GGUF rev `13ac0662…`, Q4_0) into the same private
   `aivora_reasoning/` directory `prepareModel()` already uses, then run the
   existing SHA-256 verification before first use. Reuse `prepareModel()` —
   do not write a second copy/verify path.
3. On failure (no Wi-Fi, storage <493 MB, hash mismatch, cancelled): delete the
   partial file, say the model is not installed, and keep phone commands working.
   Never fall back to the web silently; never auto-download without confirmation.
4. Settings shows model status by voice too: "Offline model: installed / not
   installed / downloading". A "Remove offline model" voice command frees the
   429 MB.
5. Build-time path stays as-is for developers (`git lfs pull` + the flag);
   the in-app path is for users who install the starter APK. Do not bundle the
   429 MB model into the starter APK — it would make every install 400+ MB.
6. This needs a JDK/SDK build + a phone test before any claim: download on
   Wi-Fi, hash pass, first offline question answered, cancel mid-download,
   low-storage refusal, airplane-mode question after install.

## 3. Other screen-free gaps found (not fixed here — need design / phone work)

1. **Hands-free opt-ins are screen-only.** `handsFreeControls`, `directCalling`,
   `directSms`, `wifiControls`, `mobileDataControls` all default `false` and are
   toggled only by Settings switches (`MainActivity.kt:443–453`). A user who
   cannot / will not touch the screen cannot enable spoken approval, spoken
   choices, direct calls or direct SMS. A voice enrolment + spoken opt-in flow
   (with the existing device-auth gate) is the next design task — do not just
   flip the defaults on, or sensitive actions lose their opt-in protection.
2. **First setup is screen-heavy.** Assistant name (text field), permissions,
   assistant-role selection, notification/accessibility access, file grants —
   all need taps in Android settings. Voice can *open* the right screen
   (`check permissions`, `app control settings`), but cannot grant. Record this
   honestly in onboarding: "Some setup needs one touch; after that, everything
   is voice."
3. **Home hint was web-first.** StarterScreen's idle line read "Reminders,
   alarms, and web answers." — fixed on this branch to "Reminders, alarms, and
   offline answers. Web search is optional."
4. **WhatsApp "press Send" is still a tap.** `VoiceSessionController.kt:280`:
   after owner verification, an unverified-voice draft says "Review the
   transcription, then press Send". That is honest, but it is the single most
   common screen step in daily use. Full voice sending needs the authorised-Send
   design in `docs/MESSAGING_DESIGN.md` — not attempted here.
5. **Manifest declares INTERNET** (`AndroidManifest.xml:3`). Core offline flows
   do not use it unless the user opts in (Gnani/Brave/online task), but a
   screen-free user cannot see that. The offline/online labelling in Settings
   already says this; keep it, and do not claim "no internet permission".

## 4. Changes made on this branch (local only, not pushed)

| File | Change |
|---|---|
| `brain/StarterCapabilities.kt` | Capability description rewritten to the accurate scope above; model routing (`knownRoute`/`label`) unchanged. |
| `brain/StarterCapabilitiesTest.kt` | `capabilityDescriptionMatchesReleaseScope` asserts the new wording (`Clock`, `draft`, `offline`, `never press Send`). |
| `ui/StarterScreen.kt` | Home idle hint: "Reminders, alarms, and offline answers. Web search is optional." |
| `README.md` | Offline question answering: added that in-app first-run model download is planned, not yet implemented; today the model comes from `git lfs pull` + the full-offline build flag. |
| `PROJECT_STATE.md` | Dated checkpoint for this branch at the top. |
| `KNOWN_LIMITATIONS.md` | Dated note appended. |
| `SCREEN_FREE_NEXT.md` | This file: audit, provisioning plan, airplane-mode test matrix. |

Nothing removed: no Gnani/Brave code touched, no secrets added, no defaults flipped
except the wording above. No push, no merge, original Gnani repo untouched.

## 5. Screen-free test matrix — airplane mode, no keys, fresh install

Run on a machine with JDK 21 + Android SDK 37, then on a physical phone
(POCO priority). **Not run here** — no JDK/SDK and no phone in this VM.
Do not claim any row passes until it is observed on the phone.

| # | Test (airplane mode, no keys, fresh install) | Pass if… |
|---|---|---|
| 1 | Say "Hey Aivora", wait for greeting, say "What can you do?" | Spoken answer lists memory, reminders, alarms, offline questions, drafts + dialer — and does **not** say calls/messages aren't available |
| 2 | "Remember that my favourite colour is blue" → "What is my favourite colour?" | Spoken: blue. No screen touched. |
| 3 | "Remind me in 10 minutes to stretch" | Reminder saved by voice, spoken when due (approximate timing is expected) |
| 4 | "Alarm seven thirty pm" | Clock request confirmed by voice; ringing is the Clock app's — record, don't claim AIVORA rang |
| 5 | "When I say usual setup, do volume 30 then pause music" → "usual setup" | Routine runs by voice |
| 6 | "What is two plus two?" (model installed) | Spoken local answer, labelled unverified |
| 7 | Same question (model **not** installed) | Honest unavailable reply, never a key demand, never a web fallback |
| 8 | "What is the weather today?" (no Brave key) | Says online search is optional, no key demand |
| 9 | "Message Rahul on WhatsApp that I'll be late" | Draft prepared, exact app/recipient/body confirmed by voice, WhatsApp draft opens — **user still presses Send**; record that tap |
| 10 | "Call Mom on speaker" (direct calling off) | Dialer opens after exact confirmation; AIVORA does not claim the call connected |
| 11 | "Turn Wi-Fi on" | Android Internet panel opens, user confirms there; no silent change |
| 12 | "Play calm music on YouTube" | YouTube search opens for the user to pick; pause/resume verified via media session only if it reports it |
| 13 | Lock the phone, say "Hey Aivora" | Wake behaviour recorded honestly (foreground/background rules apply; do not claim background wake works if it didn't) |
| 14 | First offline question with no model | Voice offers the 429 MB one-time Wi-Fi download (once implemented); today, record that no such offer exists |
| 15 | With a Gnani key saved, then remove it | Cloud speech used while saved and labelled optional; clean fallback to on-device speech after removal, no error |

## 6. What still needs a phone / JDK

- `./gradlew testDebugUnitTest assembleDebug lintDebug` (with and without
  `-PaivoraIncludeLocalReasoning=true`, after `git lfs pull`) — not runnable here.
- All 15 rows of §5 on a physical phone, airplane mode, no keys.
- Hands-free opt-in by voice (design task, §3.1) and first-run model download
  (§2) — both need design + build + phone test; neither is claimed here.
- `StarterCapabilitiesTest` change needs the JVM suite to confirm it passes;
  brace/paren balance was checked by reading, which is not a compile check.

## 7. Next push (for the parent agent, not done here)

1. Push branch `offline/screen-free-next` to `VyasGaurav1/Aivora` (remote
   `https://github.com/VyasGaurav1/Aivora.git` — do **not** push to `origin`,
   which still points at `VyasGaurav7/Gnani-AI-Competition`).
2. Open a second PR against `VyasGaurav1/Aivora` `main` (after PR #1 for
   `offline/aivora-first`), describing the capability-answer fix and this audit.
3. Never open a PR against the original Gnani competition repo.
