# Phase 3 — Android Studio project structure

Open the repository root in Android Studio (File > Open > the folder containing
`settings.gradle.kts`). Studio downloads Gradle 8.11.1 from `gradle/wrapper/gradle-wrapper.properties`.
The project was built and unit-tested from the command line with JDK 21 (Studio's bundled JDK is fine).

```text
StorytelSpeedMod/
├── settings.gradle.kts            plugin repositories + Xposed Maven repo (https://api.xposed.info/); includes :probe and :app
├── build.gradle.kts               AGP 8.9.3 and Kotlin 2.1.20 declared once (apply false)
├── gradle.properties              JVM args, AndroidX flag
├── gradlew / gradlew.bat / gradle/wrapper/   Gradle 8.11.1 wrapper
├── probe/                         PHASE 1: empty compatibility module
│   ├── build.gradle.kts           compileOnly("de.robv.android.xposed:api:82"), minSdk 28, no other dependencies
│   ├── proguard-rules.pro         only relevant if minification is enabled
│   └── src/main/
│       ├── AndroidManifest.xml    <application> with the four xposed* <meta-data>; no components
│       ├── assets/xposed_init     "io.github.sisukah.storytelspeedmod.probe.ProbeHook"
│       ├── res/values/arrays.xml  xposedscope string-array (grit.storytel.app)
│       ├── res/values/strings.xml
│       └── java/.../probe/ProbeHook.kt   logs one line, installs nothing
├── app/                           the real module
│   ├── build.gradle.kts           same deps + junit for JVM tests; BuildConfig enabled
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/AndroidManifest.xml      xposed* meta-data + optional ConfigActivity (LAUNCHER)
│       ├── main/assets/xposed_init       "io.github.sisukah.storytelspeedmod.MainHook"
│       ├── main/res/values/{arrays,strings}.xml
│       ├── main/java/io/github/sisukah/storytelspeedmod/
│       │   ├── MainHook.kt               entry point (IXposedHookLoadPackage): log, load config, hook Application.onCreate, install hooks
│       │   ├── Media3Targets.kt          resolves PlaybackParameters / ExoPlayerImpl / BasePlayer by name, fallback name, or structure
│       │   ├── Media3Hooks.kt            the hooks (funnel, entry, ctor, discovery-only)
│       │   ├── SpeedPolicy.kt            pure decision logic: remap / force / off + clamps
│       │   ├── Config.kt                 keys, defaults, parsing, properties-file store
│       │   ├── ConfigReceiver.kt         broadcast channel registered inside Storytel's process
│       │   ├── Discovery.kt              caller-frame capture and formatting
│       │   ├── StorytelUiHooks.kt        Option C scaffold (inactive until JADX names are configured)
│       │   ├── SLog.kt                   the single log tag "StorytelSpeedMod"
│       │   └── ui/ConfigActivity.kt      optional dependency-free settings screen
│       └── test/java/...                 JVM unit tests (SpeedPolicy, Config, UI-list expansion)
├── scripts/                       adb helpers: pull APK, patch, logcat filter, config broadcast (bash + PowerShell)
└── docs/                          these guides
```

## Which files matter for what

### Required by classic Xposed module loading (LSPatch legacy path, LSPosed, EdXposed)

| File / setting | Purpose |
|----------------|---------|
| `assets/xposed_init` | one fully-qualified class name per line; the framework instantiates it |
| entry class implements `de.robv.android.xposed.IXposedHookLoadPackage` | `handleLoadPackage()` is called in each process of the target app |
| `<meta-data android:name="xposedmodule" android:value="true"/>` | marks the APK as a module |
| `<meta-data android:name="xposedminversion" android:value="93"/>` | minimum framework API; 93 is what LSPosed/LSPatch report |
| `<meta-data android:name="xposeddescription" .../>` | shown in module lists |
| `compileOnly("de.robv.android.xposed:api:82")` from `https://api.xposed.info/` | the API is provided at runtime by the framework; it must **not** be bundled (verified: the built APK contains no `de.robv.android.xposed` classes) |
| entry class name must survive R8 | minification is off; `proguard-rules.pro` keeps it anyway |

### Expected by modern LSPosed / LSPatch tooling

| File / setting | Purpose |
|----------------|---------|
| `<meta-data android:name="xposedscope" android:resource="@array/xposedscope"/>` + `res/values/arrays.xml` | recommended scope shown by LSPosed when enabling the module. LSPatch ignores it (the module is embedded into exactly one app). Harmless to keep. |
| `xposedminversion` >= 93 | required for LSPosed to honour `xposedscope` |
| Installable APK (signed) | the LSPatch *manager* lists modules from installed apps, and the config Activity needs the APK installed; the CLI only needs the file |
| *Not used:* libxposed "modern API" (`io.github.libxposed:api`, `META-INF/xposed/java_init.list`) | the classic API is supported by both the official LSPatch and the maintained fork, so it is the safer choice for a rootless setup |

### Optional

| Item | Purpose |
|------|---------|
| `ui/ConfigActivity.kt` + LAUNCHER intent filter | settings UI. Remove the `<activity>` element if you only want to use adb broadcasts. |
| `src/test` | `./gradlew :app:testReleaseUnitTest` (16 tests) |
| `scripts/` | convenience only |

## Signing and build types

Both modules sign `release` with the Android debug keystore (`~/.android/debug.keystore`, created
by Studio/Gradle on first build). That is acceptable for a personal module: it just needs to be a
valid signed APK. If you ever want a stable key of your own, create a keystore and replace
`signingConfig = signingConfigs.getByName("debug")` with a proper `signingConfigs { create("release") {...} }`.

`isMinifyEnabled = false` keeps class and method names readable in crash stacks and discovery
output. The price is ~2 MB of unused Kotlin stdlib in the APK; irrelevant for embedding.

## Build commands

```bash
./gradlew :probe:assembleRelease          # -> probe/build/outputs/apk/release/probe-release.apk
./gradlew :app:assembleRelease            # -> app/build/outputs/apk/release/app-release.apk
./gradlew :app:testReleaseUnitTest        # JVM tests
```

Android Studio: select the module in the run configuration dropdown, then
Build > Build Bundle(s)/APK(s) > Build APK(s); the "locate" link opens the output folder.
