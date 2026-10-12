package tutor

import context.ExpectedState
import context.VerificationOperation
import models.ApplicationType
import models.HighlightRegion
import models.TutorStep

/**
 * Tutorial workflows for each supported application enriched with
 * multi-stage pedagogical metadata and expected state specifications.
 */
object MockTutorData {

    private val blenderSteps = listOf(
        TutorStep(
            id = 1,
            title = "Open Add Menu",
            instruction = "Open the Add menu.",
            description = "The Add menu is in the top toolbar of the 3D viewport. It lets you create new objects.",
            stepNumber = 1,
            totalSteps = 5,
            targetRegion = HighlightRegion(x = 160f, y = 78f, width = 60f, height = 28f),
            taskId = "blender_add_menu",
            skillId = "blender.primitives",
            conceptualHint = "Access the creation menu in the viewport header to see available object types.",
            reflectionQuestion = "Where in the 3D Viewport header can you find the menu to add new scene objects?",
            targetControlName = "Add",
            targetAreaDescription = "top toolbar of the 3D viewport"
        ),
        TutorStep(
            id = 2,
            title = "Click on UV Sphere",
            instruction = "Click on UV Sphere.",
            description = "This will add a sphere to your scene at the current cursor location.",
            stepNumber = 2,
            totalSteps = 5,
            targetRegion = HighlightRegion(x = 310f, y = 195f, width = 125f, height = 26f),
            taskId = "blender_sphere",
            skillId = "blender.primitives",
            conceptualHint = "Select the UV Sphere primitive from the Mesh submenu.",
            reflectionQuestion = "Which primitive command creates a spherical polygonal mesh in the scene?",
            targetControlName = "UV Sphere",
            targetAreaDescription = "Mesh submenu dropdown",
            expectedState = ExpectedState(
                operation = VerificationOperation.CREATE,
                targetName = "Sphere",
                targetType = "MESH"
            )
        ),
        TutorStep(
            id = 3,
            title = "Adjust radius (optional)",
            instruction = "Adjust radius if desired.",
            description = "Use the operator popup panel in the bottom-left to adjust the sphere's radius and segments.",
            stepNumber = 3,
            totalSteps = 5,
            targetRegion = HighlightRegion(x = 20f, y = 820f, width = 180f, height = 80f),
            taskId = "blender_cube",
            skillId = "blender.primitives",
            conceptualHint = "Configure primitive parameters in the operator adjustment box.",
            reflectionQuestion = "Where in the Blender interface can you adjust parameters immediately after creating a primitive?",
            targetControlName = "Radius",
            targetAreaDescription = "bottom-left operator popup",
            expectedState = ExpectedState(
                operation = VerificationOperation.CREATE,
                targetName = "Cube",
                targetType = "MESH"
            )
        ),
        TutorStep(
            id = 4,
            title = "Move the sphere",
            instruction = "Move to the viewport.",
            description = "Press G or move your mouse in the 3D viewport so the sphere is placed where you want it.",
            stepNumber = 4,
            totalSteps = 5,
            targetRegion = HighlightRegion(x = 420f, y = 280f, width = 300f, height = 240f),
            taskId = "blender_viewport",
            skillId = "blender.navigation",
            conceptualHint = "Direct your mouse pointer into the primary 3D modeling area.",
            reflectionQuestion = "How do you navigate focus back to the main spatial workspace?",
            targetControlName = "3D Viewport",
            targetAreaDescription = "main 3D workspace"
        ),
        TutorStep(
            id = 5,
            title = "Done!",
            instruction = "Confirm the object.",
            description = "Left-click to confirm the sphere placement in the scene. You're all set!",
            stepNumber = 5,
            totalSteps = 5,
            targetRegion = HighlightRegion(x = 600f, y = 380f, width = 60f, height = 60f),
            taskId = "blender_confirm",
            skillId = "blender.primitives",
            conceptualHint = "Finalize the active placement interaction in the viewport.",
            reflectionQuestion = "What mouse action commits the current object placement in the viewport?",
            targetControlName = "Left Click",
            targetAreaDescription = "3D Viewport"
        )
    )

