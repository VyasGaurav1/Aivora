# Scheduled connection preparation

Explicit foreground/voice review may prepare an online saved action while its saved network condition is unmet. This does not waive charging, target, due time, state, authentication or confirmation. Only actions whose typed definition requires internet can use this path; a condition-only offline action still waits for its saved network condition. Default scheduler review remains strict for existing callers.

The existing internet orchestrator retains the original scheduled registry/source and requests its normal task-bound connection approval. Its validated-network result is not sufficient for an unmetered saved condition. Therefore the scheduled registry revalidates exact occurrence, action, target and all saved conditions before presenting action authorization. The execution guard independently repeats these checks immediately before effects. Cancellation or another occurrence during connection setup cannot authorize a replacement occurrence. No automatic execution or implicit network restoration is introduced.

Verification: pure charging/network eligibility tests; registry guard rejection before approval; persisted scheduler tests for exact occurrence, conditions and cancellation; ordinary Android11/API35 suites. Existing radio probes establish switching separately; injected input is not physical recognition.

## Combined native probe

`ScheduledConnectionProbe` is explicitly excluded from ordinary instrumentation and requires `scheduledConnectionProbe=true` on API30/35 emulator hardware. Run only on the isolated Aivora_Test_API30/API35 AVDs. It stores an encrypted online task and makes its occurrence due while both radios are off, verifies default strict review refusal, and uses injected verified commands for the production foreground connection path. A real known-panel Wi-Fi helper must enable the radio. If Wi-Fi has no validated internet, the harness separately restores mobile data; this does not prove production mobile switching or Wi-Fi internet.

Cancellation during connection approval must reject the saved registry before presenting action authorization and must not request YouTube. A second task repeats offline setup and must present a separate exact saved-action confirmation after validated connectivity; only the fresh injected confirmation permits a HANDLED state and observed YouTube resumed activity. That is a search handoff, not playback. Task fixtures, app preferences, radios and accessibility configuration are restored in finally blocks.

One initial API30 run timed out at a 15-second wait before per-step diagnostics existed. Subsequent diagnostic and repeat runs passed; the initial timeout cause is unresolved. This is a reliability concern to retain for physical/long-run acceptance, not evidence of a fixed production fault.
