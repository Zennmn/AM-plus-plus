package dev.amenhancer.module.hook

import android.content.res.Resources
import android.view.View
import android.widget.TextView
import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import org.json.JSONObject

/** Reuses the verified line seams without depending on the blur feature or retaining native pointers. */
internal fun installLineLyricAgentAlignment(
    profile: AppleMusicHostProfile,
    loader: ClassLoader,
    resources: Resources,
    enabled: () -> Boolean,
): TargetCapabilityInstall {
    if (!profile.productionEnabled || !profile.capability("lineLyricAgentAlignment")) {
        return TargetCapabilityInstall.Unsupported("Line lyric agent alignment has no verified host contract")
    }
    val scope = HookRegistrationScope()
    return try {
        val names = profile.document.getJSONObject("lyricBlur")
        val roles = setOf("lineBind", "lineType", "lineMap", "lineReader", "lineToken", "readerCount", "readerLine", "lineGet")
        // FragmentProfileMembers resolves every supplied role; select only this repair's dependencies.
        val selected = JSONObject().put("methods", JSONObject().apply {
            roles.forEach { put(it, names.getJSONObject("methods").getJSONObject(it)) }
        }).put("fields", JSONObject().put("holderView", names.getJSONObject("fields").getJSONObject("holderView")))
        val members = FragmentProfileMembers(loader, selected, "lineLyricAgentAlignment",
            FragmentMemberRequirements(methods = roles, fields = setOf("holderView")))
        val bind = members.method("lineBind")
        val rowType = members.method("lineType")
        val mapPosition = members.method("lineMap")
        val read = members.method("lineReader")
        val token = members.method("lineToken")
        val count = members.method("readerCount")
        val lineAt = members.method("readerLine")
        val lineGet = members.method("lineGet")
        val holderView = members.field("holderView")
        check(names.getJSONObject("rowTypes").getInt("line") == 0)
        check(bind.parameterTypes.size == 2 && holderView.declaringClass.isAssignableFrom(bind.parameterTypes[0]))
        check(lineAt.declaringClass.isAssignableFrom(read.returnType) && lineGet.declaringClass == lineAt.returnType)
        val ids = listOf("song_lyrics_line", "lyrics_pronunciation_line", "lyrics_translation_line").map {
            resources.getIdentifier(it, "id", profile.packageName).also { id -> check(id != 0) { "Missing lyric view: $it" } }
        }

        // These names are the stable JavaCPP ABI, not obfuscated Android view/model members.
        val lineAgent = lineGet.returnType.getMethod("getAgent")
        val lineText = lineGet.returnType.getMethod("getHtmlLineText")
        val songGet = token.returnType.getMethod("get")
        val timing = songGet.returnType.getMethod("getTiming")
        val agents = songGet.returnType.getMethod("getAgents")
        val agentCount = agents.returnType.getMethod("size")
        val agentAt = agents.returnType.getMethod("get", Long::class.javaPrimitiveType)
        val agentGet = agentAt.returnType.getMethod("get")
        val agentId = agentGet.returnType.getMethod("getId")
        val agentType = agentGet.returnType.getMethod("getType")
        var failureLogged = false

        fun rightSide(adapter: Any, position: Int): Boolean? {
            if (!enabled()) return null
            val document = token.invoke(adapter) ?: return null
            val song = songGet.invoke(document) ?: return null
            if ((timing.invoke(song) as Number).toInt() != 1) return null
            val reader = read.invoke(adapter) ?: return null
            val index = (mapPosition.invoke(adapter, position, true) as Number).toInt()
            val size = (count.invoke(reader) as Number).toInt()
            if (size !in 1..4096 || index !in 0 until size) return null
            fun line(index: Int): Any? = lineAt.invoke(reader, index)?.let { lineGet.invoke(it) }
            val current = line(index) ?: return null
            val currentAgent = lineAgent.invoke(current) as? String ?: return null
            if (currentAgent.isEmpty()) return null
            val vector = agents.invoke(song) ?: return null
            val vectorSize = (agentCount.invoke(vector) as Number).toLong()
            if (vectorSize !in 2L..64L) return null
            val voices = linkedMapOf<String, String>()
            for (ordinal in 0L until vectorSize) {
                val agent = agentAt.invoke(vector, ordinal)?.let { agentGet.invoke(it) } ?: return null
                val type = (agentType.invoke(agent) as? Enum<*>)?.name
                if (type == "Person" || type == "Other") {
                    val id = agentId.invoke(agent) as? String ?: return null
                    if (id.isEmpty() || voices.put(id, type) != null || voices.size > 2) return null
                }
            }
            // This repair covers duets. Group, unknown and larger casts retain the native line layout.
            if (voices.size != 2 || currentAgent !in voices) return null
            val people = voices.filterValues { it == "Person" }.keys
            if (people.isEmpty()) return null
            for (ordinal in 0 until size) {
                val first = line(ordinal) ?: return null
                val firstAgent = lineAgent.invoke(first) as? String
                if (firstAgent in people) {
                    val firstText = lineText.invoke(first) as? String ?: return null
                    return lineLyricTextIsRtl(firstText) xor (currentAgent != firstAgent)
                }
            }
            return null
        }

        check(ModernXposedRuntime.hookMethod(bind, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val adapter = param.thisObject ?: return
                val holder = param.args.getOrNull(0) ?: return
                val position = param.args.getOrNull(1) as? Int ?: return
                runCatching {
                    if ((rowType.invoke(adapter, position) as Number).toInt() != 0) return@runCatching
                    val root = holderView.get(holder) as? View ?: return@runCatching
                    val views = ids.map { root.findViewById<TextView>(it) ?: return@runCatching }
                    val right = runCatching { rightSide(adapter, position) }.onFailure {
                        if (!failureLogged) {
                            failureLogged = true
                            ModernXposedRuntime.log("Line lyric agent lookup failed open", it)
                        }
                    }.getOrNull()
                    val nativeRight = right == null && lineLyricTextIsRtl(views.first().text)
                    views.forEachIndexed { index, view ->
                        val alignment = when {
                            right != null -> if ((view.layoutDirection == View.LAYOUT_DIRECTION_RTL) == right)
                                View.TEXT_ALIGNMENT_VIEW_START else View.TEXT_ALIGNMENT_VIEW_END
                            index == 0 -> View.TEXT_ALIGNMENT_GRAVITY
                            lineLyricTextIsRtl(view.text) == (right ?: nativeRight) -> View.TEXT_ALIGNMENT_TEXT_START
                            else -> View.TEXT_ALIGNMENT_TEXT_END
                        }
                        // Always assign, even when Apple's text cache skipped its own direction update.
                        view.textAlignment = alignment
                        if (view.isLaidOut && !view.isLayoutRequested) {
                            alignLineLyricPivot(view)
                        } else {
                            view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                                    oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
                                    v.removeOnLayoutChangeListener(this)
                                    // Read the current binding, so a queued callback cannot restore an old side.
                                    alignLineLyricPivot(view)
                                }
                            })
                        }
                    }
                }.onFailure {
                    if (!failureLogged) {
                        failureLogged = true
                        ModernXposedRuntime.log("Line lyric alignment failed open", it)
                    }
                }
            }
        }, scope)) { "Line lyric binding hook unavailable" }
        scope.activate()
        TargetCapabilityInstall.Active("Line lyric duet alignment installed; ${profile.id}")
    } catch (error: Throwable) {
        scope.close()
        ModernXposedRuntime.log("Line lyric duet alignment unavailable", error)
        TargetCapabilityInstall.Degraded("Line lyric duet alignment unavailable: ${error.message ?: error.javaClass.simpleName}")
    }
}

/** Match the host's first-letter predicate without changing TextView text direction. */
private fun lineLyricTextIsRtl(text: CharSequence): Boolean {
    val first = text.indices.firstOrNull { Character.isLetter(Character.codePointAt(text, it)) } ?: return false
    return text[first] in '\u0590'..'\u06ff'
}

private fun alignLineLyricPivot(view: TextView) {
    val right = when (view.textAlignment) {
        View.TEXT_ALIGNMENT_VIEW_START -> view.layoutDirection == View.LAYOUT_DIRECTION_RTL
        View.TEXT_ALIGNMENT_VIEW_END -> view.layoutDirection != View.LAYOUT_DIRECTION_RTL
        View.TEXT_ALIGNMENT_TEXT_END -> !lineLyricTextIsRtl(view.text)
        else -> lineLyricTextIsRtl(view.text)
    }
    view.pivotX = if (right) view.width.toFloat() else 0f
    view.pivotY = view.height.toFloat()
}
