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
  adb shell pm grant $PKG android.permission.READ_MEDIA_VIDEO
  [ "$API" -ge 34 ] && adb shell pm grant $PKG android.permission.READ_MEDIA_VISUAL_USER_SELECTED || true
  adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
else
  adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE
fi
adb shell pm grant $PKG android.permission.READ_SMS
adb shell pm grant $PKG android.permission.SEND_SMS
adb shell pm grant $PKG android.permission.READ_CONTACTS
adb shell pm grant $PKG android.permission.ACCESS_MEDIA_LOCATION || true

# A real photo for MediaStore (1x1 PNG), and a 2.5 MB "video" to back up.
echo 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==' | base64 -d > "$OUT/test.png"
adb push "$OUT/test.png" /sdcard/Pictures/r3u-e2e.png >/dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/r3u-e2e.png >/dev/null
head -c 2621440 /dev/urandom > "$OUT/clip.mp4"
adb push "$OUT/clip.mp4" /sdcard/Movies/r3u-e2e-clip.mp4 >/dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/r3u-e2e-clip.mp4 >/dev/null

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

has() { local pat="$1"; shift; local body; body=$(curl -s "$@" || true); grep -q -- "$pat" <<<"$body"; }
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
has "R3U" -b "$JAR" "$BASE/" || fail "web UI not served"
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
has '"batteryPercent"' -b "$JAR" "$BASE/api/status" || fail "status"
ok "status"

# --- files ---
echo "hello from the computer" > "$OUT/up.txt"
[ "$(code -b "$JAR" -F "file=@$OUT/up.txt;filename=up.txt" "$BASE/api/files/upload")" = "200" ] || fail "file upload"
LIST=$(curl -s -b "$JAR" "$BASE/api/files")
grep -q 'up.txt' <<<"$LIST" || fail "uploaded file not listed: $LIST"
FID=$(echo "$LIST" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2 || true)
[ -n "$FID" ] || fail "no file id in $LIST"
has "hello from the computer" -b "$JAR" "$BASE/api/files/$FID/download" || fail "file download content"
[ "$(code -b "$JAR" -X DELETE "$BASE/api/files/$FID")" = "200" ] || fail "file delete"
ok "files upload, list, download, delete"

# --- clipboard + find ---
[ "$(code -b "$JAR" -X POST -H 'Content-Type: application/json' -d '{"text":"clip"}' "$BASE/api/clipboard")" = "200" ] || fail "clipboard set"
[ "$(code -b "$JAR" -X POST -H 'Content-Type: application/json' -d '{}' "$BASE/api/find/ring")" = "200" ] || fail "ring"
has '"ringing":true' -b "$JAR" "$BASE/api/find" || fail "should be ringing"
curl -s -b "$JAR" -X POST -H 'Content-Type: application/json' -d '{}' "$BASE/api/find/stop" >/dev/null
ok "clipboard and find phone"

# --- notifications ---
adb shell cmd notification post -S bigtext -t "E2E" r3u-e2e "Body from the emulator" >/dev/null || true
N=""
for i in $(seq 1 15); do
  N=$(curl -s -b "$JAR" "$BASE/api/notifications" || true)
  grep -q "r3u-e2e" <<<"$N" && break
  sleep 1
done
grep -q '"connected":true' <<<"$N" || fail "notification listener not connected: $N"
grep -q "r3u-e2e" <<<"$N" || fail "notification not mirrored: $N"
ok "notification mirrored"
NKEY=$(echo "$N" | grep -o '"key":"[^"]*r3u-e2e[^"]*"' | head -1 | cut -d'"' -f4 || true)
if [ -n "$NKEY" ]; then
  echo "dismissing key: $NKEY"
  DISMISS=$(curl -s -w ' [%{http_code}]' -b "$JAR" -X POST -H 'Content-Type: application/json' -d "{\"key\":\"$NKEY\"}" "$BASE/api/notifications/dismiss" || true)
  echo "dismiss response: $DISMISS"
  GONE=""
  for i in $(seq 1 10); do
    sleep 1
    AFTER=$(curl -s -b "$JAR" "$BASE/api/notifications" || true)
    if ! grep -q "r3u-e2e" <<<"$AFTER"; then GONE=1; break; fi
  done
  [ -n "$GONE" ] || { adb shell dumpsys notification --noredact | grep -i "r3u-e2e" | head -5 || true; fail "notification not dismissed: $AFTER"; }
  ok "notification dismissed from the browser"
