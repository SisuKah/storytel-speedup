# Only used if you enable isMinifyEnabled. The entry class is referenced by name from
# assets/xposed_init; the receiver/activity are referenced from the manifest (kept by AGP).
-keep class io.github.sisukah.storytelspeedmod.MainHook { *; }
-keepclassmembers class io.github.sisukah.storytelspeedmod.** { *; }
-dontwarn de.robv.android.xposed.**
