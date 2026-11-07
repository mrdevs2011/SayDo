# SayDo status

Last updated: 2026-11-06. Version on `main`: 2.15.0 (versionCode 28). Newest verified-on-phone builds are listed per row.

Only what was observed on a real phone counts as working. Unit tests run on a JVM with Android
stubbed, so they cannot prove on-device behaviour.

## State by feature

Stage 0 was run on a Galaxy S22+ (SM-S906B, Android 16) with build `1dffca6`, Settings app only. Later fixes were checked on the same phone with the builds named in the Evidence column. The cleanup commits after `13d19b0` (removal of unused `kernel/` code, quieter replay log, relay hardening) are covered by the final smoke test described under "Cleanup check" below.

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
| Voice, ERROR_CLIENT loop | **Fixed** (another session, own evidence) | Cause: SayDo's own audio-focus request was taken away each time the recognizer started, which cancelled the session. In a 60 s silent window: 40 CLIENT errors and 40 starts before, 0 CLIENT errors and 9 starts after (`13d19b0`) |
| Voice triggers, recognition quality | **Not tested** | No spoken trigger has been measured |
| First START | **Changed** (`58286b2`) | Asks only for overlay, accessibility, microphone, notifications. Battery exemption is a one-time optional prompt; speech pack status is shown in the app |
| Status text on main screen | **Changed** (`e0964b2`) | Follows VoiceService state; no longer stuck on "Starting" |
| Delete | **Changed** (`184a532`) | Confirmation dialog instead of tap-twice |
| Pause before a step with a known app | **Capped at 3 s** (`a46f7a4`) | An 8.14 s recorded pause replayed as 3 s; logged when capped |
| Voice gate | **Not tested** | Off by default |
| MCP relay | Deployed (older code, no rate limits), E2E test passes locally | Also tested against the live relay with the real phone, see next row |
| MCP on the phone | **Works against the live relay** (S22+, build 2.12.0) | tools/list = 14 tools; device_status, list_macros, press home/back, open_app, read_screen (20-34 numbered elements, counts only), tap, run_macro of a 2-step macro (completed, 6.5 s incl. one 3 s capped pause) all returned ok. Errors: unknown macro and tap outside the screen return isError with a clear text; negative coordinate is rejected by schema; 2 wrong tokens got 401. Timings (single samples, via relay from this PC): tap 0.5-1.5 s, read_screen 1.1-2.9 s, run_macro 6.5 s. First "connected" in the app appeared about 26 s after switching on (first long poll). Not tested: screenshot, list_apps, type_text, click_element, swipe, long_press (privacy rules) |
| MCP token rotation with MCP off | **Bug found, fixed (`a49d4b2`) and verified on the phone** | Before the fix: old token still HTTP 200 after rotation with MCP off. After (build of `805bc06`): token A 200; rotated with MCP on: A 401; B 200; MCP off and rotated: B 401, log `token rotated while off, register HTTP 200`; MCP service gone. Old note: | On the phone, rotating the token with MCP off left the OLD token valid on the relay (a tools/list with the old token returned 200). Reason: the new hash is only registered when MCP starts. Fix registers the new hash right after rotation |
| Replay in Calculator | **Works** | Samsung Calculator, 11-tap macro (12+34x5-67), recorded 11/11 strokes for 11 inputs; replay 5/5: every step once, result 115 each time. All steps were done by accessibility click on the anchored node, not by coordinates |
| Replay in Clock | **Works** | Samsung Clock, 13 inputs (12 tab taps + 1 swipe) recorded as 13 strokes; replay 5/5: 13 steps each once, ended on the Stopwatch tab like the recording. The swipe was dispatched; its visible effect was not checked |
| Pause cap in Calculator | **Works** | 8.139 s recorded pause replayed as 3000 ms (log: `pause capped 8139 -> 3000`) |
| 9+ macros on the main screen | **Works** | 14 rows: list bottom y=2036, START top y=2059; last row's play button [714,1805][849,1940] fully visible; tapping it ran that macro (dispatch y=0.0949 = m9) and listening stayed on |
| MacroStudio, Debug, Benchmark screens | **Open without crash** | `logcat -b crash` empty. Garbled bullet in MacroStudio subtitle fixed (`0b6507a`) |
| Voice gate screen | **Opens and closes, no crash** (2 times) | Meter level for silence NOT verified: `uiautomator dump` fails there ("could not get idle state", the screen animates) and screenshots are not allowed. Gate and calibration untested |
| App icon | **APK is correct; phone still unconfirmed** | CI APK has the adaptive icon (navy background, white Say, green Do, monochrome layer); no legacy or override resources; installed APK was byte-identical to it. `am force-stop` of the launcher done; shell may not disable/enable SayDo's component (SecurityException). Cause is most likely the One UI Home icon cache. Human: look at the icon; if old, Settings > Apps > One UI Home > Storage > Clear cache (cache only), or restart the phone; also check no icon pack/theme is applied |
| MCP compact tools (2.14.0) | **Built and unit/relay tested; NOT deployed, NOT run through MCP on a phone** | Compact `read_screen` (query, bounds, max_elements), `act` (15 steps in one call), `read_notifications` (opt-in setting + Notification access), `canTakeScreenshot` in the accessibility config, bubble untouchable during commands, screenshot defaults 540/q50. JVM unit tests (format, act parser, notification buffer) and relay e2e test (schemas, act and read_notifications with a simulated phone). Live relay still has the old tools, so none of the new tools were called on a phone. Unverified: act timing and retries on real screens, Telegram flows, whether the bubble stopped swallowing taps, whether the screenshot SecurityException is gone, anything needing Notification access (the owner grants it). See `docs/MCP.md` |
| MCP macro first (2.15.0) | **Built; JVM and relay tests pass; NOT deployed; NOTHING tested on a phone** | New tools `find_macro`, `describe_macro`, `set_macro_info`, `scroll_to`; `list_macros` (paged, compact) and `run_macro` (confirm, params) changed. New macro fields `description` and `sensitiveReviewed` (old JSON loads unchanged), description generated at the end of a recording, Info dialog in MacroStudio, ranking over trigger/aliases/description/apps/anchors in Uzbek, Russian, English, money-app detection, sensitive macros need `confirm=true` plus an Allow notification on the phone (switchable), screen tools refused in money apps (switchable, default off). Evidence: JVM unit tests (ranking, descriptions, guard decisions, JSON compatibility), relay e2e with a simulated phone, CI build. **Unverified:** the approval notification (channel, heads-up, Allow/Deny, 60 s timeout, notification permission), the Info dialog, the description generated from a real recording (app labels, anchor texts), ranking quality on the owner's real macros, `scroll_to`, the money-app refusals on a real banking app, `run_macro` result text, whether `params` can ever apply (recorder makes no slots), the live relay (still the old tools). See `docs/MCP.md` |

