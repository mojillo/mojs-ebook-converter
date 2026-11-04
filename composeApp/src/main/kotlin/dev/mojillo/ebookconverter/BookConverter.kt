package dev.mojillo.ebookconverter

import org.jsoup.Jsoup
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

enum class OutputFormat(val extension: String, val label: String) {
    EPUB("epub", "EPUB"),
    HTML("html", "HTML"),
    TXT("txt", "TXT"),
}

data class ConvertResult(val output: Path, val replacements: Int)

class UnsupportedBookException(message: String) : Exception(message)

fun convertBook(source: Path, format: OutputFormat, removeDividers: Boolean): ConvertResult {
    val extension = source.fileName.toString().substringAfterLast('.', "").lowercase()
    return when (extension) {
        "epub" -> convertEpub(source, format, removeDividers)
        "html", "htm" -> convertHtmlFile(source, format, removeDividers)
        "txt" -> convertTextFile(source, format, removeDividers)
        "fb2", "mobi", "azw", "azw3", "pdf" -> throw UnsupportedBookException(
            "${extension.uppercase()} is not in this version yet. This version converts EPUB, HTML, and TXT.",
        )
        else -> throw UnsupportedBookException("Unsupported file. This version converts EPUB, HTML, and TXT.")
    }
}

internal fun outputPathFor(source: Path, format: OutputFormat): Path {
    val parent = source.parent ?: Path.of(".")
    val stem = source.fileName.toString().substringBeforeLast('.')
    var candidate = parent.resolve("${stem}_fixed.${format.extension}")
    var number = 2
    while (Files.exists(candidate)) {
        candidate = parent.resolve("${stem}_fixed $number.${format.extension}")
        number += 1
    }
    return candidate
}

private fun convertEpub(source: Path, format: OutputFormat, removeDividers: Boolean): ConvertResult {
    val temp = createTempDirectory("moj-epub")
    try {
        unzipEpub(source, temp)
        val replacements = if (removeDividers) fixEpubTree(temp) else 0
        val output = outputPathFor(source, format)
        val title = source.fileName.toString().substringBeforeLast('.')
        when (format) {
            OutputFormat.EPUB -> zipEpub(temp, output)
            OutputFormat.HTML -> output.writeText(chaptersToHtml(temp, title))
            OutputFormat.TXT -> output.writeText(chaptersToText(temp))
        }
        return ConvertResult(output, replacements)
    } finally {
        temp.toFile().deleteRecursively()
    }
}

private fun convertHtmlFile(source: Path, format: OutputFormat, removeDividers: Boolean): ConvertResult {
    val original = readText(source)
    val root = source.parent ?: Path.of(".")
    val (html, replacements) = if (removeDividers) {
        processHtml(original, source, root, indexImages(root))
    } else {
        original to 0
    }
    val output = outputPathFor(source, format)
    val title = source.fileName.toString().substringBeforeLast('.')
    when (format) {
        OutputFormat.HTML -> output.writeText(html)
        OutputFormat.TXT -> output.writeText(htmlToText(html))
        OutputFormat.EPUB -> writeSimpleEpub(output, title, bodyInnerHtml(html))
    }
    return ConvertResult(output, replacements)
}

private fun convertTextFile(source: Path, format: OutputFormat, removeDividers: Boolean): ConvertResult {
    val text = readText(source)
    val output = outputPathFor(source, format)
    val title = source.fileName.toString().substringBeforeLast('.')
    when (format) {
        OutputFormat.TXT -> output.writeText(text)
        OutputFormat.HTML -> output.writeText(textToHtml(text, title))
        OutputFormat.EPUB -> writeSimpleEpub(output, title, textToBody(text))
    }
    return ConvertResult(output, 0)
}

private fun chaptersToHtml(root: Path, title: String): String {
    val bodies = spineHtmlFiles(root).map { bodyInnerHtml(readText(it)) }
    val body = bodies.joinToString("\n")
    return """<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>${escapeHtml(title)}</title></head>
<body>
$body
</body>
</html>
"""
}

private fun chaptersToText(root: Path): String {
    return spineHtmlFiles(root).joinToString("\n\n") { htmlToText(readText(it)) }.trim() + "\n"
}

private fun bodyInnerHtml(html: String): String = Jsoup.parse(html).body().html()

internal fun htmlToText(html: String): String {
    val body = Jsoup.parse(html).body()
    val blocks = body.select("p, h1, h2, h3, h4, li")
    val text = if (blocks.isEmpty()) body.text() else blocks.joinToString("\n\n") { it.text() }
    return text.trim()
}

internal fun textToHtml(text: String, title: String): String {
    return """<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>${escapeHtml(title)}</title></head>
<body>
${textToBody(text)}
</body>
</html>
"""
}

private fun textToBody(text: String): String {
    val paragraphs = text.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (paragraphs.isEmpty()) return "<p></p>"
    return paragraphs.joinToString("\n") { paragraph ->
        "<p>${escapeHtml(paragraph).replace("\n", "<br/>")}</p>"
    }
}

private fun writeSimpleEpub(destination: Path, title: String, bodyHtml: String) {
    val temp = createTempDirectory("moj-build-epub")
    try {
        temp.resolve("META-INF").createDirectories()
        temp.resolve("OEBPS").createDirectories()
        temp.resolve("mimetype").writeText("application/epub+zip")
        temp.resolve("META-INF/container.xml").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
""",
        )
        temp.resolve("OEBPS/content.opf").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>${escapeHtml(title)}</dc:title>
    <dc:language>en</dc:language>
    <dc:identifier id="uid">urn:uuid:${UUID.randomUUID()}</dc:identifier>
  </metadata>
  <manifest>
    <item id="chap" href="chapter.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="chap"/>
  </spine>
</package>
""",
        )
        temp.resolve("OEBPS/chapter.xhtml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>${escapeHtml(title)}</title></head>
<body>
$bodyHtml
</body>
</html>
""",
        )
        zipEpub(temp, destination)
    } finally {
        temp.toFile().deleteRecursively()
    }
}

private fun escapeHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
