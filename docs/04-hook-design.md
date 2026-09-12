# Phases 4–7 — Hook design, discovery mode, configuration, audio behaviour

## Interception points considered

Verified Media3 facts (release branch source):

- `PlaybackParameters(float speed, float pitch)`: `public final float speed; public final float pitch;`
  plus a `private final int scaledUsPerMs = Math.round(speed * 1000f)`. `withSpeed(f)` builds a new
  instance with the same pitch.
- `BasePlayer.setPlaybackSpeed(float)` is `final` and does
  `setPlaybackParameters(getPlaybackParameters().withSpeed(speed))`.
- `ExoPlayerImpl` (package-private) declares exactly one method with a single `PlaybackParameters`
  parameter: `setPlaybackParameters`. It returns early when the value equals the current one.
- `DefaultAudioSink` clamps speed and pitch to `[0.1, 8.0]`; 4x is inside ExoPlayer's range.
- Media3's consumer ProGuard rules do **not** keep these classes, so an app build may rename them.

| | Option A: `PlaybackParameters` constructor | Option B: `ExoPlayerImpl.setPlaybackParameters` (+ `BasePlayer.setPlaybackSpeed`) | Option C: Storytel's speed list / UI |
|---|---|---|---|
| Depends on Storytel names | no | no | **yes** (obfuscated) |
| Survives Storytel updates | as long as Media3 keeps the class (any version) | as long as Media3 keeps the method (stable since ExoPlayer 2.14) | breaks on almost every Storytel release |
| Survives R8 renaming of Media3 | needs the renamed class name (from JADX); ctor is found by `(float,float)` signature | needs class names; methods/fields are found structurally | n/a |
| Can affect unrelated Media3 players in the process | **yes**: every construction, including Media3-internal ones (audio sink clamp results, session bundles) | only app-initiated speed changes; per-instance filtering possible (caller frames) | no |
| Exposes real 2.5–4x UI entries | no (remap / force only) | no (remap / force only) | **yes** |
| Simplicity | very simple: one hook, `args[0]` | simple: one funnel hook, replace `args[0]` with a new instance | depends on Storytel's data shapes; possibly several hooks (list, clamp, label) |
| Crash risk | low; wrong value → `checkArgument` throws inside Media3 (we only pass finite > 0) | low; same | medium: type mismatches in Storytel code |
| Where the value is caught | anywhere a PP is built (too early for app-side clamps, too late for nothing) | after all Storytel logic, before Media3 internals | before Storytel logic |

**Chosen design (matches your preference: lowest useful Media3 level, constrained):**

1. Substitution at the **Option B funnel** `ExoPlayerImpl.setPlaybackParameters` (`hook_point=player`, default).
   Everything Storytel can do (direct `Player` calls, `MediaController`, legacy `MediaSessionCompat`
   callbacks, `ForwardingPlayer` wrappers) ends there; Media3-internal speed adjustments do not.
   If the funnel cannot be resolved but `BasePlayer.setPlaybackSpeed(float)` can, substitution
   moves there automatically (logged as `entry+fallback-substitution`).
2. **Option A as a switchable fallback** (`hook_point=ctor`) for exotic builds where the player
   implementation cannot be resolved at all but the `PlaybackParameters` class can.
3. **Option C scaffold** (`StorytelUiHooks`) that stays inactive until you supply Storytel names.
4. Constraints so unrelated media is not touched:
   - `MODE_REMAP_2X` (default) only rewrites a request for *exactly* `remap_from` (2.0). A trailer
     or video preview never asks for 2.0x on its own.
   - `caller_filter=<substring>`: only substitute when a caller frame contains the substring (e.g.
     the Storytel class that discovery mode shows calling the funnel). Empty = no filter.
   - `[PLAYER-CREATED]` discovery lines show how many `ExoPlayerImpl` instances exist and who built
     them, so you can tell whether Storytel even has a second player.
   - `max_speed` (4.0) caps the target regardless of what is typed.
5. **Loop safety**: if Storytel listens to `onPlaybackParametersChanged`, sees 3.5 and re-applies
   2.0, the hook turns that into 3.5 again, `ExoPlayerImpl` sees "unchanged" and returns without a
   callback: the loop terminates after one round.
6. **Pitch is never touched**: the substituted `PlaybackParameters` reuses the incoming `pitch`
   (normally 1.0), so Media3 keeps time-stretching with pitch correction exactly as it does at 2x.

## Discovery mode (Phase 5)

All hooks are installed at process start; discovery only changes what is **logged**. It is toggled
at runtime, so no rebuild or re-patch is needed for experiments:

```bash
scripts/config.sh "discovery=true"          # or the checkbox in the Storytel Speed Mod app
adb logcat -c && adb logcat -s StorytelSpeedMod
```

