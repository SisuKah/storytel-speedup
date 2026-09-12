package io.github.sisukah.storytelspeedmod

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory diagnostics that can be read back WITHOUT adb/logcat: the config broadcast reply
 * (and therefore the ConfigActivity's "Reply from Storytel process" panel) includes a snapshot.
 *
 * - [resolution] is set once when the hooks are installed: which Media3 classes were found and
 *   whether the module is usable. This answers "did the hook even attach".
 * - [add] records the last speed-change events ([SET]/[ENTRY]/[PLAYER-CREATED]/errors) in a small
 *   ring buffer. This answers "does the hook fire when I press a speed, and with what values".
 *
 * All of this is visible in-app: change the speed in Storytel, then press "Show current".
 */
object Diag {

    @Volatile
    var resolution: String = "(hooks not installed yet — is the module loaded? see the pid line above)"

    private const val MAX = 25
    private const val MAX_INFO = 6
    private val events = ArrayDeque<String>()
    private val infos = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    @Synchronized
    fun add(line: String) {
        events.addLast(clock.format(Date()) + " " + line)
        while (events.size > MAX) events.removeFirst()
    }

    /** Status lines (scan progress, etc). Kept apart so "speed events" counts only real ones. */
    @Synchronized
    fun info(line: String) {
        infos.addLast(clock.format(Date()) + " " + line)
        while (infos.size > MAX_INFO) infos.removeFirst()
    }

    @Synchronized
    fun clear() {
        events.clear()
        infos.clear()
    }

    /** Compact block appended to every broadcast reply. [maxEvents] most recent events are shown. */
    @Synchronized
    fun snapshot(maxEvents: Int = 8): String = buildString {
        append("--- diagnostics ---\n")
        append(resolution).append('\n')
        infos.forEach { append(it).append('\n') }
        append("speed events: ").append(events.size)
        if (events.isEmpty()) {
            append("\n(none yet. Start a book, change the speed to 2x, then press Show current. ")
            append("If this still says 0, Storytel is not reaching the hooked player.)")
        } else {
            events.toList().takeLast(maxEvents).forEach { append('\n').append(it) }
        }
    }
}
