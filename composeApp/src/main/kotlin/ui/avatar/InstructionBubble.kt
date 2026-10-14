package ui.avatar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Integrated frosted-glass instruction bubble displayed beside the robot avatar.
 *
 * Provides:
 * - Step progress indicator or context state chip.
 * - Current active instruction text (or idle/offline prompt).
 * - Target control indicator if resolved.
 * - Primary action buttons: Highlight, Explain More, Open Workspace.
 * - Contextual in-bubble explanation display with loading & dismiss controls.
 */
@Composable
fun InstructionBubble(
    stepNumber: Int?,
    totalSteps: Int?,
    instructionText: String?,
    targetControlName: String? = null,
    statusLabel: String = "Ready",
    statusColor: Color = Color(0xFFF59E0B),
    isHighlightBusy: Boolean = false,
    highlightFeedback: String? = null,
    onHighlightClick: () -> Unit = {},
    isExplainBusy: Boolean = false,
    explanationText: String? = null,
    onExplainClick: () -> Unit = {},
    onDismissExplanation: () -> Unit = {},
    onToggleWorkspace: () -> Unit = {},
    isWorkspaceOpen: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .widthIn(min = 270.dp, max = 390.dp)
            .background(
                color = Color(0xEE0F1424), // High-opacity frosted dark glass
                shape = RoundedCornerShape(18.dp)
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color(0x888B5CF6),
                        Color(0x4438BDF8),
                        Color(0x22FFFFFF),
                        statusColor.copy(alpha = 0.4f)
                    )
                ),
                shape = RoundedCornerShape(18.dp)
            )
            .padding(14.dp)
    ) {
        // 1. Header: Step index badge & Status Chip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (stepNumber != null && totalSteps != null && totalSteps > 0) {
                Text(
                    text = "Step $stepNumber of $totalSteps",
                    color = Color(0xFFAAB0BD),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Text(
                    text = "AI Visual Tutor",
                    color = Color(0xFFAAB0BD),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Status chip
            Box(
                modifier = Modifier
                    .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .border(1.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = statusLabel,
                    color = statusColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(7.dp))

        // 2. Main Instruction or Idle/Offline Text
        val displayText = when {
            !instructionText.isNullOrBlank() -> instructionText
            statusLabel.contains("Offline", ignoreCase = true) ->
                "Blender adapter disconnected on port 47629. Start Blender and enable the State Adapter."
            else ->
                "Ready to guide. Enter a 3D goal in the workspace or start Blender to begin."
        }

        Text(
            text = displayText,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )

        // 3. Target Control indicator if present
        if (!targetControlName.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "🎯 Target: ", color = Color(0xFF94A3B8), fontSize = 11.sp)
                Text(
                    text = targetControlName,
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 4. In-bubble Explanation View (if explanation is active or loading)
        if (isExplainBusy || !explanationText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x331E293B), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0x446366F1), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "💡 Explanation",
                        color = Color(0xFFC084FC),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "✕",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { onDismissExplanation() }
                            .padding(2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                if (isExplainBusy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = Color(0xFFC084FC),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Consulting tutoring knowledge engine...",
                            color = Color(0xFFCBD5E1),
                            fontSize = 11.sp
                        )
                    }
                } else if (explanationText != null) {
                    Text(
                        text = explanationText,
                        color = Color(0xFFE2E8F0),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // 5. Highlight Feedback notification
        if (!highlightFeedback.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = highlightFeedback,
                color = if (highlightFeedback.startsWith("✓")) Color(0xFF4ADE80) else Color(0xFFFBBF24),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 6. Action Buttons: Highlight | Explain More | Workspace
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Highlight Button
            Box(
                modifier = Modifier
                    .weight(1.1f)
                    .background(
                        brush = Brush.horizontalGradient(listOf(Color(0xFF8B5CF6), Color(0xFF6366F1))),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable(enabled = !isHighlightBusy) { onHighlightClick() }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isHighlightBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        color = Color.White,
                        strokeWidth = 1.5.dp
                    )
                } else {
                    Text(
                        text = "✨ Highlight",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            // Explain More Button
            Box(
                modifier = Modifier
                    .weight(1.1f)
                    .background(Color(0x281E293B), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0x408B5CF6), RoundedCornerShape(8.dp))
                    .clickable(enabled = !isExplainBusy) { onExplainClick() }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "💬 Explain",
                    color = Color(0xFFE0E7FF),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp
                )
            }

            // Workspace toggle button
            Box(
                modifier = Modifier
                    .weight(0.9f)
                    .background(Color(0x20334155), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0x3364748B), RoundedCornerShape(8.dp))
                    .clickable { onToggleWorkspace() }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isWorkspaceOpen) "HUD ▾" else "HUD ▴",
                    color = Color(0xFF94A3B8),
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp
                )
            }
        }
    }
}
