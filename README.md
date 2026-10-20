# R3U | Phone Link

Browse your Android phone's photos from a desktop browser on the same Wi-Fi: zoom, download, and move unwanted photos to the trash. No USB, no developer options, no ADB, no cloud. The phone app runs a small web server and serves the web UI itself.

```
Desktop browser --HTTP over Wi-Fi--> Android app (embedded server + web UI) --> MediaStore
```

Three pieces live in this repo:

| Piece | Where | What it is |
|---|---|---|
| Android app | `app/` | Kotlin + Compose. Foreground service running NanoHTTPD, biometric gate, QR code. |
| Web UI | `app/src/main/assets/web/` | Vanilla JS/CSS, no build step, no CDN. Bundled in the APK. |
| Landing page | `landing/` | Static page on GitHub Pages: APK download + "Open gallery" box. Never touches photos. |

You need **no local Android setup**. Everything builds on GitHub (Codespaces for hacking, Actions for APKs).

## Project structure

```
.devcontainer/            Codespaces: JDK 17 + Android command-line SDK + Gradle
.github/workflows/
  build.yml               every push: unit tests + debug APK (uploaded as an artifact)
  release.yml             tag v*: signed release APK -> GitHub Release (gallery.apk)
  pages.yml               deploys landing/ to GitHub Pages
app/
  src/main/kotlin/.../server/    pure-Kotlin server layer (auth, API, range, zip) - JVM-tested
  src/main/kotlin/.../android/   MediaStore store, foreground service, trash flow, network tracker
  src/main/kotlin/.../ui/        Compose screens, MainActivity, TrashConfirmActivity
  src/main/assets/web/           the web UI the phone serves
  src/test/                      unit tests
landing/                  GitHub Pages site (config.js holds the download link)
tools/                    mock-server.mjs, generate-keystore.sh, rename-package.sh
docs/MANUAL-TESTS.md      device test checklist (Android 11 / 13 / 14)
```

## Open the project in Codespaces

1. Push this repo to GitHub (see "First-time GitHub setup" below).
2. On the repo page: **Code → Codespaces → Create codespace on main**.
3. Wait for the container to build. It installs JDK 17, Gradle, Node, the Android SDK (platform 35) and generates `./gradlew` if it is missing. **Commit `gradlew`, `gradlew.bat` and `gradle/wrapper/`** the first time.
4. Build and test inside the Codespace:
   ```bash
   ./gradlew testDebugUnitTest assembleDebug     # APK: app/build/outputs/apk/debug/
   ```
5. Preview the web UI without a phone (the Codespace forwards port 8099):
   ```bash
   node tools/mock-server.mjs        # then open the forwarded URL with ?t=dev
   ```

## Trigger a build

- **Every push / pull request** runs `build.yml`: unit tests, then a debug APK uploaded as the artifact `gallery-debug-apk` (Actions tab → the run → Artifacts).
- Run it by hand: Actions → **Build** → *Run workflow*.
- If a build fails, read the logs with the GitHub CLI and fix the cause:
  ```bash
  gh run list --limit 5
  gh run view --log-failed
  ```

## Signing and publishing a release

Release APKs must be signed with a key that **never changes**. Android only installs an update over an existing app if the signature matches.

1. **Generate the keystore** (in a Codespace; it needs `keytool`):
   ```bash
   bash tools/generate-keystore.sh
   ```
   It prints the password and the four secrets to add. The keystore files are gitignored (`gallery-release.*`).
2. **Back up the keystore and passwords in a password manager now.** If you lose them you can never ship an update that installs over the old version.
3. **Add the repository secrets** (Settings → Secrets and variables → Actions), or run the `gh secret set …` lines the script prints:
   `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
4. **Tag a release:**
   ```bash
   git tag v1.0.0 && git push origin v1.0.0
   ```
   `release.yml` runs the tests, builds and verifies the signed APK, and publishes a GitHub Release with `gallery.apk`. The stable download URL is
   `https://github.com/<owner>/<repo>/releases/latest/download/gallery.apk`.
5. Delete the local keystore copies once they are safely backed up: `shred -u gallery-release.jks gallery-release.jks.b64`.

Version name comes from the tag (`v1.2.3` → `1.2.3`); version code is the commit count, so it always increases.

## Landing page and its download link

