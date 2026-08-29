package dev.amenhancer.module.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import dev.amenhancer.module.CurrentSongIdentityProtocol
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.ModuleConstants
import java.util.UUID

/** Issues one user-initiated request for the latest current-song details observed by the target hook. */
internal class CurrentSongIdentityRequester(
    context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val applicationContext = context.applicationContext
    private var activeRequest: ActiveRequest? = null
    private val timeout = Runnable {
        activeRequest?.let { request -> complete(request.token, null) }
    }
    private val resultReceiver = object : ResultReceiver(handler) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            val token = resultData?.getString(CurrentSongIdentityProtocol.EXTRA_REQUEST_TOKEN) ?: return
            val appleMusicId = resultData
                ?.getLong(CurrentSongIdentityProtocol.EXTRA_APPLE_MUSIC_ID, 0L)
                ?.takeIf { resultCode == CurrentSongIdentityProtocol.RESULT_AVAILABLE && it > 0L }
            complete(
                token,
                appleMusicId?.let {
                    CurrentSongDetails(
                        appleMusicId = it,
                        title = resultData.stringOrNull(CurrentSongIdentityProtocol.EXTRA_SONG_TITLE),
                        artist = resultData.stringOrNull(CurrentSongIdentityProtocol.EXTRA_SONG_ARTIST),
                        album = resultData.stringOrNull(CurrentSongIdentityProtocol.EXTRA_SONG_ALBUM),
                        durationMs = resultData
                            .getLong(CurrentSongIdentityProtocol.EXTRA_SONG_DURATION_MS, 0L)
                            .takeIf { duration -> duration > 0L },
                    )
                },
            )
        }
    }

    fun request(onResult: (CurrentSongDetails?) -> Unit): Boolean {
        if (activeRequest != null) return false

        val request = ActiveRequest(UUID.randomUUID().toString(), onResult)
        activeRequest = request
        handler.postDelayed(timeout, TIMEOUT_MILLIS)
        applicationContext.sendBroadcast(
            Intent(CurrentSongIdentityProtocol.REQUEST_ACTION)
                .setPackage(ModuleConstants.TARGET_PACKAGE)
                .putExtra(CurrentSongIdentityProtocol.EXTRA_REQUEST_TOKEN, request.token)
                .putExtra(CurrentSongIdentityProtocol.EXTRA_RESULT_RECEIVER, resultReceiver),
        )
        return true
    }

    fun cancel() {
        handler.removeCallbacks(timeout)
        activeRequest = null
    }

    private fun complete(token: String, details: CurrentSongDetails?) {
        val request = activeRequest?.takeIf { it.token == token } ?: return
        handler.removeCallbacks(timeout)
        activeRequest = null
        request.onResult(details)
    }

    private fun Bundle?.stringOrNull(key: String): String? = this
        ?.getString(key)
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private data class ActiveRequest(
        val token: String,
        val onResult: (CurrentSongDetails?) -> Unit,
    )

    private companion object {
        const val TIMEOUT_MILLIS = 1_500L
    }
}
