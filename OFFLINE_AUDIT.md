# AIVORA — Offline-first audit (13 November 2026)

Branch: `offline/aivora-first` (local only, not pushed, original repo untouched)
Base: VyasGaurav7/Gnani-AI-Competition, commit `97591c4` "Add AIVORA AI Android application" (2026-11-10)
Goal: screen-free, completely voice-based assistant that handles all mobile tasks, offline-first, API key optional.

## 1. Audit findings (verified by reading the code in this clone)

### Speech — already key-gated, but labelled as primary
- `voice/VoiceSessionController.kt`: `speakText` uses Gnani TTS only `if (!localOnly && gnani.configured())`, otherwise `IndianEnglishSpeechEngine` (on-device). `transcribe()` uses Gnani STT only when a key is saved, otherwise `AndroidSpeechInput`. Verified voice commands always use the bundled Sherpa STT (`SherpaSpeechToTextEngine`, `app/src/main/assets/models/stt-en-20m`). Background wake (`platform/BackgroundWakeService.kt`) uses Gnani only when configured, otherwise `AndroidSpeechInput`.
- Gap found: the UI called Gnani the "(primary)" voice provider. No code change was needed to the routing itself — key presence already is the opt-in.

### General questions — the real offline gap
- `ui/AssistantViewModel.kt` (old line ~573): ordinary questions were routed to live web search whenever `webSearchConfigured() || !localReasoning || model missing`. So a fresh user with no Brave key and no local model was sent to web search and told "Add a Brave Search API key" — no offline answer at all.
- `connectivity/SpokenWebAnswers.kt`: `answer()` returns null with no key; with a key it calls Brave, then summarises with the local model. Brave is already key-gated; nothing to remove.
- `data/AssistantSettings.kt`: `localReasoning` already defaults to `true`.

### Local models — speech is bundled, reasoning is not (in this clone)
- Present as real files (~120 MB total): `assets/models/stt-en-20m` (Sherpa Zipformer, encoder 42 MB), `tts-vctk` (VITS, 39 MB), `wake-en` (~6 MB), `speaker-en` (E-Res2Net, 26 MB).
- `app/src/localReasoning/assets/models/reasoning/qwen3-0.6b-q4_0.gguf` is a **Git LFS pointer (134 bytes)** in this clone, not the 429 MB model (oid sha256 `da2572f1…`, size 428970080). `git lfs` is not installed in this VM, so the real model could not be fetched here.
- `app/build.gradle.kts`: the model is packaged only when `-PaivoraIncludeLocalReasoning=true`; `gradle.properties` does not set it, so the default/starter build omits it. The existing `verifyReasoningModel` task hash-checks the model at build time — turning the flag on without the real file would fail the build.
- `brain/LlamaLocalReasoningEngine.kt`: `prepareModel()` copies + SHA-256-verifies the model into private storage on first use; a missing model ends in an honest `ReasoningResult.Unavailable` ("I couldn't produce a complete local answer… Phone commands remain available"), never a crash and never a silent web fallback.

### Manifest — a doc/code mismatch
- `app/src/main/AndroidManifest.xml` **does** declare `android.permission.INTERNET` (plus `ACCESS_NETWORK_STATE`). The historical note in `PROJECT_STATE.md` saying "No runtime internet permission" is outdated. Core offline flows do not use the network unless the user opts in to Gnani/Brave or approves an online task; the permission exists for those opt-in paths. Recorded in `PROJECT_STATE.md` and `KNOWN_LIMITATIONS.md`, not silently rewritten.

### Settings defaults — voice replies were off by default
- `AssistantSettings.spokenReplies` defaulted to `false`, so on a fresh install most replies were text-only — wrong for a screen-free assistant. Every instrumentation test that waits for `!spokenReplies` sets it explicitly to `false` first and restores the original, so changing the default does not rely on, or break, those tests' assumptions (verified by reading them; not run).

### Core flows — already offline-capable (by code reading, not device-tested)
Wake, memory remember/recall, reminders, alarms, routines, files and device controls are deterministic local paths (Room/WorkManager/AlarmManager, encrypted on-device). They do not call Gnani or Brave. No code change was needed for them; they still need the airplane-mode phone checks listed below.

