package io.github.sisukah.storytelspeedmod

import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * Pure decision logic (no Android, no Xposed) so it can be unit tested on the JVM.
 *
 *   LADDER (default) : each of Storytel's own buttons maps to a faster speed
 *   REMAP_2X         : only `remap_from` (2.0) becomes `target`
 *   FORCE_TARGET     : any speed becomes `target`
 *   OFF              : nothing changes (discovery still logs)
 */
object SpeedPolicy {

    /** Media3 DefaultAudioSink.MIN_PLAYBACK_SPEED / MAX_PLAYBACK_SPEED (verified in source). */
    const val MEDIA3_MIN_SPEED = 0.1f
    const val MEDIA3_MAX_SPEED = 8.0f

    data class Decision(val speed: Float, val changed: Boolean, val reason: String)

    /** Upper bound actually allowed: the configured cap, never above Media3's own limit. */
    fun ceiling(cfg: Config): Float = min(cfg.maxSpeed, MEDIA3_MAX_SPEED).coerceAtLeast(MEDIA3_MIN_SPEED)

    /** Clamps a requested output speed into the allowed range. */
    fun capped(value: Float, cfg: Config): Float = value.coerceIn(MEDIA3_MIN_SPEED, ceiling(cfg))

    /** The configured target, clamped. Used by REMAP_2X and FORCE_TARGET. */
    fun effectiveTarget(cfg: Config): Float = capped(cfg.target, cfg)

    fun decide(incoming: Float, cfg: Config, callerMatches: Boolean = true): Decision {
        if (incoming.isNaN() || incoming.isInfinite() || incoming <= 0f) {
            return Decision(incoming, false, "invalid incoming speed, untouched")
        }
        if (!callerMatches) {
            return Decision(incoming, false, "caller filter not matched, untouched")
        }
        return when (cfg.mode) {
            Mode.OFF -> Decision(incoming, false, "mode=off")

            Mode.LADDER -> {
                val step = cfg.ladder.firstOrNull { approxEqual(incoming, it.from) }
                if (step == null) {
                    Decision(incoming, false, "passthrough (${fmt(incoming)} is not a ladder step)")
                } else {
                    val out = capped(step.to, cfg)
                    Decision(out, !approxEqual(out, incoming), "ladder ${fmt(step.from)} -> ${fmt(out)}")
                }
            }

            Mode.REMAP_2X -> {
                if (approxEqual(incoming, cfg.remapFrom)) {
                    val out = effectiveTarget(cfg)
                    Decision(out, !approxEqual(out, incoming), "remap ${fmt(cfg.remapFrom)} -> ${fmt(out)}")
                } else {
                    Decision(incoming, false, "passthrough (not ${fmt(cfg.remapFrom)})")
                }
            }

            Mode.FORCE_TARGET -> {
                val out = effectiveTarget(cfg)
                Decision(out, !approxEqual(out, incoming), "force -> ${fmt(out)}")
            }
        }
    }

    /**
     * Storytel's steps are 0.25 apart, so the tolerance must stay well below that. It also has to
     * absorb float noise from parsing and from any percentage-based UI maths.
     */
    fun approxEqual(a: Float, b: Float): Boolean = abs(a - b) < 0.005f

    fun fmt(f: Float): String = String.format(Locale.ROOT, "%.2f", f)

    /** One-line human summary of the ladder, for the in-app diagnostics. */
    fun describeLadder(cfg: Config): String {
        if (cfg.mode != Mode.LADDER) return "(not in use, mode=${cfg.mode.key})"
        val ceil = ceiling(cfg)
        val base = if (cfg.ladder.isEmpty()) "(empty — no speed will change)"
        else cfg.ladder.joinToString("  ") {
            val out = capped(it.to, cfg)
            // A rung above the cap is silently truncated, and two rungs can collapse onto the same
            // speed that way, so say so instead of printing a mapping the user never asked for.
            if (it.to > ceil + 0.005f) "${fmt(it.from)}->${fmt(out)}(capped from ${fmt(it.to)}; raise max_speed)"
            else "${fmt(it.from)}->${fmt(out)}"
        }
        val bad = cfg.ladderConflicts
        return if (bad.isEmpty()) base
        else base + "  [dropped, would map twice: " +
            bad.joinToString(", ") { "${fmt(it.from)}->${fmt(it.to)}" } + "]"
    }

    /**
     * True when [v] is a speed this configuration produces, i.e. possibly our own substitution
     * coming back through the constructor after Media3 rebuilt the parameters. Used to keep the
     * observed-speeds list showing Storytel's real button values.
     */
    fun isOurOutput(v: Float, cfg: Config): Boolean = when (cfg.mode) {
        Mode.OFF -> false
        Mode.LADDER -> cfg.ladder.any { approxEqual(v, capped(it.to, cfg)) }
        Mode.REMAP_2X, Mode.FORCE_TARGET -> approxEqual(v, effectiveTarget(cfg))
    }
}
