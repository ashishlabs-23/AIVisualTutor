package calibration

/** Allows destructive window signals only for HWNDs returned by this diagnostic's creation step. */
class DiagnosticWindowCloseAllowlist {
    private val taskCreatedHandles = mutableSetOf<Long>()

    @Synchronized
    fun createOwnedWindow(create: () -> Long): Long {
        val handle = create()
        require(handle != 0L) { "Diagnostic window creation returned an invalid HWND." }
        taskCreatedHandles += handle
        return handle
    }

    @Synchronized
    fun requireOwnedForCloseSignal(handle: Long) {
        check(handle != 0L && handle in taskCreatedHandles) {
            "Refusing to send a close signal to a window not created by this diagnostic."
        }
    }

    fun trySendCloseSignal(
        handle: Long,
        activate: () -> Boolean,
        isForeground: () -> Boolean,
        sendSignal: () -> Unit
    ): Boolean {
        requireOwnedForCloseSignal(handle)
        if (!activate() || !isForeground()) return false
        sendSignal()
        return true
    }
}
