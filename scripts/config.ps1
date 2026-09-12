# Changes the module configuration inside the running (patched) Storytel process.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\config.ps1 "mode=remap;target=3.5;discovery=true"
#        ... scripts\config.ps1 show     |     ... scripts\config.ps1 reset
param([string]$Arg = "show", [string]$Pkg = "grit.storytel.app")
$action = "io.github.sisukah.storytelspeedmod.CONFIG"
switch ($Arg) {
  "show"  { adb shell am broadcast -a $action -p $Pkg --ez show true }
  "reset" { adb shell am broadcast -a $action -p $Pkg --ez reset true }
  default { adb shell am broadcast -a $action -p $Pkg --es set "'$Arg'" }
}
