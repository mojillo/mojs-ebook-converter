package dev.mojillo.ebookconverter

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalibreTest {
    @Test
    fun findsAnExecutableAndSkipsAMissingPath() {
        val folder = createTempDirectory("calibre-bin")
        val stub = folder.resolve("ebook-convert")
        stub.writeText("#!/bin/sh\nexit 0\n")
        stub.toFile().setExecutable(true)

        val found = firstExecutable(listOf(folder.resolve("missing"), stub))

        assertEquals(stub, found)
    }

    @Test
    fun writesAzw3ThroughEbookConvertAfterRemovingDividers() {
        val folder = createTempDirectory("azw3")
        val stub = folder.resolve("ebook-convert")
        stub.writeText(
            """
            #!/bin/sh
            printf '%s\n' "$@" > "${'$'}2.args"
            printf 'azw3' > "${'$'}2"
            """.trimIndent(),
        )
        stub.toFile().setExecutable(true)
        val html = folder.resolve("chapter.html")
        html.writeText("""<html><body><img src="bar.png" width="1200" height="40" alt=""></body></html>""")

        val result = convertBook(html, OutputFormat.AZW3, removeDividers = true, ebookConvert = stub)

        assertEquals(1, result.replacements)
        assertTrue(Files.exists(result.output))
        assertEquals("azw3", result.output.readText())
        val args = folder.resolve(result.output.fileName.toString() + ".args").readText()
        assertTrue(args.contains("--output-profile"))
        assertTrue(args.contains("kindle"))
    }
}
