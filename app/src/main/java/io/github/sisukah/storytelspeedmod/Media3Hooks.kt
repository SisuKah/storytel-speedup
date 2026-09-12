package io.github.sisukah.storytelspeedmod

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Member
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Installs the Media3 hooks once. Behaviour is decided at CALL time from [ConfigStore.current],
 * so mode / target / discovery / hook_point can be changed with a broadcast while Storytel is
 * running; no rebuild, no restart. (Only the cls_ / m_ / f_ overrides need a process restart,
 * because they are used at resolution time.)
 *
 * Hook points:
 *  [B, default]  ExoPlayerImpl.setPlaybackParameters(PlaybackParameters)  -> substitute args[0]
 *  [B fallback]  BasePlayer.setPlaybackSpeed(float)                        -> substitute args[0]
 *                (only if the ExoPlayerImpl funnel could not be resolved)
 *  [A]           PlaybackParameters.<init>(float speed, float pitch)        -> substitute args[0]
 *                (only when hook_point=ctor)
 *  discovery     ExoPlayerImpl.<init>, getPlaybackParameters(), ForwardingPlayer /
 *                SimpleExoPlayer / MediaController setters -> log only, never modify
 */
object Media3Hooks {

    /** Set while WE construct a PlaybackParameters, so the ctor hook ignores that call. */
    private val selfConstruct = ThreadLocal<Boolean>()
    private val playerCount = AtomicInteger()

