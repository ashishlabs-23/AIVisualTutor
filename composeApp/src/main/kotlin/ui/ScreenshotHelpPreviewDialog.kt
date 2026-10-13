package ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import org.jetbrains.skia.Image as SkiaImage
import tutor.ScreenshotHelpCoordinator
import tutor.ScreenshotHelpWorkflowState

/**
 * Dedicated preview window for the screenshot-assisted help workflow.
 * Shows the captured Blender screenshot, active step context, and confirmation/retry/cancel actions.
 */
@Composable
fun ScreenshotHelpPreviewDialog(
    coordinator: ScreenshotHelpCoordinator,
    onDismissRequest: () -> Unit,
    onRetakeRequest: () -> Unit,
    onConfirmRequest: () -> Unit
) {
    val state = coordinator.state
    val windowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        size = DpSize(920.dp, 680.dp)
    )

    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var imageLoadError by remember { mutableStateOf<String?>(null) }

    val imagePath = when (state) {
        is ScreenshotHelpWorkflowState.PreviewReady -> state.context.imagePath
        is ScreenshotHelpWorkflowState.Analyzing -> state.context.imagePath
        is ScreenshotHelpWorkflowState.ResponseReady -> state.context.imagePath
        else -> null
    }

    LaunchedEffect(imagePath) {
        if (imagePath != null) {
            try {
                val file = File(imagePath)
                if (!file.exists() || !file.isFile || file.length() <= 0) {
                    imageLoadError = "Screenshot file not found or empty."
                    imageBitmap = null
                } else {
                    val bytes = file.readBytes()
                    val skiaImg = SkiaImage.makeFromEncoded(bytes)
                    imageBitmap = skiaImg.toComposeImageBitmap()
                    imageLoadError = null
                }
            } catch (ex: Exception) {
                imageLoadError = "Could not decode screenshot: ${ex.message}"
                imageBitmap = null
            }
        } else {
            imageBitmap = null
            imageLoadError = null
        }
    }

    Window(
        onCloseRequest = onDismissRequest,
        state = windowState,
        title = "AI Visual Tutor — Screenshot Help Preview",
        undecorated = false,
        transparent = false,
        resizable = true,
        alwaysOnTop = true
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F1422))
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFF6366F1))),
                                    RoundedCornerShape(8.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("📸", fontSize = 16.sp)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Screenshot-Assisted Help Preview",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Verify that the captured Blender workspace correctly shows where you are stuck.",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .background(Color(0x33475569), RoundedCornerShape(8.dp))
                            .clickable { onDismissRequest() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Close ✕", color = Color(0xFFE2E8F0), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Main Content Body: Screenshot on left, Lesson Context on right
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Screenshot Viewport
                    Box(
                        modifier = Modifier
                            .weight(1.8f)
                            .fillMaxSize()
                            .background(Color(0xFF0A0D16), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0x33475569), RoundedCornerShape(12.dp))
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            imageLoadError != null -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("⚠️ Image Load Error", color = Color(0xFFF87171), fontWeight = FontWeight.Bold)
                                    Text(imageLoadError ?: "Unknown error", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                                }
                            }
                            imageBitmap != null -> {
                                Image(
                                    bitmap = imageBitmap!!,
                                    contentDescription = "Captured Blender Workspace",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            state is ScreenshotHelpWorkflowState.Capturing -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("⏳ Capturing Blender screen...", color = Color(0xFFA78BFA), fontWeight = FontWeight.Bold)
                                    Text("Temporarily hiding tutor overlay for clean capture...", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                }
                            }
                            else -> {
                                Text("No screenshot loaded.", color = Color(0xFF64748B), fontSize = 13.sp)
                            }
                        }
                    }

                    // Tutoring Lesson Context Card
                    Column(
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxSize()
                            .background(Color(0x2E181E2E), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0x2B475569), RoundedCornerShape(12.dp))
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "ASSOCIATED LESSON CONTEXT",
                            color = Color(0xFFC084FC),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )

                        val activeContext = when (state) {
                            is ScreenshotHelpWorkflowState.PreviewReady -> state.context
                            is ScreenshotHelpWorkflowState.Analyzing -> state.context
                            is ScreenshotHelpWorkflowState.ResponseReady -> state.context
                            else -> null
                        }

                        if (activeContext != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Goal:", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                Text(activeContext.userGoal, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Step ${activeContext.stepIndex + 1}:", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                Text(activeContext.stepTitle, color = Color(0xFFE2E8F0), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Instruction:", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                Text(activeContext.stepInstruction, color = Color(0xFFCBD5E1), fontSize = 12.sp, lineHeight = 16.sp)
                            }

                            if (!activeContext.expectedOutcome.isNullOrBlank()) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Expected Outcome:", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                    Text(activeContext.expectedOutcome, color = Color(0xFF34D399), fontSize = 12.sp)
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Captured Workspace:", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                Text("${activeContext.imageWidth} × ${activeContext.imageHeight} px", color = Color(0xFFA78BFA), fontSize = 12.sp)
                                activeContext.blenderWindowBounds?.let { b ->
                                    Text("Window Rect: ${b.width}×${b.height} at (${b.x}, ${b.y})", color = Color(0xFF64748B), fontSize = 11.sp)
                                }
                            }
                        } else if (state is ScreenshotHelpWorkflowState.Error) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0x33DC2626), RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(0x66EF4444), RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            ) {
                                Text("Error", color = Color(0xFFFCA5A5), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(state.message, color = Color.White, fontSize = 12.sp)
                                state.diagnostic?.let { diag ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Details: $diag", color = Color(0xFFCBD5E1), fontSize = 11.sp)
                                }
                            }
                        } else {
                            Text("Awaiting capture...", color = Color(0xFF64748B), fontSize = 12.sp)
                        }

                        // State Banner
                        when (state) {
                            is ScreenshotHelpWorkflowState.Analyzing -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0x334338CA), RoundedCornerShape(8.dp))
                                        .border(1.dp, Color(0x666366F1), RoundedCornerShape(8.dp))
                                        .padding(12.dp)
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "⚡ Analyzing Blender Workspace with Gemini Vision...",
                                            color = Color(0xFFA5B4FC),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Evaluating active mode, 3D viewport, toolbar, and object geometry...",
                                            color = Color(0xFFE2E8F0),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                            is ScreenshotHelpWorkflowState.ResponseReady -> {
                                val rec = state.recommendation
                                if (rec != null) {
                                    val badgeColor = when (rec.recommendationType) {
                                        tutor.RecommendationType.CONTINUE -> Color(0xFF10B981)
                                        tutor.RecommendationType.CORRECT_STATE -> Color(0xFFF59E0B)
                                        tutor.RecommendationType.CLARIFY -> Color(0xFFA855F7)
                                        tutor.RecommendationType.REQUEST_MORE_EVIDENCE -> Color(0xFF38BDF8)
                                        tutor.RecommendationType.RETRY -> Color(0xFFEC4899)
                                        tutor.RecommendationType.RECOVER -> Color(0xFFEF4444)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                Brush.linearGradient(listOf(Color(0xFF1E1B4B), Color(0xFF0F172A))),
                                                RoundedCornerShape(10.dp)
                                            )
                                            .border(1.5.dp, Color(0xFF6366F1), RoundedCornerShape(10.dp))
                                            .padding(12.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Box(
                                                        modifier = Modifier
                                                            .background(badgeColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                            .border(1.dp, badgeColor, RoundedCornerShape(4.dp))
                                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = rec.recommendationType.displayName.uppercase(),
                                                            color = badgeColor,
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                    Text(
                                                        text = "RECOMMENDED ACTION",
                                                        color = Color(0xFFCBD5E1),
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                                if (rec.confidence != null) {
                                                    Text(
                                                        text = "Confidence: ${(rec.confidence * 100).toInt()}%",
                                                        color = Color(0xFF94A3B8),
                                                        fontSize = 10.sp
                                                    )
                                                }
                                            }

                                            Text(
                                                text = rec.title,
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )

                                            Text(
                                                text = rec.explanation,
                                                color = Color(0xFFE2E8F0),
                                                fontSize = 11.sp,
                                                lineHeight = 15.sp
                                            )

                                            if (!rec.conflictWarning.isNullOrBlank()) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(Color(0x33F59E0B), RoundedCornerShape(6.dp))
                                                        .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(6.dp))
                                                        .padding(8.dp)
                                                ) {
                                                    Text("⚠️ ${rec.conflictWarning}", color = Color(0xFFFDE68A), fontSize = 11.sp)
                                                }
                                            }

                                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                Text("TRY THIS NEXT:", color = Color(0xFF67E8F9), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                rec.actionSteps.forEachIndexed { idx, step ->
                                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        Text("${idx + 1}.", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                        Text(step, color = Color.White, fontSize = 11.sp)
                                                    }
                                                }
                                            }

                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("Why this helps:", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                                Text(rec.rationale, color = Color(0xFFCBD5E1), fontSize = 10.sp)
                                            }

                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("How to check:", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                                Text(rec.expectedOutcome, color = Color(0xFFA7F3D0), fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }

                                val result = state.analysisResult
                                if (result != null) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        // Analysis Summary Card
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color(0x2E065F46), RoundedCornerShape(8.dp))
                                                .border(1.dp, Color(0x6610B981), RoundedCornerShape(8.dp))
                                                .padding(10.dp)
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text("✔ Visual Analysis Complete", color = Color(0xFF34D399), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                    Text("${result.modelIdentifier} • ${result.latencyMillis}ms", color = Color(0xFF6EE7B7), fontSize = 10.sp)
                                                }
                                                Text(result.summary, color = Color.White, fontSize = 12.sp, lineHeight = 16.sp)
                                            }
                                        }

                                        // Observations
                                        if (result.observations.isNotEmpty()) {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("VISUAL OBSERVATIONS", color = Color(0xFF93C5FD), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                result.observations.forEach { obs ->
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(Color(0x1A1E293B), RoundedCornerShape(6.dp))
                                                            .padding(8.dp)
                                                    ) {
                                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                            Text("• ${obs.description}", color = Color(0xFFE2E8F0), fontSize = 11.sp)
                                                            if (!obs.evidence.isNullOrBlank()) {
                                                                Text("  Evidence: ${obs.evidence}", color = Color(0xFF94A3B8), fontSize = 10.sp)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // Relevant UI Elements
                                        if (result.relevantUiElements.isNotEmpty()) {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("DETECTED BLENDER ELEMENTS", color = Color(0xFFC084FC), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                result.relevantUiElements.forEach { el ->
                                                    Text(
                                                        text = "• ${el.name} (${if (el.visible) "visible" else "hidden"})${if (!el.evidence.isNullOrBlank()) ": ${el.evidence}" else ""}",
                                                        color = Color(0xFFCBD5E1),
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }
                                        }

                                        // Uncertainty Notes
                                        if (result.uncertaintyNotes.isNotEmpty()) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color(0x26D97706), RoundedCornerShape(8.dp))
                                                    .border(1.dp, Color(0x66F59E0B), RoundedCornerShape(8.dp))
                                                    .padding(10.dp)
                                            ) {
                                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("⚠️ Visual Ambiguity / Limits:", color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                    result.uncertaintyNotes.forEach { note ->
                                                        Text("• $note", color = Color(0xFFFEF3C7), fontSize = 11.sp)
                                                    }
                                                }
                                            }
                                        }

                                        // Next Step Evidence
                                        if (!result.suggestedNextStepEvidence.isNullOrBlank()) {
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text("NEXT STEP VISUAL EVIDENCE", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                Text(result.suggestedNextStepEvidence, color = Color(0xFFBAE6FD), fontSize = 11.sp)
                                            }
                                        }
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0x33065F46), RoundedCornerShape(8.dp))
                                            .border(1.dp, Color(0x6610B981), RoundedCornerShape(8.dp))
                                            .padding(10.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("✔ Request Staged", color = Color(0xFF34D399), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            Text(state.message, color = Color.White, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Bottom Action Footer Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tutoring lesson remains active and intact.",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Cancel Button
                        Box(
                            modifier = Modifier
                                .background(Color(0x26475569), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0x33475569), RoundedCornerShape(8.dp))
                                .clickable { onDismissRequest() }
                                .padding(horizontal = 14.dp, vertical = 9.dp)
                        ) {
                            Text("Cancel", color = Color(0xFFCBD5E1), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                        // Retake Button
                        if (state is ScreenshotHelpWorkflowState.PreviewReady || state is ScreenshotHelpWorkflowState.Error) {
                            Box(
                                modifier = Modifier
                                    .background(Color(0x331E293B), RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(0x4064748B), RoundedCornerShape(8.dp))
                                    .clickable { onRetakeRequest() }
                                    .padding(horizontal = 14.dp, vertical = 9.dp)
                            ) {
                                Text("🔄 Retake Screenshot", color = Color(0xFF93C5FD), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        // Confirm & Submit Button
                        if (state is ScreenshotHelpWorkflowState.PreviewReady) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFF6366F1))),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { onConfirmRequest() }
                                    .padding(horizontal = 18.dp, vertical = 9.dp)
                            ) {
                                Text(
                                    text = "Confirm & Submit for Analysis",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else if (state is ScreenshotHelpWorkflowState.ResponseReady) {
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF059669), RoundedCornerShape(8.dp))
                                    .clickable { onDismissRequest() }
                                    .padding(horizontal = 18.dp, vertical = 9.dp)
                            ) {
                                Text("Done", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
