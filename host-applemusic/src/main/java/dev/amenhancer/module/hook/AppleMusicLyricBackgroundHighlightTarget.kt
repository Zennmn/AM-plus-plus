package dev.amenhancer.module.hook

import android.os.Handler
import android.os.Looper
import android.util.Pair
import android.util.SparseArray
import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.TreeSet

/** Keeps background-vocal parents in the host's committed active rows, so all native consumers agree. */
internal class AppleMusicLyricBackgroundHighlightTarget(
    private val profile: AppleMusicHostProfile,
    private val loader: ClassLoader,
    private val onFailure: (String) -> Unit = {},
) : LyricBackgroundHighlightTarget {
    private data class Window(val begin: Long, val end: Long) {
        fun contains(position: Long) = position >= begin && position < end
    }

    private class Session(reader: Any) {
        val reader = WeakReference(reader)
        val windows = HashMap<Int, Window?>()
        var held = emptyMap<Int, Window>()
        var nativeLines = emptyList<Int>()
        var deadline = 0
        var receivedMediaPosition = 0L
        var mediaToLineOffset = 0L
        var paused = false
        var replaying = false
    }

    private class CommitCall(val adapter: Any, val previous: Set<Int>, val nativeInput: Set<Int>,
        val previousHeld: Set<Int>, val retained: Set<Int>) {
        var removed = previousHeld - retained - nativeInput
        var filterObserved = false
    }

    private val sessions = WeakIdentityMap<Any, Session>()
    private val calls = ThreadLocal<MutableList<CommitCall>>()
    private val handler = Handler(Looper.getMainLooper())
    private var registration: HookRegistrationScope? = null
    private var contract: BackgroundHighlightContract? = null
    private var tickPosted = false
    private var recovering = false
    private var lastFailure: String? = null
    @Volatile private var offMainReported = false
    private val tick = Runnable {
        tickPosted = false
        guarded { expireHeldLines() }
        scheduleTick()
    }

    @Synchronized override fun install(): TargetCapabilityInstall {
        registration?.takeIf { it.isActive }?.let {
            return TargetCapabilityInstall.Active("Background vocal native highlighting already active; ${profile.id}")
        }
        val names = profile.document.optJSONObject("lyricBackgroundHighlight")
            ?: return TargetCapabilityInstall.Unsupported("Background vocal native highlighting has no exact contract; ${profile.id}")
        val scope = HookRegistrationScope()
        return try {
            val native = BackgroundHighlightContract(profile, loader, names)
            contract = native
            // O must cross its post-filter subset seam rather than an inlined copy of that helper.
            check(checkNotNull(ModernXposedRuntime.activeModule()).deoptimize(native.commit)) {
                "Native lyric post-filter caller could not be deoptimized"
            }
            fun hook(method: Method, callback: ModernMethodHook) {
                check(ModernXposedRuntime.hookMethod(method, callback, scope))
            }
            hook(native.commit, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val main = Looper.myLooper() == Looper.getMainLooper()
                    if (main) guarded { prepareCommit(param) }
                    else if (!offMainReported) {
                        offMainReported = true
                        reportFailure("Native lyric commit arrived outside the main thread")
                    }
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    val call = param.extras.remove(COMMIT_CALL) as? CommitCall ?: return
                    try {
                        if (param.throwable == null) guarded {
                            native.reconcile(call.adapter, call.previous, call.retained, call.removed)
                            val active = native.current(call.adapter)
                            check(active.containsAll(call.retained)) {
                                "Native lyric commit dropped retained parents ${call.retained - active}; " +
                                    "active=$active"
                            }
                            val range = native.focusRange(call.adapter)
                            if (range != null) {
                                var first = range.first
                                var last = range.second
                                if (first in call.removed) {
                                    first = active.minOrNull() ?: -1
                                    last = active.maxOrNull() ?: -1
                                }
                                call.retained.minOrNull()?.let { anchor ->
                                    first = if (first < 0) anchor else minOf(first, anchor)
                                    last = maxOf(last, first)
                                }
                                if (first != range.first || last != range.second) {
                                    native.focus.invoke(native.callback(call.adapter), first, last)
                                }
                            }
                            scheduleTick()
                        } else sessions.remove(call.adapter)
                    } finally {
                        calls.get()?.let { stack ->
                            stack.remove(call)
                            if (stack.isEmpty()) calls.remove()
                        }
                    }
                }
            })
            hook(native.lineSubset, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    guarded { prepareFilteredCommit(param) }
                }
            })
            hook(native.focus, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val call = calls.get()?.lastOrNull() ?: return
                    if (native.callback(call.adapter) !== param.thisObject) return
                    val anchor = call.retained.minOrNull() ?: return
                    val first = param.args.getOrNull(0) as? Int ?: return
                    // Keep Apple's own focus/scroll method and its user-interaction gates, including noncontiguous rows.
                    param.args[0] = if (first < 0) anchor else minOf(first, anchor)
                }
            })
            listOf(native.document, native.detach).forEach { method ->
                hook(method, object : ModernMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        onMain {
                            param.thisObject?.let { adapter ->
                                releaseSession(adapter)
                                sessions.remove(adapter)
                            }
                        }
                    }
                })
            }
            hook(native.destroyView, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    onMain {
                        sessions.keys().filter { native.fragment(it) === param.thisObject }.forEach { adapter ->
                            releaseSession(adapter)
                            sessions.remove(adapter)
                        }
                    }
                }
            })
            hook(native.seek, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    onMain {
                        sessions.keys().filter { native.browser(it) === param.thisObject }.forEach { adapter ->
                            releaseSession(adapter)
                            sessions.remove(adapter)
                        }
                    }
                }
            })
            listOf(native.pause, native.pauseRequested).forEach { method ->
                hook(method, object : ModernMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        onMain { param.thisObject?.let { sessions[it]?.paused = true } }
                    }
                })
            }
            hook(native.resume, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    onMain {
                        param.thisObject?.let { sessions[it]?.paused = false }
                        scheduleTick()
                    }
                }
            })
            val entryFailure = names.optJSONObject("duetEntry")?.let { entry ->
                runCatching { installDuetEntry(native, entry, scope) }.exceptionOrNull()
            }
            scope.onClose {
                handler.removeCallbacks(tick)
                tickPosted = false
                sessions.clear()
                contract = null
            }
            registration = scope
            scope.activate()
            if (entryFailure != null) {
                ModernXposedRuntime.log("Duet entry unavailable; background highlighting stays active", entryFailure)
                TargetCapabilityInstall.Degraded("Duet entry unavailable: ${entryFailure.message}; ${profile.id}")
            } else TargetCapabilityInstall.Active("Native lyric highlighting; ${profile.id}")
        } catch (error: Throwable) {
            scope.close()
            contract = null
            ModernXposedRuntime.log("Background vocal native highlighting unavailable", error)
            TargetCapabilityInstall.Degraded("Background vocal highlighting unavailable: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun installDuetEntry(native: BackgroundHighlightContract, names: JSONObject, scope: HookRegistrationScope) {
        val entry = DuetEntryContract(native, loader, names)
        val diagnostics = LyricBlurDiagnostics(profile.id)
        check(checkNotNull(ModernXposedRuntime.activeModule()).deoptimize(entry.lineEvent)) {
            "Native line event must call the request constructor without inlining"
        }
        check(ModernXposedRuntime.hookMethod(entry.request, object : ModernMethodHook() {
            private var failed = false
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (failed || Looper.myLooper() != Looper.getMainLooper()) return
                // The original completion listener and ItemAnimator still own when this request is submitted.
                runCatching { entry.prepare(param, diagnostics) }.onFailure { error ->
                    failed = true
                    ModernXposedRuntime.log("Duet entry failed open", error)
                    runCatching { onFailure("Duet entry: ${error.cause?.message ?: error.message}") }
                }
            }
        }, scope))
    }

    private fun prepareCommit(param: ModernMethodHook.MethodHookParam) {
        val native = contract ?: return
        val adapter = param.thisObject ?: return
        val incoming = (param.args.firstOrNull() as? Collection<*>)?.map { it as? Int ?: return } ?: return
        val reader = native.reader.get(adapter) ?: return
        val session = sessions[adapter]?.takeIf { it.reader.get() === reader }
            ?: Session(reader).also { sessions[adapter] = it }
        val position = native.position(adapter) ?: return
        val payloadPosition = native.playbackPosition(param.args.getOrNull(2))
        // PLAYBACK_POSITION belongs to the line scheduler and includes its look-ahead offset.
        // Keep it for native replay only: background word timing follows playback, not line look-ahead.
        if (!session.replaying && payloadPosition != null) {
            session.mediaToLineOffset = position - payloadPosition
        }
        val previousHeld = session.held
        // Pause/resume may feed w() back to O. Do not turn our injected rows into permanent native inputs.
        val raw = if (session.replaying) session.nativeLines else incoming.filterNot {
            it in previousHeld && it !in session.nativeLines
        }
        if (!session.replaying) {
            session.nativeLines = raw.toList()
            session.deadline = param.args.getOrNull(1) as? Int ?: 0
            session.receivedMediaPosition = position
        }
        val current = native.current(adapter)
        val held = LinkedHashMap<Int, Window>()
        val latest = raw.maxOrNull()
        if (!session.replaying || !recovering) {
            // O can discard a background parent internally while it is still present in inLineIds.
            // Register older input rows before that filter, not only rows absent from the raw callback.
            (current + raw + previousHeld.keys).forEach { lineId ->
                if (lineId !in raw || (latest != null && lineId < latest)) {
                    val timing = previousHeld[lineId] ?: window(session, lineId)
                    if (timing?.contains(position) == true) held[lineId] = timing
                }
            }
        }
        session.held = held
        val retained = held.keys.toSet()
        if (retained.isEmpty() && previousHeld.isEmpty()) return
        val call = CommitCall(adapter, current.toSet(), raw.toSet(), previousHeld.keys.toSet(), retained)
        param.args[0] = TreeSet<Int>().apply { addAll(raw); addAll(retained) }.toList()
        // O clears w/x on an ordinary line submission; its position-only payload cannot restore held rows.
        param.args[2] = native.retainingPayload(adapter, param.args.getOrNull(2), retained, call.removed)
        val stack = calls.get() ?: ArrayList<CommitCall>().also(calls::set)
        stack += call
        param.extras[COMMIT_CALL] = call
    }

    private fun prepareFilteredCommit(param: ModernMethodHook.MethodHookParam) {
        val native = contract ?: return
        val call = calls.get()?.lastOrNull() ?: return
        if (call.filterObserved) return
        val current = native.current(call.adapter)
        if (param.args.getOrNull(0) !== current) return
        @Suppress("UNCHECKED_CAST")
        val filtered = param.args.getOrNull(1) as? TreeSet<Int> ?: error("Native lyric post-filter set is unavailable")
        call.filterObserved = true
        val nativeLines = filtered.intersect(call.nativeInput)
        call.removed = call.previousHeld - call.retained - nativeLines
        filtered.addAll(call.retained)
        // D treats a proper subset as unchanged. Let this scoped release reach O's native r/s update.
        if (current != filtered && current.containsAll(filtered)) param.result = false
    }

    private fun window(session: Session, lineId: Int): Window? {
        if (session.windows.containsKey(lineId)) return session.windows[lineId]
        val native = contract ?: return null
        val reader = session.reader.get() ?: return null
        val line = native.line(reader, lineId) ?: return null
        val begin = (native.timingBegin.invoke(line) as Number).toLong()
        var end = -1L
        listOf(native.backgroundWords, native.pronunciationBackgroundWords).forEach { method ->
            val vector = method.invoke(line, false) ?: return@forEach
            val size = (native.vectorSize.invoke(vector) as Number).toLong()
            check(size in 0L..MAX_BACKGROUND_WORDS) { "Background word vector exceeds its read bound" }
            for (index in 0L until size) {
                val wordPointer = native.vectorGet.invoke(vector, index) ?: continue
                val word = native.wordGet.invoke(wordPointer) ?: continue
                val wordBegin = (native.timingBegin.invoke(word) as Number).toLong()
                val wordEnd = (native.timingEnd.invoke(word) as Number).toLong()
                if (wordBegin >= 0L && wordEnd > wordBegin) end = maxOf(end, wordEnd)
            }
        }
        val timing = if (begin >= 0L && end > begin) Window(begin, end) else null
        session.windows[lineId] = timing
        return timing
    }

    private fun expireHeldLines() {
        val native = contract ?: return
        sessions.keys().forEach { adapter ->
            val session = sessions[adapter] ?: return@forEach
            if (session.paused || session.held.isEmpty()) return@forEach
            if (session.reader.get() !== native.reader.get(adapter)) { sessions.remove(adapter); return@forEach }
            val position = native.position(adapter) ?: return@forEach
            if (session.held.values.any { !it.contains(position) }) replay(adapter, session, position)
        }
    }

    private fun replay(adapter: Any, session: Session, position: Long) {
        val native = contract ?: return
        if (session.replaying || session.reader.get() !== native.reader.get(adapter)) return
        val elapsed = (position - session.receivedMediaPosition).coerceAtLeast(0L)
        val remaining = (session.deadline.toLong() - elapsed).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val payload = native.payload(adapter, position - session.mediaToLineOffset)
        session.replaying = true
        try {
            native.commit.invoke(adapter, session.nativeLines.toList(), remaining, payload)
        } finally {
            session.replaying = false
        }
    }

    private fun releaseSession(adapter: Any) {
        val native = contract ?: return
        val session = sessions[adapter] ?: return
        if (session.held.isEmpty()) return
        val saved = recovering
        recovering = true
        try {
            replay(adapter, session, native.position(adapter) ?: session.receivedMediaPosition)
        } finally {
            recovering = saved
        }
    }

    private fun scheduleTick() {
        if (tickPosted || registration?.isActive != true) return
        if (sessions.keys().none { sessions[it]?.let { session -> !session.paused && session.held.isNotEmpty() } == true }) return
        tickPosted = true
        handler.postDelayed(tick, TICK_MILLIS)
    }

    private fun guarded(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            val message = error.cause?.message ?: error.message ?: error.javaClass.simpleName
            if (message != lastFailure) {
                lastFailure = message
                ModernXposedRuntime.log("Background vocal native highlighting failed open: $message", error)
                reportFailure(message)
            }
            if (!recovering) {
                recovering = true
                try {
                    sessions.keys().forEach { adapter -> runCatching { releaseSession(adapter) } }
                } finally {
                    sessions.clear()
                    recovering = false
                }
            }
        }
    }

    private fun reportFailure(message: String) {
        runCatching { onFailure("Background vocal highlighting: $message") }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) guarded(action)
        else handler.post { if (registration?.isActive == true) guarded(action) }
    }

    private companion object {
        const val COMMIT_CALL = "AM++.backgroundVocalCommit"
        const val TICK_MILLIS = 50L
        const val MAX_BACKGROUND_WORDS = 4096L
    }
}

