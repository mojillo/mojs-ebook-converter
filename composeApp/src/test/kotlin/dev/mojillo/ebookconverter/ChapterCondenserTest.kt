package dev.mojillo.ebookconverter

import org.json.JSONObject
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChapterCondenserTest {
    @Test
    fun skipsShortFrontMatterAndWritesTheSpineIndex() {
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
        val chapter = List(320) { "The dragon crossed the ridge and gained a level." }.joinToString(" ")
        root.resolve("OEBPS/chapter.xhtml").writeText(
            """<html><body><h1>Chapter 1</h1><p>$chapter</p></body></html>""",
        )
        val epub = createTempDirectory("epubs").resolve("story.epub")
        zipEpub(root, epub)

        val seen = mutableListOf<String>()
        val output = condenseChapters(
            epub,
            "gemma4:12b",
            {},
            ChatClient { _, system, user ->
                assertEquals(CHAPTER_SYSTEM_PROMPT, system)
                seen += user
                "### 1. Plot & Narrative Beats\nThe dragon gained a level."
            },
        )

        assertEquals(1, seen.size)
        assertTrue(seen[0].contains("dragon"))
        assertEquals("story_condensed.md", output.fileName.toString())
        val markdown = Files.readString(output)
        assertTrue(markdown.contains("## Section 2"))
        assertFalse(markdown.contains("## Section 1"))
        assertTrue(markdown.contains("The dragon gained a level."))
    }

    @Test
    fun refusesABookWithNoLongSection() {
        val dir = createTempDirectory("short")
        val source = dir.resolve("note.txt")
        source.writeText("Too short to condense.")

        val error = assertFailsWith<ChapterCondenseException> {
            condenseChapters(source, "gemma4:12b", {}, ChatClient { _, _, _ -> "unused" })
        }

        assertTrue(error.message!!.contains("300"))
        assertFalse(Files.exists(dir.resolve("note_condensed.md")))
    }

    @Test
    fun chatJsonKeepsChapterTextIntact() {
        val chapter = "He said \"level 12\"\nand gained 3 skill points."
        val parsed = JSONObject(ollamaChatJson("gemma4:12b", CHAPTER_SYSTEM_PROMPT, chapter))

        assertEquals("gemma4:12b", parsed.getString("model"))
        assertFalse(parsed.getBoolean("stream"))
        assertEquals(32768, parsed.getJSONObject("options").getInt("num_ctx"))
        assertEquals(0.3, parsed.getJSONObject("options").getDouble("temperature"), 0.0001)
        assertEquals(chapter, parsed.getJSONArray("messages").getJSONObject(1).getString("content"))
    }

    @Test
    fun readsTheSummaryFromAnOllamaResponse() {
        val reply = ollamaReply("""{"message":{"role":"assistant","content":"### 1. Plot & Narrative Beats\nKept."}}""")
        assertTrue(reply.contains("Kept."))

        val error = assertFailsWith<ChapterCondenseException> {
            ollamaReply("""{"error":"model 'gemma4:12b' not found"}""")
        }
        assertTrue(error.message!!.contains("not found"))
    }
}
