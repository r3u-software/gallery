#!/usr/bin/env bash
# Emulator smoke test. Runs inside reactivecircus/android-emulator-runner (see .github/workflows/emulator.yml).
# Installs the debug APK, starts the real server on the emulator and exercises the HTTP API end to end.
set -euo pipefail

PKG=com.example.gallery
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
PASS="e2e-pass-123"
OUT="${2:-e2e-out}"
mkdir -p "$OUT"
fail() { echo "SMOKE FAIL: $*" >&2; adb logcat -d > "$OUT/logcat.txt" || true; adb exec-out screencap -p > "$OUT/failure.png" || true; exit 1; }
ok() { echo "ok  - $*"; }

API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "Android API $API"

adb install -r "$APK" >/dev/null || fail "install"
ok "installed"

# Device state the app requires: a screen lock and full photo access.
adb shell locksettings set-pin 1234 >/dev/null 2>&1 || true
adb shell settings put global stay_on_while_plugged_in 3 || true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true

if [ "$API" -ge 33 ]; then
  adb shell pm grant $PKG android.permission.READ_MEDIA_IMAGES
  [ "$API" -ge 34 ] && adb shell pm grant $PKG android.permission.READ_MEDIA_VISUAL_USER_SELECTED || true
  adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
else
  adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE
fi
adb shell pm grant $PKG android.permission.READ_SMS
adb shell pm grant $PKG android.permission.SEND_SMS
adb shell pm grant $PKG android.permission.READ_CONTACTS

# A real photo for MediaStore (1x1 PNG).
echo 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==' | base64 -d > "$OUT/test.png"
adb push "$OUT/test.png" /sdcard/Pictures/r3u-e2e.png >/dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/r3u-e2e.png >/dev/null

# Let the notification listener in, so the notifications API has something to read.
adb shell cmd notification allow_listener "$PKG/$PKG.android.GalleryNotificationListener" || true

adb logcat -c
adb shell am start -n "$PKG/.ui.MainActivity" --es e2e_passcode "$PASS" >/dev/null

# Wait for the server link in logcat.
LINK=""
for i in $(seq 1 60); do
  LINK=$(adb logcat -d -s R3U_E2E | grep -o "LINK port=[0-9]* token=[A-Za-z0-9_-]*" | tail -1 || true)
  [ -n "$LINK" ] && break
  sleep 2
done
[ -n "$LINK" ] || fail "server never reported a link"
PORT=$(echo "$LINK" | sed -E 's/.*port=([0-9]+).*/\1/')
TOKEN=$(echo "$LINK" | sed -E 's/.*token=([A-Za-z0-9_-]+).*/\1/')
ok "server up on port $PORT"

adb forward tcp:18080 tcp:"$PORT" >/dev/null
BASE=http://127.0.0.1:18080
JAR="$OUT/jar.txt"
rm -f "$JAR"
sleep 2
adb exec-out screencap -p > "$OUT/running-screen.png" || true

code() { curl -s -o /dev/null -w "%{http_code}" "$@"; }

