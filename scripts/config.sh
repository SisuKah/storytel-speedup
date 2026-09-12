#!/usr/bin/env bash
# Changes the module configuration inside the running (patched) Storytel process.
# Usage: scripts/config.sh "mode=remap;target=3.5;discovery=true"
#        scripts/config.sh show
#        scripts/config.sh reset
# Storytel must be running. The reply (effective config) is printed by `am broadcast` as data="...".
set -euo pipefail
PKG="${PKG:-grit.storytel.app}"
ACTION="io.github.sisukah.storytelspeedmod.CONFIG"
case "${1:-show}" in
  show)  adb shell am broadcast -a "$ACTION" -p "$PKG" --ez show true ;;
  reset) adb shell am broadcast -a "$ACTION" -p "$PKG" --ez reset true ;;
  *)     adb shell am broadcast -a "$ACTION" -p "$PKG" --es set "'$1'" ;;
esac
