package io.github.sisukah.storytelspeedmod

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StorytelUiHooksTest {

    private val extras = listOf(2.5f, 3.0f, 3.5f, 4.0f)

    @Test
    fun floatArrayIsExtendedAndSorted() {
        val r = StorytelUiHooks.expand(floatArrayOf(0.5f, 1.0f, 2.0f, 3.0f), extras) as FloatArray
        assertArrayEquals(floatArrayOf(0.5f, 1.0f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f), r, 0f)
    }

    @Test
    fun floatListIsExtended() {
        val r = StorytelUiHooks.expand(listOf(1.0f, 2.0f), extras)
        assertEquals(listOf(1.0f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f), r)
    }

    @Test
    fun unknownShapesAreReturnedUntouched() {
        val weird = listOf("1x", "2x")
        assertSame(weird, StorytelUiHooks.expand(weird, extras))
        val obj = Any()
        assertSame(obj, StorytelUiHooks.expand(obj, extras))
    }
}
