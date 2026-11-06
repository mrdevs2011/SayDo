# SayDo architecture

A short description of what is in the code. Behaviour on a real phone is in `docs/STATUS.md`.
Nothing here is a performance or reliability claim.

## Components

- `MainActivity`, `TriggerActivity`, `MacroStudioActivity`: UI. The main screen lists macros and has the `START` button.
- `FloatService`: floating bubble and the recording overlay.
- `GestureService` (AccessibilityService): dispatches taps, holds and swipes, and reads the screen for anchors and the
  current app. `SmartGestureDispatcher` and `NodeAnchor` (in `kernel/`) are used by it.
- `Runner`: starts a macro (wake, go Home, then `GestureService`).
- `VoiceService`: foreground service. Uses Android `SpeechRecognizer`, asks for the on-device engine first
  (`EXTRA_PREFER_OFFLINE`); it may use the online engine when no offline pack is available. Matches the result with
  `TriggerMatcher` (and `ParametricMatcher` / `MultilingualMatcher` from `kernel/voice`).
- `McpService`, `McpRelay`, `McpCommands`: optional remote control through the `relay/` project. Off by default.
- `audio/VoiceGate`: optional loudness gate. Off by default.
- `data/MacroRepository`: macros in `macros_v2.json`.
- `metrics`, `BenchmarkActivity`: local measurements.

## Storage

`MacroRepository` writes a temporary file, syncs it, keeps the old file as `macros_v2.json.bak` and renames the new
one into place. A missing or corrupt main file is restored from the `.bak`. Access is guarded by a
`ReentrantReadWriteLock`. Old v1 SharedPreferences data is migrated.

## Recording and replay

- Recording: the overlay catches touches and re-injects them, storing points normalized to 0..1 with timestamps.
  Each stroke stores the package of the app in front and, when found, a `NodeAnchor` (id, text or description of the
  touched view).
- Replay: for each step, wait for the recorded app (up to 8 s, pause before a step with a known app capped at 3 s),
  try the anchor for up to 2.5 s (skipped when it can never match), otherwise use coordinates. The path is resampled
  and smoothed (`GestureResampler`) and split into segments when longer than the system maximum gesture duration.
  A cancelled gesture is retried once; then replay stops.
- Not done: checking the exact screen inside an app, `FLAG_SECURE` screens.

## Voice

`VoiceService` restarts a `SpeechRecognizer` session repeatedly; each error code has its own restart delay.
The service does not request audio focus. The sessions are short and Google's speech service plays its own start and
end sounds (open problem, see `docs/STATUS.md`). `TriggerMatcher` normalizes text and compares with edit distance
and a phonetic key. Recognition quality has not been measured.

## Crash log

`SayDoApp` installs an uncaught-exception handler that writes to `app_crash.log` (trimmed when it grows large).

## `kernel/`

Historic package name. After cleanup it holds only classes that other code uses (see `docs/STATUS.md`). The earlier
"micro-kernel" (daemon, mesh, Vulkan, chaos engine and so on) was never started and has been removed.
