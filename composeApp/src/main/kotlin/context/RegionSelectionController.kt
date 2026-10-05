package context

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

sealed interface SelectionState {
    data object Idle : SelectionState
    data class Selecting(val regionId: UUID) : SelectionState
    data class Processing(val regionId: UUID) : SelectionState
    data class Completed(val context: VisualContext) : SelectionState
    data class Cancelled(val regionId: UUID) : SelectionState
}

/** Synchronized transitions reject overlapping starts and stale callbacks. */
class RegionSelectionController(private val logger: ContextLogger = StderrContextLogger()) {
    private val mutable = MutableStateFlow<SelectionState>(SelectionState.Idle)
    val state: StateFlow<SelectionState> = mutable

    fun log(event: LifecycleEvent, fields: Map<String, Any?>) = logger.event(event, fields)

    @Synchronized fun begin(): UUID? {
        if (mutable.value !is SelectionState.Idle) return null
        val id = UUID.randomUUID()
        mutable.value = SelectionState.Selecting(id)
        logger.event(LifecycleEvent.SELECTION_STARTED, mapOf("regionId" to id, "state" to "SELECTING"))
        return id
    }

    @Synchronized fun processing(regionId: UUID): Boolean {
        if ((mutable.value as? SelectionState.Selecting)?.regionId != regionId) return false
        mutable.value = SelectionState.Processing(regionId)
        logger.event(LifecycleEvent.SELECTION_COMPLETED, mapOf("regionId" to regionId, "state" to "SELECTED"))
        return true
    }

    @Synchronized fun complete(regionId: UUID, visualContext: VisualContext): Boolean {
        if ((mutable.value as? SelectionState.Processing)?.regionId != regionId) return false
        mutable.value = SelectionState.Completed(visualContext)
        return true
    }

    @Synchronized fun cancel(regionId: UUID): Boolean {
        val activeId = when (val current = mutable.value) {
            is SelectionState.Selecting -> current.regionId
            is SelectionState.Processing -> current.regionId
            else -> return false
        }
        if (activeId != regionId) return false
        mutable.value = SelectionState.Cancelled(regionId)
        logger.event(LifecycleEvent.SELECTION_CANCELLED, mapOf("regionId" to regionId, "state" to "CANCELLED"))
        return true
    }

    @Synchronized fun fail(regionId: UUID) {
        val currentId = when (val current = mutable.value) {
            is SelectionState.Selecting -> current.regionId
            is SelectionState.Processing -> current.regionId
            is SelectionState.Cancelled -> current.regionId
            is SelectionState.Completed -> current.context.regionId
            else -> null
        }
        if (currentId == regionId) logger.event(LifecycleEvent.PROCESSING_FAILED, mapOf("regionId" to regionId, "state" to "IDLE"))
        if (currentId == regionId) mutable.value = SelectionState.Idle
    }

    @Synchronized fun reset(regionId: UUID) {
        val currentId = when (val current = mutable.value) {
            is SelectionState.Completed -> current.context.regionId
            is SelectionState.Cancelled -> current.regionId
            else -> null
        }
        if (currentId == regionId) mutable.value = SelectionState.Idle
    }
}
