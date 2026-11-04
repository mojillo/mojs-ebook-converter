package dev.mojillo.ebookconverter

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.roundToInt

internal data class Limits(
    val maxDividerHeight: Int = 80,
    val maxHeaderHeight: Int = 180,
    val minAspect: Double = 3.0,
    val minWidth: Int = 120,
    val maxOrnamentHeight: Int = 480,
    val ornamentMinWidth: Int = 480,
    val ornamentMinAspect: Double = 3.5,
)

private val separatorHints = listOf(
    "separator", "divider", "ornament", "flourish", "filigree", "asterism",
    "dinkus", "scenebreak", "scene-break", "scene_break", "rule", "hr_", "_hr",
    "break", "deco", "tribal", "swirl", "fleuron", "vine", "border",
    "chaphead", "chap-head", "chapterhead", "header-orn", "ornamental",
)

private val protectHints = listOf(
    "cover", "frontispiece", "portrait", "photo", "map", "diagram",
    "illustration", "figure", "comic", "panel", "logo-publisher",
)

private val wrapperTags = setOf("p", "div", "span", "center")

internal fun readText(path: Path): String {
    val bytes = Files.readAllBytes(path)
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charsets.ISO_8859_1)
    }
}

internal fun containsHint(text: String, hints: List<String> = separatorHints): Boolean {
    val lower = text.lowercase()
    return hints.any { matchesHint(lower, it.lowercase()) }
}

private fun matchesHint(lower: String, needle: String): Boolean {
    var start = 0
    while (start <= lower.length - needle.length) {
        val index = lower.indexOf(needle, start)
        if (index < 0) return false
        val beforeOk = index == 0 || !lower[index - 1].isLetterOrDigit()
        val afterIndex = index + needle.length
        val afterOk = afterIndex >= lower.length || !lower[afterIndex].isLetterOrDigit()
        if (beforeOk && afterOk) return true
        start = index + 1
    }
    return false
}

internal fun isSeparatorShape(
    width: Int?,
    height: Int?,
    widthIsPercent: Boolean,
    limits: Limits = Limits(),
): Boolean {
    if (height == null) return false
    if (height <= limits.maxDividerHeight) {
        if (widthIsPercent || width == null || width >= limits.minWidth) return true
        if (width.toDouble() / max(height, 1) >= limits.minAspect) return true
    }
    if (height <= limits.maxHeaderHeight && width != null) {
        val aspect = width.toDouble() / max(height, 1)
        if (aspect >= limits.minAspect && width >= limits.minWidth) return true
        if (widthIsPercent) return true
    }
    if (
        width != null &&
        height <= limits.maxOrnamentHeight &&
        width >= limits.ornamentMinWidth &&
        width.toDouble() / max(height, 1) >= limits.ornamentMinAspect
    ) {
        return true
    }
    return false
}

internal fun processHtml(
    html: String,
    htmlFile: Path,
    root: Path,
    images: Map<Path, PixelSize>,
    limits: Limits = Limits(),
): Pair<String, Int> {
    val xml = htmlFile.fileName.toString().lowercase().endsWith(".xhtml") ||
        html.take(800).contains("xmlns=")
    val document = if (xml) {
        Jsoup.parse(html, "", Parser.xmlParser())
    } else {
        Jsoup.parse(html)
    }
    document.outputSettings()
        .prettyPrint(false)
        .charset(Charsets.UTF_8)
    if (xml) {
        document.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
    }

    var replacements = 0
    for (image in document.select("img").toList()) {
        if (image.parent() == null) continue
        if (isSeparatorImage(image, htmlFile, root, images, limits)) {
            replaceSeparator(image)
            replacements += 1
        }
    }
    for (svg in document.select("svg").toList()) {
        if (svg.parent() == null) continue
        val width = parsePx(svg.attr("width"))
        val height = parsePx(svg.attr("height"))
        if (width != null && height != null && isSeparatorShape(width, height, false, limits)) {
            replaceSeparator(svg)
            replacements += 1
        }
    }
    if (replacements == 0) return html to 0
    return document.html() to replacements
}