## Branch `safe-smart`: safety and search work (written without a phone, UNTESTED on a phone)

Evidence is JVM unit tests only (263 tests pass with a local javac + JUnit run; android classes are stubs). Nothing below has
been seen on a phone. No success rates exist.

| Feature | State |
|---|---|
| `wait_user` step (fingerprint/face/lock screen) | **Built, untested on a phone.** Recording: `AuthWatch` (hook in `EventRecorder.onEvent`) + `AuthRecordLogic` + `AuthDetector` turn "an auth prompt opened and closed with no touch of the owner inside" into a step `wait_user` (action `biometric`). Replay: `WaitUserPlayer` (hook in `GestureService.step`) waits for the prompt (5 s grace; none shown = continues), vibrates, shows the toast and a notification "Barmoq izingizni qo'ying", continues when the prompt is gone, stops after 60 s with a clear message. Uses window events only, never screen content (FLAG_SECURE safe by design). **Unverified:** the window class/package names of the prompt on real phones (guessed from AOSP/Samsung, in `AuthDetector`), whether the recorder sees the prompt at all, vibration/toast/notification on the lock screen, whether an app behind the prompt gets a window event when it closes |
| PIN taps as sensitive steps | **Built, untested on a phone.** Taps made inside the auth prompt are recorded as ordinary steps with `sens=true`. On disk each such step is AES-256-GCM encrypted with a non-extractable Android Keystore key (`KeystoreBox`, `SensitiveSteps`, applied in `MacroRepository`); without Keystore those macros are not written at all. A macro with such a step is always `sensitive`, shows a lock badge in the list, is left out of export, import keeps it, and is hidden from MCP list/find/describe/run (`McpGuard.hiddenFromAi`). Descriptions never use their labels. First-time warning dialog (`PinWarning`, sets `sensitiveReviewed`). **Unverified:** that the keypad sends click events (if not, a wait_user step is recorded instead, which still works for the owner), Keystore on the phones, replay of taps in the systemui window (`appRoots` for that package), the dialog and badge. Limits: a key lost (reset/new phone) drops that macro; text typed on the on-screen keyboard is never recorded (existing rule); anyone who can start the macro on the unlocked phone makes it enter the PIN |
| Safety review | **Done by reading code, fixes untested on a phone.** See "Safety review" below |
| MCP search (synonyms, suffixes) | **Built, JVM tests only.** About 40 more concepts in Uzbek/Russian/English (alarm, volume, flashlight, taxi, notes, mail, downloads and others) and "stem + a" suffix handling. Ranking on the owner's real macros is not measured |
| Clearer stop messages | **Built, JVM tests only.** `run_macro` now says the step, its label and the likely reason (`StopReason`), unknown macro names list the closest ones |

