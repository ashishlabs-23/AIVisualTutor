package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import models.TutorStep
import pedagogy.AdaptiveInstruction
import pedagogy.GuidanceStage
import pedagogy.PedagogicalOutcome

import androidx.compose.material.OutlinedTextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Displays the current step's step counter, adaptive guidance stage,
 * instruction text, and pedagogical feedback inside a rounded card.
 */
@Composable
fun InstructionCard(
    step: TutorStep,
    modifier: Modifier = Modifier,
    adaptiveInstruction: AdaptiveInstruction? = null,
    guidanceStage: GuidanceStage? = null,
    lastOutcome: PedagogicalOutcome? = null,
    competenceEstimate: Double? = null,
    onSubmitAnswer: (String) -> Unit = {},
    onStuck: () -> Unit = {}
) {
    val activeStage = guidanceStage ?: adaptiveInstruction?.stage ?: GuidanceStage.STAGE_1_EXACT_GUIDANCE
    val displayText = adaptiveInstruction?.instructionText ?: step.instruction
    val descriptionText = when (activeStage) {
        GuidanceStage.STAGE_1_EXACT_GUIDANCE -> step.description
        GuidanceStage.STAGE_2_CONCEPTUAL_HINT -> adaptiveInstruction?.conceptSummary ?: step.description
        GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION -> step.reflectionQuestion ?: "Reflect on the required action before checking the solution."
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(color = Color(0xFF2B2F38), shape = RoundedCornerShape(10.dp))
            .padding(14.dp)
    ) {
        // Header row with step counter and active guidance badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Step ${step.stepNumber} of ${step.totalSteps}",
                color = Color(0xFFAAB0BD),
                fontSize = 12.sp
            )

            GuidanceStageBadge(stage = activeStage)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Main instruction text
        Text(
            text = displayText,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Context / Description
        Text(
            text = descriptionText,
            color = Color(0xFFCFD3DA),
            fontSize = 13.sp,
            lineHeight = 17.sp
        )

        // Stage 3 interactive learner answer response
        if (activeStage == GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION) {
            Spacer(modifier = Modifier.height(10.dp))
            var answerText by remember { mutableStateOf("") }

            OutlinedTextField(
                value = answerText,
                onValueChange = { answerText = it },
                label = { Text("Your answer / command", color = Color(0xFFAAB0BD), fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = Color.White,
                    focusedBorderColor = Color(0xFF8B5CF6),
                    unfocusedBorderColor = Color(0xFF4B5563),
                    cursorColor = Color(0xFFA78BFA)
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TutorButton(
                    text = "Submit Answer",
                    onClick = {
                        if (answerText.isNotBlank()) {
                            onSubmitAnswer(answerText)
                            answerText = ""
                        }
                    },
                    enabled = answerText.isNotBlank()
                )
                TutorButton(
                    text = "I'm Stuck",
                    onClick = { onStuck() }
                )
            }
        }

        // Verification outcome feedback tag
        if (lastOutcome != null) {
            Spacer(modifier = Modifier.height(10.dp))
            VerificationFeedbackTag(outcome = lastOutcome, competence = competenceEstimate)
        }
    }
}

@Composable
private fun GuidanceStageBadge(stage: GuidanceStage) {
    val (badgeText, badgeColor, borderColor) = when (stage) {
        GuidanceStage.STAGE_1_EXACT_GUIDANCE ->
            Triple("Stage 1: Direct", Color(0xFF38BDF8), Color(0xFF0284C7))
        GuidanceStage.STAGE_2_CONCEPTUAL_HINT ->
            Triple("Stage 2: Hint", Color(0xFFFBBF24), Color(0xFFD97706))
        GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION ->
            Triple("Stage 3: Question", Color(0xFFA78BFA), Color(0xFF7C3AED))
    }

    Box(
        modifier = Modifier
            .background(color = badgeColor.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp))
            .border(width = 1.dp, color = borderColor.copy(alpha = 0.5f), shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = badgeText,
            color = badgeColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun VerificationFeedbackTag(outcome: PedagogicalOutcome, competence: Double?) {
    val (icon, label, color) = when (outcome) {
        PedagogicalOutcome.VERIFIED_SUCCESS -> Triple("✓", "Verified Success", Color(0xFF4ADE80))
        PedagogicalOutcome.VERIFIED_FAILURE -> Triple("✗", "Unverified / Action Not Detected", Color(0xFFF87171))
        PedagogicalOutcome.UNCERTAIN -> Triple("?", "Verification Inconclusive", Color(0xFFFACC15))
        PedagogicalOutcome.NOT_EVALUATED -> Triple("○", "Not Evaluated", Color(0xFFAAB0BD))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = color.copy(alpha = 0.10f), shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$icon $label",
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        if (competence != null) {
            Text(
                text = "Competence: ${(competence * 100).toInt()}%",
                color = Color(0xFFAAB0BD),
                fontSize = 10.sp
            )
        }
    }
}
