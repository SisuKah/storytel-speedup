package io.github.sisukah.storytelspeedmod

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Resolves the Media3 / ExoPlayer members we hook.
 *
 * Resolution order for every target:
 *   1. an explicit override from the config (cls_* / m_* / f_* keys, values copied from JADX),
 *   2. the real Media3 name (androidx.media3.*), then the legacy ExoPlayer 2 name
 *      (com.google.android.exoplayer2.*),
 *   3. a STRUCTURAL search that does not depend on names at all, e.g. "the one method on the
 *      player class that takes exactly one PlaybackParameters and returns void".
 *
 * Verified against Media3 source (release branch):
 *   - PlaybackParameters: public final float speed; public final float pitch;
 *     constructor (float speed, float pitch); DEFAULT = new PlaybackParameters(1f)
 *   - BasePlayer.setPlaybackSpeed(float) is final and calls
 *     setPlaybackParameters(getPlaybackParameters().withSpeed(speed))  -> the funnel below
 *   - ExoPlayerImpl (package-private) declares exactly one method taking a PlaybackParameters:
 *     setPlaybackParameters(PlaybackParameters); it early-returns when the value is unchanged.
 *   - DefaultAudioSink clamps speed to [0.1, 8.0] -> 4x is inside ExoPlayer's own range.
 */
