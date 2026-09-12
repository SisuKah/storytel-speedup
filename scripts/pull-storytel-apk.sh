#!/usr/bin/env bash
# Pulls the exact Storytel APK set installed on the connected phone into ./storytel-apk/<versionName>/
# Usage: scripts/pull-storytel-apk.sh [package]   (default grit.storytel.app)
set -euo pipefail
PKG="${1:-grit.storytel.app}"

echo "== package check"
adb shell pm list packages | grep -i storytel || { echo "no Storytel package found; is USB debugging on?"; exit 1; }

VERSION="$(adb shell dumpsys package "$PKG" | tr -d '\r' | grep -m1 'versionName=' | sed 's/.*versionName=//')"
CODE="$(adb shell dumpsys package "$PKG" | tr -d '\r' | grep -m1 'versionCode=' | sed 's/.*versionCode=\([0-9]*\).*/\1/')"
OUT="storytel-apk/${VERSION:-unknown}-${CODE:-0}"
mkdir -p "$OUT"
echo "== $PKG versionName=$VERSION versionCode=$CODE -> $OUT"

echo "== installed APK paths"
adb shell pm path "$PKG" | tr -d '\r' | sed 's/^package://' | tee "$OUT/paths.txt"

while read -r p; do
  [ -z "$p" ] && continue
  echo "== pulling $p"
  adb pull "$p" "$OUT/"
done < "$OUT/paths.txt"

echo
echo "Done. Files in $OUT:"
ls -la "$OUT"
echo
echo "Keep this folder: it is your way back to the official app without Google Play"
echo "(adb install-multiple $OUT/*.apk)."
