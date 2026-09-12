# Phase 9 — Troubleshooting decision tree

Always start with the same two commands, in this order, then reproduce:

```bash
adb logcat -c
adb logcat -v threadtime -s StorytelSpeedMod:* LSPatch:* LSPosed:* LSPosed-Bridge:* AndroidRuntime:E     # scripts/logcat.sh
```

Windows: identical command, or `adb logcat | findstr /C:"StorytelSpeedMod" /C:"AndroidRuntime" /C:"LSPatch"`.

---

## 1. Module does not load (no `LOADED:` / `PROBE LOADED` line)

```text
Is there ANY LSPatch/LSPosed line at Storytel start?
├── no  → LSPatch loader not active
│        • wrong APK installed? adb shell dumpsys package grit.storytel.app | grep -E "versionName|signatures"
│          (a Play-signed build means the official app is installed, not the patched one)
│        • patch output: did lspatch print "Embedding modules" / the module name? Re-run with -v.
│        • Manager mode patched but manager not installed/running → re-patch in local mode with -m.
└── yes → loader ran; module not picked up
         • assets/xposed_init inside the module APK:  unzip -p app-release.apk assets/xposed_init
           must print exactly io.github.sisukah.storytelspeedmod.MainHook (no BOM, one line)
         • manifest meta-data: aapt dump xmltree app-release.apk AndroidManifest.xml | grep xposed
           needs xposedmodule=true and xposedminversion
         • xposedminversion higher than the framework reports (93) → lower it
         • minified build renamed MainHook → keep isMinifyEnabled=false or fix proguard-rules.pro
         • wrong module APK passed to -m (e.g. probe instead of app) → check the file name/size
         • LSPosed only: scope not enabled for grit.storytel.app; LSPatch: scope is irrelevant
         • package name: EXPECTED_PACKAGES only affects a warning; the module hooks any package it is loaded in
         • the LSPatch log itself: LSPatch Manager > Logs (fork) or logcat tag LSPatch for "module … failed"
```

## 2. Hook loads but never fires (no `[SET]`/`[ENTRY]` when changing speed)

Read the `Media3 target resolution:` block first.

```text
PlaybackParameters: NOT FOUND
└── First the module now RETRIES automatically with a structural scan at app start (it finds
    PlaybackParameters by shape, not by name, and substitutes at its constructor). Re-check the
    diagnostics: after the retry, usable should become true and PlaybackParameters should show a
    (renamed) class name. Only if the scan reports "could not read dex entries" or "no
    PlaybackParameters-shaped class" do you fall back to finding the names via strings
    ("AndroidXMedia3/", "ExoPlayerImpl", 0.1f/8.0f clamp, Math.round(f*1000.0f)) and set
    cls_playback_parameters / cls_player_impl (+ cls_base_player), then force-stop Storytel.
    Also possible: Storytel does not use Media3/ExoPlayer at all (e.g. a native player or
    MediaPlayer) → search JADX for "MediaPlayer", "setPlaybackParams", "PlaybackParams", "AudioTrack".

PlaybackParameters found, funnel method NOT FOUND, note says "ambiguous structural match"
└── the impl class has several (PlaybackParameters)->void methods in this Media3 version → read the
    candidates listed in the note, open them in JADX, set m_set_playback_parameters=<name>.

funnel hooked, but nothing logged when tapping speed
├── discovery=false? → scripts/config.sh "discovery=true" (the reply must show discovery=true)
├── a different process? Storytel may run playback in a second process. Every process logs its
│   own LOADED line with process=…; the [SET] lines appear in the process that owns the player.
│   Filter with: adb logcat -s StorytelSpeedMod | grep -E "process=|\[SET\]"
├── another player path: [PLAYER-CREATED] never appears → Storytel did not construct ExoPlayerImpl:
│     • SimpleExoPlayer? (wraps ExoPlayerImpl → would still appear) • CastPlayer? • a custom
│       SimpleBasePlayer subclass? • MediaPlayer? → JADX: who implements/extends Player.
├── Media3 version differences: Media3 < 1.0 is com.google.android.exoplayer2 (handled); very old
│   ExoPlayer set speed by constructing PlaybackParameters → switch hook_point=ctor and
│   discovery_ctor=true to see constructions.
├── overloads: the funnel hook is exact-signature; a build with e.g. setPlaybackParameters(PP, boolean)
│   would show in the "ambiguous"/"no structural match" notes → give m_* override or paste the notes back.
├── interface vs implementation: hooking Player (interface) is impossible; the module hooks the
│   concrete ExoPlayerImpl/BasePlayer. If Storytel wraps the player in its own ForwardingPlayer,
│   the [ENTRY] hooks show it (this=<Storytel class>) but the funnel still fires below it.
├── classloader: "Media3 targets not usable at handleLoadPackage" followed by success at
│   Application.onCreate is normal for feature-split apps. If both fail, the classes are in a
│   classloader created even later (dynamic feature) → paste the log; a lazy retry on
│   first Activity would be the next step.
└── R8 inlined the call: BasePlayer.setPlaybackSpeed is final and tiny; R8 can inline it into
    Storytel's caller. Then only [SET] appears (never [ENTRY]) → expected, harmless.
```

