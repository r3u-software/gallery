# Manual test checklist

Run on a real phone with the debug or release APK. Repeat the **core path** on each Android version, then the **scenarios** on the version noted. Tick = pass. Write down the phone model and Android version in each run.

Devices: **Android 11** (API 30), **Android 13** (API 33), **Android 14** (API 34).
Desktop: any current Chrome / Edge / Firefox / Safari on the same Wi-Fi.

## Core path (every Android version)

| # | Step | Expected | 11 | 13 | 14 |
|---|---|---|---|---|---|
| 1 | Fresh install, open the app | System photo permission prompt appears immediately | ☐ | ☐ | ☐ |
| 2 | Choose **Allow all** | Biometric / passcode prompt appears right away | ☐ | ☐ | ☐ |
| 3 | Authenticate | "Starting server…" then the Running screen: green "Running" pill, QR code, monospace link, **Copy link**, **Stop server**, hint text | ☐ | ☐ | ☐ |
| 4 | Notification shade | Ongoing notification with **Stop server** action; it does **not** show the token | ☐ | ☐ | ☐ |
| 5 | Open the link on the computer | Gallery loads, photos appear, count shown, URL becomes `/` (token gone) | ☐ | ☐ | ☐ |
| 6 | Reopen the same link in a private window | Works once (new cookie). Open `/api/photos` directly in a fresh profile without the link → **401** | ☐ | ☐ | ☐ |
| 7 | Scroll a long library | Smooth; thumbnails load as they come into view; date headers correct | ☐ | ☐ | ☐ |
| 8 | Click a photo | Viewer opens; "3 of N"; name; zoom −/+ with % (50–400); Fit; wheel zoom; double-click toggles; drag pans when zoomed; ←/→, +/−/0, Esc | ☐ | ☐ | ☐ |
| 9 | Viewer on touch laptop or phone browser | Pinch zoom, swipe to next/previous | ☐ | ☐ | ☐ |
| 10 | Select 1 photo → Download | File downloads with its original name | ☐ | ☐ | ☐ |
| 11 | Select 5+ photos (use shift-click for a range) → Download | Toast, then one `gallery-<date>.zip` containing all, original names | ☐ | ☐ | ☐ |
| 12 | Filters: Screenshots, Large files, sort Oldest | Lists update and counts change | ☐ | ☐ | ☐ |
| 13 | Select 2 photos → Move to trash → confirm in browser | "Confirm on your phone" appears; phone shows Android's trash dialog **immediately** (app in foreground) | ☐ | ☐ | ☐ |
| 14 | Approve on phone | Browser: photos animate out, toast; photos are in the system Gallery/Photos trash | ☐ | ☐ | ☐ |
| 15 | Repeat 13 but **Deny** | Browser toast says nothing was moved; photos still there | ☐ | ☐ | ☐ |
| 16 | Tap **Stop server** | Browser shows "Session ended"/errors; app shows Stopped; notification gone | ☐ | ☐ | ☐ |

## Scenarios

| Scenario | Steps | Expected | Version |
|---|---|---|---|
| **Permission denied** | Deny the photo prompt (twice to make it permanent) | Permission screen with **Allow photo access** and **Open app settings**; server never starts. Granting in Settings then returning continues the flow automatically | 11 / 13 / 14 |
| **Partial access** | Android 14: choose **Select photos** | "Full photo access is required" screen with **Fix in settings**; server does not start. After switching to *Allow all* in Settings and returning, the flow continues | 14 |
| **Revoked later** | While running, revoke Photos permission in Settings | Server stops within ~5 s; browser fails; reopening the app shows the permission screen | 13 / 14 |
| **No screen lock** | Remove the screen lock in Settings, open the app | "Set a screen lock first" screen, server does not start. Setting a lock and returning continues | 11 / 13 |
| **Biometric cancelled** | Cancel the prompt | "Unlock to continue" screen with **Unlock** button; no server running; no prompt loop | all |
| **Every launch** | Swipe the app away, reopen | Biometric prompt appears again before the Running screen | all |
| **Wi-Fi change** | Switch the phone to a different Wi-Fi network while running | Link, QR and notification text update to the new address (old link stops working); new link works | 13 |
| **Wi-Fi off** | Turn Wi-Fi off | App shows **Connect to Wi-Fi**, nothing served; turning Wi-Fi on brings the server back with a **new** link/token | 11 / 13 |
| **Port busy** | Occupy 8080 (another app) or start twice | Server falls back to 8081… and the shown link has that port | any |
| **10,000 photos** | Library of 10k+ images (copy a folder of samples, or use a stress phone) | Grid appears after the first page, count grows to the full total; scrolling stays smooth; Select all works; no crash / ANR; memory stays reasonable | 13 / 14 |
| **HEIC** | A HEIC photo (Android 11+ camera set to HEIC) | Thumbnail shows, viewer shows it (JPEG rendition), Download gives the original `.heic` | 13 / 14 |
| **Rotated photo** | Portrait photo with EXIF rotation | Correct orientation in grid, viewer and thumbnail | any |
| **Trash from background** | Press Home so the app is not visible, then Move to trash in the browser | A heads-up notification "Confirm moving N photos to trash" appears; tapping it opens the system dialog; approving updates the browser | 13 / 14 |
| **Trash, no notification permission** | Deny notifications on Android 13+, repeat the above | Browser keeps waiting; as soon as you open the Gallery app, Android's trash dialog appears and approving it completes the request | 13 / 14 |
| **Trash timeout** | Request trash and ignore it for 3+ minutes | Browser shows a timeout message and nothing is moved | 13 |
| **Foreign network** | Connect to the server from a device with a public IP (e.g. via port-forward/VPN) | **403**, even with a valid cookie | any |
| **Download interrupted** | Start a large zip, close the tab | Server stays healthy; next request works (no stuck threads, phone not hot) | 13 |
| **Zip size** | Download 200+ photos as a zip | Starts immediately, streams to disk, browser tab memory stays flat | 13 / 14 |
| **Doze / screen off** | Lock the phone for 10 minutes | Gallery still reachable (foreground service + Wi-Fi lock) | 13 / 14 |
| **Update over old version** | Install release v1, then v2 (same signature) | Installs over the top; settings intact | any |

## Automated checks to run first

```bash
./gradlew testDebugUnitTest      # server layer
node landing/validate.test.mjs   # landing link validation
```

## Record

| Date | Phone / Android | Build (tag or run #) | Failures / notes |
|---|---|---|---|
| | | | |
