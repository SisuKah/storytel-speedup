package io.github.sisukah.storytelspeedmod

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure arithmetic for extending Storytel's custom-speed slider (no Android, unit tested).
 *
 * Storytel's slider runs 0.5 … 2.0 in steps of 0.1. Whatever widget draws it, the same two facts
 * identify it and let us extend it while keeping the step size the user already knows.
 */
object SliderMath {

    const val STORYTEL_MIN = 0.5f
    const val STORYTEL_MAX = 2.0f
    const val STORYTEL_STEP = 0.1f

    /** True when a float range is Storytel's speed range (tolerant of float noise). */
    fun isSpeedRange(from: Float, to: Float): Boolean =
        near(from, STORYTEL_MIN, 0.01f) && near(to, STORYTEL_MAX, 0.01f)

    /**
     * android.widget.SeekBar keeps integer progress 0..max; the app maps it linearly onto
     * 0.5 … 2.0, so the step is 1.5/max (max = 15 for 0.1 steps). Returns the max that reaches
     * [ceiling] at the same step, or null if [oldMax] cannot be that mapping.
     */
    fun newSeekBarMax(oldMax: Int, ceiling: Float): Int? {
        if (oldMax <= 0 || ceiling <= STORYTEL_MAX) return null
        val step = (STORYTEL_MAX - STORYTEL_MIN) / oldMax
        return ((ceiling - STORYTEL_MIN) / step).roundToInt()
    }

    /** Speed a SeekBar progress value means under that same linear mapping. */
    fun seekBarSpeed(progress: Int, max: Int): Float =
        if (max <= 0) STORYTEL_MIN else STORYTEL_MIN + progress * ((STORYTEL_MAX - STORYTEL_MIN) / max)

    /**
     * Compose's Slider takes `steps` = number of points strictly between the ends (14 for 0.1
     * steps over 0.5..2.0). Returns the steps that keep the same increment up to [ceiling];
     * 0 (continuous) stays 0.
     */
    fun newComposeSteps(oldSteps: Int, from: Float, to: Float, ceiling: Float): Int {
        if (oldSteps <= 0) return oldSteps
        val increment = (to - from) / (oldSteps + 1)
        if (increment <= 0f) return oldSteps
        val intervals = ((ceiling - from) / increment).roundToInt()
        return (intervals - 1).coerceAtLeast(oldSteps)
    }

    /**
     * Decides whether a speed Storytel just built is really a clamped version of what the user
     * chose on the slider. The slider intent is trusted only when it is beyond Storytel's own
     * maximum (the only case Storytel would clamp), recent, and the built value is not already
     * what the slider asked for.
     */
    fun clampBypass(built: Float, sliderIntent: Float?, ageMs: Long, windowMs: Long, ceiling: Float): Float? {
        if (sliderIntent == null || ageMs < 0 || ageMs > windowMs) return null
        if (sliderIntent <= STORYTEL_MAX + 0.005f) return null
        if (near(built, sliderIntent, 0.005f)) return null
        if (built > STORYTEL_MAX + 0.005f) return null   // not clamped; leave it alone
        return sliderIntent.coerceIn(SpeedPolicy.MEDIA3_MIN_SPEED, ceiling)
    }

    /**
     * Material's Slider requires (valueTo - valueFrom) to be a whole number of steps and throws
     * at draw time otherwise. Snap [ceiling] onto the grid the slider already uses.
     */
    fun snapToStep(from: Float, step: Float, ceiling: Float): Float {
        if (step <= 0f || !step.isFinite()) return ceiling
        val n = ((ceiling - from) / step).roundToInt().coerceAtLeast(1)
        return from + n * step
    }

    /** True when the value Storytel built is the one the user just chose on the slider. */
    fun fromSlider(built: Float, sliderIntent: Float?, ageMs: Long, windowMs: Long): Boolean =
        sliderIntent != null && ageMs in 0..windowMs && near(built, sliderIntent, 0.005f)

    fun near(a: Float, b: Float, tol: Float): Boolean = abs(a - b) <= tol
}
