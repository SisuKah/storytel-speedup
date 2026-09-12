package io.github.sisukah.storytelspeedmod

import android.util.Log

/**
 * One log tag for everything the module prints, so a single filter shows it all:
 *
 *   adb logcat -s StorytelSpeedMod
 *
 * (Windows: the same command works; `adb logcat | findstr StorytelSpeedMod` also works.)
 */
object SLog {
    const val TAG = "StorytelSpeedMod"

    fun i(msg: String) {
        Log.i(TAG, msg)
    }

    fun w(msg: String, t: Throwable? = null) {
        if (t == null) Log.w(TAG, msg) else Log.w(TAG, msg, t)
    }

    fun e(msg: String, t: Throwable? = null) {
        if (t == null) Log.e(TAG, msg) else Log.e(TAG, msg, t)
    }
}
