package dev.amenhancer.module.share

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.text.util.Linkify
import android.widget.EditText
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.PopupWindow
import java.util.UUID
import java.util.concurrent.Executors
import android.text.Editable
import android.text.TextWatcher
import android.widget.ScrollView
import dev.amenhancer.module.MomentsSongMetadata
import dev.amenhancer.module.MomentsLinkContract as Contract
import dev.amenhancer.module.i18n.ModuleText

/** Opens NetEase Cloud Music's native share panel with the original Apple Music URL. */
class MomentsShareActivity : Activity() {
    internal var metadataResolver: (String) -> MomentsSongMetadata? = AppleSongMetadataLookup()::lookup
    private lateinit var panel: MomentsSharePanel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        panel = MomentsSharePanel(this, intent).also { it.metadataResolver = metadataResolver }
        setContentView(panel.buildView(savedInstanceState))
    }
    override fun onSaveInstanceState(outState: Bundle) { panel.saveState(outState); super.onSaveInstanceState(outState) }
    override fun onDestroy() { panel.close(); super.onDestroy() }
}

private class MomentsSharePanel(private val owner: MomentsShareActivity, private val intent: Intent) : android.content.ContextWrapper(owner) {
    private var closed = false
    private val isDestroyed get() = closed || owner.isDestroyed
    private val isFinishing get() = owner.isFinishing
    private val handler = Handler(Looper.getMainLooper())
    private var chinaStorefront = false
    private var attempt: ShareAttempt? = null
    private var pending = false
    private var shareStarted = false
    private var draftEditsProtected = false
    private var draftRevision = 0
    private var savedDraft: ShareDraft? = null
    private val draftStore by lazy { ShareDraftStore(applicationContext) }
    private val draftExecutor = ShareDraftStore.io
    private var refreshDraftActions: () -> Unit = {}
    private var artworkUrl: String? = null
    private var helpPopup: PopupWindow? = null
    private val metadataExecutor = Executors.newSingleThreadExecutor { Thread(it, "AMPlusPlus-share-metadata").apply { isDaemon = true } }
    internal var metadataResolver: (String) -> MomentsSongMetadata? = AppleSongMetadataLookup()::lookup
    private lateinit var titleInput: EditText
    private lateinit var artistInput: EditText
    fun buildView(savedInstanceState: Bundle?): android.view.View {
        val originalUrl = Contract.extract(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
        chinaStorefront = savedInstanceState?.getBoolean(Contract.CHINA_STOREFRONT)
            ?: intent.getBooleanExtra(Contract.CHINA_STOREFRONT, false)
        val url = originalUrl
        artworkUrl = MomentsSongMetadata.artwork(savedInstanceState?.getString(Contract.ARTWORK) ?: intent.getStringExtra(Contract.ARTWORK))
        val initialTitle = savedInstanceState?.getString(Contract.TITLE)
            ?: MomentsSongMetadata.title(intent.getStringExtra(Contract.TITLE))
            ?: MomentsSongMetadata.title(intent.getStringExtra(Intent.EXTRA_SUBJECT)).orEmpty()
        val initialArtist = savedInstanceState?.getString(Contract.ARTIST)
            ?: MomentsSongMetadata.clean(intent.getStringExtra(Contract.ARTIST)).orEmpty()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        layout.addView(TextView(this).apply { text = ModuleText.WECHAT_MOMENTS_CARD.text(); textSize = 22f })
        titleInput = EditText(this).apply { hint = ModuleText.SHARE_SONG_TITLE.text(); setSingleLine(true); setText(initialTitle) }
        artistInput = EditText(this).apply { hint = ModuleText.SHARE_SONG_ARTIST.text(); setSingleLine(true); setText(initialArtist) }
        layout.addView(titleInput); layout.addView(artistInput)
        val linkPreview = TextView(this).apply { text = Contract.withChinaStorefront(url, chinaStorefront).orEmpty(); autoLinkMask = Linkify.WEB_URLS; setTextIsSelectable(true) }
        val regionSwitch = android.widget.Switch(this).apply {
            text = ModuleText.SHARE_CHINA_STOREFRONT_ONCE.text(); isChecked = chinaStorefront
            setOnCheckedChangeListener { _, checked ->
                chinaStorefront = checked
                linkPreview.text = Contract.withChinaStorefront(url, checked).orEmpty()
            }
        }
        layout.addView(LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(regionSwitch, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val target = (48 * resources.displayMetrics.density).toInt()
            addView(Button(this@MomentsSharePanel).apply {
                text = "ⓘ"
                textSize = 22f
                contentDescription = ModuleText.SETTINGS_DETAILS_ACTION.text()
                setPadding(0, 0, 0, 0)
                setOnClickListener { showHelp(this) }
            }, LinearLayout.LayoutParams(target, target))
        })
        layout.addView(linkPreview)
        val status = TextView(this).apply { textSize = 15f }
        layout.addView(status)
        layout.addView(Button(this).apply {
            text = ModuleText.COPY_SONG_LINK.text(); isEnabled = url != null
            setOnClickListener { Contract.withChinaStorefront(url, chinaStorefront)?.let { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Apple Music", it)) } }
        })
        val open = Button(this).apply { text = ModuleText.WECHAT_SHARE_OPEN.text(); isEnabled = url != null && MomentsSongMetadata.title(initialTitle) != null }
        titleInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                open.isEnabled = !pending && url != null && MomentsSongMetadata.title(s?.toString()) != null
                refreshDraftActions()
            }
        })
        val draftActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val saveDraft = Button(this).apply { text = ModuleText.SHARE_DRAFT_SAVE.text() }
        val restoreDraft = Button(this).apply { text = ModuleText.SHARE_DRAFT_RESTORE.text(); visibility = android.view.View.GONE }
        val clearDraft = Button(this).apply { text = ModuleText.SHARE_DRAFT_CLEAR.text() }
        listOf(saveDraft, restoreDraft, clearDraft).forEach { draftActions.addView(it) }
        layout.addView(draftActions)
        refreshDraftActions = {
            saveDraft.isEnabled = !pending && url != null && MomentsSongMetadata.title(titleInput.text.toString()) != null
            restoreDraft.isEnabled = !pending && savedDraft != null
            restoreDraft.visibility = if (savedDraft != null) android.view.View.VISIBLE else android.view.View.GONE
            clearDraft.isEnabled = !pending && savedDraft != null
        }
        saveDraft.setOnClickListener {
            if (!pending && url != null) {
                val title = MomentsSongMetadata.title(titleInput.text.toString())
                if (title != null) {
                    val snapshot = ShareDraft(url, title, MomentsSongMetadata.clean(artistInput.text.toString()),
                        artworkUrl, chinaStorefront, System.currentTimeMillis())
                    val revision = ++draftRevision
                    draftExecutor.execute {
                        val success = runCatching { draftStore.save(snapshot) }.isSuccess
                        handler.post {
                            if (!isDestroyed && revision == draftRevision) {
                                if (success) savedDraft = snapshot
                                if (!pending) status.text = (if (success) ModuleText.SHARE_DRAFT_SAVED else ModuleText.SHARE_DRAFT_FAILED).text()
                                refreshDraftActions()
                            }
                        }
                    }
                }
            }
        }
        restoreDraft.setOnClickListener {
            val draft = savedDraft
            if (!pending && draft != null) {
                draftEditsProtected = true
                titleInput.setText(draft.title); artistInput.setText(draft.artist.orEmpty()); artworkUrl = draft.artwork
                regionSwitch.isChecked = draft.chinaStorefront
                status.text = ModuleText.SHARE_DRAFT_RESTORED.text()
                refreshDraftActions()
            }
        }
        clearDraft.setOnClickListener {
            if (!pending && savedDraft != null) {
                val revision = ++draftRevision
                draftExecutor.execute {
                    val success = runCatching { draftStore.clear() }.isSuccess
                    handler.post {
                        if (!isDestroyed && revision == draftRevision) {
                            if (success) savedDraft = null
                            if (!pending) status.text = (if (success) ModuleText.SHARE_DRAFT_CLEARED else ModuleText.SHARE_DRAFT_FAILED).text()
                            refreshDraftActions()
                        }
                    }
                }
            }
        }
        if (url != null) {
            val revision = draftRevision
            draftExecutor.execute {
                val draft = draftStore.load(url)
                handler.post {
                    if (!isDestroyed && revision == draftRevision) {
                        savedDraft = draft
                        if (draft != null && !pending) status.text = ModuleText.SHARE_DRAFT_AVAILABLE.text()
                        refreshDraftActions()
                    }
                }
            }
        }
        refreshDraftActions()
        layout.addView(open)
        val stop = Button(this).apply { text = ModuleText.SHARE_STOP_WAITING.text(); visibility = android.view.View.GONE }
        layout.addView(stop)
        layout.isFocusableInTouchMode = true; layout.requestFocus()
        val content = ScrollView(this).apply { addView(layout) }
        if (url == null) status.text = ModuleText.WECHAT_LINK_REQUIRED.text()
        val begin = begin@{
            if (pending || url == null) return@begin
            val title = MomentsSongMetadata.title(titleInput.text.toString()) ?: return@begin
            val artist = MomentsSongMetadata.clean(artistInput.text.toString())
            val sharedUrl = Contract.withChinaStorefront(url, chinaStorefront) ?: return@begin
            shareStarted = true; pending = true; open.isEnabled = false; regionSwitch.isEnabled = false
            refreshDraftActions()
            val current = ShareAttempt(sharedUrl, title, artist, artworkUrl, status, open, regionSwitch, stop)
            attempt = current
            current.start()
        }
        open.setOnClickListener { begin() }
        if (url != null && MomentsSongMetadata.songId(url) != null && (initialTitle.isBlank() || initialArtist.isBlank() || artworkUrl == null)) {
            status.text = (if (open.isEnabled) ModuleText.SHARE_METADATA_BACKGROUND else ModuleText.SHARE_METADATA_LOADING).text()
            metadataExecutor.execute {
                val metadata = runCatching { metadataResolver(originalUrl!!) }.getOrNull()
                handler.post {
                    if (isDestroyed || isFinishing || shareStarted) return@post
                    if (metadata != null && !draftEditsProtected) {
                        artworkUrl = MomentsSongMetadata.artwork(metadata.artworkUrl)
                        if (initialTitle.isBlank() && titleInput.text.toString() == initialTitle) titleInput.setText(metadata.title)
                        if (initialArtist.isBlank() && artistInput.text.toString() == initialArtist) artistInput.setText(metadata.artist.orEmpty())
                    }
                    open.isEnabled = !pending && MomentsSongMetadata.title(titleInput.text.toString()) != null
                    refreshDraftActions()
                    if (!pending && !draftEditsProtected) status.text = when {
                        MomentsSongMetadata.title(titleInput.text.toString()) == null -> ModuleText.SHARE_METADATA_MISSING.text()
                        artworkUrl == null -> ModuleText.SHARE_ARTWORK_MISSING.text()
                        else -> ""
                    }
                }
            }
        }
        return content
    }

