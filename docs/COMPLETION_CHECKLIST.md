# AIVORA completion checklist

## First release scope

### Current device checkpoint — 7 November 2026

Latest voice revision:

- [x] Priority 7: local current-reply context, targeted memory recall, persisted corrections reused with attribution and private-mode checks. Model-weight training remains separate/pending.
- [x] Priority 8: offline wake-word interruption during speech, actual playback stopping and self-echo checks on POCO. Say Hey Jarvis/Hey Aivora, wait for Go ahead, then speak.
- [x] Priority 3: natural reminder/alarm wording and follow-up slots; local-reference routing verified in ViewModel with zero web requests; ambiguous topics retain context.
- [x] Priority 5: precise-alarm access plus WorkManager fallback, reboot/clock-change recovery and cancellation safeguards. Screen-off/battery-saver speech and actual reboot recovery verified; test fixtures cleaned.
- [x] Priority 6: retrieved evidence and validated local summaries for every new web answer; independent-source ranking and direct causal answers. Three reviewed factual topic checks passed.
- [x] 251 unit tests, full lint and 14 acceptance checks passed (12 feature checks + battery/screen-off + actual reboot). Reboot staging is not counted as acceptance.
- [ ] Prolonged unattended OEM/Doze testing, arbitrary speech overlap, broader devices/languages, model-weight training and Clock ringing remain pending.

- [x] Wake handoff ticket preservation, cancelled-turn cleanup, bounded waits and transient reconnection retry.
- [x] Encrypted local conversation history (80 pairs), repeat/recent recall, pause/clear controls and persistent speech-speed preference.
- [x] Saved-memory readbacks and reminder speech use offline synthesis; stored history/feedback are excluded from online requests. Gnani live speech and explicit web questions remain authorized.
- [x] 243 unit tests, full lint and five targeted POCO checks pass (18.96 s), including disk reopen and three successive panel restarts.
- [ ] Prolonged live recurrent wake acceptance and missed-word/latency measurements.
- [ ] Reinforcement/fine-tuning of model weights; current implementation is local retrieval, saved preferences and feedback storage.

- [x] Clear Home/panel processing states and spoken listening/search/waiting cues; Gnani connection prepared before greeting.
- [x] Local capability answers; guarded model fallback routing and explicit unsupported-feature replies.
- [x] Updated release: 240 unit tests, full lint and two targeted POCO checks passed (32.454 s).
- [ ] Measure live missed words, response latency and progress-cue UX; full-duplex interruption remains pending.

- [x] Spoken reminders: persistent speech job and content-free completion journal; actual scheduled notification plus audio playback passed on POCO. Phone voice is a fallback if Gnani fails.
- [x] Web replies now use relevant extracted Brave context and a pinned local summarizer; no default raw search-result readout. Actual photosynthesis summary reviewed and audio playback completed.
- [x] Seven core device checks passed in the last full run (61.801 s), including recorded audio -> live Gnani STT -> Brave context -> local summary -> live Gnani TTS. Report: app/build/reports/spoken-core-verified-poco.txt.
- [x] 236 JVM tests, full application lint and signed build pass. Fixed PCM16 network-chunk alignment; arbitrary chunk-boundary regressions pass.
- [x] Two separate live microphone wake-to-panel/greeting cycles observed on 7 November after restoring the assistant binding. Wake re-arms on idle Home and after voice work.
- [x] 409.1 MiB model installed and verified separately; signed APK stays 230.8 MiB. Duplicate USB model staging file removed; notices bundled.
- [~] Assistant-role instrumentation cannot safely rebind inside the POCO test process; that conditional branch is not counted as passed. Live sessions were checked outside instrumentation.
- [ ] Full-duplex interruption while AIVORA speaks, alarm ringing, other-device/model provisioning, reboot/force-stop behavior and prolonged battery/OEM testing.

### Earlier checkpoints (historical; current status above)

- [x] Restored AIVORA's assistant binding after instrumentation; subsequent wake -> assistant session -> voice-panel ticket acceptance observed. Full spoken confirmation remains pending.

- [x] Latest signed release installed; 228 JVM tests and full debug lint pass.
- [x] Exact competition-reminder follow-up resolves tomorrow at 7 AM; relative seconds/minutes/days, retained date hints and contextual memory have regression coverage.
- [x] POCO device tests: encrypted-memory/privacy and contextual reminder check, actual WorkManager notification delivery, and live Brave web result. Three tests passed in 6.829 seconds; report: app/build/reports/core-release-poco-final.txt. Fixture notification was cleaned up.
- [x] Diagnosed saved Brave key: Answers is not in its plan (HTTP400 OPTION_NOT_IN_PLAN); Web Search works (HTTP200). Clear search-result fallback implemented, with specific key/plan/rate-limit messages.
- [x] Spoken completion detail followed by Anything else; No/Goodbye ends the loop. Clarification prompts remain separate from completion prompts.
- [~] Fresh Gnani wake stream observed after installation; final human reminder/search/conversation regression remains open.

