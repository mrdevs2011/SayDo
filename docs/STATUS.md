# SayDo status

Last updated: 2026-11-06. Version on `main`: 2.12.0 (versionCode 25). Newest verified-on-phone builds are listed per row.

Only what was observed on a real phone counts as working. Unit tests run on a JVM with Android
stubbed, so they cannot prove on-device behaviour.

## State by feature

Stage 0 was run on a Galaxy S22+ (SM-S906B, Android 16) with build `1dffca6`, Settings app only. Later fixes were checked on the same phone with the builds named in the Evidence column. The cleanup commits after `13d19b0` (removal of unused `kernel/` code, quieter replay log, relay hardening) are covered by the final smoke test described under "Cleanup check" below.

| Feature | State | Evidence |
|---|---|---|
| Install, launch, main screen | Works | Galaxy A12 and S22+ |
| Start from launcher default page | Works | A12, S22+ |
| One tap = one stroke | **Works** | S22+: 4/4 recordings stored exactly the taps sent, no stray strokes |
| Recording coordinates | Accurate | e.g. tap at 948,222 stored as 0.8778 / 0.0949 |
| Saving | Works | 9/9 recordings with input saved; quick-stop 0.1 s still saved; list survives reopening. The earlier lost recording was not reproduced |
| Replay, short macros | Works in narrow tests | 14/14 verified runs did every step once and ended on the recorded screen (4-5 steps, Settings only) |
| Replay, 12 steps | Works in narrow tests | 5/5, final screen only; intermediate screens not checked |
| Swipe | Works | 3/3, list ended within about 13 px of the recording |
| Stops on a wrong screen | Works once | 1/1: expected `com.android.settings`, saw the launcher, no tap sent. The on-screen stop message was not captured |
| Replay speed | **Improved, partly verified** | Was about 6 s per step for a 3 s recorded gap. Cause found for anchors with no id/text/description: they can never match but were waited on for 2.5 s. Fixed in `97f800d`. S22+: a 2-swipe macro (both anchors unusable) replayed with 0-2 ms wait and 2.9 s between steps (recorded 2.5 s + dispatch). Anchors that can match were not retested; `Play:` logs now show scan and resolve times |
| Other apps, slow screens, long idle gaps | **Not tested** | |
| Voice, ERROR_CLIENT loop | **Fixed** (another session, own evidence) | Cause: SayDo's own audio-focus request was taken away each time the recognizer started, which cancelled the session. In a 60 s silent window: 40 CLIENT errors and 40 starts before, 0 CLIENT errors and 9 starts after (`13d19b0`) |
| Voice triggers, recognition quality | **Not tested** | No spoken trigger has been measured |
| First START | **Changed** (`58286b2`) | Asks only for overlay, accessibility, microphone, notifications. Battery exemption is a one-time optional prompt; speech pack status is shown in the app |
| Status text on main screen | **Changed** (`e0964b2`) | Follows VoiceService state; no longer stuck on "Starting" |
| Delete | **Changed** (`184a532`) | Confirmation dialog instead of tap-twice |
| Pause before a step with a known app | **Capped at 3 s** (`a46f7a4`) | An 8.14 s recorded pause replayed as 3 s; logged when capped |
| Voice gate | **Not tested** | Off by default |
| MCP relay | Deployed, E2E test passes locally | Simulated phone only |
| MCP on the phone | **Not tested** | Off by default |

## Fixed, with evidence

- START button covering the list (`97f800d`): list bottom y=2036, button top y=2059 on the S22+. Not re-tested with 8+ macros.
- Anchors that can never match cost 2.5 s per step (`97f800d`): fixed; a 2-swipe macro replayed with 2.9 s between steps.
- One tap = one stroke, saving, short Settings-only replay: see the table.
- Voice `ERROR_CLIENT` loop, stale "Starting" status, confusing first START, tap-twice delete, long pauses: see the table.

## Still open

1. **Beeps.** Google's own speech service plays a sound at the start and end of each listening session, about every 5 s. Not muted, because that is not clearly safe.
2. **Top-edge swipe is not recorded.** The status-bar region belongs to SystemUI; probably not fixable from the overlay.
3. **Voice recognition quality** is unmeasured.
4. **Voice gate** is untested (off by default).
5. **Replay in third-party apps, slow screens, long idle gaps** are untested.
6. **MCP on the phone** is untested (off by default). The relay has an end-to-end test with a simulated phone only. Hardening in git (rate limits, Origin check, caps) is **not deployed**; owner decisions are in the relay review notes of the final report.
7. **8+ macros on the main screen** not checked.
8. **Uzbek triggers** are not supported or tested.
9. Slow replay for anchors that *can* match: not measured.
10. Accessibility via ADB right after install sometimes does not stick; setting it again works.
11. The on-screen "stopped on a wrong screen" message was never captured.

## Cleanup check

Removed unused code under `kernel/` (daemon, mesh, Vulkan pipeline, chaos engine, vault and others; `SayDoKernel.start()` was never called) and the docs that described it. Kept the classes the app uses: `NodeAnchor`, `SmartGestureDispatcher`, `MacroStudioActivity`, `AppShortcutManager`, `ExecutionHudOverlay`, `SayDoTileService`, `ParametricMatcher`, `MultilingualMatcher`, `ContextMemory`. Compile check, CI build and CI unit tests are the evidence; the phone smoke test result is in the final report (not repeated here unless recorded in a later edit).

## Corrections to earlier claims

- The "85-95% for short macros" figure was a guess, and too optimistic.
- The "0 failures" claim in the old Omega certificate was false (6 tests were failing). The certificate is deleted.
- `docs/ROADMAP.md` said anchors permanently change coordinates. That was wrong (`parse()` builds new objects per run); the line is corrected.
- Replay reliability, voice accuracy and battery use have not been measured.
