# AGENTS

This is CardinalBird's fork of Ezequiel-CE/Docking-Enhancer, retargeted to the **AYN Thor**
(Android 13). The Odin 2 is upstream's target and is no longer tested here. Upstream is the
`upstream` remote; this fork's PRs target `main` on `origin`.

## Stack
- Native Android (Kotlin) with Jetpack Compose + Material 3. No React Native.
- Gradle 9.8.2, Android Gradle Plugin 9.5.0 (Kotlin is built in: no `kotlin.android` plugin, no `kotlinOptions`), Kotlin 2.4.21, Java/JVM 17 bytecode for `:app`/`:data`.
- Compose BOM 2026.09.00, activity-compose 1.13.0, lifecycle 2.11.0 (kotlinx-coroutines pinned to what lifecycle pulls: 1.9.0). Versions live ONLY in `android/gradle/libs.versions.toml`; the root `build.gradle` uses the catalog aliases.
- No root: privileged commands run through the stock firmware's PServerBinder service (reflection + binder transact). minSdk 28, targetSdk 35, compileSdk 37 for `:app` (Compose 1.12 / core 1.19 / activity 1.13 refuse less) and 35 for `:data`. Raising targetSdk is its own PR; lint's `OldTargetApi` is suppressed at the site.
- App id (`applicationId`) is `com.controlswitch`. App label is `Control Switch` (`strings.xml`).
- The Kotlin package, Gradle `namespace` and source paths are still `com.odininputmirror` on purpose:
  renaming them is a large, behaviour-free diff, so only the install id changed. Use
  `com.controlswitch` for anything addressed by app id (`am`, `run-as`, `/data/data/...`, the
  daemon's don't-kill-myself check); keep `com.odininputmirror` for imports and class names.
  v0.2.0 shipped as `com.odininputmirror`, so it installs alongside rather than upgrading.

## Repo layout that matters
- Android multi-module project under `android/`.
- `:app` — Compose UI + `MainActivity` (ComponentActivity) + `MainApplication` (plain Application; starts the supervisor unconditionally, and the supervisor decides) + supervisor service + boot receiver. UI lives in `android/app/src/main/java/com/odininputmirror/ui/`.
- `:data` — Android/process impl (`InputMirrorGraph`, repositories, `MirrorShell` → `PServerShell` over PServerBinder, `PServerExec`).
- `:domain` — pure Kotlin use cases + interfaces + models.
- Dependency direction is `app -> data -> domain`.
- `MirrorViewModel` (in `:app` `ui`) wraps `InputMirrorGraph` and exposes a `MirrorUiState` StateFlow built from the snapshot the supervisor publishes to `MirrorStateStore` (no poll loop of its own). `MirrorScreen` renders it. Settings writes call `MirrorStateStore.wake()` so the supervisor acts at once.

## Commands (source of truth: Gradle files)
Run from `android/`. On Windows use `gradlew.bat` and the `.ps1` scripts; on macOS/Linux, `./gradlew`
(or `sh ./gradlew` if it lost its executable bit) and the `.sh` twins.
- Build debug APK: `./gradlew :app:assembleDebug`
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Unit tests: `./gradlew :domain:test :data:testDebugUnitTest :app:testDebugUnitTest`. `:domain` is a
  plain JVM module, so its task is `test` — `:domain:testDebugUnitTest` does not exist.
- Compile without packaging (fastest sanity check): `./gradlew :app:compileDebugKotlin`
- Lint is a gate: `./gradlew :app:lintDebug` fails on any warning (`warningsAsErrors` in
  `app/build.gradle`). Fix the finding or suppress it at the site with a comment saying why; the
  dependency-age checks are off on purpose (upgrades are their own PR).
- Build native mirror binary: `scripts/build-input-mirror.sh` (macOS/Linux) or
  `powershell -ExecutionPolicy Bypass -File scripts/build-input-mirror.ps1` (Windows). Same flags.
