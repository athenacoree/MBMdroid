package com.locol.mbmdroid.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locol.mbmdroid.data.AppManager
import java.io.File

/**
 * Muestra el árbol de archivos de cualquier mini-app instalada (sin importar el
 * formato: html, css, js, json, png, mp3...) y el contenido crudo de cada uno.
 * Los binarios se muestran como "archivo binario" con su tamaño, en vez de
 * intentar renderizarlos como texto.
 */
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
                    TopAppBar(title = { Text("Código de ${app.name}") })
                }) { padding ->
                    Box(Modifier.padding(padding)) {
                        val current = selectedFile
                        if (current == null) {
                            FileTree(root = root, onFileClick = { selectedFile = it })
                        } else {
                            FileContentView(file = current, onBack = { selectedFile = null })
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
private fun FileTree(root: File, onFileClick: (File) -> Unit) {
    val files = remember(root) {
        root.walkTopDown().filter { it.isFile }
            .sortedBy { it.relativeTo(root).path }
            .toList()
    }
    LazyColumn {
        items(files) { file ->
            val relPath = file.relativeTo(root).path
            ListItem(
                headlineContent = { Text(relPath) },
                supportingContent = { Text("${file.length()} bytes") },
                modifier = Modifier.clickable { onFileClick(file) }
            )
            Divider()
        }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider()
}

@Composable
private fun FileContentView(file: File, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        TextButton(onClick = onBack) { Text("← Volver") }
        val ext = file.extension.lowercase()
        if (ext in textExtensions) {
            val content = remember(file) { runCatching { file.readText() }.getOrElse { "(no se pudo leer como texto)" } }
            Text(
                text = content,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            )
        } else {
            Text("Archivo binario · ${file.length()} bytes · no se puede mostrar como texto")
        }
    }
}
