package io.github.sisukah.storytelspeedmod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedPolicyTest {

    private fun cfg(vararg kv: Pair<String, String>) = Config.defaults().with(kv.toMap())

    @Test
    fun remapOnlyTouchesTwoX() {
        val c = cfg(Keys.MODE to "remap", Keys.TARGET to "3.5")
        for (s in listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f)) {
            val d = SpeedPolicy.decide(s, c)
            assertFalse("speed $s must pass through", d.changed)
            assertEquals(s, d.speed, 0f)
        }
        val d = SpeedPolicy.decide(2.0f, c)
        assertTrue(d.changed)
        assertEquals(3.5f, d.speed, 0f)
    }

    @Test
    fun remapToleratesFloatNoise() {
        val c = cfg(Keys.TARGET to "4.0")
        assertTrue(SpeedPolicy.decide(2.0001f, c).changed)
        assertFalse(SpeedPolicy.decide(1.99f, c).changed)
    }

    @Test
    fun remapFromIsConfigurable() {
        val c = cfg(Keys.REMAP_FROM to "1.75", Keys.TARGET to "2.5")
        assertTrue(SpeedPolicy.decide(1.75f, c).changed)
        assertFalse(SpeedPolicy.decide(2.0f, c).changed)
    }

    @Test
    fun forceReplacesEverythingExceptTarget() {
        val c = cfg(Keys.MODE to "force", Keys.TARGET to "3.0")
        assertEquals(3.0f, SpeedPolicy.decide(1.0f, c).speed, 0f)
        assertEquals(3.0f, SpeedPolicy.decide(2.0f, c).speed, 0f)
        assertFalse("already at target: no substitution, no loop", SpeedPolicy.decide(3.0f, c).changed)
    }

    @Test
    fun offNeverChanges() {
        val c = cfg(Keys.MODE to "off", Keys.TARGET to "4.0")
        assertFalse(SpeedPolicy.decide(2.0f, c).changed)
    }

    @Test
    fun targetIsClampedByMaxSpeedAndMedia3Limits() {
        assertEquals(4.0f, SpeedPolicy.effectiveTarget(cfg(Keys.TARGET to "9.0")), 0f)           // max_speed default 4.0
        assertEquals(6.0f, SpeedPolicy.effectiveTarget(cfg(Keys.TARGET to "6.0", Keys.MAX_SPEED to "8.0")), 0f)
        assertEquals(8.0f, SpeedPolicy.effectiveTarget(cfg(Keys.TARGET to "20", Keys.MAX_SPEED to "20")), 0f) // Media3 hard limit
    }

    @Test
    fun callerFilterMismatchPassesThrough() {
        val c = cfg(Keys.TARGET to "3.0")
        val d = SpeedPolicy.decide(2.0f, c, callerMatches = false)
        assertFalse(d.changed)
        assertEquals(2.0f, d.speed, 0f)
    }

    @Test
    fun invalidIncomingIsUntouched() {
        val c = cfg(Keys.MODE to "force")
        assertFalse(SpeedPolicy.decide(Float.NaN, c).changed)
        assertFalse(SpeedPolicy.decide(0f, c).changed)
    }
}
