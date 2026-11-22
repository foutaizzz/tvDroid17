#!/usr/bin/env bash
# Build the launcher + its instrumentation tests, install both on the TV, and run every
# instrumentation class, reporting PASS/FAIL per test.
#
#   TV_HOST=TV_IP ./run-tests.sh                 run everything
#   TV_HOST=TV_IP ./run-tests.sh FadePerformance only tests matching a name
#
# Works on Linux without Android Studio: it finds a Python (venv) and a JDK/SDK by itself.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=TV_IP $0}"
FILTER="${1:-}"

# Python: the workspace venv first (it carries the crypto module tv.py needs), then any python3.
. tools/pyenv.sh
export MSYS_NO_PATHCONV=1

# JDK and Android SDK for Gradle, without Android Studio.
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
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew assembleDebug assembleDebugAndroidTest -q --console=plain

APK=app/build/outputs/apk/debug/app-debug.apk
TESTAPK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$PY" -I tools/b64push.py "$APK" /data/local/tmp/tvdroid17.apk
"$PY" -I tools/b64push.py "$TESTAPK" /data/local/tmp/tvdroid17-test.apk

printf '%s\n' \
  "pm install -r /data/local/tmp/tvdroid17.apk" \
  "pm install -r /data/local/tmp/tvdroid17-test.apk" \
  "rm -f /data/local/tmp/tvdroid17.apk /data/local/tmp/tvdroid17-test.apk" \
  "pm grant com.tvdroid17 android.permission.ACCESS_FINE_LOCATION 2>/dev/null; pm grant com.tvdroid17 android.permission.ACCESS_COARSE_LOCATION 2>/dev/null; pm grant com.tvdroid17 android.permission.NEARBY_WIFI_DEVICES 2>/dev/null" \
  "pm grant com.tvdroid17.test android.permission.INJECT_EVENTS 2>/dev/null; echo test-perms-granted" \
  | "$PY" -I tools/tv.py --batch > /dev/null 2>&1 || true

# Discover every instrumentation class from the test sources.
CLASSES=$(cd app/src/androidTest/java/com/tvdroid17 && ls *Instrumentation.java 2>/dev/null | sed 's/\.java$//' | sort)
if [ -z "$CLASSES" ]; then
  echo "No instrumentation classes found in the test sources" >&2
  exit 1
fi

PASS=0; FAIL=0
for name in $CLASSES; do
  case "$name" in *"$FILTER"*) ;; *) [ -n "$FILTER" ] && continue ;; esac
  echo "== $name"
  # Instrumentation runs can take minutes; give the shell read a long timeout.
  out=$(printf '%s\n' "am instrument -w com.tvdroid17.test/com.tvdroid17.$name" \
        | TV_SHELL_TIMEOUT=600 "$PY" -I tools/tv.py --batch 2>&1 || true)
  # am instrument reports Activity.RESULT_OK (-1) as INSTRUMENTATION_CODE: -1 on success.
  if printf '%s' "$out" | grep -qE "INSTRUMENTATION_CODE: -1|verification=PASS"; then
    echo "PASS  $name"
    PASS=$((PASS+1))
  else
    echo "FAIL  $name"
    printf '%s\n' "$out" | grep -oE 'verification: [^|]*|failure: [^|]*|INSTRUMENTATION_RESULT: [^"]*' | head -3 | sed 's/^/        /' || true
    printf '%s\n' "$out" | tail -4 | sed 's/^/        | /' || true
    FAIL=$((FAIL+1))
  fi
done

# Leave the television in a clean state: home app, accessibility service, permissions, HOME key.
# The accessibility service (SourceKeyService) is what makes the remote's SET and INPUT keys work
# on this firmware; a package uninstall silently drops it, so always re-assert it and verify it is
# actually bound (retry a few times), then restore the screen timeout.
printf '%s\n' \
  "pm grant com.tvdroid17 android.permission.ACCESS_FINE_LOCATION 2>/dev/null; pm grant com.tvdroid17 android.permission.ACCESS_COARSE_LOCATION 2>/dev/null; pm grant com.tvdroid17 android.permission.NEARBY_WIFI_DEVICES 2>/dev/null" \
  "cmd package set-home-activity --user 0 com.tvdroid17/.MainActivity" \
  "am force-stop com.tvdroid17" \
  "c=0; for i in 1 2 3; do settings put secure enabled_accessibility_services com.tvdroid17/com.tvdroid17.SourceKeyService; settings put secure accessibility_enabled 1; sleep 2; c=\$(dumpsys accessibility | grep -c SourceKeyService); [ \"\$c\" -gt 0 ] && break; done; echo a11y-bound=\$c" \
  "input keyevent KEYCODE_HOME" \
  | "$PY" -I tools/tv.py --batch 2>&1 | grep -vE "RuntimeWarning|frozen site" || true

echo "----------------------------------------"
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
