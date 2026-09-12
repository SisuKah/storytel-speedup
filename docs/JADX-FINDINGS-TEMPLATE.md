# JADX findings to paste back (fill in only what applies)

Storytel versionName / versionCode: ____ / ____   (adb shell dumpsys package grit.storytel.app | grep version)
Media3 version (MediaLibraryInfo.VERSION or the "AndroidXMedia3/x.y.z" string): ____

## A. Media3 naming (Case A or Case B)

- [ ] Case A: `androidx.media3.common.PlaybackParameters` exists by name.
- [ ] Case B: renamed. Paste the resolved names:

```text
cls_playback_parameters=
cls_player_impl=
cls_base_player=
m_set_playback_parameters=
m_set_playback_speed=
f_speed=
f_pitch=
```

Paste the decompiled class header + fields + constructor of the PlaybackParameters candidate:

```java
// paste here
```

## B. Speed list source (Storytel code)

Class + method signature that produces the selectable speeds, and its decompiled body:

```java
// e.g.  public final List<Float> a()  {  return CollectionsKt.listOf(Float.valueOf(0.5f), ...); }
```

Return type / element class (if not plain Float): ____  Fields of the element class: ____

## C. Clamp (if any) between UI and Media3

Class + method + decompiled body of anything that limits the value (coerceIn, Math.min, if > 2.0f):

```java
// paste here
```

## D. Last Storytel method before Media3

The method that calls setPlaybackSpeed/setPlaybackParameters (or the MediaSession callback):

```java
// paste here
```

## E. Discovery log excerpt

`adb logcat -s StorytelSpeedMod` while changing 1x → 1.5x → 2x (the [SET]/[ENTRY] blocks with frames):

```text
// paste here
```
