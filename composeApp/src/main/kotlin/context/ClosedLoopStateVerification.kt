package context

import java.time.Instant
import java.util.UUID

enum class VerificationStatus { SUCCESS, FAILURE, UNCERTAIN }
enum class VerificationOperation { CREATE, DELETE, RENAME, SELECT, MODIFY, UNKNOWN }
enum class ExpectedStateField { OBJECT_EXISTS, OBJECT_TYPE, OBJECT_NAME, OBJECT_SELECTED, OBJECT_PROPERTY, SCENE_NAME, MODE }
enum class EvidenceSource { VISUAL, UIA, BLENDER, UNKNOWN }
enum class StateChangeType {
    OBJECT_ADDED,
    OBJECT_REMOVED,
    OBJECT_RENAMED,
    OBJECT_SELECTION_CHANGED,
    ACTIVE_MODE_CHANGED,
    SCENE_CHANGED,
    OBJECT_PROPERTY_CHANGED,
    NO_RELEVANT_CHANGE,
    INSUFFICIENT_BASELINE,
    UNKNOWN_CHANGE
}

data class ExpectedCondition(
    val field: ExpectedStateField,
    val expectedValue: String? = null,
    val objectName: String? = null,
    val required: Boolean = true,
    val expectedBoolean: Boolean? = null,
    val propertyName: String? = null
)

data class ExpectedState(
    val operation: VerificationOperation,
    val targetName: String? = null,
    val targetType: String? = null,
    val targetScene: String? = null,
    val expectedSelection: Boolean? = null,
    val targetMode: String? = null,
    val targetProperty: String? = null,
    val expectedPropertyValue: String? = null,
    val requiredConditions: List<ExpectedCondition> = emptyList()
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (operation == VerificationOperation.UNKNOWN) {
            errors += "verification operation is required"
        }
        if (operation in setOf(VerificationOperation.CREATE, VerificationOperation.DELETE, VerificationOperation.RENAME, VerificationOperation.SELECT, VerificationOperation.MODIFY) &&
            targetName.isNullOrBlank()
        ) {
            errors += "targetName is required for $operation verification"
        }
        requiredConditions.forEach { condition ->
            when (condition.field) {
                ExpectedStateField.OBJECT_EXISTS -> if (
                    condition.objectName.isNullOrBlank() && targetName.isNullOrBlank() && condition.expectedValue.isNullOrBlank()
                ) {
                    errors += "OBJECT_EXISTS conditions require an object name"
                }
                ExpectedStateField.OBJECT_TYPE -> {
                    if (condition.objectName.isNullOrBlank() && targetName.isNullOrBlank()) {
                        errors += "OBJECT_TYPE conditions require an object name"
                    }
                    if (condition.required && condition.expectedValue.isNullOrBlank() && targetType.isNullOrBlank()) {
                        errors += "required OBJECT_TYPE conditions need an expected type"
                    }
                }
                ExpectedStateField.OBJECT_NAME -> if (condition.required &&
                    condition.expectedValue.isNullOrBlank() && targetName.isNullOrBlank()
                ) {
                    errors += "required OBJECT_NAME conditions need an expected name"
                }
                ExpectedStateField.OBJECT_SELECTED -> {
                    if (condition.objectName.isNullOrBlank() && targetName.isNullOrBlank()) {
                        errors += "OBJECT_SELECTED conditions require an object name"
                    }
                    if (condition.expectedBoolean == null && condition.expectedValue?.lowercase() !in setOf("true", "false")) {
                        errors += "OBJECT_SELECTED conditions need a boolean expected value"
                    }
                }
                ExpectedStateField.OBJECT_PROPERTY -> {
                    if (condition.objectName.isNullOrBlank() && targetName.isNullOrBlank()) {
                        errors += "OBJECT_PROPERTY conditions require an object name"
                    }
                    if (condition.propertyName.isNullOrBlank() && targetProperty.isNullOrBlank()) {
                        errors += "OBJECT_PROPERTY conditions require a property name"
                    }
                    if (condition.required && condition.expectedValue.isNullOrBlank() && expectedPropertyValue.isNullOrBlank()) {
                        errors += "required OBJECT_PROPERTY conditions need an expected value"
                    }
                }
                ExpectedStateField.SCENE_NAME -> if (condition.required &&
                    (condition.expectedValue ?: targetScene).isNullOrBlank()
                ) errors += "required SCENE_NAME conditions need an expected scene"
                ExpectedStateField.MODE -> if (condition.required &&
                    (condition.expectedValue ?: targetMode).isNullOrBlank()
                ) errors += "required MODE conditions need an expected mode"
            }
        }
        return errors
    }
}