### Safety review (read from the code; no pen test)

Fixed on this branch:
- `McpApproval`: on Android 12 and older the Allow/Deny receiver is not private, and its action name was guessable (a counter). Another app could have answered Allow. The action now has a random part.
- Money-app guard now also covers `read_screen`, `screenshot` and `scroll_to` (before, only taps/typing were refused, so a bank balance could be read to the AI).
- Logs: crash lines in `McpCommands`/`McpService` printed the full exception text; now only the class name. Checked: typed text and notification content are not logged (only lengths and tool names); PIN steps log nothing about labels.
- Backup: `allowBackup=false` was already set; added `dataExtractionRules` and `fullBackupContent=false` so device transfer is excluded too.
- PIN steps: encrypted on disk, left out of export, hidden from AI.
- `VIBRATE` permission added (normal permission) for the wait step.

Checked and fine: no exported activity except the launcher; services are `exported=false` or protected by the system bind permission; all PendingIntents are immutable; cleartext traffic off and the relay client refuses non-https; the relay stores only hashes of tokens.

Not fixed, for the owner to decide:
1. MCP token and device secret sit in plain SharedPreferences (`saydo_mcp`), app-private and not backed up. Encrypting them with the Keystore is possible but changes the registration path, so it was not done blind.
2. Error text of unexpected exceptions is still returned to the AI (`Phone error in <tool>: ... message`); messages can contain screen details.
3. The voice transcript and matched trigger are kept in the in-memory debug log and in logcat (`SayDoDebug`) and can be copied from the Debug screen. Not typed text or notifications, but speech.
4. `act`'s money-app guard is per step; an `open_app` inside `act` is covered, a money app opened by a macro run (not by AI) is out of scope.
5. Any app with Accessibility-level access on the phone can see what SayDo's accessibility service sees. Not something SayDo can change.
6. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and `SYSTEM_ALERT_WINDOW` are powerful permissions that the app needs; Google Play policy may object to the first.
7. The Allow/Deny approval depends on notification permission; without it sensitive macros are denied (safe default).

## Fixed, with evidence

- START button covering the list (`97f800d`): list bottom y=2036, button top y=2059 on the S22+. Not re-tested with 8+ macros.
- Anchors that can never match cost 2.5 s per step (`97f800d`): fixed; a 2-swipe macro replayed with 2.9 s between steps.
- One tap = one stroke, saving, short Settings-only replay: see the table.
- Voice `ERROR_CLIENT` loop, stale "Starting" status, confusing first START, tap-twice delete, long pauses: see the table.

## Still open

1. **Beeps.** Measured source: `com.google.android.tts` (Speech Services by Google, `GoogleTTSRecognitionService`, uid 10283) plays two short `AudioTrack` tones per recognition session, usage `USAGE_NOTIFICATION_EVENT` (content type MUSIC), each about 0.2-0.4 s, one at the start and one at the end. 9 sessions in 45 s, one every 5.1 s. Listening with zero saved macros starts no recognizer and therefore makes no beeps. Not fixed. Options: see the final report of the session.
2. **Top-edge swipe is not recorded.** The status-bar region belongs to SystemUI; probably not fixable from the overlay.
3. **Voice recognition quality** is unmeasured.
4. **Voice gate** is untested (off by default).
5. **Replay in the owner's own apps, slow screens, long idle gaps** are untested (Calculator and Clock pass).
6. **MCP on the phone** is untested (off by default). The relay has an end-to-end test with a simulated phone only. Hardening in git (rate limits, Origin check, caps) is **not deployed**; owner decisions are in the relay review notes of the final report.
7. **Replay always presses Home first** (`Runner`), so a macro must begin with a tap on the app icon on the launcher. A macro recorded inside an app only works if the app is opened within the first ~8 s (tested by launching it with adb after the replay started). Opening the app by adb *before* pressing play does not help. Decide whether macros should start from the current screen.
8. **Uzbek triggers** are not supported or tested.
9. Slow replay for anchors that *can* match: not measured.
10. Accessibility via ADB right after install sometimes does not stick; setting it again works.
11. The on-screen "stopped on a wrong screen" message was never captured.



