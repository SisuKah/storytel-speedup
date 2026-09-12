# Only relevant if you turn on isMinifyEnabled. The entry class is referenced by name from
# assets/xposed_init, so it must keep its name.
-keep class io.github.sisukah.storytelspeedmod.probe.ProbeHook { *; }
-dontwarn de.robv.android.xposed.**
