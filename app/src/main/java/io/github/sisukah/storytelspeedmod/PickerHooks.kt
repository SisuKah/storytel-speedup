package io.github.sisukah.storytelspeedmod

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Extends Storytel's "Edit custom speed" wheel picker past 2.0 by acting where Storytel itself
 * decides the picker's top value. Verified by decompiling Storytel 26.35:
 *
 *   GetPlaybackSpeedOptions:
 *     predefined = generateSequence(1.0) { it + 0.25 }.takeWhile { it <= 2.0 }
 *     custom     = generateSequence(0.5) { it + 0.1  }.takeWhile { it <= max }
 *     max        = featureFlags.maximumPlaybackSpeed()     // Firebase Remote Config key
 *                                                          // "maximum_playback_speed"; <= 0 -> 2.0
 *     -> PlaybackSpeedOptions(selectedSpeed, selectedMode, customSpeed, predefinedSpeeds, additionalSpeeds)
 *   PlaybackSpeedViewModel.setSpeed(f, mode, analytics): if (f <= 0) return; service.setSpeed(f)
 *   AudioService: reads EXTRA_PLAYBACK_SPEED, stores it; PlaybackManager builds
 *                 PlaybackParameters(f, 1.0) -> player.   No clamp anywhere in that chain.
 *
 * So the only ceiling is the list the picker is offered. Two independent, name-free ways to raise
 * it (Storytel's own class names are renamed per release, so nothing below depends on one):
 *
 *  A. The flag getter. com.storytel.playbackspeed.ui.viewmodel.PlaybackSpeedViewModel keeps its
 *     name (Hilt generates classes that reference it). When one is constructed: its injected
 *     use-case fields (interface-typed) -> each implementation's fields -> the dependency whose
 *     class declares exactly ONE zero-arg method returning double. That method is
 *     maximumPlaybackSpeed(). It is hooked to return at least max_speed, but ONLY when called
 *     from that use case (checked on the stack), so a wrong guess cannot alter anything else.
 *
 *  B. The options object. PlaybackSpeedOptions is a data class whose 5-arg constructor is
 *     (float, Mode, float, List, List) where Mode has static members named PREDEFINED and CUSTOM
 *     (member names survive R8) and whose toString() still starts with "PlaybackSpeedOptions(".
 *     Found by that shape, verified by that string, and its 5th argument (additionalSpeeds) is
 *     replaced with 0.5 … max_speed in 0.1 steps.
 *
 * Either one is enough; together they are idempotent. Once either has handed Storytel a longer
 * list, the ladder stands down (Media3Hooks): with a native 0.5 … 4.0 picker there is nothing
 * left to remap. That fact is persisted (picker_confirmed) so it also holds right after a restart.
 */
object PickerHooks {

    const val VIEW_MODEL = "com.storytel.playbackspeed.ui.viewmodel.PlaybackSpeedViewModel"
    private const val OPTIONS_TO_STRING_PREFIX = "PlaybackSpeedOptions("

    /** True once a longer list has actually reached Storytel (or did so in an earlier run). */
    @Volatile
    var extended: Boolean = false
        private set

    private val optionsHooked = AtomicBoolean(false)
    private val flagHooked = AtomicBoolean(false)
    private val hookedMethods = Collections.synchronizedSet(HashSet<Method>())
    private val notes = Collections.synchronizedList(ArrayList<String>())
    @Volatile private var lastAnnouncedTop = -1f

    /** Fast part: hook the view model by name. Safe to call at process start. */
    fun install(cl: ClassLoader, store: ConfigStore) {
        val cfg = store.current
        extended = cfg.pickerConfirmed
        if (!cfg.pickerEnabled) {
            Diag.picker = "off (picker=off)"
            return
        }
        try {
            val vm = Class.forName(VIEW_MODEL, false, cl)
            XposedBridge.hookAllConstructors(vm, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        onViewModelCreated(param.thisObject ?: return, store)
                    } catch (t: Throwable) {
                        SLog.w("picker: view model hook", t)
                    }
                }
            })
            note("A: PlaybackSpeedViewModel hooked; flag getter is resolved when the speed sheet opens")
        } catch (t: Throwable) {
            note("A: $VIEW_MODEL not found by name")
        }
    }

    /** Slow part: scans the app's classes for the options data class. Run on a background thread. */
    fun installOptionsHook(cl: ClassLoader, store: ConfigStore) {
        if (!store.current.pickerEnabled || !optionsHooked.compareAndSet(false, true)) return
        try {
            val r = ClassScanner.findFirst(cl, "PlaybackSpeedOptions") { c -> optionsCtor(c) != null }
            val cls = r.cls
            if (cls == null) {
                note("B: PlaybackSpeedOptions shape not found (${r.note})")
                return
            }
            val ctor = optionsCtor(cls) ?: return
            val verified = verifyOptions(cls, ctor)   // must run BEFORE the hook is installed
            XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        onOptionsConstructed(param, store)
                    } catch (t: Throwable) {
                        SLog.w("picker: options hook (call passes through unchanged)", t)
                    }
                }
            })
            note("B: ${cls.name} constructor hooked (${if (verified) "verified by toString" else "shape only, unverified"}; " +
                "${r.tested} classes tested, ${r.ms}ms)")
        } catch (t: Throwable) {
            note("B: failed ($t)")
        }
    }

    // ---- A -----------------------------------------------------------------------------------

    private fun onViewModelCreated(vm: Any, store: ConfigStore) {
        if (flagHooked.get()) return
        var hooked = 0
        for (f in vm.javaClass.declaredFields) {
            if (Modifier.isStatic(f.modifiers) || !f.type.isInterface) continue
            f.isAccessible = true
            val useCase = f.get(vm) ?: continue
            val useCaseClass = useCase.javaClass
            for (g in useCaseClass.declaredFields) {
                if (Modifier.isStatic(g.modifiers) || g.type.isPrimitive) continue
                g.isAccessible = true
                val dep = g.get(useCase) ?: continue
                val doubles = dep.javaClass.declaredMethods.filter {
                    !Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() && it.returnType == java.lang.Double.TYPE
                }
                if (doubles.size != 1) continue
                val m = doubles[0]
                if (!hookedMethods.add(m)) continue
                m.isAccessible = true
                XposedBridge.hookMethod(m, flagGetterHook(m, useCaseClass.name, store))
                hooked++
                Diag.add("[PICKER] flag getter candidate ${m.declaringClass.name}.${m.name}() (only honoured when called from ${useCaseClass.name})")
            }
        }
        if (hooked > 0) flagHooked.set(true)
        else Diag.add("[PICKER] A: no dependency with exactly one zero-arg double method under the view model")
    }

    private fun flagGetterHook(m: Method, useCaseClass: String, store: ConfigStore) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                val cfg = store.current
                if (!cfg.pickerEnabled) return
                val v = param.result as? Double ?: return
                // Only the options builder may see the raised value.
                val fromUseCase = Thread.currentThread().stackTrace.any { it.className == useCaseClass }
                if (!fromUseCase) return
                // A maximum playback speed is either unset (<= 0, Storytel then uses 2.0) or a
                // plausible speed. Anything else means this is not the flag: leave it alone.
                if (v > 0.0 && (v < 0.5 || v > 10.0)) return
                val want = SpeedPolicy.ceiling(cfg).toDouble()
                if (v >= want) return
                param.result = want
                markExtended(store, want.toFloat())
                Diag.add("[PICKER] maximum_playback_speed ${SpeedPolicy.fmt(v.toFloat())} -> ${SpeedPolicy.fmt(want.toFloat())} (via ${m.declaringClass.name}.${m.name})")
            } catch (t: Throwable) {
                SLog.w("picker: flag getter hook", t)
            }
        }
    }

    // ---- B -----------------------------------------------------------------------------------

    /** The (float, Mode, float, List, List) constructor, or null if this is not the class. */
    internal fun optionsCtor(c: Class<*>): Constructor<*>? {
        if (c.isInterface || c.isArray || c.isPrimitive || Modifier.isAbstract(c.modifiers)) return null
        val ctors = try { c.declaredConstructors } catch (t: Throwable) { return null }
        return ctors.firstOrNull { k ->
            val p = k.parameterTypes
            p.size == 5 && p[0] == java.lang.Float.TYPE && p[2] == java.lang.Float.TYPE &&
                p[3] == java.util.List::class.java && p[4] == java.util.List::class.java && isModeLike(p[1])
        }
    }

    /** Storytel's mode type: static members of its own type named PREDEFINED and CUSTOM. */
    internal fun isModeLike(c: Class<*>): Boolean {
        if (c.isPrimitive || c.isArray || c == Any::class.java) return false
        val names = try {
            c.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.type == c }.map { it.name }
        } catch (t: Throwable) { return false }
        return names.contains("PREDEFINED") && names.contains("CUSTOM")
    }

    private fun verifyOptions(cls: Class<*>, ctor: Constructor<*>): Boolean = try {
        val modeType = ctor.parameterTypes[1]
        val predefined = modeType.declaredFields.first {
            Modifier.isStatic(it.modifiers) && it.type == modeType && it.name == "PREDEFINED"
        }.apply { isAccessible = true }.get(null)
        ctor.isAccessible = true
        val probe = ctor.newInstance(1.0f, predefined, 1.0f, emptyList<Any>(), emptyList<Any>())
        probe.toString().startsWith(OPTIONS_TO_STRING_PREFIX)
    } catch (t: Throwable) {
        false
    }

    private fun onOptionsConstructed(param: XC_MethodHook.MethodHookParam, store: ConfigStore) {
        val cfg = store.current
        if (!cfg.pickerEnabled) return
        val theirs = param.args[4] as? List<*>
        val theirTop = PickerMath.lastSpeed(theirs) ?: 0f
        val top = max(SpeedPolicy.ceiling(cfg), theirTop)
        if (top <= theirTop + 0.005f) return          // nothing to add
        param.args[4] = PickerMath.customSpeeds(top)
        markExtended(store, top)
        if (!SliderMath.near(top, lastAnnouncedTop, 0.005f)) {
            lastAnnouncedTop = top
            Diag.add("[PICKER] custom speeds ${SpeedPolicy.fmt(PickerMath.STORYTEL_MIN)}..${SpeedPolicy.fmt(theirTop)} " +
                "-> ${SpeedPolicy.fmt(PickerMath.STORYTEL_MIN)}..${SpeedPolicy.fmt(top)} (${(param.args[4] as List<*>).size} entries)")
        }
    }

    // ------------------------------------------------------------------------------------------

    private fun markExtended(store: ConfigStore, top: Float) {
        if (!extended) {
            extended = true
            Diag.add("[PICKER] native picker extended to ${SpeedPolicy.fmt(top)}x: the ladder stands down")
        }
        if (!store.current.pickerConfirmed) store.apply("${Keys.PICKER_CONFIRMED}=true")
    }

    private fun note(s: String) {
        notes.add(s)
        Diag.picker = notes.joinToString("\n  ")
        SLog.i("picker: $s")
    }
}
