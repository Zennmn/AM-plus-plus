package dev.amenhancer.module.share

import dev.amenhancer.module.MomentsLinkContract
import dev.amenhancer.module.MomentsSongMetadata
import org.json.JSONObject

/** Private, editable content only: restoring a draft must never resume a native send automatically. */
data class ShareDraft(val url: String, val title: String, val artist: String?, val artwork: String?,
    val chinaStorefront: Boolean, val editedAt: Long)

object ShareDraftCodec {
    const val RETENTION_MS = 24 * 60 * 60 * 1000L
    private const val MAX_BYTES = 32 * 1024
    fun encode(draft: ShareDraft): String {
        require(MomentsLinkContract.validate(draft.url) != null && draft.editedAt > 0)
        val title = requireNotNull(MomentsSongMetadata.title(draft.title))
        return JSONObject().put("schemaVersion", 1).put("url", draft.url).put("title", title)
            .put("artist", MomentsSongMetadata.clean(draft.artist)).put("artwork", MomentsSongMetadata.artwork(draft.artwork))
            .put("china", draft.chinaStorefront).put("editedAt", draft.editedAt).toString()
    }
    fun decode(raw: String, now: Long): ShareDraft? = runCatching {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES && now > 0)
        val row = JSONObject(raw)
        require(row.opt("schemaVersion") == 1 && row.opt("china") is Boolean)
        val edited = row.getLong("editedAt")
        require(edited > 0 && edited <= now + 300_000 && now - edited < RETENTION_MS)
        ShareDraft(requireNotNull(MomentsLinkContract.validate(row.getString("url"))),
            requireNotNull(MomentsSongMetadata.title(row.getString("title"))),
            MomentsSongMetadata.clean(row.optString("artist")), MomentsSongMetadata.artwork(row.optString("artwork")),
            row.getBoolean("china"), edited)
    }.getOrNull()
}
