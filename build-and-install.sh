#!/usr/bin/env bash
# Build the launcher APK and install it on the TV as the default home screen.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=TV_IP $0}"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.tvlauncher}"
# Find a JDK and an Android SDK without Android Studio (Linux first, then macOS/Windows).
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in ~/android-tools/jdk /usr/lib/jvm/* /opt/homebrew/opt/openjdk@17; do
    if [ -x "$candidate/bin/java" ]; then JAVA_HOME="$candidate"; break; fi
  done
fi
: "${JAVA_HOME:?no JDK found; set JAVA_HOME}"
export PATH="$JAVA_HOME/bin:$PATH"
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  for candidate in ~/android-tools/sdk ~/Android/Sdk ~/Library/Android/sdk; do
    if [ -d "$candidate/platforms" ]; then ANDROID_HOME="$candidate"; break; fi
  done
fi
: "${ANDROID_HOME:?no Android SDK found; set ANDROID_HOME}"
export ANDROID_HOME ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
# local.properties points at this machine's SDK, so always rewrite it: a copy carried over from
# another machine (e.g. a Windows path) would point nowhere and break the build.
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug -q --console=plain
# Picks a Python that can import the ADB modules (workspace venv first) and explains how to
# install them if none can.
. tools/pyenv.sh
export MSYS_NO_PATHCONV=1
APK=app/build/outputs/apk/debug/app-debug.apk
"$PY" -I tools/b64push.py "$APK" /data/local/tmp/tvhome.apk

# The TMDB read token is not compiled into the APK: it is copied onto the television, where the
# launcher reads it at startup. Keep it outside the project, in ~/.config/tvhome/tmdb-token.
TOKEN_FILE="${TMDB_TOKEN_FILE:-$HOME/.config/tvhome/tmdb-token}"
TOKEN=""
if [ -f "$TOKEN_FILE" ]; then
  TOKEN="$(tr -d '\r\n' < "$TOKEN_FILE")"
fi
if [ -z "$TOKEN" ]; then
  TOKEN="${TMDB_READ_ACCESS_TOKEN:-}"
fi
if [ -z "$TOKEN" ]; then
  echo "No TMDB token found at $TOKEN_FILE: trailers and French summaries will stay off."
  echo "Put the token in that file (one line, no quotes) to enable them."
fi

printf '%s\n' \
  "pm install -r /data/local/tmp/tvhome.apk" \
  "rm -f /data/local/tmp/tvhome.apk" \
  "pm grant com.tvdroid17 android.permission.ACCESS_FINE_LOCATION 2>/dev/null; pm grant com.tvdroid17 android.permission.ACCESS_COARSE_LOCATION 2>/dev/null; pm grant com.tvdroid17 android.permission.NEARBY_WIFI_DEVICES 2>/dev/null; echo wifi-granted" \
  "pm grant com.tvdroid17 android.permission.WRITE_SECURE_SETTINGS 2>/dev/null; echo picture-settings-granted" \
  "settings put secure enabled_accessibility_services com.tvdroid17/com.tvdroid17.SourceKeyService" \
  "settings put secure accessibility_enabled 1" \
  "cmd package set-home-activity --user 0 com.tvdroid17/.MainActivity" \
  "pm disable-user --user 0 $STOCK_LAUNCHER" \
  "am start -n com.tvdroid17/.MainActivity >/dev/null 2>&1 || true" \
  | "$PY" -I tools/tv.py --batch

if [ -n "$TOKEN" ]; then
  # Written into the app's own files, which survive an upgrade; the launcher reads it on start.
  TOKEN_B64="$(printf '%s' "$TOKEN" | base64 | tr -d '\n')"
  printf '%s\n' "echo '$TOKEN_B64' | base64 -d | run-as com.tvdroid17 sh -c 'cat > files/tmdb-token.txt'" \
    | "$PY" -I tools/tv.py --quiet-batch
fi
echo "Installed. Press Home on the remote to see it."
