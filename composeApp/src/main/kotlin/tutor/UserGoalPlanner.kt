package tutor

import context.ExpectedState
import context.VerificationOperation
import models.ApplicationType
import models.HighlightRegion
import java.util.UUID

/**
 * Deterministic rule-based task planner converting natural-language goals into structured tutorial plans.
 *
 * In Phase 12, the planner:
 * 1. Preserves original learner requests and explicit constraints.
 * 2. Decomposes high-level goals into explicit [RequiredOutcome] instances with classification
 *    (MANDATORY vs. OPTIONAL), dependencies, and honest [VerificationStrategy] limits.
 * 3. Associates each plan step with targeted outcome IDs and prerequisite step IDs.
 * 4. Detects ambiguous learner requests and asks for clarification.
 * 5. Validates plan coverage via [PlanCoverageValidator].
 * 6. Performs bounded targeted plan repair via [PlanRepairEngine].
 */
class UserGoalPlanner(
    val validator: PlanCoverageValidator = PlanCoverageValidator(),
    val repairEngine: PlanRepairEngine = PlanRepairEngine(validator)
) {

    fun planGoal(goalText: String, application: ApplicationType = ApplicationType.BLENDER): UserGoalPlan {
        val trimmed = goalText.trim()
        val normalized = trimmed.lowercase()

        // Detect ambiguous or underspecified requests first
        if (isAmbiguousGoal(normalized)) {
            return planAmbiguousGoal(trimmed)
        }

        val rawPlan = when {
            isMaterialAndRender(normalized) -> planMaterialAndRender(trimmed)
            isCompoundModeling(normalized) -> planCompoundModeling(trimmed)
            isCompoundMakeCubeAndMove(normalized) -> planMakeCubeAndMove(trimmed)
            isCreateSphere(normalized) -> planCreateSphere(trimmed)
            isCreateCube(normalized) -> planCreateCube(trimmed)
            isDeleteObject(normalized) -> planDeleteObject(trimmed, normalized)
            isRenameObject(normalized) -> planRenameObject(trimmed, normalized)
            isSelectObject(normalized) -> planSelectObject(trimmed, normalized)
            isMoveOrChangeLocation(normalized) -> planMoveOrChangeLocation(trimmed, normalized)
            else -> planGeneralFallback(trimmed)
        }

        // Validate coverage and ordering
        val validationResult = validator.validateCoverage(rawPlan)
        val planWithValidation = rawPlan.copy(validationResult = validationResult)

        // Run bounded repair if needed
        return if (!validationResult.isValid) {
            repairEngine.repairPlan(planWithValidation)
        } else {
            planWithValidation
        }
    }

    private fun isAmbiguousGoal(text: String): Boolean {
        val shortVagueTerms = setOf("help", "blender", "make something", "do 3d", "create", "render it", "start", "something")
        if (text in shortVagueTerms) return true
        if (text.length <= 4 && text !in setOf("cube")) return true
        return false
    }

    private fun planAmbiguousGoal(goalText: String): UserGoalPlan {
        val rawPlan = UserGoalPlan(
            goalText = goalText,
            application = ApplicationType.BLENDER,
            planId = UUID.randomUUID().toString(),
            isAmbiguous = true,
            clarificationPrompt = "Your request '$goalText' is ambiguous. Please specify what object you would like to work with (e.g. UV Sphere, Cube) and what operation you wish to perform (e.g. create, scale, position, material).",
            requiredOutcomes = emptyList(),
            steps = emptyList()
        )
        return rawPlan.copy(validationResult = validator.validateCoverage(rawPlan))
    }

    private fun isMaterialAndRender(text: String): Boolean {
        val hasMaterial = text.contains("material") || text.contains("color") || text.contains("texture") || text.contains("shader")
        val hasRender = text.contains("render")
        return hasMaterial && hasRender
    }

    private fun isCompoundModeling(text: String): Boolean {
        val hasSphere = text.contains("sphere")
        val hasScale = text.contains("scale") || text.contains("size") || text.contains("bigger") || text.contains("smaller")
        val hasMove = text.contains("move") || text.contains("ground") || text.contains("above") || text.contains("up")
        return hasSphere && hasScale && hasMove
    }

    private fun isCompoundMakeCubeAndMove(text: String): Boolean {
        val hasCube = text.contains("cube")
        val hasCreate = text.contains("make") || text.contains("create") || text.contains("add")
        val hasMove = text.contains("move") || text.contains("translate") || text.contains("up")
        return hasCube && hasCreate && hasMove
    }

    private fun isCreateSphere(text: String): Boolean {
        val hasSphere = text.contains("sphere") || text.contains("uv sphere") || text.contains("ico sphere")
        val hasIntent = text.contains("create") || text.contains("make") || text.contains("add") ||
            text.contains("new") || text.contains("want") || text.contains("teach") || text.startsWith("sphere") || text.startsWith("uv sphere")
        return hasSphere && (hasIntent || text.trim() == "sphere" || text.trim() == "uv sphere")
    }

    private fun isCreateCube(text: String): Boolean {
        val hasCube = text.contains("cube")
        val hasIntent = text.contains("create") || text.contains("make") || text.contains("add") ||
            text.contains("new") || text.contains("want") || text.contains("teach") || text.startsWith("cube")
        return hasCube && (hasIntent || text.trim() == "cube")
    }

    private fun isDeleteObject(text: String): Boolean {
        return text.contains("delete") || text.contains("remove") || text.contains("erase")
    }

    private fun isRenameObject(text: String): Boolean {
        return text.contains("rename")
    }

    private fun isSelectObject(text: String): Boolean {
        return text.contains("select") || text.contains("pick") || text.contains("choose")
    }

    private fun isMoveOrChangeLocation(text: String): Boolean {
        return text.contains("move") || text.contains("location") || text.contains("translate") ||
            text.contains("position")
    }

    internal fun planCreateSphere(goalText: String): UserGoalPlan {
        val planId = UUID.randomUUID().toString()
        val outcomes = listOf(
            RequiredOutcome(
                id = "out_mode_obj",
                description = "Blender is in Object Mode",
                importance = OutcomeRequirementLevel.MANDATORY,
                expectedFinalCondition = "Mode == OBJECT",
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE
            ),
            RequiredOutcome(
                id = "out_sphere_exists",
                description = "A UV Sphere mesh object exists in the scene",
                importance = OutcomeRequirementLevel.MANDATORY,
                dependencies = listOf("out_mode_obj"),
                expectedFinalCondition = "Sphere mesh exists in scene",
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE
            )
        )

        val steps = listOf(
            UserGoalPlanStep(
                stepId = "step_1",
                stepIndex = 1,
                title = "Ensure Object Mode",
                instruction = "Switch to Object Mode in the 3D Viewport header.",
                description = "Mesh primitives must be created in Object Mode rather than Edit Mode.",
                targetControlName = "Mode",
                targetedOutcomeIds = listOf("out_mode_obj"),
                prerequisiteStepIds = emptyList(),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                expectedState = ExpectedState(
                    operation = VerificationOperation.MODIFY,
                    targetMode = "OBJECT",
                    requiredConditions = listOf(
                        context.ExpectedCondition(context.ExpectedStateField.MODE, expectedValue = "OBJECT")
                    )
                ),
                conceptualHint = "Press Tab or use the top-left dropdown in the 3D Viewport to check mode.",
                fallbackHint = "Look at the top-left mode selector of the 3D Viewport and ensure it says 'Object Mode'.",
                reflectionQuestion = "Which mode in Blender allows creating new independent objects in the scene?",
                targetRegion = HighlightRegion(x = 45f, y = 26f, width = 125f, height = 31f)
            ),
            UserGoalPlanStep(
                stepId = "step_2",
                stepIndex = 2,
                title = "Add Mesh Sphere",
                instruction = "Press Shift+A and select Mesh > UV Sphere.",
                description = "Open the Add menu in the 3D Viewport and select 'UV Sphere' under Mesh.",
                targetControlName = "UV Sphere",
                targetedOutcomeIds = listOf("out_sphere_exists"),
                prerequisiteStepIds = listOf("step_1"),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                conceptualHint = "Press Shift+A anywhere in the 3D Viewport to open the Add menu.",
                fallbackHint = "Click 'Add' on the 3D Viewport header menu, navigate to 'Mesh', and choose 'UV Sphere'.",
                reflectionQuestion = "What menu or shortcut adds new geometric primitives in Blender?",
                targetRegion = HighlightRegion(x = 310f, y = 195f, width = 125f, height = 26f)
            ),
            UserGoalPlanStep(
                stepId = "step_3",
                stepIndex = 3,
                title = "Verify a Sphere object exists",
                instruction = "Check the scene to confirm the Sphere has been created.",
                description = "The newly created Sphere should appear in the 3D Viewport and the Outliner list.",
                targetControlName = "Sphere",
                targetedOutcomeIds = listOf("out_sphere_exists"),
                prerequisiteStepIds = listOf("step_2"),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                expectedState = ExpectedState(
                    operation = VerificationOperation.CREATE,
                    targetName = "Sphere",
                    targetType = "MESH"
                ),
                conceptualHint = "Verify that 'Sphere' appears in the Outliner hierarchy on the right side.",
                fallbackHint = "If the Sphere is not in the Outliner, ensure you are in Object Mode and press Shift+A > Mesh > UV Sphere.",
                reflectionQuestion = "Where in Blender's interface can you see the hierarchical list of all objects in the scene?",
                targetRegion = HighlightRegion(x = 670f, y = 360f, width = 300f, height = 260f)
            )
        )

        return UserGoalPlan(
            goalText = goalText,
            application = ApplicationType.BLENDER,
            planId = planId,
            requiredOutcomes = outcomes,
            steps = steps
        )
    }

    internal fun planCreateCube(goalText: String): UserGoalPlan {
        val planId = UUID.randomUUID().toString()
        val outcomes = listOf(
            RequiredOutcome(
                id = "out_mode_obj",
                description = "Blender is in Object Mode",
                importance = OutcomeRequirementLevel.MANDATORY,
                expectedFinalCondition = "Mode == OBJECT",
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE
            ),
            RequiredOutcome(
                id = "out_cube_exists",
                description = "A Cube mesh object exists in the scene",
                importance = OutcomeRequirementLevel.MANDATORY,
                dependencies = listOf("out_mode_obj"),
                expectedFinalCondition = "Cube mesh exists in scene",
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE
            )
        )

        val steps = listOf(
            UserGoalPlanStep(
                stepId = "step_1",
                stepIndex = 1,
                title = "Ensure Object Mode",
                instruction = "Switch to Object Mode in the 3D Viewport header.",
                description = "Mesh primitives must be created in Object Mode.",
                targetControlName = "Mode",
                targetedOutcomeIds = listOf("out_mode_obj"),
                prerequisiteStepIds = emptyList(),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                expectedState = ExpectedState(
                    operation = VerificationOperation.MODIFY,
                    targetMode = "OBJECT",
                    requiredConditions = listOf(
                        context.ExpectedCondition(context.ExpectedStateField.MODE, expectedValue = "OBJECT")
                    )
                ),
                conceptualHint = "Press Tab to toggle between Edit Mode and Object Mode.",
                fallbackHint = "Verify the top-left dropdown in the 3D Viewport reads 'Object Mode'.",
                reflectionQuestion = "Which mode must you be in to add a separate Cube primitive to the scene?",
                targetRegion = HighlightRegion(x = 45f, y = 26f, width = 125f, height = 31f)
            ),
            UserGoalPlanStep(
                stepId = "step_2",
                stepIndex = 2,
                title = "Add Mesh Cube",
                instruction = "Press Shift+A and select Mesh > Cube.",
                description = "Open the Add menu in the 3D Viewport and select 'Cube' under Mesh.",
                targetControlName = "Cube",
                targetedOutcomeIds = listOf("out_cube_exists"),
                prerequisiteStepIds = listOf("step_1"),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                conceptualHint = "Use the Shift+A shortcut to open the Add primitive menu.",
                fallbackHint = "Navigate to Add menu > Mesh submenu > Cube.",
                reflectionQuestion = "What shortcut opens the Add menu in Blender?",
                targetRegion = HighlightRegion(x = 310f, y = 170f, width = 125f, height = 26f)
            ),
            UserGoalPlanStep(
                stepId = "step_3",
                stepIndex = 3,
                title = "Verify Cube Exists",
                instruction = "Confirm that the Cube mesh exists in the active scene.",
                description = "Check that Cube appears in the 3D Viewport and in the Outliner.",
                targetControlName = "Cube",
                targetedOutcomeIds = listOf("out_cube_exists"),
                prerequisiteStepIds = listOf("step_2"),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                expectedState = ExpectedState(
                    operation = VerificationOperation.CREATE,
                    targetName = "Cube",
                    targetType = "MESH"
                ),
                conceptualHint = "Check the Outliner panel on the top-right of Blender to confirm the Cube exists.",
                fallbackHint = "If the cube did not appear, verify you are in Object Mode and try Shift+A > Mesh > Cube again.",
                reflectionQuestion = "How do you verify whether an object was successfully added to the current scene?",
                targetRegion = HighlightRegion(x = 670f, y = 360f, width = 300f, height = 260f)
            )
        )

        return UserGoalPlan(
            goalText = goalText,
            application = ApplicationType.BLENDER,
            planId = planId,
            requiredOutcomes = outcomes,
            steps = steps
        )
    }

    internal fun planMakeCubeAndMove(goalText: String): UserGoalPlan {
        val planId = UUID.randomUUID().toString()
        val outcomes = listOf(
            RequiredOutcome(
                id = "out_mode_obj",
                description = "Blender is in Object Mode",
                importance = OutcomeRequirementLevel.MANDATORY,
                expectedFinalCondition = "Mode == OBJECT",
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE
            ),
            RequiredOutcome(
                id = "out_cube_exists",
                description = "A Cube mesh object exists in the scene",
                importance = OutcomeRequirementLevel.MANDATORY,
                dependencies = listOf("out_mode_obj"),
                expectedFinalCondition = "Cube exists",
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE
            ),
            RequiredOutcome(
                id = "out_cube_moved",
                description = "Cube is moved upward along the vertical Z-axis",
                importance = OutcomeRequirementLevel.MANDATORY,
                dependencies = listOf("out_cube_exists"),
                expectedFinalCondition = "Cube location.z > 0",
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE
            )
        )

        val steps = listOf(
            UserGoalPlanStep(
                stepId = "step_1",
                stepIndex = 1,
                title = "Ensure Object Mode",
                instruction = "Switch to Object Mode in the 3D Viewport header.",
                description = "Mesh primitives must be created in Object Mode.",
                targetControlName = "Mode",
                targetedOutcomeIds = listOf("out_mode_obj"),
                prerequisiteStepIds = emptyList(),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                conceptualHint = "Press Tab or use the top-left dropdown in the 3D Viewport to enter Object Mode.",
                fallbackHint = "Verify that the mode dropdown displays 'Object Mode'.",
                targetRegion = HighlightRegion(x = 45f, y = 26f, width = 125f, height = 31f)
            ),
            UserGoalPlanStep(
                stepId = "step_2",
                stepIndex = 2,
                title = "Add Mesh Cube",
                instruction = "Press Shift+A or click Add > Mesh > Cube.",
                description = "Add a new Cube mesh to the scene at the 3D Cursor.",
                targetControlName = "Cube",
                targetedOutcomeIds = listOf("out_cube_exists"),
                prerequisiteStepIds = listOf("step_1"),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                conceptualHint = "Use Shift+A to open the Add menu in the 3D Viewport.",
                fallbackHint = "Navigate to Add > Mesh > Cube."
            ),
            UserGoalPlanStep(
                stepId = "step_3",
                stepIndex = 3,
                title = "Verify Cube Exists",
                instruction = "Confirm that the Cube exists in the scene.",
                description = "Verify the newly created Cube object appears in the scene.",
                targetControlName = "Cube",
                targetedOutcomeIds = listOf("out_cube_exists"),
                prerequisiteStepIds = listOf("step_2"),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                expectedState = ExpectedState(
                    operation = VerificationOperation.CREATE,
                    targetName = "Cube",
                    targetType = "MESH"
                ),
                conceptualHint = "Check the Outliner to confirm the Cube exists.",
                fallbackHint = "If the Cube is not in the Outliner, try Shift+A > Mesh > Cube again."
            ),
            UserGoalPlanStep(
                stepId = "step_4",
                stepIndex = 4,
                title = "Move Cube Up",
                instruction = "Press G, then Z to constrain to the Z-axis, move the mouse upward, and left-click to confirm.",
                description = "Translate the Cube upward along the vertical Z-axis.",
                targetControlName = "3D Viewport",
                targetedOutcomeIds = listOf("out_cube_moved"),
                prerequisiteStepIds = listOf("step_3"),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                conceptualHint = "Press G followed by Z to translate along the vertical axis.",
                fallbackHint = "Alternatively, open the Transform panel (press N) and increase the Z Location value.",
                reflectionQuestion = "Which key constraint moves objects along the vertical axis in Blender?"
            )
        )

        return UserGoalPlan(
            goalText = goalText,
            application = ApplicationType.BLENDER,
            planId = planId,
            requiredOutcomes = outcomes,
            steps = steps
        )
    }

    internal fun planCompoundModeling(goalText: String): UserGoalPlan {
        val planId = UUID.randomUUID().toString()
        val outcomes = listOf(
            RequiredOutcome(
                id = "out_mode_obj",
                description = "Blender is in Object Mode",
                importance = OutcomeRequirementLevel.MANDATORY,
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE
            ),
            RequiredOutcome(
                id = "out_sphere_created",
                description = "A UV Sphere mesh object exists in the scene",
                importance = OutcomeRequirementLevel.MANDATORY,
                dependencies = listOf("out_mode_obj"),
                expectedFinalCondition = "Sphere mesh exists in scene",
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE
            ),
            RequiredOutcome(
                id = "out_sphere_scaled",
                description = "The sphere is scaled to desired proportions",
                importance = OutcomeRequirementLevel.MANDATORY,
                dependencies = listOf("out_sphere_created"),
                expectedFinalCondition = "Sphere scale != (1, 1, 1)",
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE
            ),
            RequiredOutcome(
                id = "out_sphere_moved",
                description = "The sphere is moved above the ground plane",
                importance = OutcomeRequirementLevel.MANDATORY,
                dependencies = listOf("out_sphere_created"),
                expectedFinalCondition = "Sphere location.z > 0",
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE
            )
        )

        val steps = listOf(
            UserGoalPlanStep(
                stepId = "step_1",
                stepIndex = 1,
                title = "Ensure Object Mode",
                instruction = "Switch to Object Mode in the 3D Viewport header.",
                description = "Ensure Blender is in Object Mode.",
                targetControlName = "Mode",
                targetedOutcomeIds = listOf("out_mode_obj"),
                prerequisiteStepIds = emptyList(),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                targetRegion = HighlightRegion(x = 45f, y = 26f, width = 125f, height = 31f)
            ),
            UserGoalPlanStep(
                stepId = "step_2",
                stepIndex = 2,
                title = "Add UV Sphere",
                instruction = "Press Shift+A and select Mesh > UV Sphere.",
                description = "Add a UV Sphere primitive to the scene.",
                targetControlName = "UV Sphere",
                targetedOutcomeIds = listOf("out_sphere_created"),
                prerequisiteStepIds = listOf("step_1"),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                expectedState = ExpectedState(
                    operation = VerificationOperation.CREATE,
                    targetName = "Sphere",
                    targetType = "MESH"
                )
            ),
            UserGoalPlanStep(
                stepId = "step_3",
                stepIndex = 3,
                title = "Scale the Sphere",
                instruction = "Press S, move mouse outward to scale up, then left-click.",
                description = "Scale the sphere primitive.",
                targetControlName = "3D Viewport",
                targetedOutcomeIds = listOf("out_sphere_scaled"),
                prerequisiteStepIds = listOf("step_2"),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE
            ),
            UserGoalPlanStep(
                stepId = "step_4",
                stepIndex = 4,
                title = "Move Sphere Above Ground",
                instruction = "Press G, then Z, move mouse up, and left-click to position above ground.",
                description = "Position the sphere above the ground plane (Z > 0).",
                targetControlName = "3D Viewport",
                targetedOutcomeIds = listOf("out_sphere_moved"),
                prerequisiteStepIds = listOf("step_2"),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE
            )
        )

        return UserGoalPlan(
            goalText = goalText,
            application = ApplicationType.BLENDER,
            planId = planId,
            requiredOutcomes = outcomes,
            steps = steps
        )
    }

    internal fun planMaterialAndRender(goalText: String): UserGoalPlan {
        val planId = UUID.randomUUID().toString()
        val outcomes = listOf(
            RequiredOutcome(
                id = "out_sphere_created",
                description = "A UV Sphere mesh exists in the scene",
                importance = OutcomeRequirementLevel.MANDATORY,
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE
            ),
            RequiredOutcome(
                id = "out_material_assigned",
                description = "A material is assigned to the sphere",
                importance = OutcomeRequirementLevel.MANDATORY,
                dependencies = listOf("out_sphere_created"),
                verificationStrategy = VerificationStrategy.MANUAL_CHECK,
                limitationNote = "Blender telemetry adapter does not expose material slot assignment; requires manual or visual verification."
            ),
            RequiredOutcome(
                id = "out_camera_lighting_setup",
                description = "A usable camera and lighting are configured in the scene",
                importance = OutcomeRequirementLevel.MANDATORY,
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE
            ),
            RequiredOutcome(
                id = "out_render_produced",
                description = "A render of the scene is produced",
                importance = OutcomeRequirementLevel.MANDATORY,
                dependencies = listOf("out_sphere_created", "out_camera_lighting_setup"),
                verificationStrategy = VerificationStrategy.MANUAL_CHECK,
                limitationNote = "Blender render image output buffer is not verified by telemetry bridge; manual verification required."
            )
        )

        val steps = listOf(
            UserGoalPlanStep(
                stepId = "step_1",
                stepIndex = 1,
                title = "Create UV Sphere",
                instruction = "Press Shift+A and select Mesh > UV Sphere.",
                description = "Create a UV Sphere primitive.",
                targetControlName = "UV Sphere",
                targetedOutcomeIds = listOf("out_sphere_created"),
                prerequisiteStepIds = emptyList(),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                expectedState = ExpectedState(
                    operation = VerificationOperation.CREATE,
                    targetName = "Sphere",
                    targetType = "MESH"
                )
            ),
            UserGoalPlanStep(
                stepId = "step_2",
                stepIndex = 2,
                title = "Assign Material",
                instruction = "In the Material Properties panel, click '+ New' to create and assign a new material to the sphere.",
                description = "Assign a material to the sphere.",
                targetControlName = "Material Properties",
                targetedOutcomeIds = listOf("out_material_assigned"),
                prerequisiteStepIds = listOf("step_1"),
                verificationStrategy = VerificationStrategy.MANUAL_CHECK
            ),
            UserGoalPlanStep(
                stepId = "step_3",
                stepIndex = 3,
                title = "Configure Camera and Lighting",
                instruction = "Ensure a Camera and a Light object exist in the scene (add via Shift+A if missing) and frame the sphere.",
                description = "Set up camera framing and lighting.",
                targetControlName = "Camera",
                targetedOutcomeIds = listOf("out_camera_lighting_setup"),
                prerequisiteStepIds = emptyList(),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE
            ),
            UserGoalPlanStep(
                stepId = "step_4",
                stepIndex = 4,
                title = "Render Scene",
                instruction = "Press F12 or select Render > Render Image from the top menu to produce the render.",
                description = "Produce the final render frame.",
                targetControlName = "Render",
                targetedOutcomeIds = listOf("out_render_produced"),
                prerequisiteStepIds = listOf("step_2", "step_3"),
                verificationStrategy = VerificationStrategy.MANUAL_CHECK
            )
        )

        return UserGoalPlan(
            goalText = goalText,
            application = ApplicationType.BLENDER,
            planId = planId,
            requiredOutcomes = outcomes,
            steps = steps
        )
    }

    private fun planDeleteObject(goalText: String, normalized: String): UserGoalPlan {
        val targetName = extractTargetObjectName(normalized) ?: "Cube"
        val planId = UUID.randomUUID().toString()
        val outcomes = listOf(
            RequiredOutcome(
                id = "out_delete_$targetName",
                description = "Object '$targetName' is deleted from the scene",
                importance = OutcomeRequirementLevel.MANDATORY,
                expectedFinalCondition = "'$targetName' does not exist in scene",
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE
            )
        )

        val steps = listOf(
            UserGoalPlanStep(
                stepId = "step_1",
                stepIndex = 1,
                title = "Select Object to Delete",
                instruction = "Left-click '$targetName' in the 3D Viewport or click it in the Outliner.",
                description = "Select the object you want to remove.",
                targetControlName = targetName,
                targetedOutcomeIds = listOf("out_delete_$targetName"),
                prerequisiteStepIds = emptyList(),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                conceptualHint = "Click the object in the Outliner on the right to select it reliably.",
                fallbackHint = "Ensure '$targetName' has an orange outline indicating it is selected.",
                reflectionQuestion = "How do you select an object before deleting it?"
            ),
            UserGoalPlanStep(
                stepId = "step_2",
                stepIndex = 2,
                title = "Delete Object",
                instruction = "Press X or Delete, then click Delete to confirm.",
                description = "Remove '$targetName' from the scene.",
                targetControlName = "Delete",
                targetedOutcomeIds = listOf("out_delete_$targetName"),
                prerequisiteStepIds = listOf("step_1"),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                expectedState = ExpectedState(
                    operation = VerificationOperation.DELETE,
                    targetName = targetName
                ),
                conceptualHint = "Press 'X' or 'Delete' key on your keyboard.",
                fallbackHint = "Press X and click 'Delete' in the confirmation popup.",
                reflectionQuestion = "Which keyboard shortcut deletes the selected object in Blender?"
            )
        )

        return UserGoalPlan(
            goalText = goalText,
            application = ApplicationType.BLENDER,
            planId = planId,
            requiredOutcomes = outcomes,
            steps = steps
        )
    }

    private fun planRenameObject(goalText: String, normalized: String): UserGoalPlan {
        val (oldName, newName) = extractRenameNames(goalText, normalized)
        val planId = UUID.randomUUID().toString()
        val outcomes = listOf(
            RequiredOutcome(
                id = "out_rename_$newName",
                description = "Object '$oldName' is renamed to '$newName'",
                importance = OutcomeRequirementLevel.MANDATORY,
                expectedFinalCondition = "'$newName' exists in scene",
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE
            )
        )

        val steps = listOf(
            UserGoalPlanStep(
                stepId = "step_1",
                stepIndex = 1,
                title = "Select Object to Rename",
                instruction = "Select '$oldName' in the Outliner or 3D Viewport.",
                description = "Locate and activate the object you want to rename.",
                targetControlName = oldName,
                targetedOutcomeIds = listOf("out_rename_$newName"),
                prerequisiteStepIds = emptyList(),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                conceptualHint = "Click on '$oldName' in the Outliner on the right.",
                fallbackHint = "Find '$oldName' in the Outliner hierarchy.",
                reflectionQuestion = "Where can you see the names of all objects in Blender?"
            ),
            UserGoalPlanStep(
                stepId = "step_2",
                stepIndex = 2,
                title = "Rename to $newName",
                instruction = "Double-click '$oldName' in the Outliner (or press F2), type '$newName', and press Enter.",
                description = "Rename the object to '$newName'.",
                targetControlName = "Outliner",
                targetedOutcomeIds = listOf("out_rename_$newName"),
                prerequisiteStepIds = listOf("step_1"),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                expectedState = ExpectedState(
                    operation = VerificationOperation.RENAME,
                    targetName = newName
                ),
                conceptualHint = "Press F2 while an object is selected to rename it.",
                fallbackHint = "Double-click the object name directly in the Outliner list.",
                reflectionQuestion = "Which shortcut opens the Rename popup for the active object?"
            )
        )

        return UserGoalPlan(
            goalText = goalText,
            application = ApplicationType.BLENDER,
            planId = planId,
            requiredOutcomes = outcomes,
            steps = steps
        )
    }

    private fun planSelectObject(goalText: String, normalized: String): UserGoalPlan {
        val targetName = extractTargetObjectName(normalized) ?: "Cube"
        val planId = UUID.randomUUID().toString()
        val outcomes = listOf(
            RequiredOutcome(
                id = "out_select_$targetName",
                description = "Object '$targetName' is selected",
                importance = OutcomeRequirementLevel.MANDATORY,
                expectedFinalCondition = "'$targetName' is selected",
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE
            )
        )

        val steps = listOf(
            UserGoalPlanStep(
                stepId = "step_1",
                stepIndex = 1,
                title = "Locate $targetName",
                instruction = "Find '$targetName' in the 3D Viewport or the Outliner panel.",
                description = "Locate '$targetName' in your scene workspace.",
                targetControlName = targetName,
                targetedOutcomeIds = listOf("out_select_$targetName"),
                prerequisiteStepIds = emptyList(),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                conceptualHint = "Use the Outliner on the top right to locate objects by name.",
                fallbackHint = "Look at the list of objects in the scene Outliner.",
                reflectionQuestion = "Which panel lists all objects in the Blender scene?"
            ),
            UserGoalPlanStep(
                stepId = "step_2",
                stepIndex = 2,
                title = "Select $targetName",
                instruction = "Left-click on '$targetName' to select it.",
                description = "Ensure '$targetName' is active and selected.",
                targetControlName = targetName,
                targetedOutcomeIds = listOf("out_select_$targetName"),
                prerequisiteStepIds = listOf("step_1"),
                verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                expectedState = ExpectedState(
                    operation = VerificationOperation.SELECT,
                    targetName = targetName,
                    expectedSelection = true
                ),
                conceptualHint = "Clicking an object selects it with an orange outline.",
                fallbackHint = "Click directly on '$targetName' in the Outliner.",
                reflectionQuestion = "How do you know an object is currently selected in the 3D Viewport?"
            )
        )

        return UserGoalPlan(
            goalText = goalText,
            application = ApplicationType.BLENDER,
            planId = planId,
            requiredOutcomes = outcomes,
            steps = steps
        )
    }

    private fun planMoveOrChangeLocation(goalText: String, normalized: String): UserGoalPlan {
        val targetName = extractTargetObjectName(normalized) ?: "Cube"
        val explicitLocation = extractExplicitLocation(goalText)
        val planId = UUID.randomUUID().toString()

        val outcome = RequiredOutcome(
            id = "out_move_$targetName",
            description = if (explicitLocation != null) "Object '$targetName' is moved to $explicitLocation" else "Object '$targetName' is moved",
            importance = OutcomeRequirementLevel.MANDATORY,
            expectedFinalCondition = if (explicitLocation != null) "Location == $explicitLocation" else "Object moved",
            verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE
        )

        return if (explicitLocation != null) {
            UserGoalPlan(
                goalText = goalText,
                application = ApplicationType.BLENDER,
                planId = planId,
                requiredOutcomes = listOf(outcome),
                steps = listOf(
                    UserGoalPlanStep(
                        stepId = "step_1",
                        stepIndex = 1,
                        title = "Select $targetName",
                        instruction = "Select '$targetName' in the 3D Viewport or Outliner.",
                        description = "Ensure '$targetName' is selected before changing its location.",
                        targetControlName = targetName,
                        targetedOutcomeIds = listOf(outcome.id),
                        prerequisiteStepIds = emptyList(),
                        verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                        conceptualHint = "Click '$targetName' in the Outliner on the right.",
                        fallbackHint = "Verify '$targetName' has an active selection outline."
                    ),
                    UserGoalPlanStep(
                        stepId = "step_2",
                        stepIndex = 2,
                        title = "Set Location to $explicitLocation",
                        instruction = "In the Transform panel (press N if hidden), set Location to $explicitLocation.",
                        description = "Enter coordinates $explicitLocation in the Item > Transform sidebar.",
                        targetControlName = "Location",
                        targetedOutcomeIds = listOf(outcome.id),
                        prerequisiteStepIds = listOf("step_1"),
                        verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                        expectedState = ExpectedState(
                            operation = VerificationOperation.MODIFY,
                            targetName = targetName,
                            targetProperty = "location",
                            expectedPropertyValue = explicitLocation
                        ),
                        conceptualHint = "Press N in the 3D Viewport to display the Transform sidebar.",
                        fallbackHint = "Type $explicitLocation into the X, Y, Z location fields.",
                        reflectionQuestion = "Which shortcut toggles the Sidebar containing Transform coordinates?"
                    )
                )
            )
        } else {
            UserGoalPlan(
                goalText = goalText,
                application = ApplicationType.BLENDER,
                planId = planId,
                requiredOutcomes = listOf(outcome),
                steps = listOf(
                    UserGoalPlanStep(
                        stepId = "step_1",
                        stepIndex = 1,
                        title = "Select $targetName",
                        instruction = "Select '$targetName' in the 3D Viewport.",
                        description = "Click to select '$targetName'.",
                        targetControlName = targetName,
                        targetedOutcomeIds = listOf(outcome.id),
                        prerequisiteStepIds = emptyList(),
                        verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                        conceptualHint = "Left-click the object in the 3D Viewport or Outliner.",
                        fallbackHint = "Verify '$targetName' is highlighted in orange.",
                        reflectionQuestion = "How do you select an object to prepare it for transformation?"
                    ),
                    UserGoalPlanStep(
                        stepId = "step_2",
                        stepIndex = 2,
                        title = "Move $targetName",
                        instruction = "Press G to grab and move '$targetName', then left-click to place it.",
                        description = "Reposition '$targetName' in the 3D space.",
                        targetControlName = "3D Viewport",
                        targetedOutcomeIds = listOf(outcome.id),
                        prerequisiteStepIds = listOf("step_1"),
                        verificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
                        conceptualHint = "Press G to enter Grab/Move mode, move the mouse, then left-click.",
                        fallbackHint = "Use G followed by X, Y, or Z to constrain movement along an axis.",
                        reflectionQuestion = "Which key starts the Move (Grab) operator in Blender?"
                    )
                )
            )
        }
    }

    private fun planGeneralFallback(goalText: String): UserGoalPlan {
        val planId = UUID.randomUUID().toString()
        val outcomes = listOf(
            RequiredOutcome(
                id = "out_fallback_action",
                description = "Accomplish '$goalText' in Blender",
                importance = OutcomeRequirementLevel.MANDATORY,
                verificationStrategy = VerificationStrategy.MANUAL_CHECK,
                limitationNote = "General fallback plan does not have automated authoritative telemetry verification."
            )
        )

        return UserGoalPlan(
            goalText = goalText,
            application = ApplicationType.BLENDER,
            planId = planId,
            requiredOutcomes = outcomes,
            steps = listOf(
                UserGoalPlanStep(
                    stepId = "step_1",
                    stepIndex = 1,
                    title = "Locate Tool in Blender",
                    instruction = "Locate the interface control or menu for '$goalText'.",
                    description = "Find the relevant command in Blender's header menus or toolbar.",
                    targetControlName = "Blender Interface",
                    targetedOutcomeIds = listOf("out_fallback_action"),
                    prerequisiteStepIds = emptyList(),
                    verificationStrategy = VerificationStrategy.MANUAL_CHECK,
                    conceptualHint = "Press F3 in Blender to open the operator search menu and type your command.",
                    fallbackHint = "Use the F3 Operator Search dialog if you cannot find the button directly."
                ),
                UserGoalPlanStep(
                    stepId = "step_2",
                    stepIndex = 2,
                    title = "Execute Action",
                    instruction = "Perform the action for '$goalText' in Blender.",
                    description = "Apply the desired operation in the 3D Viewport.",
                    targetControlName = "3D Viewport",
                    targetedOutcomeIds = listOf("out_fallback_action"),
                    prerequisiteStepIds = listOf("step_1"),
                    verificationStrategy = VerificationStrategy.MANUAL_CHECK,
                    conceptualHint = "Follow the tooltip prompts in Blender to complete the operation.",
                    fallbackHint = "Press Ctrl+Z if you make a mistake and want to retry."
                ),
                UserGoalPlanStep(
                    stepId = "step_3",
                    stepIndex = 3,
                    title = "Verify Result",
                    instruction = "Review the scene changes in the 3D Viewport and Outliner.",
                    description = "Confirm that '$goalText' was accomplished successfully.",
                    targetControlName = "3D Viewport",
                    targetedOutcomeIds = listOf("out_fallback_action"),
                    prerequisiteStepIds = listOf("step_2"),
                    verificationStrategy = VerificationStrategy.MANUAL_CHECK,
                    conceptualHint = "Inspect the active object and scene properties.",
                    fallbackHint = "Review your active scene state."
                )
            )
        )
    }

    private fun extractTargetObjectName(normalized: String): String? {
        return when {
            normalized.contains("sphere") -> "Sphere"
            normalized.contains("cube") -> "Cube"
            normalized.contains("camera") -> "Camera"
            normalized.contains("light") -> "Light"
            normalized.contains("plane") -> "Plane"
            normalized.contains("cylinder") -> "Cylinder"
            else -> {
                val match = Regex("""(?:delete|remove|select|move)\s+(?:the\s+)?([a-zA-Z0-9_-]+)""").find(normalized)
                val candidate = match?.groupValues?.get(1)?.trim()
                if (candidate != null && candidate !in setOf("object", "the", "mesh", "it", "selected")) {
                    candidate.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                } else null
            }
        }
    }

    private fun extractRenameNames(original: String, normalized: String): Pair<String, String> {
        val regexWithTo = Regex("""rename\s+(?:the\s+)?([a-zA-Z0-9_-]+)\s+to\s+([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE)
        val match = regexWithTo.find(original)
        if (match != null) {
            val oldRaw = match.groupValues[1].trim()
            val newRaw = match.groupValues[2].trim()
            val oldName = if (oldRaw.equals("object", ignoreCase = true)) "Cube" else oldRaw
            return Pair(oldName, newRaw)
        }

        val regexToOnly = Regex("""rename\s+to\s+([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE)
        val matchTo = regexToOnly.find(original)
        if (matchTo != null) {
            return Pair("Cube", matchTo.groupValues[1].trim())
        }

        val target = extractTargetObjectName(normalized) ?: "Cube"
        return Pair(target, "RenamedObject")
    }

    private fun extractExplicitLocation(text: String): String? {
        val coordRegex = Regex("""(?:to\s+)?\(?\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\)?""")
        val match = coordRegex.find(text)
        if (match != null) {
            val (x, y, z) = match.destructured
            return "$x, $y, $z"
        }
        return null
    }
}
