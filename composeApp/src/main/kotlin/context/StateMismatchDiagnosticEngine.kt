package context

import java.time.Duration
import java.time.Instant

/**
 * Categories of deterministic state discrepancies between Expected and Observed states.
 */
enum class MismatchCategory(val title: String) {
    APPLICATION_MISMATCH("Wrong Application"),
    MODE_MISMATCH("Incorrect Mode"),
    SELECTION_MISMATCH("Incorrect Selection"),
    OBJECT_MISSING("Required Object Missing"),
    UNEXPECTED_OBJECT("Unexpected Object Found"),
    OBJECT_TYPE_MISMATCH("Incorrect Object Type"),
    TRANSFORM_UNMODIFIED("Transform Not Applied"),
    DOCUMENT_STRUCTURE_MISMATCH("Sheet or Slide Mismatch"),
    CELL_VALUE_MISMATCH("Cell Content Mismatch"),
    INSUFFICIENT_TELEMETRY("Telemetry Incomplete"),
    STALE_TELEMETRY("Stale State Data")
}

/**
 * Recommended concrete recovery action for the learner.
 */
data class RecoveryAction(
    val actionType: String,
    val description: String,
    val suggestedShortcut: String? = null,
    val targetName: String? = null
)

/**
 * Individual detected state discrepancy.
 */
data class StateMismatch(
    val category: MismatchCategory,
    val expected: String,
    val observed: String,
    val explanation: String,
    val suggestedAction: RecoveryAction,
    val confidence: Double = 1.0
)

/**
 * Overall report of state comparison.
 */
data class StateMismatchReport(
    val hasMismatch: Boolean,
    val mismatches: List<StateMismatch>,
    val primaryMismatch: StateMismatch?,
    val recoveryActions: List<RecoveryAction>,
    val summary: String,
    val timestamp: Instant = Instant.now()
)

/**
 * Deterministic State Mismatch Diagnostic Engine.
 * Formulates instantaneous, verified technical diagnostics when a learner is stuck,
 * eliminating unnecessary VLM/LLM guesswork when underlying telemetry reveals the exact violation.
 */
