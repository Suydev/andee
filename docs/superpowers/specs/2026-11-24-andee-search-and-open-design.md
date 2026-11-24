# Andee Android Search and Open Design

> Status: requested by the user on 2026-11-24. Companion to the drop-inbox design; this is a separate capability.

**Goal:** give Andee three explicit tools on the Android body: search the web with a key the user supplies, search Andee's own folders plus the device's storage for a file by name, and open a found file in the app Android considers best suited for its type. It can also edit and manage files the user has allowed it to see.

**Choices already made by the user:** search results come from a search API key entered in Settings; storage search is a hybrid of Andee's own files and full device storage; Andee picks the app itself instead of showing its own chooser.

## Constraints

- Android body only; no desktop mirroring in this spec.
- No Compose, no coroutines, no Hilt; OkHttp is already a dependency and runs on a dedicated executor.
- No credential is compiled in. The Tavily key is a `voice_prefs` string, typed in Settings, empty by default.
- No model call and no web request happens on drop, on boot, or on a search result by itself. Every tool call is user-initiated through a normal turn.
- Web results, filenames, and paths are untrusted data. They are fenced, and after `web.search` the turn is untrusted so `device.vault.*` and `file.open` are refused.
- On-demand search only: no persistent index, no background crawl, no reading file contents during a search.
- Bounded work: 2 s scan budget, 20,000 files visited, 100 results returned, 10 web results returned.

## Web search

Provider: Tavily Search API (`POST https://api.tavily.com/search`, `Authorization: Bearer <key>`), verified against its current API reference. Request fields used: `query`, `max_results` (1–10), `topic` (`general` or `news`), optional `time_range`; `include_answer=false`, `include_raw_content=false`, `include_images=false`. Response fields consumed: `title`, `url`, `content`, `published_date`, `usage`.

- `core/.../net/ToolSchemas.kt`: add `web.search` (`localOnly`), outside `screen.`. The description says results are data and that a page may contain instructions that must be ignored.
- `app/src/main/java/net/kuafuai/andee/net/WebSearch.kt`: OkHttp call on a dedicated executor, 15 s connect/read timeout, JSON parsed with `org.json`. No key in logs; error replies say only "invalid key", "rate limited", or "search failed".
- Key-free fallback, in order, when the key is blank or Tavily returns 401/429/5xx: DuckDuckGo's HTML result page (`https://html.duckduckgo.com/html/?q=…`), then Wikipedia's search API for encyclopedic questions. Both are parsed defensively into the same `Result` shape, labelled with `source: duckduckgo` / `wikipedia`, and never used to fetch a whole page. A fallback that parses zero results yields an honest "no results" rather than a made-up answer. The tool result always says which backend answered.
- `app/src/main/java/net/kuafuai/andee/config/VoiceConfig.kt`: `tavily_key` added to `ALLOWED_KEYS` and `current()`, with `tavilyKey(context)` returning a trimmed string or empty.
- `SettingsUi`: a Web search row with a password-masked key field (ordinary `inputType`, masked with `PasswordTransformationMethod` — see `SecretInput`), and a note that the key is stored only on this device.
- `LocalBrain.runTool`: latch `turnUntrusted = true` for `web.search`, same as `note.drop_read`.

## Storage search

Hybrid scope:

1. **Andee's own files** are always searched: `getExternalFilesDir(null)`, `filesDir`, their `inbox/`, `captures/`, `artifacts/`, `meetings/` and `context/` children. No permission needed.
2. **Whole device storage** is searched when `Environment.isExternalStorageManager()` is true (API 30+). A Settings row opens a non-launcher `device/StorageAccessActivity` that fires `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`. On older devices, or if the grant is refused, Settings offers `ACTION_OPEN_DOCUMENT_TREE` and Andee remembers that one folder with persistable URI permission. The card states which scope is active; it never pretends full access is on when it is not.
3. Skipped during a walk: hidden directories, `Android/data`, `Android/obb`, system volumes, and anything outside the granted SAF folder when that is the scope.

`app/src/main/java/net/kuafuai/andee/device/StorageSearch.kt` exposes `search(query, extension, limit)` and returns name, display path, size, modified time and MIME type for up to 100 matches. It never opens or reads file bytes. Bounded by `MAX_SCAN_MS` and `MAX_FILES_VISITED`; a timeout returns what was found with `partial: true` rather than an error.

