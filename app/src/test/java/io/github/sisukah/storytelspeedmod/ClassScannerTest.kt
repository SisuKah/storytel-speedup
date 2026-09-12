package io.github.sisukah.storytelspeedmod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the PlaybackParameters structural fingerprint matches the real shape, not decoys. */
class ClassScannerTest {

    // Same shape as androidx.media3.common.PlaybackParameters: two float fields, (float,float)
    // ctor, a static field of its own type (DEFAULT), an int field, and withSpeed(float).
    @Suppress("unused")
    class FakePlaybackParameters(@JvmField val speed: Float, @JvmField val pitch: Float) {
        @JvmField val scaledUsPerMs: Int = Math.round(speed * 1000f)
        fun withSpeed(s: Float): FakePlaybackParameters = FakePlaybackParameters(s, pitch)
        companion object { @JvmField val DEFAULT = FakePlaybackParameters(1f, 1f) }
    }

    // Two float fields and a (float,float) ctor, but nothing Media3-specific -> must NOT match.
    @Suppress("unused")
    class FakeSize(@JvmField val width: Float, @JvmField val height: Float)

    // One float field -> must NOT match.
    @Suppress("unused")
    class FakeScalar(@JvmField val value: Float)

    @Test fun matchesRealShape() {
        assertTrue(ClassScanner.matchesPlaybackParameters(FakePlaybackParameters::class.java))
    }

    @Test fun rejectsPlainTwoFloatClass() {
        assertFalse(ClassScanner.matchesPlaybackParameters(FakeSize::class.java))
    }

    @Test fun rejectsSingleFloatClass() {
        assertFalse(ClassScanner.matchesPlaybackParameters(FakeScalar::class.java))
    }

    @Test fun rejectsInterfacesAndPrimitiveHolders() {
        assertFalse(ClassScanner.matchesPlaybackParameters(Runnable::class.java))
        assertFalse(ClassScanner.matchesPlaybackParameters(String::class.java))
    }
}
