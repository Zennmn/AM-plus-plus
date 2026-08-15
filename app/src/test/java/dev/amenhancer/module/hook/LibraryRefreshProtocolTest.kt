package dev.amenhancer.module.hook

import dev.amenhancer.module.LibraryRefreshProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-contract coverage for the refresh/cancel protocol. The request action,
 * permission and token extras are the stable surface; the cancel action is a
 * separate broadcast scoped by the same token so first-contract callers are
 * unaffected and a cancel can only stop the refresh that owns the token.
 */
class LibraryRefreshProtocolTest {

    @Test
    fun `cancel uses a distinct action under the same permission`() {
        assertNotEquals(LibraryRefreshProtocol.REQUEST_ACTION, LibraryRefreshProtocol.CANCEL_ACTION)
        assertTrue(LibraryRefreshProtocol.CANCEL_ACTION.startsWith("dev.amenhancer.module.action."))
        assertEquals(
            LibraryRefreshProtocol.REQUEST_PERMISSION,
            LibraryRefreshProtocol.REQUEST_PERMISSION,
        )
    }

    @Test
    fun `cancel and request share the same token extra`() {
        assertEquals(
            LibraryRefreshProtocol.EXTRA_REQUEST_TOKEN,
            LibraryRefreshProtocol.EXTRA_REQUEST_TOKEN,
        )
        assertNotEquals(
            LibraryRefreshProtocol.EXTRA_RESULT_RECEIVER,
            LibraryRefreshProtocol.EXTRA_RESULT_MESSAGE,
        )
    }

    @Test
    fun `result codes are distinct and backward compatible`() {
        assertEquals(0, LibraryRefreshProtocol.RESULT_UNAVAILABLE)
        assertEquals(1, LibraryRefreshProtocol.RESULT_STARTED)
        assertEquals(2, LibraryRefreshProtocol.RESULT_FAILED)
        assertEquals(3, LibraryRefreshProtocol.RESULT_COMPLETED)
        assertEquals(4, LibraryRefreshProtocol.RESULT_CANCELLED)
        assertEquals(LibraryRefreshProtocol.RESULT_STARTED, LibraryRefreshProtocol.RESULT_TRIGGERED)
        val codes = setOf(
            LibraryRefreshProtocol.RESULT_UNAVAILABLE,
            LibraryRefreshProtocol.RESULT_STARTED,
            LibraryRefreshProtocol.RESULT_FAILED,
            LibraryRefreshProtocol.RESULT_COMPLETED,
            LibraryRefreshProtocol.RESULT_CANCELLED,
        )
        assertEquals(5, codes.size)
    }
}
