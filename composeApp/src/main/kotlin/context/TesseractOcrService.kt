package context

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.sourceforge.tess4j.ITessAPI
import net.sourceforge.tess4j.TessAPI
import net.sourceforge.tess4j.Tesseract

/** Local Tesseract OCR over an already captured crop; all word bounds remain crop-image pixels. */
class TesseractOcrService(
    languages: List<String> = configuredLanguages(),
    private val tessdataRoot: Path? = configuredTessdataRoot()
) : OCRService {
    private val languages = languages.map(String::trim).filter(String::isNotEmpty).distinct()

    override suspend fun recognize(image: BufferedImage): OcrResult = withContext(Dispatchers.Default) {
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        val totalStarted = System.nanoTime()
        var initializationMillis = 0L
        var tesseractVersion: String? = null
        try {
            require(image.width > 0 && image.height > 0) { "empty_image" }
            require(languages.isNotEmpty()) { "no_ocr_language_configured" }

            val initializeStarted = System.nanoTime()
            val dataRoot = tessdataRoot ?: EmbeddedTessdata.rootFor(languages)
            tesseractVersion = TessAPI.INSTANCE.TessVersion().toString()
            initializationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - initializeStarted)

            coroutineContext.ensureActive()
            val engineStarted = System.nanoTime()
            val engine = Tesseract().apply {
                // The Tess4J wrapper passes datapath directly to TesseractInit1;
                // that native API expects the directory containing *.traineddata.
                setDatapath(dataRoot.resolve("tessdata").toString())
                setLanguage(languages.joinToString("+"))
            }
            val recognizedWords = engine.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_WORD)
            val recognitionMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - engineStarted)
            coroutineContext.ensureActive()

            val words = recognizedWords.mapIndexedNotNull { index, word ->
                val text = word.text?.trim().orEmpty()
                val box = word.boundingBox
                if (text.isEmpty() || box == null || box.width <= 0 || box.height <= 0) null
                else OcrWord(
                    text = text,
                    confidence = word.confidence.takeIf { it.isFinite() && it in 0f..100f }?.div(100f),
                    bounds = Rectangle(box),
                    language = languages.joinToString("+"),
                    wordIndex = index
                )
            }
            val totalMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStarted)
            val metadata = timingMetadata(initializationMillis, recognitionMillis, totalMillis) + mapOf(
                "providerId" to PROVIDER_ID,
                "capability" to "TEXT_WITH_WORD_BOXES",
                "evidenceType" to "OCR_TEXT",
                "availability" to "available",
                "status" to if (words.isEmpty()) "empty" else "recognized"
            ) + if (System.getProperty(CalibrationResultStore.ENABLE_PROPERTY)?.equals("true", ignoreCase = true) == true) {
                mapOf("tesseractVersion" to tesseractVersion.orEmpty())
            } else {
                emptyMap()
            }
            OcrResult(
                text = words.joinToString(" ") { it.text },
                confidence = words.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                words = words,
                engineName = PROVIDER_ID,
                language = languages.joinToString("+"),
                coordinateSpace = OcrCoordinateSpace.CROP_IMAGE_PIXELS,
                metadata = metadata
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedResult(
                language = languages.joinToString("+"),
                status = statusFor(e),
                diagnostic = e.message?.take(100) ?: e.javaClass.simpleName,
                initializationMillis = initializationMillis,
                totalMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStarted)
            )
        } catch (e: LinkageError) {
            failedResult(
                language = languages.joinToString("+"),
                status = "native_runtime_unavailable",
                diagnostic = e.javaClass.simpleName,
                initializationMillis = initializationMillis,
                totalMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStarted)
            )
        }
    }

    private fun statusFor(error: Exception): String = when {
        error.message == "empty_image" -> "empty_image"
        error.message == "no_ocr_language_configured" -> "language_unavailable"
        error.message?.contains("language", ignoreCase = true) == true -> "language_unavailable"
        error.message?.contains("traineddata", ignoreCase = true) == true -> "language_unavailable"
        else -> "recognition_failed"
    }

    private fun failedResult(
        language: String,
        status: String,
        diagnostic: String,
        initializationMillis: Long,
        totalMillis: Long
    ) = OcrResult(
        text = "",
        confidence = null,
        words = emptyList(),
        engineName = PROVIDER_ID,
        language = language.ifBlank { null },
        coordinateSpace = OcrCoordinateSpace.CROP_IMAGE_PIXELS,
        metadata = timingMetadata(initializationMillis, null, totalMillis) + mapOf(
            "providerId" to PROVIDER_ID,
            "capability" to "TEXT_WITH_WORD_BOXES",
            "evidenceType" to "OCR_TEXT",
            "availability" to if (status == "language_unavailable" || status == "native_runtime_unavailable") "unavailable" else "available",
            "status" to status,
            "diagnostic" to diagnostic.replace(Regex("[\\r\\n|]"), " ").take(100)
        )
    )

    private fun timingMetadata(initializationMillis: Long, recognitionMillis: Long?, totalMillis: Long) = buildMap {
        put("initializationMillis", initializationMillis.toString())
        recognitionMillis?.let { put("recognitionMillis", it.toString()) }
        put("totalLatencyMillis", totalMillis.toString())
    }

    companion object {
        const val PROVIDER_ID = "tesseract"

        private fun configuredLanguages(): List<String> = System.getProperty("aivt.ocr.languages", "eng")
            .split('+', ',').map(String::trim).filter(String::isNotEmpty)

        private fun configuredTessdataRoot(): Path? = System.getProperty("aivt.ocr.tessdata")
            ?.takeIf(String::isNotBlank)?.let(Paths::get)
    }
}

private object EmbeddedTessdata {
    private val lock = Any()
    @Volatile private var cachedRoot: Path? = null

    fun rootFor(languages: List<String>): Path {
        val root = cachedRoot ?: synchronized(lock) {
            cachedRoot ?: extractBundledEnglish().also { cachedRoot = it }
        }
        val tessdataDirectory = root.resolve("tessdata")
        val missing = languages.filterNot { Files.isRegularFile(tessdataDirectory.resolve("$it.traineddata")) }
        require(missing.isEmpty()) {
            "Language model(s) ${missing.joinToString(",")} are unavailable; set aivt.ocr.tessdata to a Tesseract data root containing tessdata."
        }
        return root
    }

    private fun extractBundledEnglish(): Path {
        val root = Files.createTempDirectory("aivt-tesseract-")
        root.toFile().deleteOnExit()
        val tessdata = Files.createDirectories(root.resolve("tessdata"))
        tessdata.toFile().deleteOnExit()
        listOf("eng", "osd").forEach { language ->
            val resource = "/tessdata/$language.traineddata"
            val input = TesseractOcrService::class.java.getResourceAsStream(resource) ?: return@forEach
            input.use { source ->
                val model = tessdata.resolve("$language.traineddata")
                Files.copy(source, model)
                model.toFile().deleteOnExit()
            }
        }
        return root
    }
}
