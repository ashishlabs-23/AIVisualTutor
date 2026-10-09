package ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import context.ExpectedState
import context.VerificationOperation

@Composable
fun BlenderVerificationPanel(
    baselineExpected: ExpectedState?,
    isBusy: Boolean,
    feedback: String?,
    onCaptureBaseline: (ExpectedState) -> Unit,
    onVerifyAction: (ExpectedState) -> Unit
) {
    var operation by remember { mutableStateOf(VerificationOperation.CREATE) }
    var targetName by remember { mutableStateOf("Cube") }
    var targetType by remember { mutableStateOf("MESH") }
    var propertyName by remember { mutableStateOf("location") }
    var propertyValue by remember { mutableStateOf("1,2,3") }
    var menuExpanded by remember { mutableStateOf(false) }

    val expected = ExpectedState(
        operation = operation,
        targetName = targetName,
        targetType = targetType,
        targetProperty = propertyName.takeIf { operation == VerificationOperation.MODIFY },
        expectedPropertyValue = propertyValue.takeIf { operation == VerificationOperation.MODIFY }
    )
    val baselineMatches = baselineExpected == expected

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Blender state verification", color = Color.White, fontWeight = FontWeight.Bold)
        Text(
            "Capture Blender state, perform the action there, then verify the result.",
            color = Color(0xFFAAB0BD),
            style = MaterialTheme.typography.caption
        )

        Text("Operation: ${operation.name}", color = Color.White, modifier = Modifier.clickable { menuExpanded = true })
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            listOf(
                VerificationOperation.CREATE,
                VerificationOperation.DELETE,
                VerificationOperation.RENAME,
                VerificationOperation.SELECT,
                VerificationOperation.MODIFY
            ).forEach { option ->
                DropdownMenuItem(onClick = {
                    operation = option
                    menuExpanded = false
                }) {
                    Text(option.name)
                }
            }
        }

        VerificationTextField("Object name", targetName, { targetName = it })
        if (operation == VerificationOperation.CREATE || operation == VerificationOperation.RENAME) {
            VerificationTextField("Object type", targetType, { targetType = it })
        }
        if (operation == VerificationOperation.MODIFY) {
            VerificationTextField("Property", propertyName, { propertyName = it })
            VerificationTextField("Expected value", propertyValue, { propertyValue = it })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TutorButton(
                text = if (isBusy && !baselineMatches) "Capturing..." else "Capture baseline",
                enabled = !isBusy,
                onClick = { onCaptureBaseline(expected) }
            )
            TutorButton(
                text = if (isBusy && baselineMatches) "Verifying..." else "I completed it",
                enabled = !isBusy && baselineMatches,
                onClick = { onVerifyAction(expected) }
            )
        }
        if (feedback != null) {
            Text(feedback, color = Color(0xFFCFD3DA), style = MaterialTheme.typography.caption)
        }
    }
}

@Composable
private fun VerificationTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.textFieldColors(
            textColor = Color.White,
            cursorColor = MaterialTheme.colors.primary,
            focusedIndicatorColor = MaterialTheme.colors.primary,
            unfocusedIndicatorColor = Color(0xFF757B88),
            backgroundColor = Color(0xFF2C3039)
        )
    )
}
