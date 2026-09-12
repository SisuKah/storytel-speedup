package io.github.sisukah.storytelspeedmod

import java.lang.reflect.Method

/**
 * Lifts Android's non-SDK interface (hidden API) restrictions for this process, so the structural
 * scan can call dalvik.system.DexFile#entries() on Android 9+.
 *
 * Uses the well-known "meta-reflection" bootstrap: invoking Class.getDeclaredMethod reflectively
 * makes the lookup of VMRuntime.setHiddenApiExemptions appear to originate from the platform, which
 * is not subject to the restriction. Passing "L" (the JVM type-descriptor prefix that every
 * reference type starts with) exempts all hidden members. Best-effort: under LSPatch the framework
 * usually exempts them already, so a failure here is not fatal.
 */
object HiddenApi {

    @Volatile
    private var done = false

    @Synchronized
    fun exempt() {
        if (done) return
        done = true
        try {
            val getDeclaredMethod: Method = Class::class.java.getMethod(
                "getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = getDeclaredMethod.invoke(
                vmRuntimeClass, "getRuntime", arrayOfNulls<Class<*>>(0)) as Method
            val setExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass, "setHiddenApiExemptions", arrayOf<Class<*>>(Array<String>::class.java)) as Method
            val runtime = getRuntime.invoke(null)
            setExemptions.invoke(runtime, arrayOf("L") as Any)
            SLog.i("hidden API exemptions applied")
        } catch (t: Throwable) {
            SLog.w("hidden API exemption not applied (often already done by the framework)", t)
        }
    }
}
