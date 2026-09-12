# Phase 2 — Reverse engineering Storytel's playback-speed path

Goal: learn (a) whether Storytel's build kept the Media3 class names, (b) where the selectable
speeds `0.5 … 2.0` are defined/clamped, and (c) which Media3 call the app uses to set speed. You
end up with a short list of class/method names to paste back (template at the end).

Two ground rules:
1. **Nothing below invents Storytel names.** Storytel's own code is expected to be obfuscated
   (`a.b.c`, `C0123`), and every Storytel-specific name must come from your JADX session.
2. Verify by tracing callers/usages, never by the first search hit.

## Tools

| Tool | Use |
|------|-----|
| `adb` | pull the APK, logcat |
| JADX / JADX-GUI 1.5+ | decompile, search, "Find usage" |
| APKEditor (REAndroid) or 7-Zip | merge/unpack `.apkm`/`.xapk`/split sets |
| The module's discovery mode (Phase 5) | confirms empirically what JADX suggests |

## 1. Get the APK

Same as Phase 1 (`scripts/pull-storytel-apk.sh`, or `adb shell pm path grit.storytel.app` and
`adb pull` each file). Code lives in `base.apk` (`classes.dex`, `classes2.dex`, …); the
`split_config.*` files hold native libs, resources and languages and rarely contain code. If a
split does contain `classes.dex` (feature module), open it in JADX too.

Archive alternative: APKMirror, exactly the `versionName` shown by
`adb shell dumpsys package grit.storytel.app | grep versionName`, ideally the universal APK.

## 2. Open in JADX-GUI

- File > Open files > select `base.apk` (multi-select the splits as well if needed).
- Preferences > Decompiler: **turn "Deobfuscation" OFF** while collecting names. With it on, JADX
  shows friendly renamed identifiers (`C0123abc`) that do not exist in the APK; the module needs
  the real names. (You can keep a second window with deobfuscation on for readability; the real
  name is then shown in a `/* renamed from: ... */` comment.)
- Preferences > Appearance/Other: enable "Show inconsistent code" so broken methods still show.
- Text search: `Ctrl+Shift+F` (search in Class / Method / Field / Code / Resource, tick "Code").
- Usage: right-click an identifier > *Find Usage* (`x`). Jump back with `Alt+Left`.
- Give it time: a Storytel-size APK takes minutes to index the first time.

## 3. First question: is Media3 obfuscated in this build?

Search **Class** names for `PlaybackParameters` and packages `androidx.media3` /
`com.google.android.exoplayer2`.

**Case A — the names are there** (`androidx.media3.common.PlaybackParameters`,
`androidx.media3.exoplayer.ExoPlayerImpl` visible): the module's defaults work as-is. Note the
Media3 version: open `androidx.media3.common.MediaLibraryInfo` and copy `VERSION`.

**Case B — nothing found**: R8 renamed the library. Media3's consumer ProGuard rules do not keep
these classes (verified), so this is possible. Find them by **strings and structure**, which R8
cannot rename:

| Search (Code, case-sensitive) | What it finds | How to recognise it |
|------------------------------|---------------|---------------------|
| `AndroidXMedia3/` | `MediaLibraryInfo` | a class with `public static final String` fields; one value is `"AndroidXMedia3/1.x.y"` → that is your Media3 version. (`ExoPlayerLib/` for ExoPlayer 2.) |
| `"ExoPlayerImpl"` or `"Init "` or `"Release "` | `ExoPlayerImpl` | huge `final class`, log tag `"ExoPlayerImpl"`, a constructor taking a *Builder*, logs `"Init " + hex + " [" + VERSION_SLASHY + "] [" + deviceInfo + "]"`. |
| `1000.0f` together with `Math.round` | `PlaybackParameters` | `public final class` with **two `public final float`** fields and **one `private final int`**, constructor `(float, float)` with two `checkArgument(f > 0.0f)` and `Math.round(f * 1000.0f)`, a `public static final` field of its own type (`DEFAULT`), and a method `(float) -> same type` (`withSpeed`). |
| `0.1f` and `8.0f` | `DefaultAudioSink.setPlaybackParameters` | the code that clamps speed and pitch to `[0.1, 8.0]`; the parameter type there is `PlaybackParameters`, and the first constrained field is `speed`, the second `pitch`. |
| `"Audio sink error"`, `"AudioTrack init failed"` | `DefaultAudioSink` / audio renderer | confirms the audio path. |

