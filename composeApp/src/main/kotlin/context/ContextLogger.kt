package context

enum class LifecycleEvent {
    HOTKEY_REGISTERED, HOTKEY_TRIGGERED, SELECTION_STARTED, SELECTION_COMPLETED,
    CAPTURE_STARTED, CAPTURE_COMPLETED, PROCESSING_STARTED, SELECTION_CANCELLED,
    PROCESSING_FAILED, CONTEXT_CREATED, PREVIOUS_FOREGROUND_WINDOW_CAPTURED,
    OVERLAY_SHOWN, ESC_RECEIVED, ESC_CONSUMED, OVERLAY_CLOSED, WINDOW_RESTORED,
    FOCUS_RESTORED, SELECTION_STATE_IDLE
}

fun interface ContextLogger { fun event(event: LifecycleEvent, fields: Map<String, Any?>) }

/** Emits only explicitly safe scalar fields; unknown values and sensitive keys are dropped. */
class StderrContextLogger : ContextLogger {
    private val safeKeys = setOf("regionId", "width", "height", "durationMillis", "state", "ocrCharacters", "registeredIds")
    override fun event(event: LifecycleEvent, fields: Map<String, Any?>) {
        val safe = fields.filter { (key, value) -> key in safeKeys && (value is Number || value is String || value is java.util.UUID) }
        System.err.println("${event.name} $safe")
    }
}

class RecordingContextLogger : ContextLogger {
    data class Entry(val event: LifecycleEvent, val fields: Map<String, Any?>)
    private val mutableEntries = java.util.concurrent.CopyOnWriteArrayList<Entry>()
    val entries: List<Entry> get() = mutableEntries.toList()
    override fun event(event: LifecycleEvent, fields: Map<String, Any?>) { mutableEntries += Entry(event, fields) }
}
