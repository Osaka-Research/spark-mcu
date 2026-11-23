package com.osakaresearch.spark.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import java.io.File

private val editableExtensions = setOf(
    "ino", "cpp", "cc", "cxx", "c", "h", "hpp", "s", "txt", "json", "ini",
)

private val starterSketch = """
void setup() {
  pinMode(LED_BUILTIN, OUTPUT);
}

void loop() {
  digitalWrite(LED_BUILTIN, HIGH);
  delay(500);
  digitalWrite(LED_BUILTIN, LOW);
  delay(500);
}
""".trimIndent() + "\n"

data class WorkspaceFile(val relativePath: String, val file: File)

/** A persistent, app-private Arduino sketch folder presented by [SketchWorkspaceCard]. */
@Stable
class SketchWorkspaceState internal constructor(private val context: Context) {
    private val root = File(context.filesDir, "sketches/workspace")

    var files by mutableStateOf<List<WorkspaceFile>>(emptyList())
        private set
    var selectedPath by mutableStateOf<String?>(null)
        private set
    var editorText by mutableStateOf("")
        private set
    var dirty by mutableStateOf(false)
        private set

    init {
        root.mkdirs()
        if (root.walkTopDown().none { it.isFile && isEditable(it.name) }) {
            File(root, "main.ino").writeText(starterSketch)
        }
        refresh(select = "main.ino")
    }

    fun updateEditor(value: String) {
        editorText = value
        dirty = true
    }

    fun select(relativePath: String) {
        if (relativePath == selectedPath) return
        save()
        val entry = files.firstOrNull { it.relativePath == relativePath } ?: return
        selectedPath = entry.relativePath
        editorText = runCatching { entry.file.readText() }.getOrDefault("")
        dirty = false
    }

    fun save(): Boolean {
        val path = selectedPath ?: return false
        val destination = safeFile(path) ?: return false
        return runCatching {
            destination.parentFile?.mkdirs()
            destination.writeText(editorText)
            dirty = false
            refresh(select = path, preserveEditor = true)
            true
        }.getOrDefault(false)
    }

    fun create(relativePath: String): String {
        save()
        val cleaned = sanitizeRelativePath(relativePath)
            ?: return "Use a file name such as main.ino or src/sensor.cpp"
        val destination = safeFile(cleaned) ?: return "That path is not allowed"
        if (destination.exists()) return "$cleaned already exists"
        destination.parentFile?.mkdirs()
        destination.writeText(defaultContentsFor(destination.extension))
        refresh(select = cleaned)
        return "created $cleaned"
    }

    fun deleteSelected(): String {
        val path = selectedPath ?: return "no file selected"
        val destination = safeFile(path) ?: return "file path is not allowed"
        if (!destination.delete()) return "could not delete $path"
        destination.parentFile?.takeIf { it != root && it.listFiles().isNullOrEmpty() }?.delete()
        refresh()
        if (files.isEmpty()) {
            File(root, "main.ino").writeText(starterSketch)
            refresh(select = "main.ino")
        }
        return "deleted $path"
    }

    fun paste(text: String): String {
        if (text.isBlank()) return "clipboard has no text"
        if (selectedPath == null) create("main.ino")
        editorText = text
        dirty = true
        return "pasted ${text.length} characters"
    }

    fun importFiles(uris: List<Uri>): String {
        if (uris.isEmpty()) return "no files selected"
        save()
        removeUntouchedStarter()
        var copied = 0
        var lastPath: String? = null
        uris.forEach { uri ->
            val name = displayName(uri)?.let(::sanitizeFileName) ?: return@forEach
            if (!isEditable(name)) return@forEach
            val destination = File(root, name)
            val ok = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                } ?: error("cannot open")
            }.isSuccess
            if (ok) {
                copied++
                lastPath = name
            }
        }
        refresh(select = lastPath)
        return if (copied > 0) "imported $copied file${if (copied == 1) "" else "s"}"
        else "no supported code files found"
    }

    fun importFolder(uri: Uri): String {
        save()
        val tree = DocumentFile.fromTreeUri(context, uri) ?: return "could not open folder"
        val stage = File(context.cacheDir, "workspace-import").apply {
            deleteRecursively()
            mkdirs()
        }
        val count = copyTree(tree, stage)
        if (count == 0) {
            stage.deleteRecursively()
            return "folder has no supported code files"
        }
        root.deleteRecursively()
        root.mkdirs()
        stage.copyRecursively(root, overwrite = true)
        stage.deleteRecursively()
        refresh(select = preferredPrimary()?.relativeTo(root)?.invariantSeparatorsPath)
        return "opened project folder with $count file${if (count == 1) "" else "s"}"
    }

    /** Saves the editor and returns the Arduino primary file for the existing builder. */
    fun primarySketch(): File? {
        save()
        return preferredPrimary()
    }

    private fun preferredPrimary(): File? {
        val inos = root.listFiles()
            ?.filter { it.isFile && it.extension.equals("ino", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        return inos.firstOrNull { it.nameWithoutExtension.equals(root.name, ignoreCase = true) }
            ?: inos.firstOrNull { it.name.equals("main.ino", ignoreCase = true) }
            ?: inos.firstOrNull()
    }

    private fun refresh(select: String? = null, preserveEditor: Boolean = false) {
        files = root.walkTopDown()
            .filter { it.isFile && isEditable(it.name) }
            .map { WorkspaceFile(it.relativeTo(root).invariantSeparatorsPath, it) }
            .sortedWith(compareBy<WorkspaceFile>({ it.relativePath.count { c -> c == '/' } }, { it.relativePath.lowercase() }))
            .toList()
        val next = select?.takeIf { candidate -> files.any { it.relativePath == candidate } }
            ?: selectedPath?.takeIf { candidate -> files.any { it.relativePath == candidate } }
            ?: files.firstOrNull()?.relativePath
        if (!preserveEditor || next != selectedPath) {
            selectedPath = next
            editorText = files.firstOrNull { it.relativePath == next }
                ?.file?.let { runCatching { it.readText() }.getOrDefault("") }
                .orEmpty()
            dirty = false
        }
    }

    private fun removeUntouchedStarter() {
        val starter = File(root, "main.ino")
        if (files.size == 1 && starter.isFile && runCatching { starter.readText() }.getOrNull() == starterSketch) {
            starter.delete()
        }
    }

    private fun copyTree(from: DocumentFile, to: File): Int {
        var count = 0
        from.listFiles().forEach { child ->
            val name = child.name?.let(::sanitizeFileName) ?: return@forEach
            if (child.isDirectory) {
                val dir = File(to, name)
                val nested = copyTree(child, dir)
                if (nested > 0) {
                    dir.mkdirs()
                    count += nested
                }
            } else if (isEditable(name)) {
                to.mkdirs()
                val copied = runCatching {
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        File(to, name).outputStream().use { output -> input.copyTo(output) }
                    } ?: error("cannot open")
                }.isSuccess
                if (copied) count++
            }
        }
        return count
    }

    private fun displayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun safeFile(relativePath: String): File? {
        val candidate = File(root, relativePath).canonicalFile
        val prefix = root.canonicalPath + File.separator
        return candidate.takeIf { it.path.startsWith(prefix) }
    }
}