    private fun showHelp(anchor: android.view.View) {
        if (helpPopup?.isShowing == true) {
            helpPopup?.dismiss()
            return
        }
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val width = minOf((300 * density).toInt(), resources.displayMetrics.widthPixels - 2 * padding)
        if (width <= 0) return
        val description = TextView(this).apply {
            text = ModuleText.WECHAT_SHARE_NOTICE.text()
            textSize = 15f
            setPadding(padding, padding, padding, padding)
        }
        description.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.UNSPECIFIED,
        )
        val origin = IntArray(2).also(anchor::getLocationOnScreen)
        val visible = android.graphics.Rect().also(anchor::getWindowVisibleDisplayFrame)
        val gap = (8 * density).toInt()
        val enoughRoomAbove = origin[1] - visible.top >= description.measuredHeight + gap
        val offset = if (enoughRoomAbove) -anchor.height - description.measuredHeight - gap else gap
        helpPopup = PopupWindow(description, width, description.measuredHeight, true).apply {
            val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            description.setTextColor(if (night) 0xFFF3F3F3.toInt() else 0xFF202023.toInt())
            setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(if (night) 0xFF333338.toInt() else 0xFFF6F6FA.toInt())
                cornerRadius = 16 * density
            })
            elevation = 6 * density
            isOutsideTouchable = true
            showAsDropDown(anchor, 0, offset, android.view.Gravity.END)
        }
    }
    /** A local deadline is independent of ordered-broadcast delivery or remote main-thread health. */
    private inner class ShareAttempt(
        val url: String, val title: String, val artist: String?, val artwork: String?,
        val status: TextView, val button: Button, val region: android.widget.Switch, val stop: Button,
    ) {
        val id = UUID.randomUUID().toString()
        private val deadline = SystemClock.elapsedRealtime() + Contract.REQUEST_TIMEOUT_MS
        private var lastCode = 0
        private var detail: String? = null
        private val valid get() = attempt === this && !isDestroyed && !isFinishing
        private val timeout = Runnable { if (valid) fail(lastCode) }
        private val poll = Runnable { if (valid) send() }
        private val tick = object : Runnable {
            override fun run() {
                if (!valid) return
                val seconds = ((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000
                status.text = ModuleText.SHARE_WAITING_SECONDS.text(seconds)
                handler.postDelayed(this, 1000)
            }
        }
        fun start() {
            stop.visibility = android.view.View.VISIBLE
            stop.setOnClickListener { if (valid) finish(ModuleText.SHARE_WAIT_STOPPED.text()) }
            tick.run(); handler.postDelayed(timeout, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            try {
                val launch = NetEaseShareLaunch.resolve(packageManager)
                if (launch == null) fail(Contract.RESULT_MISSING)
                else {
                    owner.startActivity(launch)
                    handler.postDelayed(poll, 800)
                }
            } catch (_: Exception) {
                fail(Contract.RESULT_LAUNCH)
            }
        }

        fun dispose() {
            handler.removeCallbacks(timeout)
            handler.removeCallbacks(poll)
            handler.removeCallbacks(tick)
        }

        private fun finish(message: String) {
            if (!valid) return
            dispose(); attempt = null; pending = false
            button.isEnabled = true; region.isEnabled = true; stop.visibility = android.view.View.GONE
            status.text = message
            refreshDraftActions()
        }
        private fun send() {
            val callback = object : android.os.ResultReceiver(handler) {
                override fun onReceiveResult(code: Int, data: Bundle?) {
                    if (!valid) return
                    lastCode = code; detail = data?.getString(MomentsShareRelay.DETAIL)
                    when {
                        code == Contract.RESULT_OPENED -> finish(ModuleText.WECHAT_EDITOR_OPENED.text())
                        code in listOf(0, Contract.RESULT_NOT_READY, Contract.RESULT_PENDING) -> {
                            handler.removeCallbacks(poll)
                            if (SystemClock.elapsedRealtime() < deadline) handler.postDelayed(poll, 500) else fail(code)
                        }
                        else -> fail(code)
                    }
                }
            }
            val input = Bundle().apply {
                putString(Contract.URL, url); putString(Contract.TITLE, title); putString(Contract.ARTIST, artist)
                putString(Contract.ARTWORK, artwork); putString(Contract.REQUEST_ID, id)
                putParcelable(MomentsShareRelay.CALLBACK, callback)
            }
            MomentsShareRelay.dispatch(this@MomentsSharePanel, input)
        }
        private fun fail(code: Int) {
            if (!valid) return
            val message = when (code) {
                0 -> ModuleText.WECHAT_BRIDGE_NOT_LOADED
                Contract.RESULT_NOT_READY -> ModuleText.WECHAT_BRIDGE_NO_ACTIVITY
                Contract.RESULT_PENDING -> ModuleText.WECHAT_PANEL_NOT_VISIBLE
                Contract.RESULT_UNSUPPORTED -> ModuleText.WECHAT_VERSION_UNSUPPORTED
                Contract.RESULT_SIGNATURE -> ModuleText.WECHAT_SIGNATURE_UNSUPPORTED
                Contract.RESULT_CONTRACT -> ModuleText.WECHAT_CONTRACT_UNAVAILABLE
                Contract.RESULT_LAUNCH -> ModuleText.WECHAT_PANEL_LAUNCH_FAILED
                Contract.RESULT_BUSY -> ModuleText.WECHAT_SHARE_BUSY
                Contract.RESULT_RELAY -> ModuleText.SHARE_RELAY_UNAVAILABLE
                Contract.RESULT_MISSING -> ModuleText.WECHAT_MISSING
                else -> ModuleText.WECHAT_BRIDGE_UNAVAILABLE
            }.text()
            val safeDetail = detail?.takeIf { it.matches(Regex("[0-9A-Za-z./_-]{1,80}")) }
            val text = "$message [N$code]" + (safeDetail?.let { " ($it)" } ?: "")
            finish(text); Toast.makeText(this@MomentsSharePanel, text, Toast.LENGTH_LONG).show()
        }
    }
    fun saveState(outState: Bundle) {
        outState.putBoolean(Contract.CHINA_STOREFRONT, chinaStorefront)
        outState.putString(Contract.ARTWORK, artworkUrl)
        outState.putString(Contract.TITLE, titleInput.text.toString())
        outState.putString(Contract.ARTIST, artistInput.text.toString())
    }
    fun close() { helpPopup?.dismiss(); helpPopup = null; attempt?.dispose(); attempt = null; closed = true; draftRevision++; metadataExecutor.shutdownNow(); handler.removeCallbacksAndMessages(null) }
}
