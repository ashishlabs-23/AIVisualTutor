package context

import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StateVerificationTest {
    private val verifier = StateVerifier()

    @Test
    fun invalidExpectedStateIsMarkedUncertain() {
        val expected = ExpectedState(
            operation = VerificationOperation.CREATE,
            targetName = null,
            targetType = null
        )

        val result = verifier.verify(
            expected = expected,
            baseline = BaselineState(applicationName = "Blender"),
            observed = ObservedState(applicationName = "Blender"),
            evidence = listOf(EvidenceObservation(source = EvidenceSource.VISUAL, available = true))
        )

        assertEquals(VerificationStatus.UNCERTAIN, result.status)
        assertTrue(result.validationErrors.isNotEmpty())
    }

    @Test
    fun createOperationSucceedsWhenExpectedObjectExists() {
        val expected = ExpectedState(
            operation = VerificationOperation.CREATE,
            targetName = "Cube",
            targetType = "MESH",
            targetScene = "Scene",
            requiredConditions = listOf(
                ExpectedCondition(ExpectedStateField.OBJECT_EXISTS, expectedValue = "Cube", objectName = "Cube"),
                ExpectedCondition(ExpectedStateField.OBJECT_TYPE, expectedValue = "MESH", objectName = "Cube")
            )
        )

        val baseline = BaselineState(
            runId = "create-run",
            sceneName = "Scene",
            mode = "OBJECT",
            objects = listOf(ObjectObservation(id = "1", name = "Sphere", type = "MESH", sceneName = "Scene"))
        )
        val observed = ObservedState(
            runId = "create-run",
            sceneName = "Scene",
            mode = "OBJECT",
            objects = listOf(
                ObjectObservation(id = "1", name = "Sphere", type = "MESH", sceneName = "Scene"),
                ObjectObservation(id = "2", name = "Cube", type = "MESH", sceneName = "Scene", selected = true)
            ),
            selectedObjects = setOf("Cube")
        )

        val result = verifier.verify(expected, baseline, observed, listOf(EvidenceObservation(EvidenceSource.BLENDER, available = true)))

        assertEquals(VerificationStatus.SUCCESS, result.status)
        assertTrue(result.diffs.any { it.type == StateChangeType.OBJECT_ADDED })
    }

    @Test
    fun deleteOperationSucceedsWhenTargetDisappears() {
        val expected = ExpectedState(
            operation = VerificationOperation.DELETE,
            targetName = "Cube",
            targetType = "MESH",
            requiredConditions = listOf(
                ExpectedCondition(ExpectedStateField.OBJECT_EXISTS, expectedValue = "Cube", objectName = "Cube", expectedBoolean = false)
            )
        )

        val baseline = BaselineState(runId = "delete-run", objects = listOf(ObjectObservation(id = "1", name = "Cube", type = "MESH")))
        val observed = ObservedState(runId = "delete-run", objects = emptyList())

        val result = verifier.verify(expected, baseline, observed, listOf(EvidenceObservation(EvidenceSource.BLENDER, available = true)))

        assertEquals(VerificationStatus.SUCCESS, result.status)
        assertTrue(result.unsatisfiedConditions.isEmpty())
    }

    @Test
    fun selectionVerificationRequiresTheObjectToBeSelected() {
        val expected = ExpectedState(
            operation = VerificationOperation.SELECT,
            targetName = "Cube",
            targetType = "MESH",
            expectedSelection = true,
            requiredConditions = listOf(
                ExpectedCondition(ExpectedStateField.OBJECT_EXISTS, expectedValue = "Cube", objectName = "Cube"),
                ExpectedCondition(ExpectedStateField.OBJECT_SELECTED, expectedValue = "true", objectName = "Cube")
            )
        )

        val baseline = BaselineState(runId = "select-run", objects = listOf(ObjectObservation(id = "1", name = "Cube", type = "MESH", selected = false)))
        val observed = ObservedState(
            runId = "select-run",
            objects = listOf(ObjectObservation(id = "1", name = "Cube", type = "MESH", selected = true)),
            selectedObjects = setOf("Cube")
        )

        val result = verifier.verify(expected, baseline, observed, listOf(EvidenceObservation(EvidenceSource.UIA, available = true)))

        assertEquals(VerificationStatus.SUCCESS, result.status)
    }

    @Test
    fun selectionDiffUsesObjectStateWhenSelectionSetsAreAbsent() {
        val baseline = BaselineState(
            runId = "selection-diff",
            objects = listOf(ObjectObservation(id = "cube", name = "Cube", selected = false))
        )
        val observed = ObservedState(
            runId = "selection-diff",
            objects = listOf(ObjectObservation(id = "cube", name = "Cube", selected = true))
        )

        val result = verifier.verify(
            ExpectedState(VerificationOperation.SELECT, targetName = "Cube"),
            baseline,
            observed,
            listOf(EvidenceObservation(EvidenceSource.BLENDER))
        )

        assertEquals(VerificationStatus.SUCCESS, result.status)
        assertEquals("cube", result.diffs.single { it.type == StateChangeType.OBJECT_SELECTION_CHANGED }.details["changedObjects"])
    }

    @Test
    fun renameProducesRenameDiffInsteadOfAddRemovePair() {
        val expected = ExpectedState(
            operation = VerificationOperation.RENAME,
            targetName = "CubeRenamed",
            targetType = "MESH",
            requiredConditions = listOf(
                ExpectedCondition(ExpectedStateField.OBJECT_NAME, expectedValue = "CubeRenamed", objectName = "CubeRenamed")
            )
        )

        val baseline = BaselineState(runId = "rename-run", objects = listOf(ObjectObservation(id = "1", name = "Cube", type = "MESH")))
        val observed = ObservedState(runId = "rename-run", objects = listOf(ObjectObservation(id = "1", name = "CubeRenamed", type = "MESH")))

        val result = verifier.verify(expected, baseline, observed, listOf(EvidenceObservation(EvidenceSource.BLENDER, available = true)))

        assertEquals(VerificationStatus.SUCCESS, result.status)
        assertTrue(result.diffs.any { it.type == StateChangeType.OBJECT_RENAMED })
        assertTrue(result.diffs.none { it.type == StateChangeType.OBJECT_ADDED })
        assertTrue(result.diffs.none { it.type == StateChangeType.OBJECT_REMOVED })
    }

    @Test
    fun renameUsesStableIdentityAndDoesNotConfuseSimilarObjects() {
        val baseline = BaselineState(
            runId = "identity-run",
            objects = listOf(
                ObjectObservation(id = "cube-1", name = "Cube", type = "MESH"),
                ObjectObservation(id = "cube-2", name = "Cube.001", type = "MESH")
            )
        )
        val observed = ObservedState(
            runId = "identity-run",
            objects = listOf(
                ObjectObservation(id = "cube-1", name = "CubeRenamed", type = "MESH"),
                ObjectObservation(id = "cube-2", name = "Cube.001", type = "MESH")
            )
        )

        val result = verifier.verify(
            ExpectedState(
                operation = VerificationOperation.RENAME,
                targetName = "CubeRenamed",
                requiredConditions = listOf(
                    ExpectedCondition(ExpectedStateField.OBJECT_NAME, "CubeRenamed", "CubeRenamed")
                )
            ),
            baseline,
            observed,
            listOf(EvidenceObservation(EvidenceSource.BLENDER))
        )

        assertEquals(VerificationStatus.SUCCESS, result.status)
        assertEquals("Cube->CubeRenamed", result.diffs.single { it.type == StateChangeType.OBJECT_RENAMED }.subject)
        assertFalse(result.diffs.any { it.type == StateChangeType.OBJECT_ADDED || it.type == StateChangeType.OBJECT_REMOVED })
    }

    @Test
    fun unrelatedSameTypeCreationAndDeletionAreNotInferredAsRename() {
        val baseline = BaselineState(runId = "unrelated-run", objects = listOf(ObjectObservation("old-id", "Old", "MESH")))
        val observed = ObservedState(runId = "unrelated-run", objects = listOf(ObjectObservation("new-id", "New", "MESH")))

        val result = verifier.verify(
            ExpectedState(VerificationOperation.RENAME, targetName = "New"),
            baseline,
            observed,
            listOf(EvidenceObservation(EvidenceSource.BLENDER))
        )

        assertEquals(VerificationStatus.FAILURE, result.status)
        assertFalse(result.diffs.any { it.type == StateChangeType.OBJECT_RENAMED })
        assertTrue(result.diffs.any { it.type == StateChangeType.OBJECT_ADDED })
        assertTrue(result.diffs.any { it.type == StateChangeType.OBJECT_REMOVED })
    }

    @Test
    fun missingIdentityDoesNotInferRenameOrSuccess() {
        val baseline = BaselineState(runId = "no-id-run", objects = listOf(ObjectObservation(name = "Old", type = "MESH")))
        val observed = ObservedState(runId = "no-id-run", objects = listOf(ObjectObservation(name = "New", type = "MESH")))

        val result = verifier.verify(
            ExpectedState(VerificationOperation.RENAME, targetName = "New"),
            baseline,
            observed,
            listOf(EvidenceObservation(EvidenceSource.BLENDER))
        )

        assertEquals(VerificationStatus.UNCERTAIN, result.status)
        assertFalse(result.diffs.any { it.type == StateChangeType.OBJECT_RENAMED })
    }

    @Test
    fun creationCannotSucceedForPreexistingTargetOrUnrelatedChange() {
        val baseline = BaselineState(runId = "create-existing", objects = listOf(ObjectObservation("cube", "Cube", "MESH")))
        val observed = BaselineState(
            runId = "create-existing",
            objects = listOf(ObjectObservation("cube", "Cube", "MESH"), ObjectObservation("sphere", "Sphere", "MESH"))
        ).let { ObservedState(it.runId, objects = it.objects) }

        val result = verifier.verify(
            ExpectedState(VerificationOperation.CREATE, targetName = "Cube"),
            baseline,
            observed,
            listOf(EvidenceObservation(EvidenceSource.BLENDER))
        )

        assertEquals(VerificationStatus.FAILURE, result.status)
    }

    @Test
    fun creationRequiresTargetTypeEvenWhenCustomConditionsAreProvided() {
        val result = verifier.verify(
            ExpectedState(
                VerificationOperation.CREATE,
                targetName = "Cube",
                targetType = "MESH",
                requiredConditions = listOf(ExpectedCondition(ExpectedStateField.OBJECT_EXISTS, "Cube", "Cube"))
            ),
            BaselineState(runId = "typed-create"),
            ObservedState(runId = "typed-create", objects = listOf(ObjectObservation("cube", "Cube"))),
            listOf(EvidenceObservation(EvidenceSource.BLENDER))
        )

        assertEquals(VerificationStatus.UNCERTAIN, result.status)
        assertTrue(result.indeterminateConditions.any { it.field == ExpectedStateField.OBJECT_TYPE })
    }

    @Test
    fun incompletePostActionInventoryDoesNotProveDeletion() {
        val baseline = BaselineState(runId = "incomplete-delete", objects = listOf(ObjectObservation("cube", "Cube", "MESH")))
        val observed = ObservedState(runId = "incomplete-delete", objects = emptyList(), isComplete = false)

        val result = verifier.verify(
            ExpectedState(VerificationOperation.DELETE, targetName = "Cube"),
            baseline,
            observed,
            listOf(EvidenceObservation(EvidenceSource.BLENDER))
        )

        assertEquals(VerificationStatus.UNCERTAIN, result.status)
    }

    @Test
    fun deletionCannotBeProvenFromAnUnobservedScene() {
        val result = verifier.verify(
            ExpectedState(VerificationOperation.DELETE, targetName = "Cube", targetScene = "SceneB"),
            BaselineState(
                runId = "scene-delete",
                sceneName = "SceneA",
                objects = listOf(ObjectObservation("cube", "Cube", "MESH"))
            ),
            ObservedState(runId = "scene-delete", sceneName = "SceneA", objects = emptyList()),
            listOf(EvidenceObservation(EvidenceSource.BLENDER))
        )

        assertEquals(VerificationStatus.UNCERTAIN, result.status)
    }

    @Test
    fun falseSelectionIsFailureAndMissingSelectionIsIndeterminate() {
        val expected = ExpectedState(VerificationOperation.SELECT, targetName = "Cube")
        val baseline = BaselineState(runId = "selection-run", objects = listOf(ObjectObservation("cube", "Cube", "MESH", selected = false)))
        val falseSelection = ObservedState(runId = "selection-run", objects = listOf(ObjectObservation("cube", "Cube", "MESH", selected = false)))
        val missingSelection = ObservedState(runId = "selection-run", objects = listOf(ObjectObservation("cube", "Cube", "MESH")), isComplete = false)
        val available = listOf(EvidenceObservation(EvidenceSource.BLENDER))

        assertEquals(VerificationStatus.FAILURE, verifier.verify(expected, baseline, falseSelection, available).status)
        val unknown = verifier.verify(expected, baseline, missingSelection, available)
        assertEquals(VerificationStatus.UNCERTAIN, unknown.status)
        assertEquals(1, unknown.indeterminateConditions.size)
    }

    @Test
    fun selectionOperationCannotSucceedWhenSelectionConditionIsOptional() {
        val result = verifier.verify(
            ExpectedState(
                VerificationOperation.SELECT,
                targetName = "Cube",
                requiredConditions = listOf(
                    ExpectedCondition(ExpectedStateField.OBJECT_SELECTED, "true", "Cube", required = false)
                )
            ),
            BaselineState(runId = "required-select", objects = listOf(ObjectObservation("cube", "Cube", selected = false))),
            ObservedState(runId = "required-select", objects = listOf(ObjectObservation("cube", "Cube", selected = false))),
            listOf(EvidenceObservation(EvidenceSource.BLENDER))
        )

        assertEquals(VerificationStatus.FAILURE, result.status)
    }

    @Test
    fun modifyOperationVerifiesRequiredPropertyTransitionOnSameObject() {
        val expected = ExpectedState(
            VerificationOperation.MODIFY,
            targetName = "Cube",
            targetProperty = "location",
            expectedPropertyValue = "1,2,3"
        )
        val baseline = BaselineState(
            runId = "modify-run",
            objects = listOf(
                ObjectObservation("cube", "Cube", "MESH", metadata = mapOf("location" to "0,0,0"))
            )
        )
        val observed = ObservedState(
            runId = "modify-run",
            objects = listOf(
                ObjectObservation("cube", "Cube", "MESH", metadata = mapOf("location" to "1,2,3"))
            )
        )

        val result = verifier.verify(
            expected,
            baseline,
            observed,
            listOf(EvidenceObservation(EvidenceSource.BLENDER))
        )

        assertEquals(VerificationStatus.SUCCESS, result.status)
        assertTrue(result.diffs.any {
            it.type == StateChangeType.OBJECT_PROPERTY_CHANGED && it.details["property"] == "location"
        })
    }

    @Test
    fun modifyOperationDoesNotSucceedWhenValueWasAlreadyExpected() {
        val state = listOf(
            ObjectObservation("cube", "Cube", "MESH", metadata = mapOf("location" to "1,2,3"))
        )
        val result = verifier.verify(
            ExpectedState(
                VerificationOperation.MODIFY,
                targetName = "Cube",
                targetProperty = "location",
                expectedPropertyValue = "1,2,3"
            ),
            BaselineState(runId = "already-modified", objects = state),
            ObservedState(runId = "already-modified", objects = state),
            listOf(EvidenceObservation(EvidenceSource.BLENDER))
        )

        assertEquals(VerificationStatus.FAILURE, result.status)
    }

    @Test
    fun modifyOperationWithMissingPropertyObservationIsUncertain() {
        val result = verifier.verify(
            ExpectedState(
                VerificationOperation.MODIFY,
                targetName = "Cube",
                targetProperty = "location",
                expectedPropertyValue = "1,2,3"
            ),
            BaselineState(runId = "missing-property", objects = listOf(ObjectObservation("cube", "Cube", "MESH"))),
            ObservedState(runId = "missing-property", objects = listOf(ObjectObservation("cube", "Cube", "MESH"))),
            listOf(EvidenceObservation(EvidenceSource.BLENDER))
        )

        assertEquals(VerificationStatus.UNCERTAIN, result.status)
        assertTrue(result.indeterminateConditions.any { it.field == ExpectedStateField.OBJECT_PROPERTY })
    }

    @Test
    fun optionalConditionDoesNotBlockSuccessButRequiredUnknownDoes() {
        val expected = ExpectedState(
            VerificationOperation.CREATE,
            targetName = "Cube",
            requiredConditions = listOf(
                ExpectedCondition(ExpectedStateField.OBJECT_EXISTS, "Cube", "Cube"),
                ExpectedCondition(ExpectedStateField.MODE, "OBJECT", required = false)
            )
        )
        val baseline = BaselineState(runId = "optional-run")
        val observed = ObservedState(runId = "optional-run", objects = listOf(ObjectObservation("cube", "Cube", "MESH")))

        val result = verifier.verify(expected, baseline, observed, listOf(EvidenceObservation(EvidenceSource.BLENDER)))

        assertEquals(VerificationStatus.SUCCESS, result.status)
        assertTrue(result.indeterminateConditions.any { it.field == ExpectedStateField.MODE })
    }

    @Test
    fun observationsFromDifferentRunsOrApplicationsCannotVerify() {
        val expected = ExpectedState(VerificationOperation.CREATE, targetName = "Cube")
        val baseline = BaselineState(runId = "older-run", applicationName = "Blender")
        val differentRun = ObservedState(runId = "newer-run", applicationName = "Blender", objects = listOf(ObjectObservation("1", "Cube", "MESH")))
        val differentApplication = ObservedState(runId = "older-run", applicationName = "Other", objects = listOf(ObjectObservation("1", "Cube", "MESH")))
        val evidence = listOf(EvidenceObservation(EvidenceSource.BLENDER))

        assertEquals(VerificationStatus.UNCERTAIN, verifier.verify(expected, baseline, differentRun, evidence).status)
        assertEquals(VerificationStatus.UNCERTAIN, verifier.verify(expected, baseline, differentApplication, evidence).status)
    }

    @Test
    fun observationsFromDifferentActiveScenesCannotVerifyWithoutExplicitTargetScene() {
        val baseline = BaselineState(runId = "scene-switch", sceneName = "SceneA")
        val observed = ObservedState(
            runId = "scene-switch",
            sceneName = "SceneB",
            objects = listOf(ObjectObservation("cube", "Cube", "MESH", sceneName = "SceneB"))
        )

        val result = verifier.verify(
            ExpectedState(VerificationOperation.CREATE, targetName = "Cube"),
            baseline,
            observed,
            listOf(EvidenceObservation(EvidenceSource.BLENDER))
        )

        assertEquals(VerificationStatus.UNCERTAIN, result.status)
    }

    @Test
    fun postActionObservationOlderThanBaselineIsStale() {
        val result = verifier.verify(
            ExpectedState(VerificationOperation.CREATE, targetName = "Cube"),
            BaselineState(runId = "stale-time", capturedAt = Instant.parse("2026-10-09T10:00:00Z")),
            ObservedState(
                runId = "stale-time",
                capturedAt = Instant.parse("2026-10-09T09:59:00Z"),
                objects = listOf(ObjectObservation("cube", "Cube", "MESH"))
            ),
            listOf(EvidenceObservation(EvidenceSource.BLENDER))
        )

        assertEquals(VerificationStatus.UNCERTAIN, result.status)
        assertTrue(result.explanation.contains("stale"))
    }

    @Test
    fun conflictingProviderObservationsReturnUncertain() {
        val baseline = BaselineState(runId = "conflict-run")
        val observed = ObservedState(runId = "conflict-run", objects = listOf(ObjectObservation("cube", "Cube", "MESH")))
        val result = verifier.verify(
            ExpectedState(VerificationOperation.CREATE, targetName = "Cube"),
            baseline,
            observed,
            listOf(
                EvidenceObservation(EvidenceSource.BLENDER, objectObservation = ObjectObservation("cube", "Cube", "MESH")),
                EvidenceObservation(EvidenceSource.UIA, objectObservation = ObjectObservation("cube", "Sphere", "MESH"))
            )
        )

        assertEquals(VerificationStatus.UNCERTAIN, result.status)
    }

    @Test
    fun visualOnlyEvidenceCannotEstablishApplicationStateSuccess() {
        val result = verifier.verify(
            ExpectedState(VerificationOperation.CREATE, targetName = "Cube"),
            BaselineState(runId = "visual-only"),
            ObservedState(runId = "visual-only", objects = listOf(ObjectObservation("cube", "Cube", "MESH"))),
            listOf(EvidenceObservation(EvidenceSource.VISUAL))
        )

        assertEquals(VerificationStatus.UNCERTAIN, result.status)
    }

    @Test
    fun providerUnavailableAndMissingObservationsRemainUncertain() {
        val expected = ExpectedState(VerificationOperation.DELETE, targetName = "Cube")
        val unavailable = EvidenceObservation(EvidenceSource.BLENDER, available = false, diagnostic = "provider unavailable")

        val missingBaseline = verifier.verify(expected, null, ObservedState(), listOf(unavailable))
        val missingObserved = verifier.verify(expected, BaselineState(), null, listOf(unavailable))

        assertEquals(VerificationStatus.UNCERTAIN, missingBaseline.status)
        assertEquals(VerificationStatus.UNCERTAIN, missingObserved.status)
        assertNull(missingBaseline.diffs.firstOrNull { it.type == StateChangeType.OBJECT_REMOVED })
    }

    @Test
    fun unavailableUiaEvidenceDoesNotTurnAStateMismatchIntoFailure() {
        val result = verifier.verify(
            ExpectedState(VerificationOperation.CREATE, targetName = "Cube"),
            BaselineState(runId = "uia-unavailable"),
            ObservedState(runId = "uia-unavailable", objects = emptyList()),
            listOf(EvidenceObservation(EvidenceSource.UIA, available = false, diagnostic = "UIA unavailable"))
        )

        assertEquals(VerificationStatus.UNCERTAIN, result.status)
    }

    @Test
    fun missingOrConflictingEvidenceReturnsUncertain() {
        val expected = ExpectedState(
            operation = VerificationOperation.RENAME,
            targetName = "CubeRenamed",
            targetType = "MESH",
            requiredConditions = listOf(
                ExpectedCondition(ExpectedStateField.OBJECT_NAME, expectedValue = "CubeRenamed", objectName = "CubeRenamed"),
                ExpectedCondition(ExpectedStateField.OBJECT_TYPE, expectedValue = "MESH", objectName = "CubeRenamed")
            )
        )

        val baseline = BaselineState(runId = "uncertain-run", objects = listOf(ObjectObservation(id = "1", name = "Cube", type = "MESH")))
        val observed = ObservedState(runId = "uncertain-run", objects = listOf(ObjectObservation(id = "1", name = "Cube", type = "MESH")))
        val result = verifier.verify(expected, baseline, observed, listOf(EvidenceObservation(EvidenceSource.VISUAL, available = false, diagnostic = "image capture failed")))

        assertEquals(VerificationStatus.UNCERTAIN, result.status)
    }

    @Test
    fun unavailableBlenderProviderIsExplicitlyRecorded() = kotlinx.coroutines.runBlocking {
        val provider = UnavailableBlenderStateProvider()
        val observation = provider.observe()
        assertEquals(BlenderObservationStatus.UNAVAILABLE, observation.status)
        assertTrue(observation.diagnostic!!.contains("Blender"))
    }
}
