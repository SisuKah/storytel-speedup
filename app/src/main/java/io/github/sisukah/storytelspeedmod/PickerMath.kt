package io.github.sisukah.storytelspeedmod

import kotlin.math.roundToInt

/** Pure helpers for Storytel's custom-speed picker (unit tested). */
object PickerMath {

    const val STORYTEL_MIN = 0.5f
    const val STORYTEL_STEP = 0.1f

    /**
     * The list Storytel's options builder produces, but up to [top]:
     * generateSequence(0.5) { it + 0.1 }.takeWhile { it <= top }.map { it.toFloat() }.
     * Built from integers so every element equals the float Storytel itself would produce for
     * that decimal (the picker highlights the current value by float equality).
     */
    fun customSpeeds(top: Float): List<Float> {
        val out = ArrayList<Float>()
        val last = (top * 10f).roundToInt()
        for (i in 5..last) out.add(i / 10f)
        return out
    }

    /** Largest float in a list of numbers, or null if there is none. */
    fun lastSpeed(list: List<*>?): Float? =
        list?.mapNotNull { (it as? Number)?.toFloat() }?.maxOrNull()
}
