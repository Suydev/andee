# Android tools research (2026-11-24)

Research only — no production code was changed for this report. Sources: GitHub code search via `gh`/GitHub MCP, plus the local Andee checkout. The official Android/ADB/Shizuku documentation pass is complete and captured in `docs/android14-shell-reference.md` (live Android 14 device + AOSP android14-release + developer.android.com); it should be read alongside this file.

## Baseline: what Andee already has

From `app/src/main/java/net/kuafuai/andee/net/CommandDispatcher.kt`, `core/src/main/kotlin/net/kuafuai/andee/net/ToolSchemas.kt`, and `core/src/main/kotlin/net/kuafuai/andee/net/ToolSchemas.kt:2880`:

- Existing device tools: `set_volume` (`device.volume`, AudioManager), `set_brightness` (`device.brightness`, Settings.System, 1..255), `system_action` (back/home/recents/notifications/quick_settings), `network_info`, `device_facts`, `read_sensors`, `get_notifications` (read-only), `list_installed_apps`, `launch_app`, `open_url`.
- `device.shell` is internal-only and deliberately has no schema — the model cannot call an arbitrary shell command. That boundary should stay.
- Shizuku-backed fast path: `ShizukuDevice.kt` / `ShellService.kt` run as uid 2000 (`shell`), currently used for `input`/`monkey` only; no `settings put` from the shell layer yet.
- Gaps vs the requested "full ADB-like phone control": DND, screen-off timeout, Wi‑Fi/Bluetooth/mobile data/airplane mode toggles, location, hotspot, rotation, dark mode, battery saver, and a dedicated Google-search path.
- License: Apache-2.0 with additional conditions. MIT/Apache code can be ported; GPL/AGPL repos are pattern-only (design ideas, fresh code).

## Best reusable sources (ranked)

1. **`Soodok/Deepseek-Harness-Local-Android` (MIT)** — closest architectural twin: Kotlin, on-device, Shizuku-first uid-2000 shell with `su` fallback.
   - `app/src/main/java/app/dsh/mobile/engine/Privilege.kt:runPrivileged()` — Shizuku exec, then `su` fallback; probe root with `id` and check `uid=0` because toybox `su` rejects `-c`.
   - `app/src/main/java/app/dsh/mobile/engine/cap/ControlCap.kt` — `control` tool with `op = wifi|bt|volume|brightness|torch|vibrate|system`; `system` targets `airplane|battery_saver|dnd|rotation|eye_comfort|hotspot`.
   - Its best idea: **every write does a read-back with a retry budget (~1.2 s, 6 s for SoftAP) and reports `requested` / `enabled` / `verified` / `mismatch`. It refuses to claim success when state did not change.** This is exactly what Andee's fast-launch bug lacked (exit code 0 but no UI effect).
   - `NotifyCap.kt` — `NotificationListenerService` JSON shape and direct-reply via `Notification.Action` + `RemoteInput`.
   - Verdict: port the verification + permission-gating patterns.

2. **`HITsz-TMG/KnowAct` (MIT)** — `GUIClaw/nanobot/skills/claw-gui/SKILL.md` + `references/adb-commands.md`: a "prefer shell before GUI" decision policy and a clean command table for the missing capabilities:
   ```
   svc wifi enable|disable
   svc bluetooth enable|disable
   cmd connectivity airplane-mode enable|disable     # read: cmd connectivity airplane-mode
   svc data enable|disable
   cmd uimode night yes|no
   cmd power set-mode 1|0                           # battery saver
   cmd notification set_dnd on|off                  # read: settings get global zen_mode
   settings put system screen_brightness <0-255>
   settings put system screen_brightness_mode 0|1
   settings put system screen_off_timeout <ms>
   cmd location set-location-enabled true|false
   cmd statusbar expand-notifications|expand-settings|collapse   # API 30+
   am start -a android.search.action.GLOBAL_SEARCH
   input keyevent 284                               # app drawer
   ```
   Caveats it records: `cmd statusbar` needs API 30+; OEM brightness ranges vary (OPPO/OnePlus reportedly 0–4096); turn auto-brightness off before a manual write; multi-user needs `am get-current-user`.

3. **`devindudeman/nix-android` (MIT)** — `docs/CAPABILITIES.md`, `PRIMITIVES.md`, `USING.md`: a ledger of which shell writes actually work from uid 2000. Crucial: **`settings put global zen_mode N` is silently ignored** (use `cmd notification set_dnd`); on Android 15+ `cmd notification allow_dnd com.android.shell` may be needed first; quick-settings tile writes get reverted by SystemUI. Read before adding any `settings put`.