1. Settings → **Pages** → Source: **GitHub Actions**.
2. `pages.yml` deploys `landing/` on pushes that touch it and after every release. It **regenerates `landing/config.js`** from the repo name and the latest release (version + size), so the Download button points at `releases/latest/download/gallery.apk` automatically.
3. To host the APK somewhere else, edit the one place that holds the link: `landing/config.js` → `downloadUrl` (and stop `pages.yml` from overwriting it by deleting its "Generate landing/config.js" step).

The "Open gallery" box accepts only `http://<10.x | 172.16-31.x | 192.168.x>.x:<port>/…`; anything else shows *Paste the link shown in the app*. Check it with `node landing/validate.test.mjs`.

## Install the APK from the phone's browser

1. On the phone, open the landing page (or the repo's Releases page) and tap **Download APK**.
2. When the browser warns, allow it to **Install unknown apps** (Settings → Apps → Special access → Install unknown apps → your browser).
3. Open the downloaded file and tap **Install**. If **Play Protect** warns about an app from outside the Play Store, choose **Install anyway**.
4. Open **R3U | Phone Link**, choose **Allow all** for photos, and confirm with your fingerprint, face or passcode. The server starts automatically and shows a link and a QR code.
5. On the computer (same Wi-Fi), paste the link into the landing page's *Open gallery* box, or type it into the browser directly.

## Changing the package id

The placeholder id is `com.example.gallery`. Change it **before your first release** (the id identifies the app on every phone permanently):

```bash
bash tools/rename-package.sh com.yourcompany.gallery
```

It updates `namespace` and `applicationId` in `app/build.gradle.kts`, rewrites package/imports and moves the source folders.

## Phase 1: phone dashboard (v1.1.0)

Beyond photos, the web page now has tabs for **Notifications, Messages, Files and Phone** (status + Find my phone). Plan and assumptions: [docs/PHASE-1-PLAN.md](docs/PHASE-1-PLAN.md).

- **Web passcode.** After unlock the app asks you to set a passcode (6+ characters). The browser must enter it; 5 wrong tries lock that client out (30 s, doubling to 15 min). Only a salted hash is stored.
- **Per-feature switches** (app: *Features and passcode*). Photos and Phone status start on; Notifications, Messages, Files and Find phone are off until you enable them, and the server refuses them while off. Enabling Notifications sends you to Android's *Notification access*; Messages asks for SMS and contacts permission.
- **Files and clipboard.** Drag files to the Files tab to put them on the phone (`Download/R3U Phone Link`). To go the other way, share a file or text from any app to **R3U | Phone Link**; it appears in the Files tab.
- **Known limits.** Android records sent texts only for the default SMS app, so replies you send from the browser appear in the open conversation but may not be in the history later. The passcode and content travel over plain HTTP on your Wi-Fi.
- **Emulator tests.** `emulator.yml` boots Android 11, 13 and 14 emulators in CI and exercises the real server end to end (`tools/e2e/smoke.sh`).

## The new layout (in the repository, not yet released)

The web app now follows an app-bar layout: a **ribbon** (Home, View, Tools) on top, an **app bar** on the left (Photos, Alerts, Messages, Contacts, Files, Phone), a **list pane** (albums, conversations, contacts, folders), the **content**, and a **status bar**. On narrow screens the app bar moves to the bottom and the list slides over the content.

New with it: **albums** (the phone's photo folders), a **Contacts** app (its own switch in the phone app), **search** in every app, and **Open on the phone** (a link, the dialer or a map, from the Phone app or a contact). Presentation with screenshots: https://r3u-software.github.io/r3u-gallery-roadmap/deck/

## Drive backup (silent)

The phone can quietly copy its photos and videos into a Google Drive folder you choose. It uses a small Google Apps Script that lives in **your own** Google account, so there is no Google Cloud project, no OAuth client and nothing of ours in between.

```
phone --(asks for an upload slot)--> your Apps Script --(creates it in Drive)--> your folder
phone --(sends the file itself, in chunks, resumable)----------------------------> Google Drive
```

- **Built in, no setup.** Make a release with the repository secrets `GALLERY_BACKUP_URL` (the script's web app address) and `GALLERY_BACKUP_SECRET` (CI passes them to the build; they are never in git) and the app has nothing to configure: no switch, no dashboard panel. Backup starts as soon as the app is open and has the permissions it asks for once after the first unlock. Because the APK is public, anyone who downloads it can read that pair and add (not read) files in the folder; to rotate it, change `SECRET` in the script and both repository secrets, then tag a release. To build your own, follow https://r3u-software.github.io/gallery/backup.html to make the script, then set those two secrets.
- **Silent by design.** No notification of its own, no progress, no error messages. The Drive folder is the status. If it ever seems stuck, open `/api/backup/diag` on the dashboard address by hand: it says what the last pass did (for example "waiting: not on Wi-Fi"). Nothing in the page links to it.
- **How it runs.** Inside the always-on service that serves the dashboard, so it starts as soon as the app is open (plus ordinary background work, about every 15 minutes, as a fallback). "Wi-Fi only" means Wi-Fi or a cable, not mobile data. It runs again soon after a new photo or video appears. It works newest first, one file at a time, and an interrupted file resumes where it stopped. Android may run it late when the phone is idle and unplugged; overnight on the charger is the reliable time.
- **Never deletes.** Trashing a photo on the phone or in the dashboard leaves the Drive copy alone. A file already in the folder (same name and size) is not uploaded twice.
- **Security.** The script only answers requests carrying your secret. The phone only ever contacts `script.google.com` and `*.googleapis.com` over https, and the secret never goes back to the browser. Keep the Drive folder's sharing on **Restricted**.
- **Limits.** Drive's free 15 GB is shared with Gmail and Photos. Google caps Apps Script at about 20,000 Drive calls a day (about 10,000 files). Photo GPS data is kept when the *photo location* permission is granted.
- **Tests.** `node landing/backup.test.mjs` runs the real script against a fake Google; the Kotlin tests cover the uploader, resume and the API; the emulator job (`tools/e2e/fake_gas.py`) backs a photo up end to end.

## Screenshots and screen recording

In release builds every screen of the phone app (the link and QR code, settings, the unlock screens) is a secure window: screenshots and screen recordings come out black and the recent-apps preview is blank. Debug builds are left alone so CI can take the documentation screenshots. This cannot stop another camera pointed at the screen, and it does not apply to the web dashboard in a browser.

## How it behaves

- **Start flow:** photo permission ("Allow all") → biometric / device-credential prompt → server starts. The prompt appears on every fresh app launch. No screen lock set → the server never starts and the app asks you to set one. Partial photo access ("Select photos") → a screen explains full access is needed.
- **Wi-Fi:** the link, QR code and notification follow the phone's address. No Wi-Fi → "Connect to Wi-Fi" and nothing is served. Revoking photo access or removing the screen lock stops the server within 5 seconds.
- **Trash:** the browser asks for confirmation, then the phone shows Android's own dialog (immediately if the app is open, otherwise via a heads-up notification). Photos stay recoverable in Android's trash for 30 days.
- **Security:** a random token per server start; `/?t=<token>` sets an `HttpOnly; SameSite=Strict` cookie and redirects; every other request needs that cookie (401 otherwise); clients outside private IP ranges get 403. Traffic is plain HTTP on your LAN (HTTPS is out of scope for v1), so use a network you trust. The notification never contains the token.

## Tests

- **Unit tests** (JVM, run in CI): pagination (incl. 12,000 photos), token + cookie rules, private-IP rule, range requests, zip streaming (memory-bounded, stops when the client leaves), trash endpoints. `./gradlew testDebugUnitTest`
- **Landing validation:** `node landing/validate.test.mjs`
- **On a real phone:** follow [docs/MANUAL-TESTS.md](docs/MANUAL-TESTS.md).

## First-time GitHub setup

```bash
gh repo create gallery --public --source . --push     # Pages + stable release URLs need a public repo on the free plan
```

Then: enable Pages (Settings → Pages → GitHub Actions), add the signing secrets, tag `v0.1.0`.

## Defaults chosen (change freely)

- `compileSdk`/`targetSdk` 35 with AGP 8.7 / Gradle 8.11 / Kotlin 2.0 (a known-good set; bump together).
- Android 11–12 use `READ_EXTERNAL_STORAGE` because `READ_MEDIA_IMAGES` only exists on 13+. Android 14's "Select photos" is detected and rejected.
- Foreground service type `specialUse` (a LAN web server fits no other type; `dataSync` is time-limited on Android 15).
- After tapping **Stop server** the app shows "Stopped" with no Start button, per the "auto-start only" flow. Reopen the app to start again.
- Photos are sorted by EXIF date taken, falling back to the file's modified time.
- Release builds are not minified (keeps NanoHTTPD safe; the APK is small).
- Multi-photo downloads stream through a form POST into a hidden iframe so the browser saves the zip straight to disk.
