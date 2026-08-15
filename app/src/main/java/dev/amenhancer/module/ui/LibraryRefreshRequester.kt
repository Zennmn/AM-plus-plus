package dev.amenhancer.module.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import dev.amenhancer.module.LibraryRefreshProtocol
import dev.amenhancer.module.ModuleConstants
import java.util.UUID

internal data class LibraryRefreshResult(
    val resultCode: Int,
    val message: String?,
)

internal class LibraryRefreshRequester(
    context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val applicationContext = context.applicationContext
    private var activeRequest: ActiveRequest? = null
    private val timeout = Runnable {
        activeRequest?.let { request ->
            complete(
                request.token,
                LibraryRefreshResult(
                    LibraryRefreshProtocol.RESULT_FAILED,
                    "Apple Music 未响应，请确认模块已加载",
                ),
            )
        }
    }
    private val resultReceiver = object : ResultReceiver(handler) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            val token = resultData?.getString(LibraryRefreshProtocol.EXTRA_REQUEST_TOKEN) ?: return
            complete(
                token,
                LibraryRefreshResult(
                    resultCode,
                    resultData.getString(LibraryRefreshProtocol.EXTRA_RESULT_MESSAGE),
                ),
            )
        }
    }

    fun request(onResult: (LibraryRefreshResult) -> Unit): Boolean {
        if (activeRequest != null) return false
        val request = ActiveRequest(UUID.randomUUID().toString(), onResult)
        activeRequest = request
        handler.postDelayed(timeout, TIMEOUT_MILLIS)
        runCatching {
            applicationContext.sendBroadcast(
                Intent(LibraryRefreshProtocol.REQUEST_ACTION)
                    .setPackage(ModuleConstants.TARGET_PACKAGE)
                    .putExtra(LibraryRefreshProtocol.EXTRA_REQUEST_TOKEN, request.token)
                    .putExtra(LibraryRefreshProtocol.EXTRA_RESULT_RECEIVER, resultReceiver),
            )
        }.onFailure {
            complete(
                request.token,
                LibraryRefreshResult(
                    LibraryRefreshProtocol.RESULT_FAILED,
                    "无法向 Apple Music 发送刷新请求：${it.message.orEmpty()}",
                ),
            )
        }
        return true
    }

    /**
     * Drops the local request and asks the target to stop the refresh that owns
     * the active token. A later reply for that token is ignored because the
     * local request is already cleared.
     */
    fun cancel() {
        val request = activeRequest ?: return
        handler.removeCallbacks(timeout)
        activeRequest = null
        runCatching {
            applicationContext.sendBroadcast(
                Intent(LibraryRefreshProtocol.CANCEL_ACTION)
                    .setPackage(ModuleConstants.TARGET_PACKAGE)
                    .putExtra(LibraryRefreshProtocol.EXTRA_REQUEST_TOKEN, request.token),
            )
        }.onFailure {
            complete(
                request.token,
                LibraryRefreshResult(
                    LibraryRefreshProtocol.RESULT_CANCELLED,
                    "无法向 Apple Music 发送停止请求：${it.message.orEmpty()}",
                ),
            )
        }
    }

    private fun complete(token: String, result: LibraryRefreshResult) {
        val request = activeRequest?.takeIf { it.token == token } ?: return
        handler.removeCallbacks(timeout)
        activeRequest = null
        request.onResult(result)
    }

    private data class ActiveRequest(
        val token: String,
        val onResult: (LibraryRefreshResult) -> Unit,
    )

    private companion object {
        const val TIMEOUT_MILLIS = 300_000L
    }
}
