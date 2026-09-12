# Pulls the exact Storytel APK set installed on the connected phone into .\storytel-apk\<versionName>\
# Usage: powershell -ExecutionPolicy Bypass -File scripts\pull-storytel-apk.ps1 [package]
param([string]$Pkg = "grit.storytel.app")
$ErrorActionPreference = "Stop"

Write-Host "== package check"
adb shell pm list packages | Select-String -Pattern "storytel"

$dump = adb shell dumpsys package $Pkg
$version = ($dump | Select-String -Pattern "versionName=" | Select-Object -First 1).ToString() -replace ".*versionName=", ""
$code = ($dump | Select-String -Pattern "versionCode=" | Select-Object -First 1).ToString() -replace ".*versionCode=(\d+).*", '$1'
$out = "storytel-apk\$($version.Trim())-$($code.Trim())"
New-Item -ItemType Directory -Force -Path $out | Out-Null
Write-Host "== $Pkg versionName=$version versionCode=$code -> $out"

$paths = (adb shell pm path $Pkg) -replace "^package:", ""
$paths | Out-File -Encoding ascii "$out\paths.txt"
foreach ($p in $paths) {
  $p = $p.Trim()
  if ($p -eq "") { continue }
  Write-Host "== pulling $p"
  adb pull $p "$out\"
}
Write-Host "Done. Files in $out :"
Get-ChildItem $out
Write-Host "Keep this folder: it is your way back to the official app without Google Play (adb install-multiple <files>)."
