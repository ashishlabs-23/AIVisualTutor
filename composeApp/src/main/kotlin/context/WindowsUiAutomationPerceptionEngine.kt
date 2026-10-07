package context

import java.awt.Rectangle
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One actual descendant exposed by Windows UI Automation. Coordinates are desktop screen coordinates. */
data class UiAutomationCandidate(
    val name: String?,
    val automationId: String?,
    val controlTypeId: Int,
    val bounds: Rectangle?,
    val isEnabled: Boolean?,
    val isOffscreen: Boolean?,
    val value: String? = null
)

/** Isolated native boundary so ranking and result mapping can be tested without Windows UI Automation. */
fun interface UiAutomationClient {
    fun descendants(windowHandle: Long): List<UiAutomationCandidate>
}

class WindowsUiAutomationPerceptionEngine(
    private val client: UiAutomationClient = PowerShellUiAutomationClient(),
    private val isWindows: () -> Boolean = { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) },
    private val coordinatesArePhysical: () -> Boolean = ::allAwtDisplaysUsePhysicalCoordinates
) : PerceptionEngine {
    override suspend fun perceive(request: PerceptionRequest): PerceptionResult = withContext(Dispatchers.IO) {
        val app = request.applicationContext
        val baseMetadata = mutableMapOf("provider" to "Windows UI Automation")
        if (!isWindows()) return@withContext emptyResult(app, "unsupported_platform", baseMetadata)
        val hwnd = app?.windowHandle?.takeIf { it != 0L }
            ?: return@withContext emptyResult(app, "missing_window_handle", baseMetadata)
        if (!coordinatesArePhysical()) return@withContext emptyResult(app, "coordinate_mapping_unsupported", baseMetadata)
        val candidates = try {
            client.descendants(hwnd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            baseMetadata["diagnostic"] = (e.message ?: e.javaClass.simpleName).replace(Regex("[\\r\\n|]"), " ").take(100)
            return@withContext emptyResult(app, "uia_query_failed", baseMetadata)
        }
        val ranked = rankUiAutomationCandidates(candidates, request.selectedRegion)
        if (ranked.isEmpty()) return@withContext emptyResult(app, "no_intersecting_element", baseMetadata + ("candidateCount" to candidates.size.toString()))

        val candidate = ranked.first()
        val uiType = mapControlType(candidate.controlTypeId)
        val metadata = baseMetadata + mapOf(
            "automationId" to candidate.automationId,
            "controlTypeId" to candidate.controlTypeId.toString(),
            "isEnabled" to candidate.isEnabled?.toString(),
            "isOffscreen" to candidate.isOffscreen?.toString(),
            "intersection" to "true",
            "candidateCount" to ranked.size.toString()
        ).filterValues { it != null }.mapValues { it.value!! }
        // 1.0 denotes direct, deterministic UIA evidence plus a rectangle intersection; it is not a model probability.
        PerceptionResult(
            applicationName = app?.applicationName,
            selectedObject = candidate.name?.takeIf(String::isNotBlank),
            // Value is populated only through UIA's ValuePattern; Name is an accessible name, not assumed text.
            visibleText = candidate.value?.takeIf(String::isNotBlank),
            uiType = uiType,
            boundingRectangle = candidate.bounds,
            confidence = 1.0f,
            source = PerceptionSource.UI_AUTOMATION,
            metadata = metadata
        )
    }

    private fun emptyResult(app: ApplicationContext?, reason: String, metadata: Map<String, String>) = PerceptionResult(
        applicationName = app?.applicationName,
        selectedObject = null,
        visibleText = null,
        uiType = UiType.UNKNOWN,
        boundingRectangle = null,
        confidence = null,
        source = PerceptionSource.UI_AUTOMATION,
        metadata = metadata + ("status" to reason)
    )
}

private fun allAwtDisplaysUsePhysicalCoordinates(): Boolean = try {
    val devices = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
    devices.isNotEmpty() && devices.all { device ->
        val transform = device.defaultConfiguration.defaultTransform
        transform.scaleX == 1.0 && transform.scaleY == 1.0 && transform.shearX == 0.0 && transform.shearY == 0.0
    }
} catch (_: Exception) {
    false
}

/**
 * Controls are ranked by containment evidence: an element inside the selection, then an element
 * containing the selection, then partial overlaps. Within a class, prefer smaller bounds, then
 * center proximity, numeric control type, and name for a stable tie-break.
 */
fun rankUiAutomationCandidates(candidates: List<UiAutomationCandidate>, selectedRegion: Rectangle): List<UiAutomationCandidate> =
    candidates.filter { it.bounds != null && intersects(it.bounds, selectedRegion) }
        .sortedWith(compareBy<UiAutomationCandidate> { containmentRank(it.bounds!!, selectedRegion) }
            .thenBy { area(it.bounds!!) }
            .thenBy { centerDistanceSquared(it.bounds!!, selectedRegion) }
            .thenBy { it.controlTypeId }
            .thenBy { it.name.orEmpty() }
            .thenBy { it.automationId.orEmpty() })

private fun containmentRank(element: Rectangle, selection: Rectangle): Int = when {
    selection.contains(element) -> 0
    element.contains(selection) -> 1
    else -> 2
}

private fun intersects(a: Rectangle, b: Rectangle) = a.width > 0 && a.height > 0 && b.width > 0 && b.height > 0 && a.intersects(b)
private fun area(rect: Rectangle) = rect.width.toLong() * rect.height.toLong()
private fun centerDistanceSquared(a: Rectangle, b: Rectangle): Double {
    val dx = a.centerX - b.centerX
    val dy = a.centerY - b.centerY
    return dx * dx + dy * dy
}

/** Maps Windows UIA ControlType identifiers to the existing platform-neutral contract. */
fun mapControlType(controlTypeId: Int): UiType = when (controlTypeId) {
    50000, // Button
    50002, // CheckBox
    50013, // RadioButton
    50031 // SplitButton
    -> UiType.BUTTON
    50003, // ComboBox
    50004, // Edit
    50015, // Slider
    50016 // Spinner
    -> UiType.INPUT
    50009, 50010, 50011 -> UiType.MENU // Menu, MenuBar, MenuItem
    50021 -> UiType.TOOLBAR
    50028, 50036 -> UiType.TABLE // DataGrid, Table
    50020 -> UiType.TEXT
    50006 -> UiType.IMAGE
    // Other known control types have no semantically accurate UiType in the current contract.
    else -> UiType.UNKNOWN
}

/**
 * Uses Windows UIAutomationClient in an isolated STA PowerShell process. The target HWND is passed
 * from the pre-selector ApplicationContext; this adapter never consults the foreground window.
 */
class PowerShellUiAutomationClient(private val timeoutMillis: Long = 5_000) : UiAutomationClient {
    override fun descendants(windowHandle: Long): List<UiAutomationCandidate> {
        require(windowHandle != 0L) { "Invalid HWND." }
        val script = """
            try {
              Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class AivtDpi { [DllImport("user32.dll")] public static extern bool SetThreadDpiAwarenessContext(IntPtr context); }'
              if (-not [AivtDpi]::SetThreadDpiAwarenessContext([IntPtr](-4))) { throw 'dpi_awareness_unavailable' }
              Add-Type -AssemblyName UIAutomationClient
              Add-Type -AssemblyName UIAutomationTypes
              ${'$'}root = [System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]::new([long]${windowHandle}))
              if (${'$'}null -eq ${'$'}root) { [Console]::Error.WriteLine('invalid_hwnd'); exit 2 }
              ${'$'}items = @(${'$'}root) + @(${'$'}root.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition))
              foreach (${'$'}item in ${'$'}items) {
                  ${'$'}r = ${'$'}item.Current.BoundingRectangle
                  # Offscreen elements expose Rect.Empty (non-finite coordinates). Casting those to Int32 aborts the whole tree.
                  if (${'$'}r.IsEmpty -or ${'$'}r.Width -le 0 -or ${'$'}r.Height -le 0) { continue }
                  ${'$'}x = [Math]::Floor(${'$'}r.X); ${'$'}y = [Math]::Floor(${'$'}r.Y); ${'$'}w = [Math]::Ceiling(${'$'}r.Width); ${'$'}h = [Math]::Ceiling(${'$'}r.Height)
                  if (${'$'}x -lt [int]::MinValue -or ${'$'}x -gt [int]::MaxValue -or ${'$'}y -lt [int]::MinValue -or ${'$'}y -gt [int]::MaxValue -or ${'$'}w -gt [int]::MaxValue -or ${'$'}h -gt [int]::MaxValue) { continue }
                  ${'$'}controlId = [int]${'$'}item.Current.ControlType.Id
                  ${'$'}name = [string]${'$'}item.Current.Name
                  ${'$'}autoId = [string]${'$'}item.Current.AutomationId
                  ${'$'}value = ''
                  ${'$'}pattern = ${'$'}null
                  if (${'$'}item.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]${'$'}pattern)) { ${'$'}value = [string]${'$'}pattern.Current.Value }
                  ${'$'}fields = @(${'$'}controlId, [int]${'$'}x, [int]${'$'}y, [int]${'$'}w, [int]${'$'}h, [int]${'$'}item.Current.IsEnabled, [int]${'$'}item.Current.IsOffscreen,
                    [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(${'$'}name)), [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(${'$'}autoId)), [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(${'$'}value)))
                  [Console]::Out.WriteLine(${'$'}fields -join '|')
              }
            } catch {
              ${'$'}detail = (${'$'}_.Exception.GetType().FullName + ': ' + ${'$'}_.Exception.Message) -replace '[\\r\\n|]', ' '
              if (${'$'}detail.Length -gt 160) { ${'$'}detail = ${'$'}detail.Substring(0, 160) }
              [Console]::Error.WriteLine(${'$'}detail)
              exit 3
            }
        """
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
        val command = "[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$encoded')) | Invoke-Expression"
        val process = ProcessBuilder("powershell.exe", "-STA", "-NoProfile", "-NonInteractive", "-Command", command)
            .redirectErrorStream(true).start()
        val outputFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("uia_timeout")
        }
        val output = outputFuture.get(1, TimeUnit.SECONDS)
        if (process.exitValue() != 0) {
            val diagnostic = output.lineSequence().lastOrNull()?.take(80)?.ifBlank { "uia_query_failed" } ?: "uia_query_failed"
            throw IllegalStateException(diagnostic)
        }
        return output.lineSequence().mapNotNull(::parseCandidate).toList()
    }

    private fun parseCandidate(line: String): UiAutomationCandidate? {
        val parts = line.split('|')
        if (parts.size != 10) return null
        return runCatching {
            fun decode(index: Int) = String(Base64.getDecoder().decode(parts[index]), Charsets.UTF_8).ifEmpty { null }
            UiAutomationCandidate(
                name = decode(7), automationId = decode(8), controlTypeId = parts[0].toInt(),
                bounds = Rectangle(parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[4].toInt()),
                isEnabled = parts[5].toInt() != 0, isOffscreen = parts[6].toInt() != 0, value = decode(9)
            )
        }.getOrNull()
    }
}

fun main(args: Array<String>) {
    val client = PowerShellUiAutomationClient()
    val hwnd = args[0].toLong()
    val region = java.awt.Rectangle(args[1].toInt(), args[2].toInt(), args[3].toInt(), args[4].toInt())
    try {
        val items = client.descendants(hwnd)
        val ranked = rankUiAutomationCandidates(items, region)
        println("COUNT ${items.size}")
        println("RANKED ${ranked.size}")
        ranked.take(5).forEach {
            println("TOP name=${it.name} autoId=${it.automationId} type=${it.controlTypeId} bounds=${it.bounds} value=${it.value}")
        }
    } catch (e: Exception) {
        System.err.println("EX ${e.javaClass.name}: ${e.message}")
        e.printStackTrace()
    }
}
