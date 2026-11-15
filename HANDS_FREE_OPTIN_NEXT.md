# AIVORA — Voice opt-in for hands-free controls (15 November 2026)

Branch: `offline/hands-free-optin` (from `offline/model-download`, local only, not pushed)
Base: `VyasGaurav1/Aivora` main `c00ce09` (PR #1, #2, #3 merged)
Goal (BRO, 2026-11-12): screen-free, completely voice-based assistant that
handles all mobile tasks, complementing the Muse AI mobile app. Offline-first,
API key optional. Original Gnani repo untouched.

## 1. The gap (from SCREEN_FREE_NEXT.md §3.1)

Five controls defaulted off and were Settings switches only:

| Control | Setting | What it unlocks |
|---|---|---|
| Hands-free controls | `handsFreeControls` | Verified-voice commands, spoken approval, spoken choices, automatic wake |
| Direct calling | `directCalling` | Confirmed calls go to Telecom instead of the dialer |
| Direct SMS | `directSms` | Confirmed short SMS sent from AIVORA instead of a draft |
| Wi-Fi controls | `wifiControls` | Confirmed Wi-Fi change in the Android Internet panel |
| Mobile data controls | `mobileDataControls` | Confirmed mobile-data change in the Android Internet panel |

A user who cannot or will not touch the screen could not enable any of them.
Flipping the defaults on would remove opt-in protection for sensitive
actions, so this branch adds a voice opt-in flow instead of a default change.
Defaults are still OFF. No defaults were flipped here.

## 2. The flow (three gates, in order)

```
say "enable hands-free controls"      (exact request phrase)
  → AIVORA speaks the scope: what it unlocks, what still guards each use
  → pending request opens, expires in 60 seconds
say "yes, enable hands-free controls" (exact confirmation phrase, same control)
  → Android device-credential prompt opens (BiometricPrompt)
  → credential succeeds: setting flips, audit entry written, spoken "now on"
  → credential fails / cancelled: nothing changes, spoken "Cancelled"
```

Gate 1 — exact phrases. `brain/HandsFreeOptIn.kt` (`HandsFreeOptInPolicy`,
same pattern as `ModelDownloadPolicy`): only the listed phrases match.
A bare "yes", "enable it", "turn it on", "enable hands-free" (missing
"controls"), or a confirmation naming a different control never matches.
The request and the confirmation must name the same control; a
confirmation with no current request for that control is refused and the
pending request (if any, for another control) is left alone.

Gate 2 — owner enrolled. Every opt-in command is refused while no owner
voice profile is enrolled: "Enroll the owner voice in the Voice tab
first, then say 'enable …' again." Enrollment itself is still a Voice-tab
flow (three spoken samples + device credential, `VoiceSessionController`);
voice cannot enroll itself, and this branch does not change that.

Gate 3 — device authentication on enable. Enabling any of the five
controls needs Android device-credential authentication
(`MainActivity.authenticateOwner`, the same BiometricPrompt helper the
action-auth flow uses) before the setting flips. The ViewModel holds the
pending opt-in in `AssistantUiState.optInAuthentication`; a
`LaunchedEffect` in MainActivity launches the prompt once (in-flight
guard mirrors `authenticateAction`), and only
`completeOptInAuthentication()` flips the setting. Disabling needs the
same two exact phrases and owner enrollment, but no device credential:
turning a capability off is not a grant. A failed or cancelled prompt
changes nothing.

Per-control prerequisites, checked at request time (not at use time —
those guards still apply on every individual action):
- Direct calling: `CALL_PHONE` permission, else "Allow phone-call
  placement in Capabilities first…".
- Direct SMS: `SEND_SMS` + `READ_PHONE_STATE` permissions, else "Allow
  SMS sending and SIM verification in Capabilities first…".
- Hands-free / Wi-Fi / mobile data: no extra Android permission at
  opt-in time. At use time the existing guards still apply: one ready
  default SIM for calling/SMS, accessibility service for Wi-Fi/data
  panels, exact confirmation + device auth per action.

Reversible by voice and in Settings. Disabling uses the same two-phrase
flow ("disable …" / "yes, disable …"). The Settings switches still work
and are unchanged in behaviour — except every change is now audit-logged
(see §3). Saying "Cancel" clears a pending request. A pending request
expires after 60 seconds; confirming after expiry is refused.

Audit / logging. Every opt-in change — voice enable, voice disable, or
Settings switch — inserts an `AuditEvent` into the existing `audit_events`
table (`actionType` `OptIn:<CONTROL>`, result `ENABLED`/`DISABLED`,
`authorization` = `VERIFIED_VOICE`, `FOREGROUND_OWNER` or `SETTINGS`,
`confirmed` = true). No command text, phone number, or message body is
stored. The entries show in the existing Developer tab audit list, which
already observes that table. No schema change, no new table.

Status. Say "voice controls status" (also "what voice controls are
enabled") to hear all five on/off states. Settings shows the same status
line plus the exact phrases. The "What can you do?" answer now mentions
voice opt-in; the README hands-free section documents the flow.

## 3. What the entry points do — and do not — allow

| Entry point | Opt-in works? | Why |
|---|---|---|
| App open, mic button / typed command (`FOREGROUND_OWNER`) | Yes | Unlocked phone, owner present — the same bar as tapping a switch, plus two exact phrases, enrollment and device auth |
| Verified voice, hands-free already on (`VERIFIED_VOICE`) | Yes, for the other four controls | Speaker-verified command; same three gates |
| Verified voice, hands-free off | No for hands-free itself | `acceptVoiceCommand` only forwards verified commands while hands-free is on — so the hands-free request must come from the foreground path |
| Public wake session ("Hey Aivora", `startVoiceConversation`) | No | `acceptPublicWakeCommand` routes only memory/reminder/alarm commands to `submitCommand`; an opt-in phrase falls to the model classifier and is refused. Unauthenticated wake voice can never opt anything in |
| Phone locked | No | `submitCommand` returns early when the keyguard is locked |
| `submitCommand` hook | `HandsFreeOptInPolicy.match` runs next to the `ModelDownloadPolicy` hook, before question routing; `ChoiceReply` and `ConfirmAction` phrases are disjoint from the opt-in phrases, so no collision | |

## 4. Changes made on this branch (local only, not pushed)

| File | Change |
|---|---|
| `brain/HandsFreeOptIn.kt` (new) | `HandsFreeControl` (5 controls, labels, scope texts), `HandsFreeOptInPolicy` (exact-phrase match, 60 s pending, device-auth-on-enable rule, status text). Pure Kotlin, no Android imports. |
| `brain/HandsFreeOptInTest.kt` (new) | JVM tests: exact phrases, bare-yes/vague-phrase refusal, wrong-control confirm, 60 s expiry, enable-needs-auth / disable-does-not, scope and status text. |
| `ui/AssistantViewModel.kt` | Opt-in hook in `submitCommand`; `handleOptInCommand`, pending state, `completeOptInAuthentication` / `cancelOptInAuthentication`, `applyOptIn` + `logOptInChange`; Settings switch functions now log; `cancel()` clears pending opt-in; `AssistantUiState.optInAuthentication` added. |
| `ui/MainActivity.kt` | `LaunchedEffect(ui.optInAuthentication)` launches the BiometricPrompt once (in-flight guard); `onDestroy` cancels a pending opt-in auth; Settings tab shows opt-in status + exact phrases. |
| `brain/StarterCapabilities.kt` | "What can you do?" answer mentions voice opt-in and the status phrase. Model routing (`knownRoute`/`label`) unchanged. |
| `README.md` | Hands-free section documents the voice opt-in flow. |
| `HANDS_FREE_OPTIN_NEXT.md` | This file. |
| `PROJECT_STATE.md` + `KNOWN_LIMITATIONS.md` | Dated checkpoints for this branch. |

Nothing removed: no Gnani/Brave code touched, no secrets added, no defaults
flipped. No auto background execution, no incoming/end-call by voice, no
verified payments — all out of scope here, unchanged.

## 5. Deliberate decisions (recorded, not accidental)

1. **Enable needs device auth for all five controls**, including Wi-Fi /
   mobile data — a uniform rule, simpler to explain and test than a
   per-control auth table. Disabling needs none.
2. **Two phrases, not one.** The model-download flow uses one exact
   phrase to start a 429 MB download; an opt-in grants a standing
   capability, so it gets request + confirm. The confirmation phrase
   repeats the full control name — that is what makes a bare "yes"
   useless to an overheard or misheard turn.
3. **Switches keep working without device auth.** Changing that would
   alter existing UX and could lock a user out of turning a control off.
   Switch changes are now audit-logged, which is strictly more
   accountable than before; the voice path is stricter than the switch
   path, not looser, because voice can be overheard.
4. **Owner enrollment is a precondition, not a substitute for device
   auth.** Voice matching cannot guarantee liveness (already stated in
   Settings); the credential prompt is the proof of presence.
5. **Single pending slot.** Like `VoiceConfirmationGate`'s single
   captured confirmation, one pending opt-in at a time. A new request
   replaces the old one; a wrong-control confirmation refuses without
   clearing the pending request.

## 6. Phone / JDK test matrix — not run here

No JDK/SDK and no phone in this VM. Do not claim any row passes until
observed. Run `./gradlew testDebugUnitTest assembleDebug lintDebug` first
(JDK 21 + Android SDK 37), then on a physical phone (POCO priority),
fresh install, owner voice enrolled:

| # | Test | Pass if… |
|---|---|---|
| 1 | Enrolled owner, app open, say "enable hands-free controls", then "yes, enable hands-free controls", then device credential | Spoken scope, then prompt, then "now on"; Settings switch shows on; Developer audit shows `OptIn:HANDS_FREE ENABLED VERIFIED_VOICE/FOREGROUND_OWNER` |
| 2 | Say "enable direct calling", then "yes, enable hands-free controls" | Refused ("No enable request is waiting for hands-free controls"); direct-calling request still pending, confirmable within 60 s |
| 3 | Say "yes" / "enable it" / "yes, enable hands-free controls" with no pending request | No state change, no prompt; spoken refusal naming the request phrase |
| 4 | Request, wait 61 s, then confirm phrase | Refused as expired; no prompt; no state change |
| 5 | Request, confirm, then cancel the BiometricPrompt | Nothing changes; spoken "Cancelled… still off"; no audit entry |
| 6 | Request, confirm, then press Back / say "Cancel" before the prompt | Pending cleared; no prompt; no state change |
| 7 | Owner not enrolled: say "enable hands-free controls" | Refused with enrollment guidance; no pending, no prompt |
| 8 | Direct calling, CALL_PHONE permission revoked: say "enable direct calling" | Refused with Capabilities guidance; no pending |
| 9 | Hands-free on, verified voice: say "enable direct SMS", confirm, authenticate | Works from verified voice; audit authorization `VERIFIED_VOICE` |
| 10 | Public wake (locked phone, "Hey Aivora"): say "enable hands-free controls" | Refused / not routed; no pending, no prompt, no state change |
| 11 | Say "voice controls status" | Spoken on/off for all five, matching Settings |
| 12 | Say "disable hands-free controls", then "yes, disable hands-free controls" | Turns off with no device prompt; audit shows `DISABLED`; spoken approval stops working |
| 13 | Flip a switch in Settings | Change applies as before; Developer audit shows the change with authorization `SETTINGS` |
| 14 | Enable direct calling, then place a call by voice | Call still needs its own exact confirmation + device auth; opt-in alone places no call |
| 15 | Kill the app mid-pending, reopen, say the confirm phrase | Refused (pending is in-memory only); no state change |

## 7. What still needs a phone / JDK

- `./gradlew testDebugUnitTest assembleDebug lintDebug` (with and without
  `-PaivoraIncludeLocalReasoning=true`, after `git lfs pull`) — not
  runnable here. `HandsFreeOptInTest` needs the JVM suite to confirm it
  passes; brace/paren balance was checked by reading, which is not a
  compile check.
- All 15 rows of §6 on a physical phone.
- Voice enrollment by voice (not attempted: enrollment needs three
  spoken samples in the Voice tab today).
- Hands-free opt-in from a public wake session is deliberately refused
  (§3); if BRO wants that, it needs a separate verified-voice design,
  not a relaxation of this one.
