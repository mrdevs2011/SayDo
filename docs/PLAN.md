# SayDo plan

This replaces the order of work in `docs/ROADMAP.md`.

**Rule: a stage closes only after it passes on a real phone, with evidence.** No new features until
recording and replay pass.

## Stage 0: Verify the triple-tap fix (done 2026-11-06, passed)

- Install `a0fe5f0` on a test phone.
- Record a short macro in a harmless app. Pass: one tap gives one stroke in the `Rec:` logs.
- Replay it. Pass: the app ends on the same screen as the recording, each step once.
- Try to reproduce the lost recording.
- Result decides Stage 1.

Result: passed on a Galaxy S22+ for Settings-only paths (see `docs/STATUS.md`). The current
design stays for now. Before Stage 1 is closed, replay needs testing in real apps, and START
button overlap and replay speed need fixing.

## Stage 1: Decide on the recording and replay core

Measure 5 short and 2 long macros, 10 runs each, in normal apps.

- Mostly passing: keep the overlay, fix what fails.
- Still failing: rewrite the core (Stage 2).

## Stage 2: Core rewrite (if needed)

- **Recording:** remove the catch-and-reinject overlay. Record the pressed view from Accessibility
  events and let touches reach the app directly.
- **Replay:** find the view by text or id, click it, confirm the screen changed, stop on mismatch.
- **Fallback:** raw coordinates for games and custom-drawn screens, marked as less reliable.

Keep: `TriggerMatcher`, `MacroRepository`, `Setup`, main UI.

## Stage 3: Voice

- Measure the current engine with the Benchmark screen.
- Prototype on-device keyword recognition (Vosk, grammar limited to saved triggers).
- Switch only if it clearly beats the baseline. This should also remove the restart beeps.

## Stage 4: Cleanup

- Delete unused `kernel/` code; move the few used pieces out first.
- Remove `OMEGA_VERIFICATION_CERTIFICATE.md` and correct `ARCHITECTURE.md` and `RELIABILITY.md`.
- Replace the "Device assistance app" setup step.

## Stage 5: MCP

Test on a phone only after Stages 0-3. It controls the phone remotely, so review token handling,
rate limits and open device registration before anyone else uses it.
