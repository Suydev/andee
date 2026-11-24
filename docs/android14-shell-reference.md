# Shell control reference — Android 14 (Shizuku shell, UID 2000)

Primary sources: live `adb shell` help on a connected Android 14 / SDK 34 device (UID 2000, `u:r:shell:s0`), AOSP `android14-release` source, the official Shizuku README, and developer.android.com. Research only — no files in the app were changed. A later addendum from the apps/power research agent has been merged into §§9–10 and §12.

## Permission model (what shell actually has)

Verified via `dumpsys package com.android.shell` and AOSP `packages/Shell/AndroidManifest.xml` (android14-release). Shell UID 2000 holds:
`WRITE_SETTINGS`, `WRITE_SECURE_SETTINGS`, `DUMP`, `CHANGE_COMPONENT_ENABLED_STATE`, `CONTROL_DISPLAY_BRIGHTNESS`, `POST_NOTIFICATIONS`, `MANAGE_NOTIFICATIONS`, `MANAGE_NOTIFICATION_LISTENERS`, `ACCESS_NOTIFICATION_POLICY`, `FORCE_STOP_PACKAGES`, `START_ACTIVITIES_FROM_BACKGROUND`, `DEVICE_POWER`, `INJECT_EVENTS`, `MANAGE_APP_OPS_MODES`, `QUERY_ALL_PACKAGES`, `READ_LOGS`, `SYSTEM_ALERT_WINDOW`, `PACKAGE_USAGE_STATS`, `ACCESS_FINE_LOCATION`, `READ_PRIVILEGED_PHONE_STATE`, `NETWORK_SETTINGS`, `BLUETOOTH_PRIVILEGED` (plus normal runtime perms).

It does **not** hold `START_ANY_ACTIVITY` — this is why `am start -n` fails on non-exported activities (§9). Shell cannot escape SELinux `u:r:shell:s0`; some privileged sockets stay denied regardless of UID.

Shizuku caveat (official README): *"ADB permissions are limited and different on various system versions"*; hidden-API restrictions apply to normal apps since Android 9. Check with `ShizukuService#checkPermission`.

Legend: **[S]** = shell-only authority (signature/privileged perm held by `com.android.shell`); **[WS]** = `WRITE_SETTINGS`; **[WSS]** = `WRITE_SECURE_SETTINGS`; **[RO]** = read-only.

## 1. Screen brightness
- `cmd display set-brightness <0.0–1.0>` — **[S]** `CONTROL_DISPLAY_BRIGHTNESS`. AOSP `DisplayManagerShellCommand.setBrightness()` calls `dm.setBrightness()`; `DisplayManagerService` enforces the permission. Value is a **float 0–1**, validated in source.
- `settings put system screen_brightness <N>` — **[WS]**. **Caveat:** N is NOT universally 0–255. On the tested device `dumpsys display` reported `mScreenBrightnessRangeMinimum=2`, `mScreenBrightnessRangeMaximum=2047`, current `1168`. Always read the real range first: `dumpsys display | grep mScreenBrightnessRange`.
- `settings put system screen_brightness_mode 0|1` — **[WS]**; 0 = manual, 1 = adaptive. Set 0 first if adaptive brightness keeps overriding your value.
- `settings put system screen_brightness_float <0–1>` — Android 10+ `@hide` key, the canonical internal brightness value (0.0–1.0); OEM-dependent and absent on the tested device (`settings get system screen_brightness_float` → `null`). `screen_brightness` (0..255) is the legacy mirror. Prefer `cmd display set-brightness`, or write both if using settings. OEMs add per-display keys and HDR/HBM clamps.
- A dissenting agent claimed no AOSP `cmd display set-brightness` exists — rejected: live `cmd display help` on Android 14 lists `set-brightness BRIGHTNESS (0..1)`, and AOSP `DisplayManagerShellCommand.setBrightness()` calls `dm.setBrightness()` with `DisplayManagerService` enforcing `CONTROL_DISPLAY_BRIGHTNESS`. `cmd display set-brightness` remains the preferred method.
- Read: `settings get system screen_brightness`, `settings get system screen_brightness_mode`, `dumpsys display | grep Display.*Brightness`.
- **Verdict:** `cmd display set-brightness` = stable (Android 10+); `settings put ... screen_brightness` = version/OEM-fragile.

