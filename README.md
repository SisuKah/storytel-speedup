# Storytel Speed Mod (LSPatch, no root)

A small Kotlin Xposed-API module, embedded into the Storytel Android app with **LSPatch**, that lets
audiobook playback run above Storytel's built-in 2x limit (2.5x / 3x / 3.5x / 4x) by adjusting the
Media3/ExoPlayer playback speed inside the app process. Personal use on your own, unrooted phone.

It does **not** touch DRM, audio data, downloads, or network traffic. It changes one number
(`PlaybackParameters.speed`) on its way into ExoPlayer, and keeps pitch correction as-is.

Everything happens inside Storytel itself, nothing to open, nothing to restart:

- Storytel's **"Edit custom speed" picker** (0.5 … 2.0) is extended up to 4.0 in 0.1 steps, with
  Storytel's own labels, persistence and time-remaining, because the picker's ceiling is just a
  server flag (`maximum_playback_speed`) that the module overrides from inside the app.
- Until that has happened once, **ladder mode** makes the four fastest preset buttons play faster
  than they say (1.25 -> 2.5x, 1.5 -> 3x, 1.75 -> 3.5x, 2 -> 4x). As soon as the picker is
  extended, the ladder stands down and every value is a real choice.

## Working order (do not skip ahead)

| Step | Guide | Deliverable in this repo |
|------|-------|--------------------------|
| 1. Prove LSPatch compatibility with an empty module | [docs/01-compatibility-test.md](docs/01-compatibility-test.md) | `:probe` module, `scripts/pull-storytel-apk.*`, `scripts/patch.sh`, `scripts/logcat.*` |
| 2. Reverse-engineer the speed path in JADX | [docs/02-reverse-engineering.md](docs/02-reverse-engineering.md), [docs/JADX-FINDINGS-TEMPLATE.md](docs/JADX-FINDINGS-TEMPLATE.md) | search recipes; what to paste back |
| 3. Android Studio / Xposed project skeleton | [docs/03-project-structure.md](docs/03-project-structure.md) | `settings.gradle.kts`, `build.gradle.kts`, `app/`, `probe/` |
| 4. Discovery mode | [docs/04-hook-design.md](docs/04-hook-design.md#discovery-mode-phase-5) | `Media3Hooks.kt`, `Discovery.kt`, `scripts/config.*` |
| 5. Media3 hook implementation | [docs/04-hook-design.md](docs/04-hook-design.md#interception-points-considered) | `Media3Targets.kt`, `Media3Hooks.kt`, `SpeedPolicy.kt` |
| 6. Configuration / 2x remapping | [docs/04-hook-design.md](docs/04-hook-design.md#configuration-phase-6) | `Config.kt`, `ConfigReceiver.kt`, `ui/ConfigActivity.kt` |
| 7. Build and LSPatch installation | [docs/05-build-and-install.md](docs/05-build-and-install.md) | `scripts/patch.sh` |
| 8. Troubleshooting | [docs/06-troubleshooting.md](docs/06-troubleshooting.md) | |
| 9. Update resilience, risks | [docs/07-update-resilience.md](docs/07-update-resilience.md) | |

Audio behaviour at high speeds (pitch, artifacts, CPU, limits) is covered at the end of
[docs/04-hook-design.md](docs/04-hook-design.md#audio-behaviour-phase-7).

## What is confirmed vs. what you must identify

**Confirmed (from primary sources, not guessed):**

- Storytel 26.35 decompiled: its own feature packages keep real names (`com.storytel.playbackspeed…`),
  while libraries (Media3, Compose, kotlin) are renamed per release. The custom-speed list is
  `0.5, +0.1 … ≤ maximum_playback_speed` (a Firebase Remote Config flag, default 2.0) and there is
  no clamp between the picker and `PlaybackParameters(f, 1.0)`. See docs/04-hook-design.md.

- Storytel's package name is `grit.storytel.app` (Play Store listing). Confirm on your phone with
  `adb shell pm list packages | grep -i storytel`.
- Media3 structure (release branch): `PlaybackParameters(float speed, float pitch)` with public
  final `speed`/`pitch`; `BasePlayer.setPlaybackSpeed(float)` is final and funnels into
  `ExoPlayerImpl.setPlaybackParameters(PlaybackParameters)`, the only method on that class taking a
  `PlaybackParameters`; `DefaultAudioSink` clamps speed to `[0.1, 8.0]`.
- Media3's consumer ProGuard rules do not keep those classes, so a Storytel build **may** have
  renamed them (R8). The module handles both cases.
- LSPatch: the maintained fork (`JingMatrix/LSPatch`) supports Android 9 → current beta and classic
  Xposed modules; its CLI patches a base + splits set together and offers signature bypass
  levels 0–3. The official `LSPosed/LSPatch` v0.6 (2023) patches each APK separately and offers
  levels 0–2.

**Likely Media3 targets (hooked by default, verified at runtime with logging):**
`androidx.media3.exoplayer.ExoPlayerImpl.setPlaybackParameters`, `androidx.media3.common.BasePlayer.setPlaybackSpeed`,
`androidx.media3.common.PlaybackParameters.<init>(float,float)`; ExoPlayer 2 equivalents as fallback.

**Storytel-specific targets you must identify with JADX / discovery logging (none are assumed):**
renamed Media3 class names if R8 obfuscated them; Storytel's speed list source and any clamp
(only needed for real 2.5–4x picker entries, Option C); the caller class for `caller_filter`.

## Quick start (after Phase 1 passed)

```bash
./gradlew :app:assembleRelease
java -jar lspatch.jar -o patched -f -l <level> -m app/build/outputs/apk/release/app-release.apk storytel-apk/*.apk
adb install-multiple patched/*.apk
adb logcat -s StorytelSpeedMod                      # expect LOADED, "usable : true", "hooked [funnel]"
# nothing else to configure: pick the speed inside Storytel
#   1.25 -> 2.5x   1.5 -> 3x   1.75 -> 3.5x   2 -> 4x   (1x and below unchanged)
scripts/config.sh "ladder=1.25:2.25,1.5:2.5,1.75:2.75,2.0:3.0"   # optional: a gentler mapping
```

Defaults: `mode=ladder` with `ladder=1.25:2.5,1.5:3.0,1.75:3.5,2.0:4.0`, `max_speed=4.0`,
`hook_point=player`, `discovery=false`. The other modes (`remap`, `force`, `off`) are still there.

## Risks, in one paragraph

Modified clients likely violate Storytel's Terms of Service; a re-signed APK can be detected; login
or licensing may depend on signing identity or Play Integrity, which cannot be bypassed; Google Play
no longer updates the patched app and every Storytel update needs re-patching; Media3/R8 changes
can require new hook targets; playback above 2x is unsupported and may sound bad or stutter.
Details in [docs/07-update-resilience.md](docs/07-update-resilience.md).
