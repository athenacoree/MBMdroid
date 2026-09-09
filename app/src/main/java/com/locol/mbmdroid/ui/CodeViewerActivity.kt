package com.locol.mbmdroid.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locol.mbmdroid.data.AppManager
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
class CodeViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appId = intent.getStringExtra(EXTRA_APP_ID) ?: return finish()
        val appManager = AppManager(this)
        val app = appManager.getById(appId) ?: return finish()
        val root = appManager.appDir(appId)

        setContent {
            MaterialTheme {
                var selectedFile by remember { mutableStateOf<File?>(null) }
                Scaffold(topBar = {
                    TopAppBar(title = { Text("Código / Editor - ${app.name}") })
                }) { padding ->
                    Box(Modifier.padding(padding)) {
                        val current = selectedFile
                        if (current == null) {
                            FileTree(
                                root = root,
                                onFileClick = { selectedFile = it },
                                onFileCreated = { appManager.refreshFromManifest(appId) }
                            )
                        } else {
                            FileContentView(
                                file = current,
                                onBack = { selectedFile = null },
                                onSaved = { appManager.refreshFromManifest(appId) }
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_APP_ID = "extra_app_id"
    }
}

private val textExtensions = setOf("html", "htm", "css", "js", "json", "txt", "svg", "md")

@Composable
private fun FileTree(root: File, onFileClick: (File) -> Unit, onFileCreated: () -> Unit) {
    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }

    val files = remember(root, refreshKey) {
        root.walkTopDown().filter { it.isFile }
            .sortedBy { it.relativeTo(root).path }
            .toList()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Archivos de la app", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { showNewFileDialog = true }) {
                Text("+ Nuevo archivo")
            }
        }
        HorizontalDivider()
        LazyColumn {
            items(files) { file ->
                val relPath = file.relativeTo(root).path
                ListItem(
                    headlineContent = { Text(relPath) },
                    supportingContent = { Text("${file.length()} bytes") },
                    modifier = Modifier.clickable { onFileClick(file) }
                )
                HorizontalDivider()
            }
        }
    }

    if (showNewFileDialog) {
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text("Nuevo archivo") },
            text = {
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    label = { Text("Nombre / Ruta (ej. js/script.js)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newFileName.trim()
                    if (name.isNotEmpty()) {
                        val newFile = File(root, name)
                        newFile.parentFile?.mkdirs()
                        if (!newFile.exists()) {
                            newFile.createNewFile()
                        }
                        newFileName = ""
                        showNewFileDialog = false
                        refreshKey++
                        onFileCreated()
                    }
                }) { Text("Crear") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false; newFileName = "" }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun FileContentView(file: File, onBack: () -> Unit, onSaved: () -> Unit) {
    val ext = file.extension.lowercase()
    val isText = ext in textExtensions
    var contentText by remember(file) {
        mutableStateOf(if (isText) runCatching { file.readText() }.getOrElse { "" } else "")
    }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Volver") }
            if (isText) {
                Button(onClick = {
                    runCatching {
                        file.writeText(contentText)
                        saveMessage = "Guardado correctamente"
                        onSaved()
                    }.onFailure { saveMessage = "Error al guardar: ${it.message}" }
                }) {
                    Text("Guardar")
                }
            }
        }
        saveMessage?.let { msg ->
            Text(msg, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        if (isText) {
            OutlinedTextField(
                value = contentText,
                onValueChange = { contentText = it },
                modifier = Modifier.fillMaxSize(),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            )
        } else {
            Text("Archivo binario · ${file.length()} bytes · no editable como texto")
        }
    }
}
