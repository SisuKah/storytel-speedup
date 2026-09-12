package io.github.sisukah.storytelspeedmod

import android.app.Application
import android.os.Build
import android.os.Process
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Entry point (named in assets/xposed_init). Called by LSPatch in every process of the patched
 * app, before the app's Application is created.
 *
 * Order of operations:
 *  1. log a load line (same tag as the PHASE 1 probe),
 *  2. load the config from Storytel's private data dir (no Context needed),
 *  3. hook Application.onCreate to register the config broadcast receiver,
 *  4. resolve Media3 targets and install the hooks (retry once at Application.onCreate in case
 *     the player classes live in a classloader that is only ready later).
 */
class MainHook : IXposedHookLoadPackage {

    private val hooksInstalled = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        SLog.i("LOADED: package=${lpparam.packageName} process=${lpparam.processName} pid=${Process.myPid()} " +
            "android=${Build.VERSION.SDK_INT} xposedApi=${XposedBridge.getXposedVersion()} " +
            "module=${BuildConfig.VERSION_NAME} device=${Build.MANUFACTURER} ${Build.MODEL}")
        XposedBridge.log("${SLog.TAG}: loaded in ${lpparam.packageName} (${lpparam.processName})")

        if (lpparam.packageName !in EXPECTED_PACKAGES) {
            SLog.w("package ${lpparam.packageName} is not in $EXPECTED_PACKAGES; continuing anyway " +
                "(LSPatch only loads this module into the app it was embedded in)")
        }

        val dataDir = lpparam.appInfo?.dataDir
        val store = ConfigStore(dataDir?.let { File(it, "files/$CONFIG_FILE") })
        store.load()
        SLog.i("effective config:\n${store.current.dump()}")

        hookApplicationOnCreate(store)
        installHooks(lpparam.classLoader, store, phase = "handleLoadPackage")
    }

    private fun installHooks(cl: ClassLoader, store: ConfigStore, phase: String) {
        if (hooksInstalled.get()) return
        try {
            val targets = Media3Targets.resolve(cl, store.current)
            targets.logSummary()
            Diag.resolution = targets.compact()
            if (!targets.usable) {
                SLog.w("Media3 targets not usable at $phase; speed hooks NOT installed" +
                    if (phase == "handleLoadPackage") " (will retry at Application.onCreate)" else "")
                return
            }
            Media3Hooks.install(targets, store)
            StorytelUiHooks.install(cl, store)
            hooksInstalled.set(true)
            SLog.i("hooks installed at $phase")
        } catch (t: Throwable) {
            Diag.resolution = "resolve/install FAILED at $phase: $t"
            SLog.e("installing hooks failed at $phase", t)
        }
    }

    private fun hookApplicationOnCreate(store: ConfigStore) {
        try {
            XposedHelpers.findAndHookMethod(Application::class.java, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Application ?: return
                    if (receiverRegistered.compareAndSet(false, true)) {
                        try {
                            ConfigReceiver.register(app, store)
                        } catch (t: Throwable) {
                            SLog.e("registering config receiver failed", t)
                        }
                    }
                    installHooks(app.classLoader, store, phase = "Application.onCreate")
                }
            })
        } catch (t: Throwable) {
            SLog.e("hooking Application.onCreate failed (config broadcasts will not work)", t)
        }
    }

    companion object {
        /** Confirm with: adb shell pm list packages | grep -i storytel */
        val EXPECTED_PACKAGES = setOf("grit.storytel.app")
        const val CONFIG_FILE = "storytel_speed_mod.properties"
    }
}