data class ObjectObservation(
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
    val sceneName: String? = null,
    val selected: Boolean? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class BaselineState(
    val runId: String = UUID.randomUUID().toString(),
    val capturedAt: Instant = Instant.now(),
    val applicationName: String? = null,
    val sceneName: String? = null,
    val mode: String? = null,
    val objects: List<ObjectObservation> = emptyList(),
    val selectedObjects: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
    val isComplete: Boolean = true
)

data class ObservedState(
    val runId: String = UUID.randomUUID().toString(),
    val capturedAt: Instant = Instant.now(),
    val applicationName: String? = null,
    val sceneName: String? = null,
    val mode: String? = null,
    val objects: List<ObjectObservation> = emptyList(),
    val selectedObjects: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
    val isComplete: Boolean = true
)

data class StateDiff(
    val type: StateChangeType,
    val subject: String? = null,
    val relevant: Boolean = true,
    val details: Map<String, String> = emptyMap()
)

data class EvidenceObservation(
    val source: EvidenceSource,
    val capturedAt: Instant = Instant.now(),
    val available: Boolean = true,
    val objectObservation: ObjectObservation? = null,
    val details: Map<String, String> = emptyMap(),
    val diagnostic: String? = null
)

data class VerificationResult(
    val status: VerificationStatus,
    val runId: String,
    val operation: VerificationOperation,
    val explanation: String,
    val evidence: List<EvidenceObservation> = emptyList(),
    val diffs: List<StateDiff> = emptyList(),
    val requiredConditions: List<ExpectedCondition> = emptyList(),
    val satisfiedConditions: List<ExpectedCondition> = emptyList(),
    val unsatisfiedConditions: List<ExpectedCondition> = emptyList(),
    val indeterminateConditions: List<ExpectedCondition> = emptyList(),
    val validationErrors: List<String> = emptyList()
)

enum class BlenderObservationStatus { AVAILABLE, UNAVAILABLE, FAILED, INSUFFICIENT }

data class BlenderStateObservation(
    val sceneName: String? = null,
    val sceneId: String? = null,
    val mode: String? = null,
    val objects: List<ObjectObservation> = emptyList(),
    val selectedObjects: Set<String> = emptySet(),
    val status: BlenderObservationStatus = BlenderObservationStatus.AVAILABLE,
    val capturedAt: Instant = Instant.now(),
    val sessionId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val diagnostic: String? = null
)

fun interface BlenderStateProvider {
    suspend fun observe(): BlenderStateObservation
}

data class VerificationRun(
    val id: String = UUID.randomUUID().toString(),
    val expected: ExpectedState,
    val startedAt: Instant = Instant.now(),
    val deadline: Instant,
    val baseline: BaselineState? = null,
    val applicationSessionId: String? = null
) {
    fun withBaseline(observation: BlenderStateObservation): VerificationRun {
        require(observation.status == BlenderObservationStatus.AVAILABLE) {
            "A baseline requires an available Blender observation."
        }
        require(observation.sessionId != null) {
            "A baseline requires a Blender session identity."
        }
        require(observation.capturedAt >= startedAt && observation.capturedAt <= deadline) {
            "A baseline observation must be captured during its verification run."
        }
        return copy(
            baseline = observation.toBaselineState(id),
            applicationSessionId = observation.sessionId
        )
    }

    fun observedState(observation: BlenderStateObservation): ObservedState? {
        val startingState = baseline ?: return null
        if (observation.status != BlenderObservationStatus.AVAILABLE ||
            observation.sessionId != applicationSessionId
        ) return null
        return observation.toObservedState(id)
            .takeIf { it.capturedAt >= startingState.capturedAt && it.capturedAt <= deadline }
    }
}

fun BlenderStateObservation.toBaselineState(runId: String) = BaselineState(
    runId = runId,
    capturedAt = capturedAt,
    applicationName = metadata["applicationName"] ?: "Blender",
    sceneName = sceneName,
    mode = mode,
    objects = objects,
    selectedObjects = selectedObjects,
    metadata = metadata + listOfNotNull(
        sceneId?.let { "sceneId" to it },
        sessionId?.let { "applicationSessionId" to it }
    ).toMap(),
    isComplete = status == BlenderObservationStatus.AVAILABLE && metadata["complete"] == "true"
)

fun BlenderStateObservation.toObservedState(runId: String) = ObservedState(
    runId = runId,
    capturedAt = capturedAt,
    applicationName = metadata["applicationName"] ?: "Blender",
    sceneName = sceneName,
    mode = mode,
    objects = objects,
    selectedObjects = selectedObjects,
    metadata = metadata + listOfNotNull(
        sceneId?.let { "sceneId" to it },
        sessionId?.let { "applicationSessionId" to it }
    ).toMap(),
    isComplete = status == BlenderObservationStatus.AVAILABLE && metadata["complete"] == "true"
)

class UnavailableBlenderStateProvider : BlenderStateProvider {
    override suspend fun observe(): BlenderStateObservation = BlenderStateObservation(
        status = BlenderObservationStatus.UNAVAILABLE,
        metadata = mapOf("provider" to "blender-state-unavailable"),
        diagnostic = "Blender installation not available in this environment"
    )
}

class StateVerifier {
    fun validate(expected: ExpectedState): List<String> = expected.validate()

    fun verify(
        expected: ExpectedState,
        baseline: BaselineState?,
        observed: ObservedState?,
        evidence: List<EvidenceObservation>
    ): VerificationResult {
        val validationErrors = validate(expected)
        if (validationErrors.isNotEmpty()) {
            return VerificationResult(
                status = VerificationStatus.UNCERTAIN,
                runId = UUID.randomUUID().toString(),
                operation = expected.operation,
                explanation = "Invalid ExpectedState: ${validationErrors.joinToString("; ")}",
                evidence = evidence,
                validationErrors = validationErrors
            )
        }
        val required = (inferRequiredConditions(expected) + expected.requiredConditions).distinct()
        if (baseline == null || observed == null) {
            return VerificationResult(
                status = VerificationStatus.UNCERTAIN,
                runId = baseline?.runId ?: observed?.runId ?: UUID.randomUUID().toString(),
                operation = expected.operation,
                explanation = "Missing baseline or observed state; cannot verify ${expected.operation.name.lowercase()}",
                evidence = evidence,
                requiredConditions = required
            )
        }
        if (baseline.runId != observed.runId || observed.capturedAt.isBefore(baseline.capturedAt)) {
            return VerificationResult(
                status = VerificationStatus.UNCERTAIN,
                runId = baseline.runId,
                operation = expected.operation,
                explanation = "Baseline and post-action observations are stale or belong to different verification runs.",
                evidence = evidence,
                requiredConditions = required
            )
        }
        if (baseline.applicationName != null && observed.applicationName != null &&
            !baseline.applicationName.equals(observed.applicationName, ignoreCase = true)
        ) {
            return VerificationResult(
                status = VerificationStatus.UNCERTAIN,
                runId = baseline.runId,
                operation = expected.operation,
                explanation = "Baseline and post-action observations belong to different applications.",
                evidence = evidence,
                requiredConditions = required
            )
        }
        if (expected.targetScene == null && baseline.sceneName != observed.sceneName) {
            return VerificationResult(
                status = VerificationStatus.UNCERTAIN,
                runId = baseline.runId,
                operation = expected.operation,
                explanation = "Baseline and post-action observations do not identify the same scene.",
                evidence = evidence,
                requiredConditions = required
            )
        }

        val diffs = computeStateDiff(baseline, observed)
        val satisfied = mutableListOf<ExpectedCondition>()
        val unsatisfied = mutableListOf<ExpectedCondition>()
        val indeterminate = mutableListOf<ExpectedCondition>()

        required.forEach { condition ->
            when (matchesCondition(expected, observed, condition)) {
                true -> satisfied += condition
                false -> unsatisfied += condition
                null -> indeterminate += condition
            }
        }

        val operationVerdict = verifyOperation(expected, baseline, observed)
        val hasAvailableEvidence = evidence.any { it.available }
        val hasStateEvidence = evidence.any {
            it.available && it.source in setOf(EvidenceSource.UIA, EvidenceSource.BLENDER)
        }
        val completeObservations = isCompleteForExpectedScope(baseline, expected) &&
            isCompleteForExpectedScope(observed, expected)
        val requiredConditionsSatisfied = satisfied.count { it.required } == required.count { it.required }
        val status = when {
            hasConflictingEvidence(evidence) -> VerificationStatus.UNCERTAIN
            completeObservations && hasStateEvidence &&
                (operationVerdict == false || unsatisfied.any { it.required }) -> VerificationStatus.FAILURE
            !completeObservations -> VerificationStatus.UNCERTAIN
            operationVerdict != true || indeterminate.any { it.required } -> VerificationStatus.UNCERTAIN
            unsatisfied.any { it.required } || !hasAvailableEvidence || !hasStateEvidence -> VerificationStatus.UNCERTAIN
            requiredConditionsSatisfied -> VerificationStatus.SUCCESS
            else -> VerificationStatus.UNCERTAIN
        }

        val explanation = when (status) {
            VerificationStatus.SUCCESS -> "Required state conditions were observed in the post-action state."
            VerificationStatus.FAILURE -> "Required state conditions were contradicted by complete observations: ${unsatisfied.filter { it.required }.joinToString { it.field.name }}"
            VerificationStatus.UNCERTAIN -> "Insufficient evidence or conflicting state changes prevented a reliable decision."
        }

        return VerificationResult(
            status = status,
            runId = baseline.runId,
            operation = expected.operation,
            explanation = explanation,
            evidence = evidence,
            diffs = diffs,
            requiredConditions = required,
            satisfiedConditions = satisfied,
            unsatisfiedConditions = unsatisfied,
            indeterminateConditions = indeterminate
        )
    }

    private fun inferRequiredConditions(expected: ExpectedState): List<ExpectedCondition> {
        val name = expected.targetName ?: ""
        return when (expected.operation) {
            VerificationOperation.CREATE -> listOf(
                ExpectedCondition(ExpectedStateField.OBJECT_EXISTS, expectedValue = name, objectName = name, required = true),
                ExpectedCondition(ExpectedStateField.OBJECT_TYPE, expectedValue = expected.targetType, objectName = name, required = !expected.targetType.isNullOrBlank())
            )
            VerificationOperation.DELETE -> listOf(
                ExpectedCondition(ExpectedStateField.OBJECT_EXISTS, expectedValue = name, objectName = name, expectedBoolean = false)
            )
            VerificationOperation.RENAME -> listOf(
                ExpectedCondition(ExpectedStateField.OBJECT_NAME, expectedValue = name, objectName = name, required = true),
                ExpectedCondition(ExpectedStateField.OBJECT_TYPE, expectedValue = expected.targetType, objectName = name, required = !expected.targetType.isNullOrBlank())
            )
            VerificationOperation.SELECT -> listOf(
                ExpectedCondition(ExpectedStateField.OBJECT_EXISTS, expectedValue = name, objectName = name, required = true),
                ExpectedCondition(ExpectedStateField.OBJECT_SELECTED, objectName = name, expectedBoolean = expected.expectedSelection ?: true)
            )
            VerificationOperation.MODIFY -> listOf(
                ExpectedCondition(ExpectedStateField.OBJECT_EXISTS, expectedValue = name, objectName = name),
                ExpectedCondition(
                    ExpectedStateField.OBJECT_PROPERTY,
                    expectedValue = expected.expectedPropertyValue,
                    objectName = name,
                    propertyName = expected.targetProperty
                )
            )
            VerificationOperation.UNKNOWN -> emptyList()
        }
    }

    private fun matchesCondition(
        expected: ExpectedState,
        observed: ObservedState,
        condition: ExpectedCondition
    ): Boolean? {
        val name = condition.objectName ?: expected.targetName ?: when (condition.field) {
            ExpectedStateField.OBJECT_EXISTS, ExpectedStateField.OBJECT_NAME -> condition.expectedValue
            else -> null
        }
        val objects = observed.objects.filter { objectInScope(it, expected, observed.sceneName) }
        val namedObjects = objects.filter { objectMatchesName(it, name) }
        return when (condition.field) {
            ExpectedStateField.OBJECT_EXISTS -> {
                val expectedPresent = condition.expectedBoolean ?: true
                when {
                    namedObjects.isNotEmpty() -> expectedPresent
                    isCompleteForExpectedScope(observed, expected) -> !expectedPresent
                    else -> null
                }
            }
            ExpectedStateField.OBJECT_TYPE -> {
                val type = condition.expectedValue ?: expected.targetType
                val target = namedObjects.firstOrNull()
                when {
                    namedObjects.size > 1 -> null
                    target == null && isCompleteForExpectedScope(observed, expected) -> false
                    target == null -> null
                    target.type == null || type.isNullOrBlank() -> null
                    else -> target.type.equals(type, ignoreCase = true)
                }
            }
            ExpectedStateField.OBJECT_NAME -> {
                val expectedValue = condition.expectedValue ?: name
                when {
                    expectedValue.isNullOrBlank() -> null
                    objects.any { objectMatchesName(it, expectedValue) } -> true
                    isCompleteForExpectedScope(observed, expected) -> false
                    else -> null
                }
            }
            ExpectedStateField.OBJECT_SELECTED -> {
                val target = namedObjects.firstOrNull()
                val selected = condition.expectedBoolean ?: condition.expectedValue?.toBooleanStrictOrNull() ?: return null
                when {
                    namedObjects.size > 1 -> null
                    target == null && isCompleteForExpectedScope(observed, expected) -> false
                    target == null -> null
                    target.selected != null -> target.selected == selected
                    selected && target.name in observed.selectedObjects -> true
                    isCompleteForExpectedScope(observed, expected) -> false
                    else -> null
                }
            }
            ExpectedStateField.OBJECT_PROPERTY -> {
                val target = namedObjects.singleOrNull() ?: return null
                val property = condition.propertyName ?: expected.targetProperty ?: return null
                val expectedValue = condition.expectedValue ?: expected.expectedPropertyValue ?: return null
                val observedValue = target.metadata[property] ?: return null
                observedValue == expectedValue
            }
            ExpectedStateField.SCENE_NAME -> {
                val expectedValue = condition.expectedValue ?: expected.targetScene
                when {
                    expectedValue.isNullOrBlank() || observed.sceneName == null -> null
                    else -> observed.sceneName.equals(expectedValue, ignoreCase = true)
                }
            }
            ExpectedStateField.MODE -> {
                val expectedValue = condition.expectedValue ?: expected.targetMode
                when {
                    expectedValue.isNullOrBlank() || observed.mode == null -> null
                    else -> observed.mode.equals(expectedValue, ignoreCase = true)
                }
            }
        }
    }

    private fun verifyOperation(
        expected: ExpectedState,
        baseline: BaselineState,
        observed: ObservedState
    ): Boolean? {
        val name = expected.targetName ?: return null
        val beforeObjects = baseline.objects.filter { objectInScope(it, expected, baseline.sceneName) }
        val afterObjects = observed.objects.filter { objectInScope(it, expected, observed.sceneName) }
        val before = beforeObjects.filter { objectMatchesName(it, name) }
        val after = afterObjects.filter { objectMatchesName(it, name) }
        return when (expected.operation) {
            VerificationOperation.CREATE -> when {
                !isCompleteForExpectedScope(baseline, expected) -> null
                before.isNotEmpty() -> false
                after.size > 1 -> null
                after.isNotEmpty() -> true
                isCompleteForExpectedScope(observed, expected) -> false
                else -> null
            }
            VerificationOperation.DELETE -> {
                if (!isCompleteForExpectedScope(baseline, expected)) return null
                val target = before.singleOrNull() ?: return if (before.isEmpty()) false else null
                val id = target.id?.takeIf(String::isNotBlank)
                val stillPresent = if (id == null) after.isNotEmpty() else afterObjects.count { it.id == id } > 0
                when {
                    stillPresent -> false
                    isCompleteForExpectedScope(observed, expected) -> true
                    else -> null
                }
            }
            VerificationOperation.RENAME -> {
                if (!isCompleteForExpectedScope(baseline, expected) ||
                    !isCompleteForExpectedScope(observed, expected)
                ) return null
                val target = after.singleOrNull() ?: return if (after.size > 1) null else false
                val id = target.id?.takeIf(String::isNotBlank) ?: return null
                val identityMatches = beforeObjects.filter { it.id == id }
                if (identityMatches.isEmpty()) return false
                if (identityMatches.size != 1) return null
                val original = identityMatches.single()
                when {
                    original.name.isNullOrBlank() -> null
                    original.name.equals(target.name, ignoreCase = true) -> false
                    original.type != null && target.type != null && !original.type.equals(target.type, ignoreCase = true) -> false
                    else -> true
                }
            }
            VerificationOperation.SELECT -> {
                val target = after.singleOrNull() ?: return if (after.size > 1) null else if (isCompleteForExpectedScope(observed, expected)) false else null
                val selected = target.selected ?: (target.name in observed.selectedObjects)
                when {
                    selected == (expected.expectedSelection ?: true) -> true
                    target.selected != null || isCompleteForExpectedScope(observed, expected) -> false
                    else -> null
                }
            }
            VerificationOperation.MODIFY -> {
                if (!isCompleteForExpectedScope(baseline, expected) ||
                    !isCompleteForExpectedScope(observed, expected)
                ) return null
                val beforeTarget = before.singleOrNull() ?: return if (before.size > 1) null else false
                val afterTarget = after.singleOrNull() ?: return if (after.size > 1) null else false
                val id = beforeTarget.id?.takeIf(String::isNotBlank) ?: return null
                if (afterTarget.id != id) return null
                val property = expected.targetProperty ?: return null
                val expectedValue = expected.expectedPropertyValue ?: return null
                val beforeValue = beforeTarget.metadata[property] ?: return null
                val afterValue = afterTarget.metadata[property] ?: return null
                when {
                    afterValue != expectedValue -> false
                    beforeValue == expectedValue -> false
                    else -> true
                }
            }
            VerificationOperation.UNKNOWN -> null
        }
    }

    private fun objectInScope(objectObservation: ObjectObservation, expected: ExpectedState, activeScene: String?): Boolean {
        val scene = expected.targetScene ?: activeScene
        if (scene == null) return true
        val objectScene = objectObservation.sceneName
        return if (objectScene != null) objectScene.equals(scene, ignoreCase = true)
        else activeScene?.equals(scene, ignoreCase = true) == true
    }

    private fun isCompleteForExpectedScope(state: BaselineState, expected: ExpectedState): Boolean =
        state.isComplete && isSceneScopeComplete(state.sceneName, state.objects, expected)

    private fun isCompleteForExpectedScope(state: ObservedState, expected: ExpectedState): Boolean =
        state.isComplete && isSceneScopeComplete(state.sceneName, state.objects, expected)

    private fun isSceneScopeComplete(
        activeScene: String?,
        objects: List<ObjectObservation>,
        expected: ExpectedState
    ): Boolean {
        val targetScene = expected.targetScene ?: return true
        return activeScene.equals(targetScene, ignoreCase = true) ||
            objects.isNotEmpty() && objects.all { it.sceneName != null }
    }

    private fun hasConflictingEvidence(evidence: List<EvidenceObservation>): Boolean {
        val observations = evidence.filter { it.available }.mapNotNull { it.objectObservation }
        return observations.groupBy { it.id?.takeIf(String::isNotBlank) ?: it.name?.takeIf(String::isNotBlank) }
            .filterKeys { it != null }
            .values.any { group ->
                group.any { left ->
                    group.any { right ->
                        left !== right &&
                            ((left.name != null && right.name != null && !left.name.equals(right.name, ignoreCase = true)) ||
                                (left.type != null && right.type != null && !left.type.equals(right.type, ignoreCase = true)) ||
                                (left.selected != null && right.selected != null && left.selected != right.selected))
                    }
                }
            }
    }

    private fun objectMatchesName(objectObservation: ObjectObservation, expectedName: String?): Boolean {
        if (expectedName.isNullOrBlank()) return false
        return objectObservation.name.equals(expectedName, ignoreCase = true)
    }

    private fun computeStateDiff(baseline: BaselineState, observed: ObservedState): List<StateDiff> {
        if (baseline.objects.isEmpty() && observed.objects.isEmpty()) {
            return listOf(StateDiff(StateChangeType.NO_RELEVANT_CHANGE, subject = "application_state", relevant = false))
        }

        val baselineNames = baseline.objects.mapNotNull { it.name }.toSet()
        val observedNames = observed.objects.mapNotNull { it.name }.toSet()
        val renamed = detectRenames(baseline.objects, observed.objects)
        val added = observedNames - baselineNames - renamed.map { it.second }.toSet()
        val removed = baselineNames - observedNames - renamed.map { it.first }.toSet()
        val selectedByIdentityBefore = selectionByIdentity(baseline.objects)
        val selectedByIdentityAfter = selectionByIdentity(observed.objects)
        val changedSelectionIds = (selectedByIdentityBefore.keys intersect selectedByIdentityAfter.keys)
            .filter { selectedByIdentityBefore[it] != selectedByIdentityAfter[it] }
        val changedSelection = baseline.selectedObjects != observed.selectedObjects || changedSelectionIds.isNotEmpty()
        val changedScene = baseline.sceneName != observed.sceneName
        val changedMode = baseline.mode != observed.mode
        val propertyDiffs = computePropertyDiffs(baseline.objects, observed.objects)

        val diffs = mutableListOf<StateDiff>()
        if (renamed.isNotEmpty()) {
            val renameDetails = renamed.joinToString("; ") { "${it.first}->${it.second}" }
            diffs += StateDiff(
                StateChangeType.OBJECT_RENAMED,
                subject = renameDetails,
                details = mapOf(
                    "renames" to renameDetails,
                    "count" to renamed.size.toString()
                )
            )
        }
        if (added.isNotEmpty()) diffs += StateDiff(StateChangeType.OBJECT_ADDED, subject = added.joinToString(), details = mapOf("count" to added.size.toString()))
        if (removed.isNotEmpty()) diffs += StateDiff(StateChangeType.OBJECT_REMOVED, subject = removed.joinToString(), details = mapOf("count" to removed.size.toString()))
        if (changedSelection) diffs += StateDiff(StateChangeType.OBJECT_SELECTION_CHANGED, subject = "selection", details = mapOf(
            "baseline" to baseline.selectedObjects.joinToString(),
            "observed" to observed.selectedObjects.joinToString(),
            "changedObjects" to changedSelectionIds.joinToString()
        ))
        if (changedMode) diffs += StateDiff(StateChangeType.ACTIVE_MODE_CHANGED, subject = observed.mode ?: baseline.mode, details = mapOf(
            "baseline" to (baseline.mode ?: "unknown"),
            "observed" to (observed.mode ?: "unknown")
        ))
        if (changedScene) diffs += StateDiff(StateChangeType.SCENE_CHANGED, subject = observed.sceneName ?: baseline.sceneName, details = mapOf(
            "baseline" to (baseline.sceneName ?: "unknown"),
            "observed" to (observed.sceneName ?: "unknown")
        ))
        diffs += propertyDiffs
        if (diffs.isEmpty()) diffs += StateDiff(StateChangeType.NO_RELEVANT_CHANGE, subject = "application_state", relevant = false)
        return diffs
    }

    private fun selectionByIdentity(objects: List<ObjectObservation>): Map<String, Boolean> =
        objects.mapNotNull { item ->
            val identity = item.id?.takeIf(String::isNotBlank) ?: item.name?.takeIf(String::isNotBlank)
            val selected = item.selected
            if (identity == null || selected == null) null else identity to selected
        }.toMap()

    private fun computePropertyDiffs(
        baselineObjects: List<ObjectObservation>,
        observedObjects: List<ObjectObservation>
    ): List<StateDiff> {
        val baselineById = baselineObjects.mapNotNull { item ->
            item.id?.takeIf(String::isNotBlank)?.let { it to item }
        }.toMap()
        val observedById = observedObjects.mapNotNull { item ->
            item.id?.takeIf(String::isNotBlank)?.let { it to item }
        }.toMap()
        if (baselineById.size != baselineObjects.count { !it.id.isNullOrBlank() } ||
            observedById.size != observedObjects.count { !it.id.isNullOrBlank() }
        ) return emptyList()

        return (baselineById.keys intersect observedById.keys).flatMap { id ->
            val before = baselineById.getValue(id)
            val after = observedById.getValue(id)
            (before.metadata.keys + after.metadata.keys).distinct().mapNotNull { property ->
                val oldValue = before.metadata[property]
                val newValue = after.metadata[property]
                if (oldValue == newValue) null
                else StateDiff(
                    StateChangeType.OBJECT_PROPERTY_CHANGED,
                    subject = after.name ?: before.name ?: id,
                    details = mapOf(
                        "objectId" to id,
                        "property" to property,
                        "before" to (oldValue ?: "unknown"),
                        "after" to (newValue ?: "unknown")
                    )
                )
            }
        }
    }

    private fun detectRenames(
        baselineObjects: List<ObjectObservation>,
        observedObjects: List<ObjectObservation>
    ): List<Pair<String, String>> {
        val baselineIds = baselineObjects.mapNotNull { it.id?.takeIf(String::isNotBlank) }
        val observedIds = observedObjects.mapNotNull { it.id?.takeIf(String::isNotBlank) }
        if (baselineIds.size != baselineIds.toSet().size || observedIds.size != observedIds.toSet().size) return emptyList()
        val observedById = observedObjects.mapNotNull { item ->
            item.id?.takeIf(String::isNotBlank)?.let { it to item }
        }.toMap()
        return baselineObjects.mapNotNull { before ->
            val id = before.id?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val after = observedById[id] ?: return@mapNotNull null
            val beforeName = before.name ?: return@mapNotNull null
            val afterName = after.name ?: return@mapNotNull null
            if (beforeName.equals(afterName, ignoreCase = true)) return@mapNotNull null
            if (before.sceneName != after.sceneName || before.type != after.type) return@mapNotNull null
            beforeName to afterName
        }
    }
}