Inside `ExoPlayerImpl`, find the method whose single parameter is the `PlaybackParameters` class
and whose body starts with an application-thread check, then compares with the current value and
returns early, then increments a counter (`pendingOperationAcks++` in source) — that is
`setPlaybackParameters`. There is exactly one such method (verified in Media3 source), which is
why the module can also find it structurally once it knows the two class names.

`BasePlayer` is the superclass of `ExoPlayerImpl`; its `setPlaybackSpeed(float)` is the only
`(float) -> void` method it declares and its body is one line: `setPlaybackParameters(getPlaybackParameters().withSpeed(f))`.

Write down, for Case B:

```text
cls_playback_parameters=<renamed class>
cls_player_impl=<renamed ExoPlayerImpl>
cls_base_player=<renamed BasePlayer>          (optional)
m_set_playback_parameters=<method name>       (optional, module finds it structurally)
m_set_playback_speed=<method name>            (optional)
f_speed=<field name>  f_pitch=<field name>    (optional, module verifies them by construction)
```

These go into the module config later (no rebuild): `scripts/config.sh "cls_playback_parameters=...;cls_player_impl=..."`
followed by `adb shell am force-stop grit.storytel.app`.

## 4A. Storytel's speed-selector values

You are looking for where `0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0` (or a subset) are created,
filtered, clamped, formatted, or displayed. Searches, most selective first:

| Search | Why |
|--------|-----|
| `1.75f` | rare constant; almost only speed pickers use it. Best first search. |
| `0.75f`, `1.25f` | same idea |
| `2.0f` | many hits; look for ones next to `0.5f` or in `Math.min(…, 2.0f)` / `RangesKt.coerceIn(…, 0.5f, 2.0f)` / `coerceAtMost` — that is a **clamp**. |
| `new float[]{0.5f` / `listOf(Float.valueOf(0.5f)` / `CollectionsKt.listOf` near float boxes | arrays/lists of speeds (Kotlin `listOf(0.5f, …)` decompiles to `CollectionsKt.listOf(Float.valueOf(0.5f), …)`). |
| `"x"`, `"%sx"`, `"%.2fx"`, `"1.5x"`, `"2x"`, `"1x"` | label formatting / hardcoded labels. Also search Resources (`res/values/strings.xml`) for `speed` and the `arrays.xml` for float/string arrays. |
| `playbackSpeed`, `playback_speed`, `PlaybackSpeed`, `speed` (Method + Field + Code) | names sometimes survive in Kotlin data classes, JSON keys, analytics event names (`"playback_speed_changed"`), SharedPreferences keys (`"speed"`), navigation args. Strings never get renamed. |
| `SPEED_2X`, `X2`, `Speed2`, `TWO_X` (Class/Field) | enum-style pickers. |
| `setPlaybackSpeed`, `setPlaybackParameters`, `PlaybackParameters` (Method/Class/Code) | direct Media3 usage (Case A). |

For every hit that looks right:

1. *Find Usage* on the array/list/constant. Follow it **up** until you reach UI code (a Fragment,
   ViewModel, Compose function, or a `RecyclerView` adapter that turns the values into buttons).
2. Follow it **down** until you reach the call that hands a float to the player. Note every
   intermediate method that touches the value; a clamp (`coerceIn`, `Math.min`, `if (f > 2.0f)`)
   anywhere on that path is what would block real 2.5–4x options.
