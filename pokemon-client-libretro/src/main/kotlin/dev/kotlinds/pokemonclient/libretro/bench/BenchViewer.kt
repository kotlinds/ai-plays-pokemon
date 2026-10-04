package dev.kotlinds.pokemonclient.libretro.bench

import dev.kotlinds.pokemonclient.console.Frame
import java.awt.Dimension
import java.awt.Graphics
import java.awt.image.BufferedImage
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Optional live window for the bench (`BENCH_WINDOW=1`): shows every emulated frame, paced at the DS's ~60 fps
 * so a human can watch what the actions do. Never plays sound. Closed when the commands are done.
 */
internal class BenchViewer(title: String, private val scale: Int = 2) {
    private var image: BufferedImage? = null
    private var lastFrameAt = System.nanoTime()

    private val panel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            image?.let { g.drawImage(it, 0, 0, it.width * scale, it.height * scale, null) }
        }
    }

    private lateinit var window: JFrame

    init {
        SwingUtilities.invokeAndWait {
            panel.preferredSize = Dimension(256 * scale, 384 * scale)
            window = JFrame(title).apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                contentPane.add(panel)
                pack()
                isVisible = true
            }
        }
    }

    /** Shows [frame], waiting so frames go by at the console's speed. */
    fun show(frame: Frame) {
        val next = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
        next.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
        image = next
        panel.repaint()
        val wait = FRAME_NANOS - (System.nanoTime() - lastFrameAt)
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        lastFrameAt = System.nanoTime()
    }

    /** Closes the window (the bench's JVM can then exit). */
    fun close() = SwingUtilities.invokeLater { window.dispose() }

    private companion object {
        const val FRAME_NANOS = 1_000_000_000L / 60
    }
}
