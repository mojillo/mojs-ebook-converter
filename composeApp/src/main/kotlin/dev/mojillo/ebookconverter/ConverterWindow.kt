package dev.mojillo.ebookconverter

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import java.awt.Component
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

private val ink = Color(0xFF24312E)
private val paper = Color(0xFFF6F1E7)
private val accent = Color(0xFF2F6F62)

private const val aboutText =
    "Converts EPUB, FB2, MOBI, and similar books to AZW3, and removes the big divider images that force a Kindle to full-refresh in dark mode.\n\n" +
        "This version converts EPUB, HTML, and TXT. FB2, MOBI, and AZW3 are next. Original files are left untouched."

@Composable
fun ConverterWindow(onClose: () -> Unit) {
    var theme by remember { mutableStateOf(AppSettings.theme) }
    var removeDividers by remember { mutableStateOf(AppSettings.removeDividers) }
    var format by remember { mutableStateOf(AppSettings.outputFormat) }
    var files by remember { mutableStateOf(listOf<Path>()) }
    var lines by remember { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var hovering by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var lastOutputDir by remember { mutableStateOf<Path?>(null) }

    fun addPaths(paths: List<Path>) {
        val expanded = paths.flatMap { path ->
            when {
                Files.isRegularFile(path) -> listOf(path)
                Files.isDirectory(path) -> Files.list(path).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.toList()
                }
                else -> emptyList()
            }
        }
        files = (files + expanded).distinct()
    }

    fun chooseFiles(parent: Component) {
        val chooser = JFileChooser()
        chooser.isMultiSelectionEnabled = true
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            addPaths(chooser.selectedFiles.map { it.toPath() })
        }
    }

    fun revealOutput() {
        val directory = lastOutputDir ?: files.firstOrNull()?.parent ?: return
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(directory.toFile())
        }
    }

    fun convert() {
        if (files.isEmpty() || busy) return
        busy = true
        lines = listOf("Converting…")
        val snapshot = files.toList()
        val selectedFormat = format
        val strip = removeDividers
        Thread {
            val results = snapshot.map { file ->
                try {
                    val result = convertBook(file, selectedFormat, strip)
                    file.parent to "${file.fileName} → ${result.output.fileName} (${dividerLabel(result.replacements)})"
                } catch (error: Exception) {
                    null to "${file.fileName} — ${error.message ?: "failed"}"
                }
            }
            SwingUtilities.invokeLater {
                lastOutputDir = results.mapNotNull { it.first }.lastOrNull()
                lines = results.map { it.second }
                busy = false
            }
        }.start()
    }

    val icon = painterResource("icon.png")
    Window(
        onCloseRequest = onClose,
        title = "mOj's ebook converter",
        state = rememberWindowState(width = 760.dp, height = 640.dp),
        icon = icon,
    ) {
        MenuBar {
            Menu("File") {
                Item("Choose Files…", onClick = { chooseFiles(window) }, enabled = !busy)
                Item("Clear List", onClick = { files = emptyList() }, enabled = files.isNotEmpty() && !busy)
                Item("Show Output Folder", onClick = { revealOutput() }, enabled = lastOutputDir != null || files.isNotEmpty())
                Separator()
                Item("Quit", onClick = onClose)
            }
            Menu("Options") {
                CheckboxItem(
                    "Remove divider images",
                    checked = removeDividers,
                    onCheckedChange = {
                        removeDividers = it
                        AppSettings.removeDividers = it
                    },
                )
                Menu("Theme") {
                    ThemeMode.entries.forEach { mode ->
                        RadioButtonItem(
                            mode.name,
                            selected = theme == mode,
                            onClick = {
                                theme = mode
                                AppSettings.theme = mode
                            },
                        )
                    }
                }
            }
            Menu("Help") {
                Item("About mOj's ebook converter", onClick = { showAbout = true })
            }
        }

        AppTheme(theme) {
            Surface(modifier = Modifier.fillMaxSize()) {
                ConverterScreen(
                    files = files,
                    format = format,
                    removeDividers = removeDividers,
                    lines = lines,
                    busy = busy,
                    hovering = hovering,
                    onFormat = {
                        format = it
                        AppSettings.outputFormat = it
                    },
                    onRemoveDividers = {
                        removeDividers = it
                        AppSettings.removeDividers = it
                    },
                    onRemoveFile = { path -> files = files.filterNot { it == path } },
                    onChoose = { chooseFiles(window) },
                    onConvert = { convert() },
                    onDropState = { hovering = it },
                    onDrop = { addPaths(it) },
                )
            }
            if (showAbout) {
                AlertDialog(
                    onDismissRequest = { showAbout = false },
                    title = { Text("mOj's ebook converter") },
                    text = { Text(aboutText) },
                    confirmButton = {
                        TextButton(onClick = { showAbout = false }) { Text("Close") }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun ConverterScreen(
    files: List<Path>,
    format: OutputFormat,
    removeDividers: Boolean,
    lines: List<String>,
    busy: Boolean,
    hovering: Boolean,
    onFormat: (OutputFormat) -> Unit,
    onRemoveDividers: (Boolean) -> Unit,
    onRemoveFile: (Path) -> Unit,
    onChoose: () -> Unit,
    onConvert: () -> Unit,
    onDropState: (Boolean) -> Unit,
    onDrop: (List<Path>) -> Unit,
) {
    val dropTarget = remember(onDrop, onDropState) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                onDropState(true)
            }

            override fun onExited(event: DragAndDropEvent) {
                onDropState(false)
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                onDropState(false)
                val dropped = readDroppedFiles(event)
                if (dropped.isEmpty()) return false
                onDrop(dropped)
                return true
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
            .padding(24.dp),
    ) {
        Text(
            "mOj's ebook converter",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Strip divider images so a dark-mode Kindle does not full-refresh, then save a new copy.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Save as", style = MaterialTheme.typography.labelLarge)
            OutputFormat.entries.forEach { option ->
                FilterChip(
                    selected = format == option,
                    onClick = { onFormat(option) },
                    label = { Text(option.label) },
                )
            }
            Spacer(Modifier.width(8.dp))
            Checkbox(checked = removeDividers, onCheckedChange = onRemoveDividers)
            Text("Remove divider images")
        }
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(
                    width = 1.5.dp,
                    color = if (hovering) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(12.dp),
        ) {
            if (files.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Drop EPUB, HTML, or TXT files here", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onChoose) { Text("Choose files") }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(files, key = { it.toAbsolutePath().toString() }) { file ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(file.fileName.toString(), modifier = Modifier.weight(1f))
                            TextButton(onClick = { onRemoveFile(file) }, enabled = !busy) { Text("Remove") }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                if (lines.isEmpty()) {
                    Text(
                        "Originals stay put. Each result is saved beside the source as name_fixed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    lines.takeLast(4).forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Button(onClick = onConvert, enabled = files.isNotEmpty() && !busy) {
                Text(if (busy) "Converting…" else "Convert")
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun readDroppedFiles(event: DragAndDropEvent): List<Path> {
    val data = event.dragData()
    if (data !is DragData.FilesList) return emptyList()
    return data.readFiles().mapNotNull { uri ->
        runCatching { Path.of(URI.create(uri)) }.getOrNull()
    }
}

private fun dividerLabel(count: Int): String {
    return if (count == 1) "1 divider" else "$count dividers"
}

@Composable
private fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val colors = if (dark) {
        darkColorScheme(
            primary = Color(0xFF8FCBB8),
            background = Color(0xFF1A211F),
            surface = ink,
        )
    } else {
        lightColorScheme(
            primary = accent,
            background = paper,
            surface = Color.White,
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}