/** Exact native commit, focus and timing seams, resolved together before any callback is activated. */
private class BackgroundHighlightContract(profile: AppleMusicHostProfile, loader: ClassLoader, names: JSONObject) {
    private val members = FragmentProfileMembers(loader, names, "lyricBackgroundHighlight", FragmentMemberRequirements(
        methods = setOf("commit", "lineSubset", "notifyRow", "mapPosition", "focus", "payloadKind", "document", "detach", "lineCount", "lineAt",
            "lineGet", "lineId", "backgroundWords", "pronunciationBackgroundWords", "vectorSize", "vectorGet", "wordGet",
            "timingBegin", "timingEnd", "mediaBrowser", "mediaPosition", "seek", "pause", "pauseRequested", "resume", "destroyView"),
        fields = setOf("reader", "currentLines", "previousLines", "wordHighlightIds", "backgroundHighlightIds",
            "adapterCallback", "callbackFragment", "focusStart", "focusEnd"),
    ))
    val commit = members.method("commit")
    val lineSubset = members.method("lineSubset")
    private val notifyRow = members.method("notifyRow")
    private val mapPosition = members.method("mapPosition")
    val focus = members.method("focus")
    private val payloadKind = members.method("payloadKind")
    val document = members.method("document")
    val detach = members.method("detach")
    private val lineAt = members.method("lineAt")
    private val lineCount = members.method("lineCount")
    private val lineGet = members.method("lineGet")
    private val lineId = members.method("lineId")
    val backgroundWords = members.method("backgroundWords")
    val pronunciationBackgroundWords = members.method("pronunciationBackgroundWords")
    val vectorSize = members.method("vectorSize")
    val vectorGet = members.method("vectorGet")
    val wordGet = members.method("wordGet")
    val timingBegin = members.method("timingBegin")
    val timingEnd = members.method("timingEnd")
    private val mediaBrowser = members.method("mediaBrowser")
    private val mediaPosition = members.method("mediaPosition")
    val seek = members.method("seek")
    val pause = members.method("pause")
    val pauseRequested = members.method("pauseRequested")
    val resume = members.method("resume")
    val destroyView = members.method("destroyView")
    val reader = members.field("reader")
    private val currentLines = members.field("currentLines")
    private val previousLines = members.field("previousLines")
    private val wordHighlightIds = members.field("wordHighlightIds")
    private val backgroundHighlightIds = members.field("backgroundHighlightIds")
    private val adapterCallback = members.field("adapterCallback")
    private val callbackFragment = members.field("callbackFragment")
    private val focusStart = members.field("focusStart")
    private val focusEnd = members.field("focusEnd")

