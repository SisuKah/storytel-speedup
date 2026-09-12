package io.github.sisukah.storytelspeedmod

import dalvik.system.BaseDexClassLoader
import java.lang.reflect.Modifier

/**
 * Finds Media3's PlaybackParameters WITHOUT knowing its (R8-renamed) name, by scanning the loaded
 * dex files for a class with its unmistakable SHAPE. R8 renames identifiers but cannot change:
 *
 *   - two non-static float fields              (public final float speed; public final float pitch;)
 *   - a constructor taking exactly (float, float)
 *   - AND one of: a static field of its own type (DEFAULT = new PlaybackParameters(1f))
 *                 or a method (float) -> itself  (withSpeed)
 *
 * Nothing else in a typical app matches all three, so this is a reliable structural fingerprint.
 * This is the fallback used when androidx.media3 / com.google.android.exoplayer2 are not present
 * by name. Everything it does is logged into Diag so it is visible in-app (no adb needed).
 */
object ClassScanner {

    // Packages that keep their real names (not obfuscated) and are never PlaybackParameters.
    // Skipping them makes the scan much faster and cannot hide Media3 (renamed Media3 would not
    // sit under any of these).
    private val SKIP = arrayOf(
        "android.", "androidx.compose", "androidx.core", "androidx.lifecycle", "androidx.room",
        "androidx.work", "androidx.navigation", "androidx.fragment", "androidx.activity",
        "androidx.recyclerview", "androidx.appcompat", "androidx.constraintlayout", "androidx.datastore",
        "androidx.emoji2", "androidx.viewpager", "androidx.window", "androidx.startup", "androidx.profileinstaller",
        "kotlin.", "kotlinx.", "okhttp3.", "okio.", "retrofit2.", "com.google.gson", "com.google.common",
        "com.google.firebase", "com.google.android.gms", "com.google.android.datatransport", "com.squareup.",
        "io.reactivex", "com.bumptech.glide", "coil.", "dagger.", "javax.", "org.json", "org.intellij",
        "org.jetbrains", "com.facebook", "io.grpc", "com.google.protobuf", "j$.", "kotlinx.coroutines",
    )

    /** Result of a scan, for diagnostics. */
    data class Result(val cls: Class<*>?, val enumerated: Int, val tested: Int, val ms: Long, val note: String)

    /** Generic scan: the first loadable class (outside SKIP packages) for which [predicate] holds. */
    fun findFirst(cl: ClassLoader, what: String, predicate: (Class<*>) -> Boolean): Result {
        val start = System.currentTimeMillis()
        val names = classNames(cl)
        if (names.isEmpty()) return Result(null, 0, 0, 0, "could not read dex entries")
        var tested = 0
        for (name in names) {
            if (SKIP.any { name.startsWith(it) }) continue
            if (name.isEmpty() || name[0] == '[') continue
            val c = try { Class.forName(name, false, cl) } catch (t: Throwable) { continue }
            tested++
            val ok = try { predicate(c) } catch (t: Throwable) { false }
            if (ok) return Result(c, names.size, tested, System.currentTimeMillis() - start, "matched ${c.name}")
        }
        return Result(null, names.size, tested, System.currentTimeMillis() - start, "no $what among $tested classes")
    }

    fun findPlaybackParameters(cl: ClassLoader): Result {
        val start = System.currentTimeMillis()
        val names = classNames(cl)
        if (names.isEmpty()) {
            return Result(null, 0, 0, System.currentTimeMillis() - start,
                "could not read dex entries (hidden API?) — cannot auto-scan")
        }
        var tested = 0
        var candidates = 0
        // A class can LOOK like PlaybackParameters (a two-float geometry type with a constant of
        // its own type would). Only one thing proves it: PlaybackParameters precomputes
        // scaledUsPerMs = Math.round(speed * 1000f), so building it with speed 2.0 must leave an
        // int field holding exactly 2000. Keep scanning past unverified look-alikes.
        var fallback: Class<*>? = null
        for (name in names) {
            if (SKIP.any { name.startsWith(it) }) continue
            // '[' would be an array descriptor, never a plain class entry
            if (name.isEmpty() || name[0] == '[') continue
            val c = try { Class.forName(name, false, cl) } catch (t: Throwable) { continue }
            tested++
            if (!matchesPlaybackParameters(c)) continue
            candidates++
            if (verifyByScaledUsPerMs(c)) {
                return Result(c, names.size, tested, System.currentTimeMillis() - start,
                    "matched ${c.name} (VERIFIED: scaledUsPerMs==2000 at speed 2.0)")
            }
            if (fallback == null) fallback = c
        }
        val ms = System.currentTimeMillis() - start
        return if (fallback != null) {
            Result(fallback, names.size, tested, ms,
                "matched ${fallback.name} (UNVERIFIED shape-only match, $candidates candidate(s); " +
                    "if the speed does not change this is the wrong class)")
        } else {
            Result(null, names.size, tested, ms,
                "no PlaybackParameters-shaped class among $tested tested (${names.size} enumerated)")
        }
    }

