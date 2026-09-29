package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import models.TutorStep

/**
 * Displays the current step's step counter, instruction and description
 * inside a rounded card, matching the tutor panel mock-up.
 */
@Composable
fun InstructionCard(step: TutorStep, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(color = Color(0xFF2B2F38), shape = RoundedCornerShape(10.dp))
            .padding(14.dp)
    ) {
        Text(
            text = "Step ${step.stepNumber} of ${step.totalSteps}",
            color = Color(0xFFAAB0BD),
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = step.instruction,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = step.description,
            color = Color(0xFFCFD3DA),
            fontSize = 13.sp
        )
    }
}
