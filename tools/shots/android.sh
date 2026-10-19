#!/usr/bin/env bash
# Captures real Android screens from the emulator for the documentation deck.
# Run inside reactivecircus/android-emulator-runner (see .github/workflows/screenshots.yml).
set -euo pipefail

PKG=com.example.gallery
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
OUT="${2:-shots/android}"
mkdir -p "$OUT"
shot() { sleep 2; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }

tap_text() {
  local xy=""
  for attempt in 1 2 3 4 5 6; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
    adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1 || true
    xy=$(python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try:
    tree = ET.parse(sys.argv[1])
except Exception:
    sys.exit(0)
want = sys.argv[2]
for n in tree.iter('node'):
    if (n.get('text') or '') == want or (n.get('content-desc') or '') == want:
        l, t, r, b = map(int, re.findall(r'\d+', n.get('bounds')))
        print((l + r) // 2, (t + b) // 2)
        break
PY
)
    [ -n "$xy" ] && break
    sleep 2
  done
  [ -n "$xy" ] || { echo "text not found: $1"; return 1; }
  adb shell input tap $xy
  sleep 1
}

adb install -r "$APK" >/dev/null
API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
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
adb shell cmd notification allow_listener "$PKG/$PKG.android.GalleryNotificationListener" || true

adb logcat -c
adb shell am start -n "$PKG/.ui.MainActivity" --es e2e_passcode "shots-pass-123" >/dev/null
for i in $(seq 1 40); do
  adb logcat -d -s R3U_E2E | grep -q "LINK port" && break
  sleep 2
done
sleep 3
shot a1-running

tap_text "Features and passcode"
shot a2-features

# more switches than fit on screen: scroll down to the passcode button
adb shell input swipe 540 1900 540 500 300
sleep 1
tap_text "Change web passcode"
shot a3-passcode
tap_text "Cancel" || true

# notification shade with the ongoing "R3U | Phone Link is running" notification
adb shell cmd statusbar expand-notifications
shot a4-notification
adb shell cmd statusbar collapse
