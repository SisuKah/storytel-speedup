# Phase 1 — Prove LSPatch compatibility with an empty module

**Do not touch playback until this phase passes.** The probe module (`:probe`) installs no hooks.
It only prints one line to logcat when LSPatch loads it inside Storytel. If Storytel launches,
logs in, browses, and plays an audiobook with the probe embedded, the risky part (re-signed APK,
LSPatch loader, module loading) is proven and everything after this is ordinary hook work.

## What you need

| Item | Notes |
|------|-------|
| PC with Java 17+ and `adb` | `adb` comes with Android platform-tools; Android Studio installs it too (`<sdk>/platform-tools`). |
| Phone: Developer options, USB debugging | Settings > About phone > Software information > tap *Build number* 7x; then Developer options > USB debugging. |
| Samsung: Auto Blocker OFF | Settings > Security and privacy > Auto Blocker. While it is on, One UI 6+ silently refuses ADB installs and sideloads. Turn it back on afterwards if you like; installed apps keep working. |
| LSPatch | See "Which LSPatch" below. |
| The probe APK | `./gradlew :probe:assembleRelease` -> `probe/build/outputs/apk/release/probe-release.apk` |

Confirm the phone is visible before anything else:

```bash
adb devices            # must list the phone as "device", not "unauthorized"
```

## Which LSPatch

- **Official `LSPosed/LSPatch`**: last release is v0.6 (2023). The project is unmaintained and has
  known problems on Android 14 and newer.
- **`JingMatrix/LSPatch`** (fork, actively maintained, built on the same author's "Vector" fork
  of LSPosed): supports Android 9 up to the current beta, supports classic
  `de.robv.android.xposed` modules (what this project uses) and adds signature bypass level 3.
  Each release ships `manager.apk` and `lspatch.jar`.

For a Samsung on Android 14+ use the JingMatrix fork. Download `lspatch.jar` (PC route) and,
optionally, `manager.apk` (on-device route) from its GitHub releases page. Everything below shows
the PC/CLI route because it is scriptable and produces files you can keep; the manager route is
described where it differs.

LSPatch has two patch modes. This project uses **local/integrated mode** (module embedded into
the Storytel APK; no manager needed on the phone afterwards). *Manager mode* keeps the module
separate so it can be updated without re-patching Storytel, but the patched app then depends on
the manager being installed. Do not mix the two: they are re-signed with different keys.

## Step 1 — obtain the exact Storytel APK installed on your phone

```bash
adb shell pm list packages | grep -i storytel      # expected: package:grit.storytel.app
adb shell dumpsys package grit.storytel.app | grep -E "versionName|versionCode"
adb shell pm path grit.storytel.app
```

Typical `pm path` output for a Play Store install (an app bundle, delivered as splits):

```text
package:/data/app/~~Ab12.../grit.storytel.app-Cd34.../base.apk
package:/data/app/~~Ab12.../grit.storytel.app-Cd34.../split_config.arm64_v8a.apk
package:/data/app/~~Ab12.../grit.storytel.app-Cd34.../split_config.en.apk
package:/data/app/~~Ab12.../grit.storytel.app-Cd34.../split_config.xxhdpi.apk
```

Pull every file (the helper does exactly this and names the folder after the version):

```bash
scripts/pull-storytel-apk.sh                    # Windows: powershell -ExecutionPolicy Bypass -File scripts\pull-storytel-apk.ps1
# or by hand:
adb pull /data/app/~~Ab12.../grit.storytel.app-Cd34.../base.apk storytel-apk/
adb pull /data/app/~~Ab12.../grit.storytel.app-Cd34.../split_config.arm64_v8a.apk storytel-apk/
# ... one adb pull per line printed by pm path
```

**Keep this folder.** It is your backup: `adb install-multiple storytel-apk/*.apk` restores the
official, Play-signed app without Google Play.

Alternative: download the same `versionName` from a reputable archive (APKMirror). Prefer a
"universal"/"nodpi" single APK if one is offered. A `.apkm`, `.apks` or `.xapk` file is just a ZIP
containing `base.apk` + `split_*.apk`; unzip it and treat the contents like the pulled set.

## Step 2 — split APKs: do they need special handling?

Yes, a little. Only `base.apk` contains the manifest and (normally) the code. LSPatch injects
its loader into the APK that carries the `<application>` element and must re-sign **every** split
with the same key, otherwise Android refuses the set.

