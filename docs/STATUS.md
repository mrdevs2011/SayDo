# SayDo status

Last updated: 2026-11-06. Version on `main`: 2.12.0 (`1dffca6`).

Only what was observed on a real phone counts as working. Unit tests run on a JVM with Android
stubbed, so they cannot prove on-device behaviour.

## State by feature

Stage 0 was run on a Galaxy S22+ (SM-S906B, Android 16) with build `1dffca6`, Settings app only.

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
| Voice triggers | **Not tested** | `onError code=5 CLIENT` appears about once a second in the log |
| Voice gate | **Not tested** | Off by default |
| MCP relay | Deployed, E2E test passes locally | Simulated phone only |
| MCP on the phone | **Not tested** | Off by default |

## Known bugs

1. ~~START button covers the list.~~ Fixed in `97f800d`: the list now ends above the button (S22+: list bottom y=2036, button top y=2059). Not re-tested with 8+ macros.
2. **Slow replay** for anchors that can match: not measured yet.
3. **Voice `CLIENT` error** roughly once per second; may be what causes the beeps. Not investigated.
4. **Stale status text.** The main screen stays on "Starting..." until it is reopened.
5. **Confusing first START.** On a fresh install it sends the user to the Samsung battery page, then to "Digital assistant app", where there is nothing to do.
6. **Status-bar swipe not recorded.** A swipe starting at the very top edge is not captured by the overlay.
7. **Delete button** needs two taps within about 2 s; easy to miss.
8. **Accessibility via ADB** right after install sometimes does not stick; setting it again works.
9. **Speech pack.** A "Downloading offline English voice pack" toast appears; the system dialog over other apps was not seen again.

## Corrections to earlier claims

- The "85-95% for short macros" figure was a guess, and too optimistic.
- `docs/OMEGA_VERIFICATION_CERTIFICATE.md` and the "0 failures" claim are not reliable: 6 tests were
  failing at the time.
- `docs/ROADMAP.md` says anchors permanently change coordinates. That is wrong: `parse()` builds new
  objects per run.
- Replay reliability, voice accuracy and battery use have not been measured.
