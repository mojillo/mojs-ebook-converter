package dev.mojillo.ebookconverter

import org.jsoup.Jsoup
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory

class ChapterExportException(message: String) : Exception(message)

internal data class BookSection(
    val index: Int,
    val title: String?,
    val text: String,
)

fun exportChapters(
    source: Path,
    onProgress: (String) -> Unit = {},
): Path {
    if (!Files.isRegularFile(source)) {
        throw ChapterExportException("That file is not available.")
    }
    val extension = source.fileName.toString().substringAfterLast('.', "").lowercase()
    val sections = when (extension) {
        "epub" -> sectionsFromEpub(source)
        "html", "htm" -> listOf(sectionFromHtml(readText(source), index = 1))
        "txt" -> listOf(sectionFromText(readText(source)))
        "fb2", "mobi", "azw", "azw3" -> sectionsFromCalibre(source)
        "pdf" -> throw UnsupportedBookException("PDF is not supported.")
        else -> throw UnsupportedBookException("Unsupported file. This version exports chapters from EPUB, HTML, and TXT.")
    }
    return writeChapterFiles(source, sections, onProgress)
}

internal fun bookSections(root: Path): List<BookSection> {
    return spineHtmlFiles(root).mapIndexed { offset, file ->
        sectionFromHtml(readText(file), offset + 1)
    }
}

internal fun chapterTitle(html: String): String? {
    val heading = Jsoup.parse(html).selectFirst("h1, h2, h3, h4")?.text()?.trim().orEmpty()
    return heading.ifEmpty { null }
}

internal fun chapterFileName(number: Int, title: String?): String {
    val prefix = number.toString().padStart(3, '0')
    val cleaned = title?.let(::sanitizeFileName).orEmpty()
    return if (cleaned.isEmpty()) "$prefix.txt" else "$prefix $cleaned.txt"
}

internal fun sanitizeFileName(name: String): String {
    return name
        .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trim('.', ' ')
        .take(80)
        .trim('.', ' ')
}

internal fun chapterFolder(source: Path): Path {
    val parent = source.parent ?: Path.of(".")
    val stem = source.fileName.toString().substringBeforeLast('.')
    var candidate = parent.resolve("${stem}_chapters")
    var number = 2
    while (Files.exists(candidate)) {
        candidate = parent.resolve("${stem}_chapters $number")
        number += 1
    }
    return candidate
}

private fun sectionsFromEpub(source: Path): List<BookSection> {
    val temp = createTempDirectory("moj-chapters")
    try {
        unzipEpub(source, temp)
        return bookSections(temp)
    } finally {
        temp.toFile().deleteRecursively()
    }
}

private fun sectionsFromCalibre(source: Path): List<BookSection> {
    val converter = findEbookConvert() ?: throw UnsupportedBookException(
        "${source.fileName.toString().substringAfterLast('.').uppercase()} needs Calibre. $calibreMissingMessage",
    )
    val staging = createTempDirectory("moj-calibre-chapters")
    try {
        val epub = staging.resolve("source.epub")
        runEbookConvert(converter, source, epub)
        return sectionsFromEpub(epub)
    } finally {
        staging.toFile().deleteRecursively()
    }
}

private fun sectionFromHtml(html: String, index: Int): BookSection {
    val text = htmlToText(html).trim()
    return BookSection(index, chapterTitle(html), text)
}

private fun sectionFromText(text: String): BookSection {
    return BookSection(1, null, text.trim())
}

private fun writeChapterFiles(
    source: Path,
    sections: List<BookSection>,
    onProgress: (String) -> Unit,
): Path {
    val chapters = sections.filter { it.text.isNotBlank() }
    if (chapters.isEmpty()) {
        throw ChapterExportException("That book has no chapter text.")
    }
    val folder = chapterFolder(source)
    folder.createDirectories()
    chapters.forEachIndexed { offset, section ->
        val file = folder.resolve(chapterFileName(offset + 1, section.title))
        onProgress("Writing ${file.fileName} (${offset + 1}/${chapters.size})")
        Files.writeString(file, section.text + "\n", StandardCharsets.UTF_8)
    }
    return folder
}
