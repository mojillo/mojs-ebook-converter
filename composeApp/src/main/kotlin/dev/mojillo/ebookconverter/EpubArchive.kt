package dev.mojillo.ebookconverter

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream

internal fun unzipEpub(zip: Path, destination: Path) {
    ZipFile(zip.toFile()).use { archive ->
        val root = destination.toAbsolutePath().normalize()
        for (entry in archive.entries()) {
            val output = root.resolve(entry.name).normalize()
            if (!output.startsWith(root)) continue
            if (entry.isDirectory) {
                output.createDirectories()
                continue
            }
            output.parent?.createDirectories()
            archive.getInputStream(entry).use { input ->
                Files.copy(input, output)
            }
        }
    }
}

internal fun zipEpub(root: Path, destination: Path) {
    destination.parent?.createDirectories()
    ZipOutputStream(destination.outputStream()).use { zip ->
        val mimetype = root.resolve("mimetype")
        if (Files.isRegularFile(mimetype)) {
            val bytes = Files.readAllBytes(mimetype)
            val entry = ZipEntry("mimetype")
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            entry.crc = CRC32().apply { update(bytes) }.value
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString() != "mimetype" }
                .sorted()
                .forEach { file ->
                    val name = root.relativize(file).toString().replace('\\', '/')
                    zip.putNextEntry(ZipEntry(name))
                    Files.copy(file, zip)
                    zip.closeEntry()
                }
        }
    }
}

internal fun htmlFiles(root: Path): List<Path> {
    return Files.walk(root).use { paths ->
        paths.filter { path ->
            Files.isRegularFile(path) && path.fileName.toString().substringAfterLast('.', "").lowercase() in setOf("html", "xhtml", "htm")
        }.sorted().toList()
    }
}

internal fun spineHtmlFiles(root: Path): List<Path> {
    val opf = findOpf(root) ?: return htmlFiles(root)
    val document = Jsoup.parse(opf.toFile(), "UTF-8", "", Parser.xmlParser())
    val manifest = document.select("item").associate { item ->
        item.attr("id") to item.attr("href")
    }
    val ordered = document.select("itemref").mapNotNull { reference ->
        val href = manifest[reference.attr("idref")] ?: return@mapNotNull null
        val decoded = runCatching {
            java.net.URLDecoder.decode(href, Charsets.UTF_8)
        }.getOrDefault(href)
        val path = opf.parent.resolve(decoded).normalize()
        path.takeIf { Files.isRegularFile(it) }
    }
    return ordered.ifEmpty { htmlFiles(root) }
}

private fun findOpf(root: Path): Path? {
    val container = root.resolve("META-INF/container.xml")
    if (Files.isRegularFile(container)) {
        val document = Jsoup.parse(container.toFile(), "UTF-8", "", Parser.xmlParser())
        val fullPath = document.select("rootfile").first()?.attr("full-path").orEmpty()
        if (fullPath.isNotEmpty()) {
            val opf = root.resolve(fullPath).normalize()
            if (Files.isRegularFile(opf)) return opf
        }
    }
    return Files.walk(root).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".opf") }.findFirst().orElse(null)
    }
}

internal fun fixEpubTree(root: Path, limits: Limits = Limits()): Int {
    val images = indexImages(root)
    var total = 0
    for (file in htmlFiles(root)) {
        val original = readText(file)
        val (fixed, count) = processHtml(original, file, root, images, limits)
        total += count
        if (count > 0) Files.writeString(file, fixed)
    }
    return total
}
