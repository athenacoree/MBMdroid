package com.locol.mbmdroid.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locol.mbmdroid.data.AppManager
import com.locol.mbmdroid.data.AppUpdater
import com.locol.mbmdroid.data.CategoryCatalog
import com.locol.mbmdroid.data.DuplicateResolution
import com.locol.mbmdroid.data.FileChangeType
import com.locol.mbmdroid.data.InstallOutcome
import com.locol.mbmdroid.data.TrashedApp
import com.locol.mbmdroid.data.UpdatePlan
import com.locol.mbmdroid.model.AppStatus
import com.locol.mbmdroid.model.MiniApp
import com.locol.mbmdroid.runtime.AppRuntimeActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val SETTINGS_APP_ID = "system.settings"
const val STORE_APP_ID = "system.store"
const val STORAGE_APP_ID = "system.storage"

private const val PREFS_NAME = "mbm_prefs"
private const val PREF_DARK_MODE = "dark_mode"

private fun Context.isDarkModePreferred(): Boolean {
    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    if (prefs.contains(PREF_DARK_MODE)) return prefs.getBoolean(PREF_DARK_MODE, false)
    val systemNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return systemNight == Configuration.UI_MODE_NIGHT_YES
}

private fun Context.setDarkModePreferred(value: Boolean) {
    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_DARK_MODE, value).apply()
}

class MainActivity : ComponentActivity() {

    private lateinit var appManager: AppManager
    private lateinit var appUpdater: AppUpdater

