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
design stays for now. Since then the START button overlap and the replay delay for unusable anchors
are fixed and verified. Before Stage 1 is closed, replay still needs testing in real apps.

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

## Stage 4: Cleanup (mostly done)

- Done: unused `kernel/` code deleted; the used classes stay in `kernel/` (moving them out is optional).
- Done: Omega certificate removed; README, ARCHITECTURE, RELIABILITY and ROADMAP corrected.
- Done: first START no longer sends the user to the "Digital assistant app" page; ERROR_CLIENT loop fixed.
- Open: beeps from Google's speech service at each session start and end; top-edge swipe not recorded.

## Stage 5: MCP

Test on a phone only after Stages 0-3. It controls the phone remotely.

- Relay review done in git (rate limits, Origin check, queue and status caps, token-hash takeover check), **not deployed**.
  The owner decides on deployment, on the token in the URL path, and on closing registration (see the review notes).
- Done on the phone (2026-11-06, live relay with the older code): 14 tools, status, list, press, open_app, read_screen, tap, run_macro, error paths, wrong token 401, token rotation (bug found and fixed, see STATUS). Not tested: screenshot, list_apps, type_text, swipe, click_element. Voice gate and MCP stay off by default.
- 2.14.0 (built, not deployed, not run on a phone through MCP): compact `read_screen`, `act`, `read_notifications`, screenshot fix, bubble untouchable during commands; see `docs/MCP.md`. Next: owner approves the relay deploy, grants Notification access and the new MCP setting; then measure `act` on real screens (timing, retries) and a message flow the owner asks for.
- 2.15.0 (built, not deployed, not run on a phone): macro first. `find_macro` ranks the owner's macros against a goal in any language, `run_macro` refuses sensitive macros without `confirm` and an Allow on the phone, screen tools stop in money apps, macros get a description. See `docs/MCP.md`. Next: owner approves the relay deploy; on the phone check the approval notification, the Info dialog, the generated descriptions and `find_macro` on the real macros; then decide on `save_actions_as_macro` (design in MCP.md) after replay passes in real apps.
- Open: replay always presses Home first, so macros must start with the launcher icon tap (see STATUS).

## Stage 1 evidence so far (2026-11-06)

Calculator 5/5 (11 steps) and Clock 5/5 (13 steps incl. one swipe) replays passed; pause cap 3 s confirmed; 14 macros on the main screen fine. Still not run: the 5 short + 2 long, 10 runs each measurement, replay in the owner's own apps, voice.

## Event-based record and replay (2.16.0, 2026-11-07)

Done on the A12 with test apps: recording from accessibility events, view-based replay, typed text, scroll-until-visible, back/home from window changes (numbers in `docs/STATUS.md`). Still open:
- Re-measure h (20 steps) and e on the newest build (`79b3d89`); the APK could not be downloaded (gh token invalid). Run the Test workflow on the final commit.
- Repeat a-h on the S22+ (only the old build was measured there).
- Popup menu item clicks and Enter on the keyboard are not captured in event mode.
- Owner tests with their own apps: see the list in `docs/STATUS.md` section for 2.16.0 (record a macro with typing and a scroll in an app you use, replay it 10 times, and say which step fails).
