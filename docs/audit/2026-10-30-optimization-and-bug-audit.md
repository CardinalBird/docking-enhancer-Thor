# Docking Enhancer (Thor fork) — optimization + bug audit, 2026-10-30

Branch `feat/home-mapping` @ 08b9d2a, PR #1 open. Five read-only lanes: native daemon, data/domain, app service/lifecycle, Compose UI, build+tests. Nothing edited.

## Baseline (build lane)
- Unit tests: domain 33, data 83, app 8 — all green (124/124).
- Kotlin: 0 warnings. Native C: 0 warnings even at -Wall -Wextra -Wshadow -Wconversion; tracked binary is byte-identical to a fresh build.
- Release APK builds unsigned, R8 + shrinkResources on, 1.85 MiB. FORCE_DOCK_MODE_FOR_DEV is true for debug only; release inherits false.
- `lintDebug` is RED (3 RestrictedApi errors on MainActivity.dispatchKeyEvent, a known androidx false positive; main is red the same way). 35 warnings, mostly dependency-age. No baseline file exists.
- Deps a year+ behind across the board (AGP 8.7.3→9.5, Gradle 8.11→9.8, Kotlin 2.0.21→2.4, Compose BOM 2024.10→2026.09). jitpack repo declared but unused.

## Confirmed by me
- `.claude/skills/mirror-e2e/scripts/latency.sh:16` — `as_root() { as_root "$1"; }` recurses forever when `su` exists. Should be `su -c "$1"` (e2e.sh:44 has it right). Already committed in 2854a53.

## Top findings (deduped across lanes)

### A. Daemon lifecycle (two lanes found independently)
1. **start() kills a healthy daemon.** `RootMirrorProcessRepository.kt:29` deletes pid+heartbeat before the shell "already mirroring → exit 0" guard. The surviving daemon loses its heartbeat path and self-terminates ~5 s later (`input_mirror.c:340`). Trigger: any stale-heartbeat tick (CPU starvation, resume). Fix: move `rm -f pid heartbeat` into the shell command after the guard. Test at RootMirrorProcessRepositoryTest.kt:113 pins the bug.
2. **Select+Start kill combo never fires while the pad is chatty.** `input_mirror.c:963` only checks the hold timer on a poll timeout; release path clears flags first. Fix: call `maybe_kill_combo_on_timeout` every loop.
3. **Held controls stay held** on source disconnect, SIGTERM, capture start, and settings reload (`input_mirror.c:948/1033/924/471`). Fix: `release_target_holds` on each path.
4. **FIFO commands merged out of order** (`input_mirror.c:489`): `capture button\ncapture off` leaves capture on (pad mute 30 s). UI side has the same race (`MappingEditorController.kt:134/145`, unordered IO coroutines). Fix: daemon splits on `\n`, app serializes FIFO writes on `Dispatchers.IO.limitedParallelism(1)`.
5. SIGTERM can be missed up to 1 s (`input_mirror.c:871`); STOP SIGKILLs at 500 ms → hidden node stays hidden. Fix: ppoll with signal mask.

### B. Thor identity (the fork's core risk)
6. **GUID collision.** Thor's internal pad and every twinned external pad are all 2020:0111, and `guidOf` is vendor+product only (`AndroidInputDeviceRepository.kt:353`). GUID-first lookup can return the external twin as the "internal" controller. `MirrorUseCaseTest.kt:111` pins GUID-first. Fix: match guid+path, then path, then guid only if unique.
7. **hide_node can unlink the internal pad** — only check is vendor==0x2020 (`input_mirror.c:867`, `hide_nodes.c:61`). Fix: refuse when probe `st_rdev` == target's.
8. **A/B X/Y swap is unconditional on Thor** (`input_mirror.c:81,778`) — Odin firmware semantics keyed on 2020:0111. Fix: gate on a setting or verified Thor signature.
9. **Target capability bits never read** (`input_mirror.c:616`): hat vs BTN_DPAD_*, trigger axes, BTN_MODE silently dropped. Unmapped axes forwarded without rescale (`mapping.c:210`). Thor pad codes/ranges still unverified. Fix: read EVIOCGBIT/EVIOCGABS at startup, translate hat↔dpad, rescale passthrough.
10. `odinFallbackTraits` (`MappingSlot.kt:83`) used whenever targetTraits() is null → Odin slots offered on Thor. Capture real Thor traits.
11. Dock detection counts virtual/overlay/Wi-Fi displays as a dock (`AndroidDisplayDockStateRepository.kt:19`); reflection failure falls back to "docked". Fix: require TYPE_EXTERNAL, fail closed.
12. dumpsys parse keeps only the first EventHub id per device (`:388`); BT pads with IMU sub-nodes can resolve to the motion-sensor node.

### C. Service / lifecycle
13. **Suspected crash:** `MirrorViewModel.kt:112` calls startSupervisor() unguarded from viewModelScope; ForegroundServiceStartNotAllowedException after backgrounding kills the process. Fix: runCatching (MainApplication already does).
14. **Boot race stands supervisor down permanently** (`InputMirrorSupervisorService.kt:76`; `PServerExec.kt:71` caches a false verdict). Fix: retry probe with backoff, never cache false.
15. No `MY_PACKAGE_REPLACED` receiver → after any update the old daemon runs unsupervised until the app is opened.
16. START_STICKY restart after low-memory kill: background startForeground refused, service runs headless and is idled. Fix: stopSelf on failure; consider battery-optimization exemption.
17. No DisplayListener → dock-in takes up to ~10 s; setting changes don't wake the supervisor (up to 8 s). Fix: `MirrorStateStore.wake()`.

