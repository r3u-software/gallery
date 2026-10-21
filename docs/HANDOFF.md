# Handoff: where R3U | Phone Link stands, what was decided, and how to carry on

Read this first when you pick the project up in a new session, so nothing already settled gets asked again.
It deliberately holds **no secrets and no personal data** (the repos are public). Things that live outside the
repo are listed in "What is not in this repo".

## What it is

**R3U | Phone Link** (the repo and `gallery.apk` keep the old name "gallery"):

| Piece | Where | Notes |
|---|---|---|
| Android app | `app/` | Kotlin + Compose. A foreground service runs an embedded NanoHTTPD server and serves the web dashboard. |
| Web dashboard | `app/src/main/assets/web/` | Vanilla ES modules, OneNote-style layout (ribbon, app bar, list pane, content, status bar). Photos, Alerts, Messages, Contacts, Files, Phone. |
| Landing page | `landing/` | GitHub Pages: https://r3u-software.github.io/gallery/ (download button, version and size come from the latest release). |
| Drive backup setup page | `landing/backup.html` | Builds the Apps Script with the folder and a random secret filled in. No longer linked from the landing page. |
| Apps Script | `landing/backup/Code.gs` + `appsscript.json` | Hands the phone a resumable upload slot in the owner's Drive folder. |
| Roadmap + presentation | separate repo `r3u-gallery-roadmap` | 21-slide deck (screenshots come from the `screenshots.yml` workflow here: mock data + emulator, never real phone data). Phases 2-4 are marked ON HOLD. |

Current release: **v1.3.3**. Everything builds, tests and releases on GitHub (Actions, Codespaces). **Nothing is
installed on the owner's PC for development.** `.devcontainer/` gives JDK 17 + Android SDK + Gradle.

## Decisions that are settled (do not reopen without a reason)

- Product name "R3U | Phone Link". Open question still unanswered: "Phone Link" is Microsoft's product name (possible confusion).
- Cloud-only workflow. "Silence means yes" for autonomous work, except: never create accounts or enter payment details, and ask before anything outward-facing that is hard to undo.
- Security model: random token per server start, HttpOnly + SameSite=Strict cookie, private-IP clients only, a second unlock cookie after the web passcode (423 Locked), per-feature switches enforced server-side (403), lockout with doubling delay (429). Plain HTTP on the LAN is a documented limit.
- Dashboard layout: OneNote for Windows 10 style (decided after the owner rejected the earlier tab layout).
- **Drive backup** (owner's explicit requirements):
  - Individual files, not zip batches (zipping saves no space: photos and videos are already compressed).
  - Via the owner's own **Google Apps Script**, not OAuth or a Cloud project. The phone asks the script for an upload slot, then sends the bytes straight to Google in chunks (resumable).
  - **Silent**: no notification of its own, no progress, no error messages anywhere. The Drive folder is the status. The only hidden aid is `GET /api/backup/diag` on the dashboard address (not linked from the page).
  - **Automatic**: no switch, no dashboard panel. The script address and secret are baked into release builds from the repository secrets `GALLERY_BACKUP_URL` and `GALLERY_BACKUP_SECRET` (never committed). Because the APK is public, anyone can read that pair and add (not read) files in the folder. Rotating = change `SECRET` in the script and both repo secrets, then tag a release. The owner knows and accepted this.
  - Album sub-folders are ON in the owner's script. Newest first across all albums, so an album's folder only appears when its first photo comes up.
  - Never deletes from Drive. Duplicates are matched on name + size + taken time.
  - It runs inside the foreground service (a loop) with a WorkManager job as fallback. "Wi-Fi only" means Wi-Fi/Ethernet transport, not "unmetered".
- Screenshots and screen recording are blocked in release builds (FLAG_SECURE) by default, with a switch in **Features and passcode**. Debug builds are never blocked so CI can take documentation screenshots.
- Phases 2-4 (reach the phone from anywhere, accounts and pairing, screen mirroring) are ON HOLD. Phases 2-3 would need a Cloud account that the owner creates.

## Carrying on from the repo alone

1. Open the repo in a Codespace (`gh codespace create -R r3u-software/gallery`) or clone it anywhere, and start Claude Code there. `CLAUDE.md` points at this file.
2. Everything above under "How to test" works from there, including the real-Google check.
3. Two things need a person or the owner's own PC: deploying or changing an **Apps Script** (Google's permission screen is a separate window that tools can't click), and anything on the phone itself.

## What is not in this repo

- **Signing keystore and its passwords**: stored as the repo secrets `ANDROID_KEYSTORE_*` / `ANDROID_KEY_*`, and backed up by the owner outside OneDrive. Losing it means no update can install over the old app.
- **Repo secrets** `GALLERY_BACKUP_URL`, `GALLERY_BACKUP_SECRET` (see above).
- **The Apps Script project and the Drive folder** live in the owner's Google account. The script has the full `drive` scope (the folder pre-existed, and `drive.file` can't see folders the script didn't create) plus the **Drive API advanced service**. Without that service every Drive call fails because the hidden Cloud project has the API off. The folder's sharing must stay **Restricted**.
- Claude's local memory and the conversation transcript (on the owner's PC; they hold personal details and are not for the repo).

