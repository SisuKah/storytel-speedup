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
   With defaults (`mode=remap`, `target=3.0`) the app already behaves as: 0.5–1.75x unchanged,
   2x → 3x.
4. Enable discovery and confirm the path:

   ```bash
   scripts/config.sh "discovery=true"       # or: adb shell am broadcast -a io.github.sisukah.storytelspeedmod.CONFIG -p grit.storytel.app --es set "'discovery=true'"
   ```

   Change speed 1x → 1.5x → 2x in Storytel. Each tap must produce a `[SET]` block; the 2x tap must
   show `in=2.00x … -> out=3.00x (remap 2.00 -> 3.00 …)`. Listen: it should audibly be faster than 2x.
5. Test 2x remapping at each preset, one at a time, listening for at least a chapter:

   ```bash
   scripts/config.sh "target=2.5"     # tap 2x in Storytel (or tap another speed, then 2x, to re-trigger)
   scripts/config.sh "target=3.0"
   scripts/config.sh "target=3.5"
   scripts/config.sh "target=4.0"
   ```

   Because the funnel ignores unchanged values, re-select 2x (via another speed) after changing
   the target so the new value is applied. Watch for: stutter/underruns (CPU), position jumps
   after seeks, and whether Storytel's UI fights the value (repeated `[SET] in=2.00x` lines without
   you tapping = Storytel re-applying; the hook remaps them again, which is harmless but tells you
   Storytel syncs speed from its own state).
6. Optional force mode: `scripts/config.sh "mode=force;target=3.0"` makes every picker entry 3x.
   Return with `scripts/config.sh "mode=remap"`.
7. Turn discovery off for daily use: `scripts/config.sh "discovery=false"`.

## Useful adb one-liners

```bash
adb shell pidof grit.storytel.app                                  # is it running / which pid
adb shell am force-stop grit.storytel.app                          # restart to apply cls_*/ui_* overrides
adb shell run-as grit.storytel.app cat files/storytel_speed_mod.properties   # only works if the patched app is debuggable (-d)
adb logcat -d -s StorytelSpeedMod > speedmod.txt                   # dump to file
adb shell dumpsys media_session | grep -A3 -i storytel             # what the system sees as playback speed (MediaSession PlaybackState)
```
