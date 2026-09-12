package io.github.sisukah.storytelspeedmod

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/**
 * Configuration keys. The same keys are used in three places:
 *  - the properties file inside Storytel's private data dir (persisted config),
 *  - the "set" extra of the CONFIG broadcast (adb or the ConfigActivity),
 *  - the ConfigActivity UI.
 */
object Keys {
    // --- generic behaviour -------------------------------------------------------------------
    const val MODE = "mode"                         // ladder | remap | force | off
    const val LADDER = "ladder"                     // "1.25:2.5,1.5:3.0,1.75:3.5,2.0:4.0"
    const val TARGET = "target"                     // remap/force only, e.g. 3.0
    const val REMAP_FROM = "remap_from"             // remap only: the speed that becomes TARGET
    const val MAX_SPEED = "max_speed"               // safety cap (Media3 itself allows up to 8.0)
    const val HOOK_POINT = "hook_point"             // player (default) | ctor
    const val CALLER_FILTER = "caller_filter"       // substring that must appear in a caller frame
    const val CONFIG_VERSION = "config_version"     // bumped by migrations

    // --- discovery / debug -------------------------------------------------------------------
    const val DISCOVERY = "discovery"
    const val DISCOVERY_FRAMES = "discovery_frames"
    const val DISCOVERY_CTOR = "discovery_ctor"
    const val DISCOVERY_GETTERS = "discovery_getters"

    // --- Storytel-specific overrides (only if the app's Media3 was renamed) -------------------
    const val CLS_PLAYBACK_PARAMETERS = "cls_playback_parameters"
    const val CLS_PLAYER_IMPL = "cls_player_impl"
    const val CLS_BASE_PLAYER = "cls_base_player"
    const val M_SET_PLAYBACK_PARAMETERS = "m_set_playback_parameters"
    const val M_SET_PLAYBACK_SPEED = "m_set_playback_speed"
    const val F_SPEED = "f_speed"
    const val F_PITCH = "f_pitch"

    // --- Option C scaffold: Storytel's own selectable-speed list (needs JADX findings) --------
    const val UI_SPEED_LIST_CLASS = "ui_speed_list_class"
    const val UI_SPEED_LIST_METHOD = "ui_speed_list_method"
    const val UI_EXTRA_SPEEDS = "ui_extra_speeds"

    /** Free-form keys printed verbatim at the end of a dump. */
    val OVERRIDE_KEYS: List<String> = listOf(
        CLS_PLAYBACK_PARAMETERS, CLS_PLAYER_IMPL, CLS_BASE_PLAYER,
        M_SET_PLAYBACK_PARAMETERS, M_SET_PLAYBACK_SPEED, F_SPEED, F_PITCH,
        UI_SPEED_LIST_CLASS, UI_SPEED_LIST_METHOD, UI_EXTRA_SPEEDS,
    )

    val ALL: List<String> = listOf(
        MODE, LADDER, TARGET, REMAP_FROM, MAX_SPEED, HOOK_POINT, CALLER_FILTER, CONFIG_VERSION,
        DISCOVERY, DISCOVERY_FRAMES, DISCOVERY_CTOR, DISCOVERY_GETTERS,
    ) + OVERRIDE_KEYS
}

enum class Mode(val key: String) {
    /** Hooks stay installed but never change a value (useful for pure discovery). */
    OFF("off"),

    /**
     * DEFAULT. Each of Storytel's own speed buttons maps to a faster effective speed, so every
     * high speed is reachable from inside Storytel with one tap and takes effect immediately.
     */
    LADDER("ladder"),

    /** Only a request for exactly `remap_from` (2.0 by default) becomes `target`. */
    REMAP_2X("remap"),

    /** Every speed request becomes `target`. */
    FORCE_TARGET("force");

    companion object {
        fun parse(raw: String?): Mode = when (raw?.trim()?.lowercase()) {
            "off", "0", "false", "none", "disabled" -> OFF
            "force", "force_target", "mode_force_target" -> FORCE_TARGET
            "remap", "remap_2x", "mode_remap_2x" -> REMAP_2X
            else -> LADDER
        }
    }
}