private fun isSeparatorImage(
    tag: Element,
    htmlFile: Path,
    root: Path,
    images: Map<Path, PixelSize>,
    limits: Limits,
): Boolean {
    val src = tag.attr("src")
    val alt = tag.attr("alt").trim()
    val ident = tag.id()
    val blob = listOf(src, alt, tag.className(), ident, tag.parent()?.className().orEmpty())
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .lowercase()

    val namedSeparator = containsHint(blob, separatorHints)
    if (containsHint(blob, protectHints) && !namedSeparator) return false

    val (htmlWidth, htmlHeight, widthIsPercent) = declaredSize(tag)
    val pixelSize = resolveImage(htmlFile, src, root)?.let { images[it] }
    val width = htmlWidth ?: pixelSize?.width
    val height = htmlHeight ?: pixelSize?.height

    if (namedSeparator && (height == null || height <= limits.maxHeaderHeight * 1.5)) return true
    if (isSeparatorShape(width, height, widthIsPercent, limits)) return true

    val parent = tag.parent()
    if (alt.isEmpty() && parent != null && parent.tagName() in wrapperTags) {
        val siblings = parent.childNodes().filter { node ->
            when (node) {
                is TextNode -> node.text().isNotBlank()
                is Element -> node.tagName() != "br" && node != tag
                else -> false
            }
        }
        if (siblings.isEmpty() && parent.text().isBlank()) {
            if (height != null && height <= limits.maxHeaderHeight) return true
        }
    }
    return false
}

private fun declaredSize(tag: Element): Triple<Int?, Int?, Boolean> {
    val style = tag.attr("style")
    val width = parsePx(tag.attr("width").ifBlank { null }) ?: styleSize(style, "width")
    val height = parsePx(tag.attr("height").ifBlank { null }) ?: styleSize(style, "height")
    val styleLower = style.lowercase()
    var widthIsPercent = "width" in styleLower && "%" in styleLower
    if (tag.attr("width").trim().endsWith("%")) widthIsPercent = true
    return Triple(width, height, widthIsPercent)
}

private fun styleSize(style: String, property: String): Int? {
    if (style.isBlank()) return null
    val match = Regex("""${Regex.escape(property)}\s*:\s*([^;]+)""", RegexOption.IGNORE_CASE).find(style) ?: return null
    return parsePx(match.groupValues[1])
}

internal fun parsePx(value: String?): Int? {
    if (value.isNullOrBlank()) return null
    val text = value.trim().lowercase()
    if (text.endsWith("%")) return null
    val number = text.removeSuffix("px").removeSuffix("pt").trim().toFloatOrNull() ?: return null
    if (number <= 0f) return null
    return number.roundToInt()
}

private fun resolveImage(htmlFile: Path, src: String, root: Path): Path? {
    if (src.isBlank()) return null
    val cleaned = runCatching {
        URLDecoder.decode(src.substringBefore('?').substringBefore('#'), Charsets.UTF_8)
    }.getOrDefault(src.substringBefore('?').substringBefore('#')).trim()
    if (
        cleaned.isEmpty() ||
        cleaned.startsWith("http://") ||
        cleaned.startsWith("https://") ||
        cleaned.startsWith("data:")
    ) {
        return null
    }
    val candidate = htmlFile.parent.resolve(cleaned).normalize().toAbsolutePath()
    val rootAbsolute = root.toAbsolutePath().normalize()
    if (!candidate.startsWith(rootAbsolute)) return null
    return candidate.takeIf { Files.isRegularFile(it) }
}

private fun replaceSeparator(tag: Element) {
    val replacement = Element("p").attr("style", "text-align:center").text("* * *")
    val parent = tag.parent()
    if (parent != null && parent.tagName() in wrapperTags && parent.text().isBlank()) {
        val onlyThis = parent.childNodes().all { node ->
            when (node) {
                is TextNode -> node.text().isBlank()
                is Element -> node.tagName() == "br" || node == tag
                else -> false
            }
        }
        val images = parent.select("img")
        if (onlyThis || (images.size == 1 && images.first() == tag)) {
            parent.replaceWith(replacement)
            return
        }
    }
    tag.replaceWith(replacement)
}

internal fun indexImages(root: Path): Map<Path, PixelSize> {
    val sizes = linkedMapOf<Path, PixelSize>()
    Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && isImageFile(it) }.forEach { path ->
            val size = imageSize(path) ?: return@forEach
            sizes[path.toAbsolutePath().normalize()] = size
        }
    }
    return sizes
}