## Record and replay, event-based (2.16.0): measured on a Galaxy A12 (SM-A127F, Android 13)

Recording no longer needs the touch-swallowing overlay by default. It listens to accessibility events (click, long click, text change, scroll, window change) and stores steps that name the view (id, text, description or the label of its first child); typed text is stored as text and replayed with `ACTION_SET_TEXT`; scrolls store the amount and are skipped when the next target is already visible; the target is searched by scrolling the list when it is not visible; back/home are recorded from window changes (an edge swipe produces no click, only a window change). The old overlay is still there as the "Raw touches" box in the trigger panel. Old macros replay as before (new fields are optional).

All counts are "runs that ended on the expected screen with the replay reporting success", 10 runs each, one run at a time, test apps only (Settings, Clock; the A12 has no Samsung Calculator). Macros were recorded on the phone with a script that checked each screen with `uiautomator dump` in a dry run first; the recording run itself used no dumps (a dump unbinds the accessibility service for a few seconds and loses events).

| Scenario (A12) | Result | Build / note |
|---|---|---|
| a. 5 taps in Settings | 10 of 10 | `603f455` |
| b. 11 taps in Settings, several pages | 10 of 10 | `0ce5f9d` |
| c. Clock, 9 tab taps | 10 of 10 | `603f455` |
| d1. Settings search, `input text wifi` as a text step, tap first result | 9 of 10, then 10 of 10 | `0ce5f9d`, then `737e42d`. The failure: results did not appear within 8 s on a cold start; the wait is now 15 s |
| d2. same, typed by tapping the Samsung Keyboard keys | 9 of 10, then 10 of 10 | same builds. Typing is stored as text, the key taps are not replayed |
| e. scroll twice, tap an item visible only after scrolling | 0 of 10, then 9 of 10 | `0ce5f9d` (first step ran before the list existed and was skipped; fixed), then `737e42d`. The one failure: after the second scroll the target was not found and the scroll search gave up too early (fixed in `9301213` and `79b3d89`, not re-measured) |
| f. back from the left and right edge, home from the bottom | 10 of 10 | `0ce5f9d`. Recorded as global back/home steps from window changes |
| g. popup menu opened and dismissed, a second popup, back | 10 of 10 | `0ce5f9d`. The macro replays to the same end screen but did not record the click on the popup menu item (that view sends no click event), so it is not an exact copy of what was done |
| h. 20-step mix (taps, edge back, 2 scrolls, search with typed text, home) | 8 of 10 on `0ce5f9d`; 0 of 4 on `737e42d` (stopped) | Failures seen: search results not showing after the text was set; an "up" button press ignored while the page was opening; a scroll that did not move the list. Fixes in `79b3d89` (retype the text, retry an ignored up button, keep looking for the list) are pushed but were **not measured on the phone**, because `gh` lost its token and the new APK could not be downloaded |

Recording slowness (A12, 8 taps and 16 swipes in 60 s on the Settings app; tap to the next screen resumed, from the shell clock to `wm_set_resumed_activity`, includes about 100 ms of `input` start-up that is the same in every mode):

| Mode | Tap to activity resumed | SayDo CPU over 60 s (1 tick = 10 ms) |
|---|---|---|
| Not recording, listening off | mean 111 ms (8 samples) | 4 ticks |
| Not recording, listening on | mean 115 ms (8 samples) | 95 ticks |
| Recording from events (new default), listening on | mean 115 ms (8 samples) | 49 ticks |
| Recording raw (old overlay), listening on | mean 239 ms (4 samples; 4 of 8 taps showed no screen change within 2.2 s) | 188 ticks |

So the overlay adds about 125 ms to every tap and does the most work; event recording is not measurably slower than not recording. CPU is noisy because the voice recognizer runs when listening is on (95 versus 49 ticks with the same listening state shows the noise level). I did not measure how soon a list starts moving under a finger; by construction (the raw overlay re-injects a swipe after the finger is up) it moves late in raw mode and at once in event mode.

Not measured: the old S22+ baseline numbers below (S22+, build 2.13.0, Settings/Calculator/Clock) are from the previous session and were stopped partway: a 10 of 10, b 9 of 10, c 9 of 10, d1 0 of 10, d2 5 of 10, e 3 of 10, f not valid, g and h not measured. The A12 numbers above are for the new code only; the old code was not run on the A12.

