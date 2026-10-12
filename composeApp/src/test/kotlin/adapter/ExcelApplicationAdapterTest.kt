package adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ExcelApplicationAdapterTest {

    @Test
    fun `declares expected capabilities`() {
        val adapter = ExcelApplicationAdapter()
        assertTrue(adapter.supports(AdapterCapability.DOCUMENT_STRUCTURE))
        assertTrue(adapter.supports(AdapterCapability.SELECTION_STATE))
        assertTrue(adapter.supports(AdapterCapability.CELL_FORMULAS))
        assertFalse(adapter.supports(AdapterCapability.OBJECT_STATE))
    }

    @Test
    fun `parses successful Excel COM output into strongly typed observation`() {
        val adapter = ExcelApplicationAdapter()
        val mockOutput = "OK\tBudget.xlsx\tQ3_Summary\t\$B\$5\t=SUM(B2:B4)\t1450.00\tQ1|Q2|Q3_Summary|Annual"

        val obs = adapter.parseScriptOutput(mockOutput)

        assertTrue(obs.isRunning)
        assertEquals("Budget.xlsx", obs.workbookName)
        assertEquals("Q3_Summary", obs.activeSheetName)
        assertEquals("\$B\$5", obs.activeCellAddress)
        assertEquals("=SUM(B2:B4)", obs.activeCellFormula)
        assertEquals("1450.00", obs.activeCellValue)
        assertEquals(listOf("Q1", "Q2", "Q3_Summary", "Annual"), obs.worksheetNames)
    }

    @Test
    fun `takeSnapshot converts valid observation into AdapterSnapshot`() = runBlocking {
        val mockOutput = "OK\tGrades.xlsx\tFinal\t\$C\$10\t=AVERAGE(C2:C9)\t88.5\tMidterm|Final"
        val adapter = ExcelApplicationAdapter(scriptRunner = { mockOutput })

        val result = adapter.takeSnapshot()

        assertTrue(result is AdapterSnapshotResult.Success)
        val snapshot = result.snapshot
        assertEquals("Excel", snapshot.applicationName)
        assertEquals("Final", snapshot.scene.name)
        assertEquals("\$C\$10", snapshot.activeObject?.name)
        assertEquals("=AVERAGE(C2:C9)", snapshot.activeObject?.properties?.get("formula"))
        assertEquals("88.5", snapshot.activeObject?.properties?.get("value"))
        assertTrue(snapshot.selectedObjects.contains("\$C\$10"))
    }

    @Test
    fun `handles running Excel instance with no active workbook`() {
        val adapter = ExcelApplicationAdapter()
        val mockOutput = "RUNNING_NO_WORKBOOK"

        val obs = adapter.parseScriptOutput(mockOutput)

        assertTrue(obs.isRunning)
        assertEquals(null, obs.workbookName)
        assertTrue(obs.diagnostic?.contains("no workbook is currently open") == true)
    }

    @Test
    fun `handles Excel not running gracefully returning Unavailable`() = runBlocking {
        val adapter = ExcelApplicationAdapter(scriptRunner = { "ERROR\tCannot find running Excel instance." })

        val result = adapter.takeSnapshot()

        assertTrue(result is AdapterSnapshotResult.Unavailable)
        assertTrue(result.diagnostic.contains("Cannot find running Excel instance"))
    }
}