- [x] Physical POCO regression accepted by user: “Hey Jarvis” wakes the corrected release; “set alarm” asks for time; “tomorrow morning 5 AM” is accepted. Phone recognizer, without a Gnani key. Alarm ringing and broader reliability are not covered by this confirmation.

- [x] Latest corrected production-signed release installed on POCO; 218 JVM tests pass and full debug lint passes. With no Gnani key saved, the phone logged the restored Google speech provider ready for wake listening. Human regression of this corrected build is pending.

- [x] User physically confirmed an alarm was saved after AIVORA asked for its missing time. Ringing remains open.
- [x] Provider priority is Gnani when a key is saved; phone recognizer otherwise, for wake and commands. Indian English phone output remains the no-key fallback.
- [x] Natural alarm input added: “tomorrow morning 5 AM,” “wake me up,” and date/period context across follow-ups; requests the Clock API cannot represent are clarified rather than silently shifted to another day.
- [~] Segmented audio-pipe experiment failed human wake acceptance and was removed. Restored the prior external phone wake recognizer; its platform tones remain a limitation. Gnani streaming provides a tone-free path when configured. No global mute is applied.

- [x] User confirmed working recognition and Hey Jarvis panel activation without a Gnani key (before the quiet-listening revision).
- [x] Alarm/reminder missing-field conversations added; five regression tests pass, 214 JVM tests total. Specific parser clarification is spoken instead of the generic capability prompt.
- [x] Essential setup separated from collapsed optional provider settings. Installed signed update; full debug lint passes.
- [~] Quiet background mode: API33+ passes microphone PCM into a segmented Android recognition session. POCO on-device en-IN session observed without five-second restarts; audible silence and live wake still await confirmation. API30–32 local keyword fallback remains unaccepted physically.
- [~] Clock received SET_ALARM during the current physical test; saved alarm time and ringing still require verification.

- [x] Gnani production-signed APK (230.8 MiB) updated successfully on the POCO without clearing app data. One-time phone key entry and physical acceptance remain pending.

- [x] Gnani Prisma v3.0 WebSocket STT and Timbre v3.0 TTS implemented, with masked key entry and Keystore-encrypted credential storage.
- [x] Automatic language detection plus all 10 supported speech locales exposed. Speech coverage does not imply multilingual reminder/alarm command parsing; that remains English.
- [x] Owner-key live provider checks: TTS audio returned successfully; REST and WebSocket STT correctly recognized a generated English wake/greeting fixture and detected en-IN.
- [x] Release build, 209 JVM tests, full debug lint and Android test compilation pass. Native bundled models retained for pending features.
- [ ] Save the key in the phone's Settings and verify live human speech, repeated wake, greeting and conversational task execution. API-fixture success does not close this gate.

Cloud wake sends microphone audio while enabled and consumes provider credits. Stop wake listening when it is not wanted. No claim has been made about remaining account credits or multilingual task execution.

- [x] Signed release built and updated on the USB-connected POCO; application data retained. APK: 230.1 MiB.
- [x] Dedicated offline keyword model bundled (5.8 MiB), replacing general speech transcription for background wake. Both generated wake phrases have passed a phone diagnostic; this is not a live speech acceptance test.
- [x] Phone microphone audio and assistant-session/panel launch observed in logs during a live test. The user still reported no visible/audible response, so the wake milestone remains open.
- [x] Indian English speech selected from the phone's installed offline voice (`en-IN-language`); playback completion observed. Bundled speech remains a fallback when that voice is unavailable.
- [x] Partial speech transcripts connected to Home and the voice panel. Release/JVM verification: 209 tests, zero failures/errors; Android test sources compile; all 18 pinned assets verify.
- [ ] Reliable human wake → visible panel → audible greeting → spoken hello/reply, repeated without touching the phone.
- [~] Reminder notification delivery and live web results verified by device tests; alarm creation confirmed by user. Alarm ringing and full spoken task acceptance remain open.

First release status: **3/3 core task paths are implemented**, with three current automated device checks passed and alarm creation confirmed by the user. Remaining acceptance covers the complete spoken flow after these fixes, alarm ringing, repeated background reliability and broader devices. The broader product estimate below is historical and does not imply production readiness.

- [~] Offline memory and reminders. Encrypted/contextual memory and real scheduled notification delivery pass current device tests. Natural competition/time follow-up has regression coverage; fresh human voice acceptance remains open.
- [~] Clock alarms. AIVORA requests the alarm from the installed clock app without asking it to show setup. Actual creation and ringing need device verification.
- [~] Spoken Brave web results. Current Search key works live; Answers is not included in its plan, so clearly labeled search excerpts are used. Ordinary questions and search-web phrasing route correctly in regression tests. Human spoken-search acceptance remains open.
- [~] Smaller APK. The standard debug APK is 235.3 MiB and omits the optional 409.1 MiB local reasoning model. Installed on the POCO. Release size and signing remain open.