## 3. Storytel crashes immediately

```bash
adb logcat -d -b crash > crash.txt      # Java stack traces of the crash
adb logcat -d -v threadtime > full.txt  # context before the crash
grep -n -B2 -A40 "FATAL EXCEPTION" full.txt | head -120
```

Locate the crash phase from the topmost non-framework frames of the `FATAL EXCEPTION` trace:

| Top frames contain | Phase | What it means / do |
|--------------------|-------|---------------------|
| `org.lsposed`, `LSPatch`, `lspatch` | LSPatch bootstrap | LSPatch itself failed on this Android build. Different LSPatch (fork ↔ official), different sigbypass level, check LSPatch issues. Not this module. |
| `io.github.sisukah.storytelspeedmod.MainHook.handleLoadPackage` | module initialisation | a bug in the module's startup; the log line just before names it. Everything there is wrapped in try/catch, so this should not happen; paste the trace back. |
| `Class.forName` / `ClassNotFoundException` / `NoClassDefFoundError` from `Media3Targets` | class lookup | a `cls_*` override names a class that does not exist, or a class that needs another missing class. The lookups are non-initialising and caught; a crash here means a static initialiser threw; paste the trace. |
| `XposedBridge.hookMethod` / `XposedHelpers.findAndHookMethod` in the trace | method hooking | hooking failed (abstract/native/inlined method). Paste the trace; the hook target must change. |
| `ExoPlayerImpl.<init>` / `ExoPlayer$Builder.build` | player creation | crash while Storytel builds the player; if `Media3Hooks` frames are present the constructor hook logging threw (paste), otherwise unrelated to the module (compare with the probe build). |
| `ExoPlayerImpl.setPlaybackParameters` / `PlaybackParameters.<init>` / `checkArgument` | playback | an invalid value reached Media3 (speed must be > 0, finite). The policy only produces values in `[0.1, max_speed]`; if you see this, paste the `[SET]` line preceding the crash. |
| only Storytel frames, happens with the probe too | app integrity / other | Phase 1 problem (signature); see docs/01. |

Quick isolation: re-patch with the **probe** instead of the module. If the crash persists, it is
LSPatch/signature related; if it disappears, it is the module (paste the trace).

## 4. Storytel launches but login fails

First establish whether the failure is client-side or server-side:

```bash
adb logcat -d -v threadtime > login.txt
grep -n -i -E "okhttp|retrofit|http|401|403|integrity|attest|signature|tamper|root|safetynet|device|drm" login.txt | head -80
```

| Evidence | Likely cause | Test |
|----------|--------------|------|
| No requests leave the device, `UnknownHostException`, `SSLHandshakeException`, timeouts | network/VPN/DNS | same Wi-Fi works in a browser? mobile data? |
| `401`/`403` on the login endpoint with a normal-looking request; same credentials fail on the web too | account/password | log in at storytel.com |
| Requests carry an integrity token, responses mention `integrity`/`attest`/`nonce`, the app calls `com.google.android.play.core.integrity` (Play Integrity) or `SafetyNet` | server-side attestation | cannot be fixed by LSPatch. Confirm by checking whether the **probe** build (or any re-signed build) also fails. Stop here. |
| App shows "modified"/"unsupported version"/"tampered" immediately, before any network call; JADX shows `getPackageInfo(…, GET_SIGNATURES)` / `GET_SIGNING_CERTIFICATES` / a hash compare | client-side signature validation | re-patch with `-l 1`, then `-l 2` (`-l 3` fork only). If the message disappears, that was it. |
| Login succeeds, playback refuses to start, `MediaDrm`/`Widevine`/license/`403` on a license URL | backend rejection of the client during licensing (device or app identity) | try `-l 2`; if it persists across levels, the license server checks something LSPatch cannot restore. Stop here. |
| Works with the probe, fails with the module | the module's hooks interfere | disable with `scripts/config.sh "mode=off"` (should not matter for login; the module hooks nothing network-related) and paste the log. |

