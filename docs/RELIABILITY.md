# SayDo reliability notes

What the code does to survive on real phones. None of this is measured; see `docs/STATUS.md` for what has been seen
working. Background survival depends on the phone's battery settings.

## OEM background limits

`util/OemPermissionHelper` detects the manufacturer and opens the vendor's autostart or battery screen where one is
known (Xiaomi, Samsung, Huawei, Oppo, Vivo, OnePlus), otherwise the standard battery-optimization and app-details
screens. The component names are guesses from public sources and differ between OS versions; only Samsung was
tried on a phone (S22+), and the first START now asks for battery exemption only once, as an optional step.

## SpeechRecognizer errors

`VoiceService` handles each `SpeechRecognizer` error code with a restart delay: no-match and speech-timeout restart
quickly, busy and client errors back off and recreate the recognizer, audio errors recreate it, and missing
permission stops the service. The delays are in the code (`VoiceService.onError`), not repeated here.

The earlier `ERROR_CLIENT` loop (about one per second) was caused by SayDo's own audio-focus request being taken away
each time the recognizer started. Audio focus is no longer requested. A 60 s silent test went from 40 CLIENT errors to
0. Recognition quality is untested. Google's speech service still plays a sound at the start and end of each session.

## Crash log and memory

`SayDoApp` writes uncaught exceptions with device model, Android version and memory numbers to `app_crash.log` and
trims the file when it is large. `onTrimMemory` is forwarded to registered listeners.

## Lock screen

Macros recorded on the lock screen are flagged `locked`. `Runner` wakes the screen before replay. A normal macro
spoken while the phone is locked is queued for 45 s and started after `ACTION_USER_PRESENT`. Not tested on a phone.
Screens with `FLAG_SECURE` cannot be read by Accessibility.
