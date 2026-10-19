@../../BRAND-SYSTEM.md

# R3U | Phone Link — Android app + web dashboard + landing page

Browse an Android phone's photos from a desktop browser on the same Wi-Fi.
The phone app runs an embedded HTTP server (NanoHTTPD) and serves the web
UI itself. No USB, ADB, or cloud.

> The shared R3U brand system above applies to R3U Suite modules. Gallery's
> own visual spec (soft neutral palette, one accent, light/dark) lives in
> the original brief and in `app/src/main/assets/web/css/styles.css`; where
> they differ, the Gallery spec wins.

## Layout

```
app/                         Android app (Kotlin, Compose, Gradle KTS)
  src/main/kotlin/.../server/    PURE Kotlin server layer (no android.* imports) - unit-tested on the JVM
  src/main/kotlin/.../android/   MediaStore, foreground service, trash flow, network tracker
  src/main/kotlin/.../ui/        Compose screens + MainActivity
  src/main/assets/web/           Web UI served by the phone (vanilla ES modules, no build step)
  src/test/                      JVM unit tests (server layer)
landing/                     Static GitHub Pages site (APK download + "Open gallery")
tools/                       mock-server.mjs (UI dev), keystore + package-rename scripts
.github/workflows/           build.yml (every push), release.yml (v* tags), pages.yml
.devcontainer/               Codespaces: JDK 17 + Android cmdline SDK + Gradle
```

## Rules of the road

- **No local Android toolchain.** Everything builds in GitHub Actions /
  Codespaces. Do not add instructions that require Android Studio.
- Keep `server/` free of `android.*` imports; Android specifics are injected
  through `PhotoStore`, `TrashGateway`, `AssetProvider`.
- Web UI: no CDN, no build step, no external requests. Must work offline on a LAN.
- Security invariants (do not weaken): per-start random token, HttpOnly +
  SameSite=Strict cookie, every non-handshake request needs the cookie,
  clients limited to private IP ranges, keystore never committed.
- Preview the web UI without a phone: `node tools/mock-server.mjs` then open
  http://localhost:8099/?t=dev