4. **`unitedbyai/droidclaw` (MIT per README, no LICENSE file)** — `docs/adb-commands.md`: full tool/command catalog including clipboard (`cmd clipboard get-text|set-text`), power keys (`input keyevent 26/223/224`), all `am start -a android.settings.*` panels, `monkey -p <pkg> -c android.intent.category.LAUNCHER 1`, `pm grant/revoke`, `wm size`. Do **not** copy its volume-by-`settings` key approach: `settings put system volume_music` is not observed live by AudioService — Andee's existing AudioManager path is correct.

5. **`aaronmphilip/Kaivor` (MIT)** — `SettingsSkill.kt` opens the exact settings page via intent (Wi‑Fi, Bluetooth, display, sound, notification policy) then uses a strict single-screen prompt ("you are ALREADY on the correct page, never press Back"). This is the fix for ROM navigation back-loops when a shell toggle is unsupported. `GeneralSkill.kt` resolves app name → package via `PackageManager.queryIntentActivities(ACTION_MAIN/CATEGORY_LAUNCHER)` + label match. `SKILL_SPEC.md` has a useful skill manifest shape (`permissions`, `allowedPackages`, `trusted`, `NeedsConfirmation`), if Andee ever wants third-party skills.

6. **`pockebot/openpocket` (MIT)** — `src/agent/prompts.ts` has the cleanest one-shot Google search:
   ```
   am start -a android.intent.action.WEB_SEARCH --es query "<query>"
   ```
   Keep Andy's existing GUI workflow as the fallback: open `com.google.android.googlequicksearchbox`, tap the focused field, `fast_text` the query, tap the suggestion, read results with accessibility, `tap_target` for WebView links.

7. **`google/artemis` (Apache-2.0)** — `artemis/mcp/action_specs.py`, `action_names.py`: canonical action vocabulary and one translation layer from operator verbs to calls; tool descriptions mention sliders for volume/brightness and dragging slightly past the target to beat touch slop. Steal naming/descriptions, not the host ADB driver.

8. **`droidrun/mobilerun` (MIT)** — `mobilerun/agent/tool_registry.py`: registry with `register`, `disable_unsupported(capabilities)`, JSON-schema signatures, normalized `ActionResult`; `mobilerun/config/app_cards/android/app_cards.json` is the app-card format already used for Andy's WhatsApp notebook idea.

9. **`google-research/android_world` (Apache-2.0)** — `android_world/env/adb_utils.py`: canonical commands for Wi‑Fi/BT (`svc wifi|bluetooth enable|disable`), brightness (`settings put system screen_brightness`), airplane mode. No read-back verification — reference only.

### GPL-3.0 — design pattern only, do not copy code

- **`emi-ran/flowpilot`**: `actions/{BrightnessExecutor,MediaVolumeExecutor,DndExecutor,WifiExecutor,BluetoothExecutor,LaunchExecutor,NotificationExecutor,ShizukuShell.kt}` — per-capability executors with polling, permission diagnostics, and honest `STATE_MISMATCH`. Closest full reference implementation to the target.
- **`Mangi-11/Eta`**: `DisplaySoundControls.kt` (`cmd display set-brightness <pct> --unit percentage`, read with `cmd display get-brightness --unit percentage`, verify ±1%; DND mode map `off→all`, `priority→priority`, `silent→none`, `alarms→alarms` then `cmd notification set_dnd <mode>`) and `SystemStateControls.kt` (`cmd wifi set-wifi-enabled`, `cmd bluetooth_manager enable|disable`, `cmd nfc enable-nfc|disable-nfc persist`). Excellent result contract (`before`/`after`/`verified`/`errorCode`).
- **`thtbee/nectarlink`**: `SystemServiceBridge.kt` — an **allowlist parser** mapping exact shell strings to typed binder calls with capability checks. Copy the idea: it prevents arbitrary-command injection into a privileged process.
- IDE-only: `MrXMART-Abhyuday/LYRA` (WhatsApp UI anchors, already captured in `docs/andy-whatsapp-notebook.md`), `bytedance/UI-TARS`, `X-PLUG/MobileAgent`, `llm-net/adb-claw`, `hridaya423/phonedriver`.

## Traps to carry into any implementation