class StateMismatchDiagnosticEngine(
    val maxAcceptableStaleness: Duration = Duration.ofSeconds(10),
    val staleGuard: StaleEvidenceGuard = StaleEvidenceGuard(
        policies = mapOf(
            EvidenceCategory.LIVE_TELEMETRY to FreshnessPolicy(
                category = EvidenceCategory.LIVE_TELEMETRY,
                maxAge = maxAcceptableStaleness
            )
        )
    )
) {

    /**
     * Compares [expected] with [observed] state and optional [baseline].
     */
    fun diagnose(
        expected: ExpectedState?,
        observed: ObservedState?,
        baseline: BaselineState? = null,
        targetApplicationName: String? = null
    ): StateMismatchReport {
        if (expected == null) {
            return StateMismatchReport(
                hasMismatch = false,
                mismatches = emptyList(),
                primaryMismatch = null,
                recoveryActions = emptyList(),
                summary = "No explicit expected state configured for this task."
            )
        }

        if (observed == null) {
            return StateMismatchReport(
                hasMismatch = true,
                mismatches = listOf(
                    StateMismatch(
                        category = MismatchCategory.INSUFFICIENT_TELEMETRY,
                        expected = "Connected application state",
                        observed = "Unavailable",
                        explanation = "Application state could not be observed. Verify that the application is running and connected.",
                        suggestedAction = RecoveryAction("CONNECT", "Ensure application is open and adapter is active.")
                    )
                ),
                primaryMismatch = null,
                recoveryActions = listOf(RecoveryAction("CONNECT", "Ensure application is running.")),
                summary = "Application state is unavailable."
            )
        }

        // Check temporal staleness via StaleEvidenceGuard
        val freshness = staleGuard.evaluate(
            category = EvidenceCategory.LIVE_TELEMETRY,
            observedAt = observed.capturedAt,
            observedApplication = observed.applicationName,
            targetApplication = targetApplicationName
        )
        if (!freshness.isFresh) {
            val staleMismatch = StateMismatch(
                category = MismatchCategory.STALE_TELEMETRY,
                expected = "Fresh state (< ${freshness.thresholdMillis} ms)",
                observed = "Observed ${freshness.ageMillis} ms ago",
                explanation = freshness.reason,
                suggestedAction = RecoveryAction("REFRESH", "Capture a new application snapshot.")
            )
            return StateMismatchReport(
                hasMismatch = true,
                mismatches = listOf(staleMismatch),
                primaryMismatch = staleMismatch,
                recoveryActions = listOf(staleMismatch.suggestedAction),
                summary = "Application state observation is stale or invalid."
            )
        }

        val mismatches = mutableListOf<StateMismatch>()

        // 1. Application Name Check
        if (targetApplicationName != null && observed.applicationName != null) {
            if (!observed.applicationName.equals(targetApplicationName, ignoreCase = true)) {
                mismatches.add(
                    StateMismatch(
                        category = MismatchCategory.APPLICATION_MISMATCH,
                        expected = targetApplicationName,
                        observed = observed.applicationName,
                        explanation = "Expected focus in '$targetApplicationName', but currently working in '${observed.applicationName}'.",
                        suggestedAction = RecoveryAction("SWITCH_APP", "Switch window focus to $targetApplicationName.")
                    )
                )
            }
        }

        // 2. Mode Check (e.g. OBJECT vs EDIT_MESH)
        val expectedMode = expected.targetMode?.trim()?.uppercase()
        val observedMode = observed.mode?.trim()?.uppercase()
        if (expectedMode != null && observedMode != null && expectedMode != observedMode) {
            val shortcut = when {
                expectedMode.contains("EDIT") && observedMode.contains("OBJECT") -> "Tab"
                expectedMode.contains("OBJECT") && observedMode.contains("EDIT") -> "Tab"
                expectedMode.contains("SCULPT") -> "Ctrl+Tab -> Sculpt"
                else -> null
            }
            mismatches.add(
                StateMismatch(
                    category = MismatchCategory.MODE_MISMATCH,
                    expected = expectedMode,
                    observed = observedMode,
                    explanation = "Expected mode '$expectedMode', but currently in '$observedMode'.",
                    suggestedAction = RecoveryAction(
                        actionType = "SWITCH_MODE",
                        description = "Switch mode to $expectedMode${if (shortcut != null) " (Shortcut: $shortcut)" else ""}.",
                        suggestedShortcut = shortcut
                    )
                )
            )
        }

        // 3. Selection Check
        val rawTargetName = expected.targetName?.trim().orEmpty()
        val targetName = if (rawTargetName.equals("Active", ignoreCase = true) || rawTargetName.equals("Selected", ignoreCase = true)) {
            observed.selectedObjects.firstOrNull()
                ?: observed.objects.find { it.selected == true }?.name
                ?: baseline?.selectedObjects?.firstOrNull()
                ?: baseline?.objects?.find { it.type == "MESH" }?.name
                ?: rawTargetName
        } else rawTargetName

        if (targetName.isNotBlank() && expected.expectedSelection == true) {
            val isSelected = observed.selectedObjects.any { it.equals(targetName, ignoreCase = true) } ||
                observed.objects.any { (it.name.equals(targetName, ignoreCase = true) || (it.name != null && Regex("^" + Regex.escape(targetName) + "(\\.\\d+)?$", RegexOption.IGNORE_CASE).matches(it.name))) && it.selected == true }
            if (!isSelected) {
                val currentSelection = observed.selectedObjects.joinToString(", ").ifBlank { "Nothing" }
                mismatches.add(
                    StateMismatch(
                        category = MismatchCategory.SELECTION_MISMATCH,
                        expected = "Selected: '$targetName'",
                        observed = "Selected: '$currentSelection'",
                        explanation = "Expected '$targetName' to be selected, but current selection is '$currentSelection'.",
                        suggestedAction = RecoveryAction(
                            actionType = "SELECT_OBJECT",
                            description = "Click '$targetName' in viewport or Outliner to select it.",
                            targetName = targetName
                        )
                    )
                )
            }
        }

        // 4. Object Existence Checks
        if (targetName.isNotBlank()) {
            val objectExists = observed.objects.any { it.name.equals(targetName, ignoreCase = true) || (it.name != null && Regex("^" + Regex.escape(targetName) + "(\\.\\d+)?$", RegexOption.IGNORE_CASE).matches(it.name)) }
            if (expected.operation == VerificationOperation.CREATE && !objectExists) {
                mismatches.add(
                    StateMismatch(
                        category = MismatchCategory.OBJECT_MISSING,
                        expected = "Created: '$targetName'",
                        observed = "Not found in scene",
                        explanation = "Expected new object '$targetName' in scene, but it has not been created.",
                        suggestedAction = RecoveryAction(
                            actionType = "CREATE_OBJECT",
                            description = "Add '$targetName' (e.g. Shift+A -> Mesh -> Cube).",
                            targetName = targetName
                        )
                    )
                )
            } else if (expected.operation == VerificationOperation.DELETE && objectExists) {
                mismatches.add(
                    StateMismatch(
                        category = MismatchCategory.UNEXPECTED_OBJECT,
                        expected = "Deleted: '$targetName'",
                        observed = "Still exists in scene",
                        explanation = "Expected '$targetName' to be deleted, but it is still present.",
                        suggestedAction = RecoveryAction(
                            actionType = "DELETE_OBJECT",
                            description = "Select '$targetName' and press 'X' or 'Delete'.",
                            targetName = targetName
                        )
                    )
                )
            }
        }

        // 5. Transform Modification Check
        if (expected.operation == VerificationOperation.MODIFY && baseline != null && targetName.isNotBlank()) {
            val nameRegex = Regex("^" + Regex.escape(targetName) + "(\\.\\d+)?$", RegexOption.IGNORE_CASE)
            val baseObj = baseline.objects.find { it.name?.equals(targetName, ignoreCase = true) == true || (it.name != null && nameRegex.matches(it.name)) }
            val obsObj = observed.objects.find { it.name?.equals(targetName, ignoreCase = true) == true || (it.name != null && nameRegex.matches(it.name)) }

            if (baseObj != null && obsObj != null) {
                val baseLoc = baseObj.metadata["location"]
                val obsLoc = obsObj.metadata["location"]
                val targetProp = expected.targetProperty?.lowercase()
                val expVal = expected.expectedPropertyValue

                if (targetProp == "location.z" || targetProp == "z") {
                    val bZ = extractObjectProperty(baseObj.metadata, "location.z")?.toDoubleOrNull()
                    val aZ = extractObjectProperty(obsObj.metadata, "location.z")?.toDoubleOrNull()
                    if (bZ != null && aZ != null) {
                        val isRelative = expVal != null && expVal.contains("baseline", ignoreCase = true)
                        val failedUpward = if (isRelative) aZ <= bZ + 0.001 else false
                        val failedSpec = if (!isRelative && expVal != null) !evaluatePropertyCondition(aZ.toString(), expVal) else false
                        if (failedUpward || failedSpec) {
                            val (requiredStr, explanation) = if (failedSpec) {
                                expVal!! to "Not verified: the cube's Z location has not reached the required target (observed: $aZ m, required: $expVal m)."
                            } else {
                                "> $bZ" to "Not verified: the cube's Z location has not moved upward relative to the starting position (observed: $aZ m, baseline: $bZ m)."
                            }
                            mismatches.add(
                                StateMismatch(
                                    category = MismatchCategory.TRANSFORM_UNMODIFIED,
                                    expected = "Z location $requiredStr (moved upward)",
                                    observed = "Z location: $aZ m",
                                    explanation = explanation,
                                    suggestedAction = RecoveryAction(
                                        actionType = "APPLY_TRANSFORM",
                                        description = "Press 'G', then 'Z', and move mouse upward to translate '$targetName' along the Z-axis.",
                                        suggestedShortcut = "G, then Z",
                                        targetName = targetName
                                    )
                                )
                            )
                        }
                    }
                } else if (targetProp == "rotation" || targetProp == "rotation_euler" || targetProp?.startsWith("rotation.") == true) {
                    val bRot = extractObjectProperty(baseObj.metadata, targetProp)
                    val aRot = extractObjectProperty(obsObj.metadata, targetProp)
                    val isRelative = expVal != null && expVal.contains("baseline", ignoreCase = true)
                    val failedSpec = when {
                        isRelative -> bRot != null && aRot != null && bRot == aRot
                        expVal != null -> !evaluatePropertyCondition(aRot ?: "", expVal)
                        else -> bRot == aRot
                    }
                    if (failedSpec) {
                        mismatches.add(
                            StateMismatch(
                                category = MismatchCategory.TRANSFORM_UNMODIFIED,
                                expected = "Rotation ${expVal ?: "!= baseline"}",
                                observed = "Rotation: ${aRot ?: "unrotated"}",
                                explanation = "Not verified: object '$targetName' rotation has not met the required orientation (${expVal ?: "modified"}).",
                                suggestedAction = RecoveryAction(
                                    actionType = "ROTATE_OBJECT",
                                    description = "Press 'R' to rotate '$targetName' to the required orientation.",
                                    suggestedShortcut = "R",
                                    targetName = targetName
                                )
                            )
                        )
                    }
                } else if (targetProp == "scale" || targetProp?.startsWith("scale.") == true) {
                    val bScale = extractObjectProperty(baseObj.metadata, targetProp)
                    val aScale = extractObjectProperty(obsObj.metadata, targetProp)
                    val isRelative = expVal != null && expVal.contains("baseline", ignoreCase = true)
                    val failedSpec = when {
                        isRelative -> bScale != null && aScale != null && bScale == aScale
                        expVal != null -> !evaluatePropertyCondition(aScale ?: "", expVal)
                        else -> bScale == aScale
                    }
                    if (failedSpec) {
                        mismatches.add(
                            StateMismatch(
                                category = MismatchCategory.TRANSFORM_UNMODIFIED,
                                expected = "Scale ${expVal ?: "!= baseline"}",
                                observed = "Scale: ${aScale ?: "unscaled"}",
                                explanation = "Not verified: object '$targetName' scale has not changed (${expVal ?: "modified"}).",
                                suggestedAction = RecoveryAction(
                                    actionType = "SCALE_OBJECT",
                                    description = "Press 'S' to scale '$targetName'.",
                                    suggestedShortcut = "S",
                                    targetName = targetName
                                )
                            )
                        )
                    }
                } else if (baseLoc != null && obsLoc != null && baseLoc == obsLoc) {
                    mismatches.add(
                        StateMismatch(
                            category = MismatchCategory.TRANSFORM_UNMODIFIED,
                            expected = "Transform changed from $baseLoc",
                            observed = "Unchanged: $obsLoc",
                            explanation = "Object '$targetName' transform has not changed from baseline position ($baseLoc).",
                            suggestedAction = RecoveryAction(
                                actionType = "APPLY_TRANSFORM",
                                description = "Press 'G', 'R', or 'S' and move mouse to transform '$targetName'.",
                                suggestedShortcut = "G",
                                targetName = targetName
                            )
                        )
                    )
                }
            }
        }

        // 6. Excel/Document Structure Check (via metadata if present)
        val expectedCell = expected.targetProperty?.takeIf { it.startsWith("cell:") }?.removePrefix("cell:")
        if (expectedCell != null) {
            val observedCell = observed.metadata["activeCell"]
            if (observedCell != null && !observedCell.equals(expectedCell, ignoreCase = true)) {
                mismatches.add(
                    StateMismatch(
                        category = MismatchCategory.DOCUMENT_STRUCTURE_MISMATCH,
                        expected = "Active cell '$expectedCell'",
                        observed = "Active cell '$observedCell'",
                        explanation = "Expected active cell '$expectedCell', but current cell is '$observedCell'.",
                        suggestedAction = RecoveryAction(
                            actionType = "SELECT_CELL",
                            description = "Click cell $expectedCell in Excel worksheet."
                        )
                    )
                )
            }
        }

        val primary = mismatches.firstOrNull()
        val actions = mismatches.map { it.suggestedAction }

        val summary = if (mismatches.isEmpty()) {
            "No state mismatches detected; application state aligns with task expectations."
        } else {
            mismatches.joinToString(" ") { it.explanation }
        }

        return StateMismatchReport(
            hasMismatch = mismatches.isNotEmpty(),
            mismatches = mismatches,
            primaryMismatch = primary,
            recoveryActions = actions,
            summary = summary
        )
    }
}