Then: start Storytel, start an audiobook, change speed 1x → 1.5x → 2x → 1x, and read the log.

What you get (all lines under the single tag `StorytelSpeedMod`):

| Line | Meaning |
|------|---------|
| `LOADED: package=… process=… android=… xposedApi=… module=…` | the module is in this process |
| `effective config:` block | what the hooks will do |
| `Media3 target resolution:` block | which classes/methods were found (`NOT FOUND` tells you immediately that names are obfuscated), the Media3 version, structural-identification notes |
| `hooked [funnel] …` / `hooked [entry] …` / `hooked [ctor] …` / `hooked [discovery-only] …` | which members are hooked |
| `[PLAYER-CREATED] … (#n in this process)` + frames | an `ExoPlayerImpl` was constructed; frames show the Storytel class that built it |
| `[ENTRY] <class>.<method> this=<runtime class> … arg=…` + frames | a public entry point was called (`BasePlayer.setPlaybackSpeed`, `ForwardingPlayer`/`SimpleExoPlayer`/`MediaController` setters). `this=` reveals a Storytel `ForwardingPlayer` subclass if one exists. |
| `[SET] <class>.<method> player=#id thread=… in=2.00x pitch=1.00 -> out=3.00x (remap 2.00 -> 3.00; mode=remap hook_point=player)` + frames | the funnel; `in` is what Storytel asked for, `out` what Media3 receives, and the reason |
| `[CTOR] …` (only with `discovery_ctor=true`) | every `PlaybackParameters` construction, including Media3-internal ones |
| `[GET] …` (only with `discovery_getters=true`) | reads of `getPlaybackParameters()`; useful to see whether Storytel polls the player speed to sync its UI |

Frames are innermost-first, with module/Xposed plumbing removed; `discovery_frames` (default 10)
controls how many. The thread name shows whether the call came from the UI (`main`), a binder
thread (MediaSession/MediaController path) or a background executor.

How to read the result for the three questions that matter:

- **Which Media3 call does Storytel use?** The `[ENTRY]` line that appears first when you tap a
  speed (its `this=` class and the top frames). If only `[SET]` appears, Storytel calls
  `setPlaybackParameters` directly.
- **Where does 2x get clamped?** If the UI offered 4x (after Option C) but `[SET]` shows `in=2.00x`,
  Storytel clamps before Media3; the frames above `[SET]` name the method. If `[SET]` shows
  `in=4.00x` and `out=4.00x` yet audio stays at 2x, look at `[CTOR]` lines right after it: a
  Media3-internal re-clamp would show up there (it should not, since 4 < 8).
- **Is there more than one player?** Count `[PLAYER-CREATED]` lines; use their frames to pick a
  `caller_filter` if a second player should stay untouched.

Noise control: `[SET]`/`[ENTRY]` only fire when speed changes, so the log stays quiet during
normal playback; `discovery_ctor` and `discovery_getters` are the noisy ones and are off by default.

## Configuration (Phase 6)

### Where it lives and why

The hook code runs **inside Storytel's process**, so the simplest always-readable place for the
configuration is Storytel's own private data directory:

```text
/data/user/0/grit.storytel.app/files/storytel_speed_mod.properties
```

- No `XSharedPreferences` or world-readable preference tricks (which depend on the framework
  flavour and are not reliable under LSPatch).
- Survives module rebuilds and Storytel re-patching (LSPatch re-signs with the same key, so the
  data directory is kept on update). It is only lost when Storytel is uninstalled.
- Readable before any `Context` exists (`handleLoadPackage` gets the data dir from `appInfo`).

### How it is changed

A `BroadcastReceiver` (`ConfigReceiver`) is registered dynamically from an `Application.onCreate`
hook. Any sender can reach it while Storytel runs:

```bash
# adb (Linux/macOS/Windows): quotes matter
adb shell am broadcast -a io.github.sisukah.storytelspeedmod.CONFIG -p grit.storytel.app --es set "'mode=remap;target=3.5;discovery=true'"
adb shell am broadcast -a io.github.sisukah.storytelspeedmod.CONFIG -p grit.storytel.app --ez show true
adb shell am broadcast -a io.github.sisukah.storytelspeedmod.CONFIG -p grit.storytel.app --ez reset true
# helper: scripts/config.sh "mode=remap;target=3.5"   |   scripts\config.ps1 "mode=remap;target=3.5"
```

The broadcast is ordered, so `am broadcast` prints the reply, e.g.
`Broadcast completed: result=-1, data="pid=… mode=remap\ntarget=3.5\n…"`. The *Storytel Speed Mod*
app (this APK installed normally) sends the same broadcast from a small form and shows the reply.

