# Andy's WhatsApp fast notebook

Sources studied (2026-11-24):
- `MrXMART-Abhyuday/LYRA` — `android-agent/gitd/skills/whatsapp/` (skill.yaml, elements.yaml, workflows/send_message.py, actions/core.py)
- `aaronmphilip/Kaivor` — `WhatsAppSkill.kt` (chat-first launch, attachment flow, confirmation)
- `droidrun/mobilerun` — `mobilerun/config/app_cards/README.md` (the app-card pattern: inject app-specific guidance when that app is foreground)
- `google-research/android_world` — `adb_utils.py` maps WhatsApp to the explicit component `com.whatsapp/com.whatsapp.Main`

## Golden rule

Read the screen first, then act through the fast path. Never tap an avatar or contact card when you mean the chat row.

1. `get_screen_element` (fresh e-numbers and bounds).
2. `fast_tap {"eid": "e7"}` / `fast_swipe` / `fast_text` / `fast_key` for the action. `fast_tap` with an e-number from the fresh map resolves the live node on the device — no coordinate math; screenshots are last-resort and need a `reason`. They use Shizuku input and fall back to accessibility only when needed.
3. Observe the result before the next step. `verified` is evidence, not a promise.

## Launch

- Package: `com.whatsapp`. Explicit launcher component: `com.whatsapp/com.whatsapp.Main`.
- Andee's `fast_launch` uses `monkey -p com.whatsapp -c android.intent.category.LAUNCHER 1`. A bare `am start -a MAIN -c LAUNCHER -p com.whatsapp` exits 0 but prints `Error: Activity not started, unable to resolve Intent` on this device — do not trust its exit code.
- LYRA force-stops WhatsApp first so every run starts at the conversation list, not an old chat. Andee should also start from the list when a task is "message <contact>".

## Stable UI anchors (from LYRA's elements.yaml)

| Purpose | Locator |
|---|---|
| Search button | `content-desc "Search"` / `com.whatsapp:id/menuitem_search` / `com.whatsapp:id/search_bar_inner_layout` |
| Search field | `com.whatsapp:id/search_input` / `com.whatsapp:id/search_src_text` |
| Contact row | `com.whatsapp:id/contact_row_container` |
| Message box | `com.whatsapp:id/entry` / `content-desc "Type a message"` |
| Send button | `content-desc "Send"` / `com.whatsapp:id/send` |

The message box is at the **bottom** of the chat, below the bubbles. This was the thing Andy had to point out on 2026-11-24.

## Send a message to a contact

1. `fast_launch` `com.whatsapp` (or `fast_key` home → `fast_launch` if already inside an old chat).
2. `get_screen_element`; find the Search control by the anchors above.
3. `fast_text` the contact name into the search field.
4. `get_screen_element`; tap the **contact/chat row** whose text matches, not the photo, status, or header.
5. `get_screen_element`; find `com.whatsapp:id/entry` at the bottom.
6. `fast_text` the message. ASCII only on the fast path; non-ASCII needs the accessibility IME (`type_text`).
7. `get_screen_element`; tap `com.whatsapp:id/send` or content-desc `Send`.
8. Read the chat again and confirm the message bubble appears. If sending is destructive or ambiguous, type it first and ask Andy before step 7 (Kaivor's confirmation pattern).

## Attach a document (Kaivor)

Attachment control is left of the message field (paperclip or `+`): Document → navigate to file → tap it; for several files, long-press the first then tap the others, then Send. Always open the exact chat before touching the picker.

## Permission dialogs to expect (LYRA)

- "Allow" for WhatsApp's first-run permission dialog.
- "While using the app" for location-style prompts.

## Known bad habits to avoid

- Do not tap `contact_photo_view` / avatar — that opens the contact card, not the chat.
- Do not claim a launch worked from exit code 0 alone; look for the screen change.
- Do not repeat an identical `wait_for_screen` call; climb 3 → 5 → 10 → 20 s or change route.
- Do not invent a `websearch` tool. Andy's Google-search workflow is: open `com.google.android.googlequicksearchbox`, tap the focused search field, type the query with `fast_text`, tap the search suggestion, read results with accessibility, and use `tap_target` for WebView links.
