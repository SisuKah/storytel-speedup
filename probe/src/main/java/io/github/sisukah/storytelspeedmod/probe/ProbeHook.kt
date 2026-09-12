package io.github.sisukah.storytelspeedmod.probe

import android.os.Build
import android.os.Process
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * PHASE 1 probe. Proves that LSPatch loads a module inside Storytel.
 *
 * It installs NO hooks. It only writes one recognizable line to logcat:
 *
 *   adb logcat -s StorytelSpeedMod
 *
 * If this line never appears, or Storytel misbehaves with this module embedded, stop and
 * diagnose before touching playback (see docs/01-compatibility-test.md).
 */
class ProbeHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val line = "PROBE LOADED: package=${lpparam.packageName} process=${lpparam.processName} " +
            "pid=${Process.myPid()} android=${Build.VERSION.SDK_INT} " +
            "xposedApi=${XposedBridge.getXposedVersion()} device=${Build.MANUFACTURER} ${Build.MODEL}"
        // Plain logcat line with our unique tag (what you grep for).
        Log.i(TAG, line)
        // Also through the framework's own logger, so it shows up in LSPatch's log view too.
        XposedBridge.log("$TAG: $line")
    }

    private companion object {
        const val TAG = "StorytelSpeedMod"
    }
}
