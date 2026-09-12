# Phase 10 — Update resilience, risks and limitations

## How the project is built to survive updates

| Concern | Design |
|---------|--------|
| Storytel's obfuscated names | Not used anywhere in the module by default. All hooks target Media3/ExoPlayer members (`androidx.media3.*`, falling back to `com.google.android.exoplayer2.*`). |
| Media3 renamed by R8 in some Storytel build | Class names are the only thing needed; they are supplied through config (`cls_*`), not code. Methods and fields are then found **structurally** (the unique `(PlaybackParameters) -> void` method, the two float fields identified by constructing `PlaybackParameters(2.0, 1.0)`), so a renaming pass changes nothing else. |
| Storytel-specific constants | Isolated in two places: `MainHook.EXPECTED_PACKAGES` (only a warning) and the `ui_*` / `cls_*` config keys (data, not code). `StorytelUiHooks` is the only file that would ever hold Storytel-shaped logic. |
| Detecting behaviour changes quickly | The startup `Media3 target resolution:` block prints `NOT FOUND` / `usable : false` on the first launch after an update that broke a target. Discovery mode shows within one speed change whether the funnel still fires and what the caller path is. No rebuild needed for either. |
| Configuration survives module rebuilds | Stored in Storytel's data directory, not in the module APK; re-embedding a new module build or re-patching Storytel keeps it. |
| Routine update procedure | 1. `scripts/pull-storytel-apk.sh` (or download the new version) 2. `java -jar lspatch.jar … -m app-release.apk <new apks>` 3. `adb install-multiple patched/*.apk` (installs over, data kept) 4. `adb logcat -s StorytelSpeedMod` once, check `usable : true` and one `[SET]`. |

## Which Storytel updates are likely to break it

| Change in a Storytel release | Effect | Fix effort |
|------------------------------|--------|-----------|
| Ordinary app update, same Media3 | none; re-patch only | minutes |
| Media3 upgrade (e.g. 1.4 → 1.6) | none expected: `PlaybackParameters`, `BasePlayer.setPlaybackSpeed` and the `ExoPlayerImpl` funnel have been stable for years. A future refactor that splits/renames the funnel would show as `funnel NOT FOUND` → set `m_*` overrides after a JADX look | minutes to an hour |
| R8 configuration change that starts (or stops) renaming Media3 | `PlaybackParameters: NOT FOUND` → find the new names by strings (Phase 2, Case B) and update `cls_*` config; names change on **every** release once obfuscated, so this becomes part of the routine (two strings per update) | 10–20 minutes per update |
| Playback moved to a separate `:process` | hooks still install (module loads in every process); config broadcasts reach every process; nothing to do | none |
| Storytel replaces ExoPlayer with another engine | hooks find nothing; a new investigation is needed | hours |
| Storytel adds client-side integrity checks, or starts using Play Integrity for login/licensing | the patched build stops working regardless of the module; only sigbypass levels can help, and only for client-side checks | possibly unfixable |
| Storytel's UI starts re-applying its own speed periodically | remap still wins (each re-apply is remapped); only cosmetic/"time left" impact | none |
| Option C hooks (if you enabled them) | break on nearly every release because they name Storytel classes; re-do the JADX step or turn them off | 30+ minutes per update |

## Risks and limitations (read before using)

- **Terms of Service.** Storytel's terms very likely prohibit modified clients and reverse
  engineering. Using this can put your account at risk (warning, suspension, termination). This is
  a personal-use experiment on your own device; do not distribute patched APKs.
- **Detection.** Storytel can detect a re-signed APK (signature comparison, Play Integrity, or
  server-side heuristics such as the app's reported signature/installer). Detection may surface
  as failed login, blocked playback, or account action, possibly only after an update.
- **Login and DRM depend on identity.** Authentication and Widevine licensing may be tied to the
  app's signing identity or to Play Integrity verdicts. Signature bypass levels only cover
  client-side checks. If the backend refuses, nothing here can help, and no DRM circumvention is
  provided or intended: audio never leaves Media3's normal decrypt-decode-play pipeline.
- **No Play Store updates.** The patched install has a different signature; Play cannot update it
  and will never do so. Every Storytel update means re-obtaining and re-patching. Old versions can
  be force-retired by the backend ("please update"), so you cannot postpone updates forever.
- **Media3 implementation drift.** The hooks rely on Media3 internals (`ExoPlayerImpl`), not on a
  public contract. A Media3 or R8 change in a future Storytel build can require new class names or
  a new hook target.
- **Unsupported speeds.** 2.5–4x is outside what Storytel tests. Expect intelligibility loss,
  audio artifacts, higher CPU/battery use, wrong "time remaining", and possibly odd UI states
  (the picker highlighting 2x while playing 3x). Stutter or desync at 4x on a given book is a
  device/decoder limit, not a bug to chase.
- **Data loss on first install.** Uninstalling the official app wipes offline downloads and local
  settings; positions and library come back from the account.
- **Security.** The config receiver is exported (adb needs that). Anyone with an app on the phone
  could change your playback speed settings. Nothing else is exposed.
- **Samsung specifics.** Auto Blocker must be off to sideload; Samsung's app-signing checks for
  Knox/Secure Folder do not apply to normal installs.