- Release APK: `./gradlew :app:assembleRelease`. Left UNSIGNED unless `android/keystore.properties`
  exists — deliberate, so a missing keystore never silently ships a debug-signed build.

## Bringing the project up
- Needs an Android SDK with `platforms;android-37` (for `:app`) and `platforms;android-35` (for `:data`) and an NDK for the native binary (26.3 is what the Thor work
  used). The code targets Java 17, but `gradle/gradle-daemon-jvm.properties` asks for a **JDK 21** to
  run Gradle itself; without one the build stops before compiling anything.
- The `.sh` build scripts require `ANDROID_NDK_HOME`. The `.ps1` ones fall back to the newest NDK
  under `%LOCALAPPDATA%\Android\Sdk\ndk`.
- Needs a physical handheld exposing `PServerBinder`. **There is no emulator path**: no such service,
  no internal controller node to write into, and no external pad to grab.
- `FORCE_DOCK_MODE_FOR_DEV` in `android/app/build.gradle` is `true` for **debug** builds (committed
  that way upstream) so the mirror runs at a desk without a dock, and `false` for release. A debug
  build therefore mirrors in handheld use too; install a release build, or one with the flag off,
  before handing the device back to normal play.
- Debug and release are signed with different keys, so switching between them forces an uninstall,
  which wipes settings (`allowBackup="false"`). Expect to re-enable the toggles afterwards; only
  `autoMirrorEnabled` defaults back to true.
- From Git Bash, call adb through PowerShell. Git Bash rewrites `/data/local/tmp/...` into a Windows
  path, and the command fails with a `No such file or directory` that points nowhere near the cause.

## Native binary flow (non-obvious)
- Core mirroring is native C: `android/app/src/main/native/input_mirror.c`.
- Build scripts `scripts/build-input-mirror.{sh,ps1}` compile with `aarch64-linux-android23-clang` and write a `.sha256` beside the binary.
- Output must be `android/app/src/main/assets/input_mirror/input_mirror` (tracked intentionally via `.gitignore` exceptions).
- At runtime Kotlin copies that asset to app files dir and launches it via the no-root PServer shell (`RootMirrorProcessRepository` → `MirrorShell.launchDaemon`). The daemon runs foreground inside a backgrounded `sh` script — never `setsid`/inline `&` (input_mirror catches SIGHUP and would exit).

## Runtime/ops constraints
- No root. Privileged commands go through PServerBinder (`PServerShell`), which returns only the first stdout line and no exit code — reads stage output through a file, status checks use a stdout token. On devices without the service the UI shows an "Unsupported device" dialog; the supervisor retries the probe for about a minute (it can lose a race with the firmware at boot), then stops itself.
- Mirroring writes directly to `/dev/input/eventX` (the pservice SELinux domain can open input/uinput nodes).
- Event node paths are unstable across reconnects/reboots; device resolution matches by GUID first, then path (`findSavedControllerDevice`).
- Start/stop/restart is decided every tick by `ResolveAutoMirrorDecisionUseCase`, driven from the foreground service `InputMirrorSupervisorService`; it only acts while `autoMirrorEnabled` is true and the device is docked.
- Liveness is heartbeat/pid based in data layer (`RootMirrorProcessRepository` + process files), not just a simple process-name check.
- Changing the options (`homeMode`, `comboHoldKillApp`, `virtualMouse`) must only persist settings; the mirror starts/stops via the supervisor and `startMirror`/`stopMirror`. If the mirror is running with different flags, the supervisor detects it (persisted `started*` snapshot vs current settings) and restarts it automatically.

## Testing
- Unit tests cover the pure logic: use cases, device resolution, settings, process/file handling.
- Everything that only breaks on real hardware — evdev grabs, `/dev/input` node lifetimes, SELinux
  labels, a root daemon outliving its app — is covered by the on-device e2e suite in
  `.claude/skills/mirror-e2e/` (tracked; see its `SKILL.md`). Run it with:
  `build-harness.sh` (or `.ps1`) from that folder's `scripts/`, then `adb shell sh /data/local/tmp/e2e.sh`.
