# Build & test — first attempt on this VM (2026-11-15)

Branch: `offline/build-test` (from `aivora/main` `3dfbf07`, all 4 PRs merged).
Status: **BLOCKED — no build or test has ever run.** The blocker is not the
project. The JDK and Android SDK are installed and verified, but **no Java
process in this VM can write to a TCP socket**, and Gradle 9 cannot run
without its client→daemon socket. Everything below is verified, with exact
commands and outputs. Nothing is claimed as passed unless it actually ran.

## 1. What was installed (all under `~/workspace/android-tooling/`, $0 spent)

| Component | Location | Version / verification |
|---|---|---|
| Temurin JDK 21 | `jdk-21/` | 21.0.13+9-LTS, `java -version` and `javac` work |
| Android cmdline-tools | `android-sdk/cmdline-tools/latest/` | sdkmanager 12.0, but **cannot fetch repos** (see §2) |
| platform-tools | `android-sdk/platform-tools/` | 37.0.3, zip SHA-1 `e1579b28…` matches `repository2-2.xml`; `adb --version` works |
| build-tools | `android-sdk/build-tools/37.0.0/` | 37.0.0, zip SHA-1 `70954e99…` matches manifest; `aapt2 version` works (zip's top dir is `android-37.0/`, renamed on install) |
| platforms android-37.0 | `android-sdk/platforms/android-37.0/` | ApiLevel 37.0, `Platform.Version=17`, Pkg.Revision 1, `android.jar` present. **Preview-channel only** — not in `repository2-1.xml`/`repository2-2.xml` (both top out at android-36). Downloaded direct from `https://dl.google.com/android/repository/platform-37.0_r01.zip` (67,204,348 bytes, SHA-1 `1a9a8c2e2a5ab8cd95f2eded7d9da037e62fbc6f`). SHA could not be cross-checked against a Google manifest (no canary manifest found). |
| NDK | `android-sdk/ndk/28.2.13676358/` | r28c, zip SHA-1 `a7b54a5de87fecd125a17d54f73c446199e72a64` matches `repository2-2.xml`; `ndk-build` present |
| CMake | `android-sdk/cmake/3.22.1/` | 3.22.1, zip SHA-1 `fd0a48b4a758310df8c7aa51f59840ed48fe7ed8` matches manifest; `cmake --version` = `3.22.1-g37088a8` |
| Gradle | `gradle-9.3.1/` | 9.3.1, zip SHA-256 `b266d5ff…` matches published `.sha256`; `gradle --version` works. `./gradlew` deliberately NOT used (it would download the distribution via Java → same proxy failure as sdkmanager). |
| Licenses | `android-sdk/licenses/` | `android-sdk-license` hash `8933bad1…`, `android-sdk-preview-license` hash `84831b94…` |
| Gradle home | `gradle-home/` | `gradle.properties`: `org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8`, `org.gradle.workers.max=2`. No secrets in it. Build logs: `build-logs/` |

Total SDK size: 2.7 GB. Disk: 144 GB free. RAM at build time: ~7.9 GB total,
~1.6 GB available (VM heavily loaded; swap 1 GB).

Note: `/tmp` is a 512 MB tmpfs — the first NDK unzip into `/tmp/ndk28`
failed with "write error (disk full?)". Extract under `~/workspace/` instead.
Note: the manifest checksums in `repository2-1.xml`/`repository2-2.xml` are
**SHA-1** (40 hex), not SHA-256.

## 2. sdkmanager cannot fetch anything (same root cause)

Every `sdkmanager` install/license call fails with
`java.util.NoSuchElementException` at `HttpURLConnection.doTunneling0`
while fetching the remote repository. Reproduced with a minimal Java
`HttpURLConnection` program against
`https://dl.google.com/android/repository/repository2-1.xml` — it is not
sdkmanager-specific. `curl` works fine through the same proxy. Workaround
used: download SDK packages directly with `curl`, verify SHA-1 against the
manifest, install by hand into the SDK layout.

Proxy credential caution: the proxy URL in the environment contains an auth
token that rotates per exec invocation. It was accidentally exposed once in
a JVM "Picked up JDK_JAVA_OPTIONS" note written to a `/tmp` log; that log
was deleted. **Never write the proxy token to any file, log, or report.**
Derive it from the environment at runtime only. Do not use
`JDK_JAVA_OPTIONS` (it echoes secrets); use `GRADLE_OPTS` for Gradle.

## 3. The build failure — Gradle daemon dispatch

Command (branch `offline/build-test`, head `3dfbf07`):

```
export JAVA_HOME=~/workspace/android-tooling/jdk-21
export ANDROID_HOME=~/workspace/android-tooling/android-sdk
export ANDROID_SDK_ROOT=~/workspace/android-tooling/android-sdk
export GRADLE_USER_HOME=~/workspace/android-tooling/gradle-home
# GRADLE_OPTS: proxy host/port/user/password derived from $https_proxy at
# runtime (never written to a file), plus
# -Djdk.http.auth.tunneling.disabledSchemes=
# -Djdk.http.auth.proxying.disabledSchemes=
# -Dhttp.nonProxyHosts=localhost|127.0.0.1
~/workspace/android-tooling/gradle-9.3.1/bin/gradle testDebugUnitTest assembleDebug lintDebug --max-workers=2 --console=plain
```

Result: `GRADLE_EXIT=1` after ~7 s. The daemon **starts** (pid 1870,
listening on `127.0.0.1:34307`, "Daemon server started") but the client
cannot dispatch the build message:

```
FAILURE: Build failed with an exception.
* What went wrong:
Could not dispatch a message to the daemon.
```

With `--stacktrace`, the root cause is:

```
Caused by: org.gradle.internal.remote.internal.MessageIOException: Could not write '/127.0.0.1:33919'.
Caused by: java.io.IOException: Connection reset by peer
    at java.base/sun.nio.ch.SocketDispatcher.write0(Native Method)
```

The client connects, the daemon never sees the connection, the client's
first write gets RST. The daemon then notices it was removed from the
registry and exits cleanly (`Daemon will be stopped … after the daemon was
no longer found in the daemon registry`). Same failure for plain
`gradle help --stacktrace`, and for `gradle help --no-daemon` — in Gradle 9,
`--no-daemon` still forks a single-use daemon
("To honour the JVM settings for this build a single-use Daemon process
will be forked") and dispatches over the same socket. There is no
true in-process mode in Gradle 9.

## 4. Root cause, proven with minimal programs

Loopback TCP between two processes, 200,000 bytes, same shell:

| Client | Server | Result |
|---|---|---|
| Python | Python | works — server got 200000, client got ack |
| Python | Java (blocking `ServerSocket`) | works — server accepted, got 200000 |
| Java (blocking `java.net.Socket`) | Python | **fails** — `Connection reset by peer` on write, server never accepts |
| Java (NIO `SocketChannel`, non-blocking connect) | Java | **fails** — client "wrote" 200000, server still blocked in `accept()` |
| C (raw `socket`/`write`) | Python | works — server accepted, got 200000 |
| Java (from `/usr/local/jdk21-test`, not `~/workspace`) | Python | **fails** — same `Broken pipe`, ruling out binary path |
| Java, `env -i` (empty environment) | Python | **fails** — same `Broken pipe`, ruling out env vars / proxy env |

Also fails for a Java client to `127.0.0.2` and to the VM's real interface
`198.19.0.2`. Java *servers* bind and listen fine; only Java *clients*
writing to a TCP socket fail. `curl`, `git`, Python and C all work.

Conclusion: **in this VM, a Java process cannot write to any TCP socket,
regardless of address, environment, or JDK location.** This kills the
Gradle daemon protocol (and the Kotlin daemon, which also uses a loopback
socket), so no Gradle build can run here. It is a VM/sandbox networking
limitation, not an AIVORA, Gradle, or SDK problem. The exact mechanism
(transparent proxy intercepting JVM sockets, or similar) is not identified;
the behaviour is reproducible on demand with the test programs in
`/tmp/javatest/` (`NioTest.java`, `BioTest.java`, `BioClient2.java`) and
`/tmp/pyserver2.py` (note: `/tmp` is wiped on VM restart — re-create from
this description if needed).

## 5. What was NOT reached

- Dependency downloads (Gradle uses Apache HttpClient, untested — the
  build dies before any repository is contacted).
- `testDebugUnitTest`, `assembleDebug`, `lintDebug` — never started.
  **No tests ran, no APK was built, nothing is claimed as passed.**
- `git lfs pull` / the 429 MB Qwen3 model — not attempted; `git lfs` is not
  installed. The full-offline build (`-PaivoraIncludeLocalReasoning=true`)
  was not run, per the task (skip if it would download 429 MB).
- The phone test matrices in `SCREEN_FREE_NEXT.md` (15 rows),
  `MODEL_DOWNLOAD_NEXT.md` (9 rows) and `HANDS_FREE_OPTIN_NEXT.md`
  (15 rows) still need a physical phone (BRO's POCO).

## 6. Open next step — Ubuntu OpenJDK tried, same failure (update 15:35 IST)

Ubuntu's OpenJDK 21 **was** installed and tested: `apt-get update`
(exit 0; one nodesource index warning, ignored) then
`apt-get install -y openjdk-21-jdk-headless` (exit 0). Installed:
`/usr/lib/jvm/java-21-openjdk-amd64/`, `openjdk version "21.0.13.0.3"
2026-10-24` (Ubuntu build `21.0.13.0.3+1-1-24.04.1`). The approval card
for the install timed out, but the install itself completed.

The minimal Java-client socket test against a Python server on
`127.0.0.1` failed **identically** with Ubuntu's OpenJDK:
`java.net.SocketException: Broken pipe` on the first write, server never
accepting. So the blocker is **sandbox-wide for all JVMs**, not
Temurin-specific, and no JDK available to this VM can run Gradle.

The build therefore needs a different machine: a local dev machine with
Android Studio, or CI (e.g. GitHub Actions `android` runner with JDK 21
+ SDK 37) — both outside this VM's limits. The SDK layout under
`~/workspace/android-tooling/android-sdk/` is complete and can be reused
if the socket limitation is ever lifted. (Note: the apt-installed JDK
lives in system paths that do not survive VM replacement; the durable
JDK stays the Temurin one under `~/workspace/android-tooling/`.)
