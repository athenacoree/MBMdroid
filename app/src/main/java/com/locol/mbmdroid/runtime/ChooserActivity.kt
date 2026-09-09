package com.locol.mbmdroid.runtime

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.locol.mbmdroid.data.AppManager
import com.locol.mbmdroid.data.CategoryCatalog
import com.locol.mbmdroid.data.DefaultAppsManager
import com.locol.mbmdroid.model.MiniApp

class ChooserActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dataUri = intent.data
        val mimeType = intent.type ?: contentResolver.getType(dataUri ?: android.net.Uri.EMPTY) ?: ""
        val category = CategoryCatalog.categoryForMimeType(mimeType)

        if (dataUri == null || category == null) {
            finish()
            return
        }

        val appManager = AppManager(this)
        val defaults = DefaultAppsManager(this)
        val candidates = appManager.listByCategory(category)

        if (candidates.isEmpty()) {
            finish()
            return
        }

        val defaultId = defaults.getDefault(category)
        val defaultApp = candidates.find { it.id == defaultId }

        if (defaultApp != null) {
            launchApp(defaultApp, dataUri, mimeType)
            finish()
            return
        }

        setContent {
            MaterialTheme {
                OpenWithDialog(
                    categoryLabel = CategoryCatalog.label(category),
                    apps = candidates,
                    onChoose = { app, remember ->
                        if (remember) defaults.setDefault(category, app.id)
                        launchApp(app, dataUri, mimeType)
                        finish()
                    },
                    onCancel = { finish() }
                )
            }
        }
    }

    private fun launchApp(app: MiniApp, uri: android.net.Uri, mimeType: String) {
        grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(
            Intent(this, AppRuntimeActivity::class.java)
                .putExtra(AppRuntimeActivity.EXTRA_APP_ID, app.id)
                .putExtra(AppRuntimeActivity.EXTRA_OPENED_URI, uri)
                .putExtra(AppRuntimeActivity.EXTRA_OPENED_MIME, mimeType)
        )
    }
}

@Composable
private fun OpenWithDialog(
    categoryLabel: String,
    apps: List<MiniApp>,
    onChoose: (MiniApp, remember: Boolean) -> Unit,
    onCancel: () -> Unit
) {
    var selected = remember { mutableStateOf(apps.first()) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Abrir con...") },
        text = {
            Column {
                Text(categoryLabel, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                apps.forEach { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected.value = app }
                            .padding(vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected.value.id == app.id, onClick = { selected.value = app })
                        Spacer(Modifier.width(8.dp))
                        Text(app.name)
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onChoose(selected.value, false) }) { Text("Solo esta vez") }
                Spacer(Modifier.width(4.dp))
                Button(onClick = { onChoose(selected.value, true) }) { Text("Siempre") }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancelar") }
        }
    )
}
