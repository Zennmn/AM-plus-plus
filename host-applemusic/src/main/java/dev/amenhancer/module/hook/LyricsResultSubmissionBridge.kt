package dev.amenhancer.module.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/** Scoped ViewModel result submission. The native observer remains the only UI installer. */
internal class LyricsResultSubmissionBridge private constructor(
    private val contract: LyricsResultSubmissionContract,
    private val currentSong: CurrentSongIdentityCache,
    private val fragmentSongId: (Any?) -> Long?,
    private val nativeFragmentSongId: (Any?) -> Long?,
    private val itemSongId: (Any) -> Long?,
    private val selectReplacement: (Long, Long, Any?) -> Any?,
    private val isAlive: (Any?) -> Boolean,
    private val isCurrentSubmission: (Any) -> Boolean,
    private val documentOf: (Any) -> String?,
    private val logger: (String) -> Unit,
    private val mainHandler: Handler,
) {
    private val lock = Any()
    private var attachedFragment = java.lang.ref.WeakReference<Any>(null)
    private val pendingFragments = mutableListOf<Pair<WeakReference<Any>, WeakReference<Any>>>()
    private val postedEmptyGenerations = WeakIdentityMap<Any, Long>()
    private val diagnostics = mutableSetOf<String>()

    private fun trace(stage: String) {
        val stamp = synchronized(lock) { "$generation:$stage" }
        if (synchronized(diagnostics) { if (diagnostics.size >= 64) diagnostics.clear(); diagnostics.add(stamp) })
            logger("lyric result bridge: $stage request=${requestIdSnapshot()} current=${currentSong.current()?.details?.appleMusicId}")
    }

    /** Guards against rewriting the container write this bridge itself issues. */
    private val publishing = ThreadLocal<Boolean>()
    private val preparingIndex = ThreadLocal<Boolean>()

    @Volatile private var vm: Any? = null
    @Volatile private var container: Any? = null
    private var requestId: Long = 0L
    private var requestNativeId: Long = 0L
    private var requestCaptured = false
    private var observedPlaybackId: Long? = currentSong.current()?.details?.appleMusicId
    private var generation = 0L
    private var deliveryBoundary = 0L
    @Volatile private var closed = false
    private var nativeObserved = false
    private var nativeOriginal: Any? = null
    private var selectedPointer: Any? = null
    private var selectedDocument: String? = null
    private var selectedNativeId: Long? = null
    private var installationPending = false
    private var indexKnown = false
    private var indexedPointer: Any? = null
    private var skipNativeIndex = false
    private var indexRevision = 0L
    private var indexBuildsInFlight = 0
    private val reportedFailures = mutableSetOf<String>()

    private fun report(reason: String, error: Throwable) {
        if (synchronized(reportedFailures) { reportedFailures.add(reason) }) logger("$reason: $error")
    }

    private val pairConstructor: Constructor<*>? = runCatching {
        contract.loader.loadClass("kotlin.Pair").getConstructor(Any::class.java, Any::class.java)
    }.getOrNull()

    private val itemIdMethod: Method? = runCatching {
        contract.loadLyricsMethod.parameterTypes.first().getMethod("getId")
    }.getOrNull()

    /** Public Fragment API; used only to avoid adopting destroyed pending views. */
    private val fragmentViewMethod: Method? = runCatching {
        contract.fragmentResumeMethod.declaringClass.getMethod("getView")
    }.getOrNull()

    private val loadLyricsHook = object : ModernMethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val instance = param.thisObject ?: return
            val item = param.args.getOrNull(0) ?: return
            val nativeId = readAppleMusicId(item)
            val appleMusicId = itemSongId(item) ?: nativeId
            if (appleMusicId == null || nativeId == null) {
                synchronized(lock) { advanceRequestLocked(); requestId = 0L; requestNativeId = 0L; requestCaptured = false; clearSelectionLocked() }
                return
            }
            runCatching { onNewRequest(instance, appleMusicId, nativeId) }
                .onFailure { report("lyric request capture failed", it) }
        }
    }

    private val setValueHook = object : ModernMethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val receiver = param.thisObject ?: return
            if (!isResultContainer(receiver)) return
            if (publishing.get() == true) return
            if (closed || Looper.myLooper() != Looper.getMainLooper()) return
            val incoming = param.args.getOrNull(0)
            val emptyStamp = incoming?.let { value ->
                postedEmptyGenerations[value].also { postedEmptyGenerations.remove(value) }
            }
            if (emptyStamp != null && synchronized(lock) { emptyStamp < deliveryBoundary }) {
                param.result = null
                return
            }
            if (currentRequest() && isOurs(incoming)) return
            runCatching {
                // Recover only an unknown binding. A captured load can lead the
                // metadata cache; disagreement must not relabel its native result.
                val native = nativePointerOf(incoming)
                if (!recoverRequestBinding(native)) return@runCatching
                if (native != null && !matchesRequest(native)) {
                    param.result = null
                    return@runCatching
                }
                if (native == null) {
                    hydrateStoredResult(receiver)
                    val retained = currentDocument()
                    if (retained != null) {
                        // Pair(null, error) has no request identity. Even its
                        // arrival generation cannot prove which load failed.
                        // It must never downgrade a confirmed current document.
                        rememberSelection(retained)
                        param.result = null
                        onReplacementPublished(requestIdSnapshot())
                        trace("retained current document after empty result")
                        return@runCatching
                    }
                }
                recordNativeOriginal(native)
                if (!currentRequest()) {
                    rememberSelection(native)
                    return@runCatching
                }
                val nativeId = nativeIdForRequest() ?: return@runCatching
                val chosen = selectReplacement(requestIdSnapshot(), nativeId, native) ?: native
                val effective = canonical(chosen)
                if (effective != null && !isAlive(effective)) return@runCatching
                val instance = vmSnapshot() ?: return@runCatching
                prepareContext(instance, effective)
                publishing.set(true)
                try { ensureIndex(instance, effective) }
                finally { publishing.set(false) }
                val pair = hostPair(effective, if (effective !== native) null else pairSecond(incoming)) ?: return@runCatching
                rememberSelection(effective)
                param.args[0] = pair
            }.onFailure {
                synchronized(lock) { clearSelectionLocked() }
                report("lyric result selection failed", it)
            }

        }
    }

    private val buildIndexHook = object : ModernMethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val instance = param.thisObject ?: return
            if (instance !== vmSnapshot() || closed) return
            runCatching {
                if (publishing.get() != true) {
                    val original = param.args.firstOrNull()
                    if (recoverRequestBinding(original)) {
                        if (original == null) containerSnapshot()?.let(::hydrateStoredResult)
                        val selected = currentDocument()
                        if (original != null && !matchesRequest(original) && selected == null) {
                            param.result = null
                            return@runCatching
                        }
                        // A retained document must also survive the native failure
                        // path's subsequent null-index call.
                        if (nativeObserved) param.args[0] = selected
                    }
                }
                synchronized(lock) {
                    val pointer = param.args.firstOrNull()
                    if (indexKnown && indexedPointer === pointer &&
                        (preparingIndex.get() == true || skipNativeIndex)) {
                        // Native follow-up reuse is one-shot. Other native calls
                        // still rebuild, even for the same mutable host pointer.
                        skipNativeIndex = false
                        param.result = null
                        return@runCatching
                    }
                    invalidateIndexLocked()
                    param.extras["lyricResult.indexGeneration"] = generation
                    param.extras["lyricResult.indexRevision"] = indexRevision
                    indexBuildsInFlight++
                }
            }.onFailure {
                synchronized(lock) { invalidateIndexLocked() }
                report("lyric result index selection failed", it)
            }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            val stamp = param.extras["lyricResult.indexGeneration"] as? Long ?: return
            val revision = param.extras["lyricResult.indexRevision"] as? Long ?: return
            synchronized(lock) {
                indexBuildsInFlight--
                if (closed || param.thisObject !== vm) return
                if (generation != stamp || indexRevision != revision || indexBuildsInFlight != 0) {
                    // An overlapping native build can have touched the same map
                    // after another build completed. Do not reuse either result.
                    invalidateIndexLocked()
                    return
                }
                if (param.throwable == null) {
                    indexedPointer = param.args.firstOrNull()
                    indexKnown = true
                    skipNativeIndex = preparingIndex.get() == true
                }
            }
        }
    }

    private val postValueHook = object : ModernMethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val target = param.thisObject ?: return
            if (closed || !isResultContainer(target)) return
            val value = param.args.firstOrNull() ?: return
            // Observe only: LiveData owns its pending slot, coalescing and main
            // thread runnable. A result ID is verified at delivery, never against
            // an enqueue-time playback cache that can still describe the old song.
            val pointer = runCatching { value.javaClass.getMethod("component1").invoke(value) }.getOrElse { return }
            if (pointer == null) synchronized(lock) {
                if (!closed && container === target) postedEmptyGenerations[value] = generation
            }
        }
    }

    fun attach(fragment: Any, publish: Boolean = true) {
        if (closed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { if (!closed) attach(fragment, publish) }
            return
        }
        runCatching {
            val instance = contract.fragmentViewModelField.get(fragment) ?: return
            val id = fragmentSongId(fragment) ?: return
            val publishedId = currentSong.current()?.details?.appleMusicId
            val result = contract.lyricsResultField.get(instance) ?: return
            synchronized(lock) {
                // Keep the observed owner while its native item catches up, but
                // do not adopt another VM through an unrelated stale Fragment.
                if (publishedId != null && publishedId != id && vm !== instance) {
                    pendingFragments.removeAll { it.first.get() == null || it.second.get() == null || it.first.get() === fragment }
                    if (pendingFragments.size >= 16) pendingFragments.removeAt(0)
                    pendingFragments += WeakReference(fragment) to WeakReference(instance)
                    return
                }
                pendingFragments.removeAll { it.first.get() == null || it.second.get() == null || it.first.get() === fragment }
                if (attachedFragment.get() !== fragment && selectedPointer != null) installationPending = true
                attachedFragment = java.lang.ref.WeakReference(fragment)
                if (vm !== instance || container !== result) {
                    advanceRequestLocked()
                    vm = instance
                    container = result
                    requestId = 0L
                    requestNativeId = 0L
                    requestCaptured = false
                    clearSelectionLocked()
                }
            }
            if (publishedId != null && publishedId != id) return
            if (!recoverRequestBinding()) return
            // An old stored document must not prevent attaching the new owner.
            // Only hydrate a live result belonging to the verified current item.
            hydrateStoredResult(result)
            trace("attached observed=$nativeObserved")
            // A result write synchronously invokes the installer. Do not queue
            // another replay from inside a bridge-owned write, even on failure.
            if (publish && publishing.get() != true) onReplacementPublished(id)
        }.onFailure { report("lyric result attachment failed", it) }
    }

    /** Keep an unowned empty result from switching the current panel to embedded text. */
    fun suppressEmptyInstall(fragment: Any): Boolean {
        if (closed || Looper.myLooper() != Looper.getMainLooper()) return false
        return runCatching {
            val instance = vmSnapshot() ?: return@runCatching false
            val id = fragmentSongId(fragment) ?: return@runCatching false
            if (attachedFragment.get() !== fragment) return@runCatching false
            if (contract.fragmentViewModelField.get(fragment) !== instance) return@runCatching false
            if (verifiedCurrentId() != id) return@runCatching false
            if (!recoverRequestBinding()) return@runCatching false
            if (!currentRequest() || requestIdSnapshot() != id) return@runCatching false
            val stamp = synchronized(lock) { generation }
            val original = synchronized(lock) { nativeOriginal }
            val nativeId = nativeIdForRequest() ?: return@runCatching false
            val retained = currentDocument() ?: selectReplacement(id, nativeId, original)
                ?: return@runCatching false
            if (!isAlive(retained) || pointerId(retained) != nativeId) return@runCatching false
            synchronized(lock) {
                if (closed || vm !== instance || generation != stamp || requestId != id ||
                    verifiedCurrentId() != id) return@runCatching false
                // The empty install itself proves that the host has produced a result.
                // Keep a previously observed native document for source arbitration.
                if (!nativeObserved) {
                    nativeOriginal = null
                    nativeObserved = true
                }
                rememberSelection(retained)
                installationPending = true
            }
            // Repair the stored result and its index through the shared channel.
            // Never re-enter the Fragment installer or change its pointer argument.
            onReplacementPublished(id)
            true
        }.onFailure { report("lyric empty installation guard failed", it) }.getOrDefault(false)
    }

    /** A completed native install acknowledges delivery, not merely selection. */
    fun onInstalled(fragment: Any, pointer: Any?) {
        if (closed || pointer == null || Looper.myLooper() != Looper.getMainLooper()) return
        runCatching {
            if (attachedFragment.get() !== fragment ||
                contract.fragmentViewModelField.get(fragment) !== vmSnapshot() ||
                fragmentSongId(fragment) != requestIdSnapshot() || !matchesRequest(pointer)) return
            synchronized(lock) {
                if (pointer === selectedPointer) installationPending = false
            }
        }.onFailure { report("lyric result installation acknowledgement failed", it) }
    }

    /** The current playback identity changed; a selection for the old request is dropped. */
    fun onSongChanged(appleMusicId: Long?) {
        synchronized(lock) {
            val changed = appleMusicId != observedPlaybackId
            observedPlaybackId = appleMusicId
            if (changed && appleMusicId != requestId) {
                advanceRequestLocked()
                requestId = 0L
                requestNativeId = 0L
                requestCaptured = false
                clearSelectionLocked()
            }
        }
        if (appleMusicId != null) onReplacementPublished(appleMusicId)
    }

    /**
     * A background session has a ready replacement. The selector decides against
     * the native original the current request actually produced, so a native
     * result that is itself acceptable keeps the host document.
     */
    fun onReplacementPublished(appleMusicId: Long) {
        if (appleMusicId <= 0L) return
        mainHandler.post {
            // This is a wakeup, not a captured document. Re-read the current
            // identity and selector after recovery changes a request generation.
            if (closed || verifiedCurrentId() != appleMusicId) return@post
            retryPendingAttachment(appleMusicId)
            if (!closed && verifiedCurrentId() == appleMusicId) selectAndPublish(appleMusicId)
        }
    }

    private fun retryPendingAttachment(appleMusicId: Long) {
        val candidates = synchronized(lock) { pendingFragments.toList().asReversed() }
        for ((fragmentRef, modelRef) in candidates) {
            runCatching {
                val fragment = fragmentRef.get()
                val expectedModel = modelRef.get()
                if (fragment == null || expectedModel == null) {
                    synchronized(lock) { pendingFragments.removeAll { it.first === fragmentRef } }
                    return@runCatching
                }
                val instance = contract.fragmentViewModelField.get(fragment)
                val view = fragmentViewMethod?.invoke(fragment) as? View
                if (instance !== expectedModel || view == null) {
                    synchronized(lock) { pendingFragments.removeAll { it.first === fragmentRef } }
                    return@runCatching
                }
                if (fragmentSongId(fragment) != appleMusicId) return@runCatching
                // A load capture alone may come from a static/share-lyrics VM.
                // Prefer an existing VM only while its observed panel owner is
                // still live and belongs to this song. A detached/destroyed old
                // panel or an ownerless load must not strand a new candidate.
                val activeModel = vmSnapshot()
                val activeOwner = attachedFragment.get()
                val activeView = activeOwner?.let { fragmentViewMethod.invoke(it) as? View }
                val activeOwnerLives = activeView != null && activeView.isAttachedToWindow &&
                    contract.fragmentViewModelField.get(activeOwner) === activeModel &&
                    fragmentSongId(activeOwner) == appleMusicId
                if (activeOwnerLives && synchronized(lock) {
                        requestId == appleMusicId && vm === activeModel && vm !== instance &&
                            attachedFragment.get() === activeOwner
                    }) return@runCatching
                if (currentSong.current()?.details?.appleMusicId != appleMusicId) return
                attach(fragment)
                return
            }.onFailure { report("lyric pending owner recovery failed", it) }
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            advanceRequestLocked()
            clearSelectionLocked()
            attachedFragment.clear()
            pendingFragments.clear()
            postedEmptyGenerations.clear()
            vm = null
            container = null
        }
    }

    private fun selectAndPublish(appleMusicId: Long) {
        if (closed) return
        if (!runCatching {
                if (!recoverRequestBinding()) return@runCatching false
                containerSnapshot()?.let(::hydrateStoredResult)
                true
            }.onFailure { report("lyric result recovery failed", it) }.getOrDefault(false)) return
        if (!currentRequest() || requestIdSnapshot() != appleMusicId || !nativeObserved) {
            trace("waiting observed=$nativeObserved callback=$appleMusicId")
            return
        }
        val stamp = synchronized(lock) { generation }
        val instance = vmSnapshot() ?: return
        val fragment = attachedFragment.get()
        // The host installer checks its own item, independently of the VM.
        // Wait for the native item-update callback rather than rewriting it.
        if (fragment != null && (fragmentSongId(fragment) != appleMusicId ||
                contract.fragmentViewModelField.get(fragment) !== instance)) return
        val original = synchronized(lock) { nativeOriginal }
        val retryInstallation = fragment != null && synchronized(lock) { installationPending }
        val nativeId = nativeIdForRequest() ?: return
        val chosen = runCatching { selectReplacement(appleMusicId, nativeId, original) }.getOrNull()
            ?: currentDocument()?.takeIf { retryInstallation } ?: run {
            trace("no prepared replacement")
            return
        }
        if (!isAlive(chosen) || pointerId(chosen) != nativeId) return
        val effective = canonical(chosen) ?: return
        val target = containerSnapshot() ?: return
        val storedPointer = runCatching { nativePointerOf(contract.resultValueMethod.invoke(target)) }
            .getOrElse { report("lyric saved result read failed", it); return }
        if (effective === selectionSnapshot() && !retryInstallation && storedPointer === effective) {
            return
        }
        val pair = hostPair(effective) ?: return
        if (!currentRequest() || instance !== vmSnapshot() ||
            synchronized(lock) { generation != stamp }) return
        publishing.set(true)
        try {
            prepareContext(instance, effective)
            ensureIndex(instance, effective)
            if (closed || instance !== vmSnapshot() || target !== containerSnapshot() ||
                synchronized(lock) { generation != stamp } || !currentRequest()) return
            rememberSelection(effective)
            contract.setValueMethod.invoke(target, pair)
            trace("published replacement")
        } catch (error: Throwable) {
            // setValue stores before notifying observers. An observer failure
            // cannot roll that write back, so reconcile with the actual value.
            runCatching { reconcileStoredResult(instance, target, stamp) }
                .onFailure {
                    synchronized(lock) {
                        if (!closed && vm === instance && container === target && generation == stamp) invalidateIndexLocked()
                    }
                    report("lyric result reconciliation failed", it)
                }
            report("lyric result submission failed", error)
        } finally { publishing.set(false) }
    }

    private fun canonical(pointer: Any?): Any? {
        val previous = selectionSnapshot() ?: return pointer
        if (pointer == null || !isAlive(previous)) return pointer
        if (!isCurrentSubmission(previous)) return pointer
        // A changed host item ID needs its independently prepared pointer installed.
        if (synchronized(lock) { selectedNativeId } != nativeIdForRequest()) return pointer
        val text = documentOf(pointer) ?: return pointer
        return if (text == synchronized(lock) { selectedDocument }) previous else pointer
    }

    private fun currentRequest(): Boolean {
        val id = requestIdSnapshot()
        if (id <= 0) return false
        return verifiedCurrentId() == id
    }

    private fun verifiedCurrentId(): Long? =
        (currentSong.current()?.details?.appleMusicId ?: fragmentSongId(attachedFragment.get()))
            ?.takeIf { it > 0L }

    private fun nativeIdForRequest(): Long? {
        val id = requestIdSnapshot()
        val fragment = attachedFragment.get()
        val currentNativeId = runCatching {
            if (fragment != null && fragmentSongId(fragment) == id &&
                contract.fragmentViewModelField.get(fragment) === vmSnapshot()) nativeFragmentSongId(fragment)
            else null
        }.getOrNull()
        return currentNativeId?.takeIf { it > 0L }
            ?: synchronized(lock) { requestNativeId.takeIf { it > 0L } }
            ?: id.takeIf { it > 0L }
    }

    /** Fill a missed load binding; never overwrite an explicitly captured request. */
    private fun recoverRequestBinding(pointer: Any? = null): Boolean {
        val fragment = attachedFragment.get()
        val fragmentId = fragmentSongId(fragment)
        val publishedId = currentSong.current()?.details?.appleMusicId
        // A pointer alone never identifies current playback. If load capture
        // missed, the exact owner Fragment can independently corroborate it
        // before metadata catches up. Never override an explicit load capture.
        val corroboratedId = fragmentId?.takeIf {
            pointer != null && isAlive(pointer) && pointerId(pointer) == nativeFragmentSongId(fragment) &&
                contract.fragmentViewModelField.get(fragment) === vmSnapshot() &&
                // A recently published old item can be a stale Fragment with a
                // late result. It cannot overrule the newer playback identity.
                (publishedId == null || publishedId == it || !currentSong.canRebind(it, publishedId))
        }
        synchronized(lock) {
            if (closed || vm == null || container == null) return false
            if (requestId <= 0L || (!requestCaptured && corroboratedId != null && requestId != corroboratedId)) {
                val id = corroboratedId ?: verifiedCurrentId() ?: return false
                // Inference alone is not a new native request: keep pending empty
                // results from this request eligible across binding recovery.
                generation++
                requestId = id
                requestNativeId = if (fragmentId == id) nativeFragmentSongId(fragment) ?: id else id
                requestCaptured = false
                clearSelectionLocked()
            }
        }
        return true
    }

    private fun matchesRequest(pointer: Any): Boolean =
        isAlive(pointer) && pointerId(pointer) == nativeIdForRequest()

    /** A result without an ID can fill an empty state, but cannot disprove a usable document. */
    private fun hydrateStoredResult(result: Any) {
        if (!currentRequest()) return
        val value = contract.resultValueMethod.invoke(result) ?: return
        val pointer = nativePointerOf(value)
        if (pointer != null && !matchesRequest(pointer)) return
        // Re-read even after an observed empty result: a later native write may
        // have bypassed interception. Never mistake our selected replacement
        // for the native original used in automatic quality arbitration.
        if (nativeObserved && (pointer == null || pointer === selectionSnapshot())) return
        recordNativeOriginal(pointer)
        rememberSelection(pointer)
    }

    private fun currentDocument(): Any? = selectionSnapshot()?.takeIf(::matchesRequest)
        ?: synchronized(lock) { nativeOriginal }?.takeIf(::matchesRequest)

    private fun pointerId(pointer: Any): Long? = runCatching {
        val native = contract.pointerGetMethod.invoke(pointer) ?: return@runCatching null
        (contract.pointerAdamIdMethod.invoke(native) as Number).toLong()
    }.getOrNull()

    private fun prepareContext(instance: Any, pointer: Any?) {
        if (pointer == null || documentOf(pointer) == null) return
        val native = contract.pointerGetMethod.invoke(pointer) ?: return
        synchronized(lock) { invalidateIndexLocked() }
        contract.pointerScriptMethod.invoke(native, contract.systemLyricsLanguageMethod.invoke(instance))
    }

    private fun ensureIndex(instance: Any, pointer: Any?) {
        val previous = preparingIndex.get()
        preparingIndex.set(true)
        try { contract.buildIndexMethod.invoke(instance, pointer) }
        finally { if (previous == null) preparingIndex.remove() else preparingIndex.set(previous) }
    }

    private fun reconcileStoredResult(instance: Any, target: Any, stamp: Long) {
        fun ownsSession(): Boolean = synchronized(lock) {
            !closed && vm === instance && container === target && generation == stamp
        }
        if (!ownsSession()) return
        val value = contract.resultValueMethod.invoke(target)
        // Recovery must distinguish a real empty result from a failed read.
        val pointer = value?.javaClass?.getMethod("component1")?.invoke(value)
        if (!ownsSession()) return
        if (pointer != null && !isAlive(pointer)) {
            synchronized(lock) { if (ownsSession()) invalidateIndexLocked() }
            return
        }
        if (!ownsSession()) return
        rememberSelection(pointer)
        ensureIndex(instance, pointer)
    }

    private fun invalidateIndexLocked() {
        indexKnown = false
        indexedPointer = null
        skipNativeIndex = false
        indexRevision++
    }

    /** Binding recovery may change generation without starting a different request. */
    private fun advanceRequestLocked() {
        generation++
        deliveryBoundary = generation
    }

    private fun isResultContainer(receiver: Any): Boolean = receiver === containerSnapshot()

    private fun isOurs(value: Any?): Boolean {
        val chosen = selectionSnapshot() ?: return false
        return nativePointerOf(value) === chosen
    }

    private fun onNewRequest(instance: Any, appleMusicId: Long, nativeId: Long) {
        synchronized(lock) {
            if (closed) return
            if (vm !== instance) {
                vm = instance
                attachedFragment.clear()
                advanceRequestLocked()
                clearSelectionLocked()
            }
            container = containerOf(instance)
            advanceRequestLocked()
            requestId = appleMusicId
            requestNativeId = nativeId
            requestCaptured = true
            clearSelectionLocked()
        }
    }

    private fun recordNativeOriginal(native: Any?) {
        synchronized(lock) { nativeOriginal = native; nativeObserved = true }
    }

    private fun rememberSelection(pointer: Any?) {
        val document = pointer?.let { runCatching { documentOf(it) }.getOrNull() }
        val nativeId = pointer?.let(::pointerId)
        synchronized(lock) {
            if (selectedPointer !== pointer) installationPending = pointer != null
            selectedPointer = pointer
            selectedDocument = document
            selectedNativeId = nativeId
        }
    }

    private fun clearSelectionLocked() {
        nativeOriginal = null
        nativeObserved = false
        selectedPointer = null
        selectedDocument = null
        selectedNativeId = null
        installationPending = false
        invalidateIndexLocked()
    }

    private fun vmSnapshot(): Any? = synchronized(lock) { vm }
    private fun containerSnapshot(): Any? = synchronized(lock) { container }
    private fun selectionSnapshot(): Any? = synchronized(lock) { selectedPointer }
    private fun requestIdSnapshot(): Long = synchronized(lock) { requestId }

    private fun containerOf(instance: Any): Any? =
        runCatching { contract.lyricsResultField.get(instance) }.getOrNull()

    private fun readAppleMusicId(item: Any): Long? = itemIdMethod
        ?.let { method -> runCatching { method.invoke(item) as? String }.getOrNull() }
        ?.toLongOrNull()
        ?.takeIf { it > 0L }

    /** Pair members are read by name so no module/host Kotlin type is assumed. */
    private fun nativePointerOf(value: Any?): Any? = value
        ?.let { runCatching { it.javaClass.getMethod("component1").invoke(it) }.getOrNull() }

    private fun pairSecond(value: Any?): Any? = value
        ?.let { runCatching { it.javaClass.getMethod("component2").invoke(it) }.getOrNull() }

    /** Builds the host Pair the native observer expects, through the host loader. */
    private fun hostPair(pointer: Any?, error: Any? = null): Any? =
        pairConstructor?.let { runCatching { it.newInstance(pointer, error) }.getOrNull() }

    private fun install(registration: HookRegistrationScope, itemUpdateMethod: Method): Boolean {
        val hooks = listOf(
            itemUpdateMethod to object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.extras["lyricResult.previousItemId"] = fragmentSongId(param.thisObject)
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val fragment = param.thisObject ?: return
                    runCatching {
                        val previousId = param.extras["lyricResult.previousItemId"] as? Long
                        val id = fragmentSongId(fragment)
                        if (previousId != null && id != null && id != previousId &&
                            contract.fragmentViewModelField.get(fragment) === vmSnapshot()) {
                            synchronized(lock) {
                                // Retire only the request for the item the host
                                // replaced. A leading capture for the new (or a
                                // later) item must survive this callback.
                                if (requestId == previousId) {
                                    advanceRequestLocked()
                                    requestId = 0L
                                    requestNativeId = 0L
                                    requestCaptured = false
                                    clearSelectionLocked()
                                }
                            }
                        }
                        attach(fragment)
                    }.onFailure { report("lyric result item-update recovery failed", it) }
                }
            },
            contract.fragmentResumeMethod to object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null) param.thisObject?.let {
                        attach(it)
                    }
                }
            },
            contract.loadLyricsMethod to loadLyricsHook,
            contract.setValueMethod to setValueHook,
            contract.postValueMethod to postValueHook,
            contract.buildIndexMethod to buildIndexHook,
            contract.clearedMethod to object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val instance = param.thisObject ?: return
                    synchronized(lock) {
                        pendingFragments.removeAll { reference ->
                            reference.first.get() == null || reference.second.get() == null || reference.second.get() === instance
                        }
                    }
                    if (param.thisObject !== vmSnapshot()) return
                    synchronized(lock) {
                        advanceRequestLocked()
                        requestId = 0L
                        requestNativeId = 0L
                        requestCaptured = false
                        clearSelectionLocked()
                        vm = null
                        container = null
                        attachedFragment.clear()
                    }
                    currentSong.current()?.details?.appleMusicId?.let(::onReplacementPublished)
                }
            },
        )
        for ((method, callback) in hooks) {
            val hooked = runCatching {
                method.isAccessible = true
                ModernXposedRuntime.hookMethod(method, callback, registration)
            }.getOrDefault(false)
            if (!hooked) {
                logger("lyric result submission hook failed: $method")
                return false
            }
        }
        registration.onClose { close() }
        return true
    }

    companion object {
        fun create(
            symbols: TargetSymbolResolver,
            registration: HookRegistrationScope,
            currentSong: CurrentSongIdentityCache,
            fragmentSongId: (Any?) -> Long?,
            nativeFragmentSongId: (Any?) -> Long?,
            itemSongId: (Any) -> Long?,
            selectReplacement: (Long, Long, Any?) -> Any?,
            isAlive: (Any?) -> Boolean,
            isCurrentSubmission: (Any) -> Boolean,
            documentOf: (Any) -> String?,
            logger: (String) -> Unit,
        ): LyricsResultSubmissionBridge? {
            val contract = LyricsResultSubmissionContract.create(symbols, logger) ?: return null
            val itemUpdate = symbols.resolve(AppleMusicSymbols.LyricsItemUpdateMethod)
            if (itemUpdate !is TargetResolution.Found || itemUpdate.match != SymbolMatch.VERSION_PROFILE ||
                itemUpdate.profileId != contract.profileId) {
                logger("lyric result submission requires the exact item-update entry: ${itemUpdate.summary}")
                return null
            }
            val bridge = LyricsResultSubmissionBridge(
                contract = contract,
                currentSong = currentSong,
                fragmentSongId = fragmentSongId,
                nativeFragmentSongId = nativeFragmentSongId,
                itemSongId = itemSongId,
                selectReplacement = selectReplacement,
                isAlive = isAlive,
                isCurrentSubmission = isCurrentSubmission,
                documentOf = documentOf,
                logger = logger,
                mainHandler = Handler(Looper.getMainLooper()),
            )
            if (bridge.pairConstructor == null || bridge.itemIdMethod == null || bridge.fragmentViewMethod == null) {
                logger("lyric result submission public Pair, item ID or Fragment view API unavailable")
                return null
            }
            return bridge.takeIf { it.install(registration, itemUpdate.value) }
        }
    }
}