Changes to `mode`, `target`, `remap_from`, `max_speed`, `hook_point`, `caller_filter` and the
`discovery*` keys apply **immediately**. The `cls_*`, `m_*`, `f_*` and `ui_*` keys are used when
the process starts: change them, then `adb shell am force-stop grit.storytel.app` and reopen.

### Modes

**`mode=ladder` (default).** Each of Storytel's own buttons maps to a faster effective speed, so the
speed is chosen inside Storytel and applies the instant the button is tapped. This is the mode that
removes the external app and the app restart from the loop: a speed only changes when Storytel
builds a new `PlaybackParameters`, which is exactly what tapping a button does.

```text
Storytel button   plays at      (default ladder)
0.5 / 0.75 / 1.0  unchanged     normal listening still works
1.25              2.5x
1.5               3.0x
1.75              3.5x
2.0               4.0x
```

Configured as `ladder=from:to,from:to,...`. Buttons not listed keep their real speed. An explicitly
empty `ladder=` means no step changes anything (use `mode=off` to disable the module properly).

```text
MODE_REMAP_2X (mode=remap)                   MODE_FORCE_TARGET (mode=force)
Storytel requests 0.5 … 1.75x  -> unchanged  Storytel requests anything -> target
Storytel requests 2.0x         -> target     (Storytel's whole picker becomes "target")
```

`remap_from` lets you move the trigger (e.g. `remap_from=1.75` keeps a real 2x and turns 1.75x
into the fast option). `mode=off` keeps hooks and logging but changes nothing.

### Keys

| Key | Default | Meaning |
|-----|---------|---------|
| `mode` | `ladder` | `ladder` / `remap` / `force` / `off` |
| `ladder` | `1.25:2.5,1.5:3.0,1.75:3.5,2.0:4.0` | ladder mode only: `button:played` pairs |
| `target` | `3.0` | presets 2.5, 3.0, 3.5, 4.0 or any value; clamped to `[0.1, max_speed]` |
| `remap_from` | `2.0` | the requested speed that becomes `target` in remap mode |
| `max_speed` | `4.0` | safety cap (Media3's own hard cap is 8.0) |
| `hook_point` | `player` | `player` (funnel) or `ctor` (Option A) |
| `caller_filter` | empty | substring that must appear in a caller frame for substitution to happen |
| `discovery` | `false` | verbose logging |
| `discovery_frames` | `10` | frames per event (0–40) |
| `discovery_ctor` / `discovery_getters` | `false` | extra noisy logs |
| `cls_playback_parameters`, `cls_player_impl`, `cls_base_player`, `m_set_playback_parameters`, `m_set_playback_speed`, `f_speed`, `f_pitch` | empty | overrides for renamed Media3 (Phase 2, Case B); fields and methods are verified structurally even when given |
| `ui_speed_list_class`, `ui_speed_list_method`, `ui_extra_speeds` | empty / `2.5,3.0,3.5,4.0` | Option C scaffold |

## Audio behaviour (Phase 7)

The module never changes pitch. Media3 keeps applying its normal time-stretching
(`SonicAudioProcessor`, a WSOLA-style algorithm) with pitch correction, exactly as it does for
Storytel's own 1.5x/2x. What changes at 2.5x–4x:

- **Intelligibility drops non-linearly.** Sonic keeps pitch by discarding/overlapping ever more
  of each period. Up to roughly 2.5–3x most narrators stay intelligible; at 3.5–4x short consonants
  and unstressed syllables start to merge or vanish ("skipped/conflated consonants"), fast
  dialogue and heavily accented narration suffer first. Many people find ~2.5–3x substantially
  cleaner than 4x on the same book; results depend on the narrator, the audio bitrate, and the
  amount of silence Storytel's build lets Sonic skip.
- **Artifacts**: metallic/warbly texture, doubled transients, a slight "underwater" quality on
  music/intro jingles.
- **CPU**: the decoder must produce 4x real time and Sonic works on 4x the samples; on a modern
  Samsung this is still a small load, but battery use during playback rises and low-end devices
  can stutter or fall behind (buffer underruns show as brief pauses).
- **Device/player limits**: `DefaultAudioSink` hard-clamps to 8x. If Storytel enables audio
  *offload* (DSP playback for battery), ExoPlayer disables offload as soon as speed is not 1.0 and
  falls back to the normal path; nothing to do, but it explains why the battery advantage vanishes.
  If Storytel had `enableAudioTrackPlaybackParams`, the platform `AudioTrack` time-stretcher
  would be used instead of Sonic; its limits are higher than 4x but its quality differs per vendor.
  Bluetooth adds nothing new (stretching happens before the codec).
- **Position/remaining-time displays** in Storytel are computed from *its* idea of the speed. With
  remap, the app thinks it plays at 2x while audio runs at 3x, so "time left" is over-estimated;
  chapter positions and sync stay correct because they come from the player's real position.
