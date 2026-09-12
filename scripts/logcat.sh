#!/usr/bin/env bash
# Shows only what matters: the module, LSPatch/LSPosed framework lines, and Java crashes.
# Usage: scripts/logcat.sh            (live)
#        scripts/logcat.sh dump       (dump current buffer to logcat-<timestamp>.txt and exit)
if [ "${1:-}" = "dump" ]; then
  f="logcat-$(date +%Y%m%d-%H%M%S).txt"
  adb logcat -d -v threadtime > "$f"
  echo "wrote $f ($(wc -l < "$f") lines). Module lines:"
  grep -E "StorytelSpeedMod|LSPatch|LSPosed|AndroidRuntime|FATAL" "$f" | tail -50
  exit 0
fi
adb logcat -v threadtime -s StorytelSpeedMod:* LSPatch:* LSPosed:* LSPosed-Bridge:* AndroidRuntime:E
