package dev.mojillo.ebookconverter

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createTempDirectory

internal const val MIN_CHAPTER_WORDS = 300

internal const val CHAPTER_SYSTEM_PROMPT = """You are an expert LitRPG and progression fantasy chapter editor. Condense the provided chapter to approximately 30% of its original length while preserving 100% of plot continuity, mechanical progression, and key character moments.
Format every summary with:
### 1. Plot & Narrative Beats
### 2. Combat & Tactical Highlights (Omit if none)
### 3. System, Loot & Progression Log (Retain exact numbers, levels, skills, items, quests)
### 4. Cliffhanger / Next Hook"""

class ChapterCondenseException(message: String) : Exception(message)

internal data class BookSection(
    val index: Int,
    val text: String,
    val wordCount: Int,
)

internal fun interface ChatClient {
    fun complete(model: String, system: String, user: String): String
}

fun condenseBook(
    source: Path,
    model: String,
    onProgress: (String) -> Unit = {},
): Path {
    val trimmed = model.trim()
    if (trimmed.isEmpty()) {
        throw ChapterCondenseException("Enter an Ollama model name, for example gemma4:12b.")
    }
    return condenseChapters(source, trimmed, onProgress, OllamaChatClient())
}

internal fun condenseChapters(
    source: Path,
    model: String,
    onProgress: (String) -> Unit,
    chat: ChatClient,
): Path {
    if (!Files.isRegularFile(source)) {
        throw ChapterCondenseException("That file is not available.")
    }
    val extension = source.fileName.toString().substringAfterLast('.', "").lowercase()
    val sections = when (extension) {
        "epub" -> sectionsFromEpub(source)
        "html", "htm" -> listOf(sectionFromHtml(readText(source), index = 1))
        "txt" -> listOf(sectionFromText(readText(source)))
        "fb2", "mobi", "azw", "azw3" -> sectionsFromCalibre(source)
        "pdf" -> throw UnsupportedBookException("PDF is not supported.")
        else -> throw UnsupportedBookException("Unsupported file. This version condenses EPUB, HTML, and TXT.")
    }
    return writeCondensedMarkdown(source, sections, model, chat, onProgress)
}

internal fun bookSections(root: Path): List<BookSection> {
    return spineHtmlFiles(root).mapIndexed { offset, file ->
        sectionFromHtml(readText(file), offset + 1)
    }
}

internal fun wordCount(text: String): Int {
    return text.split(Regex("\\s+")).count { it.isNotEmpty() }
}

internal fun ollamaChatJson(model: String, system: String, chapter: String): String {
    return JSONObject()
        .put("model", model)
        .put("stream", false)
        .put(
            "options",
            JSONObject()
                .put("num_ctx", 32768)
                .put("temperature", 0.3),
        )
        .put(
            "messages",
            JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", chapter)),
        )
        .toString()
}

internal fun ollamaReply(responseBody: String): String {
    val json = try {
        JSONObject(responseBody)
    } catch (_: JSONException) {
        throw ChapterCondenseException("Ollama returned an unexpected response.")
    }
    val error = json.optString("error")
    if (error.isNotEmpty()) throw ChapterCondenseException(error)
    val content = json.optJSONObject("message")?.optString("content").orEmpty()
    if (content.isBlank()) throw ChapterCondenseException("Ollama returned an empty summary.")
    return content
}

internal class OllamaChatClient(
    private val endpoint: String = "http://localhost:11434/api/chat",
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) : ChatClient {
    override fun complete(model: String, system: String, user: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofMinutes(20))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(ollamaChatJson(model, system, user), StandardCharsets.UTF_8))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (_: HttpConnectTimeoutException) {
            throw ChapterCondenseException(ollamaUnreachable(endpoint))
        } catch (_: HttpTimeoutException) {
            throw ChapterCondenseException("Ollama took too long to answer. Try a shorter chapter or a faster model.")
        } catch (_: IOException) {
            throw ChapterCondenseException(ollamaUnreachable(endpoint))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ChapterCondenseException("Condensing was interrupted.")
        }
        if (response.statusCode() !in 200..299) {
            val detail = runCatching { JSONObject(response.body()).optString("error") }
                .getOrDefault("")
                .ifBlank { response.body().take(300) }
            throw ChapterCondenseException("Ollama returned ${response.statusCode()}: $detail")
        }
        return try {
            ollamaReply(response.body())
        } catch (error: ChapterCondenseException) {
            throw error
        } catch (_: JSONException) {
            throw ChapterCondenseException("Ollama returned an unexpected response.")
        }
    }
}

internal fun condensedOutputPath(source: Path): Path {
    val parent = source.parent ?: Path.of(".")
    val stem = source.fileName.toString().substringBeforeLast('.')
    var candidate = parent.resolve("${stem}_condensed.md")
    var number = 2
    while (Files.exists(candidate)) {
        candidate = parent.resolve("${stem}_condensed $number.md")
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
    return BookSection(index, text, wordCount(text))
}

private fun sectionFromText(text: String): BookSection {
    val cleaned = text.trim()
    return BookSection(1, cleaned, wordCount(cleaned))
}

private fun writeCondensedMarkdown(
    source: Path,
    sections: List<BookSection>,
    model: String,
    chat: ChatClient,
    onProgress: (String) -> Unit,
): Path {
    val chapters = sections.filter { it.wordCount >= MIN_CHAPTER_WORDS }
    if (chapters.isEmpty()) {
        throw ChapterCondenseException(
            "No section has at least $MIN_CHAPTER_WORDS words, so there is nothing to condense.",
        )
    }
    val output = condensedOutputPath(source)
    val written = StringBuilder()
    for (section in chapters) {
        onProgress("Summarizing section ${section.index}/${sections.size} (${section.wordCount} words)...")
        val summary = try {
            chat.complete(model, CHAPTER_SYSTEM_PROMPT, section.text).trim()
        } catch (error: ChapterCondenseException) {
            if (written.isNotEmpty()) Files.writeString(output, written.toString(), StandardCharsets.UTF_8)
            throw ChapterCondenseException("Section ${section.index} failed: ${error.message}")
        }
        written.append("\n\n---\n## Section ${section.index}\n\n$summary\n")
        Files.writeString(output, written.toString(), StandardCharsets.UTF_8)
    }
    return output
}

private fun ollamaUnreachable(endpoint: String): String {
    return "Could not reach Ollama at $endpoint. Start it with ollama serve, then try again."
}
