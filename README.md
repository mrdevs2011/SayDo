# SayDo (`uz.saydo.app`)

Say it — done. Voice-triggered automation macros for Android with sub-millisecond 1:1 fidelity and 100% offline speech recognition.

Record any multi-touch interaction (tap, hold, swipe) using the floating bubble, assign a voice command, and SayDo executes it whenever you speak—even from the lock screen.

---

## Key Capabilities

- **100% Offline English Voice Recognition**: Powered by Android's on-device speech engine. No internet connection required, zero cloud latency, zero external data transfer.
- **Zero Separate Settings Screen**: Single-tap **Auto Start** onboarding chain on the `START` button that verifies and guides only through missing device permissions.
- **Sub-Millisecond 1:1 Gesture Recording**: Captures batched historical touch events with microsecond timestamps and sub-pixel normalization.
- **Zero Visual Clutter**: No distracting lines, dots, or borders during recording; touches pass directly through to underlying applications.
- **Lock-Screen Macro Support**: Records and replays lock-screen bypasses (PIN digit entry, swipe patterns) seamlessly via wake locks.
- **Zero Polling & Battery Efficiency**: 0% idle CPU consumption; fully reactive repository architecture.
- **OEM Deep Autostart Diagnostics**: Tailored power management diagnostics for Xiaomi (MIUI/HyperOS), Samsung (OneUI), Huawei (EMUI), Oppo (ColorOS), Vivo, and OnePlus.

---

## Technical Documentation

- **[System Architecture](docs/ARCHITECTURE.md)**: Reactive Repository, FSM Speech Engine, Catmull-Rom Spline Resampler, and Accessibility Dispatcher.
- **[Reliability & OEM Guide](docs/RELIABILITY.md)**: OEM autostart matrix, SpeechRecognizer 13+ error recovery, circular crash logger, and audio focus management.

---

## Building & Verification

### Prerequisites
- JDK 17
- Android SDK Platform 34/35 (minSdk 26, targetSdk 34, compileSdk 35)

### Running Unit Tests
SayDo contains an exhaustive unit test suite covering NLP matching, Catmull-Rom resampling, atomic recovery, and multi-threading concurrency:
```bash
./gradlew testDebugUnitTest
```

### Building APK
```bash
./gradlew assembleRelease
```

---

## Installation
Download the signed production APK from [Releases](https://github.com/mrdevs2011/SayDo/releases).