@Composable
fun rememberSketchWorkspaceState(): SketchWorkspaceState {
    val context = LocalContext.current.applicationContext
    return remember(context) { SketchWorkspaceState(context) }
}

@Composable
fun SketchWorkspaceCard(
    state: SketchWorkspaceState,
    enabled: Boolean,
    onStatus: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var showNewFile by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onStatus(state.importFiles(uris))
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onStatus(state.importFolder(uri))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Project files", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Stored on this device · .ino, C/C++ and headers",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                if (state.dirty) {
                    Text("UNSAVED", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showNewFile = true },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("New")
                }
                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("text/*", "application/json", "application/octet-stream")) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.UploadFile, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Files")
                }
                OutlinedButton(
                    onClick = { folderPicker.launch(null) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.CreateNewFolder, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Project")
                }
            }

            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(state.files.take(40), key = { it.relativePath }) { entry ->
                    val selected = entry.relativePath == state.selectedPath
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) { state.select(entry.relativePath) },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Description,
                                contentDescription = null,
                                tint = if (entry.file.extension.equals("ino", true)) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                entry.relativePath,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
                if (state.files.size > 40) {
                    item {
                        Text("+ ${state.files.size - 40} more files", fontSize = 12.sp)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.selectedPath ?: "No file selected",
                    modifier = Modifier.weight(1f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(
                    onClick = {
                        val text = clipboard.getText()?.text.orEmpty()
                        onStatus(state.paste(text))
                    },
                    enabled = enabled,
                ) { Text("Paste") }
                IconButton(
                    onClick = {
                        onStatus(if (state.save()) "saved ${state.selectedPath}" else "save failed")
                    },
                    enabled = enabled && state.selectedPath != null,
                ) { Icon(Icons.Outlined.Save, contentDescription = "Save file") }
                IconButton(
                    onClick = { confirmDelete = true },
                    enabled = enabled && state.selectedPath != null,
                ) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete file") }
            }

            OutlinedTextField(
                value = state.editorText,
                onValueChange = state::updateEditor,
                enabled = enabled && state.selectedPath != null,
                modifier = Modifier.fillMaxWidth(),
                minLines = 10,
                maxLines = 20,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                placeholder = { Text("Paste Arduino code here") },
            )
            Text(
                "${state.editorText.lineSequence().count()} lines · Save is automatic before build or when switching files",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }

    if (showNewFile) {
        AlertDialog(
            onDismissRequest = { showNewFile = false },
            title = { Text("New project file") },
            text = {
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    singleLine = true,
                    label = { Text("Name or path") },
                    placeholder = { Text("src/sensor.cpp") },
                )
            },
            confirmButton = {
                Button(onClick = {
                    onStatus(state.create(newFileName))
                    newFileName = ""
                    showNewFile = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewFile = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${state.selectedPath}?") },
            text = { Text("This removes the file from the on-device project.") },
            confirmButton = {
                TextButton(onClick = {
                    onStatus(state.deleteSelected())
                    confirmDelete = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun isEditable(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in editableExtensions

private fun sanitizeFileName(name: String): String {
    val cleaned = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(100)
    return cleaned.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "file.txt"
}

private fun sanitizeRelativePath(path: String): String? {
    val segments = path.replace('\\', '/').split('/').filter { it.isNotBlank() }
    if (segments.isEmpty() || segments.any { it == "." || it == ".." }) return null
    val cleaned = segments.joinToString("/") { sanitizeFileName(it) }
    return cleaned.takeIf { isEditable(it) }
}

private fun defaultContentsFor(extension: String): String = when (extension.lowercase()) {
    "ino" -> "void setup() {\n  \n}\n\nvoid loop() {\n  \n}\n"
    "h", "hpp" -> "#pragma once\n"
    else -> ""
}