    init {
        check(profile.productionEnabled && names.getInt("contractVersion") == 4)
        val owner = commit.declaringClass
        check(listOf(document, detach, pause, pauseRequested, resume).all { it.declaringClass == owner })
        check(listOf(reader, currentLines, previousLines, wordHighlightIds, backgroundHighlightIds, adapterCallback).all { it.declaringClass == owner })
        val pairArrayType = arrayOfNulls<Pair<*, *>>(0).javaClass
        check(commit.parameterTypes.contentEquals(arrayOf(List::class.java, Int::class.javaPrimitiveType, pairArrayType)))
        check(lineSubset.declaringClass.isAssignableFrom(owner) &&
            lineSubset.parameterTypes.contentEquals(arrayOf(TreeSet::class.java, Collection::class.java)) &&
            lineSubset.returnType == Boolean::class.javaPrimitiveType)
        check(notifyRow.declaringClass.isAssignableFrom(owner) && notifyRow.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)))
        check(mapPosition.declaringClass == owner && mapPosition.parameterTypes.contentEquals(
            arrayOf(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)))
        check(focus.declaringClass == adapterCallback.type && focus.parameterTypes.contentEquals(
            arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)))
        check(payloadKind.returnType == payloadKind.declaringClass && payloadKind.parameterTypes.contentEquals(arrayOf(String::class.java)))
        check(TreeSet::class.java == currentLines.type && previousLines.type == TreeSet::class.java && wordHighlightIds.type == SparseArray::class.java &&
            backgroundHighlightIds.type == SparseArray::class.java)
        check(reader.type == lineAt.declaringClass && reader.type == lineCount.declaringClass && lineAt.returnType == lineGet.declaringClass)
        check(lineGet.returnType == lineId.declaringClass && lineId.declaringClass == backgroundWords.declaringClass)
        check(pronunciationBackgroundWords.declaringClass == backgroundWords.declaringClass)
        check(backgroundWords.returnType == vectorSize.declaringClass && vectorSize.declaringClass == vectorGet.declaringClass)
        check(vectorGet.returnType == wordGet.declaringClass)
        check(timingBegin.declaringClass == timingEnd.declaringClass && timingBegin.declaringClass.isAssignableFrom(lineGet.returnType))
        check(timingBegin.declaringClass.isAssignableFrom(wordGet.returnType))
        check(adapterCallback.type == callbackFragment.declaringClass)
        check(listOf(focusStart, focusEnd).all { it.declaringClass == callbackFragment.type && it.type == Int::class.javaPrimitiveType })
        check(mediaBrowser.declaringClass.isAssignableFrom(callbackFragment.type) && destroyView.declaringClass == callbackFragment.type)
        check(mediaPosition.declaringClass.isAssignableFrom(mediaBrowser.returnType) && seek.declaringClass == mediaPosition.declaringClass)
    }

    fun line(reader: Any, id: Int, allowLookup: Boolean = true): Any? {
        val count = (lineCount.invoke(reader) as Number).toInt()
        check(count in 0..4096) { "Lyric reader exceeds its read bound" }
        fun at(index: Int): Any? = lineAt.invoke(reader, index)?.let { lineGet.invoke(it) }
        val direct = if (id in 0 until count) at(id) else null
        direct?.takeIf { lineId.invoke(it) == id }?.let { return it }
        if (!allowLookup) return null
        return (0 until count).asSequence().mapNotNull(::at).firstOrNull { lineId.invoke(it) == id }
    }

    @Suppress("UNCHECKED_CAST") fun current(adapter: Any): Set<Int> = currentLines.get(adapter) as Set<Int>
    fun callback(adapter: Any): Any? = adapterCallback.get(adapter)
    fun fragment(adapter: Any): Any? = callback(adapter)?.let(callbackFragment::get)
    fun browser(adapter: Any): Any? = fragment(adapter)?.let { mediaBrowser.invoke(it) }
    fun position(adapter: Any): Long? = browser(adapter)?.let {
        (mediaPosition.invoke(it) as Number).toLong().takeIf { value -> value >= 0L }
    }

    fun focusRange(adapter: Any): Pair<Int, Int>? = fragment(adapter)?.let {
        Pair(focusStart.getInt(it), focusEnd.getInt(it))
    }

    @Suppress("UNCHECKED_CAST")
    fun reconcile(adapter: Any, before: Set<Int>, retained: Set<Int>, released: Set<Int>) {
        val active = currentLines.get(adapter) as TreeSet<Int>
        val changed = (retained - active) + (released intersect active)
        if (changed.isNotEmpty()) {
            val previous = previousLines.get(adapter) as TreeSet<Int>
            previous.clear()
            previous.addAll(before)
            active.removeAll(released)
            active.addAll(retained)
        }
        // Clear only our released rows, including commits that skip native payload handling.
        val words = wordHighlightIds.get(adapter) as SparseArray<*>
        val background = backgroundHighlightIds.get(adapter) as SparseArray<*>
        released.forEach { words.remove(it); background.remove(it) }
        changed.sorted().forEach { lineId ->
            val position = (mapPosition.invoke(adapter, lineId, false) as Number).toInt()
            check(position >= 0) { "Invalid native row position for retained parent $lineId" }
            notifyRow.invoke(adapter, position)
        }
    }

    fun playbackPosition(payload: Any?): Long? {
        val kind = kind("PLAYBACK_POSITION")
        return (payload as? Array<*>)?.firstNotNullOfOrNull { item ->
            val pair = item as? Pair<*, *> ?: return@firstNotNullOfOrNull null
            if (pair.first === kind) pair.second as? Long else null
        }
    }

    private fun kind(name: String): Any = checkNotNull(payloadKind.invoke(null, name))

    private fun copyIds(value: Any?): SparseArray<TreeSet<Int>> {
        val source = value as? SparseArray<*> ?: return SparseArray()
        return SparseArray<TreeSet<Int>>(source.size()).also { copy ->
            for (index in 0 until source.size()) {
                val ids = source.valueAt(index) as? Collection<*> ?: continue
                copy.put(source.keyAt(index), TreeSet(ids.mapNotNull { it as? Int }))
            }
        }
    }

    fun retainingPayload(adapter: Any, payload: Any?, retained: Set<Int>, released: Set<Int>): Array<Pair<Any?, Any?>> {
        val entries = (payload as? Array<*>)?.filterIsInstance<Pair<*, *>>().orEmpty()
        val wordKind = kind("WORD_HIGHLIGHT_IDS")
        val backgroundKind = kind("BG_WORD_HIGHLIGHT_IDS")
        val result = ArrayList<Pair<Any?, Any?>>()
        entries.filter { it.first !== wordKind && it.first !== backgroundKind }.forEach {
            result += Pair(it.first, it.second)
        }
        listOf(wordKind to wordHighlightIds, backgroundKind to backgroundHighlightIds).forEach { (key, field) ->
            val merged = copyIds(entries.lastOrNull { it.first === key }?.second)
            val source = field.get(adapter) as SparseArray<*>
            retained.forEach { lineId ->
                val oldIds = (source.get(lineId) as? Collection<*>)?.mapNotNull { it as? Int }.orEmpty()
                if (oldIds.isNotEmpty()) {
                    val ids = merged.get(lineId) ?: TreeSet<Int>().also { merged.put(lineId, it) }
                    ids.addAll(oldIds)
                }
            }
            released.forEach(merged::remove)
            result += Pair(key, merged)
        }
        return result.toTypedArray()
    }

    fun payload(adapter: Any, position: Long): Array<Pair<Any, Any>> {
        fun pair(name: String, value: Any) = Pair(kind(name), value)
        return arrayOf(pair("PLAYBACK_POSITION", position), pair("WORD_HIGHLIGHT_IDS", copyIds(wordHighlightIds.get(adapter))),
            pair("BG_WORD_HIGHLIGHT_IDS", copyIds(backgroundHighlightIds.get(adapter))))
    }
}