All other feature goals are pending and outside the current release work.

Updated: 3 November 2026. Scope: docs/BUILD_SPEC.md plus docs/HANDS_FREE_REQUIREMENTS.md.

## How this checklist is updated

Update this file after each verified milestone. Record what changed, the verification result, and what still needs testing. Reassess the rough percentage when a major feature or device acceptance milestone closes.

## Rough progress

**Estimated implementation progress: 45–55% complete; 45–55% remains.** This is an engineering estimate of the complete requested product, not a count of files, tests, or elapsed development time. Unfinished integrations and physical acceptance can take longer than completed foundation work. Production readiness is not established.

Legend: [x] implemented within the stated scope; [~] partial; [ ] remaining. A checked item does not imply physical-device acceptance.

## Foundation and identity

- [x] Permanent AIVORA branding and com.chronoit.aivora Android identity.
- [x] Android 11/API30 minimum, without a phone-model installation restriction.
- [x] Separate configurable assistant/wake name, initially Aivora.
- [x] Kotlin/Compose application, settings, encrypted local storage and database migrations.
- [x] Bundled offline English speech/runtime assets; no cloud AI dependency.
- [~] Signed release delivery, installation/update acceptance and release hardening.

## Voice and daily hands-free use

- [x] Offline recognition and speech synthesis implementation, with native emulator tests.
- [x] Protected speaker enrollment and configurable wake-template implementation.
- [x] Shared microphone coordination and explicit background voice-service opt-in.
- [x] Assistant-role session integration and notification fallback.
- [~] Repeated foreground wake, voice approvals, cancellation and interrupted speech handling.
- [ ] Real-owner enrollment, wake accuracy, recognition accuracy and spoof/liveness evaluation.
- [ ] Reliable physical background operation, process death, battery and OEM acceptance.
- [ ] Fully voice-driven onboarding and everyday operation without touch-only gaps.

## Understanding and learning

- [x] Typed command parsing, numbers, spoken clock/duration forms and bounded follow-ups.
- [x] Contact/file clarification, pronouns, project-scoped aliases and explicit corrections.
- [x] Encrypted teaching/recall/deletion, explicit personal-fact questions/corrections, authenticated voice-requested fact deletion and private-mode learning controls.
- [x] Explicit taught phrases and policy-checked routines.
- [~] Conversation context and procedural memory beyond supported command forms.
- [~] Bundled offline answers and session follow-ups for short general questions. Broader dialogue, personal-memory retrieval, model-assisted action planning and physical performance acceptance remain.
- [ ] Rich relationship/entity memory, habit learning and memory consolidation/evolution.
- [ ] Conservative configurable proactive assistance.

## Security and execution

- [x] Allowlisted structured actions, source trust outside action payloads and audit before effects.
- [x] Fresh Android authentication and exact expiring confirmation for sensitive actions.
- [x] Lock, permission, target-change, cancellation and replay guards.
- [x] Encrypted memory/tasks and protected voice profiles.
- [~] Cancellation/interruption across all workflows and broad reversible-action support.
- [ ] Full physical security, permission-revocation and release threat-model acceptance.
- [ ] Optional root/system bridge with verified capability detection and acceptance.
- [ ] Voice-only secure phone unlocking: unavailable on standard Android; supported system authentication must still be used.

## Phone and application control

- [x] Basic device controls and supported system/app handoffs.
- [x] Granted-file catalog, search, aliases, open/share and access rechecks.
- [x] Contact lookup, exact-number review and dialer fallback.
- [~] Opt-in direct calling and requested speakerphone: emulator evidence only; restricted single-SIM path.
- [ ] Physical call/audio acceptance, incoming/end-call control, broader SIM/VoIP support.
- [~] YouTube search/video handoff, read-only playback status, scoped media play/pause, and exact-video and same-session evidence checks. Android 11 smoke verified missing access and unknown playback responses. Positive runtime playback acceptance pending. The emulator command for the public playback trial was rejected by automatic approval review.
- [ ] Verified end-to-end YouTube playback and broad semantic application control.
- [~] WhatsApp/Business draft handoff, discovery of up to five eligible conversation titles, and live notification reply implementation. Android 11 smoke checks covered the missing-access and empty-list states and restored the listener setting. Live reply dispatch and delivery still need device acceptance. Explicit SMS drafts retain draft-only intent.
- [~] Opt-in direct single-part SMS implementation with fresh authentication/confirmation and sent-result handling; encrypted persistent status and voice status queries implemented, with actual Android no-service failure callbacks verified on API30/35; successful submission/delivery and full process-death acceptance pending.
- [ ] Verified message Send/delivery and broader messaging workflows.
- [~] Exact merchant UPI payment-link review and Google Pay/PhonePe handoff.
- [ ] Real payment-app acceptance, named-person/QR flows and verified payment result. Payment-app authentication remains required.

