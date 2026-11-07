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

## Human tests needed for branch `safe-smart` (nothing here has run on a phone)

Run on a test phone the owner agreed to, after the replay work is merged. Do not use banking or personal apps; use the Settings app's "Confirm screen lock" page or a throw-away app with a biometric prompt. Record each result in `docs/STATUS.md` with the build.

1. Fingerprint wait, recording. Start a recording, open an app screen that asks for the fingerprint (Settings > Security > screen lock confirm), pass it with the finger, stop. Expect: the list item has a step `wait_user (owner: biometric)` (check with `adb logcat -d | grep "Rec:"`: `auth prompt opened ...`, `wait_user biometric`) and no tap on the prompt.
2. Fingerprint wait, replay. Run that macro. Expect: the phone vibrates, toast and notification "Barmoq izingizni qo'ying", the macro waits, and after the finger it goes on within about 1 s. Write down how long it waited.
3. Timeout. Run again and do nothing for 60 s. Expect: the macro stops, toast says the wait ran out; `run_macro` through MCP (if MCP is on) says "waited 60 s".
4. No prompt shown. Run the macro when the phone is already unlocked and the app does not ask. Expect: it waits about 5 s and continues.
5. Secure screen. Repeat 2 on a screen with FLAG_SECURE. Expect the same result (screen content is never read).
6. Window names. If 1 recorded no `wait_user`, copy the `auth prompt` window class/package from `adb shell dumpsys window | grep -i biometric` and from `dumpsys accessibility` events, send them to the developer; `AuthDetector` has to learn them.
7. PIN recording. Choose "PIN" at the prompt. Expect: steps recorded, no digit appears in `adb logcat | grep SayDoDebug`, the list shows the lock badge and "PIN, private", the first-time dialog appears once.
8. PIN on disk. With a debuggable build or a rooted phone only (release builds block `run-as`): `macros_v2.json` must contain `"type":"enc"` steps and no digits. If you cannot read the file, say so; do not claim it.
9. PIN replay. Run the PIN macro by voice and from the list. Expect the keypad taps to land; note whether it works on the lock screen and in Settings confirm.
10. PIN hidden from AI. With MCP on: `list_macros`, `find_macro`, `describe_macro` and `run_macro` must not show or run it; export (if there is a UI path) must not include it; importing a file must not delete it.
11. Keystore loss. Not testable safely; skip. Known: a reset phone drops these macros.
12. Money guard. With a throw-away app whose package contains "bank" or "wallet" in front, call `read_screen` and `screenshot` through MCP: both must be refused; `device_status` and `press home` must work.
13. Approval spoofing. On an Android 12 or older phone (if one exists) start a sensitive `run_macro`, then try `adb shell am broadcast -a uz.saydo.app.MCP_APPROVAL.1 --ez allow true`. Expect: nothing happens, the notification stays until the owner answers.
14. Backup. `adb shell bmgr` / `adb backup` must not include the app data (Android 12+: check `dumpsys backup` for the package). Optional.
15. Search. With the owner's real macros call `find_macro` with: "yoqtirgan musiqa", "budilnik qo'y", "включи фонарик", "pochtani tekshir". Write the top result and score for each; the first ones that rank wrong go back to the developer.
16. Stop messages. Make a macro stop (change the screen so a button is missing) and run it through MCP. Expect `stopped "<name>" at step N of M (...)` with a reason sentence.