| Trap | Correct approach |
|---|---|
| `am start ... MAIN/LAUNCHER -p com.whatsapp` exits 0 but does not launch | Use `monkey -p <pkg> -c android.intent.category.LAUNCHER 1`, then verify the foreground package; already fixed in Andee commit `2ab992a` |
| `settings put global zen_mode N` silently ignored | `cmd notification set_dnd on|off`, read back `settings get global zen_mode` |
| `settings put system volume_music*` not observed live by AudioService | Keep AudioManager (`device.volume`) |
| `cmd wifi connect-network` / `add-network` are root-only on API 33/34 (UID 2000 denied); `set-wifi-enabled` starts on API 30; `cmd bluetooth_manager` starts on API 33 | Use `svc` shims on older versions; shell can still toggle Wi‑Fi via `cmd wifi set-wifi-enabled` and forget networks; do not offer shell Wi‑Fi connect on Android 14 |
| `cmd wifi start-softap` denied to uid 2000 | Mark `needs_root`, do not offer as a working tool |
| `cmd statusbar` requires API 30+ | Guard on SDK, fall back to `system_action notifications/quick_settings` |
| OEM brightness range may be 0–4096 | Discover max at runtime, disable auto-brightness first, prefer `cmd display set-brightness` (0.0–1.0) and read back |
| `screen_off_timeout -1` collapses to the system minimum timeout (AOSP `Math.max(timeout, mMinimumScreenOffTimeoutConfig)`) | Use a large finite value (e.g. 2147483647), disable `adaptive_sleep` if needed, verify with `dumpsys power` |
| Android 15+ DND may need shell allowlist | `cmd notification allow_dnd com.android.shell`, with honest failure |
| Quick-settings tile writes reverted by SystemUI | Read back after a delay; report mismatch, not success |

## Proposed tool surface (design only — not implemented)

Curated, typed tools — **no raw `device.shell` exposed to the model**:

| Tool | Write/read | Notes |
|---|---|---|
| `set_dnd` | read/write | modes off/priority/alarms/none; read-back `zen_mode`; `cmd notification set_dnd` requires API 30+ (`post` exists on API 29+) |
| `set_screen_timeout` | read/write | ms; read-back `screen_off_timeout` |
| `set_device_state` | read/write | wifi, bluetooth, mobile_data, airplane, location, battery_saver, rotation, dark_mode; typed enum, read-back, mismatch reporting |
| `set_brightness` | existing | add auto-mode flag + runtime max discovery/read-back |
| `set_volume` | existing | keep AudioManager; add per-stream enum if needed |
| `google_search` | write | `am start -a android.intent.action.WEB_SEARCH --es query`, GUI fallback via notebook |
| `get_battery`, `network_info`, `device_facts`, `get_notifications` | existing | unchanged |

Each write returns `{requested, before, after, verified, error}` after a bounded poll. Add `TurnGuards`-style checks so a notification or a note cannot change device settings; only a user turn may do so. Add a capability probe at startup so unsupported tools are not advertised on this ROM.

## Proactive autonomy: already partly solved

Andee already contains the three-part cycle described by `thunlp/ProactiveAgent` and `leomariga/ProactiveAgent`:

1. **Decide whether to respond** — `core/.../brain/Heartbeat.kt` (gates, work budgets, quiet self-wake turns) and `core/.../brain/PlanMind.kt` (context-aware nudges).
2. **Generate the action** — existing self-work / Drive path, using the same tools as a user turn but under `TurnKind`/`silent` guards.
3. **Decide when to wake next** — heartbeat intervals and alarm-driven wakeups; the user switch at `app/.../config/VoiceConfig.kt:993` already lets Andy turn this on/off.

So "wakes up by itself like a human" is a matter of **richer triggers and more useful actions**, not a new always-listening loop. Natural extensions, in order of safety: foreground-app change, screen-on after a long gap, notification arrival (already guarded), sensor/environment change (`read_sensors` exists), time-based routines, then device-state-driven suggestions ("you left the hotspot on"). Every one must remain behind the same user switch and quiet-hour/work-budget gates.

## Andy's notebooks

- `docs/andy-whatsapp-notebook.md` — fast-launch fix, UI anchors, send-message and permission-dialog flow.
- Google-search workflow (already saved to Andy's memory, 2026-11-24): open `com.google.android.googlequicksearchbox`, tap the focused search field, `fast_text` the query, tap the search suggestion, read results with accessibility, use `tap_target` for WebView links. No invented `websearch` tool.
