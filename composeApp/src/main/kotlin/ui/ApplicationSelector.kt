package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import models.ApplicationType

/**
 * A row of three segmented buttons letting the user tell the tutor which
 * application they are working in. In Phase 1 this is manual - a later
 * phase will infer it automatically from the focused window.
 */
@Composable
fun ApplicationSelector(
    selected: ApplicationType,
    onSelect: (ApplicationType) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ApplicationType.values().forEach { app ->
            val isSelected = app == selected
            Row(
                modifier = Modifier
                    .background(
                        color = if (isSelected) MaterialTheme.colors.primary else Color(0xFF3A3F4B),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(app) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = app.displayName,
                    color = Color.White,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}