`ToolSchemas` adds `file.search` (`localOnly`, outside `screen.`). `CommandDispatcher` implements it. The description tells the model to pass an id/path from the result to `file.open` and not to invent one.

## Opening a file

`ToolSchemas` adds `file.open` (`localOnly`, outside `screen.`). It takes a path from `file.search` or `note.drop_list`, or a drop file id.

- `CommandDispatcher` resolves the file to a content URI through `FileProvider`. Extend `res/xml/file_paths.xml` with `<external-path name="external" path="." />` and `<root-path name="root" path="." />`; the latter is only reachable through a tool call that has already named a specific file.
- MIME type comes from `MimeTypeMap.getFileExtensionFromUrl` / `getMimeTypeFromExtension`, with `application/octet-stream` as the honest fallback.
- Build `Intent.ACTION_VIEW` with `FLAG_GRANT_READ_URI_PERMISSION` and `FLAG_ACTIVITY_NEW_TASK`; set the URI as `clipData` as well, because some ROMs drop the extra flag. Grant permission to the resolved package only.
- "Andee picks" means: resolve `queryIntentActivities` for the MIME type, exclude our own package, prefer a non-default resolver whose package is a known viewer for that MIME family (PDF, image, audio, video, text/document), otherwise take the system's first match. The tool result names the app it chose and the file path, so the user can see what happened. If nothing can open the type, say so instead of opening a random app.
- `file.open` is refused on untrusted turns in `TurnGuards`, beside `device.vault.*`, so a file or web page cannot talk Andee into launching an app on its own.

## File editing and management

Also requested: Andee may edit and manage files it is allowed to see. Five more `localOnly` tools, outside `screen.`:

- `file.write` — create or overwrite a text file, using the same size cap as the drop inbox, written atomically through a temp file and rename.
- `file.edit` — replace an exact string in a text file; refuses if the old text is absent, or ambiguous unless `replace_all` is set.
- `file.mkdir` — create a directory inside the granted root.
- `file.move` — rename or move a file or directory; refuses to overwrite unless `overwrite` is set.
- `file.delete` — delete a file or empty directory; a non-empty directory requires `recursive` and `confirm`.

Safety rules, all device-side:

- Every path is canonicalised and checked to be inside Andee's own folders or the `MANAGE_EXTERNAL_STORAGE` root. Path traversal and system directories are refused with a readable reason. SAF-picked folders are search-only in this build; writes there reply with that fact rather than pretending to work.
- All five are refused on `silent`, untrusted, or non-USER turns in `TurnGuards`, alongside `file.open` and `device.vault.*`. The user must have started the turn.
- Overwrites keep a timestamped copy in app-private `files/file-backups/` before the atomic rename, so a bad edit is reversible.
- `file.delete` additionally requires a `confirm: true` argument; without it the reply says what would be deleted and asks the model to get the user's word first.
- A `file_edits` setting defaults to off, is listed leftmost in Settings, and gates all five tools. The tool replies say plainly when it is off.

## Settings and permissions

- Web search key row (masked field, empty by default).
- Storage access row: current scope (own files / one picked folder / whole device), with 去处理 opening `StorageAccessActivity` or the SAF tree picker. This follows the `SelfCheck.Fix` pattern: a button that does nothing is worse than no button.
- No new launcher Activity; both new Activities are non-launcher and use `Theme.Body.Stage.NoPreview`.

## Testing

- Host tests: query normalisation, extension filter, result cap, MIME fallback, path display, and timeout returning `partial: true`.
- Core tests: the schemas are local-only and outside `screen.`; `web.search` latches the turn untrusted; `file.open` and all five write tools are refused on an untrusted turn and on non-USER turns (`TurnGuardsTest`).
- Device pass: set a Tavily key, ask a current question, get titled results with URLs; ask about a file by name, get its path; say open it, see the right app open with the right file; revoke storage access and confirm the card says the scope is limited to Andee's own files; turn file edits on, ask Andee to create a file, edit it, move it, then delete it with confirmation, and check the backup exists after the overwrite.

## Out of scope

Indexing database, OCR, web page fetch/read tool, automatic opening on a search hit, Play Store compatibility work, and desktop mirroring.

## Sources

- https://docs.tavily.com/documentation/api-reference/endpoint/search
- https://developer.android.com/guide/topics/manifest/provider-element
- https://developer.android.com/reference/android/os/Environment#isExternalStorageManager()