## Connectivity

- [x] Task-bound online request review, opt-in policy and validated-network gating.
- [~] Wi-Fi controls on known Android11/35 system panels; actual emulator switching verified.
- [~] Mobile-data controls for a supported single ready default SIM; actual emulator switching verified.
- [ ] Broad OEM/localization/multi-SIM panels, network/password selection and restoration policy.
- [~] Offline scheduled-task connection preparation and post-connection review guards have combined API30/35 emulator evidence; physical acceptance and an initial intermittent timeout remain.

## Scheduling

- [x] Persistent encrypted reminders, cancellation, recurring intervals and approximate WorkManager recovery.
- [x] Explicit notification-denial/recovery and reboot emulator probes.
- [x] Saved single-action review with target/condition/due-occurrence rechecks.
- [x] Charging/validated-network conditions and recurring single-action safety.
- [x] Voice entry and numbered due-task selection, with occurrence-bound fresh confirmation and cancellation checks.
- [ ] Multi-action recurring plans, richer event triggers and carefully authorized automatic execution.
- [ ] Long-duration, force-stop, physical/OEM timing and battery acceptance.

## Setup, quality and delivery

- [x] First-launch setup, permission entry points, installed-app scan and capability diagnostics.
- [~] Polished progressive onboarding, readiness feedback and complete permission-denial recovery.
- [x] Debug/release assembly and lint gates; broad JVM and Android11/35 emulator coverage.
- [~] POCO X8 Pro Max physical testing (Android 16/API 36). Install, first-run UI, microphone grant and offline speech fixtures verified. Live owner recognition, wake and full task acceptance remain.
- [ ] OPPO K13 Turbo physical testing (firmware not yet established).
- [ ] Broader Android11+ device/locale/accessibility/performance/battery coverage.
- [ ] Release signing, update/data-preservation checks and complete specification acceptance.

## Verified baseline

Latest verified gates: **204 JVM tests, 54 Android11 tests, 56 Android15/API35 tests; debug/release builds and lint pass (0 errors, 13 warnings)**. Separate radio/calling/reboot probes are additional scoped evidence, not physical acceptance. Injected voice/authentication grants do not establish real recognition or credential success.

## Suggested completion order

1. Close remaining touch-only selection and review paths, including scheduled offline-to-online tasks.
2. Strengthen local reasoning, memory and natural conversation.
3. Complete messaging/media integrations with observable results, then payment-app acceptance.
4. Expand scheduling, connectivity and app adapters without weakening authorization.
5. Test on the owner's phones, fix voice/OEM/battery issues and complete release acceptance.