    private val pdfSteps = listOf(
        TutorStep(
            id = 1,
            title = "Find Section",
            instruction = "Locate the target section.",
            description = "Use the table of contents or scroll to find the section you need.",
            stepNumber = 1,
            totalSteps = 4,
            targetRegion = HighlightRegion(x = 120f, y = 160f, width = 400f, height = 40f),
            taskId = "pdf_section",
            skillId = "pdf.navigation",
            conceptualHint = "Navigate to the desired chapter or section using outline or scrolling.",
            reflectionQuestion = "Which navigation feature helps you quickly jump to a document section?",
            targetControlName = "Table of Contents",
            targetAreaDescription = "sidebar outline"
        ),
        TutorStep(
            id = 2,
            title = "Read Text",
            instruction = "Read the highlighted text.",
            description = "This paragraph contains the key information for this step.",
            stepNumber = 2,
            totalSteps = 4,
            targetRegion = HighlightRegion(x = 120f, y = 220f, width = 400f, height = 90f),
            taskId = "pdf_read",
            skillId = "pdf.reading",
            conceptualHint = "Review the highlighted summary paragraph for key findings.",
            reflectionQuestion = "What key statement in the highlighted paragraph addresses the requirement?",
            targetAreaDescription = "document text body"
        ),
        TutorStep(
            id = 3,
            title = "Find Table",
            instruction = "Locate the relevant table.",
            description = "Tables often summarize the data referenced in the surrounding text.",
            stepNumber = 3,
            totalSteps = 4,
            targetRegion = HighlightRegion(x = 120f, y = 340f, width = 400f, height = 160f),
            taskId = "pdf_table",
            skillId = "pdf.reading",
            conceptualHint = "Scan the page for tabular data supporting the text description.",
            reflectionQuestion = "Where is the structured tabular comparison located on this page?",
            targetAreaDescription = "data table section"
        ),
        TutorStep(
            id = 4,
            title = "Next Page",
            instruction = "Move to the next page.",
            description = "Use the page-down control or scroll to continue reading.",
            stepNumber = 4,
            totalSteps = 4,
            targetRegion = HighlightRegion(x = 540f, y = 700f, width = 60f, height = 40f),
            taskId = "pdf_next_page",
            skillId = "pdf.navigation",
            conceptualHint = "Advance the viewer to the subsequent page.",
            reflectionQuestion = "Which button or shortcut advances to the following document page?",
            targetControlName = "Next Page",
            targetAreaDescription = "bottom pagination toolbar"
        )
    )

    private val excelSteps = listOf(
        TutorStep(
            id = 1,
            title = "Select Cell",
            instruction = "Select cell B2.",
            description = "Click on cell B2 in the spreadsheet grid to select it.",
            stepNumber = 1,
            totalSteps = 4,
            targetRegion = HighlightRegion(x = 180f, y = 140f, width = 70f, height = 24f),
            taskId = "excel_select_b2",
            skillId = "excel.selection",
            conceptualHint = "Activate the cell located at column B, row 2.",
            reflectionQuestion = "How do you identify and activate cell coordinate B2 in the spreadsheet grid?",
            targetControlName = "B2",
            targetAreaDescription = "spreadsheet grid cell B2"
        ),
        TutorStep(
            id = 2,
            title = "Enter Value",
            instruction = "Enter a value.",
            description = "Type a number or formula into the selected cell.",
            stepNumber = 2,
            totalSteps = 4,
            targetRegion = HighlightRegion(x = 180f, y = 140f, width = 70f, height = 24f),
            taskId = "excel_enter_value",
            skillId = "excel.entry",
            conceptualHint = "Input data or an expression into the active cell.",
            reflectionQuestion = "What keyboard action allows entering data directly into the active cell?",
            targetAreaDescription = "active cell / formula bar"
        ),
        TutorStep(
            id = 3,
            title = "Press Enter",
            instruction = "Press Enter.",
            description = "Confirm the value you typed and move to the next cell.",
            stepNumber = 3,
            totalSteps = 4,
            targetRegion = HighlightRegion(x = 180f, y = 164f, width = 70f, height = 24f),
            taskId = "excel_press_enter",
            skillId = "excel.entry",
            conceptualHint = "Commit the typed cell input and step downward.",
            reflectionQuestion = "Which key commits cell editing and shifts selection to the row below?",
            targetControlName = "Enter Key"
        ),
        TutorStep(
            id = 4,
            title = "Select Result",
            instruction = "Select the resulting cell.",
            description = "Click the cell that now contains the result to verify it.",
            stepNumber = 4,
            totalSteps = 4,
            targetRegion = HighlightRegion(x = 180f, y = 164f, width = 70f, height = 24f),
            taskId = "excel_select_result",
            skillId = "excel.selection",
            conceptualHint = "Verify the computed output by focusing on the target result cell.",
            reflectionQuestion = "How do you verify the calculated value versus the formula in the formula bar?",
            targetAreaDescription = "result cell"
        )
    )

    /** Returns the step list for the given application. */
    fun stepsFor(application: ApplicationType): List<TutorStep> = when (application) {
        ApplicationType.BLENDER -> blenderSteps
        ApplicationType.PDF -> pdfSteps
        ApplicationType.EXCEL -> excelSteps
    }
}
