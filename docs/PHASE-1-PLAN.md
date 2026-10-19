# Phase 1 plan: Phone Link tier (same Wi-Fi, no cloud)

Status: BUILT and tested on Android 11, 13 and 14 emulators in CI (2026-10-12). Real-phone check pending.

## Scope

A browser dashboard served by the phone with five tabs: **Photos** (existing), **Notifications**, **Messages**, **Files**, **Status**, plus **Find phone**. Protected by an app passcode and per-feature switches.

| Area | Web feature | Android mechanism | Permission / setup |
|---|---|---|---|
| Passcode | Lock screen, lockout | PBKDF2-SHA256 hash in app prefs, per-IP lockout | none |
| Feature switches | Tabs disabled when off | Prefs, enforced server-side (403) | none |
| Status | Device name, model, Android, battery, charging, storage | BatteryManager, StatFs | none |
| Notifications | List, dismiss, reply | NotificationListenerService | user enables "Notification access" |
| Messages | Threads, history, send | content://sms, SmsManager, ContactsContract | READ_SMS, SEND_SMS, READ_CONTACTS |
| Files | Upload from PC, list, download, delete; send phone to PC through the Android Share sheet | MediaStore.Downloads (`Download/R3U Phone Link/`), share-target activity | none |
| Clipboard | PC to phone (set), phone to PC (share text to the app) | ClipboardManager, share-target | none |
| Find phone | Ring at full volume, stop | MediaPlayer on the alarm stream, auto-stop at 60 s | none |

## Server API additions (all under the existing token cookie)

`GET /api/session`, `POST /api/login`, `POST /api/logout`, `GET /api/status`, `GET /api/notifications`, `POST /api/notifications/dismiss|reply`, `GET /api/sms/threads`, `GET /api/sms/threads/{id}`, `POST /api/sms/send`, `GET /api/files`, `GET /api/files/{id}/download`, `POST /api/files/upload`, `DELETE /api/files/{id}`, `GET|POST /api/clipboard`, `GET /api/find`, `POST /api/find/ring|stop`.

Order of checks per request: private IP, token cookie, **unlock cookie when a passcode is set (423 Locked)**, **feature enabled (403)**, handler.

## Security model

- Passcode is mandatory before the server starts (min 6 characters). Hash only is stored (PBKDF2, random salt).
- Wrong passcode: 5 free attempts per client IP, then lockout 30 s doubling to 15 min. Correct passcode during lockout is also refused.
- Second cookie `gallery_unlock` (HttpOnly, SameSite=Strict), random per server start.
- Sensitive features default **off**; the user switches each on in the app, which also requests the Android permission.
- Known limit: LAN traffic is plain HTTP, so the passcode and content can be read by someone sniffing your Wi-Fi.

## Test strategy

1. JVM unit tests for every new route, the lockout, the passcode gate and feature gates (fakes for each provider).
2. Mock server + browser pass for the new web tabs.
3. Emulator job in GitHub Actions (Android 13/14): install debug APK, grant permissions, start the server, hit the API with curl, capture a screenshot. Debug builds only have a test hook that skips the unlock prompt.
4. One real-phone check from the owner at the end (manual checklist).

## Assumptions made while the owner was away (review these)

1. Phase 1 before any cloud work; sideload-only distribution (keeps SMS and notification access).
2. Passcode applies to **all** web access, including photos, once set. Sensitive features are opt-in.
3. Files tab uses a dedicated `Download/R3U Phone Link` folder plus the Share sheet rather than free browsing of all storage (no broad storage permission).
4. Clipboard from phone to PC works through the Share sheet, because Android blocks background clipboard reads.
5. Notification reply only appears when the source app offers a reply action.
6. Version becomes 1.1.0 when Phase 1 ships.