| Situation | What to do |
|-----------|-----------|
| Single universal APK | Patch it; install with `adb install`. Simplest. |
| Split set, **JingMatrix CLI** | Pass all files in one command (verified in its source: a multi-APK set is patched together, `split_*.apk` files are repacked and re-signed). Install with `adb install-multiple`. |
| Split set, **LSPatch Manager on the phone** | "Patch" from the installed app; the manager processes the whole set and installs it. |
| Split set, **official v0.6 CLI** | It treats each APK independently. Merge the splits into one APK first (below), then patch that. |
| Merging splits | `java -jar APKEditor.jar m -i <folder-with-base-and-splits> -o storytel-merged.apk` (REAndroid/APKEditor). If the merged app later shows "Missing split"/`isSplitRequired`, the merge tool did not clear the Play split metadata; APKEditor normally does. |

## Step 3 — patch the APK with the empty module

```bash
# first attempt: no signature bypass, i.e. the purest test
java -jar lspatch.jar -o patched -f -l 0 -m probe/build/outputs/apk/release/probe-release.apk \
     storytel-apk/base.apk storytel-apk/split_config.arm64_v8a.apk storytel-apk/split_config.en.apk storytel-apk/split_config.xxhdpi.apk
# helper doing the same:  scripts/patch.sh lspatch.jar probe/build/outputs/apk/release/probe-release.apk 0 storytel-apk/*.apk
```

Flags (verified against the CLI source; run `java -jar lspatch.jar --help` for your version):

| Flag | Meaning |
|------|---------|
| `-o patched` | output directory |
| `-f` | overwrite existing output |
| `-m <module.apk>` | embed the module (local/integrated mode) |
| `-l 0/1/2/3` | signature bypass level, see "Compatibility failure" below (level 3 exists only in the fork) |
| `-d` | make the app debuggable (not needed) |
| `--manager` | manager mode instead of embedding (cannot be combined with `-m`) |
| `-k <ks> <pw> <alias> <alias-pw>` | your own signing key. Without it LSPatch uses its built-in key, which is the same every run, so later re-patches install *over* the previous patched build and keep app data. |

Manager route: install `manager.apk` and the probe APK on the phone, open LSPatch Manager >
Manage > "+" > pick Storytel from installed apps (or select the APK files) > Patch mode
"Integrated/Local" > Embedded modules: select the probe > Signature bypass level > Start patch.
The manager then offers to install; Android will refuse the install until the original is gone
(next step), so use "Save" first, uninstall Storytel, then install.

## Step 4 — why the patched APK cannot simply replace the Play install

