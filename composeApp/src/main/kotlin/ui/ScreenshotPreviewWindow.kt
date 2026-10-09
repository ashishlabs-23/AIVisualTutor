package ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.skia.Image
import java.io.File
import context.EvidenceDecision
import context.GroundingMode
import context.GroundingResult
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.Button
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.TextField
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun ScreenshotPreviewWindow(
    pngPath: String,
    evidence: ScreenshotPreviewEvidence,
    onCloseRequest: () -> Unit,
    onRunGrounding: (suspend (GroundingMode, String?) -> GroundingResult)? = null
) {
    val windowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        size = DpSize(900.dp, 700.dp)
    )

    var bitmap by remember(pngPath) { mutableStateOf<ImageBitmap?>(null) }
    var loadError by remember(pngPath) { mutableStateOf<String?>(null) }
    var selectedMode by remember(pngPath) { mutableStateOf(GroundingMode.ADAPTIVE) }
    var groundingTarget by remember(pngPath) { mutableStateOf(evidence.targetDescription.orEmpty()) }
    var modeMenuOpen by remember(pngPath) { mutableStateOf(false) }
    var groundingResult by remember(pngPath) { mutableStateOf<GroundingResult?>(null) }
    var groundingProgress by remember(pngPath) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pngPath) {
        try {
            val file = File(pngPath)
            if (!file.exists() || !file.isFile || !pngPath.lowercase().endsWith(".png")) {
                loadError = "Screenshot is missing or invalid."
                bitmap = null
                return@LaunchedEffect
            }
            val image = Image.makeFromEncoded(file.readBytes())
            bitmap = image.toComposeImageBitmap()
            loadError = null
        } catch (exception: Exception) {
            loadError = exception.message ?: "Unable to load screenshot."
            bitmap = null
        }
    }

    Window(
        onCloseRequest = onCloseRequest,
        state = windowState,
        title = "Screenshot Preview",
        undecorated = false,
        transparent = false,
        resizable = true,
        alwaysOnTop = false,
        focusable = true
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF111827))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                loadError != null -> {
                    Text(
                        text = loadError ?: "Unable to load screenshot.",
                        color = Color.White,
                        modifier = Modifier
                            .background(Color(0xFF1F2937), RoundedCornerShape(8.dp))
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                    )
                }

                bitmap != null -> {
                    Image(
                        bitmap = bitmap!!,
                        contentDescription = "Captured application screenshot",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0F172A), RoundedCornerShape(10.dp))
                    )
                    groundingResult?.takeIf { it.acceptedBox != null || it.acceptedPoint != null }?.let { result ->
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val sourceWidth = bitmap!!.width.toFloat()
                            val sourceHeight = bitmap!!.height.toFloat()
                            val scale = minOf(size.width / sourceWidth, size.height / sourceHeight)
                            val drawnWidth = sourceWidth * scale
                            val drawnHeight = sourceHeight * scale
                            val offsetX = (size.width - drawnWidth) / 2f
                            val offsetY = (size.height - drawnHeight) / 2f
                            result.acceptedBox?.let { box ->
                                drawRect(Color(0xFF34D399), Offset(offsetX + box.x.toFloat() * scale, offsetY + box.y.toFloat() * scale),
                                    Size(box.width.toFloat() * scale, box.height.toFloat() * scale), style = Stroke(width = 3.dp.toPx()))
                            }
                            result.acceptedPoint?.let { point ->
                                drawCircle(Color(0xFF34D399), radius = 7.dp.toPx(), center = Offset(offsetX + point.first.toFloat() * scale, offsetY + point.second.toFloat() * scale))
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .width(360.dp)
                            .heightIn(max = 320.dp)
                            .background(Color(0xEE1F2937), RoundedCornerShape(8.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text("TARGET", color = Color(0xFF93C5FD))
                        if (onRunGrounding != null) {
                            TextField(
                                value = groundingTarget,
                                onValueChange = { groundingTarget = it },
                                label = { Text("Target description") },
                                singleLine = false
                            )
                        } else Text(evidence.targetLabel, color = Color.White)
                        Text("UIA: ${evidence.uiAutomationStatus}", color = Color.White)
                        Text("OCR: ${evidence.ocrStatus}", color = Color.White)
                        Text("VISION: ${evidence.visualGroundingStatus}", color = Color.White)
                        evidence.visualReason?.let { Text("Reason: $it", color = Color.White) }
                        Text("DECISION: ${evidence.decision}", color = Color.White)
                        if (onRunGrounding != null) {
                            Box {
                                Button(onClick = { modeMenuOpen = true }) { Text("Mode: ${selectedMode.name}") }
                                DropdownMenu(expanded = modeMenuOpen, onDismissRequest = { modeMenuOpen = false }) {
                                    GroundingMode.values().forEach { mode ->
                                        DropdownMenuItem(onClick = { selectedMode = mode; modeMenuOpen = false }) { Text(mode.name) }
                                    }
                                }
                            }
                            Button(onClick = {
                                groundingProgress = "Running ${selectedMode.name}..."
                                groundingResult = null
                                scope.launch {
                                    groundingProgress = try {
                                        val result = onRunGrounding(selectedMode, groundingTarget.takeIf(String::isNotBlank))
                                        groundingResult = result
                                        "${result.decision}: ${result.reason.name}"
                                    } catch (error: Exception) {
                                        "Experiment failed: ${error.javaClass.simpleName}"
                                    }
                                }
                            }, enabled = groundingProgress?.startsWith("Running") != true) { Text("Run grounding") }
                            groundingProgress?.let { Text(it, color = Color.White) }
                            groundingResult?.let { result ->
                                Text("Run ID: ${result.runId}", color = Color.White)
                                Text("Providers: ${result.executedProviders.joinToString()}", color = Color.White)
                                result.providerRuns.forEach { provider ->
                                    Text("${provider.provider}: ${provider.status} (${provider.latencyMillis} ms)${provider.diagnostic?.let { " — $it" } ?: ""}", color = Color.White)
                                }
                                result.skippedReasons.forEach { (provider, reason) -> Text("$provider skipped: $reason", color = Color.White) }
                                result.proposedCandidate?.let { candidate ->
                                    Text("Heuristic score ${candidate.score}; target text similarity ${candidate.targetSimilarity}", color = Color.White)
                                }
                                Text("Accepted target: ${result.acceptedBox?.let { "box ${it.x}, ${it.y}, ${it.width}, ${it.height}" } ?: result.acceptedPoint?.let { "point ${it.first}, ${it.second}" } ?: "None (unconfirmed)"}", color = Color.White)
                                Text("Proposed coordinates: ${result.proposedBox?.let { "${it.x}, ${it.y}" } ?: "None"}", color = Color.White)
                                if (result.decision != EvidenceDecision.ACCEPT) Text("No target was confirmed.", color = Color(0xFFFCA5A5))
                            }
                        }
                    }
                }

                else -> {
                    Text(
                        text = "Loading screenshot...",
                        color = Color.White,
                        modifier = Modifier
                            .background(Color(0xFF1F2937), RoundedCornerShape(8.dp))
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                    )
                }
            }

            Text(
                text = "Close",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(Color(0xFF3A3F4B), RoundedCornerShape(8.dp))
                    .clickable { onCloseRequest() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}
