#!/usr/bin/env bash
# Patches a Storytel APK set with LSPatch (CLI) and embeds the module.
# Usage: scripts/patch.sh <lspatch.jar> <module.apk> <sigbypass 0|1|2|3> <base.apk> [split_*.apk ...]
# Output goes to ./patched/
set -euo pipefail
JAR="$1"; MODULE="$2"; LEVEL="$3"; shift 3
mkdir -p patched
echo "== lspatch options for this jar (verify the flags exist in your version):"
java -jar "$JAR" --help 2>&1 | grep -E "^\s+-" || true
echo
set -x
java -jar "$JAR" -o patched -f -l "$LEVEL" -m "$MODULE" "$@"
set +x
echo
echo "Patched files:"
ls -la patched
echo
echo "Install with:  adb install-multiple patched/*.apk      (single APK: adb install patched/<file>.apk)"