Android only allows an update whose signing certificate matches the installed app. The patched
APK is signed by LSPatch's key, not Storytel's. `adb install` therefore fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` (on-device: "App not installed as package conflicts with an
existing package"). The only way past this is to uninstall the original first, which wipes its
private data.

## Step 5 — back up what you can

- The pulled APK set (Step 1) — your way back to the official app.
- Your Storytel login (email/password or the SSO you use). You will log in again.
- Listening positions and bookshelf are stored on Storytel's servers and come back after login.
- Offline downloads are lost and must be re-downloaded (they are bound to the app data anyway).
- `adb backup` is not an option: it is deprecated and only works for apps that allow backup.

## Step 6 — uninstall the original

```bash
adb uninstall grit.storytel.app
# if Android says it is a system/installed-for-all-users app: adb shell pm uninstall --user 0 grit.storytel.app
```

## Step 7 — install the patched build

```bash
adb install-multiple patched/*.apk        # split set
adb install patched/storytel-merged-lspatched.apk   # single APK (file name will differ)
```

If the install is refused on Samsung, check Auto Blocker (see table above). If you install from
the phone instead of ADB, grant "Install unknown apps" to the file manager/browser you use.

## Steps 8–10 — launch, log in, play

1. Open Storytel. Expect a first-start experience (fresh data).
2. Log in.
3. Browse, open a book, play for at least a minute, seek, change speed between 1x/1.5x/2x, pause
   and resume, lock the screen, use the notification controls. This is the baseline you will
   compare against later.

## Step 11 — verify the module loaded

Start logcat **before** launching Storytel (the line is printed once, early):

```bash
adb logcat -c                       # clear the buffer
adb logcat -s StorytelSpeedMod      # works on Linux/macOS/Windows
# Windows alternative: adb logcat | findstr StorytelSpeedMod
# Both frameworks + crashes in one view: scripts/logcat.sh   (or scripts\logcat.ps1)
```

Then launch Storytel. Expected:

```text
I StorytelSpeedMod: PROBE LOADED: package=grit.storytel.app process=grit.storytel.app pid=12345 android=34 xposedApi=93 device=samsung SM-S9xx
```

You may see the line more than once if Storytel runs a second process (e.g. a playback service
process); that is normal and each process name is printed. LSPatch's own loader messages appear
under tags such as `LSPatch` / `LSPosed` / `LSPosed-Bridge` (exact tag names differ between
versions; the `XposedBridge.log` copy of the probe line goes through that channel).

## Updates from now on

The patched app is no longer updated by Google Play (different signature). Every Storytel update
means: obtain the new APK set, patch it again with the module, and `adb install(-multiple)` it
over the current patched build. Because LSPatch re-signs with the same key each time, that is a
normal in-place update and keeps login and downloads. Do not let the Play Store "update" the app:
it cannot, but it may nag; the official app can only come back by uninstalling the patched one.

## Compatibility failure — stop and diagnose

If Storytel crashes, refuses login, refuses playback, shows a "modified app"/integrity message,
or behaves differently, do **not** continue to hooks. Collect evidence first:

```bash
adb logcat -c
# reproduce the problem, then:
adb logcat -d -v threadtime > full.txt                 # everything, with pid/tid/timestamps
adb logcat -d -b crash > crash.txt                     # only the crash buffer (Java crashes, native tombstone summaries)
adb logcat -d -v threadtime --pid=$(adb shell pidof -s grit.storytel.app) > storytel-only.txt   # while the process is alive
adb shell dumpsys dropbox --print data_app_crash | tail -200        # last stored crash reports
adb shell dumpsys package grit.storytel.app | grep -E "versionName|signatures|pkgFlags"
```

Then read `full.txt`:

| Symptom in log | Where it points |
|----------------|-----------------|
| `FATAL EXCEPTION` with `org.lsposed`/`LSPatch` frames on top | LSPatch loader itself failed on this Android build. Try the other LSPatch (fork vs official), or a different sigbypass level; check the LSPatch issue tracker for your Android version. |
| `FATAL EXCEPTION` with only Storytel frames, right after start | Usually the app's own integrity check reacting to the changed signature. Try sigbypass level 1, then 2 (3 as last resort). |
| `ClassNotFoundException`/`NoClassDefFoundError` involving the probe class | Module packaging problem (`xposed_init` name, minified class). Rebuild the probe. |
| Login screen error, network calls return 4xx, tags mention "integrity", "attest", "safetynet", "play integrity" | Server-side attestation (Play Integrity). Cannot be solved by LSPatch; stop here. |
| Login works, playback fails, DRM/`MediaDrm`/`Widevine`/license errors | The license path may depend on app identity/signature. Try sigbypass 2/3; if it persists, stop here. |
| No `PROBE LOADED` line, app fine | Module not embedded/loaded, not a compatibility problem. Check the patch command output listed the module, check `assets/xposed_init` inside the module APK. |

### LSPatch's signature handling

LSPatch's `-l / --sigbypasslv` option exists precisely for apps that reject a re-signed APK:

| Level | What LSPatch does |
|-------|-------------------|
| 0 | nothing; the app sees LSPatch's certificate |
| 1 | hooks `PackageManager` so that the app's own signature queries return the **original** Storytel certificate |
| 2 | level 1 + hooks `openat` so reading the app's own APK file (file-based verification) returns the original APK |
| 3 (fork only) | level 2 + raw-syscall reads; patches native code, so an app that verifies its own code could notice. Prefer 2 unless it is not enough. |

Testing procedure: keep everything else identical and re-patch with the next level. Each attempt
requires uninstall + install + login again:

```bash
java -jar lspatch.jar -o patched-l2 -f -l 2 -m probe/build/outputs/apk/release/probe-release.apk storytel-apk/*.apk
adb uninstall grit.storytel.app && adb install-multiple patched-l2/*.apk
```

Neither level helps against server-side attestation (Play Integrity) because the verdict is
computed by Google's servers from the device and the app's *installed* signature.

**Gate for Phase 2+:** with the probe embedded Storytel must launch, authenticate, browse, and
play an audiobook normally, and `PROBE LOADED` must be in the log. Only then continue.
