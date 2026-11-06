# SayDo

Android app (Java, `uz.saydo.app`) that records touch macros and replays them on a voice command.
`relay/` is a separate TypeScript Vercel project for MCP remote control.

Read `docs/STATUS.md` and `docs/PLAN.md` first.

## Rules

- Nothing counts as working until it has been seen on a real phone. Unit tests stub Android.
- Do not give success rates that were not measured.
- No new features until recording and replay pass on a phone (see `docs/PLAN.md`).
- Only use a phone the owner has agreed to. Ask before changing a phone you were not told to use.
- Always target a phone with `adb -s <serial>`. Check `adb devices -l` first.
- On real phones, do not open messaging, banking or personal apps, take photos, or send anything.
- Never commit `.env*`, `.vercel/` or `relay/node_modules`.

## Build

- There is no Gradle wrapper and no local Gradle. APKs are built by GitHub Actions on push to `main`.
- `gh run download <run> -n SayDo-apk -D <dir>` fetches the APK; `adb -s <serial> install -r` installs it.
- The `Test` workflow does not run on `main`: `gh workflow run pr-check.yml --ref main`.
- Quick compile check: `javac` against `/opt/android-sdk/platforms/android-36/android.jar`, excluding
  `MainActivity.java` and `MacroAdapter.java` (androidx), with stubs for those and `R`.

## Debugging on a phone

- Release builds are not debuggable, so `run-as` does not work.
- Logs: `adb logcat -d | grep SayDoDebug`. Prefixes: `Rec:`, `Play:`, `Home:`, `Toast:`.
- Grant by ADB: `pm grant` for mic and notifications, `appops set uz.saydo.app SYSTEM_ALERT_WINDOW allow`.
- Accessibility: `settings put secure enabled_accessibility_services uz.saydo.app/uz.saydo.app.GestureService`,
  then `settings put secure accessibility_enabled 1`. Check `dumpsys accessibility`; retry if not bound.
- `am force-stop` unbinds Accessibility.

## Relay

`cd relay && npm test` (needs `redis-server`). Deploy with `vercel deploy --prod`.