### D. Settings consistency
18. **Config pairs fresh configGeneration with stale request values** (`RootMirrorProcessRepository.kt:34`, `AndroidMirrorSettingsRepository.kt:31`); saveStarted writes them back → a toggle flipped mid-tick silently reverts. Test at RootMirrorProcessRepositoryTest.kt:147 pins it. Fix: carry generation in MirrorStartRequest; stop re-persisting in saveStarted.
19. Home ‹ › row acts on key auto-repeat, each step a parallel IO write that can land out of order (`MirrorScreenParts.kt:153`). Fix: ignore repeatCount>0, cancel previous write Job.
20. applyStatus overwrites optimistic UI values with pre-write snapshots (`MirrorViewModel.kt:174`).

### E. Gamepad navigation (UI)
21. Player 1 screen: X→Unlock handler sits on a non-focusable Column, never runs (`Player1Screen.kt:88`).
22. Capture with mirror off: beginCapture() false is ignored; overlay waits forever (`MappingEditorController.kt:226`, gate `MirrorScreen.kt:271`).
23. Capture overlay is in-tree, focus can escape to rows behind it and rebind the wrong slot (`MappingEditorParts.kt:213`).
24. anchorFocus() yanks focus on every enabled/busy flip and window-focus regain (`MirrorScreen.kt:194`).
25. choosePlayer1 matches against a mirror-era snapshot for up to 2.5 s (`MirrorViewModel.kt:299`).
26. Suspected, verify on device: X/Y consume only KeyUp so Generic.kcm fallback fires (X→DPAD_CENTER opens capture on unbound rows); hat/stick D-pad injected via dispatchKeyEvent never leaves touch mode after a screen tap (`MainActivity.kt:163`).
27. M3 Switch inside MenuRow is a second focus stop (`MirrorScreenParts.kt:183`); editor focus-restore loop treats a refused requestFocus as success (`MappingEditorScreen.kt:100`); group debounce cancels on fast Down→Right.

### F. Perf / battery
28. `dumpsys input` every 2.5 s tick whenever two same-named pads exist — the normal Thor state (`AndroidInputDeviceRepository.kt:78`). Cache on device-id set.
29. ~20 regexes compiled per tick in /proc and dumpsys parsers; hoist to top-level vals.
30. One write() per forwarded event (`input_mirror.c:630`); batch per SYN frame. mouse exit usleep(40 ms) stalls forwarding and can overflow the source buffer (SYN_DROPPED never recovered, `:990`).
31. Home→Back/Home/Recents forks `input keyevent` (JVM start, 300–800 ms per press, `input_mirror.c:582`). Write KEY_BACK/HOMEPAGE/APPSELECT via a uinput node the daemon owns.
32. healOrphanedHideNodes SHA-256s two binaries per idle tick; canPinShortcut binder call per recomposition; getType reflection lookup per display per tick.
33. Suspected: mouse-mode exit taps (0,0) because uinput drops SYN_DROPPED (`mouse.c:196`). Verify with getevent.

## Plan

Fix waves, each a PR off feat/home-mapping (or main after #1 merges). Max 5 agents, worktrees, commit not push, I review before anything ships. Daemon changes need the on-device e2e suite.

| Wave | Scope | Items | Needs device |
|---|---|---|---|
| 0 | One-liners, today | latency.sh recursion; runCatching startSupervisor; MY_PACKAGE_REPLACED; hoist regexes; remember canPinShortcut; Switch onCheckedChange=null; anchorFocus guard; lint baseline + fix the 2 mutableIntStateOf | no |
| 1 | Daemon lifecycle | 1–5 + release_target_holds, SYN_DROPPED resync, fifo line split, app-side serialized FIFO writes | yes (e2e) |
| 2 | Thor identity | 6–12 + capture Thor traits fixture, fix the two tests that pin wrong behaviour | yes |
| 3 | Settings + supervisor wake | 13–20 | partly |
| 4 | Gamepad navigation | 21–27 | yes (Nick verifies 26) |
| 5 | Perf | 28–33 | yes for 30/31/33 |
| later | Deps: AGP 9 / Gradle 9 / Compose BOM / lifecycle are major migrations, separate PR; drop jitpack; contrast + 48dp targets | no |

Nick decisions needed
- Wave 2 #8: should the A/B swap become a user setting, or do we verify the Thor pad layout first and hardcode?
- Wave 3 #16: ask for REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, or accept headless-restart risk?
- Wave 5 #31: uinput device for Home keys changes the SELinux surface; OK to try?
- Lint: adopt a baseline so lintDebug becomes a gate, or keep ignoring it?

Doc drift to fix with wave 0: AGENTS.md:16/20/60 (supervisor start is unconditional, ViewModel no longer polls, unsupported → stopSelf not idle).
