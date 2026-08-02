package dev.amenhancer.module.hook

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Wraps Apple Music's native TTML parser surface with the version-exact
 * classes and methods resolved through [TargetSymbolResolver]:
 *
 * `TTMLParser$TTMLParserNative#songInfoFromTTML(String)` → `SongInfo$SongInfoPtr`
 * → `SongInfoPtr#get()` → `SongInfo$SongInfoNative` (sections / Adam ID).
 *
 * Native pointer lifetime: Apple Music calls `Pointer.deallocate()` on
 * lyrics pointers (PlayerLyricsViewFragment.onDestroyView does), which zeroes
 * the native address while Java wrappers may still be referenced. Every entry
 * point therefore checks pointer liveness (address field / `Pointer.isNull`)
 * before any native call, and a dead cached pointer is dropped by the caller
 * instead of being used. This is a best-effort Java-layer guard, not a full
 * use-after-free guarantee — that still needs real-device verification.
 *
 * The single parser instance is kept as a strong reference for its whole
 * native lifetime; every call is cheap and runs on the background executor,
 * never on the I2 hook or the main thread.
 */
internal class TtmlNativeParser private constructor(
    private val parserClass: Class<*>,
    private val parseMethod: Method,
    private val ptrClass: Class<*>,
    private val nativeClass: Class<*>,
    private val aliveCheck: (Any) -> Boolean,
) {
    private val parser: Any by lazy {
        parserClass.getDeclaredConstructor().newInstance()
    }

    private val ptrGet: Method = ptrClass.getMethod("get")
    private val sectionsMethod: Method = nativeClass.getMethod("getSections")
    private val sectionsSizeMethod: Method = sectionsMethod.returnType.getMethod("size")
    private val adamIdGet: Method = nativeClass.getMethod("getAdamId")
    private val adamIdSet: Method = nativeClass.getMethod("setAdamId", Long::class.javaPrimitiveType)

    /** True while the JavaCPP wrapper still owns a native address. */
    fun isAlive(ptr: Any?): Boolean = runCatching {
        ptr != null && ptrClass.isInstance(ptr) && aliveCheck(ptr)
    }.getOrDefault(false)

    /** Parses Word-TTML into a SongInfoPtr, or `null` when parsing failed. */
    fun parse(ttml: String): Any? = runCatching {
        parseMethod.invoke(parser, ttml)
    }.getOrNull()

    /** True when the pointer is alive and holds at least one section. */
    fun isValid(ptr: Any?): Boolean = runCatching {
        if (!isAlive(ptr)) return@runCatching false
        val native = ptrGet.invoke(ptr) ?: return@runCatching false
        if (!nativeClass.isInstance(native)) return@runCatching false
        val sections = sectionsMethod.invoke(native) ?: return@runCatching false
        val size = sectionsSizeMethod.invoke(sections) as? Number ?: return@runCatching false
        size.toLong() > 0L
    }.getOrDefault(false)

    /** The SongInfo's Adam ID, or `null` when unavailable or deallocated. */
    fun adamIdOf(ptr: Any): Long? = runCatching {
        if (!isAlive(ptr)) return@runCatching null
        val native = ptrGet.invoke(ptr) ?: return@runCatching null
        (adamIdGet.invoke(native) as? Number)?.toLong()
    }.getOrNull()

    /**
     * Sets the replacement's Adam ID so Apple's own identity checks (I2's
     * incoming-id vs current item) pass, then verifies the value stuck.
     */
    fun bindAdamId(ptr: Any, adamId: Long): Boolean = runCatching {
        if (!isAlive(ptr)) return@runCatching false
        val native = ptrGet.invoke(ptr) ?: return@runCatching false
        adamIdSet.invoke(native, adamId)
        (adamIdGet.invoke(native) as? Number)?.toLong() == adamId
    }.getOrDefault(false)

    companion object {
        private const val JAVACPP_POINTER_NAME = "org.bytedeco.javacpp.Pointer"

        /**
         * Builds the wrapper, resolving the JavaCPP liveness surface up
     * front. Returns `null` when the method or liveness surface is unavailable
     * so the caller can report a targeted degraded state instead of risking a
     * released native pointer.
         */
        fun create(
            parserClass: Class<*>,
            parseMethod: Method,
            ptrClass: Class<*>,
            nativeClass: Class<*>,
        ): TtmlNativeParser? = runCatching {
            val pointerBase = generateSequence(ptrClass as Class<*>) { type -> type.superclass }
                .firstOrNull { type -> type.name == JAVACPP_POINTER_NAME }
            val addressField: Field? = pointerBase
                ?.getDeclaredField("address")
                ?.apply { isAccessible = true }
            val isNullMethod: Method? = pointerBase?.let { type ->
                runCatching { type.getMethod("isNull", type) }.getOrNull()
            }
            val aliveCheck: (Any) -> Boolean = when {
                addressField != null -> { ptr -> addressField.getLong(ptr) != 0L }
                isNullMethod != null -> { ptr ->
                    !((isNullMethod.invoke(null, ptr) as? Boolean) ?: false)
                }
                else -> return@runCatching null
            }
            TtmlNativeParser(
                parserClass = parserClass,
                parseMethod = parseMethod,
                ptrClass = ptrClass,
                nativeClass = nativeClass,
                aliveCheck = aliveCheck,
            )
        }.getOrNull()
    }
}
