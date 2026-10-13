package tutor

import context.ExpectedState
import context.VerificationOperation
import models.ApplicationType
import models.HighlightRegion

/**
 * Deterministic rule-based task planner converting natural-language goals into structured tutorial plans.
 *
 * NOTE: This is an initial deterministic rule-based planner, not full general AI or cloud-only planning.
 * It parses recognized intention patterns and constructs ordered, pedagogy-ready tutorial steps with
 * closed-loop verification constraints where possible.
 */
class UserGoalPlanner {

    fun planGoal(goalText: String, application: ApplicationType = ApplicationType.BLENDER): UserGoalPlan {
        val trimmed = goalText.trim()
        val normalized = trimmed.lowercase()

        // Currently specialized for Blender workflows
        return when {
            isCompoundMakeCubeAndMove(normalized) -> planMakeCubeAndMove(trimmed)
            isCreateSphere(normalized) -> planCreateSphere(trimmed)
            isCreateCube(normalized) -> planCreateCube(trimmed)
            isDeleteObject(normalized) -> planDeleteObject(trimmed, normalized)
            isRenameObject(normalized) -> planRenameObject(trimmed, normalized)
            isSelectObject(normalized) -> planSelectObject(trimmed, normalized)
            isMoveOrChangeLocation(normalized) -> planMoveOrChangeLocation(trimmed, normalized)
            else -> planGeneralFallback(trimmed)
        }
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
            text.contains("new") || text.contains("want") || text.startsWith("sphere") || text.startsWith("uv sphere")
        return hasSphere && (hasIntent || text.trim() == "sphere" || text.trim() == "uv sphere")
    }

