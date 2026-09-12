package io.github.sisukah.storytelspeedmod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerMathTest {

    @Test
    fun reproducesStorytelsOwnListForTheDefaultCeiling() {
        val l = PickerMath.customSpeeds(2.0f)
        assertEquals(16, l.size)
        assertEquals(0.5f, l.first(), 0f)
        assertEquals(2.0f, l.last(), 0f)
        assertTrue(l.contains(1.9f))
    }

    @Test
    fun extendsToTheTopInTenthSteps() {
        val l = PickerMath.customSpeeds(4.0f)
        assertEquals(36, l.size)
        assertEquals(4.0f, l.last(), 0f)
        assertTrue(l.contains(2.5f))
        assertTrue(l.contains(3.0f))
        for (i in 1 until l.size) assertEquals("uniform 0.1 step", 0.1f, l[i] - l[i - 1], 1e-6f)
    }

    @Test
    fun elementsEqualTheFloatsStorytelWouldProduce() {
        // Storytel: (float) of 0.5 + n*0.1 accumulated in double; ours: i/10f. Must be identical
        // floats or the picker will not highlight the saved custom speed.
        var d = 0.5
        val ours = PickerMath.customSpeeds(4.0f)
        for (i in ours.indices) {
            assertEquals("index $i", d.toFloat(), ours[i], 0f)
            d += 0.1
        }
    }

    @Test
    fun lastSpeedReadsMixedNumberLists() {
        assertEquals(2.0f, PickerMath.lastSpeed(listOf(0.5f, 1.0f, 2.0f))!!, 0f)
        assertEquals(2.0f, PickerMath.lastSpeed(listOf(0.5, 2.0))!!, 0f)
        assertNull(PickerMath.lastSpeed(emptyList<Float>()))
        assertNull(PickerMath.lastSpeed(null))
    }

    @Test
    fun modeLikeRequiresBothMemberNames() {
        assertTrue(PickerHooks.isModeLike(FakeMode::class.java))
        assertTrue(!PickerHooks.isModeLike(FakeOther::class.java))
        assertTrue(!PickerHooks.isModeLike(String::class.java))
    }

    @Test
    fun optionsCtorMatchesTheDataClassShapeOnly() {
        assertTrue(PickerHooks.optionsCtor(FakeOptions::class.java) != null)
        assertNull(PickerHooks.optionsCtor(FakeNotOptions::class.java))
        assertNull(PickerHooks.optionsCtor(String::class.java))
    }

    // Storytel's mode type after R8 stripped the enum: static members of its own type.
    @Suppress("unused")
    class FakeMode private constructor() {
        companion object {
            @JvmField val PREDEFINED = FakeMode()
            @JvmField val CUSTOM = FakeMode()
        }
    }

    @Suppress("unused")
    class FakeOther private constructor() {
        companion object { @JvmField val PREDEFINED = FakeOther() }
    }

    @Suppress("unused")
    class FakeOptions(val a: Float, val b: FakeMode, val c: Float, val d: List<Float>, val e: List<Float>) {
        override fun toString() = "PlaybackSpeedOptions(selectedSpeed=$a)"
    }

    @Suppress("unused")
    class FakeNotOptions(val a: Float, val b: FakeOther, val c: Float, val d: List<Float>, val e: List<Float>)
}
