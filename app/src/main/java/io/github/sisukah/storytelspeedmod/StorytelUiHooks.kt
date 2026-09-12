package io.github.sisukah.storytelspeedmod

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * OPTION C scaffold: expose real 2.5x / 3x / 3.5x / 4x entries in Storytel's own speed picker.
 *
 * This needs Storytel-specific (obfuscated) names that can only come from JADX, so it is OFF
 * until both ui_speed_list_class and ui_speed_list_method are configured. Nothing here invents
 * Storytel names.
 *
 * Supported return shapes of the configured method (the common ones for a "list of speeds"):
 *   float[]            -> extra speeds are appended
 *   List<Float>/Double -> extra speeds are appended (a new ArrayList is returned)
 * Anything else is logged with its runtime type and field names so you can paste it back and
 * the hook can be finished for the real shape.
 *
 * Note: even with the list expanded, Storytel's controller may clamp the value before it reaches
 * Media3. Discovery mode shows that (UI selects 4.0 but [SET] logs in=2.00x). That clamp is a
 * second Storytel-specific hook and also needs JADX findings.
 */
object StorytelUiHooks {

    fun install(cl: ClassLoader, store: ConfigStore) {
        val cfg = store.current
        val clsName = cfg.raw(Keys.UI_SPEED_LIST_CLASS) ?: return
        val methodName = cfg.raw(Keys.UI_SPEED_LIST_METHOD) ?: run {
            SLog.w("ui_speed_list_class is set but ui_speed_list_method is empty; Option C hook not installed")
            return
        }
        val clazz = try {
            Class.forName(clsName, false, cl)
        } catch (t: Throwable) {
            SLog.w("Option C: class $clsName not found ($t)")
            return
        }
        val unhooks = XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val extras = store.current.uiExtraSpeeds
                    val expanded = expand(param.result, extras)
                    if (expanded !== param.result) {
                        param.result = expanded
                        if (store.current.discovery) {
                            SLog.i("[UI-LIST] ${Discovery.sig(param.method)} -> appended $extras")
                        }
                    }
                } catch (t: Throwable) {
                    SLog.e("Option C hook failed (result left unchanged)", t)
                }
            }
        })
        SLog.i("Option C: hooked ${unhooks.size} method(s) named $methodName on $clsName")
    }

    /** Returns the same instance when nothing could be done. */
    fun expand(result: Any?, extras: List<Float>): Any? {
        if (extras.isEmpty()) return result
        when (result) {
            is FloatArray -> {
                return (result.toList() + extras).distinctBy { SpeedPolicy.fmt(it) }.sorted().toFloatArray()
            }
            is DoubleArray -> {
                return (result.toList() + extras.map { it.toDouble() }).distinctBy { SpeedPolicy.fmt(it.toFloat()) }.sorted().toDoubleArray()
            }
            is List<*> -> {
                val first = result.firstOrNull()
                return when (first) {
                    is Float -> (result.filterIsInstance<Float>() + extras).distinctBy { SpeedPolicy.fmt(it) }.sorted()
                    is Double -> (result.filterIsInstance<Double>() + extras.map { it.toDouble() }).distinctBy { SpeedPolicy.fmt(it.toFloat()) }.sorted()
                    null -> result
                    else -> {
                        SLog.w("Option C: list element type ${first.javaClass.name} is not a plain number. " +
                            "Fields: ${first.javaClass.declaredFields.joinToString { "${it.type.simpleName} ${it.name}" }}. " +
                            "Paste this line back to finish the hook.")
                        result
                    }
                }
            }
            null -> return result
            else -> {
                SLog.w("Option C: unexpected return type ${result.javaClass.name}; paste this line back to finish the hook.")
                return result
            }
        }
    }
}