    fun install(t: Media3Targets, store: ConfigStore) {
        val funnel = t.setPlaybackParameters
        if (funnel != null) {
            XposedBridge.hookMethod(funnel, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        onSetPlaybackParameters(param, funnel, t, store)
                    } catch (e: Throwable) {
                        SLog.e("hook error in ${Discovery.sig(funnel)} (call passes through unchanged)", e)
                    }
                }
            })
            SLog.i("hooked [funnel] ${Discovery.sig(funnel)}")
        }

        val setSpeed = t.setPlaybackSpeed
        if (setSpeed != null) {
            val substituteHere = funnel == null
            XposedBridge.hookMethod(setSpeed, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        onSetPlaybackSpeed(param, setSpeed, t, store, substituteHere)
                    } catch (e: Throwable) {
                        SLog.e("hook error in ${Discovery.sig(setSpeed)} (call passes through unchanged)", e)
                    }
                }
            })
            SLog.i("hooked [entry${if (substituteHere) "+fallback-substitution" else ""}] ${Discovery.sig(setSpeed)}")
        } else if (funnel == null) {
            SLog.w("no player funnel and no BasePlayer.setPlaybackSpeed resolved: the constructor " +
                "hook will substitute automatically (this is the obfuscated-Media3 path)")
        }

        val ctor = t.ppCtor
        if (ctor != null) {
            XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        onConstruct(param, ctor, t, store)
                    } catch (e: Throwable) {
                        SLog.e("hook error in ${Discovery.sig(ctor)} (call passes through unchanged)", e)
                    }
                }
            })
            SLog.i("hooked [ctor] ${Discovery.sig(ctor)} " +
                if (t.ctorOnly) "(ACTIVE: no player funnel resolved, ctor substitutes)"
                else "(active with hook_point=ctor)")
        }

        // ---- discovery-only hooks (never modify anything) ------------------------------------
        t.playerImplClass?.let { impl ->
            XposedBridge.hookAllConstructors(impl, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val n = playerCount.incrementAndGet()
                    val cfg = store.current
                    val header = "[PLAYER-CREATED] ${impl.name} ${Discovery.id(param.thisObject)} (#$n in this process) thread=${Thread.currentThread().name}"
                    Diag.add(header)
                    if (!cfg.discovery) return
                    SLog.i(Discovery.block(header, Discovery.callerFrames(cfg.discoveryFrames)))
                }
            })
        }

        t.getPlaybackParameters?.let { getter ->
            XposedBridge.hookMethod(getter, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val cfg = store.current
                    if (!cfg.discovery || !cfg.discoveryGetters) return
                    SLog.i(Discovery.block(
                        "[GET] ${Discovery.sig(getter)} player=${Discovery.id(param.thisObject)} -> ${t.describe(param.result)}",
                        Discovery.callerFrames(cfg.discoveryFrames)))
                }
            })
        }

        (t.extraEntryPoints + t.floatSetterCandidates).forEach { m ->
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val cfg = store.current
                    val header = "[ENTRY] ${Discovery.sig(m)} this=${param.thisObject?.javaClass?.name} ${Discovery.id(param.thisObject)} " +
                        "thread=${Thread.currentThread().name} arg=${t.describe(param.args.getOrNull(0))}"
                    Diag.add(header)
                    if (!cfg.discovery) return
                    SLog.i(Discovery.block(header, Discovery.callerFrames(cfg.discoveryFrames)))
                }
            })
        }
        if (t.extraEntryPoints.isNotEmpty() || t.floatSetterCandidates.isNotEmpty()) {
            SLog.i("hooked [discovery-only] ${(t.extraEntryPoints + t.floatSetterCandidates).joinToString { Discovery.sig(it) }}")
        }
    }

    // ------------------------------------------------------------------------------------------

    private fun frames(cfg: Config): List<String> {
        val forFilter = if (cfg.callerFilter.isNotEmpty()) 30 else 0
        val forLog = if (cfg.discovery) cfg.discoveryFrames else 0
        return Discovery.callerFrames(max(forFilter, forLog))
    }

    private fun callerOk(cfg: Config, frames: List<String>): Boolean =
        cfg.callerFilter.isEmpty() || frames.any { it.contains(cfg.callerFilter) }

    /**
     * Storytel's own slider takes precedence over every mode:
     *  - if the user chose a speed beyond 2.0 on it and Storytel clamped that back, restore it;
     *  - if the value IS what the user just chose on the slider, take it as is (no rung applies,
     *    so sliding to exactly 2.0 plays 2.0 while the 2x button still plays its rung).
     * Null when the slider is not involved.
     */
    private fun sliderDecision(inSpeed: Float, cfg: Config): SpeedPolicy.Decision? {
        if (!cfg.sliderEnabled) return null
        SliderHooks.clampBypassFor(inSpeed, cfg)?.let {
            return SpeedPolicy.Decision(it, true, "slider chose ${SpeedPolicy.fmt(it)}, Storytel clamped it to ${SpeedPolicy.fmt(inSpeed)}")
        }
        if (SliderHooks.cameFromSlider(inSpeed)) {
            return SpeedPolicy.Decision(inSpeed, false, "chosen on Storytel's slider, taken as is")
        }
        return null
    }

    private fun onSetPlaybackParameters(param: XC_MethodHook.MethodHookParam, m: Member, t: Media3Targets, store: ConfigStore) {
        val cfg = store.current
        val pp = param.args[0] ?: return   // null means PlaybackParameters.DEFAULT inside Media3; leave it
        val inSpeed = t.speedOf(pp)
        val inPitch = t.pitchOf(pp)
        val frames = frames(cfg)
        val decision = if (cfg.hookPoint == HookPoint.PLAYER) {
            sliderDecision(inSpeed, cfg) ?: SpeedPolicy.decide(inSpeed, cfg, callerOk(cfg, frames))
        } else {
            SpeedPolicy.Decision(inSpeed, false, "hook_point=ctor substitutes in the constructor")
        }
        if (decision.changed) {
            param.args[0] = construct(t, decision.speed, inPitch)
        }
        val header = "[SET] ${Discovery.sig(m)} player=${Discovery.id(param.thisObject)} thread=${Thread.currentThread().name} " +
            "in=${SpeedPolicy.fmt(inSpeed)}x pitch=${SpeedPolicy.fmt(inPitch)} -> out=${SpeedPolicy.fmt(decision.speed)}x " +
            "(${decision.reason}; mode=${cfg.mode.key} hook_point=${cfg.hookPoint.key})"
        Diag.add(header)
        if (cfg.discovery) SLog.i(Discovery.block(header, frames.take(cfg.discoveryFrames)))
        else if (decision.changed) SLog.i(header)
    }

    private fun onSetPlaybackSpeed(param: XC_MethodHook.MethodHookParam, m: Member, t: Media3Targets, store: ConfigStore, substituteHere: Boolean) {
        val cfg = store.current
        val inSpeed = (param.args[0] as? Float) ?: return
        val frames = frames(cfg)
        val decision = if (substituteHere && cfg.hookPoint == HookPoint.PLAYER) {
            sliderDecision(inSpeed, cfg) ?: SpeedPolicy.decide(inSpeed, cfg, callerOk(cfg, frames))
        } else {
            SpeedPolicy.Decision(inSpeed, false, "entry point; substitution happens downstream")
        }
        if (decision.changed) param.args[0] = decision.speed
        val header = "[ENTRY] ${Discovery.sig(m)} this=${param.thisObject?.javaClass?.name} ${Discovery.id(param.thisObject)} " +
            "thread=${Thread.currentThread().name} in=${SpeedPolicy.fmt(inSpeed)}x -> out=${SpeedPolicy.fmt(decision.speed)}x (${decision.reason})"
        Diag.add(header)
        if (cfg.discovery) SLog.i(Discovery.block(header, frames.take(cfg.discoveryFrames)))
        else if (decision.changed) SLog.i(header)
    }

    private fun onConstruct(param: XC_MethodHook.MethodHookParam, c: Member, t: Media3Targets, store: ConfigStore) {
        if (selfConstruct.get() == true) return
        val cfg = store.current
        val inSpeed = (param.args[0] as? Float) ?: return
        val pitch = (param.args[1] as? Float) ?: 1f
        // Media3 rebuilds the parameters we substituted, so our own output re-enters here.
        if (!SpeedPolicy.isOurOutput(inSpeed, cfg)) Diag.observe(inSpeed)
        val wantLog = cfg.discovery && cfg.discoveryCtor
        // The ctor substitutes when explicitly selected (hook_point=ctor) OR automatically when no
        // player-level entry point was resolved (the obfuscated-Media3 path).
        val active = cfg.hookPoint == HookPoint.CTOR || t.ctorOnly
        if (!wantLog && !active) return
        val frames = frames(cfg)
        val fromSlider = if (active) sliderDecision(inSpeed, cfg) else null
        val decision = when {
            fromSlider != null -> fromSlider
            active -> SpeedPolicy.decide(inSpeed, cfg, callerOk(cfg, frames))
            else -> SpeedPolicy.Decision(inSpeed, false, "hook_point=player; ctor logs only")
        }
        if (decision.changed) param.args[0] = decision.speed
        val why = if (fromSlider == null && active && t.ctorOnly && cfg.hookPoint != HookPoint.CTOR) "auto-ctor; ${decision.reason}" else decision.reason
        val header = "[CTOR] ${Discovery.sig(c)} thread=${Thread.currentThread().name} " +
            "in=${SpeedPolicy.fmt(inSpeed)}x pitch=${SpeedPolicy.fmt(pitch)} -> out=${SpeedPolicy.fmt(decision.speed)}x ($why)"
        if (decision.changed || !SpeedPolicy.approxEqual(inSpeed, 1.0f)) Diag.add(header)
        if (wantLog) SLog.i(Discovery.block(header, frames.take(cfg.discoveryFrames)))
        else if (decision.changed) SLog.i(header)
    }

    private fun construct(t: Media3Targets, speed: Float, pitch: Float): Any {
        selfConstruct.set(true)
        try {
            return t.newPlaybackParameters(speed, pitch)
        } finally {
            selfConstruct.set(false)
        }
    }
}
