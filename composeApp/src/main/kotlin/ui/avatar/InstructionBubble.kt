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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tutor.TutorConversationState

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
    isTailOnRight: Boolean = false,
    conversationState: TutorConversationState = TutorConversationState.Idle,
    onFollowUpSubmit: (String) -> Unit = {},
    onCancelQuery: () -> Unit = {},
    onRetryQuery: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val primaryOrange = Color(0xFFF5792A)
    val darkUiBackground = Color(0xF21E2127) // 95% opacity graphite
    val surfaceColor = Color(0xFF282F38)
    val borderColor = Color(0xFF2D323B)
    val textWhite = Color(0xFFF1F5F9)
    val textMuted = Color(0xFF8A94A6)

    val isThinkingQuery = conversationState is TutorConversationState.Thinking
    val thinkingQueryText = (conversationState as? TutorConversationState.Thinking)?.queryText
    val isError = conversationState is TutorConversationState.Error
    var isErrorDismissed by remember(conversationState) { mutableStateOf(false) }
    var followUpDraft by remember { mutableStateOf("") }

    val isOffline = statusLabel.contains("Offline", ignoreCase = true) || statusLabel.contains("No Blender", ignoreCase = true)
    val isSuccess = statusLabel.contains("Great job", ignoreCase = true) || statusLabel.contains("Success", ignoreCase = true)
    val isThinking = statusLabel.contains("Thinking", ignoreCase = true) || isHighlightBusy || isExplainBusy || isThinkingQuery
    val isGreeting = (stepNumber == null || stepNumber == 0) && instructionText.isNullOrBlank() && !isOffline

    Column(
        modifier = modifier
            .widthIn(min = 280.dp, max = 390.dp)
            .drawBehind {
                // Speech bubble pointer tail pointing toward avatar (left or right)
                val tailPath = Path().apply {
                    if (!isTailOnRight) {
                        moveTo(0f, 40f)
                        lineTo(-14f, 50f)
                        lineTo(0f, 60f)
                    } else {
                        val bubbleW = size.width
                        moveTo(bubbleW, 40f)
                        lineTo(bubbleW + 14f, 50f)
                        lineTo(bubbleW, 60f)
                    }
                    close()
                }
                drawPath(tailPath, color = darkUiBackground)
                drawPath(
                    tailPath,
                    color = if (isSuccess) Color(0xFF22C55E).copy(alpha = 0.6f) else borderColor,
                    style = Stroke(width = 1f)
                )
            }
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

        // 4. In-bubble Contextual Explanation / Thinking / Error Card (if open)
        val isCardVisible = isExplainBusy || isThinkingQuery || !explanationText.isNullOrBlank() || (isError && !isErrorDismissed)
        if (isCardVisible) {
            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 140.dp)
                    .background(
                        color = if (isError && !isErrorDismissed) Color(0x28EF4444) else surfaceColor,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isError && !isErrorDismissed) Color(0x66EF4444) else Color(0xFF3E4652),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (isExplainBusy || isThinkingQuery) {
                    // Thinking State with Compact Spinner and Cancel Action
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                color = primaryOrange,
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Tutor is thinking...",
                                color = primaryOrange,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Explicit Cancel Control
                        Box(
                            modifier = Modifier
                                .background(Color(0x33EF4444), RoundedCornerShape(6.dp))
                                .border(1.dp, Color(0x66EF4444), RoundedCornerShape(6.dp))
                                .clickable { onCancelQuery() }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Cancel",
                                color = Color(0xFFFCA5A5),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (thinkingQueryText != null && !thinkingQueryText.startsWith("Explain step:")) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "“$thinkingQueryText”",
                            color = textMuted,
                            fontSize = 11.sp,
                            fontStyle = FontStyle.Italic
                        )
                    }
                } else if (isError && !isErrorDismissed) {
                    // Error State with Sanitized Message and Retry Action
                    val errorState = conversationState as TutorConversationState.Error
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(text = "⚠️", fontSize = 11.sp)
                            Text(
                                text = "Tutor Unavailable",
                                color = Color(0xFFFCA5A5),
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
                                .clickable {
                                    isErrorDismissed = true
                                    onDismissExplanation()
                                }
                                .padding(2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = sanitizeErrorMessage(errorState.message),
                        color = Color(0xFFFEE2E2),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    if (errorState.canRetry) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .background(Color(0x40EF4444), RoundedCornerShape(6.dp))
                                .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(6.dp))
                                .clickable { onRetryQuery() }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Retry",
                                color = Color.White,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else if (!explanationText.isNullOrBlank()) {
                    // Tutor Response Presentation
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(text = "💡", fontSize = 11.sp)
                            Text(
                                text = "Tutor Response",
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

        // 7. Inline Follow-Up Input Area (Phase 4.2)
        if (!isGreeting && !isOffline) {
            Spacer(modifier = Modifier.height(8.dp))

            val isInputDisabled = isThinking
            val canSend = followUpDraft.trim().isNotBlank() && !isInputDisabled

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF232830), RoundedCornerShape(10.dp))
                    .border(
                        width = 1.dp,
                        color = if (isInputDisabled) Color(0xFF353C47) else Color(0xFF3E4652),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = followUpDraft,
                    onValueChange = { followUpDraft = it },
                    enabled = !isInputDisabled,
                    singleLine = false,
                    maxLines = 3,
                    textStyle = TextStyle(
                        color = if (!isInputDisabled) Color(0xFFF1F5F9) else textMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Default
                    ),
                    cursorBrush = SolidColor(primaryOrange),
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Enter) {
                                if (keyEvent.isShiftPressed) {
                                    // Shift + Enter: allow multiline newline insertion
                                    false
                                } else {
                                    // Enter alone: submit trimmed query if valid and not thinking
                                    val trimmed = followUpDraft.trim()
                                    if (trimmed.isNotBlank() && !isInputDisabled) {
                                        onFollowUpSubmit(trimmed)
                                        followUpDraft = ""
                                    }
                                    true // Consume Enter key event to prevent extra newline
                                }
                            } else {
                                false
                            }
                        },
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            if (followUpDraft.isEmpty()) {
                                Text(
                                    text = if (isInputDisabled) "Tutor is responding..." else "Ask a follow-up question...",
                                    color = Color(0xFF6B7280),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Normal
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Visible Send Control
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(
                            color = if (canSend) primaryOrange else Color(0xFF2D333D),
                            shape = RoundedCornerShape(6.dp)
                        )
                        .clickable(
                            enabled = canSend,
                            onClickLabel = "Send follow-up question"
                        ) {
                            val trimmed = followUpDraft.trim()
                            if (trimmed.isNotBlank() && !isInputDisabled) {
                                onFollowUpSubmit(trimmed)
                                followUpDraft = ""
                            }
                        }
                        .semantics {
                            contentDescription = "Send follow-up question"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "➤",
                        color = if (canSend) Color.White else Color(0xFF5A6372),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Sanitizes raw error output so stack traces, network dumps, and sensitive tokens
 * are never exposed in the compact desktop tutoring interface.
 */
internal fun sanitizeErrorMessage(rawMessage: String?): String {
    if (rawMessage.isNullOrBlank()) return "Unable to complete request. Please try again."
    val firstLine = rawMessage.lineSequence()
        .map { it.trim() }
        .firstOrNull { line ->
            line.isNotBlank() &&
                !line.startsWith("at ") &&
                !line.startsWith("... ") &&
                !line.startsWith("Caused by:")
        } ?: return "Unable to complete request. Please try again."

    // Strip Java/Kotlin package and exception class prefixes, e.g. "java.net.ConnectException: "
    val withoutExceptionPrefix = firstLine.replace(Regex("""^(?:[a-zA-Z0-9_.]+(?:Exception|Error):\s*)"""), "")

    // Strip sensitive tokens or auth strings if any
    val sanitized = withoutExceptionPrefix
        .replace(Regex("""(gsk_[a-zA-Z0-9_\-]+|AIza[a-zA-Z0-9_\-]+|Bearer\s+[a-zA-Z0-9_\.\-]+)""", RegexOption.IGNORE_CASE), "[REDACTED]")
        .replace(Regex("""https?://\S+"""), "")
        .trim()

    return if (sanitized.length > 120) {
        sanitized.take(117) + "..."
    } else if (sanitized.isNotBlank()) {
        sanitized
    } else {
        "Unable to complete request. Please try again."
    }
}