- [~] Connection status reports Android validation and the pending approval or connection stage without replacing the task. JVM regression covers approval preservation, readiness, and expiry. Debug and release APKs rebuilt and lint passed. Spoken and device acceptance pending.
- Android 11 emulator smoke verified the typed connection status response with no pending task. Pending-task UI and spoken acceptance remain.
- Android 11 emulator also verified typed status with Wi-Fi and mobile data disabled. Both radio settings were restored after the check.
- With Wi-Fi and mobile data disabled on the Android 11 emulator, the typed permission audit returned current access gaps and suggested settings commands. Both radios were re-enabled after the test.
- [~] Permission audit now offers an Assistant role settings command. JVM suite passed. Android settings handoff and spoken use remain to be verified.
- Android 11 emulator verified that the typed Assistant role settings command opens Default apps and exposes the Digital assistant app selector. Spoken setup remains pending.
- Android 11 regression suite passed all 54 applicable tests on the build with the Assistant role settings shortcut. AIVORA refreshes capabilities on resume.
- Android 15 regression suite passed all 56 tests on the build with the Assistant role settings shortcut.
- Permission audit now separates missing access, unavailable capabilities, and system privilege requirements. JVM suite passed; spoken and Android UI verification of the expanded report remain.
- Android 11 emulator verified the expanded permission audit in the app UI using typed input. Spoken verification remains open.
- Permission audit distinguishes device credential setup from unsupported features and offers an Android security settings shortcut. JVM suite passed. Settings handoff and spoken use still need verification.
- POCO X8 Pro Max (Android 16/API 36) passed USB debug APK installation and cold launch. Onboarding, spoken commands and device-specific behavior remain pending.
- POCO first-run UI verified over USB. The welcome screen shows Aivora as the initial assistant name and exposes permission review and Assistant role selection. Owner permission choices and voice enrollment are pending.
- First-run setup offers a microphone-only Android permission prompt without starting recording. JVM suite passed. Physical prompt and owner voice verification remain pending.
- Updated debug APK installed on the POCO and the microphone-only setup button was verified on screen. Android approval and spoken verification remain pending.
- POCO Android package state confirmed microphone permission granted by the user. Acoustic recognition remains pending.
- Typed security settings command verified on Android 11. Android displayed its Screen lock entry; no credential changed.
- First-run setup now displays the verified microphone grant using the live capability scan. JVM suite passed; updated UI verification pending.
- POCO setup displayed Microphone access granted after updating the APK with the existing Android permission. Acoustic testing remains open.
- Continue to AIVORA now appears before the detailed capability list in setup. JVM suite passed. POCO UI verification pending.
- Latest setup build installed and cold-launched on the POCO. The device has completed onboarding and shows Voice input. The reordered first-run layout was not exercised on this device because onboarding was already complete.
- Home now explains how to finish a manual voice recording while listening. JVM suite passed. Device capture remains pending.
- Manual voice guidance now describes automatic end-of-speech detection and the optional Finish recording control. Physical capture remains pending.
- Voice endpoint regression covers speech starting on the no-speech timeout boundary. JVM suite passed. Physical speech capture remains pending.
- Android 11 regression suite passed all 54 tests with the voice endpoint timeout fix.
- Manual voice input publishes only finalized recognition to the command editor. JVM suite passed. Physical voice verification remains open.
- Repeated identical finalized voice drafts now trigger separate editor updates. JVM suite passed. Spoken repetition on a physical device remains pending.
- POCO Android 16 passed the bundled local TTS test for non-silent PCM and voice/speed options. This tested synthesis, with physical microphone recognition still pending.
- POCO Android 16 passed native offline speech recognition on bundled sample audio without Android internet permission. Live microphone and wake activation remain unverified.
- POCO Android 16 passed the SpeechRecognizer round trip through AIVORA offline service using supplied PCM and the existing owner-granted microphone permission.
- POCO Android 16 passed the AudioTrack playback lifecycle test for local synthesized speech. Audible output was not independently observed.
- POCO offline reasoning probe returned an incomplete local answer after approximately 62 seconds. Initial inference did not pass, so cancellation and recovery were not reached.
- Offline reasoning now reports generation timeout and answer length limits separately. Debug APK and JVM suite passed. The POCO inference failure still needs a rerun and performance diagnosis.
- POCO rerun still returned the generic incomplete-answer result. Source now distinguishes a deadline reached inside token decoding from other decode failures. This follow-up needs build and device verification.
- POCO decode diagnostic rerun failed after 60.7 seconds. Native source now checks deadline expiry across the full request. Build and device verification of that follow-up remain open.
- ARM64 reasoning build now permits llama.cpp CPU weight repacking through its native backend selection. Debug APK built; POCO inference speed and compatibility remain to be measured.
- POCO ARM64 weight repacking probe result: java.lang.AssertionError: Unavailable(reason=The local model ran out of time. Try a shorter question. Phone commands remain available.) Time: 60.539 java.lang.AssertionError: Unavailable(reason=The local model ran out of time. Try a shorter question. Phone commands remain available.)
- Native offline reasoning reports which stage timed out: loading, prompt processing, or answer generation. Debug build and JVM tests passed. Physical stage diagnosis pending.
- POCO stage-aware probe confirmed the native deadline expired during prompt processing (60.6 seconds total). Model loading reached completion, and generation was not reached.
- Native reasoning prompt processing now uses a 32-token internal microbatch while retaining the existing context and request limits. Debug build passed. Performance effect on the POCO is unverified.
- POCO 32-token microbatch experiment still timed out during prompt processing (60.6 seconds). Source restored the previous 256-token microbatch. The phone still has the experimental APK.
- Offline reasoning probe now runs with MainActivity in the foreground to match interactive use and distinguish background scheduling effects. App and test APKs built; POCO rerun pending.
- POCO foreground reasoning still timed out during prompt processing (61.6 seconds including activity launch). Removed experimental CPU repacking from the native build after it failed to resolve the timeout. Debug APK and JVM suite passed. LiteRT-LM is an upstream candidate and has not been benchmarked or integrated.
- Shortened the fixed offline model prompt while preserving execution and memory constraints and the JSON grammar. All 204 JVM tests and debug build passed. POCO latency remains unverified.
- POCO Android 16 foreground offline reasoning probe passed after prompt shortening and removal of experimental CPU repacking. The complete probe took 6.644 seconds and verified answer 4, cancellation during native decoding, and a successful answer after cancellation. Broader question and spoken acceptance remain open.
- Broader POCO reasoning probe failed factual accuracy despite completing in 11.1 seconds. The lightning/thunder answer incorrectly attributed the delay to the Doppler effect. Arithmetic and cancellation recovery passed before this assertion.
- Offline model prompt now explicitly asks it to acknowledge uncertainty. Existing unverified-answer labeling and the factual accuracy assertion remain. JVM suite and debug build passed; effect on answer quality is unverified.
- POCO uncertainty-prompt rerun still failed factual accuracy (11.9 seconds total). The Doppler explanation remained incorrect. Prompt-only uncertainty wording did not resolve this fixture; broader accuracy requires a model or grounding change.
- Restored the shorter prompt after the uncertainty wording failed the POCO accuracy check. The existing JSON grammar permits only a reply object, so thinking mode needs a separate bounded-output design. JVM suite and debug build passed.
- Located the unpackaged Q8 comparison candidate under app/build/model-candidates and measured its local size (639446688 bytes) and SHA-256 (9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031). Physical quality and latency comparison pending.
- POCO Q8 comparison preflight verified available storage and debug app sandbox access. Production model preparation checks the bundled model pin. A separate comparison build is still required to exercise Q8 without replacing the installed model cache.
- Added a Q8 instrumentation comparison with pinned candidate hash and the same factual question. App/test builds and JVM suite passed. Candidate remains outside the app APK and device benchmark is pending.
- POCO Q8 comparison completed in 5.646 seconds including candidate hash and activity setup, but failed the same factual check. Q8 incorrectly stated lightning travels at the speed of sound. Temporary device files removed; bundled Q4 retained.
- Removed the completed Q8 comparison hook and temporary instrumentation case. Production uses only the pinned bundled model path. App/test APK builds and JVM tests passed. The Q4 factual accuracy check remains open.
- Settings now explains the offline model can make factual mistakes. Local document grounding has not been integrated. JVM suite passed.
- Offline follow-ups with missing or expired context now ask for the complete question before invoking inference. JVM suite passed; spoken verification remains open.
- Android 11 smoke confirmed fresh-session follow-up recovery was already handled by an earlier branch. Consolidated duplicate routing to show the clearer recovery message. JVM suite passed; revised wording still needs device verification.
- Android 11 emulator verified that a typed follow-up in a fresh session asks the user to repeat the full question.
- Connection status now reports Wi-Fi radio state separately from Android-validated internet access. JVM suite passed. Updated device and spoken verification remain open.
- Android 11 emulator verified the typed connection status includes both internet validation and Wi-Fi radio state.
- Android 11 typed connection status verified Wi-Fi off and no validated internet with both radios disabled. Both radios restored.
- Connection status can identify the validated default route as VPN, Wi-Fi, mobile data, or Ethernet. JVM suite passed. Device route reporting and spoken use remain to be verified.
- Android 11 emulator verified the typed connection status reports validated internet over Wi-Fi.
- Android 11 emulator verified typed connection status reports validated mobile data while Wi-Fi is off. Wi-Fi was restored.
- JVM regression verifies that a validated mobile route and Wi-Fi off are reported while the original task still requires its exact approval. Full JVM suite passed.
- After the Android Internet panel handoff finishes, AIVORA rechecks an approved matching task if foreground and validated. This covers validation arriving during the handoff. JVM suite passed; panel race verification remains open.
- Reconnection callbacks bind to the pending task token through the synchronized consume operation. JVM regression confirms a stale token cannot consume a newer approved task.
- Reconnect consumption now requires an explicit matching token at the API boundary. JVM regressions passed and the instrumentation APK compiled.
- Android 11 standard instrumentation suite passed all 54 tests with mandatory reconnect tokens. Dedicated end-to-end reconnect probe remains separate.
- Android 15 standard run had one voice-choice failure at an immediate capture-state assertion. The test now waits up to five seconds for capture cleanup and passed in isolation. Full rerun pending.
- Full Android 15 rerun passed all 56 standard tests after the voice-choice test was updated to wait for capture cleanup.
- Failed Android Internet panel handoff restores the retry control for the matching live approved task. JVM suite passed; device failure path unverified.
- Failed Internet panel handoff now offers a fresh spoken review for a still-live verified voice task when hands-free controls are enabled. JVM suite passed; failure-path device acceptance remains open.
- Automatic spoken connection review now checks the original task token before presenting the prompt. JVM suite passed; spoken device acceptance remains pending.

