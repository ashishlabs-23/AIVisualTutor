package calibration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DiagnosticWindowCloseAllowlistTest {
    @Test
    fun onlyWindowHandleReturnedByTaskCreationCanBeClosed() {
        val allowlist = DiagnosticWindowCloseAllowlist()
        val createdHandle = 0x1234L

        assertEquals(createdHandle, allowlist.createOwnedWindow { createdHandle })
        allowlist.requireOwnedForCloseSignal(createdHandle)

        assertFailsWith<IllegalStateException> {
            allowlist.requireOwnedForCloseSignal(0x5678L)
        }
    }

    @Test
    fun invalidWindowCreationDoesNotAuthorizeAnyHandle() {
        val allowlist = DiagnosticWindowCloseAllowlist()

        assertFailsWith<IllegalArgumentException> {
            allowlist.createOwnedWindow { 0L }
        }
        assertFailsWith<IllegalStateException> {
            allowlist.requireOwnedForCloseSignal(0L)
        }
    }

    @Test
    fun failedActivationNeverSendsCloseSignal() {
        val allowlist = DiagnosticWindowCloseAllowlist()
        val handle = allowlist.createOwnedWindow { 0x1234L }
        var signalSent = false

        val sent = allowlist.trySendCloseSignal(
            handle = handle,
            activate = { false },
            isForeground = { error("Foreground check must not follow failed activation.") },
            sendSignal = { signalSent = true }
        )

        assertEquals(false, sent)
        assertEquals(false, signalSent)
    }

    @Test
    fun externalWindowCannotReceiveCloseSignalEvenWhenActivationSucceeds() {
        val allowlist = DiagnosticWindowCloseAllowlist()
        var activationAttempted = false
        var signalSent = false

        assertFailsWith<IllegalStateException> {
            allowlist.trySendCloseSignal(
                handle = 0x5678L,
                activate = { activationAttempted = true; true },
                isForeground = { true },
                sendSignal = { signalSent = true }
            )
        }

        assertEquals(false, activationAttempted)
        assertEquals(false, signalSent)
    }
}
