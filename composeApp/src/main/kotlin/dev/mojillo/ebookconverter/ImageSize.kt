package dev.mojillo.ebookconverter

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

internal data class PixelSize(val width: Int, val height: Int)

private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp")

internal fun imageSize(path: Path): PixelSize? {
    val data = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return null
    return imageSize(data, path.fileName.toString().substringAfterLast('.', "").lowercase())
}

internal fun imageSize(data: ByteArray, extension: String): PixelSize? {
    val sized = when (extension) {
        "png" -> pngSize(data)
        "jpg", "jpeg" -> jpegSize(data)
        "gif" -> gifSize(data)
        "webp" -> webpSize(data)
        "svg" -> svgSize(data)
        else -> null
    }
    return sized ?: pngSize(data) ?: jpegSize(data) ?: gifSize(data) ?: webpSize(data) ?: svgSize(data)
}

internal fun isImageFile(path: Path): Boolean {
    val extension = path.fileName.toString().substringAfterLast('.', "").lowercase()
    return extension in imageExtensions
}

internal fun pngSize(data: ByteArray): PixelSize? {
    if (data.size < 24) return null
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    if (!data.copyOfRange(0, 8).contentEquals(signature)) return null
    val buffer = ByteBuffer.wrap(data, 16, 8).order(ByteOrder.BIG_ENDIAN)
    return PixelSize(buffer.int, buffer.int)
}

internal fun gifSize(data: ByteArray): PixelSize? {
    if (data.size < 10) return null
    val header = String(data, 0, 6, Charsets.US_ASCII)
    if (header != "GIF87a" && header != "GIF89a") return null
    val buffer = ByteBuffer.wrap(data, 6, 4).order(ByteOrder.LITTLE_ENDIAN)
    return PixelSize(buffer.short.toInt() and 0xFFFF, buffer.short.toInt() and 0xFFFF)
}

internal fun jpegSize(data: ByteArray): PixelSize? {
    if (data.size < 4 || data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte()) return null
    var index = 2
    while (index + 9 < data.size) {
        if (data[index] != 0xFF.toByte()) {
            index += 1
            continue
        }
        val marker = data[index + 1].toInt() and 0xFF
        if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2 || marker == 0xC3) {
            val buffer = ByteBuffer.wrap(data, index + 5, 4).order(ByteOrder.BIG_ENDIAN)
            val height = buffer.short.toInt() and 0xFFFF
            val width = buffer.short.toInt() and 0xFFFF
            return PixelSize(width, height)
        }
        if (marker == 0xD8 || marker == 0xD9 || marker == 0x01 || marker in 0xD0..0xD7) {
            index += 2
            continue
        }
        if (index + 3 >= data.size) break
        val length = ByteBuffer.wrap(data, index + 2, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        index += 2 + length
    }
    return null
}

internal fun webpSize(data: ByteArray): PixelSize? {
    if (data.size < 30) return null
    if (String(data, 0, 4, Charsets.US_ASCII) != "RIFF") return null
    if (String(data, 8, 4, Charsets.US_ASCII) != "WEBP") return null
    val kind = String(data, 12, 4, Charsets.US_ASCII)
    return when (kind) {
        "VP8 " -> {
            val buffer = ByteBuffer.wrap(data, 26, 4).order(ByteOrder.LITTLE_ENDIAN)
            PixelSize(buffer.short.toInt() and 0x3FFF, buffer.short.toInt() and 0x3FFF)
        }
        "VP8L" -> {
            val bits = ByteBuffer.wrap(data, 21, 4).order(ByteOrder.LITTLE_ENDIAN).int
            PixelSize((bits and 0x3FFF) + 1, ((bits shr 14) and 0x3FFF) + 1)
        }
        "VP8X" -> {
            val width = 1 + (data[24].toInt() and 0xFF) + ((data[25].toInt() and 0xFF) shl 8) + ((data[26].toInt() and 0xFF) shl 16)
            val height = 1 + (data[27].toInt() and 0xFF) + ((data[28].toInt() and 0xFF) shl 8) + ((data[29].toInt() and 0xFF) shl 16)
            PixelSize(width, height)
        }
        else -> null
    }
}

internal fun svgSize(data: ByteArray): PixelSize? {
    val text = String(data, Charsets.UTF_8)
    val viewBox = Regex("""viewBox\s*=\s*["']([\d.\s-]+)["']""", RegexOption.IGNORE_CASE).find(text)
    if (viewBox != null) {
        val parts = viewBox.groupValues[1].trim().split(Regex("\\s+"))
        if (parts.size == 4) {
            val width = parts[2].toFloatOrNull()?.roundToInt()
            val height = parts[3].toFloatOrNull()?.roundToInt()
            if (width != null && height != null && width > 0 && height > 0) {
                return PixelSize(width, height)
            }
        }
    }
    val width = Regex("""\bwidth\s*=\s*["']([\d.]+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toFloatOrNull()
    val height = Regex("""\bheight\s*=\s*["']([\d.]+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toFloatOrNull()
    if (width != null && height != null && width > 0 && height > 0) {
        return PixelSize(width.roundToInt(), height.roundToInt())
    }
    return null
}