enum class HookPoint(val key: String) {
    /** Substitute in the player's setPlaybackParameters funnel (Option B) when it is resolvable. */
    PLAYER("player"),

    /** Substitute in the PlaybackParameters(speed, pitch) constructor (Option A). */
    CTOR("ctor");

    companion object {
        fun parse(raw: String?): HookPoint = when (raw?.trim()?.lowercase()) {
            "ctor", "constructor", "pp", "playbackparameters" -> CTOR
            else -> PLAYER
        }
    }
}

/** One rung of the ladder: Storytel's button value -> the speed actually played. */
data class LadderStep(val from: Float, val to: Float)

/** Immutable view over a key/value map with typed accessors and defaults. */
class Config(private val values: Map<String, String>) {

    val mode: Mode get() = Mode.parse(values[Keys.MODE])
    val target: Float get() = float(Keys.TARGET, 3.0f)
    val remapFrom: Float get() = float(Keys.REMAP_FROM, 2.0f)
    val maxSpeed: Float get() = float(Keys.MAX_SPEED, 4.0f)
    val hookPoint: HookPoint get() = HookPoint.parse(values[Keys.HOOK_POINT])
    val callerFilter: String get() = raw(Keys.CALLER_FILTER) ?: ""
    val configVersion: Int get() = raw(Keys.CONFIG_VERSION)?.toIntOrNull() ?: 1
    val discovery: Boolean get() = bool(Keys.DISCOVERY, false)
    val discoveryFrames: Int get() = int(Keys.DISCOVERY_FRAMES, 10).coerceIn(0, 40)
    val discoveryCtor: Boolean get() = bool(Keys.DISCOVERY_CTOR, false)
    val discoveryGetters: Boolean get() = bool(Keys.DISCOVERY_GETTERS, false)

    /** Parsed once per Config instance: the ctor hook consults this on every speed change. */
    val ladder: List<LadderStep> by lazy { parseLadder(ladderSpec) }

    /** Rungs that were refused because they would be applied twice (see [Config.parseLadder]). */
    val ladderConflicts: List<LadderStep> by lazy { ladderConflicts(ladderSpec) }

    /**
     * Deliberately reads the raw map rather than [raw]: a ladder explicitly set to an empty value
     * means "no steps, change nothing", which must not silently fall back to the default and start
     * boosting speeds the user just tried to turn off. Only an absent key takes the default.
     */
    val ladderSpec: String
        get() = values[Keys.LADDER]?.trim() ?: DEFAULTS.getValue(Keys.LADDER)

    val uiExtraSpeeds: List<Float>
        get() = (raw(Keys.UI_EXTRA_SPEEDS) ?: DEFAULTS.getValue(Keys.UI_EXTRA_SPEEDS))
            .split(',')
            .mapNotNull { it.trim().toFloatOrNull() }
            .filter { it > 0f && it.isFinite() }

    /** Trimmed non-empty raw value, or null. */
    fun raw(key: String): String? = values[key]?.trim()?.takeIf { it.isNotEmpty() }

    fun with(updates: Map<String, String>): Config =
        Config(values + updates.map { (k, v) -> k.trim().lowercase() to v.trim() })

    fun asMap(): Map<String, String> = values

    /** Human readable dump of the effective configuration (what the receiver replies with). */
    fun dump(): String = buildString {
        append("mode=").append(mode.key).append('\n')
        append("ladder=").append(ladderSpec).append('\n')
        append("target=").append(target).append('\n')
        append("remap_from=").append(remapFrom).append('\n')
        append("max_speed=").append(maxSpeed).append('\n')
        append("hook_point=").append(hookPoint.key).append('\n')
        append("caller_filter=").append(callerFilter).append('\n')
        append("config_version=").append(configVersion).append('\n')
        append("discovery=").append(discovery).append('\n')
        append("discovery_frames=").append(discoveryFrames).append('\n')
        append("discovery_ctor=").append(discoveryCtor).append('\n')
        append("discovery_getters=").append(discoveryGetters).append('\n')
        for (k in Keys.OVERRIDE_KEYS) {
            append(k).append('=').append(raw(k) ?: "").append('\n')
        }
    }.trimEnd()

