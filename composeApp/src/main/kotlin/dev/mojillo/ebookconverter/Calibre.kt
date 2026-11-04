package dev.mojillo.ebookconverter

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

internal const val calibreDownloadUrl = "https://calibre-ebook.com/download"

internal const val calibreFoundMessage = "AZW3 uses the Calibre installed on this computer."

internal const val calibreMissingMessage =
    "AZW3 needs Calibre. Install it from $calibreDownloadUrl and open the app again."

fun findEbookConvert(): Path? = firstExecutable(calibreCandidates())

internal fun firstExecutable(candidates: List<Path>): Path? {
    return candidates.firstOrNull { it.isRegularFile() && it.isExecutable() }
}

internal fun calibreCandidates(): List<Path> {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val home = Path(System.getProperty("user.home"))
    val command = if (os.contains("win")) "ebook-convert.exe" else "ebook-convert"
    val fromPath = System.getenv("PATH").orEmpty()
        .split(java.io.File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { Path(it).resolve(command) }
    val installed = when {
        os.contains("mac") -> listOf(
            Path("/Applications/calibre.app/Contents/MacOS/ebook-convert"),
            home.resolve("Applications/calibre.app/Contents/MacOS/ebook-convert"),
        )
        os.contains("win") -> listOf(
            Path("C:/Program Files/Calibre2/ebook-convert.exe"),
            Path("C:/Program Files (x86)/Calibre2/ebook-convert.exe"),
        )
        else -> listOf(Path("/usr/bin/ebook-convert"))
    }
    return fromPath + installed
}

internal fun runEbookConvert(executable: Path, source: Path, destination: Path) {
    val command = mutableListOf(executable.toString(), source.toString(), destination.toString())
    if (destination.fileName.toString().endsWith(".azw3", ignoreCase = true)) {
        command += listOf("--output-profile", "kindle")
    }
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val log = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()
    if (code != 0 || !destination.exists()) {
        val detail = log.trim().ifBlank { "exit code $code" }
        throw RuntimeException("ebook-convert failed (${source.fileName} → ${destination.fileName}):\n$detail")
    }
}

private fun requireCalibre(path: Path?): Path {
    return path ?: throw UnsupportedBookException(calibreMissingMessage)
}

internal fun calibreOrThrow(path: Path?): Path = requireCalibre(path)