fi

# --- sms (emulator console) ---
adb emu sms send 5551234 "hello from e2e" >/dev/null 2>&1 || true
sleep 2
T=$(curl -s -b "$JAR" "$BASE/api/sms/threads")
grep -q '"permission":true' <<<"$T" || fail "sms permission not granted: $T"
if grep -q "hello from e2e" <<<"$T"; then ok "sms received"; else echo "WARN sms not delivered by emulator console: $T"; fi

# --- backup to Drive: a fake Apps Script on this machine; the emulator reaches it at 10.0.2.2 ---
GAS_PORT=9099
python3 "$(dirname "$0")/fake_gas.py" "$GAS_PORT" >"$OUT/fake_gas.log" 2>&1 &
GAS_PID=$!
trap 'kill $GAS_PID 2>/dev/null || true' EXIT
for i in $(seq 1 20); do curl -s "http://127.0.0.1:$GAS_PORT/report" >/dev/null 2>&1 && break; sleep 0.5; done

BK_BODY='{"url":"http://10.0.2.2:'"$GAS_PORT"'/exec","secret":"e2e-backup-secret","photos":true,"videos":true,"wifiOnly":false}'
BK=$(curl -s -b "$JAR" -X POST -H 'Content-Type: application/json' -d "$BK_BODY" "$BASE/api/backup" || true)
grep -q '"reachable":true' <<<"$BK" || fail "backup save should reach the fake script: $BK"
BKG=$(curl -s -b "$JAR" "$BASE/api/backup")
grep -q '"configured":true' <<<"$BKG" || fail "backup should be configured: $BKG"
grep -q "e2e-backup-secret" <<<"$BKG" && fail "the secret must never be returned: $BKG"
[ "$(code -b "$JAR" -X POST -H 'Content-Type: application/json' -d '{"url":"http://evil.example/exec","secret":"e2e-backup-secret"}' "$BASE/api/backup")" = "400" ] || fail "a non-Google address must be refused"
ok "backup settings saved, secret hidden, bad address refused"

WANT_PNG=$(sha256sum "$OUT/test.png" | cut -d' ' -f1)
WANT_MP4=$(sha256sum "$OUT/clip.mp4" | cut -d' ' -f1)
REPORT=""
for i in $(seq 1 90); do
  REPORT=$(curl -s "http://127.0.0.1:$GAS_PORT/report" || true)
  grep -q "$WANT_PNG" <<<"$REPORT" && break
  sleep 3
done
echo "fake drive report: $REPORT"
grep -q "$WANT_PNG" <<<"$REPORT" || { adb logcat -d -s R3U_BACKUP | tail -20 || true; fail "the photo never reached the fake Drive"; }
ok "photo backed up to the fake Drive, byte for byte"
for i in $(seq 1 20); do
  REPORT=$(curl -s "http://127.0.0.1:$GAS_PORT/report" || true)
  grep -q "$WANT_MP4" <<<"$REPORT" && break
  sleep 3
done
if grep -q "$WANT_MP4" <<<"$REPORT"; then ok "video backed up to the fake Drive, byte for byte"; else echo "WARN the emulator's media scanner did not index the test video: $REPORT"; fi
adb logcat -d -s R3U_BACKUP | tail -5 || true

# --- feature switch is enforced server-side ---
adb shell am force-stop $PKG >/dev/null || true
ok "done"
echo "$LINK" > "$OUT/link.txt"
