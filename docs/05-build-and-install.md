# Phase 8 — Build, patch, install, test

## Build

Android Studio: open the project, wait for sync, then
Build > Build Bundle(s)/APK(s) > Build APK(s). Studio builds the *selected* module; to be sure,
use the Gradle tool window (probe > Tasks > build > assembleRelease) or the terminal:

```bash
./gradlew :probe:assembleRelease    # probe/build/outputs/apk/release/probe-release.apk
./gradlew :app:assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew :app:testReleaseUnitTest  # optional: 16 JVM tests for the policy/config code
# Windows: gradlew.bat :app:assembleRelease
```

Both `release` APKs are signed with the debug keystore on purpose (personal use); they are valid,
installable APKs.

## Patch

```text
Storytel APK (base + splits)
        +
module APK (app-release.apk)
        +
LSPatch (lspatch.jar or LSPatch Manager)
        ↓
patched Storytel APK set (re-signed with LSPatch's key, module embedded)
```

```bash
# same command as in Phase 1, module swapped in; use the sigbypass level that passed Phase 1
java -jar lspatch.jar -o patched -f -l <0|1|2|3> -m app/build/outputs/apk/release/app-release.apk storytel-apk/*.apk
# helper: scripts/patch.sh lspatch.jar app/build/outputs/apk/release/app-release.apk <level> storytel-apk/*.apk
```

Manager route: install `app-release.apk` on the phone (so the manager can list it and so you get
the config screen), LSPatch Manager > Manage > + > Storytel > Integrated/Local > Embedded modules:
*Storytel Speed Mod* > same signature bypass level as Phase 1 > Patch > Save/Install.

Relevant options recap: `-m` embed; `-l` signature bypass; `-k` your own keystore (optional,
otherwise LSPatch's built-in key, identical every run); `--manager` (do not combine with `-m`).

## Install

```bash
# only needed the first time you switch from the official/probe build to a build signed by a different key.
# Phase 1 probe -> this module: same LSPatch key, so it installs OVER the probe build (data kept).
adb uninstall grit.storytel.app                 # ONLY if the install below reports INSTALL_FAILED_UPDATE_INCOMPATIBLE

adb install-multiple patched/*.apk              # split set;   single APK: adb install patched/<name>.apk
adb install app/build/outputs/apk/release/app-release.apk    # optional: the config screen app
```

Then:

1. Launch Storytel, log in again if the data directory was wiped.
2. Confirm the module loaded (start logcat first, then launch):

   ```bash
   adb logcat -c
   adb logcat -s StorytelSpeedMod          # Windows alt: adb logcat | findstr StorytelSpeedMod
   ```

   Expect `LOADED: package=grit.storytel.app …`, then `effective config:`, then the
   `Media3 target resolution:` block ending in `usable : true`, then `hooked [funnel] …`.
3. Test normal playback exactly as in Phase 1 (play, seek, pause, notification controls, lock screen).
   With defaults (`mode=ladder`) the app already behaves as: 0.5/0.75/1x unchanged,
   1.25 → 2.5x, 1.5 → 3x, 1.75 → 3.5x, 2 → 4x — all chosen inside Storytel.
4. Enable discovery and confirm the path:

   ```bash
   scripts/config.sh "discovery=true"       # or: adb shell am broadcast -a io.github.sisukah.storytelspeedmod.CONFIG -p grit.storytel.app --es set "'discovery=true'"
   ```

   Change speed in Storytel. Each tap produces an event; on an obfuscated build it is a `[CTOR]`
   line, on a clean build a `[SET]` line. Either way `in=` is the button you tapped and `out=` is
   what Media3 receives.
5. Test each rung, one at a time, listening for at least a few minutes:

   In ladder mode there is nothing to set: tap 1.25x, then 1.5x, 1.75x and 2x in Storytel and
   listen. They should play at 2.5x, 3x, 3.5x and 4x. The diagnostics reply lists each one as a
   `[CTOR] ... in=1.50x -> out=3.00x (ladder ...)` event.

   A speed only changes when Storytel builds a new value, so after editing the ladder, tap a
   different speed and then the one you want. Watch for: stutter/underruns (CPU), position jumps
   after seeks, and whether Storytel's UI fights the value (repeated events you did not trigger
   mean Storytel re-applies its own state; the hook maps those too, which is harmless).
6. Tune the ladder if a rung is too fast, e.g. a gentler mapping:
   `scripts/config.sh "ladder=1.25:2.0,1.5:2.5,1.75:3.0,2.0:3.5"`. The other modes are still
   available: `scripts/config.sh "mode=force;target=3.0"`, back with `scripts/config.sh "mode=ladder"`.
7. Turn discovery off for daily use: `scripts/config.sh "discovery=false"`.

## Useful adb one-liners

```bash
adb shell pidof grit.storytel.app                                  # is it running / which pid
adb shell am force-stop grit.storytel.app                          # restart to apply cls_*/ui_* overrides
adb shell run-as grit.storytel.app cat files/storytel_speed_mod.properties   # only works if the patched app is debuggable (-d)
adb logcat -d -s StorytelSpeedMod > speedmod.txt                   # dump to file
adb shell dumpsys media_session | grep -A3 -i storytel             # what the system sees as playback speed (MediaSession PlaybackState)
```
