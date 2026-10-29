package dev.amenhancer.module.hook

import android.content.Context
import android.view.View
import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import java.lang.reflect.Modifier

/** One verified profile owns all the native seams used by the tablet coordinator and session. */
internal class FragmentDualPaneContract(val profile: AppleMusicHostProfile, private val members: FragmentProfileMembers,
    val songLayout: FragmentTabletSongLayoutContract?) {
    private val names = profile.document.getJSONObject("fragmentDualPane")
    val hostBottomDividerResource = names.getJSONObject("resources").getString("hostBottomDivider")
    private fun type(role: String) = members.type(names.getJSONObject("classes").getString(role))
    val controller = type("controller")
    val lyrics = type("lyrics")
    val state = type("state")
    private val manager = type("manager")
    private val transaction = type("transaction")
    val states: Map<String, Any> = checkNotNull(state.enumConstants).associateBy { (it as Enum<*>).name }
    val transactionConstructor = members.constructor("transaction")

    val initialize = members.method("initialize")
    val createView = members.method("createView")
    val selectPane = members.method("selectPane")
    val resume = members.method("resume")
    val destroyView = members.method("destroyView")
    val paneViewCreated = members.method("paneViewCreated")
    val context = members.method("context")
    val parent = members.method("parent")
    val fragmentId = members.method("fragmentId")
    val childManager = members.method("childManager")
    val fragmentAccessor = members.method("fragmentAccessor")
    val tagAccessor = members.method("tagAccessor")
    val stateSaved = members.method("stateSaved")
    val findFragment = members.method("findFragment")
    val replace = members.method("replace")
    val show = members.method("show")
    val remove = members.method("remove")
    val onCommit = members.method("onCommit")
    val commit = members.method("commit")
    val refresh = members.method("refresh")
    val lyricsVisibility = members.method("lyricsVisibility")
    val setPane = members.method("setPane")
    val coverFrame = members.method("coverFrame")
    val resetCover = members.method("resetCover")
    val nativeCover = members.method("nativeCover")
    val expandedSheetTop = members.method("expandedSheetTop")
    val executeBinding = members.method("executeBinding")
    val bindingRoot = members.field("bindingRoot")
    val callbackOwner = members.field("callbackOwner")
    val pane = members.field("pane")
    val entering = members.field("entering")
    val transitioning = members.field("transitioning")
    val paneLiveData = members.field("paneLiveData")
    val callback = members.field("callback")
    val behavior = members.field("behavior")
    val behaviorState = members.field("behaviorState")
    private val currentItem = members.field("currentItem")
    private val supportsLyrics = members.method("supportsLyrics")
    private val online = members.method("online")
    private val offlineLyrics = members.method("offlineLyrics")

    init {
        check(profile.family == "fragment-content")
        check(hostBottomDividerResource.isNotBlank()) { "fragmentDualPane.resources.hostBottomDivider missing" }
        check(state.isEnum && states.keys.containsAll(listOf("SONG", "LYRICS", "QUEUE")))
        check(transactionConstructor.declaringClass == transaction && transactionConstructor.parameterTypes.contentEquals(arrayOf(manager)))
        check(callback.type == coverFrame.declaringClass && callbackOwner.type == controller)
        check(behavior.type == behaviorState.declaringClass && pane.type == state)
        check(nativeCover.returnType == View::class.java && Modifier.isStatic(nativeCover.modifiers))
        check(executeBinding.declaringClass == bindingRoot.declaringClass && bindingRoot.type == View::class.java)
        check(executeBinding.parameterTypes.isEmpty() && executeBinding.returnType == Void.TYPE)
    }

    fun lyricsAvailability(controller: Any, context: Context) = FragmentTabletLyricsAvailability(
        { currentItem.get(controller)?.takeIf(offlineLyrics.declaringClass::isInstance) },
        { supportsLyrics.invoke(null, it) == true },
        { online.invoke(null, context) == true },
        { offlineLyrics.invoke(it) == true },
    )
}

/** Private binding and RecyclerView fields are version-owned too, including listener cleanup. */
internal class FragmentLyricsRuntimeContract(profile: AppleMusicHostProfile, private val members: FragmentProfileMembers) {
    private val names = profile.document.getJSONObject("fragmentLyricsRuntime")
    val binding = members.type(names.getString("bindingClass"))
    val auxiliary = names.getJSONArray("auxiliaryFields").let { a ->
        List(a.length()) { members.field(a.getString(it)) }
    }
    val listener = members.type(names.getString("listenerClass"))
    val registerListener = members.method("registerListener")
    val listenerList = members.field("listenerList")
    val attachMethod = names.getString("attachMethod")
    val detachMethod = names.getString("detachMethod")
    val refresh = members.method("refresh")
    val metrics = members.method("metrics")
    val chrome = members.method("chrome")
    val controls = members.method("controls")
    val createView = members.method("createView")
    val resume = members.method("resume")
    val destroyView = members.method("destroyView")
    val getView = members.method("getView")
    val fragmentId = members.method("fragmentId")
    val bindingField = members.field("binding")
    /** Declared only for builds whose new adapters retain an obsolete installation guard. */
    val installedPointer = names.getJSONObject("fields").takeIf { it.has("installedPointer") }
        ?.let { members.field("installedPointer") }
    val container = members.field("container")
    val recycler = members.field("recycler")
    val gradients = members.field("gradients")
    val synchronizedMetrics = listOf(members.field("syncFirst"), members.field("syncSecond"))
    val metricOffset = members.field("metricOffset")
    val metricStart = members.field("metricStart")
    val metricEnd = members.field("metricEnd")

    init {
        check(listener.isInterface && registerListener.parameterTypes.contentEquals(arrayOf(listener)))
        check(bindingField.type == binding)
        installedPointer?.let { field ->
            val pointerClass = profile.document.getJSONObject("indexed").getJSONObject("classes").getString("SONG_INFO_PTR")
            check(field.declaringClass == createView.declaringClass && field.type == members.type(pointerClass) &&
                !Modifier.isFinal(field.modifiers)) { "Invalid lyrics view installation pointer: $field" }
        }
        listOf(container, recycler, gradients).forEach { check(it.declaringClass == binding) }
        auxiliary.forEach { check(it.declaringClass == binding) }
        synchronizedMetrics.forEach { check(it.type == metricOffset.declaringClass) }
        listOf(metricOffset, metricStart, metricEnd).forEach { check(it.type == Int::class.javaPrimitiveType) }
        check(registerListener.declaringClass.isAssignableFrom(recycler.type) && listenerList.declaringClass.isAssignableFrom(recycler.type))
        listOf("listenerAttach" to attachMethod, "listenerDetach" to detachMethod).forEach { (role, name) ->
            val method = members.method(role)
            check(method.declaringClass == listener && method.name == name &&
                method.parameterTypes.contentEquals(arrayOf(View::class.java)) && method.returnType == Void.TYPE)
        }
    }
}