    /**
     * Builds the candidate with (speed=2.0, pitch=1.0) and looks for the precomputed
     * Math.round(speed * 1000f) == 2000. Decisive, and immune to renaming.
     */
    internal fun verifyByScaledUsPerMs(c: Class<*>): Boolean = try {
        val ctor = c.declaredConstructors.first { k ->
            k.parameterTypes.size == 2 && k.parameterTypes.all { it == java.lang.Float.TYPE }
        }
        ctor.isAccessible = true
        val instance = ctor.newInstance(2.0f, 1.0f)
        c.declaredFields.any { f ->
            !Modifier.isStatic(f.modifiers) && f.type == Integer.TYPE && run {
                f.isAccessible = true
                f.getInt(instance) == 2000
            }
        }
    } catch (t: Throwable) {
        false
    }

    internal fun matchesPlaybackParameters(c: Class<*>): Boolean {
        if (c.isInterface || c.isEnum || c.isAnnotation || c.isArray) return false
        if (Modifier.isAbstract(c.modifiers)) return false
        val fields = try { c.declaredFields } catch (t: Throwable) { return false }
        val floatInstance = fields.count { !Modifier.isStatic(it.modifiers) && it.type == java.lang.Float.TYPE }
        if (floatInstance != 2) return false
        val hasFloatFloatCtor = try {
            c.declaredConstructors.any { ctor ->
                ctor.parameterTypes.size == 2 && ctor.parameterTypes.all { it == java.lang.Float.TYPE }
            }
        } catch (t: Throwable) { return false }
        if (!hasFloatFloatCtor) return false
        val hasSelfStatic = fields.any { Modifier.isStatic(it.modifiers) && it.type == c }
        val hasWithSpeed = try {
            c.declaredMethods.any { m ->
                m.returnType == c && m.parameterTypes.size == 1 && m.parameterTypes[0] == java.lang.Float.TYPE
            }
        } catch (t: Throwable) { false }
        return hasSelfStatic || hasWithSpeed
    }

    /** Class names from every dex in this loader (and its parents). Empty if enumeration is blocked. */
    private fun classNames(cl: ClassLoader): List<String> {
        val out = ArrayList<String>(4096)
        var loader: ClassLoader? = cl
        while (loader != null) {
            if (loader is BaseDexClassLoader) {
                try {
                    val pathList = field(BaseDexClassLoader::class.java, "pathList").get(loader) ?: continue
                    val dexElements = field(pathList.javaClass, "dexElements").get(pathList) as? Array<*> ?: continue
                    for (el in dexElements) {
                        el ?: continue
                        val dexFile = field(el.javaClass, "dexFile").get(el) ?: continue
                        @Suppress("UNCHECKED_CAST")
                        val entries = dexFile.javaClass.getMethod("entries").invoke(dexFile) as? java.util.Enumeration<String> ?: continue
                        while (entries.hasMoreElements()) out.add(entries.nextElement())
                    }
                } catch (t: Throwable) {
                    SLog.w("class scan: cannot enumerate dex on ${loader.javaClass.name}", t)
                }
            }
            loader = loader.parent
        }
        return out
    }

    private fun field(cls: Class<*>, name: String): java.lang.reflect.Field {
        val f = cls.getDeclaredField(name)
        f.isAccessible = true
        return f
    }
}
