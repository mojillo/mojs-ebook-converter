package dev.mojillo.ebookconverter

import androidx.compose.ui.window.application

fun main() = application {
    ConverterWindow(onClose = ::exitApplication)
}