## How to test

| What | How |
|---|---|
| Server, engine, transport, API | `gradle testDebugUnitTest` (Build workflow runs it on every push). |
| Apps Script and setup page | `node landing/backup.test.mjs` runs the real `Code.gs` against a fake Google. `node landing/validate.test.mjs` for the landing helpers. |
| Real Android runtime | Emulator workflow (API 30/33/34): `tools/e2e/smoke.sh` plus `tools/e2e/fake_gas.py` (a fake Apps Script on the host, reached from the emulator at 10.0.2.2). Run it on a branch with `gh workflow run emulator.yml --ref <branch>`. |
| Dashboard UI without a phone | `node tools/mock-server.mjs`, then open `http://localhost:8099/?t=dev`. |
| **Real Google** (the deployed script) | Actions > **Drive check** > Run workflow (add the `upload` tick to also send one tiny file). It uses the repo secrets, so it works from a Codespace or any machine with `gh`: `gh workflow run drive-check.yml -f upload=true`. By hand: `curl -sL -H 'Content-Type: text/plain' --data '{"secret":"...","action":"ping"}' "<exec url>"` should answer `{"ok":true}`; a wrong secret gives `unauthorized`. An `init` call returns a Google upload address; PUT bytes to it with `Content-Range`. **Use `--data` without `-X POST`**: curl's `-X POST` re-posts to the redirect target and gets HTTP 411. Fake Google cannot catch Drive-API-not-enabled problems; only a real call can. |

## Pitfalls already paid for

- NanoHTTPD reuses one session per keep-alive connection: parse each request body once (ThreadLocal), drain bodies on early rejections.
- Write Kotlin through the editor tools or raw strings, never through Python string escapes (they mangle regex backslashes). `.gitattributes` forces LF; some files are CRLF on Windows, so scripted multi-line replacements can silently miss. Check that a replacement really changed the file.
- The landing and roadmap pages have a strict CSP: no inline style attributes. A broad rule like `.card a {color}` also hits button-styled links (it made the Download label unreadable once); scope colour rules with `:not(.btn)`.
- The Claude in Chrome extension cannot see or click Google's OAuth consent popup (a separate window). Authorizing a script needs a person.
- Apps Script web app `POST` answers with a redirect to the page holding the reply; HttpURLConnection follows it, curl needs `--data` with `-L`.
- Don't commit identifiers belonging to the owner (Drive folder id, emails, URLs of the deployed script). The folder id was once committed in a test by mistake (history only; harmless while the folder is Restricted).

## Open items

- Confirm on the real phone: the in-service backup loop, the new screenshot switch, and that other album folders appear as the backlog works down.
- Old test deployments of the Apps Script remain active in its project; archive them (Manage deployments).
- Decide the name question above. The landing page screenshots and deck predate the Drive backup.
- A duplicate-looking SMS bubble seen once; cause unknown.

## Working agreement with the owner

The owner has been clear and fast to lose patience with repeats: act, verify, then report in plain language; don't ask what the code or this file already answers; never put secrets in chat unless they are the thing being delivered; confirm before anything outward-facing or irreversible (deploying publicly, granting permissions, releasing something that changes what is already installed).
