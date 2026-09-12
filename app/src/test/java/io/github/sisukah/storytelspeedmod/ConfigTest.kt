package io.github.sisukah.storytelspeedmod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {

    @Test
    fun defaultsAreTheSafeMode() {
        val c = Config.defaults()
        assertEquals(Mode.REMAP_2X, c.mode)
        assertEquals(3.0f, c.target, 0f)
        assertEquals(2.0f, c.remapFrom, 0f)
        assertEquals(4.0f, c.maxSpeed, 0f)
        assertEquals(HookPoint.PLAYER, c.hookPoint)
        assertFalse(c.discovery)
        assertNull(c.raw(Keys.CLS_PLAYBACK_PARAMETERS))
        assertEquals(listOf(2.5f, 3.0f, 3.5f, 4.0f), c.uiExtraSpeeds)
    }

    @Test
    fun parsesSemicolonAndNewlineSeparatedPairs() {
        val m = Config.parseKeyValues("mode=force; target = 3.5 \n# comment\nDISCOVERY=true\nui_extra_speeds=2.5,3.0")
        assertEquals("force", m["mode"])
        assertEquals("3.5", m["target"])
        assertEquals("true", m["discovery"])
        assertEquals("2.5,3.0", m["ui_extra_speeds"])
        assertEquals(4, m.size)
    }

    @Test
    fun typedAccessorsFallBackOnGarbage() {
        val c = Config.defaults().with(mapOf(Keys.TARGET to "abc", Keys.DISCOVERY_FRAMES to "x", Keys.MODE to "???"))
        assertEquals(3.0f, c.target, 0f)
        assertEquals(10, c.discoveryFrames)
        assertEquals(Mode.REMAP_2X, c.mode)
    }

    @Test
    fun modeAliases() {
        assertEquals(Mode.OFF, Mode.parse("OFF"))
        assertEquals(Mode.FORCE_TARGET, Mode.parse("MODE_FORCE_TARGET"))
        assertEquals(Mode.REMAP_2X, Mode.parse("MODE_REMAP_2X"))
        assertEquals(HookPoint.CTOR, HookPoint.parse("ctor"))
        assertEquals(HookPoint.PLAYER, HookPoint.parse(null))
    }

    @Test
    fun dumpContainsEveryKey() {
        val d = Config.defaults().dump()
        for (k in Keys.ALL) assertTrue("dump must mention $k", d.contains("$k="))
    }
}