class Media3Targets private constructor(
    val ppClass: Class<*>?,
    val ppCtor: Constructor<*>?,           // PlaybackParameters(float speed, float pitch)
    val speedField: Field?,
    val pitchField: Field?,
    val playerImplClass: Class<*>?,        // ExoPlayerImpl (the funnel)
    val setPlaybackParameters: Method?,    // ExoPlayerImpl.setPlaybackParameters(PlaybackParameters)
    val getPlaybackParameters: Method?,    // ExoPlayerImpl.getPlaybackParameters() (discovery only)
    val basePlayerClass: Class<*>?,
    val setPlaybackSpeed: Method?,         // BasePlayer.setPlaybackSpeed(float)
    val floatSetterCandidates: List<Method>, // ambiguous (float)->void candidates (discovery only)
    val extraEntryPoints: List<Method>,    // ForwardingPlayer / SimpleExoPlayer / MediaController setters (discovery only)
    val media3Version: String?,
    val notes: List<String>,
) {

    /**
     * True when we can read/construct PlaybackParameters. That alone is enough to substitute at
     * the constructor (which every speed change flows through), so a resolved player funnel is a
     * bonus, not a requirement. When the funnel and setPlaybackSpeed are both missing (e.g. the
     * player class is renamed and could not be found), the constructor hook substitutes on its own.
     */
    val usable: Boolean
        get() = ppClass != null && ppCtor != null && speedField != null && pitchField != null

    /** No player-level entry point resolved: the constructor hook is the only substitution site. */
    val ctorOnly: Boolean
        get() = setPlaybackParameters == null && setPlaybackSpeed == null

    fun speedOf(pp: Any?): Float = if (pp == null) 1f else (speedField?.getFloat(pp) ?: 1f)
    fun pitchOf(pp: Any?): Float = if (pp == null) 1f else (pitchField?.getFloat(pp) ?: 1f)

    fun newPlaybackParameters(speed: Float, pitch: Float): Any =
        checkNotNull(ppCtor) { "PlaybackParameters constructor not resolved" }.newInstance(speed, pitch)

    fun describe(arg: Any?): String = when {
        arg == null -> "null"
        arg is Float -> "${SpeedPolicy.fmt(arg)}x"
        ppClass != null && ppClass.isInstance(arg) ->
            "PlaybackParameters(speed=${SpeedPolicy.fmt(speedOf(arg))}, pitch=${SpeedPolicy.fmt(pitchOf(arg))})"
        else -> arg.toString()
    }

    /** Short, human-readable summary for the in-app diagnostics reply (no adb needed). */
    fun compact(): String = buildString {
        append("media3=").append(media3Version ?: "unknown").append("  usable=").append(usable).append('\n')
        append("PlaybackParameters=").append(ppClass?.name ?: "NOT FOUND").append('\n')
        append("ExoPlayerImpl=").append(playerImplClass?.name ?: "NOT FOUND").append('\n')
        append("funnel(setPlaybackParameters)=").append(setPlaybackParameters?.name ?: "NOT FOUND").append('\n')
        append("setPlaybackSpeed=").append(setPlaybackSpeed?.name ?: "NOT FOUND")
        if (notes.isNotEmpty()) {
            append("\nnotes: ").append(notes.joinToString(" | "))
        }
    }

    fun logSummary() {
        val sb = StringBuilder("Media3 target resolution:\n")
        sb.append("  media3 version      : ").append(media3Version ?: "unknown (MediaLibraryInfo not found by name)").append('\n')
        sb.append("  PlaybackParameters  : ").append(ppClass?.name ?: "NOT FOUND").append('\n')
        sb.append("  ctor(float,float)   : ").append(if (ppCtor != null) "ok" else "NOT FOUND").append('\n')
        sb.append("  speed/pitch fields  : ").append(speedField?.name ?: "?").append(" / ").append(pitchField?.name ?: "?").append('\n')
        sb.append("  player impl class   : ").append(playerImplClass?.name ?: "NOT FOUND").append('\n')
        sb.append("  funnel method       : ").append(setPlaybackParameters?.let { Discovery.sig(it) } ?: "NOT FOUND").append('\n')
        sb.append("  getter (discovery)  : ").append(getPlaybackParameters?.let { Discovery.sig(it) } ?: "-").append('\n')
        sb.append("  BasePlayer class    : ").append(basePlayerClass?.name ?: "NOT FOUND").append('\n')
        sb.append("  setPlaybackSpeed    : ").append(setPlaybackSpeed?.let { Discovery.sig(it) } ?: "NOT FOUND").append('\n')
        if (floatSetterCandidates.isNotEmpty()) {
            sb.append("  (float) candidates  : ").append(floatSetterCandidates.joinToString { Discovery.sig(it) }).append('\n')
        }
        sb.append("  extra entry points  : ").append(if (extraEntryPoints.isEmpty()) "-" else extraEntryPoints.joinToString { Discovery.sig(it) }).append('\n')
        sb.append("  usable              : ").append(usable).append('\n')
        notes.forEach { sb.append("  note: ").append(it).append('\n') }
        SLog.i(sb.toString().trimEnd())
    }

    companion object {
        private val PP_CANDIDATES = listOf(
            "androidx.media3.common.PlaybackParameters",
            "com.google.android.exoplayer2.PlaybackParameters",
        )
        private val IMPL_CANDIDATES = listOf(
            "androidx.media3.exoplayer.ExoPlayerImpl",
            "com.google.android.exoplayer2.ExoPlayerImpl",
        )
        private val BASE_CANDIDATES = listOf(
            "androidx.media3.common.BasePlayer",
            "com.google.android.exoplayer2.BasePlayer",
        )
        private val EXTRA_CANDIDATES = listOf(
            "androidx.media3.common.ForwardingPlayer",
            "androidx.media3.exoplayer.SimpleExoPlayer",
            "androidx.media3.session.MediaController",
            "androidx.media3.common.ForwardingSimpleBasePlayer",
            "com.google.android.exoplayer2.ForwardingPlayer",
            "com.google.android.exoplayer2.SimpleExoPlayer",
        )
        private val LIBINFO_CANDIDATES = listOf(
            "androidx.media3.common.MediaLibraryInfo",
            "com.google.android.exoplayer2.ExoPlayerLibraryInfo",
        )

        fun resolve(cl: ClassLoader, cfg: Config, allowScan: Boolean = false): Media3Targets {
            val notes = ArrayList<String>()

            // ---- PlaybackParameters ---------------------------------------------------------
            var pp = findClass(cl, cfg.raw(Keys.CLS_PLAYBACK_PARAMETERS), PP_CANDIDATES, notes)
            if (pp == null && allowScan) {
                // Media3 is not present by name (R8 renamed it): find it by structural fingerprint.
                val r = ClassScanner.findPlaybackParameters(cl)
                Diag.add("[SCAN] ${r.note} — enumerated ${r.enumerated}, tested ${r.tested}, ${r.ms}ms")
                if (r.cls != null) {
                    pp = r.cls
                    notes += "PlaybackParameters found by structural scan: ${r.cls.name}"
                } else {
                    notes += "structural scan did not find PlaybackParameters (${r.note})"
                }
            }
            var ppCtor: Constructor<*>? = null
            var speedField: Field? = null
            var pitchField: Field? = null
            if (pp != null) {
                ppCtor = pp.declaredConstructors.firstOrNull {
                    it.parameterTypes.size == 2 && it.parameterTypes.all { p -> p == java.lang.Float.TYPE }
                }?.apply { isAccessible = true }
                if (ppCtor == null) notes += "no (float,float) constructor on ${pp.name}"

                val floatFields = pp.declaredFields
                    .filter { !Modifier.isStatic(it.modifiers) && it.type == java.lang.Float.TYPE }
                    .onEach { it.isAccessible = true }
                speedField = cfg.raw(Keys.F_SPEED)?.let { n -> floatFields.firstOrNull { it.name == n } }
                    ?: floatFields.firstOrNull { it.name == "speed" }
                pitchField = cfg.raw(Keys.F_PITCH)?.let { n -> floatFields.firstOrNull { it.name == n } }
                    ?: floatFields.firstOrNull { it.name == "pitch" }

                // Name-independent verification: build PlaybackParameters(2.0, 1.0) and see which
                // float field holds which value. Works even if R8 renamed the fields.
                if (ppCtor != null) {
                    try {
                        val probe = ppCtor.newInstance(2.0f, 1.0f)
                        val bySpeed = floatFields.filter { it.getFloat(probe) == 2.0f }
                        val byPitch = floatFields.filter { it.getFloat(probe) == 1.0f }
                        if (bySpeed.size == 1 && byPitch.size == 1) {
                            if (speedField != bySpeed[0] || pitchField != byPitch[0]) {
                                notes += "fields identified structurally: speed=${bySpeed[0].name} pitch=${byPitch[0].name}"
                            }
                            speedField = bySpeed[0]
                            pitchField = byPitch[0]
                        } else {
                            notes += "field probe ambiguous (float fields: ${floatFields.joinToString { it.name }})"
                        }
                    } catch (t: Throwable) {
                        notes += "field probe failed: $t"
                    }
                }
            }

            // ---- ExoPlayerImpl funnel -------------------------------------------------------
            val impl = findClass(cl, cfg.raw(Keys.CLS_PLAYER_IMPL), IMPL_CANDIDATES, notes)
            var setPP: Method? = null
            var getPP: Method? = null
            if (impl != null && pp != null) {
                setPP = findMethod(impl, cfg.raw(Keys.M_SET_PLAYBACK_PARAMETERS), "setPlaybackParameters",
                    arrayOf(pp), Void.TYPE, notes)
                getPP = impl.declaredMethods.filter {
                    it.parameterTypes.isEmpty() && it.returnType == pp && !Modifier.isStatic(it.modifiers)
                }.let { list ->
                    list.firstOrNull { it.name == "getPlaybackParameters" } ?: list.singleOrNull()
                }?.apply { isAccessible = true }
            }

            // ---- BasePlayer.setPlaybackSpeed(float) -----------------------------------------
            val base = findClass(cl, cfg.raw(Keys.CLS_BASE_PLAYER), BASE_CANDIDATES, notes)
            var setSpeed: Method? = null
            val floatCandidates = ArrayList<Method>()
            if (base != null) {
                setSpeed = findMethod(base, cfg.raw(Keys.M_SET_PLAYBACK_SPEED), "setPlaybackSpeed",
                    arrayOf(java.lang.Float.TYPE), Void.TYPE, notes)
                if (setSpeed == null) {
                    floatCandidates += base.declaredMethods.filter {
                        it.parameterTypes.size == 1 && it.parameterTypes[0] == java.lang.Float.TYPE &&
                            it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers)
                    }.onEach { it.isAccessible = true }
                }
            }
            // If R8 merged BasePlayer into the impl class, the (float) setter lives there.
            if (setSpeed == null && impl != null) {
                floatCandidates += impl.declaredMethods.filter {
                    it.parameterTypes.size == 1 && it.parameterTypes[0] == java.lang.Float.TYPE &&
                        it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers)
                }.onEach { it.isAccessible = true }
                if (floatCandidates.isNotEmpty()) {
                    notes += "setPlaybackSpeed not resolved; (float) candidates will be logged in discovery mode"
                }
            }

            // ---- Extra entry points (discovery only) ----------------------------------------
            val extras = ArrayList<Method>()
            if (pp != null) {
                for (name in EXTRA_CANDIDATES) {
                    val c = loadClass(cl, name) ?: continue
                    c.declaredMethods.filter { m ->
                        !Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE && m.parameterTypes.size == 1 &&
                            (m.parameterTypes[0] == pp ||
                                (m.parameterTypes[0] == java.lang.Float.TYPE && m.name == "setPlaybackSpeed"))
                    }.forEach { it.isAccessible = true; extras += it }
                }
            }

            // ---- Media3 version -------------------------------------------------------------
            var version: String? = null
            for (name in LIBINFO_CANDIDATES) {
                val c = loadClass(cl, name) ?: continue
                version = c.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                    .mapNotNull { f -> runCatching { f.isAccessible = true; f.get(null) as? String }.getOrNull() }
                    .firstOrNull { it.startsWith("AndroidXMedia3/") || it.startsWith("ExoPlayerLib/") }
                if (version != null) break
            }

            if (pp == null) {
                notes += "PlaybackParameters not found by name -> Media3 is probably renamed by R8. " +
                    "Follow docs/02-reverse-engineering.md and set cls_playback_parameters / cls_player_impl."
            }

            return Media3Targets(
                ppClass = pp, ppCtor = ppCtor, speedField = speedField, pitchField = pitchField,
                playerImplClass = impl, setPlaybackParameters = setPP, getPlaybackParameters = getPP,
                basePlayerClass = base, setPlaybackSpeed = setSpeed,
                floatSetterCandidates = floatCandidates, extraEntryPoints = extras,
                media3Version = version, notes = notes,
            )
        }

        private fun loadClass(cl: ClassLoader, name: String): Class<*>? = try {
            Class.forName(name, false, cl)
        } catch (t: Throwable) {
            null
        }

        private fun findClass(cl: ClassLoader, override: String?, candidates: List<String>, notes: MutableList<String>): Class<*>? {
            val names = (listOfNotNull(override) + candidates).distinct()
            for (n in names) {
                val c = loadClass(cl, n)
                if (c != null) {
                    if (n == override) notes += "using override class $n"
                    return c
                }
            }
            return null
        }

        /**
         * 1) override name, 2) default name, 3) structural: exactly one declared method with the
         * given parameter types and return type. Searches the class and its superclasses for
         * named lookups; structural search is limited to the class itself.
         */
        private fun findMethod(
            cls: Class<*>, override: String?, defaultName: String,
            params: Array<Class<*>>, ret: Class<*>, notes: MutableList<String>,
        ): Method? {
            for (name in listOfNotNull(override, defaultName)) {
                var c: Class<*>? = cls
                while (c != null && c != Any::class.java) {
                    val m = runCatching { c.getDeclaredMethod(name, *params) }.getOrNull()
                    if (m != null && m.returnType == ret) {
                        m.isAccessible = true
                        if (name == override) notes += "using override method ${Discovery.sig(m)}"
                        return m
                    }
                    c = c.superclass
                }
            }
            val structural = cls.declaredMethods.filter {
                !Modifier.isStatic(it.modifiers) && it.returnType == ret && it.parameterTypes.contentEquals(params)
            }
            return when (structural.size) {
                1 -> structural[0].apply { isAccessible = true }.also {
                    notes += "method identified structurally: ${Discovery.sig(it)} (expected name $defaultName)"
                }
                0 -> null.also { notes += "no method named $defaultName and no structural match on ${cls.name}" }
                else -> null.also {
                    notes += "ambiguous structural match for $defaultName on ${cls.name}: " +
                        structural.joinToString { Discovery.sig(it) } + " -> set the m_* override"
                }
            }
        }
    }
}
