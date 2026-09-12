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
    private val scanStarted = AtomicBoolean(false)
    private val sliderInstalled = AtomicBoolean(false)
    private val pickerInstalled = AtomicBoolean(false)
    private val pickerScanStarted = AtomicBoolean(false)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        SLog.i("LOADED: package=${lpparam.packageName} process=${lpparam.processName} pid=${Process.myPid()} " +
            "android=${Build.VERSION.SDK_INT} xposedApi=${XposedBridge.getXposedVersion()} " +
            "module=${BuildConfig.VERSION_NAME} device=${Build.MANUFACTURER} ${Build.MODEL}")
        XposedBridge.log("${SLog.TAG}: loaded in ${lpparam.packageName} (${lpparam.processName})")

        // Allow the structural scan to read the class list on Android 9+ where non-SDK interfaces
        // (dalvik.system.DexFile#entries) are otherwise blocked. No-op if the framework already
        // exempted them.
        HiddenApi.exempt()

        if (lpparam.packageName !in EXPECTED_PACKAGES) {
            SLog.w("package ${lpparam.packageName} is not in $EXPECTED_PACKAGES; continuing anyway " +
                "(LSPatch only loads this module into the app it was embedded in)")
        }

        val dataDir = lpparam.appInfo?.dataDir
        val store = ConfigStore(dataDir?.let { File(it, "files/$CONFIG_FILE") })
        store.load()
        SLog.i("effective config:\n${store.current.dump()}")

        hookApplicationOnCreate(store)
        // Fast, name-only attempt first (no scan) so an un-obfuscated build hooks immediately.
        installHooks(lpparam.classLoader, store, phase = "handleLoadPackage", allowScan = false)
        // Independent of the Media3 resolution: the slider hooks target framework/library widgets.
        if (sliderInstalled.compareAndSet(false, true)) {
            try {
                SliderHooks.install(lpparam.classLoader, store)
            } catch (t: Throwable) {
                Diag.slider = "install FAILED: $t"
                SLog.e("installing slider hooks failed", t)
            }
        }
        // Storytel's custom-speed picker: the fast, name-based part now; the class scan at onCreate.
        if (pickerInstalled.compareAndSet(false, true)) {
            try {
                PickerHooks.install(lpparam.classLoader, store)
            } catch (t: Throwable) {
                Diag.picker = "install FAILED: $t"
                SLog.e("installing picker hooks failed", t)
            }
        }
    }

    private fun installHooks(cl: ClassLoader, store: ConfigStore, phase: String, allowScan: Boolean) {
        if (hooksInstalled.get()) return
        try {
            val targets = Media3Targets.resolve(cl, store.current, allowScan)
            targets.logSummary()
            Diag.resolution = targets.compact()
            if (!targets.usable) {
                SLog.w("Media3 targets not usable at $phase; speed hooks NOT installed" +
                    if (!allowScan) " (will retry with a structural scan at Application.onCreate)" else "")
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

    /**
     * The structural scan can enumerate tens of thousands of classes, so it must not run on the
     * app's main thread. Run it once, in the background; hooking from another thread is fine.
     */
    private fun installHooksWithScanAsync(cl: ClassLoader, store: ConfigStore) {
        if (hooksInstalled.get() || !scanStarted.compareAndSet(false, true)) return
        Thread({
            installHooks(cl, store, phase = "Application.onCreate (scan)", allowScan = true)
        }, "StorytelSpeedMod-scan").apply { isDaemon = true }.start()
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
                    // Try name-only again (cheap); if still not resolved, scan in the background.
                    installHooks(app.classLoader, store, phase = "Application.onCreate", allowScan = false)
                    installHooksWithScanAsync(app.classLoader, store)
                    if (pickerScanStarted.compareAndSet(false, true)) {
                        Thread({ PickerHooks.installOptionsHook(app.classLoader, store) }, "StorytelSpeedMod-picker")
                            .apply { isDaemon = true }.start()
                    }
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
