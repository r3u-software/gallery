#!/usr/bin/env bash
# Installs the Android command-line SDK and generates the Gradle wrapper (./gradlew).
set -euo pipefail

SDK_ROOT="${ANDROID_HOME:-/usr/local/android-sdk}"
CMDLINE_ZIP="commandlinetools-linux-11076708_latest.zip"

if [ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo ">> Installing Android command-line tools into $SDK_ROOT"
  sudo mkdir -p "$SDK_ROOT"
  sudo chown -R "$(whoami)" "$SDK_ROOT"
  tmp="$(mktemp -d)"
  curl -fsSL "https://dl.google.com/android/repository/$CMDLINE_ZIP" -o "$tmp/cmdline.zip"
  unzip -q "$tmp/cmdline.zip" -d "$tmp"
  mkdir -p "$SDK_ROOT/cmdline-tools"
  mv "$tmp/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  rm -rf "$tmp"
fi

export PATH="$PATH:$SDK_ROOT/cmdline-tools/latest/bin"
yes | sdkmanager --licenses >/dev/null || true
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

echo "sdk.dir=$SDK_ROOT" > local.properties

if [ ! -f gradlew ]; then
  echo ">> Generating Gradle wrapper"
  gradle wrapper --gradle-version 8.11.1
  echo ">> Commit gradlew, gradlew.bat and gradle/wrapper/ so CI can reuse them."
fi

echo ">> Ready. Try: ./gradlew testDebugUnitTest assembleDebug"