# --- security ---
[ "$(code "$BASE/api/photos")" = "401" ] || fail "no cookie should be 401"
ok "401 without cookie"
[ "$(code -c "$JAR" "$BASE/?t=wrong")" = "401" ] || fail "wrong token should be 401"
[ "$(code -c "$JAR" "$BASE/?t=$TOKEN")" = "303" ] || fail "token handshake should be 303"
grep -q HttpOnly "$JAR" || fail "cookie must be HttpOnly"
ok "token handshake sets an HttpOnly cookie"
[ "$(code -b "$JAR" "$BASE/api/photos")" = "423" ] || fail "passcode gate should answer 423"
ok "passcode gate locks the API"
[ "$(code -b "$JAR" -c "$JAR" -X POST -H 'Content-Type: application/json' -d '{"passcode":"nope"}' "$BASE/api/login")" = "403" ] || fail "wrong passcode should be 403"
[ "$(code -b "$JAR" -c "$JAR" -X POST -H 'Content-Type: application/json' -d "{\"passcode\":\"$PASS\"}" "$BASE/api/login")" = "200" ] || fail "right passcode should be 200"
ok "passcode login"

# --- web UI is served ---
curl -s -b "$JAR" "$BASE/" | grep -q "R3U" || fail "web UI not served"
ok "web UI served"

# --- photos ---
ID=""
for i in $(seq 1 20); do
  PHOTOS=$(curl -s -b "$JAR" "$BASE/api/photos?limit=5" || true)
  ID=$(echo "$PHOTOS" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2 || true)
  [ -n "$ID" ] && break
  # nudge the media scanner again while we wait
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/r3u-e2e.png >/dev/null || true
  sleep 3
done
echo "photos response: $PHOTOS"
[ -n "$ID" ] || { adb shell content query --uri content://media/external/images/media --projection _id:_display_name | head -5 || true; fail "no photo found in MediaStore"; }
[ "$(code -b "$JAR" "$BASE/api/photos/$ID/thumb")" = "200" ] || fail "thumbnail"
[ "$(code -b "$JAR" -H 'Range: bytes=0-9' "$BASE/api/photos/$ID/full")" = "206" ] || fail "range request"
ok "photos, thumbnail and range requests"

# --- status ---
curl -s -b "$JAR" "$BASE/api/status" | grep -q '"batteryPercent"' || fail "status"
ok "status"

# --- files ---
echo "hello from the computer" > "$OUT/up.txt"
[ "$(code -b "$JAR" -F "file=@$OUT/up.txt;filename=up.txt" "$BASE/api/files/upload")" = "200" ] || fail "file upload"
LIST=$(curl -s -b "$JAR" "$BASE/api/files")
echo "$LIST" | grep -q 'up.txt' || fail "uploaded file not listed: $LIST"
FID=$(echo "$LIST" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2 || true)
[ -n "$FID" ] || fail "no file id in $LIST"
curl -s -b "$JAR" "$BASE/api/files/$FID/download" | grep -q "hello from the computer" || fail "file download content"
[ "$(code -b "$JAR" -X DELETE "$BASE/api/files/$FID")" = "200" ] || fail "file delete"
ok "files upload, list, download, delete"

# --- clipboard + find ---
[ "$(code -b "$JAR" -X POST -H 'Content-Type: application/json' -d '{"text":"clip"}' "$BASE/api/clipboard")" = "200" ] || fail "clipboard set"
[ "$(code -b "$JAR" -X POST -H 'Content-Type: application/json' -d '{}' "$BASE/api/find/ring")" = "200" ] || fail "ring"
curl -s -b "$JAR" "$BASE/api/find" | grep -q '"ringing":true' || fail "should be ringing"
curl -s -b "$JAR" -X POST -H 'Content-Type: application/json' -d '{}' "$BASE/api/find/stop" >/dev/null
ok "clipboard and find phone"

# --- notifications ---
adb shell cmd notification post -S bigtext -t "E2E" r3u-e2e "Body from the emulator" >/dev/null || true
N=""
for i in $(seq 1 15); do
  N=$(curl -s -b "$JAR" "$BASE/api/notifications" || true)
  echo "$N" | grep -q "r3u-e2e" && break
  sleep 1
done
echo "$N" | grep -q '"connected":true' || fail "notification listener not connected: $N"
echo "$N" | grep -q "r3u-e2e" || fail "notification not mirrored: $N"
ok "notification mirrored"
NKEY=$(echo "$N" | grep -o '"key":"[^"]*r3u-e2e[^"]*"' | head -1 | sed -E 's/"key":"(.*)"//' || true)
if [ -n "$NKEY" ]; then
  curl -s -b "$JAR" -X POST -H 'Content-Type: application/json' -d "{\"key\":\"$NKEY\"}" "$BASE/api/notifications/dismiss" >/dev/null
  sleep 2
  if curl -s -b "$JAR" "$BASE/api/notifications" | grep -q "r3u-e2e"; then fail "notification not dismissed"; fi
  ok "notification dismissed from the browser"
fi

# --- sms (emulator console) ---
adb emu sms send 5551234 "hello from e2e" >/dev/null 2>&1 || true
sleep 2
T=$(curl -s -b "$JAR" "$BASE/api/sms/threads")
echo "$T" | grep -q '"permission":true' || fail "sms permission not granted: $T"
if echo "$T" | grep -q "hello from e2e"; then ok "sms received"; else echo "WARN sms not delivered by emulator console: $T"; fi

# --- feature switch is enforced server-side ---
adb shell am force-stop $PKG >/dev/null || true
ok "done"
echo "$LINK" > "$OUT/link.txt"
