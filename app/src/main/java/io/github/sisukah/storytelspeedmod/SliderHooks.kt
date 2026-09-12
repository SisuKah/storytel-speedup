package io.github.sisukah.storytelspeedmod

import android.view.View
import android.widget.ProgressBar
import android.widget.SeekBar
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Extends Storytel's "Edit custom speed" slider (0.5 … 2.0 in 0.1 steps) up to max_speed, and
 * remembers what the user chose on it so a value Storytel clamps back to 2.0 can be restored at
 * the player (the "clamp bypass" in Media3Hooks.onConstruct).
 *
 * Storytel's own code is renamed by R8, so nothing here uses a Storytel name. The slider is
 * reached through whichever widget draws it, each of which has a handle R8 cannot remove:
 *
 *  1. android.widget.SeekBar — a framework class. Never renamed. Its listener interface is a
 *     framework interface, so Storytel's implementation must keep the method name
 *     onProgressChanged. The app maps integer progress 0..max linearly onto 0.5..2.0.
 *  2. com.google.android.material.slider.Slider — a library View. Referenced from layout XML it
 *     keeps its class name, and the default R8 config keeps setter/getter names on Views, so the range is
 *     readable and settable by name. Float based.
 *  3. androidx.compose.material3.SliderKt.Slider (or material) — a static composable. Found by
 *     name only if the build kept it; the range and steps are arguments and are rewritten in
 *     flight; onValueChange is wrapped in a Proxy to capture the chosen value.
 *
 * Every hook only LOGS unless the widget's range is exactly Storytel's speed range, and every
 * modification is wrapped so a failure degrades to logging. slider=off disables all of it.
 */
object SliderHooks {

    private const val INTENT_WINDOW_MS = 2000L
    private const val SPEED_HINTS = "speed,tempo,rate,playback"

    @Volatile private var intentValue: Float? = null
    @Volatile private var intentAtMs: Long = 0L
    @Volatile private var lastLoggedMove: Float = -1f

