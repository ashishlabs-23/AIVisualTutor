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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reference-matched dark translucent instruction bubble displayed beside the robot avatar.
 * Adheres strictly to the "AI Visual Tutor — Avatar States & PRD" design specification.
 *
 * Official Palette:
 * - Primary Orange: #F5792A (Blender warm orange)
 * - Accent: #FFA757 (Soft orange glow)
 * - Dark UI: #1E2127 (Graphite bubble background)
 * - Surface: #282F38 (Card surface)
 * - Text: #F1F5F9 (Clean white text)
 * - Success: #22C55E (Green confirmation)
 * - Error: #EF4444 (Red alert)
 */
@Composable
fun InstructionBubble(
    stepNumber: Int?,
    totalSteps: Int?,
    instructionText: String?,
    targetControlName: String? = null,
    statusLabel: String = "Ready",
    statusColor: Color = Color(0xFFF5792A),
    isHighlightBusy: Boolean = false,
    highlightFeedback: String? = null,
    onHighlightClick: () -> Unit = {},
    isExplainBusy: Boolean = false,
    explanationText: String? = null,
    onExplainClick: () -> Unit = {},
    onDismissExplanation: () -> Unit = {},
    onToggleWorkspace: () -> Unit = {},
    isWorkspaceOpen: Boolean = false,
    onStartTutorialClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val primaryOrange = Color(0xFFF5792A)
    val darkUiBackground = Color(0xF21E2127) // 95% opacity graphite
    val surfaceColor = Color(0xFF282F38)
    val borderColor = Color(0xFF2D323B)
    val textWhite = Color(0xFFF1F5F9)
    val textMuted = Color(0xFF8A94A6)

    val isOffline = statusLabel.contains("Offline", ignoreCase = true) || statusLabel.contains("No Blender", ignoreCase = true)
    val isSuccess = statusLabel.contains("Great job", ignoreCase = true) || statusLabel.contains("Success", ignoreCase = true)
    val isThinking = statusLabel.contains("Thinking", ignoreCase = true) || isHighlightBusy || isExplainBusy
    val isGreeting = (stepNumber == null || stepNumber == 0) && instructionText.isNullOrBlank() && !isOffline

    Column(
        modifier = modifier
            .widthIn(min = 280.dp, max = 390.dp)
            .background(
                color = darkUiBackground,
                shape = RoundedCornerShape(20.dp)
            )
            .border(
                width = 1.dp,
                color = if (isSuccess) Color(0xFF22C55E).copy(alpha = 0.6f) else borderColor,
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        // 1. Top Bar: Title / Greeting & HUD Workspace Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val titleText = when {
                isGreeting -> "Hi! 👋"
                isSuccess -> "Great job! 🎉"
                isThinking -> "Finding the ${targetControlName ?: "menu"}..."
                isOffline -> "No Blender Detected 💤"
                else -> if (!targetControlName.isNullOrBlank()) "Let's locate $targetControlName!" else "Active Step"
            }

            Text(
                text = titleText,
                color = if (isSuccess) Color(0xFF22C55E) else textWhite,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold
            )

            // Mini HUD Toggle Chip
            Box(
                modifier = Modifier
                    .background(surfaceColor, RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF3E4652), RoundedCornerShape(8.dp))
                    .clickable { onToggleWorkspace() }
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (isWorkspaceOpen) "HUD ▾" else "HUD ▴",
                    color = textMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 2. Main Instruction or State Description Text
        val bodyText = when {
            isGreeting ->
                "I'm your AI Visual Tutor. Shall we create something together?"
            isSuccess ->
                "You've successfully created the UV Sphere!"
            isThinking ->
                "Analyzing Blender window layout. Please wait a moment."
            isOffline ->
                "I couldn't find Blender. Please open Blender and I'll be right here."
            !instructionText.isNullOrBlank() ->
                instructionText
            else ->
                "Ready to guide. Enter a 3D goal or select a tutorial step to begin."
        }

        Text(
            text = bodyText,
            color = if (isSuccess) Color(0xFFE2E8F0) else Color(0xFFCBD5E1),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Normal
        )

        // 3. Progress Dots Indicator (4-5 round dots + '1 / 4' count)
        if (!isGreeting && !isOffline && totalSteps != null && totalSteps > 0) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Row of rounded progress dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val activeIdx = (stepNumber ?: 1) - 1
                    val maxDots = totalSteps.coerceIn(1, 6)
                    for (i in 0 until maxDots) {
                        val isDotActive = i <= activeIdx
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(
                                    color = if (isDotActive) primaryOrange else Color(0xFF3A414D),
                                    shape = CircleShape
                                )
                        )
                    }
                }

                // Step count: '1 / 4'
                Text(
                    text = "${stepNumber ?: 1} / $totalSteps",
                    color = textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 4. In-bubble Contextual Explanation Card (if open)
        if (isExplainBusy || !explanationText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(surfaceColor, RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF3E4652), RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "💡", fontSize = 11.sp)
                        Text(
                            text = "Concept Explanation",
                            color = primaryOrange,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "✕",
                        color = textMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { onDismissExplanation() }
                            .padding(2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(5.dp))

                if (isExplainBusy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            color = primaryOrange,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Querying intelligent Blender knowledge engine...",
                            color = textMuted,
                            fontSize = 11.sp
                        )
                    }
                } else if (explanationText != null) {
                    Text(
                        text = explanationText,
                        color = Color(0xFFF1F5F9),
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // 5. Highlight Feedback Banner (if active)
        if (!highlightFeedback.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = highlightFeedback,
                color = if (highlightFeedback.startsWith("✓")) Color(0xFF4ADE80) else Color(0xFFFFA757),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 6. Action Buttons Matching the Reference
        if (isGreeting) {
            // Stage 1: Application Start (Start a tutorial | Maybe later)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1.2f)
                        .background(primaryOrange, RoundedCornerShape(12.dp))
                        .clickable { onStartTutorialClick() }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Start a tutorial",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1.0f)
                        .background(surfaceColor, RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF3E4652), RoundedCornerShape(12.dp))
                        .clickable { onDismissExplanation() }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Maybe later",
                        color = textMuted,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.5.sp
                    )
                }
            }
        } else if (isOffline) {
            // Offline / Disconnected: Retry connection button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(surfaceColor, RoundedCornerShape(12.dp))
                    .border(1.dp, primaryOrange.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .clickable { onHighlightClick() }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Retry Connection",
                    color = primaryOrange,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.5.sp
                )
            }
        } else {
            // Stage 2 & 4: Highlight Action + Explain More Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Primary Action Button (Highlight Target)
                val highlightBtnLabel = if (!targetControlName.isNullOrBlank()) {
                    "Highlight $targetControlName"
                } else {
                    "Highlight target"
                }

                Box(
                    modifier = Modifier
                        .weight(1.3f)
                        .background(Color(0xFF2B1D15), RoundedCornerShape(12.dp))
                        .border(1.5.dp, primaryOrange, RoundedCornerShape(12.dp))
                        .clickable(enabled = !isHighlightBusy) { onHighlightClick() }
                        .padding(vertical = 8.dp, horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isHighlightBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            color = primaryOrange,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "🎯", fontSize = 11.sp)
                            Text(
                                text = highlightBtnLabel,
                                color = primaryOrange,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // Secondary Action Button (Explain More)
                Box(
                    modifier = Modifier
                        .weight(1.0f)
                        .background(surfaceColor, RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF3E4652), RoundedCornerShape(12.dp))
                        .clickable(enabled = !isExplainBusy) { onExplainClick() }
                        .padding(vertical = 8.dp, horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "📖", fontSize = 11.sp)
                        Text(
                            text = "Explain more",
                            color = textWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