3. Record class + method + signature for: the list source, the clamp (if any), the UI consumer,
   and the last Storytel method before Media3.

## 4B. Media3 / ExoPlayer speed calls

Trace this chain:

```text
Storytel UI (picker)
    ↓  Storytel speed value (float / enum / data class)
Storytel playback controller / service (may pass through MediaSession)
    ↓  Player.setPlaybackSpeed(float)  or  Player.setPlaybackParameters(PlaybackParameters)
Media3 Player  →  BasePlayer.setPlaybackSpeed → ExoPlayerImpl.setPlaybackParameters
    ↓
PlaybackParameters(speed, pitch)  →  ExoPlayerImplInternal → DefaultAudioSink (clamps 0.1–8.0) → Sonic time-stretch
```

Possible middle layers, depending on Storytel's architecture (all end in the same funnel):

| Path | JADX evidence |
|------|---------------|
| Direct: UI/ViewModel holds the `ExoPlayer` | usages of `setPlaybackSpeed(float)`/`setPlaybackParameters(...)` in Storytel classes |
| Media3 session: `MediaController` in UI, `MediaSession` in a service | `androidx.media3.session.MediaController` usages; `MediaSessionService`/`MediaLibraryService` subclasses; the controller's `setPlaybackSpeed` is forwarded through a binder to the session's player (same process unless the service declares `android:process`). |
| Legacy compat session: `MediaControllerCompat.getTransportControls().setPlaybackSpeed(float)` → `MediaSessionCompat.Callback.onSetPlaybackSpeed(float)` | search `onSetPlaybackSpeed` (Method) — Storytel's override is where the value re-enters app code before the player. |
| Custom wrapper: a Storytel class extending `ForwardingPlayer` or implementing `Player` | classes whose superclass is `androidx.media3.common.ForwardingPlayer`; their `setPlaybackSpeed` override may clamp. |

API-version notes:
- Media3 1.x: `setPlaybackSpeed(float)` is `final` in `BasePlayer`; everything funnels into `ExoPlayerImpl.setPlaybackParameters`.
- ExoPlayer 2.x (`com.google.android.exoplayer2`): identical structure, different package. The module already tries both.
- Very old ExoPlayer (<2.14) had no `setPlaybackSpeed`; apps built `new PlaybackParameters(speed)` themselves. Still caught by the constructor hook point.

**Best interception point**: `ExoPlayerImpl.setPlaybackParameters(PlaybackParameters)`. Every
public path converges there, the value is final (no later app-side clamp), Media3's own internal
speed adjustments (live playback control) do *not* pass through it, and it early-returns when the
value is unchanged, which prevents ping-pong loops if Storytel re-applies "2.0" after noticing a
different speed. Hooking *above* it (Storytel code) is only needed for Option C (real UI entries)
or if a Storytel clamp sits between the UI and the funnel and you want the UI's 2.5–4x buttons to
work; hooking *below* it (audio sink) would also catch Media3-internal adjustments, which we do
not want.

## 5. Let discovery mode confirm it

Before trusting JADX, run the module in discovery mode (Phase 5). It logs every call to the
funnel/entry points with caller frames. Changing speed in Storytel to 1x → 1.5x → 2x should
produce `[SET]`/`[ENTRY]` lines whose caller frames contain Storytel classes — those frames are
your ground truth for 4A/4B and for the `caller_filter` option.

## 6. What to paste back

If Media3 names are intact (Case A) and discovery mode shows `[SET] … in=2.00x`, you need nothing
else; the remap works. Paste findings back only when:

- Case B (renamed Media3): the names from section 3.
- Discovery shows the app never reaches the funnel, or a clamp sits in Storytel code: the
  Storytel class/method signatures from 4A/4B, plus the decompiled body of the clamp/list method.
- You want Option C (real 2.5–4x UI entries): the list-source method signature, its return type,
  and (if it is a data class) the class's fields.

Use `docs/JADX-FINDINGS-TEMPLATE.md`.