/** Only combines the first native layout request for two close, independently timed voices. */
private class DuetEntryContract(private val native: BackgroundHighlightContract, loader: ClassLoader, names: JSONObject) {
    private val members = FragmentProfileMembers(loader, names, "lyricBackgroundHighlight.duetEntry", FragmentMemberRequirements(
        methods = setOf("lineEvent", "lineAgent"),
        fields = setOf("eventFragment", "fragmentAdapter"),
        constructors = setOf("request"),
    ))
    val lineEvent = members.method("lineEvent")
    val request = members.constructor("request")
    private val lineAgent = members.method("lineAgent")
    private val fragment = members.field("eventFragment")
    private val adapter = members.field("fragmentAdapter")

    init {
        check(names.getInt("contractVersion") == 1)
        check(request.parameterTypes.contentEquals(arrayOf(lineEvent.declaringClass, ArrayList::class.java,
            Long::class.javaObjectType, Long::class.javaObjectType)))
        check(fragment.declaringClass == lineEvent.declaringClass && fragment.type == adapter.declaringClass &&
            adapter.type.isAssignableFrom(native.commit.declaringClass))
        check(lineAgent.declaringClass == native.timingBegin.declaringClass && lineAgent.returnType == String::class.java)
    }

    fun prepare(param: ModernMethodHook.MethodHookParam, diagnostics: LyricBlurDiagnostics) {
        val id = (param.args[1] as List<*>).singleOrNull() as? Int ?: return
        if (id < 0) return
        val wordAdapter = adapter.get(fragment.get(param.args[0])) ?: return
        if (!native.commit.declaringClass.isInstance(wordAdapter)) return
        val active = native.current(wordAdapter)
        if (id in active) return
        val reader = native.reader.get(wordAdapter) ?: return
        val first = native.line(reader, id, allowLookup = false) ?: return
        val firstAgent = lineAgent.invoke(first) as? String ?: return
        if (firstAgent.isBlank()) return
        val begin = (native.timingBegin.invoke(first) as Number).toLong()
        if (begin < 0L) return
        val end = (native.timingEnd.invoke(first) as Number).toLong()
        val position = native.position(wordAdapter) ?: return
        val otherId = listOf(id - 1, id + 1).filter { candidate ->
            if (candidate < 0 || candidate in active) return@filter false
            val other = native.line(reader, candidate, allowLookup = false) ?: return@filter false
            val otherBegin = (native.timingBegin.invoke(other) as Number).toLong()
            if (otherBegin - begin !in 0L..MAX_START_GAP_MS || otherBegin >= end || position >= otherBegin) return@filter false
            val otherAgent = lineAgent.invoke(other) as? String ?: return@filter false
            otherAgent.isNotBlank() && otherAgent != firstAgent &&
                (native.timingEnd.invoke(other) as Number).toLong() > otherBegin
        }.singleOrNull() ?: return
        // Prepares the incoming row only. Each voice still consumes its own native word events.
        param.args[1] = arrayListOf(minOf(id, otherId), maxOf(id, otherId))
        diagnostics.event("duet-entry") { "media=$position line=$id paired=$otherId" }
    }

    private companion object {
        const val MAX_START_GAP_MS = 50L
    }
}
