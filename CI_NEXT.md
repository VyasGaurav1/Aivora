# GitHub Actions CI (15 November 2026)

Branch: `offline/ci` (from `offline/build-test`, main `e142272`, all 5 PRs
merged). Local work only — the workflow **has run once and failed**
(run 43734779708, §5). **Nothing here claims a green build.**

## 1. What the workflow does

`.github/workflows/android.yml`, trigger: push and PRs to `main`, plus
manual `workflow_dispatch`.

| Step | What it does |
|---|---|
| Checkout | `actions/checkout@v4` with `lfs: false` — the 429 MB Qwen model is a Git LFS pointer and is deliberately **not** fetched (see §2) |
| JDK | `actions/setup-java@v4`, Temurin 21, Gradle cache on |
| Android SDK | Manual install, no third-party setup action: curl downloads cmdline-tools 16.0 into `$ANDROID_HOME/cmdline-tools/latest/`, then `sdkmanager` installs `platform-tools`, `platforms;android-37`, `build-tools;37.0.0`, `ndk;28.2.13676358`, `cmake;3.22.1` — the exact local toolchain from `BUILD_TEST_NEXT.md`. See §5 for why the setup action was removed, and §3.1 for the `platforms;android-37` fallbacks |
| Licenses | `yes \| sdkmanager --licenses` |
| Build | `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon --stacktrace --console=plain` |
| Artifacts | Unit-test XML + HTML reports and the lint report (uploaded even on failure); the debug APK (uploaded only on success) |

Why CI at all: this VM cannot run Gradle — no JVM here can write to a
TCP socket, so the Gradle daemon protocol is dead on any JDK (proven in
`BUILD_TEST_NEXT.md` §4, two JDKs, minimal Java/Python/C programs).
GitHub Actions is the build machine until a local dev machine is used.

Build-config changes made for the workflow (one):
- `gradlew` was not executable in git (`-rw-rw----`, mode `100644`).
  `./gradlew` would have failed with permission denied on the runner.
  Fixed with `git update-index --chmod=+x gradlew` (now `100755`).
  The workflow also runs `chmod +x gradlew` before the build, because
  a snapshot push does not carry git file modes — the in-workflow
  chmod is the belt-and-braces, the git mode is the fix.
  No `build.gradle.kts`, `settings.gradle.kts` or `gradle.properties`
  change was needed: Gradle 9.3.1, AGP 9.1.1, compileSdk/targetSdk 37,
  minSdk 30, NDK 28.2.13676358, CMake 3.22.1 and Java 17 source/target
  are all as the workflow expects.

## 2. What the first run will prove

The starter build only — the same command the VM could never run:
`testDebugUnitTest` (the JVM suite, last recorded passing at 251 tests
on 7 November, before the offline-first, screen-free, model-download,
hands-free and build-test changes), `assembleDebug` (debug APK, with the
native `reasoning_jni` CMake build), and `lintDebug`.

What a green run proves: the five merged branches compile, their tests
pass, and lint is clean — on a real machine, for the first time since
12 November. What it does **not** prove: anything on a physical phone
(the 15/9/15-row phone matrices in `SCREEN_FREE_NEXT.md`,
`MODEL_DOWNLOAD_NEXT.md`, `HANDS_FREE_OPTIN_NEXT.md` still need BRO's
POCO), and nothing about the full-offline build (below).

## 3. What to watch for on the first run

1. **`platforms;android-37` not found.** SDK 37 was preview-only when
   this was written: it is not in the stable `repository2-1.xml` /
   `repository2-2.xml` manifests (both top out at android-36), and the
   local copy was downloaded direct from
   `dl.google.com/android/repository/platform-37.0_r01.zip`
   (`BUILD_TEST_NEXT.md` §1). The workflow now handles this itself,
   in order: stable channel, then canary (`sdkmanager --channel=3`),
   then the direct platform zip with SHA-1 verification
   (`1a9a8c2e…62fbc6f`) unzipped into `$ANDROID_HOME/platforms/`
   (top directory `android-37.0/`, exactly as the local toolchain).
   Do not silently drop `compileSdk` to 36 to make CI pass — that
   would change what ships. If even the direct zip stops working,
   the platform URL or revision is the thing that changed, not the
   app code.