What event recording cannot capture (Android sends no event for it): taps on views that are not accessible (games, canvas, some web views, some popup menus), Enter/Search on the on-screen keyboard (type the text and tap the result instead), the Samsung Edge panel (its handle is a system window; opening it is not recorded and its items are not replayed), secure windows and other users' windows (Android blocks both for every accessibility service). A new macro that needs these: record it with "Raw touches". Android 16 `AccessibilityService` motion events only cover mouse/stylus sources, not the touch screen, so there is no way to read touch coordinates without an overlay.

Owner tests with their own apps (not done by me): (1) record a macro of 5 to 10 taps in an app you use daily, replay it 10 times, count; (2) record one with typing into a search field and a tap on the first result; (3) record one that scrolls a long list and taps an item that is only visible after scrolling; (4) record a back swipe from each edge and a home swipe; (5) record one with "Raw touches" in a game or drawing app. For each, write down which step stops the replay (the on-screen message names the step).

Found on the way: Settings declares a HOME activity, so it counted as "the launcher"; a replay that starts inside an app never opened the app (fixed: the default home app only, and the first app is opened). A `uiautomator dump` unbinds the accessibility service (new: a running recording re-subscribes on reconnect).

## Record and replay: how it worked before 2.15.0, and where it failed

Read from the code, then checked on the phone (counts in the next section).

| Thing | Recorded as | Replayed as | Where it can fail |
|---|---|---|---|
| Tap | A full-screen accessibility overlay (`FloatService.RecordView`) swallows the touch; after the finger is up it makes the overlay untouchable, waits 70 ms (`PASS_SETTLE_MS`) and re-injects the tap with `dispatchGesture`. Anchor = deepest node at the point in the active window | Wait for the app package (up to 8 s) and for the anchor (2.5 s), then `ACTION_CLICK` on the node if it is clickable, else a 20 ms tap at the node's current centre, else at the recorded point | Every tap reaches the app late (about 130 ms or more). A node that is not clickable (list text) falls back to coordinates. Anchors with no id/text/description never match |
| Keyboard key tap | Same as a tap; IME windows are not the active window, so the anchor is a node of the app behind the keyboard or missing | Raw coordinates | Only works if the keyboard is the same one, same size and already visible. Nothing waits for it. The text is never stored as text (`TYPE_TEXT` existed but nothing wrote it) |
| Text typed by `input text` / hardware keys | Not recorded at all (no touch) | Not replayed | The step is simply missing, so the next tap hits the wrong thing |
| Edge swipe (back/home/recents) | The system cancels the overlay's touch (ACTION_CANCEL); `edgeAction` turns it into a `global` step with fixed thresholds (32 dp from the sides = back, 64 dp from the bottom = home, 450 ms hold = recents) | `performGlobalAction` | Any other way of swiping (starting 40 dp from the edge, a slow swipe that does not cancel) is recorded as a plain swipe and replays as a swipe inside the app |
| Scroll / swipe | The swipe is swallowed and re-injected after the finger is up, so the list does not follow the finger. Path stored as normalised points | The path is resampled, smoothed and dispatched | The result depends on the list position and on fling momentum, so a following tap at fixed coordinates can hit another item. Nothing scrolls "until the target is visible" |
| Dialogs and popups | Taps are recorded like any other | Same | A dialog that appears late is not waited for beyond the 2.5 s anchor wait; then the tap goes to coordinates |
| Slow screens | Gaps recorded; pause capped at 3 s | Fixed wait, then the 2.5 s anchor wait | After 2.5 s the step taps coordinates anyway instead of stopping |
| Rotation | Normalised coordinates | Same | Not tested |

## Cleanup check

Removed unused code under `kernel/` (daemon, mesh, Vulkan pipeline, chaos engine, vault and others; `SayDoKernel.start()` was never called) and the docs that described it. Kept the classes the app uses: `NodeAnchor`, `SmartGestureDispatcher`, `MacroStudioActivity`, `AppShortcutManager`, `ExecutionHudOverlay`, `SayDoTileService`, `ParametricMatcher`, `MultilingualMatcher`, `ContextMemory`. Compile check, CI build and CI unit tests are the evidence; the phone smoke test result is in the final report (not repeated here unless recorded in a later edit).

## Corrections to earlier claims

- The "85-95% for short macros" figure was a guess, and too optimistic.
- The "0 failures" claim in the old Omega certificate was false (6 tests were failing). The certificate is deleted.
- `docs/ROADMAP.md` said anchors permanently change coordinates. That was wrong (`parse()` builds new objects per run); the line is corrected.
- Replay reliability, voice accuracy and battery use have not been measured.
