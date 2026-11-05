# SayDo (`uz.saydo.app`)

Say it — done. Voice-triggered touch macros for Android.

Record a sequence of taps, holds and swipes with the floating bubble, give it a voice command, and SayDo replays it when you say the command.

> **Status: beta.** Voice triggering and long-macro replay are not yet reliable on every device.
> See [docs/ROADMAP.md](docs/ROADMAP.md) for the known problems and the plan, and
> [docs/BENCHMARK.md](docs/BENCHMARK.md) for how reliability is measured.

---

## What it does today

- **Voice triggers (English).** Uses Android's built-in `SpeechRecognizer`. It prefers the on-device engine when the
  device has one; otherwise it may fall back to the online Google engine. There can be short gaps between listening
  sessions where a command is missed.
- **Gesture recording and replay.** Taps, holds and swipes are recorded with their timing and replayed through the
  Accessibility service. Replay is time-based and does not yet check that the expected screen appeared, so long
  macros can drift if an app loads slower than during recording.
- **Lock-screen macros.** Macros can be recorded and started from the lock screen. Screens that block accessibility
  (`FLAG_SECURE`, many banking and PIN screens) cannot be automated reliably.
- **Guided setup.** The `START` button walks through only the permissions that are still missing (overlay,
  accessibility, microphone, notifications, battery optimization).
- **OEM hints** for keeping the background service alive on Xiaomi, Samsung, Huawei, Oppo, Vivo and OnePlus.
- **Built-in benchmark.** Long-press the SayDo title → Debug → Benchmark to measure voice and replay reliability on
  your own phone.

## Known limitations

- English triggers only.
- Replay is open-loop: one failed step is not detected, and the following steps continue on the wrong screen.
- The app's own "done" message is not proof that the macro succeeded.
- Background survival depends on the phone's battery settings.

---

## Documentation

- [Roadmap](docs/ROADMAP.md) — reliability plan and stage criteria
- [Benchmark protocol](docs/BENCHMARK.md) — how to measure a release
- [Architecture](docs/ARCHITECTURE.md)
- [Reliability & OEM guide](docs/RELIABILITY.md)

---

## Building

Prerequisites: JDK 17, Android SDK (compileSdk 35, minSdk 26, targetSdk 34), Gradle 8.9.
The repository has no Gradle wrapper; CI uses Gradle 8.9 (see `.github/workflows/build.yml`).

```bash
gradle testDebugUnitTest   # unit tests (JVM, mocked Android)
gradle assembleRelease     # APK
```

Unit tests cover pure logic (trigger matching, resampling, storage, metrics). They do not prove on-device
behaviour; use the benchmark for that.

## Installation

Download the APK from [Releases](https://github.com/mrdevs2011/SayDo/releases).