    private val pickZipLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onZipPicked(it) }
    }
    private var onInstallResult: ((InstallOutcome) -> Unit)? = null

    private val pickUpdateZipLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = pendingUpdateTarget
        pendingUpdateTarget = null
        if (uri != null && target != null) onUpdateZipPicked(target, uri)
    }
    private var pendingUpdateTarget: MiniApp? = null
    private var onUpdatePlanReady: ((Result<UpdatePlan>) -> Unit)? = null

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* si la niega, no verá notificaciones de alarmas/segundo plano/descargas */ }

    // ---- copia de seguridad ----
    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let { onBackupDestinationPicked(it) } }
    private var onBackupResult: ((Result<Int>) -> Unit)? = null

    private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onBackupSourcePicked(it) }
    }
    private var onRestoreResult: ((Result<Int>) -> Unit)? = null

    private fun onZipPicked(uri: Uri) {
        val outcome = appManager.install(uri)
        onInstallResult?.invoke(outcome)
    }

    private fun onUpdateZipPicked(target: MiniApp, uri: Uri) {
        androidx.lifecycle.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                appUpdater.stage(target.id, appManager.appDir(target.id), uri, target.isSystem)
            }
            onUpdatePlanReady?.invoke(result)
        }
    }

    private fun onBackupDestinationPicked(uri: Uri) {
        androidx.lifecycle.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { appManager.exportBackup(uri) }
            onBackupResult?.invoke(result)
        }
    }

    private fun onBackupSourcePicked(uri: Uri) {
        androidx.lifecycle.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { appManager.importBackup(uri) }
            onRestoreResult?.invoke(result)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appManager = AppManager(this)
        appUpdater = AppUpdater(this)

        // Apps de sistema: Ajustes, Tienda y Almacenamiento. Se instalan una sola vez desde
        // assets/system_apps/ si aún no existen; el usuario nunca las borra.
        appManager.installSystemAppFromAssets("settings", SETTINGS_APP_ID)
        appManager.installSystemAppFromAssets("store", STORE_APP_ID)
        appManager.installSystemAppFromAssets("storage", STORAGE_APP_ID)
        appManager.purgeExpiredTrash()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            var darkMode by remember { mutableStateOf(isDarkModePreferred()) }
            MaterialTheme(colorScheme = mbmColorScheme(darkMode)) {
                LauncherScreen(
                    appManager = appManager,
                    darkMode = darkMode,
                    onToggleDarkMode = {
                        darkMode = !darkMode
                        setDarkModePreferred(darkMode)
                    },
                    onInstallClick = { onResult ->
                        onInstallResult = onResult
                        pickZipLauncher.launch("application/zip")
                    },
                    onInstallWithResolution = { uri, resolution -> appManager.install(uri, resolution) },
                    onUpdateClick = { app, onPlanReady ->
                        pendingUpdateTarget = app
                        onUpdatePlanReady = onPlanReady
                        pickUpdateZipLauncher.launch("application/zip")
                    },
                    appUpdater = appUpdater,
                    onLaunchApp = { app ->
                        startActivity(
                            Intent(this, AppRuntimeActivity::class.java)
                                .putExtra(AppRuntimeActivity.EXTRA_APP_ID, app.id)
                        )
                    },
                    onOpenCode = { app ->
                        startActivity(
                            Intent(this, CodeViewerActivity::class.java)
                                .putExtra(CodeViewerActivity.EXTRA_APP_ID, app.id)
                        )
                    },
                    onExportBackup = { onResult ->
                        onBackupResult = onResult
                        val fileName = "MbMdroid_backup_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.zip"
                        exportBackupLauncher.launch(fileName)
                    },
                    onImportBackup = { onResult ->
                        onRestoreResult = onResult
                        importBackupLauncher.launch("application/zip")
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    appManager: AppManager,
    darkMode: Boolean,
    onToggleDarkMode: () -> Unit,
    onInstallClick: (onResult: (InstallOutcome) -> Unit) -> Unit,
    onInstallWithResolution: (Uri, DuplicateResolution) -> InstallOutcome,
    onUpdateClick: (MiniApp, onPlanReady: (Result<UpdatePlan>) -> Unit) -> Unit,
    appUpdater: AppUpdater,
    onLaunchApp: (MiniApp) -> Unit,
    onOpenCode: (MiniApp) -> Unit,
    onExportBackup: (onResult: (Result<Int>) -> Unit) -> Unit,
    onImportBackup: (onResult: (Result<Int>) -> Unit) -> Unit
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val allApps = remember(refreshTick) { appManager.allApps() }
    val systemApps = allApps.filter { it.isSystem }
    val userApps = allApps.filter { !it.isSystem && it.status == AppStatus.INSTALLED }
    val suspendedApps = allApps.filter { !it.isSystem && it.status == AppStatus.SUSPENDED }

    var tab by remember { mutableIntStateOf(0) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    var menuApp by remember { mutableStateOf<MiniApp?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }
    var pendingCategoryPromptFor by remember { mutableStateOf<MiniApp?>(null) }
    var updatePlanState by remember { mutableStateOf<Pair<MiniApp, UpdatePlan>?>(null) }
    var updateErrorState by remember { mutableStateOf<String?>(null) }
    var installErrorState by remember { mutableStateOf<String?>(null) }
    var duplicatePrompt by remember { mutableStateOf<InstallOutcome.DuplicateName?>(null) }
    var showTrashSheet by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun refresh() { refreshTick++ }

    fun handleOutcome(outcome: InstallOutcome) {
        when (outcome) {
            is InstallOutcome.Success -> {
                refresh()
                if (outcome.app.category == "other") pendingCategoryPromptFor = outcome.app
            }
            is InstallOutcome.Failure -> installErrorState = outcome.message
            is InstallOutcome.DuplicateName -> duplicatePrompt = outcome
        }
    }

    val categories = remember(userApps) { userApps.map { it.category }.distinct() }
    val baseList = if (tab == 0) userApps else suspendedApps
    val visibleList = baseList
        .filter { selectedCategory == null || it.category == selectedCategory }
        .filter { searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (searchActive) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Buscar app…") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("MbMdroid", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (searchActive) { searchQuery = ""; searchActive = false } else searchActive = true
                    }) {
                        Icon(if (searchActive) Icons.Default.Close else Icons.Default.Search, contentDescription = "Buscar")
                    }
                    IconButton(onClick = onToggleDarkMode) {
                        Icon(if (darkMode) Icons.Default.LightMode else Icons.Default.DarkMode, contentDescription = "Modo oscuro")
                    }
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Más opciones")
                        }
                        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Papelera (${appManager.listTrash().size})") },
                                onClick = { overflowOpen = false; showTrashSheet = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Exportar copia de seguridad") },
                                onClick = {
                                    overflowOpen = false
                                    onExportBackup { result ->
                                        backupMessage = result.fold(
                                            { n -> "Copia guardada: $n app${if (n == 1) "" else "s"}" },
                                            { e -> "No se pudo exportar: ${e.message}" }
                                        )
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Restaurar copia de seguridad") },
                                onClick = {
                                    overflowOpen = false
                                    onImportBackup { result ->
                                        backupMessage = result.fold(
                                            { n -> "Restauradas $n app${if (n == 1) "" else "s"}" },
                                            { e -> "No se pudo restaurar: ${e.message}" }
                                        )
                                        refresh()
                                    }
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Instalar app") },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = { onInstallClick { outcome -> handleOutcome(outcome) } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            // Apps de sistema: siempre visibles arriba, no dependen de tab/categoría/búsqueda
            if (systemApps.isNotEmpty() && !searchActive) {
                Text(
                    "Sistema",
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.Gray
                )
                LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    rowItems(systemApps) { app ->
                        Box(modifier = Modifier.width(72.dp)) {
                            AppTile(
                                app = app,
                                iconFile = appManager.iconFile(app),
                                onClick = { onLaunchApp(app) },
                                onLongClick = { menuApp = app }
                            )
                        }
                    }
                }
                HorizontalDivider()
            }

            if (!searchActive) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Instaladas") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Suspendidas") })
                }

                if (categories.size > 1) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems(listOf<String?>(null) + categories) { cat ->
                            FilterChip(
                                selected = selectedCategory == cat,
                                onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                                label = { Text(cat?.let { CategoryCatalog.label(it) } ?: "Todas") }
                            )
                        }
                    }
                }
            }

            if (visibleList.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            searchQuery.isNotBlank() -> "Ninguna app coincide con \"$searchQuery\""
                            tab == 0 -> "Aún no tienes apps instaladas"
                            else -> "No hay apps suspendidas"
                        },
                        color = Color.Gray
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(visibleList, key = { it.id }) { app ->
                        AppTile(
                            app = app,
                            iconFile = appManager.iconFile(app),
                            onClick = { if (app.status == AppStatus.INSTALLED) onLaunchApp(app) },
                            onLongClick = { menuApp = app }
                        )
                    }
                }
            }
        }
    }

    menuApp?.let { app ->
        AppActionsSheet(
            app = app,
            onDismiss = { menuApp = null },
            onSuspendToggle = {
                val newStatus = if (app.status == AppStatus.INSTALLED) AppStatus.SUSPENDED else AppStatus.INSTALLED
                appManager.setStatus(app.id, newStatus)
                refresh(); menuApp = null
            },
            onUninstall = {
                appManager.uninstall(app.id)
                refresh(); menuApp = null
                scope.launch {
                    val res = snackbarHostState.showSnackbar(
                        message = "\"${app.name}\" eliminada",
                        actionLabel = "Deshacer",
                        duration = SnackbarDuration.Long
                    )
                    if (res == SnackbarResult.ActionPerformed) {
                        appManager.restoreFromTrash(app.id)
                        refresh()
                    }
                }
            },
            onUpdate = {
                menuApp = null
                onUpdateClick(app) { result ->
                    result.onSuccess { plan -> updatePlanState = app to plan }
                    result.onFailure { updateErrorState = it.message ?: "No se pudo preparar la actualización" }
                }
            },
            onViewCode = { onOpenCode(app); menuApp = null }
        )
    }

    pendingCategoryPromptFor?.let { app ->
        CategoryPromptDialog(
            appName = app.name,
            onPick = { cat -> appManager.setCategory(app.id, cat); refresh(); pendingCategoryPromptFor = null },
            onDismiss = { pendingCategoryPromptFor = null }
        )
    }

    updatePlanState?.let { (app, plan) ->
        UpdateDiffDialog(
            app = app,
            plan = plan,
            onApply = {
                appUpdater.apply(appManager.appDir(app.id), plan)
                appManager.refreshFromManifest(app.id)
                com.locol.mbmdroid.runtime.RuntimeRegistry.destroy(app.id)
                updatePlanState = null
                refresh()
            },
            onCancel = { appUpdater.discard(plan); updatePlanState = null }
        )
    }

    updateErrorState?.let { message ->
        AlertDialog(
            onDismissRequest = { updateErrorState = null },
            title = { Text("No se pudo actualizar") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { updateErrorState = null }) { Text("OK") } }
        )
    }

    installErrorState?.let { message ->
        AlertDialog(
            onDismissRequest = { installErrorState = null },
            title = { Text("No se pudo instalar") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { installErrorState = null }) { Text("OK") } }
        )
    }

    duplicatePrompt?.let { dup ->
        AlertDialog(
            onDismissRequest = { duplicatePrompt = null },
            title = { Text("Ya tienes una app llamada \"${dup.existing.name}\"") },
            text = { Text("¿Qué quieres hacer con el paquete nuevo?") },
            confirmButton = {
                TextButton(onClick = {
                    duplicatePrompt = null
                    handleOutcome(onInstallWithResolution(dup.zipUri, DuplicateResolution.REPLACE))
                }) { Text("Reemplazar") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        duplicatePrompt = null
                        handleOutcome(onInstallWithResolution(dup.zipUri, DuplicateResolution.KEEP_BOTH))
                    }) { Text("Instalar aparte") }
                    TextButton(onClick = { duplicatePrompt = null }) { Text("Cancelar") }
                }
            }
        )
    }

    backupMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { backupMessage = null },
            title = { Text("Copia de seguridad") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { backupMessage = null }) { Text("OK") } }
        )
    }

    if (showTrashSheet) {
        TrashSheet(
            trashed = appManager.listTrash(),
            onDismiss = { showTrashSheet = false },
            onRestore = { id -> appManager.restoreFromTrash(id); refresh() },
            onDeleteForever = { id -> appManager.deleteFromTrashForever(id) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashSheet(
    trashed: List<TrashedApp>,
    onDismiss: () -> Unit,
    onRestore: (String) -> Unit,
    onDeleteForever: (String) -> Unit
) {
    var localList by remember(trashed) { mutableStateOf(trashed) }
    val dateFmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Text("Papelera", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Las apps borradas se conservan aquí 7 días.", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(12.dp))
            if (localList.isEmpty()) {
                Text("La papelera está vacía.", color = Color.Gray, modifier = Modifier.padding(vertical = 24.dp))
            }
            localList.forEach { t ->
                val daysLeft = (7 - (System.currentTimeMillis() - t.deletedAt) / (24 * 60 * 60 * 1000)).coerceAtLeast(0)
                ListItem(
                    headlineContent = { Text(t.name) },
                    supportingContent = { Text("Eliminada el ${dateFmt.format(Date(t.deletedAt))} · quedan $daysLeft día${if (daysLeft == 1L) "" else "s"}") },
                    trailingContent = {
                        Row {
                            TextButton(onClick = { onRestore(t.id); localList = localList.filterNot { it.id == t.id } }) { Text("Restaurar") }
                            TextButton(onClick = { onDeleteForever(t.id); localList = localList.filterNot { it.id == t.id } }) {
                                Text("Borrar ya", color = Color.Red)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun AppTile(app: MiniApp, iconFile: File?, onClick: () -> Unit, onLongClick: () -> Unit) {
    var icon by remember(app.id) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(iconFile) {
        if (iconFile != null) {
            icon = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(iconFile.absolutePath)?.asImageBitmap() }.getOrNull()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (icon == null) mbmIconBackground(app.name) else Color.Transparent)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            val currentIcon = icon
            if (currentIcon != null) {
                Image(
                    bitmap = currentIcon,
                    contentDescription = app.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(app.name.take(1).uppercase(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            if (app.background) {
                Box(
                    modifier = Modifier.align(Alignment.BottomEnd).size(16.dp)
                        .clip(RoundedCornerShape(50)).background(Color(0xFF3D5AFE))
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(app.name, fontSize = 12.sp, maxLines = 1, modifier = Modifier.clickable(onClick = onLongClick))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppActionsSheet(
    app: MiniApp,
    onDismiss: () -> Unit,
    onSuspendToggle: () -> Unit,
    onUninstall: () -> Unit,
    onUpdate: () -> Unit,
    onViewCode: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(24.dp)) {
            Text(app.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("v${app.version} · ${CategoryCatalog.label(app.category)}", color = Color.Gray, fontSize = 12.sp)
            if (app.permissions.isNotEmpty()) {
                Text("Permisos: ${app.permissions.joinToString(", ")}", fontSize = 12.sp, color = Color.Gray)
            }
            if (app.background) Text("Puede ejecutarse en segundo plano", fontSize = 12.sp, color = Color(0xFF3D5AFE))
            if (app.isSystem) Text("App de sistema — protegida", fontSize = 12.sp, color = Color(0xFFFF4081))
            Spacer(Modifier.height(16.dp))

            ListItem(headlineContent = { Text("Ver código") }, modifier = Modifier.clickable(onClick = onViewCode))
            ListItem(headlineContent = { Text("Actualizar") }, modifier = Modifier.clickable(onClick = onUpdate))
            if (!app.isSystem) {
                ListItem(
                    headlineContent = { Text(if (app.status == AppStatus.INSTALLED) "Suspender" else "Reanudar") },
                    modifier = Modifier.clickable(onClick = onSuspendToggle)
                )
                ListItem(
                    headlineContent = { Text("Desinstalar", color = Color.Red) },
                    modifier = Modifier.clickable(onClick = onUninstall)
                )
            }
        }
    }
}

@Composable
fun CategoryPromptDialog(appName: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿De qué categoría es \"$appName\"?") },
        text = {
            Column {
                CategoryCatalog.ALL.filter { it.id != "system" }.forEach { cat ->
                    ListItem(
                        headlineContent = { Text(cat.label) },
                        modifier = Modifier.clickable { onPick(cat.id) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Después") } }
    )
}

@Composable
fun UpdateDiffDialog(app: MiniApp, plan: UpdatePlan, onApply: () -> Unit, onCancel: () -> Unit) {
    val added = plan.diffs.count { it.type == FileChangeType.ADDED }
    val removed = plan.diffs.count { it.type == FileChangeType.REMOVED }
    val modified = plan.diffs.filter { it.type == FileChangeType.MODIFIED }
    val linesAdded = modified.sumOf { it.linesAdded }
    val linesRemoved = modified.sumOf { it.linesRemoved }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Actualizar ${app.name} a ${plan.newVersion}") },
        text = {
            Column(Modifier.verticalScrollWorkaround()) {
                Text("Archivos nuevos: $added · eliminados: $removed · modificados: ${modified.size}")
                Text("Líneas: +$linesAdded / -$linesRemoved", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                modified.take(20).forEach { d ->
                    Text("• ${d.path} (+${d.linesAdded}/-${d.linesRemoved})", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                if (modified.size > 20) Text("...y ${modified.size - 20} más", fontSize = 11.sp, color = Color.Gray)
            }
        },
        confirmButton = { Button(onClick = onApply) { Text("Aplicar exactamente") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } }
    )
}

@Composable
private fun Modifier.verticalScrollWorkaround(): Modifier =
    this.then(Modifier.heightIn(max = 400.dp))
        .then(Modifier.verticalScroll(rememberScrollState()))

fun mbmIconBackground(seed: String): Color {
    val hue = (seed.hashCode().mod(360)).toFloat()
    return Color.hsv(hue, 0.55f, 0.85f)
}

@Composable
fun mbmColorScheme(dark: Boolean) = if (dark) {
    darkColorScheme(primary = Color(0xFF7C8BFF), secondary = Color(0xFFFF6EA8))
} else {
    lightColorScheme(primary = Color(0xFF3D5AFE), secondary = Color(0xFFFF4081))
}
