package context

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/** Small deterministic synthetic inputs, not an accuracy benchmark. */
data class OcrFixture(
    val imageId: String,
    val image: BufferedImage,
    val expectedText: String,
    val expectedBounds: Rectangle
)

data class GuiGroundingFixture(
    val imageId: String,
    val application: String,
    val targetDescription: String?,
    val targetDescriptionVariants: List<TargetDescriptionVariant>,
    val expectedRegion: Rectangle,
    val targetType: String,
    val sourceAnnotations: List<String>,
    val coordinateSpace: VisualGroundingCoordinateSpace
)

object OcrFixtures {
    val textOnly = draw("text-only", 900, 150, "Visible text baseline 42", Rectangle(35, 35, 825, 78), false)
    val buttonLike = draw("button-like", 320, 140, "Save", Rectangle(70, 36, 180, 68), true)
    val denseUi = draw("dense-ui", 1100, 320, "File Edit View Project Settings Tools Open Save Cancel", Rectangle(24, 24, 1050, 270), false, dense = true)
    val emptyNonText = OcrFixture("empty-non-text", BufferedImage(480, 220, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().useGraphics { g ->
            g.color = Color(238, 241, 244); g.fillRect(0, 0, width, height)
        }
    }, "", Rectangle(0, 0, 480, 220))
    val iconOnly = iconOnlyFixture()
    val neighboringText = neighboringTextFixture()

    val all = listOf(textOnly, buttonLike, denseUi, emptyNonText, iconOnly, neighboringText)
    val grounding = all.map { fixture ->
        GuiGroundingFixture(
            imageId = fixture.imageId,
            application = "SyntheticFixtureApp",
            targetDescription = fixture.expectedText.takeIf(String::isNotBlank),
            targetDescriptionVariants = descriptionVariants(fixture.imageId, fixture.expectedText),
            expectedRegion = fixture.expectedBounds,
            targetType = when (fixture.imageId) {
                "button-like" -> "button"
                "icon-only" -> "icon"
                "empty-non-text" -> "non-target"
                else -> "text"
            },
            sourceAnnotations = listOf("synthetic", "expected-region"),
            coordinateSpace = VisualGroundingCoordinateSpace.CROP_IMAGE_PIXELS
        )
    }

    private fun draw(id: String, width: Int, height: Int, text: String, expectedBounds: Rectangle, button: Boolean, dense: Boolean = false): OcrFixture {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().useGraphics { g ->
            g.color = Color.WHITE; g.fillRect(0, 0, width, height)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color(35, 39, 45)
            if (button) { g.color = Color(35, 39, 45); g.drawRoundRect(55, 25, 210, 90, 12, 12) }
            if (dense) {
                g.font = Font(Font.SANS_SERIF, Font.PLAIN, 31)
                listOf("File   Edit   View", "Project   Settings   Tools", "Open   Save   Cancel").forEachIndexed { i, line -> g.drawString(line, 30, 75 + i * 76) }
            } else {
                g.font = Font(Font.SANS_SERIF, if (button) Font.BOLD else Font.PLAIN, if (button) 42 else 50)
                g.drawString(text, 40, 97)
            }
        }
        return OcrFixture(id, image, text, expectedBounds)
    }

    private fun iconOnlyFixture(): OcrFixture {
        val image = BufferedImage(240, 180, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().useGraphics { g ->
            g.color = Color.WHITE; g.fillRect(0, 0, image.width, image.height)
            g.color = Color(35, 39, 45)
            val x = intArrayOf(120, 136, 172, 143, 151, 120, 89, 97, 68, 104)
            val y = intArrayOf(34, 85, 85, 114, 153, 130, 153, 114, 85, 85)
            g.fillPolygon(x, y, x.size)
        }
        return OcrFixture("icon-only", image, "", Rectangle(68, 34, 104, 119))
    }

    private fun neighboringTextFixture(): OcrFixture {
        val image = BufferedImage(480, 150, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().useGraphics { g ->
            g.color = Color.WHITE; g.fillRect(0, 0, image.width, image.height)
            g.color = Color(35, 39, 45); g.font = Font(Font.SANS_SERIF, Font.PLAIN, 42)
            g.drawString("Save", 36, 91)
            g.drawString("Save As", 205, 91)
        }
        return OcrFixture("neighboring-text", image, "Save", Rectangle(30, 42, 105, 58))
    }

    private fun descriptionVariants(id: String, text: String): List<TargetDescriptionVariant> = when (id) {
        "button-like" -> listOf("Save", "Save button", "the save control").map(::TargetDescriptionVariant)
        "icon-only" -> listOf("star icon", "the star control", "the icon in the center").map(::TargetDescriptionVariant)
        "neighboring-text" -> listOf("Save", "Save button", "the left Save control").map(::TargetDescriptionVariant)
        "dense-ui" -> listOf("File", "File menu", "the File control").map(::TargetDescriptionVariant)
        "text-only" -> listOf("Visible text baseline 42", "Visible text", "the visible text").map(::TargetDescriptionVariant)
        else -> if (text.isBlank()) emptyList() else listOf(TargetDescriptionVariant(text))
    }
}

private inline fun <T> Graphics2D.useGraphics(block: (Graphics2D) -> T): T = try { block(this) } finally { dispose() }

/** Grounding-ready record shape for later evaluation tooling. */
data class OcrGroundingRecord(
    val imageIdentifier: String,
    val expectedText: String,
    val expectedBounds: Rectangle,
    val actualBounds: Rectangle?,
    val confidence: Float?,
    val processingLatencyMillis: Long?,
    val coordinateSpace: OcrCoordinateSpace
)