- The Thor has **no `su`**. The suite and `latency.sh` fall back to the shell user (it is in the
  `input` group), so everything runs except the three assertions that unlink `/dev/input` nodes,
  which need root and fail by design there (46/49 is a clean Thor run). The app does that step
  through `PServerBinder`; check it through the app on a Thor.
- The suite drives synthetic uinput controllers, so no physical pad is needed and runs are
  reproducible. It force-stops the app first; relaunch the app afterwards.
- **No CI is possible** for any of that: every meaningful test needs the handheld attached. Unit
  tests and compilation are all a machine without a device can verify.
- A change to the daemon or the hide/restore paths is not done until the e2e suite passes on-device.
  Compiling is not evidence — these are failure paths, and they only fail on hardware.

## Commit conventions
- Conventional Commits: `<type>(<scope>): <imperative summary>`, subject ≤50 chars (hard cap 72), no
  trailing period.
- Body only when the "why" is not obvious from the diff. Explain intent and consequence, not a
  file-by-file recap of what the diff already shows.
- **Never** add a `Co-Authored-By: Claude` trailer, or any other AI attribution, to a commit.
- Rebuild and commit `assets/input_mirror/input_mirror` in the SAME commit as the `input_mirror.c`
  change it was built from. That binary is tracked and shipped; letting it drift from its source
  means the repo describes one daemon and runs another.

## Device realities that cost time
- The handheld republishes an external controller under its own vendor id (`0x2020:0111`) and deletes
  the original node. On the **Thor only one pad at a time** gets that twin; a second pad keeps its own
  node, can't be hidden (the daemon only unlinks `0x2020` nodes), and its name may match the first's.
- Device paths are resolved by name, so identically named pads collide. When they do,
  `dumpsys input` (through PServer) maps each framework device id to its real node; the hide node of
  one pad must never be another pad's own node (`dropForeignHideNodes`).
- The Thor's bottom screen is a `TYPE_INTERNAL` display that is always on; dock detection must ignore
  it, or the Thor always looks docked.
- The Thor's built-in pad is "Odin Controller" `2020:0111` (Nintendo-layout product id, so the daemon's
  face-button swap is on). Its key layout maps `BTN_DPAD_*` and the hat.
- A Switch Pro Controller (`057e:2009`) on the Thor reports its D-pad as `BTN_DPAD_*` buttons, no hat,
  and exposes no Bluetooth address (`Uniq=` is empty). SDL_GameControllerDB has several entries per
  pad, one per driver; `GameControllerDb.entryFor` scores Linux entries against standard codes rather
  than taking the first.
- Synthetic pads: use a generic vendor id (e.g. `0x1234`) when a test needs L1/R1 as 310/311. Under
  `045e:02e0` the Xbox layout reads R1 as `0x135`, which `vgamepad` doesn't declare.
- `getevent` labels `0x130` as `BTN_GAMEPAD`; `BTN_SOUTH` and `BTN_A` are aliases of the same code.
  Asserting on the wrong alias reads as a forwarding bug that is not there.
- The input core drops an absolute event repeating its current value, so a synthetic axis burst must
  alternate values or half of it silently vanishes.
- Bluetooth pads sleep on their own mid-session. A mirror that "stopped working" is usually that.

## Known install quirk (seen on Odin)
- Install may fail with `Failed to parse APK file` / `avc ... Permission denied` even when build succeeds.
- Fallback: `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`, then launch `com.controlswitch/com.odininputmirror.MainActivity`.

## Agent-local files
- `.agents/` is intentionally gitignored; do not re-add it to tracking.
- Third-party skills under `.claude/skills/` stay untracked, but `skills-lock.json` IS tracked so a
  collaborator can reproduce the same tooling with `npx skills` — vendoring the skills themselves
  would mean redistributing other authors' work under this repo's licence.
- `.claude/skills/mirror-e2e/` is the exception and is tracked: it is this project's own e2e suite
  (see its `SKILL.md`), not reinstallable tooling. Its `build/` output stays ignored.