## 5. Playback works but stays capped at 2x

Enable discovery and tap 2x; read the `[SET]` line:

```text
no [SET] line at all
└── the tap never reaches Media3 through the hooked funnel → section 2.

[SET] … in=2.00x -> out=2.00x (mode=off / passthrough / caller filter not matched)
└── configuration: mode=off, remap_from ≠ 2.0, caller_filter mismatching, or hook_point=ctor while the
    ctor hook could not be installed. scripts/config.sh show; fix; re-tap.

[SET] … in=2.00x -> out=3.00x  but audio is still 2x
├── a second [SET] follows immediately with in=2.00x: Storytel re-applies its own state after the
│   change callback (UI clamps by re-setting). The hook remaps it again; if audio still sounds 2x,
│   enable discovery_ctor=true and check whether a [CTOR] with speed 2.0 appears AFTER the [SET]
│   that is NOT followed by a [SET] (Media3-internal path). Paste the sequence.
├── a different player instance plays the audio: compare player=#id in [SET] with the ids in
│   [PLAYER-CREATED]; if the audible player never receives a [SET], Storytel sets speed on it via a
│   path that is not the funnel (e.g. ExoPlayerImplInternal directly, or AudioTrack params) → paste.
└── the value is set before playback starts and then overwritten by Storytel when the item starts
    (in=2.00x → out=3.00x, then in=1.50x from a "restore last speed" routine). That is Storytel
    logic; remap it too (it is a normal [SET]) or note the caller frame for a Storytel-side hook.

The UI (after Option C) offers 4x, but [SET] shows in=2.00x
└── Storytel clamps in its controller before Media3. The caller frames above [SET] point at the
    clamp method; paste its decompiled body (docs/JADX-FINDINGS-TEMPLATE.md, section C).

[SET] … in=4.00x -> out=4.00x yet audio ≈ 2x
└── extremely unlikely with Media3 (sink clamps at 8x). Check with discovery_ctor whether a later
    [CTOR] 2.0 construction comes from Media3 classes (paste) — or the audio is simply hard to judge:
    compare elapsed position vs wall clock over one minute.
```

## 6. Config broadcasts get no reply

- Storytel must be **running** (the receiver is registered in `Application.onCreate`). Open it, start playback, retry.
- `LOADED:` present but no `config receiver registered` line → Storytel's `Application` subclass does
  not call `super.onCreate()` (rare) → paste the log; the registration would move to an Activity hook.
- Android 14 defers broadcasts to *cached* apps; bring Storytel to the foreground.
- Check the package: `-p grit.storytel.app` must match `pm list packages`.

---

## Reading diagnostics without adb (in-app)

The module reports its state through the config broadcast reply, which is what the
*Storytel Speed Mod* app shows under "Reply from Storytel process". No adb needed:

1. Open Storytel (patched) and start any audiobook.
2. Change the speed to 2x (and try 1.5x, 2x again).
3. Open *Storytel Speed Mod* and press **Show current**.

The reply ends with a `--- diagnostics ---` block:

- `media3=… usable=true/false` and the `PlaybackParameters=/ExoPlayerImpl=/funnel=` lines say
  whether the hook attached. `usable=false` or `NOT FOUND` means Storytel's build renamed Media3
  (Phase 2, Case B) or does not use ExoPlayer — the hook never ran.
- `events seen: N` with `[SET]/[ENTRY]/[PLAYER-CREATED]` lines shows what the hook saw when you
  changed speed. `[SET] … in=2.00x -> out=4.00x` means it is working. `events seen: 0` after you
  changed speed means Storytel is not routing through the hooked player (different player, a clamp
  before it, or a separate playback process — each `pid=…` block in the reply is one process).
