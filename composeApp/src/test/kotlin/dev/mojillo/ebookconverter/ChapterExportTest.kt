package dev.mojillo.ebookconverter

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChapterExportTest {
    @Test
    fun writesOneFilePerSpineItemInASingleFolder() {
        val root = createTempDirectory("chapters")
        root.resolve("META-INF").createDirectories()
        root.resolve("OEBPS").createDirectories()
        root.resolve("META-INF/container.xml").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
            """.trimIndent(),
        )
        root.resolve("OEBPS/content.opf").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Story</dc:title>
                <dc:language>en</dc:language>
                <dc:identifier id="uid">urn:uuid:test</dc:identifier>
              </metadata>
              <manifest>
                <item id="c0" href="copyright.xhtml" media-type="application/xhtml+xml"/>
                <item id="c1" href="chapter.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="c0"/>
                <itemref idref="c1"/>
              </spine>
            </package>
            """.trimIndent(),
        )
        root.resolve("OEBPS/copyright.xhtml").writeText(
            """<html><body><p>Copyright 2024. All rights reserved.</p></body></html>""",
        )
        root.resolve("OEBPS/chapter.xhtml").writeText(
            """<html><body><h1>The Ridge: "West"</h1><p>The dragon crossed the ridge.</p></body></html>""",
        )
        val epub = createTempDirectory("epubs").resolve("story.epub")
        zipEpub(root, epub)

        val folder = exportChapters(epub)
        val names = Files.list(folder).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }

        assertEquals("story_chapters", folder.fileName.toString())
        assertEquals(listOf("001.txt", "002 The Ridge West.txt"), names)
        assertTrue(Files.readString(folder.resolve("002 The Ridge West.txt")).contains("dragon"))
        assertFalse(Files.exists(epub.parent.resolve("story_condensed.md")))
    }

    @Test
    fun namesASecondFolderWhenTheFirstAlreadyExists() {
        val dir = createTempDirectory("again")
        val source = dir.resolve("note.txt")
        source.writeText("A short chapter stays in the export.")
        dir.resolve("note_chapters").createDirectories()

        val folder = exportChapters(source)

        assertEquals("note_chapters 2", folder.fileName.toString())
        assertEquals("001.txt", Files.list(folder).use { it.map { path -> path.fileName.toString() }.findFirst().get() })
    }

    @Test
    fun refusesABookWithNoText() {
        val dir = createTempDirectory("empty")
        val source = dir.resolve("blank.txt")
        source.writeText("   \n")

        val error = assertFailsWith<ChapterExportException> {
            exportChapters(source)
        }

        assertTrue(error.message!!.contains("no chapter text"))
        assertFalse(Files.exists(dir.resolve("blank_chapters")))
    }

    @Test
    fun fileNamesKeepTheChapterNumberWhenTheTitleIsUnsafe() {
        assertEquals("004.txt", chapterFileName(4, null))
        assertEquals("004 Gate North One.txt", chapterFileName(4, "  Gate/North: \"One\" "))
    }
}
