package dev.mojillo.ebookconverter

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeparatorTest {
    @Test
    fun removesDividerImageAndKeepsIllustration() {
        val root = createTempDirectory("separators")
        val images = root.resolve("images").createDirectories()
        Files.write(images.resolve("divider.png"), png(800, 40))
        Files.write(images.resolve("illustration.png"), png(800, 1200))
        val chapter = root.resolve("chapter.xhtml")
        val html = """
            <html xmlns="http://www.w3.org/1999/xhtml"><body>
            <p><img src="images/divider.png" alt=""/></p>
            <p><img src="images/illustration.png" alt="illustration of a harbor"/></p>
            </body></html>
        """.trimIndent()
        chapter.writeText(html)

        val (fixed, count) = processHtml(html, chapter, root, indexImages(root))

        assertEquals(1, count)
        assertTrue(fixed.contains("* * *"))
        assertTrue(fixed.contains("illustration.png"))
        assertFalse(fixed.contains("divider.png"))
    }

    @Test
    fun removesWideTallOrnament() {
        val root = createTempDirectory("ornament")
        val chapter = root.resolve("chapter.html")
        val html = """<html><body><img src="ornament.png" width="1200" height="300" alt=""></body></html>"""

        val (fixed, count) = processHtml(html, chapter, root, emptyMap())

        assertEquals(1, count)
        assertTrue(fixed.contains("* * *"))
        assertFalse(fixed.contains("ornament.png"))
    }

    @Test
    fun keepsSmallIconAndDoesNotTreatRulerAsADividerHint() {
        val root = createTempDirectory("keep")
        val chapter = root.resolve("chapter.html")
        val html = """
            <html><body>
            <p>A small mark <img src="icon.png" width="50" height="50" alt="icon"></p>
            <p><img src="ruler.png" width="100" height="200" alt="ruler"></p>
            </body></html>
        """.trimIndent()

        val (fixed, count) = processHtml(html, chapter, root, emptyMap())

        assertEquals(0, count)
        assertTrue(fixed.contains("icon.png"))
        assertTrue(fixed.contains("ruler.png"))
    }

    @Test
    fun namedSceneBreakIsRemoved() {
        val root = createTempDirectory("hint")
        val chapter = root.resolve("chapter.html")
        val html = """<html><body><img class="scene-break" src="mark.png" width="80" height="200" alt=""></body></html>"""

        val (_, count) = processHtml(html, chapter, root, emptyMap())

        assertEquals(1, count)
    }

    @Test
    fun epubConversionRemovesTheDividerAndKeepsTheIllustration() {
        val folder = createTempDirectory("book")
        val book = folder.resolve("harbor.epub")
        writeSampleEpub(book)

        val result = convertBook(book, OutputFormat.EPUB, removeDividers = true)

        assertTrue(Files.exists(result.output))
        assertEquals(1, result.replacements)
        val extracted = createTempDirectory("out-epub")
        unzipEpub(result.output, extracted)
        val chapter = readText(extracted.resolve("chapter.xhtml"))
        assertTrue(chapter.contains("* * *"))
        assertTrue(chapter.contains("illustration.png"))
        assertFalse(chapter.contains("divider.png"))
        assertTrue(Files.exists(book))
    }

    private fun writeSampleEpub(destination: java.nio.file.Path) {
        val root = createTempDirectory("src-epub")
        root.resolve("META-INF").createDirectories()
        root.resolve("images").createDirectories()
        root.resolve("mimetype").writeText("application/epub+zip")
        root.resolve("META-INF/container.xml").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
            """.trimIndent(),
        )
        root.resolve("content.opf").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Harbor</dc:title>
                <dc:language>en</dc:language>
                <dc:identifier id="uid">urn:uuid:test</dc:identifier>
              </metadata>
              <manifest>
                <item id="chap" href="chapter.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="chap"/></spine>
            </package>
            """.trimIndent(),
        )
        root.resolve("chapter.xhtml").writeText(
            """
            <html xmlns="http://www.w3.org/1999/xhtml"><body>
            <p><img src="images/divider.png" alt=""/></p>
            <p><img src="images/illustration.png" alt="illustration of a harbor"/></p>
            </body></html>
            """.trimIndent(),
        )
        Files.write(root.resolve("images/divider.png"), png(900, 36))
        Files.write(root.resolve("images/illustration.png"), png(800, 1200))
        zipEpub(root, destination)
    }

    private fun png(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(24)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(bytes)
        ByteBuffer.wrap(bytes, 16, 8).order(ByteOrder.BIG_ENDIAN).putInt(width).putInt(height)
        return bytes
    }
}
