package io.github.sisukah.storytelspeedmod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SliderMathTest {

    @Test
    fun recognisesStorytelsSpeedRangeOnly() {
        assertTrue(SliderMath.isSpeedRange(0.5f, 2.0f))
        assertTrue(SliderMath.isSpeedRange(0.5000001f, 1.9999999f))
        assertFalse("a 0..1 default slider is not it", SliderMath.isSpeedRange(0f, 1f))
        assertFalse("a sleep timer is not it", SliderMath.isSpeedRange(0f, 120f))
        assertFalse("already extended", SliderMath.isSpeedRange(0.5f, 4.0f))
    }

    @Test
    fun seekBarMaxKeepsTheSameStep() {
        // 0.5..2.0 over max 15 is a 0.1 step; reaching 4.0 at 0.1 needs 35
        assertEquals(35, SliderMath.newSeekBarMax(15, 4.0f))
        assertEquals(25, SliderMath.newSeekBarMax(15, 3.0f))
        // a finer slider (0.01 steps, max 150) scales the same way
        assertEquals(350, SliderMath.newSeekBarMax(150, 4.0f))
        assertNull("nothing to extend", SliderMath.newSeekBarMax(15, 2.0f))
        assertNull(SliderMath.newSeekBarMax(0, 4.0f))
    }

    @Test
    fun seekBarProgressMapsBackToSpeedUnderTheOriginalMax() {
        assertEquals(0.5f, SliderMath.seekBarSpeed(0, 15), 1e-5f)
        assertEquals(2.0f, SliderMath.seekBarSpeed(15, 15), 1e-5f)
        assertEquals(2.5f, SliderMath.seekBarSpeed(20, 15), 1e-5f)   // past the original end
        assertEquals(4.0f, SliderMath.seekBarSpeed(35, 15), 1e-5f)
    }

    @Test
    fun composeStepsKeepTheSameIncrement() {
        // 14 steps between 0.5 and 2.0 = 15 intervals of 0.1; up to 4.0 that is 35 intervals = 34 steps
        assertEquals(34, SliderMath.newComposeSteps(14, 0.5f, 2.0f, 4.0f))
        assertEquals(24, SliderMath.newComposeSteps(14, 0.5f, 2.0f, 3.0f))
        assertEquals("continuous stays continuous", 0, SliderMath.newComposeSteps(0, 0.5f, 2.0f, 4.0f))
        assertEquals("never fewer steps than before", 14, SliderMath.newComposeSteps(14, 0.5f, 2.0f, 2.0f))
    }

    @Test
    fun clampBypassRestoresASliderChoiceStorytelClampedTo2() {
        val out = SliderMath.clampBypass(built = 2.0f, sliderIntent = 2.5f, ageMs = 300, windowMs = 2000, ceiling = 4.0f)
        assertEquals(2.5f, out!!, 0f)
    }

    @Test
    fun clampBypassCapsAtTheCeiling() {
        val out = SliderMath.clampBypass(built = 2.0f, sliderIntent = 6.0f, ageMs = 10, windowMs = 2000, ceiling = 4.0f)
        assertEquals(4.0f, out!!, 0f)
    }

    @Test
    fun clampBypassStaysOutWhenNotApplicable() {
        val w = 2000L
        assertNull("no intent", SliderMath.clampBypass(2.0f, null, 0, w, 4f))
        assertNull("intent too old", SliderMath.clampBypass(2.0f, 2.5f, 5000, w, 4f))
        assertNull("intent inside Storytel's own range: nothing was clamped", SliderMath.clampBypass(1.5f, 1.5f, 10, w, 4f))
        assertNull("slider at exactly 2.0 is a real 2.0", SliderMath.clampBypass(2.0f, 2.0f, 10, w, 4f))
        assertNull("Storytel did not clamp: built already equals the intent", SliderMath.clampBypass(2.5f, 2.5f, 10, w, 4f))
        assertNull("built value is above 2.0 so it was not clamped", SliderMath.clampBypass(3.0f, 3.5f, 10, w, 4f))
        assertNull("negative age is bogus", SliderMath.clampBypass(2.0f, 2.5f, -1, w, 4f))
    }

    @Test
    fun snapToStepLandsOnTheSlidersGrid() {
        // 0.5 + n*0.1 must reach a tick, or Material's Slider throws while drawing
        assertEquals(4.0f, SliderMath.snapToStep(0.5f, 0.1f, 4.0f), 1e-5f)
        assertEquals(2.8f, SliderMath.snapToStep(0.5f, 0.1f, 2.75f), 1e-5f)
        assertEquals("continuous slider: unchanged", 2.75f, SliderMath.snapToStep(0.5f, 0f, 2.75f), 0f)
        assertEquals("never collapses onto the start", 0.6f, SliderMath.snapToStep(0.5f, 0.1f, 0.5f), 1e-5f)
    }

    @Test
    fun fromSliderRecognisesTheChosenValueOnlyWhileRecent() {
        assertTrue(SliderMath.fromSlider(2.0f, 2.0f, 100, 2000))
        assertTrue(SliderMath.fromSlider(2.0f, 2.0001f, 100, 2000))
        assertFalse("different value: a button, not the slider", SliderMath.fromSlider(2.0f, 1.5f, 100, 2000))
        assertFalse("too old", SliderMath.fromSlider(2.0f, 2.0f, 5000, 2000))
        assertFalse(SliderMath.fromSlider(2.0f, null, 0, 2000))
    }
}
