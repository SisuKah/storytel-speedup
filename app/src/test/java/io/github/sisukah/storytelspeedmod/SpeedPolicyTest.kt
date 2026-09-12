package io.github.sisukah.storytelspeedmod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedPolicyTest {

    private fun cfg(vararg kv: Pair<String, String>) = Config.defaults().with(kv.toMap())

    // ---- ladder mode (the default) -----------------------------------------------------------

    @Test
    fun defaultLadderMapsStorytelsFourFastestButtons() {
        val c = Config.defaults()
        assertEquals(2.5f, SpeedPolicy.decide(1.25f, c).speed, 0f)
        assertEquals(3.0f, SpeedPolicy.decide(1.5f, c).speed, 0f)
        assertEquals(3.5f, SpeedPolicy.decide(1.75f, c).speed, 0f)
        assertEquals(4.0f, SpeedPolicy.decide(2.0f, c).speed, 0f)
        for (s in listOf(1.25f, 1.5f, 1.75f, 2.0f)) {
            assertTrue("$s must be boosted", SpeedPolicy.decide(s, c).changed)
        }
    }

    @Test
    fun ladderLeavesNormalListeningSpeedsAlone() {
        val c = Config.defaults()
        for (s in listOf(0.5f, 0.75f, 1.0f)) {
            val d = SpeedPolicy.decide(s, c)
            assertFalse("$s must pass through", d.changed)
            assertEquals(s, d.speed, 0f)
        }
    }

    @Test
    fun ladderIgnoresSpeedsThatAreNotSteps() {
        val c = Config.defaults()
        assertFalse(SpeedPolicy.decide(1.1f, c).changed)
        assertFalse(SpeedPolicy.decide(3.0f, c).changed)   // our own output must not re-map
    }

    @Test
    fun ladderToleratesFloatNoiseButNotNeighbouringSteps() {
        val c = Config.defaults()
        assertEquals(4.0f, SpeedPolicy.decide(2.0001f, c).speed, 0f)
        // 1.75 and 2.0 are only 0.25 apart: the tolerance must not confuse them
        assertEquals(3.5f, SpeedPolicy.decide(1.75f, c).speed, 0f)
        assertFalse(SpeedPolicy.decide(1.9f, c).changed)
    }

    @Test
    fun ladderIsCappedByMaxSpeed() {
        val c = cfg(Keys.LADDER to "2.0:6.0", Keys.MAX_SPEED to "4.0")
        assertEquals(4.0f, SpeedPolicy.decide(2.0f, c).speed, 0f)
        val raised = cfg(Keys.LADDER to "2.0:6.0", Keys.MAX_SPEED to "8.0")
        assertEquals(6.0f, SpeedPolicy.decide(2.0f, raised).speed, 0f)
        val absurd = cfg(Keys.LADDER to "2.0:99", Keys.MAX_SPEED to "99")
        assertEquals(SpeedPolicy.MEDIA3_MAX_SPEED, SpeedPolicy.decide(2.0f, absurd).speed, 0f)
    }

    @Test
    fun emptyLadderChangesNothing() {
        val c = cfg(Keys.LADDER to "")
        assertFalse(SpeedPolicy.decide(2.0f, c).changed)
    }

    @Test
    fun ladderStepThatEqualsItsInputIsNotAChange() {
        val c = cfg(Keys.LADDER to "2.0:2.0")
        val d = SpeedPolicy.decide(2.0f, c)
        assertFalse("no-op step must not count as a substitution", d.changed)
    }

    @Test
    fun aChainingRungIsInertRatherThanDoubleApplied() {
        // "1.25 -> 1.75" is refused because 1.75 is itself a button that maps on to 2.25
        val c = cfg(Keys.LADDER to "1.25:1.75,1.75:2.25")
        assertFalse("the refused rung must not fire at all", SpeedPolicy.decide(1.25f, c).changed)
        assertEquals(2.25f, SpeedPolicy.decide(1.75f, c).speed, 0f)
    }

    @Test
    fun describeLadderReportsRefusedRungs() {
        val c = cfg(Keys.LADDER to "1.25:1.75,1.75:2.25")
        val text = SpeedPolicy.describeLadder(c)
        assertTrue(text, text.contains("dropped"))
        assertTrue(text, text.contains("1.25->1.75"))
    }

    @Test
    fun ourOwnOutputIsRecognisedSoItIsNotMistakenForAButton() {
        // Media3 rebuilds the parameters we substituted, so 4.0 comes back through the ctor
        val c = Config.defaults()
        assertTrue(SpeedPolicy.isOurOutput(4.0f, c))
        assertTrue(SpeedPolicy.isOurOutput(2.5f, c))
        assertFalse("a real button value is not our output", SpeedPolicy.isOurOutput(2.0f, c))
        assertFalse(SpeedPolicy.isOurOutput(1.0f, c))
    }

    @Test
    fun ourOwnOutputCoversRemapAndForceAndNeverFiresWhenOff() {
        assertTrue(SpeedPolicy.isOurOutput(3.5f, cfg(Keys.MODE to "remap", Keys.TARGET to "3.5")))
        assertTrue(SpeedPolicy.isOurOutput(3.0f, cfg(Keys.MODE to "force", Keys.TARGET to "3.0")))
        assertFalse(SpeedPolicy.isOurOutput(3.0f, cfg(Keys.MODE to "off", Keys.TARGET to "3.0")))
    }

    @Test
    fun describeLadderFlagsRungsTruncatedByTheCap() {
        val c = cfg(Keys.LADDER to "1.5:3.0,2.0:6.0", Keys.MAX_SPEED to "4.0")
        val text = SpeedPolicy.describeLadder(c)
        assertTrue(text, text.contains("capped from 6.00"))
        assertFalse("an in-range rung must not be flagged", text.contains("1.50->3.00(capped"))
    }

    @Test
    fun describeLadderSaysWhenTheLadderIsNotInUse() {
        val text = SpeedPolicy.describeLadder(cfg(Keys.MODE to "remap"))
        assertTrue(text, text.contains("not in use"))
        assertTrue(text, text.contains("remap"))
    }

    @Test
    fun describeLadderListsEveryStep() {
        val text = SpeedPolicy.describeLadder(Config.defaults())
        assertTrue(text, text.contains("1.25->2.50"))
        assertTrue(text, text.contains("2.00->4.00"))
    }

    // ---- remap / force / off -----------------------------------------------------------------

    @Test
    fun remapOnlyTouchesTwoX() {
        val c = cfg(Keys.MODE to "remap", Keys.TARGET to "3.5")
        for (s in listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f)) {
            assertFalse("speed $s must pass through", SpeedPolicy.decide(s, c).changed)
        }
        val d = SpeedPolicy.decide(2.0f, c)
        assertTrue(d.changed)
        assertEquals(3.5f, d.speed, 0f)
    }

    @Test
    fun remapFromIsConfigurable() {
        val c = cfg(Keys.MODE to "remap", Keys.REMAP_FROM to "1.75", Keys.TARGET to "2.5")
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
        assertFalse(SpeedPolicy.decide(1.5f, c).changed)
    }

    @Test
    fun targetIsClampedByMaxSpeedAndMedia3Limits() {
        assertEquals(4.0f, SpeedPolicy.effectiveTarget(cfg(Keys.TARGET to "9.0")), 0f)
        assertEquals(6.0f, SpeedPolicy.effectiveTarget(cfg(Keys.TARGET to "6.0", Keys.MAX_SPEED to "8.0")), 0f)
        assertEquals(8.0f, SpeedPolicy.effectiveTarget(cfg(Keys.TARGET to "20", Keys.MAX_SPEED to "20")), 0f)
    }

    @Test
    fun callerFilterMismatchPassesThrough() {
        val d = SpeedPolicy.decide(2.0f, Config.defaults(), callerMatches = false)
        assertFalse(d.changed)
        assertEquals(2.0f, d.speed, 0f)
    }

    @Test
    fun invalidIncomingIsUntouched() {
        val c = cfg(Keys.MODE to "force")
        assertFalse(SpeedPolicy.decide(Float.NaN, c).changed)
        assertFalse(SpeedPolicy.decide(0f, c).changed)
        assertFalse(SpeedPolicy.decide(Float.POSITIVE_INFINITY, c).changed)
    }
}
