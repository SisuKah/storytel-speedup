package io.github.sisukah.storytelspeedmod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {

    @Test
    fun defaultsAreLadderMode() {
        val c = Config.defaults()
        assertEquals(Mode.LADDER, c.mode)
        assertEquals(4, c.ladder.size)
        assertEquals(4.0f, c.maxSpeed, 0f)
        assertEquals(HookPoint.PLAYER, c.hookPoint)
        assertFalse(c.discovery)
        assertEquals(Config.CURRENT_VERSION, c.configVersion)
        assertNull(c.raw(Keys.CLS_PLAYBACK_PARAMETERS))
    }

    @Test
    fun parsesLadderSpec() {
        val steps = Config.parseLadder("1.25:2.5, 1.5:3.0 ,2.0:4.0")
        assertEquals(3, steps.size)
        assertEquals(LadderStep(1.25f, 2.5f), steps[0])
        assertEquals(LadderStep(2.0f, 4.0f), steps[2])
    }

    @Test
    fun ladderParsingDropsGarbageAndKeepsOrder() {
        val steps = Config.parseLadderRaw("2.0:4.0,oops,1.5:,:3,1.25:2.5,0:5,1.0:-2,x:y")
        assertEquals(listOf(LadderStep(1.25f, 2.5f), LadderStep(2.0f, 4.0f)), steps)
    }

    @Test
    fun ladderFirstMappingWinsForDuplicateButtons() {
        val steps = Config.parseLadder("2.0:3.0,2.0:4.0")
        assertEquals(1, steps.size)
        assertEquals(3.0f, steps[0].to, 0f)
    }

    // ---- the anti-chaining rule --------------------------------------------------------------

    @Test
    fun ladderDropsRungsThatWouldBeAppliedTwice() {
        // Media3 rebuilds PlaybackParameters from our substituted value, so it re-enters the ctor
        // hook. 1.25 -> 1.75 followed by 1.75 -> 2.25 would land on 2.25, not 1.75.
        val spec = "1.25:1.75,1.75:2.25"
        assertEquals(2, Config.parseLadderRaw(spec).size)
        val kept = Config.parseLadder(spec)
        assertEquals(listOf(LadderStep(1.75f, 2.25f)), kept)
        assertEquals(listOf(LadderStep(1.25f, 1.75f)), Config.ladderConflicts(spec))
    }

    @Test
    fun defaultLadderHasNoChainingConflicts() {
        assertTrue(Config.ladderConflicts(Config.DEFAULT_LADDER).isEmpty())
        assertEquals(4, Config.parseLadder(Config.DEFAULT_LADDER).size)
    }

    @Test
    fun aSingleRungIsNeverDroppedEvenIfItMapsOntoItself() {
        // one rung cannot chain with anything else; decide() already treats a no-op as unchanged
        assertEquals(listOf(LadderStep(2.0f, 2.0f)), Config.parseLadder("2.0:2.0"))
    }

    @Test
    fun outputsAboveStorytelsMaximumNeverConflict() {
        val spec = "1.25:2.25,1.5:2.5,1.75:2.75,2.0:3.0"
        assertTrue(Config.ladderConflicts(spec).isEmpty())
        assertEquals(4, Config.parseLadder(spec).size)
    }

    @Test
    fun emptyOrBlankLadderParsesToNothing() {
        assertTrue(Config.parseLadder("").isEmpty())
        assertTrue(Config.parseLadder(null).isEmpty())
        assertTrue(Config.parseLadder("   ").isEmpty())
    }

    @Test
    fun parsesSemicolonAndNewlineSeparatedPairs() {
        val m = Config.parseKeyValues("mode=ladder; ladder = 1.25:2.5,2.0:4.0 \n# comment\nDISCOVERY=true")
        assertEquals("ladder", m["mode"])
        assertEquals("1.25:2.5,2.0:4.0", m["ladder"])
        assertEquals("true", m["discovery"])
        assertEquals(3, m.size)
    }

    @Test
    fun typedAccessorsFallBackOnGarbage() {
        val c = Config.defaults().with(mapOf(Keys.TARGET to "abc", Keys.DISCOVERY_FRAMES to "x", Keys.MODE to "???"))
        assertEquals(3.0f, c.target, 0f)
        assertEquals(10, c.discoveryFrames)
        assertEquals(Mode.LADDER, c.mode)
    }

    @Test
    fun modeAliases() {
        assertEquals(Mode.OFF, Mode.parse("OFF"))
        assertEquals(Mode.FORCE_TARGET, Mode.parse("MODE_FORCE_TARGET"))
        assertEquals(Mode.REMAP_2X, Mode.parse("MODE_REMAP_2X"))
        assertEquals(Mode.REMAP_2X, Mode.parse("remap"))
        assertEquals(Mode.LADDER, Mode.parse("ladder"))
        assertEquals(Mode.LADDER, Mode.parse(null))
        assertEquals(HookPoint.CTOR, HookPoint.parse("ctor"))
        assertEquals(HookPoint.PLAYER, HookPoint.parse(null))
    }

    @Test
    fun dumpContainsEveryKey() {
        val d = Config.defaults().dump()
        for (k in Keys.ALL) assertTrue("dump must mention $k", d.contains("$k="))
    }

    // ---- migration ---------------------------------------------------------------------------

    @Test
    fun migrationUpgradesOldRemapInstallToLadder() {
        val old = mapOf(Keys.MODE to "remap", Keys.TARGET to "4.0")
        val migrated = Config.migrate(old)
        assertEquals(Mode.LADDER.key, migrated[Keys.MODE])
        assertEquals(Config.CURRENT_VERSION.toString(), migrated[Keys.CONFIG_VERSION])
        assertEquals("4.0", migrated[Keys.TARGET])   // unrelated values are preserved
    }

    @Test
    fun migrationNeverRevivesAnExplicitlyDisabledModule() {
        // every spelling Mode.parse accepts as OFF must survive migration, and the user's own
        // spelling is preserved rather than rewritten
        for (raw in listOf("off", "OFF", " off ", "0", "false", "none", "disabled")) {
            val migrated = Config.migrate(mapOf(Keys.MODE to raw))
            assertEquals("'$raw' must stay disabled", Mode.OFF, Mode.parse(migrated[Keys.MODE]))
            assertEquals(Config.CURRENT_VERSION.toString(), migrated[Keys.CONFIG_VERSION])
        }
    }

    @Test
    fun migrationStillUpgradesAnAbsentMode() {
        val migrated = Config.migrate(mapOf(Keys.TARGET to "4.0"))
        assertEquals(Mode.LADDER, Mode.parse(migrated[Keys.MODE]))
    }

    @Test
    fun migrationLeavesCurrentConfigsAlone() {
        val current = mapOf(Keys.MODE to "remap", Keys.CONFIG_VERSION to Config.CURRENT_VERSION.toString())
        assertSame(current, Config.migrate(current))
    }

    @Test
    fun migrationLeavesAnEmptyConfigAlone() {
        val empty = emptyMap<String, String>()
        assertSame(empty, Config.migrate(empty))
    }
}