- [x] Removed the unused llama.cpp HTTP dependency from the workspace. Offline debug build succeeded for all four configured ABIs. Gradle reused existing JVM test results.
- [x] Latest debug APK updated on POCO X8 Pro Max without clearing app data. Three physical Android tests passed (3.848 s): bundled speech transcription and Android recognition provider discovery/PCM. Report: app/build/reports/poco-latest-offline-speech.txt. Live owner enrollment, acoustic wake and voice execution still need acceptance.
- [x] Voice tab now guides owner enrollment, wake enrollment, hands-free settings and a first connection status command. Offline debug build succeeded and the update installed on POCO. Live owner voice acceptance remains open.
- Voice setup now exposes Speak replies next to the first hands-free command so the owner can hear the result. Offline debug build passed. Live owner verification remains open.
- Starter build: voice and wake enrollment deferred by request. Onboarding points to offline tap-to-dictate and review/send. Offline debug build passed.
- Starter APK installed on POCO. Three offline speech and recognition-provider fixture tests passed. Live tap-to-dictate remains to be checked.
- Starter microphone now submits final offline speech after a tap on the unlocked foreground screen. Speaker enrollment is unnecessary. Spoken confirmation and connection approval still use on-screen review in this mode. Offline debug build and JVM suite passed. Live dictation and QR-to-PIN payment remain open.
- Payment preparation now rejects a stated rupee amount that differs from the fixed amount in a supplied merchant UPI link. Exact-match and one-paisa mismatch tests passed with the offline debug build. Camera scanning and physical payment-app PIN handoff remain open.
- POCO Android 16 passed three payment intent fixture tests, including exact 30.00 INR from parser to PhonePe intent and mismatch rejection. Tests inspect payloads only. Real scanner and PIN handoff acceptance remain open.
- Payment link commands now accept exact English whole-rupee amounts such as thirty rupees. Approximate and mismatched amounts are rejected. Offline JVM tests and debug build passed. Live speech and QR capture remain open.
- Merchant UPI links that omit the amount can now use an exact positive amount supplied in the command. Fixed amounts still require an exact match and malformed amounts refuse. JVM tests and offline debug build passed. QR capture and physical PIN handoff remain open.
- [~] Home can scan a merchant UPI QR with the bundled offline ZXing camera and stage an editable payment command. Offline debug build and JVM tests passed. Physical scanning, spoken amount carryover, and the payment app PIN screen remain unverified.
- [~] Foreground command "scan QR and send thirty rupees using PhonePe" opens the offline scanner and carries the amount and app into existing payment review. Offline debug build and JVM tests passed. Live camera, speech, and PIN screen remain open.
- POCO accepted and launched the latest QR build. Home displayed Scan payment QR. HyperOS rejected ADB input injection, so scanner launch, real QR capture, and spoken amount carryover still need an on-device interaction.
- POCO passed four Android payment tests. A synthetic scanner result with a conflicting amount was rejected before authentication or confirmation. Physical camera and live voice acceptance remain open.
- Cancelling a pending QR command clears the spoken amount and app. POCO payment fixture tests passed, including a later scan staged as a manual draft.
- First-run permission review offers camera access for payment QR scans even on devices with a camera and no flash. Offline debug build passed.
- POCO passed five payment tests, including offline decoding of a generated merchant QR image and validation of all payment fields. Live camera capture and payment app PIN handoff remain open.
- QR command accepts compact amounts such as 30rs and rejects zero before opening the scanner. Offline app and test APKs built and JVM tests passed. Updated Android fixture awaits a device run.
- POCO passed all five payment tests with the compact 30rs command. The test supplied a conflicting QR result at the scanner callback and observed rejection. Live microphone and camera remain open.
- QR scan now reports when no code was read or the scanner cannot open, and clears the pending amount. Offline debug build passed.
- Starter tap-to-speak can approve the exact online task present at microphone activation by saying Connect for this task. Changed or expired tasks refuse. Offline debug build and JVM suite passed. Live speech and radio routing acceptance remain open.
- The online task dialog now exposes Voice input in starter mode so the user can record approval while reviewing the exact task. Offline debug build passed. Device acceptance remains open.
- Connection voice review carries the task token through the Android microphone permission prompt and rejects a changed task before recording. Offline debug build and JVM tests passed.
- POCO Android regression passed: a stale connection review token was refused before starting microphone capture.
- Starter connection dialog can read the pending task aloud with bundled speech before tap-to-speak approval. Offline debug build passed. Live review and connectivity remain open.
- Starter connection review now reads the exact task and captures a reply after synthesis in one interaction. This capture accepts connection approval or cancellation only. Offline debug build and JVM tests passed. Live acceptance remains open.
- Connection readback rechecks foreground, lock state and the exact pending task before recording. Offline debug build and JVM tests passed. Live audio acceptance remains open.
- Starter action confirmation now offers offline readback of the exact pending action. Screen confirmation remains required in starter mode. Offline debug build and JVM tests passed.
- Latest starter build installed on POCO and passed nine targeted Android checks for offline speech, QR/payment payload and stale connection review. Report: app/build/reports/poco-starter-regression.txt. Live capture and app handoffs remain open.
- Setup and Capabilities distinguish QR camera access from Google Pay and PhonePe app visibility. Offline build and JVM tests passed. Visibility does not establish payment readiness.
- Check permissions now reports missing permissions separately from setup needs such as missing payment apps, and only suggests screen-lock setup when the device credential is missing. Offline build and JVM tests passed.
- Starter contact review accepts a spoken numbered choice scoped to the contact list shown at activation. Offline build and JVM tests passed. Live speech acceptance is open.
- Starter contact review reads numbered choices aloud and accepts spoken selection, cancellation and page navigation. Offline debug build and JVM tests passed. Live capture remains open.
- Android assistant shortcut can start starter offline command capture without enrollment after unlock. Background wake retains its verified capture path. Offline debug build and JVM tests passed. OEM shortcut and live audio need physical acceptance.
- Latest assistant shortcut build installed on POCO. AIVORA was not the selected assistant and the non-holder test passed. Default role was left unchanged. Selected-role shortcut and live speech still need verification.
- Starter assistant shortcut now plays an offline command cue before recording and rechecks foreground and unlock state. Offline debug build and JVM tests passed. Selected-role physical acceptance remains open.
- Starter Android assistant shortcut can read and answer a pending contact or connection review, retaining that exact review across microphone permission. Offline debug build and JVM tests passed. Live shortcut verification remains open.
- Android assistant shortcut can read back and accept a spoken confirmation for a single pending message draft. Actual sends and payment approvals retain existing checks. Offline debug build and JVM tests passed. Live shortcut acceptance remains open.
- First-run setup explains the Android assistant shortcut and offers Speak replies for offline spoken results. Offline debug build passed.
- Android microphone permission results refresh capabilities and denied voice capture shows recovery guidance. Offline debug build and JVM tests passed.
- Setup now offers the existing Wi-Fi control opt-in alongside Android app-control access. Offline debug build passed. Supported Wi-Fi switching still needs physical acceptance.
- Android assistant shortcut accepts a spoken Wi-Fi confirmation only when it belongs to the same approved online task and current registry. Offline build and JVM tests passed. Live Wi-Fi activation and task resumption remain open.
- After a verified radio switch, AIVORA waits for validated internet without reopening the approval dialog. Unsuccessful switching still offers the connection review. Offline build and JVM tests passed; physical resumption remains open.
- An approved online task can now report a timeout when it remains pending until its deadline. Expiry is scoped to the exact task token. Offline build and JVM tests passed.
- JVM regression verifies an old approval timeout leaves a replacement online task pending and an expired task cannot resume.
- Consolidated starter gate passed offline debug assembly, 207 JVM tests and debug lint (0 errors, 13 warnings). Live device acceptance remains open.
- Brave web answer build installed on POCO. Three bundled speech and Android recognition provider tests passed. Live Brave API access remains unverified.
- Latest three-task debug build installed on POCO. Brave Answers uses an encrypted owner-entered API key. Ordinary answers stay out of the conversation display and explicit suggestions open a dialog. Live provider and Clock acceptance remain open.
- First-release UI now exposes only Speak, Reminders and required settings. First launch requests microphone and notification permissions. Custom wake enrollment and assistant role are available in Settings. Wake launches a dedicated translucent panel. Offline build, JVM tests and debug lint passed. POCO install was canceled by Android, so live wake is still open.
- Signed the 225.5 MiB release APK with the new AIVORA production key and verified the APK signature and 16 KiB ZIP alignment. POCO rejected the USB install with INSTALL_FAILED_USER_RESTRICTED. Production device acceptance remains open.
- POCO had the AIVORA assistant role and running wake service. Updated background wake to open the panel on a wake-name match and verify the subsequent command separately. Added spoken greeting. Signed release update installed and JVM tests passed. Live wake-name matching remains unverified.
- Wake setup and the wake panel no longer require a speaker profile. Wake commands are limited to reminders, alarms and web answers. Release build and JVM suite passed. Signed update installed on POCO. Wake detection across different voices still needs live acceptance.
- Added offline transcript fallback for short wake utterances and a detection marker without speech content. Exact-name matching regression and release build passed. Installed signed update on POCO. Live wake still requires user test.
- Current wake milestone uses default Aivora, no enrollment requirement, a Play test greeting button, and spoken What can I do for you after background activation. Command capture is deferred for this wake test. Signed release installed on POCO; build and JVM suite passed. Live wake and audible output still need user confirmation.
- Wake greeting test accepts Hey Aivora and Hey Jarvis. Exact transcript matching tests and release build passed. Signed update installed on POCO. Live acoustic detection and greeting remain unverified.
- POCO configured with AIVORA as default assistant, microphone/notification grants and Android battery-idle exemption. Signed continuous voice build installed. Session now greets, listens, speaks replies and listens again until Goodbye or silence. Release/JVM gates passed. PC-speaker wake attempts did not produce a wake event. Android blocked the separately signed instrumentation companion installation, so live end-to-end acceptance remains pending.