    private fun float(key: String, def: Float): Float {
        val f = raw(key)?.toFloatOrNull() ?: return def
        return if (f.isFinite() && f > 0f) f else def
    }

    private fun int(key: String, def: Int): Int = raw(key)?.toIntOrNull() ?: def

    private fun bool(key: String, def: Boolean): Boolean = when (raw(key)?.lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> def
    }

    companion object {
        /** Current schema version. Bump when a migration is added to ConfigStore. */
        const val CURRENT_VERSION = 2

        /**
         * Storytel's four fastest buttons become the four high speeds, keeping the picker in
         * ascending order. 1.0x and below are untouched, so ordinary listening still works.
         */
        const val DEFAULT_LADDER = "1.25:2.5,1.5:3.0,1.75:3.5,2.0:4.0"

        val DEFAULTS: Map<String, String> = linkedMapOf(
            Keys.MODE to Mode.LADDER.key,
            Keys.LADDER to DEFAULT_LADDER,
            Keys.TARGET to "3.0",
            Keys.REMAP_FROM to "2.0",
            Keys.MAX_SPEED to "4.0",
            Keys.HOOK_POINT to HookPoint.PLAYER.key,
            Keys.CALLER_FILTER to "",
            Keys.CONFIG_VERSION to CURRENT_VERSION.toString(),
            Keys.DISCOVERY to "false",
            Keys.DISCOVERY_FRAMES to "10",
            Keys.DISCOVERY_CTOR to "false",
            Keys.DISCOVERY_GETTERS to "false",
            Keys.UI_EXTRA_SPEEDS to "2.5,3.0,3.5,4.0",
        )

        fun defaults(): Config = Config(DEFAULTS)

        /**
         * Parses "from:to" pairs separated by commas, e.g. "1.25:2.5,1.5:3.0". Malformed or
         * non-positive entries are dropped; the first mapping for a given `from` wins. Sorted by
         * `from` so the dump reads like the picker. Does NOT apply the chaining rule.
         */
        fun parseLadderRaw(spec: String?): List<LadderStep> {
            if (spec.isNullOrBlank()) return emptyList()
            val out = LinkedHashMap<String, LadderStep>()
            for (part in spec.split(',')) {
                val bits = part.split(':')
                if (bits.size != 2) continue
                val from = bits[0].trim().toFloatOrNull() ?: continue
                val to = bits[1].trim().toFloatOrNull() ?: continue
                if (!from.isFinite() || !to.isFinite() || from <= 0f || to <= 0f) continue
                val key = SpeedPolicy.fmt(from)
                if (!out.containsKey(key)) out[key] = LadderStep(from, to)
            }
            return out.values.sortedBy { it.from }
        }

        /**
         * The usable ladder: [parseLadderRaw] minus any rung that would be applied twice.
         *
         * Media3 does not keep the PlaybackParameters we hand it. DefaultAudioSink rebuilds one
         * from the clamped values, which re-enters the very constructor we hook. So if a rung's
         * OUTPUT equals another rung's INPUT, the second rung fires on our own substitution and the
         * speed is mapped again: with "1.25:1.75" alongside "1.75:2.25", tapping 1.25 would end up
         * at 2.25. Such rungs are dropped rather than silently double-applied.
         *
         * In practice this costs nothing: every useful rung outputs a speed above Storytel's own
         * 2.0 maximum, so it cannot collide with a button value.
         */
        fun parseLadder(spec: String?): List<LadderStep> {
            val steps = parseLadderRaw(spec)
            if (steps.size < 2) return steps
            return steps.filterNot { step ->
                steps.any { other -> SpeedPolicy.approxEqual(other.from, step.to) }
            }
        }

        /** Rungs [parseLadder] refused because they would be applied twice. For diagnostics. */
        fun ladderConflicts(spec: String?): List<LadderStep> {
            val raw = parseLadderRaw(spec)
            val kept = parseLadder(spec).toSet()
            return raw.filterNot { kept.contains(it) }
        }

        /**
         * Parses "key=value" pairs separated by ';' or newlines. Lines starting with '#' are
         * ignored. Values may contain ',' and ':' (used by `ladder`) but not ';'.
         */
        fun parseKeyValues(text: String?): Map<String, String> {
            if (text.isNullOrBlank()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            text.split(';', '\n', '\r').forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val eq = line.indexOf('=')
                if (eq <= 0) return@forEach
                out[line.substring(0, eq).trim().lowercase()] = line.substring(eq + 1).trim()
            }
            return out
        }

        /**
         * Upgrades a configuration written by an older module build. Version 1 predates ladder
         * mode, where the only way to get a high speed was a single external target plus an app
         * restart; those installs are moved to the ladder so every speed is reachable from
         * Storytel's own picker. An explicit `off` is never overridden.
         */
        fun migrate(stored: Map<String, String>): Map<String, String> {
            if (stored.isEmpty()) return stored
            val version = stored[Keys.CONFIG_VERSION]?.trim()?.toIntOrNull() ?: 1
            if (version >= CURRENT_VERSION) return stored
            val out = LinkedHashMap(stored)
            val storedMode = stored[Keys.MODE]?.trim()?.lowercase()
            if (storedMode != Mode.OFF.key) {
                out[Keys.MODE] = Mode.LADDER.key
            }
            out[Keys.CONFIG_VERSION] = CURRENT_VERSION.toString()
            return out
        }
    }
}

