package me.nathanfallet.aiplayspokemon.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import me.nathanfallet.aiplayspokemon.emulator.Emulator
import me.nathanfallet.aiplayspokemon.emulator.Frame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Displays the emulator's frames, scaled with sharp pixels, and forwards mouse clicks as touches
 * (on the DS, the bottom half of the frame is the touch screen).
 */
@Composable
fun EmulatorScreen(emulator: Emulator, modifier: Modifier = Modifier) {
    val frame by emulator.frames.collectAsState()
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val current = frame ?: return@Box
        val bitmap = remember(current) { current.toImageBitmap() }
        Image(
            bitmap = bitmap,
            contentDescription = "Game screen",
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
            modifier = Modifier
                .aspectRatio(current.width.toFloat() / current.height)
                .fillMaxSize()
                .pointerInput(emulator) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val position = event.changes.first().position
                            val pressed = event.buttons.isPrimaryPressed &&
                                event.type in setOf(PointerEventType.Press, PointerEventType.Move)
                            emulator.touch(
                                if (pressed) (position.x / size.width).coerceIn(0f, 1f) to (position.y / size.height).coerceIn(0f, 1f)
                                else null
                            )
                        }
                    }
                },
        )
    }
}

/** Frame pixels are ARGB ints; Skia's N32 format on little-endian machines is BGRA bytes, i.e. the same memory layout. */
private fun Frame.toImageBitmap(): ImageBitmap {
    val bytes = ByteBuffer.allocate(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    bytes.asIntBuffer().put(pixels)
    val info = ImageInfo.makeN32(width, height, ColorAlphaType.OPAQUE)
    return Image.makeRaster(info, bytes.array(), width * 4).toComposeImageBitmap()
}
