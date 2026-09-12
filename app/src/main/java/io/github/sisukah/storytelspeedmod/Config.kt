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
 *
 * Only the "generic" keys are needed in the normal case. The cls_* / m_* / f_* keys exist so
 * that, if Storytel's build renamed the Media3 classes (R8), you can supply the renamed names
 * you found in JADX WITHOUT rebuilding the module. The ui_* keys are the Option C scaffold and
 * also require JADX findings.
 */
object Keys {
    // --- generic behaviour -------------------------------------------------------------------
    const val MODE = "mode"                         // off | remap | force
    const val TARGET = "target"                     // e.g. 2.5, 3.0, 3.5, 4.0
    const val REMAP_FROM = "remap_from"             // the Storytel speed that becomes TARGET (default 2.0)
    const val MAX_SPEED = "max_speed"               // safety clamp for TARGET (default 4.0; Media3 itself allows 8.0)
    const val HOOK_POINT = "hook_point"             // player (default) | ctor
    const val CALLER_FILTER = "caller_filter"       // substring that must appear in a caller frame; empty = no filter

    // --- discovery / debug -------------------------------------------------------------------
    const val DISCOVERY = "discovery"               // true | false
    const val DISCOVERY_FRAMES = "discovery_frames" // caller frames to print per event (default 10)
    const val DISCOVERY_CTOR = "discovery_ctor"     // also log every PlaybackParameters construction (noisy)
    const val DISCOVERY_GETTERS = "discovery_getters" // also log getPlaybackParameters() reads (noisy)

    // --- Storytel-specific overrides (only if JADX shows Media3 was renamed) ------------------
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
    const val UI_EXTRA_SPEEDS = "ui_extra_speeds"   // comma separated, default 2.5,3.0,3.5,4.0

    val ALL: List<String> = listOf(
        MODE, TARGET, REMAP_FROM, MAX_SPEED, HOOK_POINT, CALLER_FILTER,
        DISCOVERY, DISCOVERY_FRAMES, DISCOVERY_CTOR, DISCOVERY_GETTERS,
        CLS_PLAYBACK_PARAMETERS, CLS_PLAYER_IMPL, CLS_BASE_PLAYER,
        M_SET_PLAYBACK_PARAMETERS, M_SET_PLAYBACK_SPEED, F_SPEED, F_PITCH,
        UI_SPEED_LIST_CLASS, UI_SPEED_LIST_METHOD, UI_EXTRA_SPEEDS,
    )
}

enum class Mode(val key: String) {
    /** Hooks stay installed but never change a value (useful for pure discovery). */
    OFF("off"),

    /** MODE_REMAP_2X: only a request for exactly `remap_from` (2.0 by default) becomes TARGET. */
    REMAP_2X("remap"),

    /** MODE_FORCE_TARGET: every speed request becomes TARGET. */
    FORCE_TARGET("force");

    companion object {
        fun parse(raw: String?): Mode = when (raw?.trim()?.lowercase()) {
            "off", "0", "false", "none", "disabled" -> OFF
            "force", "force_target", "mode_force_target" -> FORCE_TARGET
            else -> REMAP_2X
        }
    }
}

enum class HookPoint(val key: String) {
    /** Substitute in the player's setPlaybackParameters(...) funnel (Option B, default). */
    PLAYER("player"),

    /** Substitute in the PlaybackParameters(speed, pitch) constructor (Option A fallback). */
    CTOR("ctor");

    companion object {
        fun parse(raw: String?): HookPoint = when (raw?.trim()?.lowercase()) {
            "ctor", "constructor", "pp", "playbackparameters" -> CTOR
            else -> PLAYER
        }
    }
}

/** Immutable view over a key/value map with typed accessors and defaults. */
class Config(private val values: Map<String, String>) {

    val mode: Mode get() = Mode.parse(values[Keys.MODE])
    val target: Float get() = float(Keys.TARGET, 3.0f)
    val remapFrom: Float get() = float(Keys.REMAP_FROM, 2.0f)
    val maxSpeed: Float get() = float(Keys.MAX_SPEED, 4.0f)
    val hookPoint: HookPoint get() = HookPoint.parse(values[Keys.HOOK_POINT])
    val callerFilter: String get() = raw(Keys.CALLER_FILTER) ?: ""
    val discovery: Boolean get() = bool(Keys.DISCOVERY, false)
    val discoveryFrames: Int get() = int(Keys.DISCOVERY_FRAMES, 10).coerceIn(0, 40)
    val discoveryCtor: Boolean get() = bool(Keys.DISCOVERY_CTOR, false)
    val discoveryGetters: Boolean get() = bool(Keys.DISCOVERY_GETTERS, false)

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
        append("target=").append(target).append('\n')
        append("remap_from=").append(remapFrom).append('\n')
        append("max_speed=").append(maxSpeed).append('\n')
        append("hook_point=").append(hookPoint.key).append('\n')
        append("caller_filter=").append(callerFilter).append('\n')
        append("discovery=").append(discovery).append('\n')
        append("discovery_frames=").append(discoveryFrames).append('\n')
        append("discovery_ctor=").append(discoveryCtor).append('\n')
        append("discovery_getters=").append(discoveryGetters).append('\n')
        for (k in Keys.ALL.drop(10)) {
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
        val DEFAULTS: Map<String, String> = linkedMapOf(
            Keys.MODE to Mode.REMAP_2X.key,
            Keys.TARGET to "3.0",
            Keys.REMAP_FROM to "2.0",
            Keys.MAX_SPEED to "4.0",
            Keys.HOOK_POINT to HookPoint.PLAYER.key,
            Keys.CALLER_FILTER to "",
            Keys.DISCOVERY to "false",
            Keys.DISCOVERY_FRAMES to "10",
            Keys.DISCOVERY_CTOR to "false",
            Keys.DISCOVERY_GETTERS to "false",
            Keys.UI_EXTRA_SPEEDS to "2.5,3.0,3.5,4.0",
        )

        fun defaults(): Config = Config(DEFAULTS)

        /**
         * Parses "key=value" pairs separated by ';' or newlines. Lines starting with '#' are
         * ignored. Values may contain ',' (used by ui_extra_speeds) but not ';'.
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
    }
}

/**
 * Loads/saves the configuration as a java.util.Properties file INSIDE THE PATCHED APP'S private
 * data directory (e.g. /data/user/0/grit.storytel.app/files/storytel_speed_mod.properties).
 *
 * Why there and not XSharedPreferences: the hook code runs inside Storytel's process, so
 * Storytel's own data dir is always readable and writable for us, in every LSPatch mode, on
 * every Android version, without world-readable preference tricks. Config therefore survives
 * module rebuilds and re-patching (as long as Storytel's data is not wiped).
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
            current = Config.defaults().with(map)
            SLog.i("config: loaded ${map.size} value(s) from ${file.path}")
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
