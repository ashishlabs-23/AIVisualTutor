package ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

@Composable
fun ScreenshotPreviewWindow(
    pngPath: String,
    onCloseRequest: () -> Unit
) {
    val windowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        size = DpSize(900.dp, 700.dp)
    )

    var bitmap by remember(pngPath) { mutableStateOf<ImageBitmap?>(null) }
    var loadError by remember(pngPath) { mutableStateOf<String?>(null) }

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
