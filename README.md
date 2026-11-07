# SayDo (`uz.saydo.app`)

SayDo: touch macros for Android, controlled remotely through MCP.

Record a sequence of taps, holds and swipes with the floating bubble, name it, and replay it from the app or remotely through MCP (`relay/`). Voice control was removed in 2.15.0.

> **Status: beta.** Replay outside the Calculator, Clock and Settings apps is not verified.
> See [docs/STATUS.md](docs/STATUS.md) for what has been seen working on a real phone and what has not,
> [docs/PLAN.md](docs/PLAN.md) for the order of work, and [docs/BENCHMARK.md](docs/BENCHMARK.md) for how
> reliability is measured.

---

## What it does today

- **Gesture recording and replay.** Taps, holds and swipes are recorded with their timing and replayed through the
  Accessibility service. Before a step, replay waits for the recorded app to be in front (up to 8 s) and stops with
  a message if it does not appear. Only short macros in the Settings app have been verified on a phone.
- **Lock-screen macros.** Macros can be recorded and started from the lock screen. Screens that block accessibility
  (`FLAG_SECURE`, many banking and PIN screens) cannot be automated reliably.
- **Guided setup.** The `START` button walks through only the permissions that are still missing (overlay,
  accessibility, notifications, battery optimization).
- **OEM hints** for keeping the background service alive on Xiaomi, Samsung, Huawei, Oppo, Vivo and OnePlus.
- **Built-in benchmark.** Long-press the SayDo title → Debug → Benchmark to measure replay reliability on
  your own phone.

## Known limitations

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
