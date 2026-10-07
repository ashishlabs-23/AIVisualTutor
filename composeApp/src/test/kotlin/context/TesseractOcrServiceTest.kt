package context

import java.awt.Rectangle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TesseractOcrServiceTest {
    private val service = TesseractOcrService()

    @Test fun realOcrReturnsSpatialTextEvidenceAndTiming() = runBlocking {
        val fixture = OcrFixtures.textOnly
        val result = service.recognize(fixture.image)
        val record = OcrGroundingRecord(
            fixture.imageId, fixture.expectedText, fixture.expectedBounds,
            result.words.map { it.bounds }.reduceOrNull(Rectangle::union), result.confidence,
            result.metadata["totalLatencyMillis"]?.toLongOrNull(), result.coordinateSpace
        )
        assertTrue(result.text.contains("Visible", ignoreCase = true), "real OCR should recognize fixture text: $record")
        assertTrue(result.words.isNotEmpty())
        assertTrue(result.words.all { it.bounds.width > 0 && it.bounds.height > 0 })
        assertEquals(OcrCoordinateSpace.CROP_IMAGE_PIXELS, result.coordinateSpace)
        assertNotNull(result.metadata["totalLatencyMillis"])
        assertNotNull(record.actualBounds)
        assertNotNull(record.processingLatencyMillis)
        assertEquals("tesseract", result.metadata["providerId"])
    }

    @Test fun buttonAndDenseUiFixturesProduceStructuredWordEvidence() = runBlocking {
        listOf(OcrFixtures.buttonLike, OcrFixtures.denseUi).forEach { fixture ->
            val result = service.recognize(fixture.image)
            assertTrue(result.words.isNotEmpty(), "${fixture.imageId}: ${result.metadata}")
            assertTrue(result.words.all { it.bounds.x >= 0 && it.bounds.y >= 0 && it.bounds.x + it.bounds.width <= fixture.image.width && it.bounds.y + it.bounds.height <= fixture.image.height })
            assertTrue(result.words.any { it.bounds.intersects(fixture.expectedBounds) }, "${fixture.imageId}: OCR boxes missed expected region")
        }
    }

    @Test fun blankFixtureReturnsExplicitEmptyEvidence() = runBlocking {
        val result = service.recognize(OcrFixtures.emptyNonText.image)
        assertTrue(result.text.isEmpty(), "unexpected OCR text in non-text fixture: ${result.text}")
        assertTrue(result.words.isEmpty())
        assertEquals("empty", result.metadata["status"])
    }

    @Test fun iconOnlyFixtureShowsAnOcrFalsePositiveRatherThanIconSemantics() = runBlocking {
        val result = service.recognize(OcrFixtures.iconOnly.image)
        assertEquals("*", result.text)
        assertTrue(result.words.single().bounds.intersects(OcrFixtures.iconOnly.expectedBounds))
        assertEquals("recognized", result.metadata["status"])
    }

    @Test fun neighboringTextFixtureRetainsBothPlausibleWords() = runBlocking {
        val result = service.recognize(OcrFixtures.neighboringText.image)
        assertTrue(result.text.contains("Save", ignoreCase = true), result.text)
        assertTrue(result.words.count { it.text.equals("Save", ignoreCase = true) } >= 1, result.words.toString())
        assertTrue(result.words.any { it.text.equals("As", ignoreCase = true) }, result.words.toString())
        assertTrue(result.words.first { it.text.equals("Save", ignoreCase = true) }.bounds.intersects(OcrFixtures.neighboringText.expectedBounds))
        assertTrue(result.words.all { it.bounds.intersects(Rectangle(0, 0, OcrFixtures.neighboringText.image.width, OcrFixtures.neighboringText.image.height)) })
    }

    @Test fun missingLanguageModelDegradesToExplicitEmptyResult() = runBlocking {
        val result = TesseractOcrService(listOf("aivt_missing_model")).recognize(OcrFixtures.textOnly.image)
        assertTrue(result.text.isEmpty())
        assertEquals("language_unavailable", result.metadata["status"])
        assertEquals("unavailable", result.metadata["availability"])
    }

    @Test fun cancellationPropagates() = runBlocking {
        val cancelled = Job().apply { cancel(CancellationException("test cancellation")) }
        assertFailsWith<CancellationException> {
            withContext(cancelled + Dispatchers.Default) { service.recognize(OcrFixtures.textOnly.image) }
        }
        Unit
    }
}