    private fun isCreateCube(text: String): Boolean {
        val hasCube = text.contains("cube")
        val hasIntent = text.contains("create") || text.contains("make") || text.contains("add") ||
            text.contains("new") || text.contains("want") || text.startsWith("cube")
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

    private fun planCreateSphere(goalText: String): UserGoalPlan {
        return UserGoalPlan(
            goalText = goalText,
            application = ApplicationType.BLENDER,
            steps = listOf(
                UserGoalPlanStep(
                    stepIndex = 1,
                    title = "Ensure Object Mode",
                    instruction = "Switch to Object Mode in the 3D Viewport header.",
                    description = "Mesh primitives must be created in Object Mode rather than Edit Mode.",
                    targetControlName = "Mode",
                    conceptualHint = "Press Tab or use the top-left dropdown in the 3D Viewport to check mode.",
                    fallbackHint = "Look at the top-left mode selector of the 3D Viewport and ensure it says 'Object Mode'.",
                    reflectionQuestion = "Which mode in Blender allows creating new independent objects in the scene?",
                    targetRegion = HighlightRegion(x = 100f, y = 78f, width = 80f, height = 28f)
                ),
                UserGoalPlanStep(
                    stepIndex = 2,
                    title = "Add Mesh Sphere",
                    instruction = "Press Shift+A and select Mesh > UV Sphere.",
                    description = "Open the Add menu in the 3D Viewport and select 'UV Sphere' under Mesh.",
                    targetControlName = "UV Sphere",
                    conceptualHint = "Press Shift+A anywhere in the 3D Viewport to open the Add menu.",
                    fallbackHint = "Click 'Add' on the 3D Viewport header menu, navigate to 'Mesh', and choose 'UV Sphere'.",
                    reflectionQuestion = "What menu or shortcut adds new geometric primitives in Blender?",
                    targetRegion = HighlightRegion(x = 310f, y = 195f, width = 125f, height = 26f)
                ),
                UserGoalPlanStep(
                    stepIndex = 3,
                    title = "Verify a Sphere object exists",
                    instruction = "Check the scene to confirm the Sphere has been created.",
                    description = "The newly created Sphere should appear in the 3D Viewport and the Outliner list.",
                    targetControlName = "Sphere",
                    expectedState = ExpectedState(
                        operation = VerificationOperation.CREATE,
                        targetName = "Sphere",
                        targetType = "MESH"
                    ),
                    conceptualHint = "Verify that 'Sphere' appears in the Outliner hierarchy on the right side.",
                    fallbackHint = "If the Sphere is not in the Outliner, ensure you are in Object Mode and press Shift+A > Mesh > UV Sphere.",
                    reflectionQuestion = "Where in Blender's interface can you see the hierarchical list of all objects in the scene?",
                    targetRegion = HighlightRegion(x = 420f, y = 280f, width = 300f, height = 240f)
                )
            )
        )
    }

    private fun planCreateCube(goalText: String): UserGoalPlan {
        return UserGoalPlan(
            goalText = goalText,
            application = ApplicationType.BLENDER,
            steps = listOf(
                UserGoalPlanStep(
                    stepIndex = 1,
                    title = "Ensure Object Mode",
                    instruction = "Switch to Object Mode in the 3D Viewport header.",
                    description = "Mesh primitives must be created in Object Mode.",
                    targetControlName = "Mode",
                    conceptualHint = "Press Tab to toggle between Edit Mode and Object Mode.",
                    fallbackHint = "Verify the top-left dropdown in the 3D Viewport reads 'Object Mode'.",
                    reflectionQuestion = "Which mode must you be in to add a separate Cube primitive to the scene?",
                    targetRegion = HighlightRegion(x = 100f, y = 78f, width = 80f, height = 28f)
                ),
                UserGoalPlanStep(
                    stepIndex = 2,
                    title = "Add Mesh Cube",
                    instruction = "Press Shift+A and select Mesh > Cube.",
                    description = "Open the Add menu in the 3D Viewport and select 'Cube' under Mesh.",
                    targetControlName = "Cube",
                    conceptualHint = "Use the Shift+A shortcut to open the Add primitive menu.",
                    fallbackHint = "Navigate to Add menu > Mesh submenu > Cube.",
                    reflectionQuestion = "What shortcut opens the Add menu in Blender?",
                    targetRegion = HighlightRegion(x = 310f, y = 170f, width = 125f, height = 26f)
                ),
                UserGoalPlanStep(
                    stepIndex = 3,
                    title = "Verify Cube Exists",
                    instruction = "Confirm that the Cube mesh exists in the active scene.",
                    description = "Check that Cube appears in the 3D Viewport and in the Outliner.",
                    targetControlName = "Cube",
                    expectedState = ExpectedState(
                        operation = VerificationOperation.CREATE,
                        targetName = "Cube",
                        targetType = "MESH"
                    ),
                    conceptualHint = "Check the Outliner panel on the top-right of Blender to confirm the Cube exists.",
                    fallbackHint = "If the cube did not appear, verify you are in Object Mode and try Shift+A > Mesh > Cube again.",
                    reflectionQuestion = "How do you verify whether an object was successfully added to the current scene?",
                    targetRegion = HighlightRegion(x = 420f, y = 280f, width = 300f, height = 240f)
                )
            )
        )
    }

    private fun planMakeCubeAndMove(goalText: String): UserGoalPlan {
        return UserGoalPlan(
            goalText = goalText,
            application = ApplicationType.BLENDER,
            steps = listOf(
                UserGoalPlanStep(
                    stepIndex = 1,
                    title = "Ensure Object Mode",
                    instruction = "Switch to Object Mode in the 3D Viewport header.",
                    description = "Mesh primitives must be created in Object Mode.",
                    targetControlName = "Mode",
                    conceptualHint = "Press Tab or use the top-left dropdown in the 3D Viewport to enter Object Mode.",
                    fallbackHint = "Verify that the mode dropdown displays 'Object Mode'."
                ),
                UserGoalPlanStep(
                    stepIndex = 2,
                    title = "Add Mesh Cube",
                    instruction = "Press Shift+A or click Add > Mesh > Cube.",
                    description = "Add a new Cube mesh to the scene at the 3D Cursor.",
                    targetControlName = "Cube",
                    conceptualHint = "Use Shift+A to open the Add menu in the 3D Viewport.",
                    fallbackHint = "Navigate to Add > Mesh > Cube."
                ),
                UserGoalPlanStep(
                    stepIndex = 3,
                    title = "Verify Cube Exists",
                    instruction = "Confirm that the Cube exists in the scene.",
                    description = "Verify the newly created Cube object appears in the scene.",
                    targetControlName = "Cube",
                    expectedState = ExpectedState(
                        operation = VerificationOperation.CREATE,
                        targetName = "Cube",
                        targetType = "MESH"
                    ),
                    conceptualHint = "Check the Outliner to confirm the Cube exists.",
                    fallbackHint = "If the Cube is not in the Outliner, try Shift+A > Mesh > Cube again."
                ),
                UserGoalPlanStep(
                    stepIndex = 4,
                    title = "Move Cube Up",
                    instruction = "Press G, then Z to constrain to the Z-axis, move the mouse upward, and left-click to confirm.",
                    description = "Translate the Cube upward along the vertical Z-axis.",
                    targetControlName = "3D Viewport",
                    conceptualHint = "Press G followed by Z to translate along the vertical axis.",
                    fallbackHint = "Alternatively, open the Transform panel (press N) and increase the Z Location value.",
                    reflectionQuestion = "Which key constraint moves objects along the vertical axis in Blender?"
                )
            )
        )
    }

    private fun planDeleteObject(goalText: String, normalized: String): UserGoalPlan {
        val targetName = extractTargetObjectName(normalized) ?: "Cube"
        return UserGoalPlan(
            goalText = goalText,
            application = ApplicationType.BLENDER,
            steps = listOf(
                UserGoalPlanStep(
                    stepIndex = 1,
                    title = "Select Object to Delete",
                    instruction = "Left-click '$targetName' in the 3D Viewport or click it in the Outliner.",
                    description = "Select the object you want to remove.",
                    targetControlName = targetName,
                    conceptualHint = "Click the object in the Outliner on the right to select it reliably.",
                    fallbackHint = "Ensure '$targetName' has an orange outline indicating it is selected.",
                    reflectionQuestion = "How do you select an object before deleting it?"
                ),
                UserGoalPlanStep(
                    stepIndex = 2,
                    title = "Delete Object",
                    instruction = "Press X or Delete, then click Delete to confirm.",
                    description = "Remove '$targetName' from the scene.",
                    targetControlName = "Delete",
                    expectedState = ExpectedState(
                        operation = VerificationOperation.DELETE,
                        targetName = targetName
                    ),
                    conceptualHint = "Press 'X' or 'Delete' key on your keyboard.",
                    fallbackHint = "Press X and click 'Delete' in the confirmation popup.",
                    reflectionQuestion = "Which keyboard shortcut deletes the selected object in Blender?"
                )
            )
        )
    }

    private fun planRenameObject(goalText: String, normalized: String): UserGoalPlan {
        val (oldName, newName) = extractRenameNames(goalText, normalized)
        return UserGoalPlan(
            goalText = goalText,
            application = ApplicationType.BLENDER,
            steps = listOf(
                UserGoalPlanStep(
                    stepIndex = 1,
                    title = "Select Object to Rename",
                    instruction = "Select '$oldName' in the Outliner or 3D Viewport.",
                    description = "Locate and activate the object you want to rename.",
                    targetControlName = oldName,
                    conceptualHint = "Click on '$oldName' in the Outliner on the right.",
                    fallbackHint = "Find '$oldName' in the Outliner hierarchy.",
                    reflectionQuestion = "Where can you see the names of all objects in Blender?"
                ),
                UserGoalPlanStep(
                    stepIndex = 2,
                    title = "Rename to $newName",
                    instruction = "Double-click '$oldName' in the Outliner (or press F2), type '$newName', and press Enter.",
                    description = "Rename the object to '$newName'.",
                    targetControlName = "Outliner",
                    expectedState = ExpectedState(
                        operation = VerificationOperation.RENAME,
                        targetName = newName
                    ),
                    conceptualHint = "Press F2 while an object is selected to rename it.",
                    fallbackHint = "Double-click the object name directly in the Outliner list.",
                    reflectionQuestion = "Which shortcut opens the Rename popup for the active object?"
                )
            )
        )
    }

    private fun planSelectObject(goalText: String, normalized: String): UserGoalPlan {
        val targetName = extractTargetObjectName(normalized) ?: "Cube"
        return UserGoalPlan(
            goalText = goalText,
            application = ApplicationType.BLENDER,
            steps = listOf(
                UserGoalPlanStep(
                    stepIndex = 1,
                    title = "Locate $targetName",
                    instruction = "Find '$targetName' in the 3D Viewport or the Outliner panel.",
                    description = "Locate '$targetName' in your scene workspace.",
                    targetControlName = targetName,
                    conceptualHint = "Use the Outliner on the top right to locate objects by name.",
                    fallbackHint = "Look at the list of objects in the scene Outliner.",
                    reflectionQuestion = "Which panel lists all objects in the Blender scene?"
                ),
                UserGoalPlanStep(
                    stepIndex = 2,
                    title = "Select $targetName",
                    instruction = "Left-click on '$targetName' to select it.",
                    description = "Ensure '$targetName' is active and selected.",
                    targetControlName = targetName,
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
        )
    }

    private fun planMoveOrChangeLocation(goalText: String, normalized: String): UserGoalPlan {
        val targetName = extractTargetObjectName(normalized) ?: "Cube"
        val explicitLocation = extractExplicitLocation(goalText)

        return if (explicitLocation != null) {
            UserGoalPlan(
                goalText = goalText,
                application = ApplicationType.BLENDER,
                steps = listOf(
                    UserGoalPlanStep(
                        stepIndex = 1,
                        title = "Select $targetName",
                        instruction = "Select '$targetName' in the 3D Viewport or Outliner.",
                        description = "Ensure '$targetName' is selected before changing its location.",
                        targetControlName = targetName,
                        conceptualHint = "Click '$targetName' in the Outliner on the right.",
                        fallbackHint = "Verify '$targetName' has an active selection outline."
                    ),
                    UserGoalPlanStep(
                        stepIndex = 2,
                        title = "Set Location to $explicitLocation",
                        instruction = "In the Transform panel (press N if hidden), set Location to $explicitLocation.",
                        description = "Enter coordinates $explicitLocation in the Item > Transform sidebar.",
                        targetControlName = "Location",
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
                steps = listOf(
                    UserGoalPlanStep(
                        stepIndex = 1,
                        title = "Select $targetName",
                        instruction = "Select '$targetName' in the 3D Viewport.",
                        description = "Click to select '$targetName'.",
                        targetControlName = targetName,
                        conceptualHint = "Left-click the object in the 3D Viewport or Outliner.",
                        fallbackHint = "Verify '$targetName' is highlighted in orange.",
                        reflectionQuestion = "How do you select an object to prepare it for transformation?"
                    ),
                    UserGoalPlanStep(
                        stepIndex = 2,
                        title = "Move $targetName",
                        instruction = "Press G to grab and move '$targetName', then left-click to place it.",
                        description = "Reposition '$targetName' in the 3D space.",
                        targetControlName = "3D Viewport",
                        conceptualHint = "Press G to enter Grab/Move mode, move the mouse, then left-click.",
                        fallbackHint = "Use G followed by X, Y, or Z to constrain movement along an axis.",
                        reflectionQuestion = "Which key starts the Move (Grab) operator in Blender?"
                    )
                )
            )
        }
    }

    private fun planGeneralFallback(goalText: String): UserGoalPlan {
        return UserGoalPlan(
            goalText = goalText,
            application = ApplicationType.BLENDER,
            steps = listOf(
                UserGoalPlanStep(
                    stepIndex = 1,
                    title = "Locate Tool in Blender",
                    instruction = "Locate the interface control or menu for '$goalText'.",
                    description = "Find the relevant command in Blender's header menus or toolbar.",
                    targetControlName = "Blender Interface",
                    conceptualHint = "Press F3 in Blender to open the operator search menu and type your command.",
                    fallbackHint = "Use the F3 Operator Search dialog if you cannot find the button directly."
                ),
                UserGoalPlanStep(
                    stepIndex = 2,
                    title = "Execute Action",
                    instruction = "Perform the action for '$goalText' in Blender.",
                    description = "Apply the desired operation in the 3D Viewport.",
                    targetControlName = "3D Viewport",
                    conceptualHint = "Follow the tooltip prompts in Blender to complete the operation.",
                    fallbackHint = "Press Ctrl+Z if you make a mistake and want to retry."
                ),
                UserGoalPlanStep(
                    stepIndex = 3,
                    title = "Verify Result",
                    instruction = "Review the scene changes in the 3D Viewport and Outliner.",
                    description = "Confirm that '$goalText' was accomplished successfully.",
                    targetControlName = "3D Viewport",
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
                // Try to find a named object like "delete Foo" or "select Bar"
                val match = Regex("""(?:delete|remove|select|move)\s+(?:the\s+)?([a-zA-Z0-9_-]+)""").find(normalized)
                val candidate = match?.groupValues?.get(1)?.trim()
                if (candidate != null && candidate !in setOf("object", "the", "mesh", "it", "selected")) {
                    candidate.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                } else null
            }
        }
    }

    private fun extractRenameNames(original: String, normalized: String): Pair<String, String> {
        // e.g. "rename Cube to MyCube" or "rename object to Player"
        val regexWithTo = Regex("""rename\s+(?:the\s+)?([a-zA-Z0-9_-]+)\s+to\s+([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE)
        val match = regexWithTo.find(original)
        if (match != null) {
            val oldRaw = match.groupValues[1].trim()
            val newRaw = match.groupValues[2].trim()
            val oldName = if (oldRaw.equals("object", ignoreCase = true)) "Cube" else oldRaw
            return Pair(oldName, newRaw)
        }

        // e.g. "rename to MyMesh"
        val regexToOnly = Regex("""rename\s+to\s+([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE)
        val matchTo = regexToOnly.find(original)
        if (matchTo != null) {
            return Pair("Cube", matchTo.groupValues[1].trim())
        }

        val target = extractTargetObjectName(normalized) ?: "Cube"
        return Pair(target, "RenamedObject")
    }

    private fun extractExplicitLocation(text: String): String? {
        // e.g. "0.0, 0.0, 2.0" or "(0, 0, 2)" or "0, 0, 2" or "to 0.0, 0.0, 2.0"
        val coordRegex = Regex("""(?:to\s+)?\(?\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\)?""")
        val match = coordRegex.find(text)
        if (match != null) {
            val (x, y, z) = match.destructured
            return "$x, $y, $z"
        }
        return null
    }
}
