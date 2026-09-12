# Shows only what matters: the module, LSPatch/LSPosed framework lines, and Java crashes.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\logcat.ps1 [dump]
param([string]$Mode = "")
if ($Mode -eq "dump") {
  $f = "logcat-$(Get-Date -Format yyyyMMdd-HHmmss).txt"
  adb logcat -d -v threadtime | Out-File -Encoding utf8 $f
  Write-Host "wrote $f. Module lines:"
  Select-String -Path $f -Pattern "StorytelSpeedMod|LSPatch|LSPosed|AndroidRuntime|FATAL" | Select-Object -Last 50
  exit 0
}
adb logcat -v threadtime -s StorytelSpeedMod:* LSPatch:* LSPosed:* LSPosed-Bridge:* AndroidRuntime:E