2. **The 429 MB LFS model must stay out of this job.** The starter
   build omits it by design: `aivoraIncludeLocalReasoning` is unset,
   so `app/build.gradle.kts` never adds `src/localReasoning/assets`
   and never runs `verifyReasoningModel`. If a future change makes
   the starter build touch
   `src/localReasoning/assets/models/reasoning/qwen3-0.6b-q4_0.gguf`,
   it will hit the 134-byte LFS pointer and fail hash verification —
   that failure means the build config regressed, not that CI needs
   LFS. The full-offline build (`git lfs pull`, then
   `-PaivoraIncludeLocalReasoning=true`) is a separate manual job and
   is deliberately **not** enabled by default here.
3. **A test failure stops the later stages.** The three tasks run in
   one Gradle invocation, so a `testDebugUnitTest` failure means no
   APK and no lint report that run. Read the uploaded test reports
   first. Splitting test/build/lint into separate jobs is a later
   improvement, not needed for the first run.
4. **No secrets are used.** The workflow needs no keys, tokens or
   signing config; it builds the debug APK only. Release signing is
   out of scope.
5. **First-run duration.** Dependency downloads + NDK/CMake native
   build on a cold runner will be slow; the job timeout is 60
   minutes. The Gradle cache (via `setup-java`) should make later
   runs faster.

## 4. After the first run

- Green: record the run URL, test count and APK artifact in
  `PROJECT_STATE.md`; the phone matrices are then the next gate.
- Red at the SDK step: the first red run is already recorded in §5 —
  it was the setup action, not the SDK packages. If a later run goes
  red at the SDK step, read the log first: the workflow already
  tries stable, canary and direct-zip for `platforms;android-37`.
- Red at tests/lint: fix in code on a branch from main, not by
  weakening the workflow (no skipping tests, no `--continue` to
  hide failures, no dropping the lint task).

## 5. First run failed — the setup action, not our code (15 Nov 2026)

Run 43734779708, on PR #6 (branch `offline/ci`, commit `784b85f`):
failed in **18 s**. Only one step failed — "Set up Android SDK"
(7 s), running `android-actions/setup-android@v3`. From its log:

- The action found the runner's preinstalled sdkmanager was
  cmdline-tools 20.0 ("Wrong version in preinstalled sdkmanager"),
  downloaded `commandlinetools-linux-12266719_latest.zip` and used
  cmdline-tools 16.0. Licenses were accepted fine.
- Then the action itself ran `sdkmanager tools` — and the obsolete
  SDK package named `tools` no longer exists in the repository:
  "Warning: Failed to find package 'tools'", exit code 1.
- Checkout and JDK 21 setup passed. Everything after the failure
  was skipped, so the "Install Android SDK packages" step never ran:
  `platforms;android-37` was never reached or tested in this run.
  No test ran, no APK was built, nothing is claimed green or broken
  in the app code.

Fix (this commit, local only — not yet pushed):

- `android-actions/setup-android@v3` removed entirely. SDK setup is
  now plain shell: curl downloads cmdline-tools 16.0
  (`commandlinetools-linux-12266719_latest.zip` — the exact zip the
  failed run proved exists, downloaded by the action itself),
  unzipped to `$ANDROID_HOME/cmdline-tools/latest/`; `sdkmanager`
  then installs the packages directly. Nothing asks sdkmanager for
  the obsolete `tools` package.
- `platforms;android-37` fallback chain is now in the workflow, not
  just documented: stable channel → canary (`--channel=3`) →
  direct platform zip with SHA-1 verification.
- Nothing else changed: JDK 21 Temurin, the same Gradle invocation,
  `chmod +x gradlew`, LFS off, same artifact uploads, no secrets.

What the next run will test, in order:

1. Does the manual cmdline-tools install work (curl + unzip +
   `sdkmanager` on PATH)?
2. Does sdkmanager find `platforms;android-37` — stable, canary,
   or direct zip? The log will show which fallback, if any, was
   used; that answers the §3.1 watch item for real.
3. Only then: the actual build — `testDebugUnitTest`,
   `assembleDebug`, `lintDebug`.
