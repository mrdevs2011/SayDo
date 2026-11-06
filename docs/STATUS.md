# SayDo status

Last updated: 2026-11-06. Version on `main`: 2.12.0 (`a0fe5f0`).

Only what was observed on a real phone counts as working. Unit tests run on a JVM with Android
stubbed, so they cannot prove on-device behaviour.

## State by feature

| Feature | State | Evidence |
|---|---|---|
| Install, launch, main screen | Works | Galaxy A12, Android 13 |
| Guided setup (START) | Works, poor UX | Sends the user to "Device assistance app", where there is nothing to do |
| Start from launcher default page | Works | Galaxy A12, build `3b93285` |
| Recording coordinates | Accurate | Tap at 583x1460 stored as 0.8097 x 0.9125 |
| Recording: one tap = one stroke | **Fix unverified** | Bug seen on device: one tap saved 3 times. Fix in `a0fe5f0`, not yet seen working |
| Saving a macro | **Unreliable** | First recording was lost, second saved. Cause unknown |
| Replay | **Never run on a phone** | |
| Closed-loop replay (`pkg` wait, stop on wrong app) | **Never run on a phone** | |
| Voice triggers | **Never tested on a phone** | |
| Voice gate (loudness) | **Never tested on a phone** | Off by default |
| Benchmark screen | **Never run on a phone** | |
| MCP relay | Deployed, E2E test passes locally | `https://saydo-relay.vercel.app` answers; simulated phone only |
| MCP on the phone | **Never run on a phone** | Off by default |

## Known bugs

1. **Lost recording.** A recording made from the SayDo screen did not save. It may have been a
   side effect of the old home-detection bug. Not reproduced or explained.
2. **Speech pack prompt.** The 62 MB system dialog appeared over the camera. `a0fe5f0` limits it to
   when SayDo is in front; unverified.
3. **Stale status text.** The main screen stays on "Starting..." until it is reopened.
4. **Beeps.** Probably from `SpeechRecognizer` restarting. Not confirmed.
5. **Bubble covers UI.** The floating button overlapped the trigger panel and a dialog button.
6. **Accessibility via ADB right after install** did not stick the first time; setting it again worked.

## Corrections to earlier claims

- The "85-95% for short macros" figure was a guess, and too optimistic.
- `docs/OMEGA_VERIFICATION_CERTIFICATE.md` and the "0 failures" claim are not reliable: 6 tests were
  failing at the time.
- `docs/ROADMAP.md` says anchors permanently change coordinates. That is wrong: `parse()` builds new
  objects per run.
- Replay reliability, voice accuracy and battery use have not been measured.
