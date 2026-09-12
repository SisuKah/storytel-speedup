package io.github.sisukah.storytelspeedmod

/** Helpers for discovery-mode logging: caller frames, object ids, formatting. */
object Discovery {

    /** Frames from these packages are our own plumbing and are not interesting. */
    private val SKIP_PREFIXES = arrayOf(
        "io.github.sisukah.storytelspeedmod.",
        "de.robv.android.xposed.",
        "org.lsposed.",
        "LSPHooker_",
        "java.lang.reflect.",
        "java.lang.Thread",
        "dalvik.system.VMStack",
        "jdk.internal.",
    )

    /** Up to [max] caller frames, innermost first, without module/Xposed plumbing. */
    fun callerFrames(max: Int): List<String> {
        if (max <= 0) return emptyList()
        return Thread.currentThread().stackTrace.asSequence()
            .filter { f -> SKIP_PREFIXES.none { f.className.startsWith(it) } }
            .map { f -> "${f.className}.${f.methodName}(${f.fileName ?: "?"}:${f.lineNumber})" }
            .take(max)
            .toList()
    }

    fun id(o: Any?): String = if (o == null) "null" else "#" + Integer.toHexString(System.identityHashCode(o))

    fun block(header: String, frames: List<String>): String =
        if (frames.isEmpty()) header else header + "\n" + frames.joinToString("\n") { "    at $it" }

    fun sig(m: java.lang.reflect.Member): String {
        val params = when (m) {
            is java.lang.reflect.Method -> m.parameterTypes
            is java.lang.reflect.Constructor<*> -> m.parameterTypes
            else -> emptyArray()
        }
        val name = if (m is java.lang.reflect.Constructor<*>) "<init>" else m.name
        return "${m.declaringClass.name}.$name(${params.joinToString(",") { it.simpleName }})"
    }
}