## 2. Volume (media / alarm / ring / notification)
- `cmd media_session volume --stream <N> --set <index> [--show]`
- `cmd media_session volume --stream <N> --adj raise|same|lower`
- `cmd media_session volume --stream <N> --get`
- Streams (`AudioManager.STREAM_*`): 0 VOICE_CALL, 1 SYSTEM, 2 RING, 3 MUSIC, 4 ALARM, 5 NOTIFICATION. All are **[S]** (AudioService shell).
- **No `--mute` option exists on Android 14** — set index 0, or use `cmd media_session dispatch mute` (KEYCODE_MUTE=91) / `input keyevent 164` (VOLUME_MUTE toggles). `settings put system volume_music*` is not observed live by AudioService. `cmd audio` volume is unreliable on Android 14; `cmd media_session volume` is the CTS-used path.
- `input keyevent 24` / `25` — relative volume via key events; affects the stream currently targeted by UI, so less deterministic.
- Ranges are device-specific (observed: stream 3 in `[0..16]`, stream 4 in `[1..16]`, stream 0 in `[1..9]`). Read with `--get` before writing.
- DND suppresses ring/notification audibility independently of stored index.
- **Verdict:** stable Android 9+; prefer `cmd media_session volume` over keyevents or `settings`.

## 3. Do Not Disturb / notification policy
- `cmd notification set_dnd <value>` — **[S]** (shell holds `MANAGE_NOTIFICATIONS`/`ACCESS_NOTIFICATION_POLICY`). Exact values from on-device help and AOSP `NotificationShellCmd.java`:
  - `on` or `none` → `INTERRUPTION_FILTER_NONE` = **total silence** (`settings get global zen_mode` → 2)
  - `priority` → `INTERRUPTION_FILTER_PRIORITY` → zen_mode 1 (priority only)
  - `alarms` → `INTERRUPTION_FILTER_ALARMS` → zen_mode 3
  - `off` or `all` → `INTERRUPTION_FILTER_ALL` → zen_mode 0 (DND off)
  - **Trap:** many guides claim `set_dnd on` = priority. AOSP android14 maps `on|none` to **total silence**; use `priority` for priority-only and always confirm with `settings get global zen_mode`.
- `cmd notification allow_dnd <pkg>` / `disallow_dnd <pkg>` — grants/revokes Notification Policy Access (bypasses Settings UI). On Android 15+ the shell itself may need `cmd notification allow_dnd com.android.shell` first.
- `settings put global zen_mode 0|1|2|3` — **[WSS]**, **unsupported**: ZenModeHelper treats this key as an *output* written after `evaluateZenMode()` from the manual rule. Direct writes can be reverted at the next policy evaluation. Never use it to change DND; use `cmd notification set_dnd`.
- `cmd notification set-dnd <value>` (hyphenated) also works — AOSP normalizes with `cmd.replace('-', '_')`.
- DND behavior: `none`/`alarms` force `RINGER_MODE_SILENT` and restore the prior ringer level on exit; `priority` mutes at AudioService instead. DND mutes streams at the audio-policy layer, so changing a volume index while DND is on will not restore audibility — clear DND first.
- Read: `settings get global zen_mode`; `dumpsys notification | grep mZenMode` (shows manual/automatic rules).
- **Version timeline (AOSP-tag verified):** `post`, `allow_listener`, `allow_dnd`, `disallow_dnd` exist since Android 10 / API 29; `list`, `get`, `snooze`, `unsnooze`, `set_dnd` only since Android 11 / API 30 (absent in the Android 10 tag). Do not claim `set_dnd`/`list`/`get` on API 29.
- **Verdict:** `cmd notification set_dnd` stable Android 11+ (API 30+), with counterintuitive value semantics that have changed across versions — verify zen_mode after every call.

