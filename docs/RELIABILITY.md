# SayDo Reliability & Production Engineering Guide

## 1. OEM Background Autostart & Power Management Engine

Android device manufacturers employ aggressive background task killers that terminate accessibility and audio services. SayDo integrates a specialized [`OemPermissionHelper`](file:///home/muhamad/SayDo/app/src/main/java/uz/saydo/app/util/OemPermissionHelper.java) that detects manufacturer signatures and launches vendor-specific configuration screens.

### 1.1 Vendor Dispatch Matrix

| Manufacturer | System Packages / Target Activities | Purpose |
| :--- | :--- | :--- |
| **Xiaomi / Redmi / Poco** | `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`<br>`miui.intent.action.OP_AUTO_START`<br>`com.miui.securitycenter/com.miui.powercenter.PowerSettings` | Autostart permission & No Battery Restrictions |
| **Samsung** | `com.samsung.android.lool/com.samsung.android.sm.battery.ui.BatteryActivity`<br>`com.samsung.android.sm/com.samsung.android.sm.ui.battery.BatteryActivity` | Never Sleeping Apps whitelist |
| **Huawei / Honor** | `com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity`<br>`com.huawei.systemmanager/com.huawei.systemmanager.optimize.bootstart.BootStartActivity` | Protected Apps startup manager |
| **Oppo / Realme** | `com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity`<br>`com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity` | Background autostart permission |
| **OnePlus** | `com.oneplus.security/com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity` | Deep background optimization bypass |
| **Vivo / iQOO** | `com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity`<br>`com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity` | High background power consumption allow |
| **Standard Android** | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`<br>`ACTION_APPLICATION_DETAILS_SETTINGS` | Doze Mode battery exemption |

---

## 2. SpeechRecognizer Error Resilience & Auto-Recovery Matrix

[`VoiceService`](file:///home/muhamad/SayDo/app/src/main/java/uz/saydo/app/VoiceService.java) implements a specialized recovery action for all 13+ Android `SpeechRecognizer` error codes:

| Error Code | Error Name | FSM State Transition | Recovery Action & Timing |
| :---: | :--- | :--- | :--- |
| **1** | `ERROR_NETWORK_TIMEOUT` | `BACKOFF_RECOVERY` | Backoff 200ms -> restart offline recognition |
| **2** | `ERROR_NETWORK` | `BACKOFF_RECOVERY` | Non-fatal in offline mode; restart immediately |
| **3** | `ERROR_AUDIO` | `BACKOFF_RECOVERY` | Cleanly re-instantiate recognizer; 400ms guard interval |
| **4** | `ERROR_SERVER` | `BACKOFF_RECOVERY` | Reset state, backoff 300ms |
| **5** | `ERROR_CLIENT` | `BACKOFF_RECOVERY` | Exponential backoff (150ms -> 300ms -> 600ms); recreate after 2 errors |
| **6** | `ERROR_SPEECH_TIMEOUT` | `COOLDOWN` | Normal silence detection; seamless restart in 100ms |
| **7** | `ERROR_NO_MATCH` | `COOLDOWN` | Speech not matched to shortcuts; restart in 100ms |
| **8** | `ERROR_RECOGNIZER_BUSY`| `BACKOFF_RECOVERY` | Destroy old instance; 250ms guard interval; fresh instance |
| **9** | `ERROR_INSUFFICIENT_PERMISSIONS` | `STOPPED` | Revocation detected; notify user, stopForeground, stopSelf |
| **10**| `ERROR_TOO_MANY_REQUESTS` | `BACKOFF_RECOVERY` | Throttle and backoff 1200ms |
| **11**| `ERROR_SERVER_DISCONNECTED` | `BACKOFF_RECOVERY` | Re-instantiate recognizer; restart in 500ms |
| **12**| `ERROR_LANGUAGE_NOT_SUPPORTED` | `BACKOFF_RECOVERY` | Toast prompt for offline English pack; backoff 1000ms |
| **13**| `ERROR_LANGUAGE_UNAVAILABLE` | `BACKOFF_RECOVERY` | Launch direct intent to Google offline voice pack download |
| **14**| `ERROR_CANNOT_CHECK_SUPPORT` | `PREPARING` | Bypass support probe; proceed with offline recognition intent |

---

## 3. Circular Crash Recording & Crash Loop Prevention

### 3.1 Uncaught Exception Handler
[`SayDoApp`](file:///home/muhamad/SayDo/app/src/main/java/uz/saydo/app/SayDoApp.java) installs a global uncaught exception interceptor before any service or activity initializes:
- Intercepts all unexpected crashes on background and UI threads.
- Gathers hardware metrics: `Build.MANUFACTURER`, `Build.MODEL`, `Build.VERSION.SDK_INT`, and memory statistics (free, total, max heap).
- Writes structured entries to `app_crash.log` in internal app files storage.

### 3.2 128 KB Circular Trimming
If `app_crash.log` exceeds 128 KB, `SayDoApp` truncates older log entries while retaining the most recent 64 KB of structured traces, preventing disk fill.

### 3.3 System Memory Pressure Management
Implements `ComponentCallbacks2.onTrimMemory(int level)`:
- On `TRIM_MEMORY_RUNNING_CRITICAL` or `TRIM_MEMORY_COMPLETE`, delegates to registered memory trim listeners.
- `MacroRepository` and services clear non-critical buffers, preventing Out-Of-Memory (OOM) kills.

---

## 4. Audio Focus & Phone Call Interruption Handling

`VoiceService` requests audio focus using `AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` with `AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY`.

- **Incoming Phone Call / Ringtone**: `AudioManager.OnAudioFocusChangeListener` receives `AUDIOFOCUS_LOSS` or `AUDIOFOCUS_LOSS_TRANSIENT`. The speech engine immediately pauses recognition, releases the partial wake lock, and updates the notification to `"Microphone paused for call/audio"`.
- **Call Disconnected**: Audio focus is restored (`AUDIOFOCUS_GAIN`), and `VoiceService` seamlessly resumes listening within 500ms.

---

## 5. Lock-Screen Macro Hardening

Macros recorded on the lock screen are flagged with `locked: true`.
- **Wake-up**: Replay begins with a screen bright wake-up via `PowerManager.ACQUIRE_CAUSES_WAKEUP`.
- **Direct Execution**: Lock-screen macros replay lock-screen swipes and PIN digit taps without navigating to the Home launcher.
- **Normal Macros Spoken at Lock Screen**: Queued in memory for up to 45 seconds (`queuedMacro`). When the user unlocks the device (`Intent.ACTION_USER_PRESENT`), SayDo navigates to Home and executes the queued macro automatically.
