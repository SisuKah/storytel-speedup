package io.github.sisukah.storytelspeedmod

import java.util.Locale
import kotlin.math.abs

/**
 * Pure decision logic (no Android, no Xposed) so it can be unit tested on the JVM.
 *
 *   MODE_REMAP_2X  : incoming == remap_from  ->  target ; everything else untouched
 *   MODE_FORCE_TARGET : any incoming          ->  target
 *   OFF            : nothing changes (discovery still logs)
 */
object SpeedPolicy {

    /** Media3 DefaultAudioSink.MIN_PLAYBACK_SPEED / MAX_PLAYBACK_SPEED (verified in source). */
    const val MEDIA3_MIN_SPEED = 0.1f
    const val MEDIA3_MAX_SPEED = 8.0f

    data class Decision(val speed: Float, val changed: Boolean, val reason: String)

    /** The configured target, clamped to [0.1, max_speed] where max_speed itself is <= 8.0. */
    fun effectiveTarget(cfg: Config): Float {
        val cap = cfg.maxSpeed.coerceIn(MEDIA3_MIN_SPEED, MEDIA3_MAX_SPEED)
        return cfg.target.coerceIn(MEDIA3_MIN_SPEED, cap)
    }

    fun decide(incoming: Float, cfg: Config, callerMatches: Boolean = true): Decision {
        if (incoming.isNaN() || incoming.isInfinite() || incoming <= 0f) {
            return Decision(incoming, false, "invalid incoming speed, untouched")
        }
        if (!callerMatches) {
            return Decision(incoming, false, "caller filter not matched, untouched")
        }
        val target = effectiveTarget(cfg)
        return when (cfg.mode) {
            Mode.OFF -> Decision(incoming, false, "mode=off")
            Mode.REMAP_2X ->
                if (approxEqual(incoming, cfg.remapFrom)) {
                    Decision(target, !approxEqual(target, incoming), "remap ${fmt(cfg.remapFrom)} -> ${fmt(target)}")
                } else {
                    Decision(incoming, false, "passthrough (not ${fmt(cfg.remapFrom)})")
                }
            Mode.FORCE_TARGET -> Decision(target, !approxEqual(target, incoming), "force -> ${fmt(target)}")
        }
    }

    fun approxEqual(a: Float, b: Float): Boolean = abs(a - b) < 0.005f

    fun fmt(f: Float): String = String.format(Locale.ROOT, "%.2f", f)
}
