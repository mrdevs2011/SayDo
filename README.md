# SayDo (`uz.saydo.app`)

Say it — done. Voice-triggered touch macros for Android.

Record a sequence of taps, holds and swipes with the floating bubble, give it a voice command, and SayDo replays it when you say the command.

> **Status: beta.** Voice triggering and replay outside the Settings app are not verified.
> See [docs/STATUS.md](docs/STATUS.md) for what has been seen working on a real phone and what has not,
> [docs/PLAN.md](docs/PLAN.md) for the order of work, and [docs/BENCHMARK.md](docs/BENCHMARK.md) for how
> reliability is measured.

---

## What it does today

- **Voice triggers (English).** Uses Android's built-in `SpeechRecognizer`. It prefers the on-device engine when the
  device has one; otherwise it may fall back to the online Google engine. There can be short gaps between listening
  sessions where a command is missed.
- **Gesture recording and replay.** Taps, holds and swipes are recorded with their timing and replayed through the
  Accessibility service. Before a step, replay waits for the recorded app to be in front (up to 8 s) and stops with
  a message if it does not appear. Only short macros in the Settings app have been verified on a phone.
- **Lock-screen macros.** Macros can be recorded and started from the lock screen. Screens that block accessibility
  (`FLAG_SECURE`, many banking and PIN screens) cannot be automated reliably.
- **Guided setup.** The `START` button walks through only the permissions that are still missing (overlay,
  accessibility, microphone, notifications, battery optimization).
- **OEM hints** for keeping the background service alive on Xiaomi, Samsung, Huawei, Oppo, Vivo and OnePlus.
- **Voice gate (optional, off by default).** Tap **Ovoz ⚙** on the main screen. When on, speech recognition
  starts only after a sound louder than a per-phone calibrated threshold, instead of running all the time. On
  Android 13+ the last 1.2 s of audio is kept and handed to the recognizer, so the start of the trigger is not lost;
  if a phone's recognizer does not accept that stream, SayDo switches to restart mode automatically. On Android 12
  and older the start of the trigger can be cut off. The microphone stays open to measure the level (the system mic
  indicator stays on), and nothing runs when listening is switched off in the app.
- **Built-in benchmark.** Long-press the SayDo title → Debug → Benchmark to measure voice and replay reliability on
  your own phone.

## Known limitations

- English triggers only.
- Replay checks the app (package) of each step, not the exact screen inside the app.
- The app's own "done" message is not proof that the macro succeeded.
- Background survival depends on the phone's battery settings.

---

## Documentation

- [Status](docs/STATUS.md) and [Plan](docs/PLAN.md) — what works on a phone, what is next
- [Roadmap](docs/ROADMAP.md) — older reliability plan (superseded by the plan where they differ)
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
