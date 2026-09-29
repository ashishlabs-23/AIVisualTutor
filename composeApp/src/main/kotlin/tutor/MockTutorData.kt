package tutor

import models.ApplicationType
import models.HighlightRegion
import models.TutorStep

/**
 * Hard-coded tutorial workflows for each supported application.
 *
 * These are placeholders for Phase 1 only. The instructions and the
 * [HighlightRegion] coordinates are made up - nothing here comes from
 * observing a real application window. A later phase will replace this
 * object with steps generated from real screen understanding.
 */
object MockTutorData {

    private val blenderSteps = listOf(
        TutorStep(
            id = 1,
            title = "Add Menu",
            instruction = "Open the Add menu.",
            description = "The Add menu is in the top toolbar of the 3D viewport. It lets you create new objects.",
            stepNumber = 1,
            totalSteps = 5,
            targetRegion = HighlightRegion(x = 220f, y = 90f, width = 90f, height = 30f)
        ),
        TutorStep(
            id = 2,
            title = "Mesh Category",
            instruction = "Select Mesh.",
            description = "Mesh objects are the basic 3D shapes Blender can build on, like cubes and spheres.",
            stepNumber = 2,
            totalSteps = 5,
            targetRegion = HighlightRegion(x = 260f, y = 130f, width = 110f, height = 28f)
        ),
        TutorStep(
            id = 3,
            title = "Cube",
            instruction = "Select Cube.",
            description = "This adds a default cube to the scene at the 3D cursor's location.",
            stepNumber = 3,
            totalSteps = 5,
            targetRegion = HighlightRegion(x = 320f, y = 160f, width = 80f, height = 28f)
        ),
        TutorStep(
            id = 4,
            title = "Move Into Viewport",
            instruction = "Move to the viewport.",
            description = "Move your mouse into the 3D viewport so the new cube is placed where you want it.",
            stepNumber = 4,
            totalSteps = 5,
            targetRegion = HighlightRegion(x = 500f, y = 300f, width = 260f, height = 200f)
        ),
        TutorStep(
            id = 5,
            title = "Confirm",
            instruction = "Confirm the object.",
            description = "Left-click to confirm the cube's placement in the scene.",
            stepNumber = 5,
            totalSteps = 5,
            targetRegion = HighlightRegion(x = 600f, y = 380f, width = 60f, height = 60f)
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
            targetRegion = HighlightRegion(x = 120f, y = 160f, width = 400f, height = 40f)
        ),
        TutorStep(
            id = 2,
            title = "Read Text",
            instruction = "Read the highlighted text.",
            description = "This paragraph contains the key information for this step.",
            stepNumber = 2,
            totalSteps = 4,
            targetRegion = HighlightRegion(x = 120f, y = 220f, width = 400f, height = 90f)
        ),
        TutorStep(
            id = 3,
            title = "Find Table",
            instruction = "Locate the relevant table.",
            description = "Tables often summarize the data referenced in the surrounding text.",
            stepNumber = 3,
            totalSteps = 4,
            targetRegion = HighlightRegion(x = 120f, y = 340f, width = 400f, height = 160f)
        ),
        TutorStep(
            id = 4,
            title = "Next Page",
            instruction = "Move to the next page.",
            description = "Use the page-down control or scroll to continue reading.",
            stepNumber = 4,
            totalSteps = 4,
            targetRegion = HighlightRegion(x = 540f, y = 700f, width = 60f, height = 40f)
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
            targetRegion = HighlightRegion(x = 180f, y = 140f, width = 70f, height = 24f)
        ),
        TutorStep(
            id = 2,
            title = "Enter Value",
            instruction = "Enter a value.",
            description = "Type a number or formula into the selected cell.",
            stepNumber = 2,
            totalSteps = 4,
            targetRegion = HighlightRegion(x = 180f, y = 140f, width = 70f, height = 24f)
        ),
        TutorStep(
            id = 3,
            title = "Press Enter",
            instruction = "Press Enter.",
            description = "Confirm the value you typed and move to the next cell.",
            stepNumber = 3,
            totalSteps = 4,
            targetRegion = HighlightRegion(x = 180f, y = 164f, width = 70f, height = 24f)
        ),
        TutorStep(
            id = 4,
            title = "Select Result",
            instruction = "Select the resulting cell.",
            description = "Click the cell that now contains the result to verify it.",
            stepNumber = 4,
            totalSteps = 4,
            targetRegion = HighlightRegion(x = 180f, y = 164f, width = 70f, height = 24f)
        )
    )

    /** Returns the mock step list for the given application. */
    fun stepsFor(application: ApplicationType): List<TutorStep> = when (application) {
        ApplicationType.BLENDER -> blenderSteps
        ApplicationType.PDF -> pdfSteps
        ApplicationType.EXCEL -> excelSteps
    }
}