    private val hookedClasses = Collections.synchronizedSet(HashSet<Class<*>>())
    private val loggedInstances = Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<Any, Boolean>()))
    private val seenRanges = Collections.synchronizedSet(HashSet<String>())
    private val proxies = Collections.synchronizedMap(WeakHashMap<Any, Any>())

    /** SeekBars identified as the speed slider -> the max Storytel gave them (for the mapping). */
    private val speedSeekBars = Collections.synchronizedMap(WeakHashMap<SeekBar, Int>())
    /** SeekBars whose app code indexes an array by progress (learned from a swallowed exception). */
    private val arrayIndexed = Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<SeekBar, Boolean>()))

    @Volatile private var materialSlider: WeakReference<Any>? = null
    @Volatile private var materialGetValue: Method? = null

    fun install(cl: ClassLoader, store: ConfigStore) {
        val parts = ArrayList<String>()
        parts += runCatching { installSeekBar(store) }.getOrElse { "SeekBar: failed ($it)" }
        parts += runCatching { installMaterial(cl, store) }.getOrElse { "material Slider: failed ($it)" }
        parts += runCatching { installCompose(cl, store) }.getOrElse { "compose SliderKt: failed ($it)" }
        Diag.slider = parts.joinToString("\n  ")
        SLog.i("slider hooks:\n  ${Diag.slider}")
    }

    /**
     * If Storytel just built [built] but the user's slider asked for more, returns what the
     * slider asked for (capped). Null when no bypass applies.
     */
    fun clampBypassFor(built: Float, cfg: Config): Float? {
        val ceil = SpeedPolicy.ceiling(cfg)
        val now = System.currentTimeMillis()
        intentValue?.let { v ->
            SliderMath.clampBypass(built, v, now - intentAtMs, INTENT_WINDOW_MS, ceil)?.let { return it }
        }
        // Fallback for a Material slider whose listener could not be hooked by name: read the
        // widget's current value while it is on screen.
        val slider = materialSlider?.get() ?: return null
        val getValue = materialGetValue ?: return null
        return try {
            if (slider is View && !slider.isShown) return null
            val cur = getValue.invoke(slider) as? Float ?: return null
            SliderMath.clampBypass(built, cur, 0L, INTENT_WINDOW_MS, ceil)
        } catch (t: Throwable) {
            null
        }
    }

    /** True when [speed] is exactly what the user just chose on the slider (so no rung applies). */
    fun cameFromSlider(speed: Float): Boolean =
        SliderMath.fromSlider(speed, intentValue, System.currentTimeMillis() - intentAtMs, INTENT_WINDOW_MS)

    private fun recordMove(value: Float, source: String) {
        if (!value.isFinite() || value <= 0f) return
        intentValue = value
        intentAtMs = System.currentTimeMillis()
        if (value > SliderMath.STORYTEL_MAX + 0.005f && !SliderMath.near(value, lastLoggedMove, 0.005f)) {
            lastLoggedMove = value
            Diag.add("[SLIDER-MOVE] $source chose ${SpeedPolicy.fmt(value)}x")
        }
    }

    // ---------------------------------------------------------------------------------------
    // 1. android.widget.SeekBar
    // ---------------------------------------------------------------------------------------

    private fun installSeekBar(store: ConfigStore): String {
        XposedBridge.hookAllConstructors(SeekBar::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val bar = param.thisObject as? SeekBar ?: return
                    if (!loggedInstances.add(bar)) return
                    val name = idName(bar)
                    val speedy = speedSeekBars.containsKey(bar) || isSpeedSeekBar(name, bar.max, store.current)
                    Diag.add("[SLIDER] SeekBar ${bar.javaClass.simpleName} id=$name max=${bar.max}" +
                        if (speedy) "  <- speed slider" else "")
                } catch (t: Throwable) {
                    SLog.w("SeekBar constructor hook", t)
                }
            }
        })

        // setMax is called from the ProgressBar constructor (layout XML) and from app code.
        XposedHelpers.findAndHookMethod(ProgressBar::class.java, "setMax", Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val bar = param.thisObject as? SeekBar ?: return
                        val cfg = store.current
                        val requested = param.args[0] as? Int ?: return
                        val name = idName(bar)
                        if (!isSpeedSeekBar(name, requested, cfg)) return
                        speedSeekBars[bar] = requested
                        if (!cfg.sliderEnabled || !cfg.sliderSeekBar) return
                        val newMax = SliderMath.newSeekBarMax(requested, SpeedPolicy.ceiling(cfg)) ?: return
                        if (newMax <= requested) return
                        param.args[0] = newMax
                        Diag.add("[SLIDER-EXTEND] SeekBar id=$name max $requested -> $newMax " +
                            "(top ${SpeedPolicy.fmt(SliderMath.seekBarSpeed(newMax, requested))}x)")
                    } catch (t: Throwable) {
                        SLog.w("setMax hook", t)
                    }
                }
            })

        // The listener interface is a framework interface, so the implementing method keeps its
        // name whatever R8 did to the class. Hook it for intent capture and as a crash net.
        XposedHelpers.findAndHookMethod(SeekBar::class.java, "setOnSeekBarChangeListener",
            SeekBar.OnSeekBarChangeListener::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        hookSeekBarListener(param.args[0] ?: return)
                    } catch (t: Throwable) {
                        SLog.w("SeekBar listener hook", t)
                    }
                }
            })
        return "SeekBar: hooked (extension ${if (store.current.sliderSeekBar) "on" else "off"})"
    }

    private fun isSpeedSeekBar(idName: String, max: Int, cfg: Config): Boolean {
        val forced = cfg.sliderSeekBarId
        if (forced != null) return idName.equals(forced, ignoreCase = true)
        val lower = idName.lowercase()
        if (SPEED_HINTS.split(',').any { lower.contains(it) }) return true
        return max == cfg.sliderSeekBarMax
    }

    private fun hookSeekBarListener(listener: Any) {
        val cls = listener.javaClass
        if (!hookedClasses.add(cls)) return
        var c: Class<*>? = cls
        var hooked = 0
        while (c != null && c != Any::class.java) {
            c.declaredMethods.filter {
                it.parameterTypes.size == 3 && it.parameterTypes[0] == SeekBar::class.java &&
                    (it.name == "onProgressChanged" || it.name == "onStartTrackingTouch" || it.name == "onStopTrackingTouch")
            }.forEach { m ->
                m.isAccessible = true
                XposedBridge.hookMethod(m, seekBarListenerHook(m))
                hooked++
            }
            // onStart/StopTrackingTouch take one parameter
            c.declaredMethods.filter {
                it.parameterTypes.size == 1 && it.parameterTypes[0] == SeekBar::class.java &&
                    (it.name == "onStartTrackingTouch" || it.name == "onStopTrackingTouch")
            }.forEach { m ->
                m.isAccessible = true
                XposedBridge.hookMethod(m, seekBarListenerHook(m))
                hooked++
            }
            if (hooked > 0) break
            c = c.superclass
        }
        if (hooked > 0) Diag.add("[SLIDER] hooked SeekBar listener ${cls.name} ($hooked method(s))")
    }

    private fun seekBarListenerHook(m: Member) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                val bar = param.args[0] as? SeekBar ?: return
                val originalMax = speedSeekBars[bar] ?: return
                if (m.name != "onProgressChanged") return
                val progress = param.args[1] as? Int ?: return
                val fromUser = param.args[2] as? Boolean ?: true
                if (fromUser) recordMove(SliderMath.seekBarSpeed(progress, originalMax), "SeekBar")
                // Once we know the app indexes an array by progress, keep its code inside the
                // original range; the intent recorded above restores the real speed at the player.
                if (arrayIndexed.contains(bar) && progress > originalMax) param.args[1] = originalMax
            } catch (t: Throwable) {
                SLog.w("SeekBar listener before-hook", t)
            }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                val bar = param.args[0] as? SeekBar ?: return
                val originalMax = speedSeekBars[bar] ?: return
                val t = param.throwable ?: return
                if (t is IndexOutOfBoundsException && bar.progress > originalMax) {
                    param.result = null          // swallow: the app tried to index past its table
                    arrayIndexed.add(bar)
                    Diag.add("[SLIDER] SeekBar id=${idName(bar)} indexes a table by position; " +
                        "from now on its code sees at most $originalMax while the player gets the real speed")
                }
            } catch (e: Throwable) {
                SLog.w("SeekBar listener after-hook", e)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // 2. com.google.android.material.slider.Slider
    // ---------------------------------------------------------------------------------------

    private fun installMaterial(cl: ClassLoader, store: ConfigStore): String {
        val cls = try {
            Class.forName("com.google.android.material.slider.Slider", false, cl)
        } catch (t: Throwable) {
            return "material Slider: not present by name"
        }
        val getFrom = noArg(cls, "getValueFrom", java.lang.Float.TYPE)
        val getTo = noArg(cls, "getValueTo", java.lang.Float.TYPE)
        val getStep = noArg(cls, "getStepSize", java.lang.Float.TYPE)
        val getValue = noArg(cls, "getValue", java.lang.Float.TYPE)
        val setTo = oneArg(cls, "setValueTo", java.lang.Float.TYPE)
        if (getFrom == null || getTo == null) return "material Slider: present, but its getters are renamed (cannot read the range)"
        materialGetValue = getValue

        XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val s = param.thisObject ?: return
                    if (!loggedInstances.add(s)) return
                    val from = getFrom.invoke(s) as Float
                    val to = getTo.invoke(s) as Float
                    val step = getStep?.invoke(s) as? Float
                    val name = if (s is View) idName(s) else "?"
                    val speedy = SliderMath.isSpeedRange(from, to)
                    Diag.add("[SLIDER] material Slider id=$name from=${SpeedPolicy.fmt(from)} to=${SpeedPolicy.fmt(to)} " +
                        "step=${step?.let { SpeedPolicy.fmt(it) } ?: "?"}" + if (speedy) "  <- speed slider" else "")
                    if (!speedy) return
                    materialSlider = WeakReference(s)
                    val cfg = store.current
                    if (!cfg.sliderEnabled) return
                    if (setTo == null) {
                        Diag.add("[SLIDER] material Slider: setValueTo is renamed, cannot extend")
                        return
                    }
                    // Material throws at draw time unless the range is a whole number of steps.
                    val ceil = SliderMath.snapToStep(from, step ?: 0f, SpeedPolicy.ceiling(cfg))
                    setTo.invoke(s, ceil)
                    Diag.add("[SLIDER-EXTEND] material Slider to ${SpeedPolicy.fmt(to)} -> ${SpeedPolicy.fmt(ceil)}")
                } catch (t: Throwable) {
                    SLog.w("material Slider constructor hook", t)
                }
            }
        })

        if (setTo != null) {
            // Ranges set from code after inflation.
            XposedBridge.hookMethod(setTo, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val s = param.thisObject ?: return
                        val cfg = store.current
                        if (!cfg.sliderEnabled) return
                        val requested = param.args[0] as? Float ?: return
                        val from = getFrom.invoke(s) as Float
                        if (!SliderMath.isSpeedRange(from, requested)) return
                        materialSlider = WeakReference(s)
                        val step = getStep?.invoke(s) as? Float ?: 0f
                        val ceil = SliderMath.snapToStep(from, step, SpeedPolicy.ceiling(cfg))
                        param.args[0] = ceil
                        Diag.add("[SLIDER-EXTEND] material Slider setValueTo ${SpeedPolicy.fmt(requested)} -> ${SpeedPolicy.fmt(ceil)}")
                    } catch (t: Throwable) {
                        SLog.w("material setValueTo hook", t)
                    }
                }
            })
        }

        val addListener = cls.methods.firstOrNull { it.name == "addOnChangeListener" && it.parameterTypes.size == 1 }
        if (addListener != null) {
            XposedBridge.hookMethod(addListener, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        hookMaterialListener(param.args[0] ?: return)
                    } catch (t: Throwable) {
                        SLog.w("material listener hook", t)
                    }
                }
            })
        }
        return "material Slider: hooked (setValueTo ${if (setTo != null) "ok" else "renamed"}, " +
            "listener ${if (addListener != null) "ok" else "renamed, using getValue()"})"
    }

    private fun hookMaterialListener(listener: Any) {
        val cls = listener.javaClass
        if (!hookedClasses.add(cls)) return
        var c: Class<*>? = cls
        var hooked = 0
        while (c != null && c != Any::class.java) {
            // onValueChange(Slider, float, boolean); by name if kept, else by that exact shape
            c.declaredMethods.filter {
                it.parameterTypes.size == 3 && it.parameterTypes[1] == java.lang.Float.TYPE &&
                    it.parameterTypes[2] == java.lang.Boolean.TYPE && it.returnType == Void.TYPE &&
                    (it.name == "onValueChange" || !Modifier.isStatic(it.modifiers))
            }.forEach { m ->
                m.isAccessible = true
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val v = param.args[1] as? Float ?: return
                            val fromUser = param.args[2] as? Boolean ?: true
                            if (fromUser) recordMove(v, "material")
                        } catch (t: Throwable) {
                            SLog.w("material onValueChange hook", t)
                        }
                    }
                })
                hooked++
            }
            if (hooked > 0) break
            c = c.superclass
        }
        if (hooked > 0) Diag.add("[SLIDER] hooked material listener ${cls.name} ($hooked method(s))")
    }

    // ---------------------------------------------------------------------------------------
    // 3. Jetpack Compose Slider
    // ---------------------------------------------------------------------------------------

    private fun installCompose(cl: ClassLoader, store: ConfigStore): String {
        val classes = listOf("androidx.compose.material3.SliderKt", "androidx.compose.material.SliderKt")
            .mapNotNull { runCatching { Class.forName(it, false, cl) }.getOrNull() }
        if (classes.isEmpty()) return "compose SliderKt: not present by name"
        var count = 0
        for (cls in classes) {
            cls.declaredMethods.filter { it.name == "Slider" && Modifier.isStatic(it.modifiers) }.forEach { m ->
                m.isAccessible = true
                XposedBridge.hookMethod(m, composeSliderHook(m, store))
                count++
            }
        }
        return "compose SliderKt: hooked $count Slider overload(s)"
    }

    private fun composeSliderHook(m: Method, store: ConfigStore) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                val args = param.args
                var rangeIdx = -1
                var from = 0f
                var to = 0f
                for (i in args.indices) {
                    val r = twoFloatRange(args[i] ?: continue) ?: continue
                    rangeIdx = i; from = r.first; to = r.second
                    break
                }
                if (rangeIdx < 0) return
                val stepsIdx = stepsIndex(args, rangeIdx)
                val key = "${SpeedPolicy.fmt(from)}..${SpeedPolicy.fmt(to)}"
                if (seenRanges.add(key)) {
                    Diag.add("[SLIDER] compose ${m.declaringClass.simpleName}.Slider range=$key " +
                        "steps=${if (stepsIdx >= 0) args[stepsIdx] else "?"}" +
                        if (SliderMath.isSpeedRange(from, to)) "  <- speed slider" else "")
                }
                val cfg = store.current
                if (!cfg.sliderEnabled || !SliderMath.isSpeedRange(from, to)) return
                val ceil = SpeedPolicy.ceiling(cfg)
                args[rangeIdx] = rangeLike(args[rangeIdx]!!, from, ceil)
                var stepsNote = ""
                if (stepsIdx >= 0) {
                    val old = args[stepsIdx] as Int
                    val new = SliderMath.newComposeSteps(old, from, to, ceil)
                    args[stepsIdx] = new
                    stepsNote = " steps $old -> $new"
                }
                // onValueChange is always the second parameter of every Slider overload.
                if (args.size > 1 && args[1] != null) args[1] = wrapOnValueChange(args[1]!!)
                if (seenRanges.add("extended:$key")) {
                    Diag.add("[SLIDER-EXTEND] compose range $key -> ${SpeedPolicy.fmt(from)}..${SpeedPolicy.fmt(ceil)}$stepsNote")
                }
            } catch (t: Throwable) {
                SLog.w("compose Slider hook (call passes through unchanged)", t)
            }
        }
    }

    /** (min, max) if [a] is a two-float value object such as kotlin's ClosedFloatRange. */
    private fun twoFloatRange(a: Any): Pair<Float, Float>? {
        if (a is Number || a is String || a is Boolean || a is CharSequence) return null
        val c = a.javaClass
        if (c.isArray || c.isPrimitive) return null
        val inst = c.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        if (inst.size != 2 || inst.any { it.type != java.lang.Float.TYPE }) return null
        if (c.declaredConstructors.none { k -> k.parameterTypes.size == 2 && k.parameterTypes.all { it == java.lang.Float.TYPE } }) return null
        // A lambda that happens to capture two floats has the same fields and constructor. A range
        // additionally exposes its ends through zero-argument getters returning Float.
        val floatGetters = c.methods.count {
            it.parameterTypes.isEmpty() && !Modifier.isStatic(it.modifiers) &&
                (it.returnType == java.lang.Float::class.java || it.returnType == java.lang.Float.TYPE)
        }
        if (floatGetters < 2) return null
        inst.forEach { it.isAccessible = true }
        val v0 = inst[0].getFloat(a)
        val v1 = inst[1].getFloat(a)
        return min(v0, v1) to max(v0, v1)
    }

    private fun rangeLike(orig: Any, from: Float, to: Float): Any {
        val k = orig.javaClass.getDeclaredConstructor(java.lang.Float.TYPE, java.lang.Float.TYPE)
        k.isAccessible = true
        return k.newInstance(from, to)
    }

    /**
     * The `steps` argument: the single small Int among the real parameters. The trailing three
     * arguments of a composable ($composer, $changed, $default) are excluded, since $changed can
     * be a small number too.
     */
    private fun stepsIndex(args: Array<Any?>, rangeIdx: Int): Int {
        val limit = args.size - 3
        val candidates = args.indices.filter { i ->
            i < limit && i != rangeIdx && (args[i] as? Int)?.let { it in 0..200 } == true
        }
        return if (candidates.size == 1) candidates[0] else -1
    }

    private fun wrapOnValueChange(orig: Any): Any {
        proxies[orig]?.let { return it }
        val interfaces = orig.javaClass.interfaces
        if (interfaces.isEmpty()) return orig
        val handler = InvocationHandler { proxy, method, margs ->
            if (method.declaringClass == Any::class.java) {
                when (method.name) {
                    "equals" -> (margs?.get(0) === proxy) || orig == margs?.get(0)
                    "hashCode" -> orig.hashCode()
                    else -> orig.toString()
                }
            } else {
                (margs?.singleOrNull() as? Float)?.let { recordMove(it, "compose") }
                if (margs == null) method.invoke(orig) else method.invoke(orig, *margs)
            }
        }
        val proxy = Proxy.newProxyInstance(orig.javaClass.classLoader, interfaces, handler)
        proxies[orig] = proxy
        return proxy
    }

    // ---------------------------------------------------------------------------------------

    private fun idName(v: View): String = try {
        if (v.id == View.NO_ID) "(no id)" else v.resources.getResourceEntryName(v.id)
    } catch (t: Throwable) {
        "0x" + Integer.toHexString(v.id)
    }

    private fun noArg(cls: Class<*>, name: String, ret: Class<*>): Method? =
        runCatching { cls.getMethod(name) }.getOrNull()?.takeIf { it.returnType == ret }

    private fun oneArg(cls: Class<*>, name: String, p: Class<*>): Method? =
        runCatching { cls.getMethod(name, p) }.getOrNull()
}
