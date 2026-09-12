package io.github.sisukah.storytelspeedmod

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Runtime configuration channel. Registered INSIDE the patched Storytel process (dynamically,
 * from the Application.onCreate hook), so it works in every LSPatch mode without touching
 * Storytel's manifest.
 *
 * From adb (ordered broadcast -> the reply is printed as "data=..."):
 *
 *   adb shell am broadcast -a io.github.sisukah.storytelspeedmod.CONFIG -p grit.storytel.app \
 *       --es set "mode=remap;target=3.5;discovery=true"
 *   adb shell am broadcast -a io.github.sisukah.storytelspeedmod.CONFIG -p grit.storytel.app --ez show true
 *   adb shell am broadcast -a io.github.sisukah.storytelspeedmod.CONFIG -p grit.storytel.app --ez reset true
 *
 * Storytel must be running (a dynamically registered receiver only exists while the process
 * lives). The ConfigActivity in this APK sends exactly the same broadcast.
 */
class ConfigReceiver(private val store: ConfigStore) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.getBooleanExtra(EXTRA_RESET, false)) {
                store.reset()
                SLog.i("config reset to defaults")
            }
            val set = intent.getStringExtra(EXTRA_SET)
            if (!set.isNullOrBlank()) {
                store.apply(set)
                SLog.i("config updated: ${set.replace('\n', ';')}")
            }
            val dump = store.current.dump()
            SLog.i("effective config:\n$dump")
            if (isOrderedBroadcast) {
                resultCode = Activity.RESULT_OK
                resultData = "pid=${android.os.Process.myPid()} process=${processName()}\n$dump"
            }
        } catch (t: Throwable) {
            SLog.e("config receiver failed", t)
            if (isOrderedBroadcast) {
                resultCode = Activity.RESULT_CANCELED
                resultData = "error: $t"
            }
        }
    }

    private fun processName(): String = try {
        if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName() else "?"
    } catch (t: Throwable) {
        "?"
    }

    companion object {
        const val ACTION = "io.github.sisukah.storytelspeedmod.CONFIG"
        const val EXTRA_SET = "set"       // String: "key=value;key=value" (or newline separated)
        const val EXTRA_RESET = "reset"   // Boolean
        const val EXTRA_SHOW = "show"     // Boolean (no-op: every broadcast replies with the config)

        fun register(context: Context, store: ConfigStore) {
            val receiver = ConfigReceiver(store)
            val filter = IntentFilter(ACTION)
            if (Build.VERSION.SDK_INT >= 33) {
                // Storytel targets a modern SDK: the export flag is mandatory on API 33+.
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            SLog.i("config receiver registered for action $ACTION")
        }
    }
}