## 2. Changes made on this branch (9 files, +49/−10)

| File | Change |
|---|---|
| `ui/AssistantViewModel.kt` | Ordinary questions go to web search **only** when a Brave key is saved. Otherwise they go to local reasoning (or an honest unavailable reply if the model is not installed). Explicit web searches still need a key; the message now says online search is optional. |
| `data/AssistantSettings.kt` | `spokenReplies` defaults to `true` (fresh installs speak replies). Stored values on existing installs are unchanged. |
| `ui/StarterScreen.kt` | Gnani labelled "online, optional" (was "primary"); no-key state reads "on-device (offline)". Web answers labelled optional. |
| `ui/MainActivity.kt` | Web answers labelled "Optional, online only". Settings now says short questions work offline. |
| `voice/VoiceSessionController.kt` | Comment only: Gnani is opt-in by saved key; on-device engines are the default otherwise. |
| `gradle.properties` | Comment only: how to build the full offline build (`git lfs pull`, then `-PaivoraIncludeLocalReasoning=true`). Flag deliberately NOT turned on here — the model file is absent from this clone. |
| `README.md` | Speech table row fixed (on-device by default; Gnani optional). Full-offline build note added. |
| `PROJECT_STATE.md` | New dated checkpoint at the top with the changes, the INTERNET-permission finding, and what is not verified. |
| `KNOWN_LIMITATIONS.md` | Dated note appended with the same scope. |

Nothing was removed: Gnani and Brave code are intact, gated behind saved keys. No secrets added. No repo was renamed here — the GitHub rename to `Aivora` is a separate browser step on the original repo, and the original repo was not touched. No push was made.

## 3. What was NOT run (state this plainly)
- No JVM tests, no lint, no Gradle build: this VM has **no JDK and no Android SDK** (`java` not found, `ANDROID_HOME` unset). The edits are small and brace/paren balance checks pass on all five edited Kotlin files, but that is not a compile check.
- No phone test: no POCO/device here. The routing and default changes need a real build + device run before any claim that offline-first works on device.

## 4. What still needs a phone test (before merging)
1. Build the full offline build: `git lfs pull`, then `./gradlew testDebugUnitTest assembleDebug lintDebug` with `-PaivoraIncludeLocalReasoning=true`, on a machine with JDK 21 + Android SDK 37.
2. Fresh install with **no keys, airplane mode on**: wake ("Hey Aivora"), remember/recall a fact, set a reminder, set an alarm, run a routine, find a file, ask a short question (expect a local unverified answer or an honest unavailable reply, never a key demand), and confirm replies are spoken.
3. With a Gnani key saved: confirm cloud speech is used and labelled optional; remove the key and confirm the app falls back to on-device speech with no error.
4. With a Brave key saved: confirm explicit web searches work; without it, confirm they say online search is optional.
5. Confirm the 429 MB first-use model copy works (storage + hash) on the test phone.

## 5. Blocked / next steps
- Update (2026-11-13 00:05 IST): the new remote now exists — `VyasGaurav1/Aivora`, a public fork of `VyasGaurav7/Gnani-AI-Competition`, verified here via README read (same SHA `b09e2a9c…`) and commit list (HEAD `97591c4`, same as this clone). The original competition repo was left untouched. The GitHub search index had not picked the new repo up yet at check time (0 results), so direct file/commit reads were used to verify it instead.
- This task's instruction was "do NOT push to GitHub yet", so nothing was pushed. Push is now one step: add remote `https://github.com/VyasGaurav1/Aivora.git` to this clone and push branch `offline/aivora-first` there — needs the parent's go-ahead, since the push itself was out of scope for this run.
- Blocked on: a machine with JDK 21 + Android SDK 37 to build/test (not available in this VM); a physical phone for the airplane-mode checks.
- Next: push the branch to `VyasGaurav1/Aivora`, run the build + tests above, then open a PR against the new repo's `main` — never against the original Gnani competition repo.
- Future (not done here): an explicit online/offline speech toggle in Settings (today key presence is the toggle); in-app first-run model provisioning instead of manual `git lfs pull` at build time.