## 4. Screen timeout
- `settings put system screen_off_timeout <milliseconds>` — **[WS]** (e.g. `600000` = 10 min).
- `settings get system screen_off_timeout` — **[RO]**.
- `settings put system screen_off_timeout -1` does **not** mean "keep screen on": AOSP `PowerManagerService.getScreenOffTimeoutLocked()` ends with `return Math.max(timeout, mMinimumScreenOffTimeoutConfig)`, so -1 collapses to the system **minimum** timeout — the opposite of intended (verified in android14-release PowerManagerService.java:3153-3168). For "never", use a large finite value such as `2147483647`. Device-admin max, `adaptive_sleep` (Android 10+; `settings put secure adaptive_sleep 0` to disable), SLEEP_TIMEOUT and ATTENTIVE_TIMEOUT clamps also apply. Verify the effective value with `dumpsys power` (`Screen off timeout`, `Attentive timeout`, `Sleep timeout`).
- Adaptive sleep can shorten the effective timeout independent of this value.
- Discovery/hygiene idea (`NetherlandsForensicInstitute/puma`): save the original value and restore it after automation.
- **Verdict:** normal numeric values stable; `-1` is actively wrong; use a large finite value and verify via `dumpsys power`.

## 5. Wi‑Fi
- `cmd wifi set-wifi-enabled enabled|disabled` — **[S]**. AOSP `WifiShellCommand` passes `SHELL_PACKAGE_NAME = "com.android.shell"` to `WifiService`.
- `cmd wifi status`, `cmd wifi list-scan-results`, `cmd wifi start-scan`, `cmd wifi list-networks`, `cmd wifi get-country-code` — **[RO]**/**[S]**.
- `cmd wifi connect-network "<ssid>" ...` and `cmd wifi add-network ...` are **ROOT-ONLY (UID 0) on Android 13 (API 33) and 14 (API 34)**. AOSP `WifiShellCommand` checks `NON_PRIVILEGED_COMMANDS`: privileged commands from UID 2000 throw `SecurityException: Uid 2000 does not have access to <cmd> wifi command`. Allowed from shell on Android 11/12, root-only on 13/14, allowed again on 15+ (API 35). This is AOSP-source-derived, not device-tested here; the live OPLUS device was used only for read-only `cmd wifi status`. **Do not claim shell Wi‑Fi connect works on Android 14.**
- Shell can still use `cmd wifi set-wifi-enabled`, `status`, `list-scan-results`, `start-scan`, `list-networks`, `get-country-code`, and `forget-network <id>`. `cmd wifi get-scan-results` does NOT exist on 13/14 (correct token is `list-scan-results`); there is no `enable-network` or `remove-network` — use `forget-network <id>`. Hidden-network flag is `-h`, not `-s`.
- `cmd wifi set-wifi-enabled` exists only on API 30+; on Android 10 use `svc wifi enable|disable`. On 11+, `svc wifi` is a translation shim to `cmd wifi` (not a no-op). Wi‑Fi cannot be toggled while airplane mode is on except by privileged callers — shell qualifies via `NETWORK_SETTINGS`.
- `cmd wifi start-softap ...` **fails under uid 2000** with `SecurityException: Uid 2000 does not have access to start-softap` — mark as `needs_root`, do not offer as working.
- **Verdict:** `cmd wifi set-wifi-enabled` + reads stable on API 30+; `svc wifi` for API 29 and below; `connect-network`/`add-network` unavailable from Shizuku ADB shell on API 33/34.

## 6. Bluetooth
- `cmd bluetooth_manager enable` / `disable` — **[S]**; **introduced only in Android 13 (API 33)**. Android 14 adds `wait-for-state:STATE_ON|STATE_OFF`. On Android 10–12 use `svc bluetooth enable|disable`.
- `svc bluetooth enable|disable` — shim translating to `cmd bluetooth_manager enable|disable` on 11+ (confirmed in `/system/bin/svc`).
- `settings put global bluetooth_on 1|0` — **NOT a toggle**: `BLUETOOTH_ON` is a `@Readable` state mirror written by the stack.
- Read: `dumpsys bluetooth_manager` **[RO]**.
- Apps need `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` runtime perms on Android 12+; shell holds `BLUETOOTH_PRIVILEGED` and is exempt.
- **Verdict:** `cmd bluetooth_manager` stable Android 13+; `svc bluetooth` for Android 10–12; never `settings put global bluetooth_on`.

## 7. Airplane mode
- `cmd connectivity airplane-mode enable|disable` — **[S]**; bare `cmd connectivity airplane-mode` reads state (`enabled`/`disabled`).
- Legacy `settings put global airplane_mode_on 1` **[WSS]** + `am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true` — **expected to fail** from shell: that is a protected broadcast and UID 2000 is not in AMS's allowed UID list (`SecurityException: Permission Denial: not allowed to send broadcast`); `FLAG_RECEIVER_FROM_SHELL` does not help. The action string really is `android.intent.action.AIRPLANE_MODE` (no `_CHANGED` suffix).
- Read: `settings get global airplane_mode_on`; `cmd connectivity airplane-mode`.
- **Verdict:** `cmd connectivity airplane-mode enable|disable` (available Android 10+, sends the broadcast as system) is the only reliable path; settings+broadcast is broken from shell.

## 8. Location
- `cmd location set-location-enabled true|false [--user N]` — **[S]**; read with `cmd location is-location-enabled [--user N]` **[RO]**. This syntax is API 32–34. Variants: Android 10 has **no** `cmd location` (use `settings put secure location_mode 0|3`); API 31 used `set-location-enabled [--user N] true|false` (different arg order) and had no `is-location-enabled`. `location_providers_allowed` was removed entirely in Android S (12+); `location_mode` only distinguishes 0=off vs nonzero=on.
- `cmd location providers ...` — test providers; needs `android:mock_location` app-op (`cmd appops set <uid> android:mock_location allow`).
- **Verdict:** `cmd location` stable on API 32+ with the syntax above; `svc`/`settings` fallbacks needed for API 30/31; `location_providers_allowed` is gone on 12+.

## 9. Apps: launch / stop / standby / power
- `am force-stop [--user N|all|current] <pkg>` — **[S]** `FORCE_STOP_PACKAGES`; kills processes, cancels alarms/jobs, leaves app stopped. Stable.
- `am stop-app [--user N|all|current] <pkg>` — Android 12+; stops the app **without** cancelling alarms/jobs.
- `am start -n <pkg>/<activity>` (or `-a`/`-d`/`-t` forms) — **[S]**. Shell passes `SHELL_PACKAGE_NAME` and always adds `FLAG_ACTIVITY_NEW_TASK`; background-activity-start restrictions are bypassed legitimately via shell's `START_ACTIVITIES_FROM_BACKGROUND`. **Hard fail for non-exported activities**: shell lacks `START_ANY_ACTIVITY`, and AOSP 14 has no `SAFE_TO_LAUNCH` flag despite blog claims. Discover the real component with `cmd package resolve-activity --brief <pkg>`. `monkey -p <pkg> -c android.intent.category.LAUNCHER 1` is the legacy fallback Andee now uses.
- `am set-standby-bucket [--user N] <pkg> active|working_set|frequent|rare|restricted` + `am get-standby-bucket` — **[S]** `PACKAGE_USAGE_STATS`. Help lists 5 names, but AOSP `bucketNameToBucketValue()` **also accepts `never` and raw ints**: exempted=5, active=10, working_set=20, frequent=30, rare=40, restricted=45, never=50. RESTRICTED is API 30 but effective ~Android 12; NEVER is a hidden SystemApi. Default user is USER_CURRENT — pass `--user 0` on multi-user. There is **no** `am reset-standby-bucket` in AOSP 14. OEM Doze/Adaptive Battery may override effects.
- `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore` (undo with `allow`) — **[S]** `MANAGE_APP_OPS_MODES`; preferred over `RUN_IN_BACKGROUND` (Google's power-testing doc uses RUN_ANY_IN_BACKGROUND — it is tied to the Background Restrictions toggle). No WSS needed.
- `cmd power set-mode 1|0` — **[S]** `DEVICE_POWER`; Android 14 CTS uses this instead of `settings put global low_power 1` (which is a shadow key on real hardware). Also `cmd power set-adaptive-power-saver-enabled true|false` and `set-fixed-performance-mode-enabled true|false`.
- `pm clear <pkg>` / `cmd package clear <pkg>` — destructive (kills app + deletes data). `cmd package install-existing <pkg>`, `cmd package list packages` — **[S]**.
- **Verdict:** force-stop/start stable when the component is exported; always verify the screen actually changed after a launch (exit code 0 is not proof — Andee commit `2ab992a`).

## 10. Battery / network / device info / display / input
- `dumpsys battery` **[RO]** (level, scale, status, health, temperature, plugged). Mutators **[S]** `DEVICE_POWER`:
  `dumpsys battery set [-f] [ac|usb|wireless|dock|status|level|temp|present|counter|invalid] <value>`, plus `dumpsys battery unplug [-f]` and `dumpsys battery reset [-f]`. Mutators freeze state until `reset`. `suspend_input` only on debuggable builds. OEM output varies (tested OnePlus prefixes an OPLUS-specific section).
- `dumpsys connectivity`, `dumpsys wifi`, `dumpsys telephony.registry`, `dumpsys batterystats`, `dumpsys usagestats` — **[RO]**. Per-service UID gating exists, but no single authoritative "Android 10 redaction switch" was found in AOSP — soften blanket claims. The specific `dumpsys notification` vs `--noredact` difference (§11) is verified live. These commands and `ip addr` work from shell but are blocked/denied for normal apps (missing `DUMP` / netlink permission) — reinforces the shell-vs-app distinction.
- `getprop`, `ip -4 addr` **[RO]**.
- `wm size [reset|WxH] [-d ID]`, `wm density [reset|D]`, `wm user-rotation [free|lock] [rot]` — read forms **[RO]**; setters enforce `WRITE_SECURE_SETTINGS` in WindowManagerService (shell has it). `wm reset [-d ID]` clears all overrides.
- `input` is a shell script wrapping `cmd input` — **[S]** `INJECT_EVENTS`. `input keyevent <code|name>` (POWER=26, SLEEP=223, WAKEUP=224), `--longpress`, `input text <string>` (**ASCII-only** via VIRTUAL_KEYBOARD, bypasses IME — non-ASCII drops; use the accessibility IME for those), `input tap x y`, `input swipe x1 y1 x2 y2 [ms]`, `input keycombination`, `-d DISPLAY_ID` to target a display.
- **Verdict:** reads stable; simulated battery state stable until reset; `input` high reliability for keyevent/tap/swipe, medium for text; `dumpsys` formatting is OEM-specific.

## 11. Notifications: post / read
- Post: `cmd notification post [-t "<title>"] [-i <iconspec>] [-S <style> ...] [-c <intentspec>] <tag> <text>` — **[S]**. AOSP `NotificationShellCmd` creates channel `shell_cmd` (fixed notification ID `2020`) and posts via `NotificationManager`; shell holds `POST_NOTIFICATIONS` + `MANAGE_NOTIFICATIONS`. Example: `cmd notification post -t "Title" mytag "Body text"`. Styles: `bigtext`, `bigpicture`, `inbox`, `messaging`, `media`. Icon specs: `file:///data/local/tmp/<img.png>`, `content://...`, `@[pkg:]drawable/...`, `data:base64,...`.
- `cmd notification notify` is an undocumented alias of `post` in AOSP 14 — prefer `post`. Posts are as `com.android.shell`, ID 2020, channel `shell_cmd`; the same tag replaces the previous notification. There is no `-p/--package` flag. Root (UID 0) posts are community-reported not to display — use Shizuku's ADB backend (already UID 2000).
- List: `cmd notification list` — **[RO]**, prints `<user>|<pkg>|<id>|<tag>|<uid>` keys (verified working with DND active). Available API 30+.
- Read one: `cmd notification get "<key>"` — **[RO]**, dumps full `NotificationRecord` including content, channel, flags, actions; it calls `NotificationRecord.dump(..., false)` so redaction is disabled. API 30+.
- `cmd notification snooze --for <msec> <key>` / `unsnooze <key>` — **[S]**.
- Listener/DND helpers: `cmd notification allow_listener <component> [user_id]` / `disallow_listener`, `allow_dnd <pkg>` / `disallow_dnd` — **[S]**.
- Shell has **no** supported command to dismiss/cancel arbitrary notifications, read notification history, or get real-time post/remove callbacks. `list`/`get` cover active notifications only; `service call notification` is brittle/unsupported.
- Full dump: `dumpsys notification --noredact` **[RO+S]** — verified live: plain dump showed `tickerText=Wireless...`, `--noredact` showed `Wireless debugging connected`. Available since Android 6 / API 23 (https://developer.android.com/about/versions/marshmallow/android-6.0-changes.html).
- There is no general "read the shade as structured JSON" command; full listener semantics (incl. real-time callbacks) still require `NotificationListenerService` inside an app.
- **Verdict:** `post` stable Android 10+ (API 29), `list`/`get`/`snooze` Android 11+ (API 30+); `--noredact` Android 6+ (API 23+).

## 12. Granting capabilities to a third-party app from shell
- `pm grant <pkg> <perm>` requires the target app to **declare** the permission in its manifest; grants are lost on uninstall/`pm clear`. Only runtime (`dangerous`), `development`, and `role` permissions are grantable — plain `signature` permissions are rejected as "not a changeable permission type." `development` on `WRITE_SECURE_SETTINGS` is why `pm grant ... WRITE_SECURE_SETTINGS` works.
- Exact WRITE_SETTINGS app-op syntax: `cmd appops set [--user N] <pkg> WRITE_SETTINGS allow|ignore|deny|default`; check with `cmd appops get <pkg> WRITE_SETTINGS`.
- `BIND_NOTIFICATION_LISTENER_SERVICE` is plain `signature` — shell cannot `pm grant` it; `cmd notification allow_listener <pkg/.Class> [userId]` is the correct route. DND policy access: `cmd notification allow_dnd <pkg>`.
- Android 13+ restricted settings: `cmd appops set <pkg> ACCESS_RESTRICTED_SETTINGS allow`; AOSP `pm install` whitelists restricted permissions by default, `--restrict-permissions` opts out. OEM-heavy — verify on device. Restricted settings and `POST_NOTIFICATIONS` as a dangerous runtime permission both arrived on API 33.
- Cannot grant signature-only perms (`MANAGE_NOTIFICATIONS`, `DUMP`) to arbitrary apps. Shell cannot read other apps' `/data/user/0/<pkg>` (SELinux + UID), and hidden APIs are restricted for normal apps since Android 9 (Shizuku UserService has no non-SDK restriction).
- **Two separate gates:** Shizuku client permission (granted in the Shizuku UI) vs Android/shell permission — a shell grant does not bypass the client gate. Check with `Shizuku.checkSelfPermission()` and `ShizukuService#checkPermission`.
- Dark-mode note from `devindudeman/nix-android`: quick-settings tile writes are reverted by SystemUI — use `cmd uimode night yes|no` and read back.
- No authoritative AOSP change was found that broadly removed shell's core ADB permissions in Android 10–14; OEM ROM variance (MIUI/ColorOS) is real.

## Official URLs
- ADB overview: https://developer.android.com/tools/adb
- `Settings.System`: https://developer.android.com/reference/android/provider/Settings.System
- `Settings.Global`: https://developer.android.com/reference/android/provider/Settings.Global
- `NotificationManager`: https://developer.android.com/reference/android/app/NotificationManager
- `ActivityManager` standby buckets: https://developer.android.com/reference/android/app/ActivityManager
- `UsageStatsManager`: https://developer.android.com/reference/android/app/usage/UsageStatsManager
- Power testing (Google): https://developer.android.com/topic/performance/power/test-power
- Secure background activity starts: https://developer.android.com/guide/components/activities/secure-bal
- Android 14 behavior changes: https://developer.android.com/about/versions/14/behavior-changes-all
- Wi‑Fi scanning/location: https://developer.android.com/guide/topics/connectivity/wifi-scan
- Bluetooth: https://developer.android.com/guide/topics/connectivity/bluetooth
- `dumpsys`: https://developer.android.com/tools/dumpsys
- Connectivity AOSP sources: https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/android14-release/service/java/com/android/server/wifi/WifiShellCommand.java ; https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android14-release/service/src/com/android/server/ConnectivityService.java ; https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android14-release/service/src/com/android/server/bluetooth/BluetoothShellCommand.java ; https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/location/LocationShellCommand.java ; https://developer.android.com/reference/android/net/wifi/WifiManager#setWifiEnabled(boolean)
- AOSP power/display/audio/Zen sources: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/power/PowerManagerService.java ; .../display/DisplayPowerController.java ; .../media/VolumeCtrl.java ; .../notification/ZenModeHelper.java
- Wake lock / background awake: https://developer.android.com/develop/background-work/background-tasks/awake
- AOSP sources (prefix `https://android.googlesource.com/platform/`): `frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/notification/NotificationShellCmd.java`; `.../am/ActivityManagerShellCommand.java`; `.../power/PowerManagerShellCommand.java`; `.../input/InputShellCommand.java`; `.../wm/WindowManagerShellCommand.java`; `.../BatteryService.java`; `frameworks/base/+/.../cmds/svc/svc`; `frameworks/base/+/.../packages/Shell/AndroidManifest.xml`; `packages/modules/Wifi/+/.../service/java/com/android/server/wifi/WifiShellCommand.java`
- Shizuku setup: https://shizuku.rikka.app/guide/setup/ ; API: https://github.com/RikkaApps/Shizuku-API
- NotificationListenerService: https://developer.android.com/reference/android/service/notification/NotificationListenerService
- DND (AOSP): https://source.android.com/docs/core/display/dnd
- Android 6.0 changes: https://developer.android.com/about/versions/marshmallow/android-6.0-changes.html
- Shizuku: https://github.com/RikkaApps/Shizuku and https://github.com/RikkaApps/Shizuku-API

## Reliability summary (most fragile first)
1. `settings put system screen_brightness` — OEM range is not 0–255; adaptive mode overrides; use `cmd display set-brightness`.
2. `settings put global zen_mode` / `set_dnd on` semantics — bypasses policy / counterintuitive mapping; always read `zen_mode` back.
3. `settings put system screen_off_timeout -1` — collapses to the system minimum timeout (`Math.max(timeout, mMinimumScreenOffTimeoutConfig)`); use a large finite value and verify with `dumpsys power`.
4. `cmd wifi|bluetooth` version floors — `set-wifi-enabled` API 30+, `cmd bluetooth_manager` API 33+ (`svc` shims for older), `connect-network`/`add-network` root-only on API 33/34; `svc` is a shim on 11+, not a no-op.
5. `settings`-based airplane mode + broadcast, `location_mode`, `location_providers_allowed`, `bluetooth_on` — legacy/broken; `AIRPLANE_MODE` broadcast from shell throws `SecurityException`; use `cmd connectivity`, `cmd location`, `cmd bluetooth_manager`.
6. `am set-standby-bucket` effects overridden by Doze/OEM; `am start` on non-exported activities hard-fails.
7. `input text` non-ASCII and `dumpsys` output formatting — device/OEM-sensitive.
