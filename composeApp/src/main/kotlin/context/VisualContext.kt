package context

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.util.UUID

enum class ContentType { TEXT, UI_ELEMENT, FORMULA, DIAGRAM, TABLE, CODE, CHART, IMAGE, MIXED, UNKNOWN }

data class ApplicationContext(
    val applicationName: String,
    val processName: String,
    val windowTitle: String? = null,
    val pid: Long? = null,
    /** Desktop user-space bounds; may have negative origin. */
    val windowBounds: Rectangle? = null
)

data class OcrWord(val text: String, val confidence: Float?, val bounds: Rectangle)
data class OcrResult(val text: String, val confidence: Float?, val words: List<OcrWord> = emptyList(), val engineName: String)
data class ClassificationResult(val contentType: ContentType, val confidence: Float?)

data class VisualContext(
    val regionId: UUID,
    val image: BufferedImage,
    /** Desktop logical/user-space screen origin. Image dimensions below are captured pixel dimensions. */
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val timestampMillis: Long,
    val extractedText: String?,
    val contentType: ContentType?,
    val applicationContext: ApplicationContext?,
    val confidence: Float?,
    val metadata: Map<String, String>
) {
    // Never allow generated data-class output to include OCR, title, or image internals.
    override fun toString() = "VisualContext(regionId=$regionId, x=$x, y=$y, width=$width, height=$height, contentType=$contentType)"
}

fun interface OCRService { suspend fun recognize(image: BufferedImage): OcrResult }
class PlaceholderOcrService : OCRService {
    override suspend fun recognize(image: BufferedImage) = OcrResult("", null, engineName = "placeholder")
}

fun interface ContentClassifier { suspend fun classify(image: BufferedImage, ocr: OcrResult?): ClassificationResult }
class PlaceholderContentClassifier : ContentClassifier {
    override suspend fun classify(image: BufferedImage, ocr: OcrResult?) = ClassificationResult(
        if (ocr?.text?.isNotBlank() == true && ocr.text.length > 80) ContentType.TEXT else ContentType.UNKNOWN,
        null
    )
}
