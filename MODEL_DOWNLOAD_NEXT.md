# AIVORA — First-run offline model download (15 November 2026)

Branch: `offline/model-download` (from `offline/screen-free-next`, local only, not pushed)
Base: VyasGaurav1/Aivora `main` at `23989f5` (PR #1 + PR #2 merged). Original
Gnani repo untouched.
Goal (BRO, 2026-11-12): screen-free, completely voice-based assistant that handles
all mobile tasks, complementing the Muse AI mobile app. Offline-first, API key optional.

## 1. What this step builds

The biggest gap from SCREEN_FREE_NEXT.md §2(b): the 429 MB Qwen3 0.6B model was a
Git LFS pointer only, with no way to get it onto a phone except a developer doing
`git lfs pull` + a custom build. This step adds the in-app first-run download —
plumbing, voice offer, Settings UI and status — and nothing else. It does not
flip any opt-in default (hands-free / direct calling / SMS / Wi-Fi controls all
stay off by default, as designed).

## 2. Verified fact that changed the design: the upstream filename

SCREEN_FREE_NEXT.md §2 named the repo and revision but not the file. Checking
the Hugging Face page for `ggml-org/Qwen3-0.6B-GGUF` at revision `13ac0662…`
on 2026-11-15 showed:

- The real upstream filename is **`Qwen3-0.6B-Q4_0.gguf`** (capital Q).
  The lowercase `qwen3-0.6b-q4_0.gguf` is only AIVORA's local name — the
  lowercase resolve URL 404s. A download built from the local name would
  have failed on every phone.
- Size shown on the page: 429 MB. SHA-256 shown on the page:
  `da2572f16c06133561ce56accaa822216f2391ef4d37fba427801cd6736417d4` —
  exactly the SHA already pinned in `LlamaLocalReasoningEngine` and in the
  build-time hash check. The pinned source is confirmed, not assumed.

Pinned download URL (one URL, one revision, one file — see `ModelDownloadPolicy`):
`https://huggingface.co/ggml-org/Qwen3-0.6B-GGUF/resolve/13ac06624948e4d8589cb4b6f90100837342fcb8/Qwen3-0.6B-Q4_0.gguf`

## 3. How it works

### The offer (honest unavailable, no silent start)
`LlamaLocalReasoningEngine.generate()` used to fall through to
`prepareModel()`, which threw on the missing asset, and the question got the
generic "I couldn't produce a complete local answer." Now, when no model
source exists at all (`hasModelSource()` — installed file, staged external
copy, completed download, or bundled asset), the reply is the honest
not-installed message: the model isn't installed yet, it's about 429 MB
downloaded once over Wi-Fi, say "yes, download the offline model" to start,
or download it from Settings. Phone commands still work. Nothing is
downloaded by asking the question — the question only offers.

### Starting it
- Voice: exact phrases only — "download the offline model",
  "yes, download the offline model", "install the offline model" (and the
  "yes, …" variants). A bare "yes", "download it", or "cancel" never matches
  (`ModelDownloadPolicy.match()`). The hook sits in `AssistantViewModel`
  before the question routing, so it works for typed and voice input.
- Settings: "Download offline model (429 MB, Wi-Fi only)" button, with
  Cancel while downloading and "Remove offline model" when installed.
  Status text comes from `ModelDownloadPolicy.statusLabel()`.

### The gates (pure, unit-tested in `ModelDownloadTest`)
`ModelDownloadPolicy.canStart()` refuses unless all three hold:
1. The connection is validated (`NetworkSnapshot.validated`).
2. The route is Wi-Fi — cellular, VPN and ethernet are refused.
3. Free space exceeds 429 MB + 64 MB headroom (the same threshold
   `prepareModel()` already uses).

### The download (`ModelDownloader`, `brain/ModelDownload.kt`)
- `HttpURLConnection` to the pinned URL only (redirects followed, 30 s
  connect / 60 s read timeouts). No new dependency.
- Streams in 1 MB chunks to `filesDir/aivora_reasoning/qwen3-0.6b-q4_0.download`,
  the same private directory `prepareModel()` uses, updating a SHA-256
  digest as bytes arrive. Progress is a `StateFlow<ModelDownloadState>`
  (NotInstalled / Downloading % / Verifying / Installed / Failed).
- Resume: if a partial `.download` file exists, it is re-hashed first and
  the request carries `Range: bytes=<already-have>-`. A server that ignores
  Range (HTTP 200 instead of 206) restarts the file from zero. A wrong
  size from the server fails the download before any bytes are trusted.
- On completion the size must equal 428,970,080 bytes and the digest must
  equal the pinned SHA-256, or the partial file is deleted. Then — and
  only then — `prepareModel()` runs: it re-verifies the downloaded file
  with its existing check and atomically moves it into place. There is
  still exactly one verify + install path; this step writes no second one.
- Failures: network drop / app killed keeps the partial file for resume;
  explicit cancel deletes it; checksum mismatch deletes it. Every failure
  leaves the question path returning the honest unavailable reply — never
  a silent web fallback, never a download from anywhere else.
- The download runs in the downloader's own coroutine scope, so cancelling
  a voice task does not stop it.

### Removal
"Remove the offline model" (voice or Settings) deletes the installed model,
the partial download and the `.part` staging file, and reports the MB freed.
It refuses while a download is running — cancel first.

## 4. What changed

| File | Change |
|---|---|
| `brain/ModelDownload.kt` (new) | `ModelDownloadPolicy` (pinned source, Wi-Fi/storage gates, exact-phrase matching, status labels), `ModelDownloadState`, `ModelDownloader` (resumable streaming download, SHA-256, handoff to `prepareModel()`). |
| `brain/LlamaLocalReasoningEngine.kt` | `DIRECTORY` const; `modelInstalled()` (size check), `hasModelSource()`, `removeModel()`; `generate()` returns the honest not-installed message when no source exists; `prepareModel()` accepts a verified `.download` file (atomic move, corrupt download deleted, external staged copy behaviour unchanged). |
| `brain/ModelDownloadTest.kt` (new) | JVM tests: pinned URL, constants match the engine, Wi-Fi/storage gates, exact-phrase matching (including the bare-"yes" refusal), progress %, status labels. |
| `AivoraApplication.kt` | `reasoning` now typed as `LlamaLocalReasoningEngine` (same instance); new lazy `modelDownload`. |
| `ui/AssistantViewModel.kt` | Exposes `modelDownloadState`; `downloadOfflineModel()` / `cancelModelDownload()` / `removeOfflineModel()`; voice hook for the exact download/remove/cancel phrases before question routing. |
| `ui/MainActivity.kt` | Settings: offline-model status, Download / Cancel / Remove buttons, one-time Wi-Fi-only explanation. |
| `docs/LOCAL_REASONING_DESIGN.md` | "No runtime downloader" corrected to point at `ModelDownload.kt`. |
| `README.md` | First-run download: planned → implemented as plumbing, phone test pending. |
| `PROJECT_STATE.md` | Dated checkpoint for this branch at the top. |
| `KNOWN_LIMITATIONS.md` | Dated note appended. |
| `MODEL_DOWNLOAD_NEXT.md` | This file. |

Nothing removed: no Gnani/Brave code touched, no secrets added, no defaults
flipped. `handsFreeControls`, `directCalling`, `directSms`, `wifiControls`
and `mobileDataControls` all still default `false` and are still Settings
switches only.

## 5. Design decisions recorded (for the next reader)

1. **Resume vs. delete-on-failure.** SCREEN_FREE_NEXT.md §2 said both
   "resumable" and "on failure … delete the partial file". Those conflict,
   so this step resolves it: failure keeps the partial file (resume is the
   point of Range support); only explicit cancel and checksum mismatch
   delete it. If a future reviewer wants delete-on-failure, Range resume
   becomes dead code — pick one.
2. **Install by atomic move, not copy.** The downloaded file already lives
   in the private `aivora_reasoning/` directory, so `prepareModel()` moves
   it into place instead of copying it through `.part`. That avoids needing
   a second 429 MB of free space at install time. The asset and external
   staged paths still use the existing copy path.
3. **"Installed" status is size-only.** `modelInstalled()` checks the file
   exists with the exact pinned size; the full SHA-256 verification runs
   inside `prepareModel()` before first use. The status text does not
   claim a hash was checked — it says "installed", and the verify step is
   documented here and in the code comment.
4. **No auto-start, no background download.** The download never starts on
   app launch, on first question, or on Wi-Fi connect. It starts only from
   the exact voice phrase or the Settings button.
5. **Voice opt-in for hands-free controls is still design-only.**
   SCREEN_FREE_NEXT.md §3.1 stands: a voice enrolment + spoken opt-in flow
   (with the existing device-auth gate) is the next design task. Flipping
   the defaults was not done here and must not be done as a shortcut.

## 6. What is NOT done / still needs a phone + JDK

- **No build, no tests run here.** No JDK/SDK on this VM. The new
  `ModelDownloadTest` and all existing tests need
  `./gradlew testDebugUnitTest assembleDebug lintDebug` on a JDK 21 +
  Android SDK 37 machine. Brace/paren balance was checked by reading
  only — that is not a compile check.
- **No download has ever run.** The URL was verified to exist (HF page,
  2026-11-15) and the SHA on the page matches the pinned SHA, but no
  429 MB file has been downloaded by this code, on any device.
- Phone test matrix (add to the SCREEN_FREE_NEXT.md §5 list):
  1. Fresh install, no model: "What is two plus two?" → honest
     not-installed offer, never a key demand, never a web fallback.
  2. "Yes, download the offline model" on Wi-Fi → download completes,
     status shows installed, next question gets a spoken local answer.
  3. Same offer on mobile data only → refused, Wi-Fi named in the reply.
  4. Cancel mid-download → partial file deleted, status not installed.
  5. Kill the app mid-download → restart resumes from the partial file.
  6. Airplane mode after install → offline question answered, no network.
  7. "Remove the offline model" → file gone, MB freed reported, next
     question offers the download again.
  8. Low storage (<493 MB free) → refused with the space reason.
  9. Corrupt the partial file, then start → checksum path deletes it.
- **Not implemented:** download progress spoken aloud (status is shown in
  Settings and the start/finish replies are spoken; a running spoken
  percentage was deliberately not added — see SCREEN_FREE_NEXT.md §2,
  "progress spoken at start/end only"); download over ethernet (refused
  by the Wi-Fi-only gate — a future unmetered-network option would need a
  design decision, not a silent widening); hands-free voice opt-in (§5.5).
