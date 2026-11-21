package dev.amenhancer.module.share

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import dev.amenhancer.module.MomentsLinkContract as Contract
import dev.amenhancer.module.MomentsSongMetadata

/** Sends the share request from the AM++ confirmation activity under the module UID. */
internal object MomentsShareRelay {
    const val CALLBACK = "share_callback"
    const val DETAIL = "share_detail"
    fun dispatch(context: Context, input: Bundle) {
        @Suppress("DEPRECATION")
        val callback = requireNotNull(input.getParcelable<ResultReceiver>(CALLBACK))
        val url = requireNotNull(Contract.validate(input.getString(Contract.URL)))
        val title = requireNotNull(MomentsSongMetadata.title(input.getString(Contract.TITLE)))
        val id = requireNotNull(input.getString(Contract.REQUEST_ID)?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,100}")) })
        val request = Intent(Contract.OPEN).setPackage(Contract.INTERMEDIARY_PACKAGE)
            .putExtra(Contract.URL, url).putExtra(Contract.TITLE, title)
            .putExtra(Contract.ARTIST, MomentsSongMetadata.clean(input.getString(Contract.ARTIST)))
            .putExtra(Contract.ARTWORK, MomentsSongMetadata.artwork(input.getString(Contract.ARTWORK)))
            .putExtra(Contract.REQUEST_ID, id)
        // Post first so the calling Binder identity can never become the broadcast sender.
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                context.applicationContext.sendOrderedBroadcast(request, null, object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent?) {
                        callback.send(resultCode, Bundle().apply {
                            resultData?.takeIf { it.matches(Regex("[0-9A-Za-z./_-]{1,80}")) }?.let { putString(DETAIL, it) }
                        })
                    }
                }, handler, 0, null, null)
            } catch (_: Exception) { callback.send(Contract.RESULT_LAUNCH, Bundle()) }
        }
    }
}
