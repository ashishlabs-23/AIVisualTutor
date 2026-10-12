package adapter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.Instant
import java.util.UUID

/**
 * Strongly typed representation of an Excel workbook snapshot.
 */
data class ExcelStateObservation(
    val workbookName: String?,
    val activeSheetName: String?,
    val activeCellAddress: String?,
    val activeCellFormula: String?,
    val activeCellValue: String?,
    val worksheetNames: List<String> = emptyList(),
    val isRunning: Boolean = false,
    val diagnostic: String? = null
)

/**
 * Capability-aware Application Adapter for Microsoft Excel.
 * Interrogates running Excel instances via Windows COM Automation to inspect
 * active workbooks, sheets, cell addresses, values, and formulas.
 * Degrades safely with UNAVAILABLE status when Excel is closed.
 */
class ExcelApplicationAdapter(
    val queryTimeoutMillis: Long = 3500L,
    private val scriptRunner: (String) -> String? = ::defaultPowerShellRunner
) : ApplicationAdapter {

    override val identity = AdapterIdentity(
        id = "excel-com-adapter",
        applicationName = "Excel",
        adapterVersion = "1.0.0",
        supportedSchemaVersion = 1
    )

    override val capabilities: Set<AdapterCapability> = setOf(
        AdapterCapability.DOCUMENT_STRUCTURE,
        AdapterCapability.SELECTION_STATE,
        AdapterCapability.CELL_FORMULAS
    )

    private var currentSnapshotVersion: Long = 0L
    private var activeSessionId: String = UUID.randomUUID().toString()

    override suspend fun checkHealth(): AdapterHealth {
        val obs = queryExcelState()
        return if (obs.isRunning && obs.workbookName != null) {
            AdapterHealth(
                status = AdapterHealthStatus.HEALTHY,
                adapterId = identity.id,
                applicationName = identity.applicationName,
                sessionId = activeSessionId,
                lastSnapshotVersion = currentSnapshotVersion,
                diagnostic = "Connected to Excel. Active workbook: '${obs.workbookName}', Sheet: '${obs.activeSheetName}'",
                details = mapOf(
                    "workbook" to (obs.workbookName ?: ""),
                    "sheet" to (obs.activeSheetName ?: ""),
                    "activeCell" to (obs.activeCellAddress ?: "")
                )
            )
        } else {
            AdapterHealth(
                status = AdapterHealthStatus.UNAVAILABLE,
                adapterId = identity.id,
                applicationName = identity.applicationName,
                sessionId = null,
                diagnostic = obs.diagnostic ?: "Excel is not running or no workbook is open.",
                details = emptyMap()
            )
        }
    }

    override suspend fun currentSession(): ApplicationSession? {
        val obs = queryExcelState()
        if (!obs.isRunning || obs.workbookName == null) return null
        return ApplicationSession(
            sessionId = activeSessionId,
            applicationName = identity.applicationName,
            applicationVersion = "Office 365 / COM",
            activeSceneId = "workbook:${obs.workbookName}",
            activeSceneName = obs.activeSheetName
        )
    }

    override suspend fun takeSnapshot(): AdapterSnapshotResult {
        val obs = queryExcelState()
        if (!obs.isRunning || obs.workbookName == null) {
            return AdapterSnapshotResult.Unavailable(
                diagnostic = obs.diagnostic ?: "Microsoft Excel instance not detected or no workbook is open."
            )
        }

        currentSnapshotVersion++
        val scene = AdapterSceneIdentity(
            identity = "workbook:${obs.workbookName}:sheet:${obs.activeSheetName ?: "Sheet1"}",
            name = obs.activeSheetName ?: "Sheet1"
        )

        val metadata = mutableMapOf<String, String>()
        metadata["workbook"] = obs.workbookName
        obs.activeSheetName?.let { metadata["sheet"] = it }
        obs.activeCellAddress?.let { metadata["activeCell"] = it }
        obs.activeCellFormula?.let { metadata["activeFormula"] = it }
        obs.activeCellValue?.let { metadata["activeValue"] = it }
        metadata["worksheets"] = obs.worksheetNames.joinToString(",")

        val cellObj = obs.activeCellAddress?.let { address ->
            AdapterObjectObservation(
                identity = "cell:$address",
                name = address,
                type = "CELL",
                sceneName = obs.activeSheetName ?: "Sheet1",
                selected = true,
                properties = mapOf(
                    "formula" to (obs.activeCellFormula ?: ""),
                    "value" to (obs.activeCellValue ?: "")
                )
            )
        }

        val snapshot = AdapterSnapshot(
            schemaVersion = 1,
            applicationName = identity.applicationName,
            applicationVersion = "COM",
            sessionId = activeSessionId,
            snapshotVersion = currentSnapshotVersion,
            observedAt = Instant.now(),
            scene = scene,
            activeObject = cellObj,
            selectedObjects = setOfNotNull(obs.activeCellAddress),
            activeMode = "READY",
            workspace = "Spreadsheet",
            objects = listOfNotNull(cellObj),
            isComplete = true,
            metadata = metadata
        )

        return AdapterSnapshotResult.Success(snapshot)
    }

    override suspend fun close() {
        // COM reference cleanup
    }

    suspend fun queryExcelState(): ExcelStateObservation = withContext(Dispatchers.IO) {
        val script = """
            try {
                ${'$'}excel = [Runtime.InteropServices.Marshal]::GetActiveObject('Excel.Application')
                if (${'$'}null -eq ${'$'}excel) { exit 1 }
                ${'$'}wb = ${'$'}excel.ActiveWorkbook
                if (${'$'}null -eq ${'$'}wb) {
                    Write-Output "RUNNING_NO_WORKBOOK"
                    exit 0
                }
                ${'$'}ws = ${'$'}excel.ActiveSheet
                ${'$'}cell = ${'$'}excel.ActiveCell
                ${'$'}sheets = (${'$'}wb.Worksheets | ForEach-Object { ${'$'}_.Name }) -join '|'
                ${'$'}cellAddr = if (${'$'}cell) { ${'$'}cell.Address } else { '' }
                ${'$'}cellFormula = if (${'$'}cell) { ${'$'}cell.Formula } else { '' }
                ${'$'}cellVal = if (${'$'}cell) { ${'$'}cell.Value2 } else { '' }
                Write-Output "OK`t${'$'}(${'$'}wb.Name)`t${'$'}(${'$'}ws.Name)`t${'$'}cellAddr`t${'$'}cellFormula`t${'$'}cellVal`t${'$'}sheets"
            } catch {
                Write-Output "ERROR`t${'$'}(${'$'}_.Exception.Message)"
            }
        """.trimIndent()

        val output = withTimeoutOrNull(queryTimeoutMillis) {
            scriptRunner(script)?.trim()
        }

        if (output == null) {
            return@withContext ExcelStateObservation(
                workbookName = null,
                activeSheetName = null,
                activeCellAddress = null,
                activeCellFormula = null,
                activeCellValue = null,
                isRunning = false,
                diagnostic = "Query timed out after ${queryTimeoutMillis}ms"
            )
        }

        parseScriptOutput(output)
    }

    fun parseScriptOutput(output: String): ExcelStateObservation {
        val trimmed = output.trim()
        if (trimmed == "RUNNING_NO_WORKBOOK") {
            return ExcelStateObservation(
                workbookName = null,
                activeSheetName = null,
                activeCellAddress = null,
                activeCellFormula = null,
                activeCellValue = null,
                isRunning = true,
                diagnostic = "Excel is running, but no workbook is currently open."
            )
        }

        if (trimmed.startsWith("OK\t")) {
            val parts = trimmed.split("\t")
            val wb = parts.getOrNull(1)?.takeIf(String::isNotEmpty)
            val ws = parts.getOrNull(2)?.takeIf(String::isNotEmpty)
            val addr = parts.getOrNull(3)?.takeIf(String::isNotEmpty)
            val formula = parts.getOrNull(4)?.takeIf(String::isNotEmpty)
            val value = parts.getOrNull(5)?.takeIf(String::isNotEmpty)
            val sheets = parts.getOrNull(6)?.split("|")?.filter(String::isNotEmpty) ?: emptyList()

            return ExcelStateObservation(
                workbookName = wb,
                activeSheetName = ws,
                activeCellAddress = addr,
                activeCellFormula = formula,
                activeCellValue = value,
                worksheetNames = sheets,
                isRunning = true
            )
        }

        return ExcelStateObservation(
            workbookName = null,
            activeSheetName = null,
            activeCellAddress = null,
            activeCellFormula = null,
            activeCellValue = null,
            isRunning = false,
            diagnostic = if (trimmed.startsWith("ERROR\t")) trimmed.removePrefix("ERROR\t") else "Excel is not running."
        )
    }
}

private fun defaultPowerShellRunner(script: String): String? {
    return try {
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            script
        ).redirectErrorStream(true).start()

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.use { it.readText() }
        process.waitFor()
        output
    } catch (_: Exception) {
        null
    }
}
