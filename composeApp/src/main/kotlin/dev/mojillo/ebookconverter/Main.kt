package dev.mojillo.ebookconverter

import androidx.compose.ui.window.application
import java.awt.Taskbar
import javax.imageio.ImageIO

fun main() {
    installDockIcon()
    application {
        ConverterWindow(onClose = ::exitApplication)
    }
}

private fun installDockIcon() {
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png") ?: return
    val image = stream.use { ImageIO.read(it) } ?: return
    if (!Taskbar.isTaskbarSupported()) return
    val taskbar = Taskbar.getTaskbar()
    if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
        taskbar.iconImage = image
    }
}