/**
 * Loads/saves the configuration as a java.util.Properties file INSIDE THE PATCHED APP'S private
 * data directory (e.g. /data/user/0/grit.storytel.app/files/storytel_speed_mod.properties).
 *
 * The hook code runs inside Storytel's process, so Storytel's own data dir is always readable and
 * writable for us, in every LSPatch mode, without world-readable preference tricks. Config
 * therefore survives module rebuilds and re-patching.
 */
class ConfigStore(private val file: File?) {

    @Volatile
    var current: Config = Config.defaults()
        private set

    fun load() {
        if (file == null) {
            SLog.w("config: no data dir available, using defaults")
            return
        }
        try {
            if (!file.isFile) {
                SLog.i("config: ${file.path} does not exist yet, using defaults")
                return
            }
            val props = Properties()
            FileInputStream(file).use { props.load(it) }
            val map = props.entries.associate { (k, v) -> k.toString() to v.toString() }
            val migrated = Config.migrate(map)
            current = Config.defaults().with(migrated)
            SLog.i("config: loaded ${map.size} value(s) from ${file.path}")
            if (migrated != map) {
                SLog.i("config: migrated to version ${Config.CURRENT_VERSION} (mode=${current.mode.key})")
                Diag.info("[CONFIG] upgraded older settings to ladder mode")
                save()
            }
        } catch (t: Throwable) {
            SLog.w("config: failed to read ${file.path}, using defaults", t)
        }
    }

    @Synchronized
    fun apply(keyValues: String?): Config {
        val updates = Config.parseKeyValues(keyValues)
        if (updates.isNotEmpty()) {
            current = current.with(updates)
            save()
        }
        return current
    }

    @Synchronized
    fun reset(): Config {
        current = Config.defaults()
        try {
            file?.delete()
        } catch (t: Throwable) {
            SLog.w("config: failed to delete ${file?.path}", t)
        }
        return current
    }

    private fun save() {
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            val props = Properties()
            current.asMap().forEach { (k, v) -> props[k] = v }
            FileOutputStream(f).use { props.store(it, "StorytelSpeedMod configuration") }
        } catch (t: Throwable) {
            SLog.w("config: failed to write ${f.path}", t)
        }
    }
}
